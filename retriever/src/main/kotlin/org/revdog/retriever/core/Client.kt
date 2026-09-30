package org.revdog.retriever.core

import org.revdog.retriever.FlushCallback
import org.revdog.retriever.FlushResult
import org.revdog.retriever.LogLevel
import org.revdog.retriever.LogLine
import org.revdog.retriever.Options
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 实例（静态 API 转发到进程内共享实例；测试直接建实例：临时目录 + 假 transport + 假时钟 + 假平台）。
 *
 * 并发模型（与 iOS RetrieverClient 同构）：
 * - 热路径 `log()` 只碰 [Writer]（ReentrantLock，锁内一次 write），不进引擎线程、不等待任何后台工作；
 * - 其余一切磁盘状态（封段、物化、出站箱、驱逐、恢复、配置、队列决策）在单线程引擎执行器上（也承担定时器）；
 * - 网络在引擎线程外：排空循环在 net 线程、配置拉取 / flush 等待 / 后台排空在 aux 线程，发送前后各回引擎线程一次。
 */
internal class RetrieverClient(
    val root: File,
    key: String,
    baseUrl: String,
    options: Options,
    val clock: Clock,
    val transport: Transport,
    val platform: Platform,
) : PlatformEventSink {
    val processName: String = sanitizeProcessName(options.processName ?: platform.autoProcessName())
    val writer = Writer(clock)
    val engine = Engine(root, processName, key, baseUrl, options, clock, platform, writer)

    private val onEngineThread = ThreadLocal<Boolean>()
    private val work = ScheduledThreadPoolExecutor(1, factory("retriever-engine", onEngineThread)).apply {
        setKeepAliveTime(KEEP_ALIVE_S, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private val net = ThreadPoolExecutor(1, 1, KEEP_ALIVE_S, TimeUnit.SECONDS, LinkedBlockingQueue(), factory("retriever-net", null))
        .apply { allowCoreThreadTimeOut(true) }
    private val aux = ThreadPoolExecutor(0, Int.MAX_VALUE, KEEP_ALIVE_S, TimeUnit.SECONDS, SynchronousQueue(), factory("retriever-aux", null))

    private val ctl = Control()

    @Volatile
    var jobId: Int = options.jobId

    /** 内部异常的出口（生产：吞掉，绝不抛给宿主；测试：记录并断言为空）。 */
    @Volatile
    var onInternalError: ((Throwable) -> Unit)? = null

    /** 锁保护的调度 / 排空 / 作业状态。 */
    class Control {
        val lock = ReentrantLock()
        var redact: ((LogLine) -> LogLine?)? = null
        var installId: String? = null
        var sessionNo: Long = 0
        var bootstrapped = false
        var nextBootstrapMono = 0L
        var draining = false
        var rekick = false
        val drainWaiters = ArrayList<CountDownLatch>()
        var lastStopReason = ""
        var lastStopWake: Long? = null
        var stopRequested = false
        var timer: ScheduledFuture<*>? = null
        var timerTarget: Long? = null
        var configFetching = false
        var tombstoneScheduled = false
        var closed = false

        /** flush 等待窗口（测试可调小）。 */
        var flushWindowMs: Long = FLUSH_WINDOW_MS
    }

    private inline fun <T> ctl(body: Control.() -> T): T = ctl.lock.withLock { ctl.body() }

    init {
        ctl.redact = options.redact
        // 同步建会话：构造返回后 log() 立即落盘
        val ok = engine.bootstrap()
        ctl {
            bootstrapped = ok
            installId = engine.install?.installId
            sessionNo = engine.current?.meta?.sessionNo ?: 0
        }
        platform.startObserving(this)
        workAsync { startup() }
    }

    // MARK: 启动（引擎线程）

    private fun startup() {
        if (!ctl { bootstrapped }) return
        engine.scanOutboxAtStartup()
        engine.recoverOldSessions()
        val prev = engine.previousAppVersion
        engine.releaseQuarantine(prev != null && prev != engine.device.appVersion)
        engine.flushTombstones()
        engine.evictIfNeeded()
        kickDrain()
        fetchConfig()
        reschedule()
    }

    // MARK: 宿主 API

    fun log(level: LogLevel, msg: String, tag: String?, attrs: Map<String, Any?>?, error: Throwable?) {
        if (Reentrancy.active) return
        Reentrancy.active = true
        try {
            logInner(level, msg, tag, attrs, error)
        } catch (t: Throwable) {
            report(t)
        } finally {
            Reentrancy.active = false
        }
    }

    private fun logInner(level: LogLevel, msg: String, tag: String?, attrs: Map<String, Any?>?, error: Throwable?) {
        if (!writer.accepts(level)) return
        if (!ctl { bootstrapped }) retryBootstrap()
        var line = LogLine(
            clock.wallMs(), level, msg, tag, attrs,
            error?.let { LineEncoder.exception(it) { t -> platform.stackTraceString(t) } },
        )
        val r = ctl { redact }
        if (r != null) {
            // 钩子抛异常：该行丢弃（宁丢不漏 PII），异常走内部出口
            line = r(line) ?: return
            if (!writer.accepts(line.level)) return
        }
        val enc = LineEncoder.encode(line)
        val token = platform.allowDiskWrites()
        val out = try {
            writer.append(line.level, enc.body)
        } finally {
            platform.restoreDiskPolicy(token)
        }
        if (out.fatal) {
            // fatal：立即封段并物化，只落盘不尝试上传；排一个后台作业兜底补传
            onWork {
                engine.processSeals()
                if (engine.hasUploadWork()) platform.scheduleUploadJob(jobId)
            }
            return
        }
        if (out.rotated) {
            workAsync { afterSeal() }
        } else if (out.deadlineChanged) {
            workAsync { reschedule() }
        }
        if (out.tombstone) {
            val schedule = ctl {
                if (tombstoneScheduled) {
                    false
                } else {
                    tombstoneScheduled = true
                    true
                }
            }
            if (schedule) {
                work.schedule({
                    guard {
                        ctl { tombstoneScheduled = false }
                        engine.flushTombstones()
                    }
                }, 1, TimeUnit.SECONDS)
            }
        }
    }

    fun setUser(id: String?) {
        safely {
            val token = platform.allowDiskWrites()
            val rotated = try {
                writer.setUser(Text.sanitizeUserId(id))
            } finally {
                platform.restoreDiskPolicy(token)
            }
            if (rotated) workAsync { afterSeal() }
        }
    }

    /**
     * 「上报问题」：向当前段追加合成行（error / tag rtv.flush / synthetic，一定是义务行），立即封段并排空。
     * 该批 15 s 内被 2xx 确认 → Stored；否则 Pending(offline | backoff | paused | timeout)；
     * setEnabled(false) 时 Pending("disabled")。includeContext == false 时该批不带 ctx。回调在 SDK 后台线程上。
     */
    fun flush(includeContext: Boolean, callback: FlushCallback) {
        try {
            if (!writer.isEnabled) {
                deliver(callback, FlushResult.Pending("disabled"))
                return
            }
            val enc = LineEncoder.encode(
                LogLine(clock.wallMs(), LogLevel.ERROR, "flush", "rtv.flush", null, null), synthetic = true,
            )
            val sid = writer.currentSessionId
            val token = platform.allowDiskWrites()
            val marker = try {
                writer.appendFlushMarker(enc.body, !includeContext)
            } finally {
                platform.restoreDiskPolicy(token)
            }
            if (marker == null) {
                deliver(callback, FlushResult.Pending("timeout"))
                return
            }
            val once = FlushOnce(callback) { report(it) }
            aux.execute {
                guard {
                    onWork { engine.processSeals() }
                    // 窗口用注入时钟计（退避 / 间隔等待）；另用真实时间兜住在途请求挂起的情况
                    val window = ctl { flushWindowMs }
                    val timer = work.schedule({ aux.execute { once.resume(FlushResult.Pending("timeout")) } }, window, TimeUnit.MILLISECONDS)
                    once.onResume = { timer.cancel(false) }
                    once.resume(flushWait(sid, marker, window, once))
                }
            }
        } catch (t: Throwable) {
            report(t)
        }
    }

    private fun flushWait(sid: String, marker: Long, window: Long, once: FlushOnce): FlushResult {
        val deadline = clock.monoMs() + window
        while (!once.done) {
            drainNow()
            if (onWork { engine.isAcked(sid, marker) }) return FlushResult.Stored
            val (reason, wake) = ctl { Pair(lastStopReason, lastStopWake) }
            val now = clock.monoMs()
            if (now < deadline && reason in WAITABLE && wake != null && wake <= deadline && !ctl { stopRequested || closed }) {
                clock.sleep(maxOf(0, wake - now))
                continue
            }
            return FlushResult.Pending(flushReason(reason))
        }
        return FlushResult.Pending("timeout")
    }

    /** 只回调一次（flush 的等待与超时兜底谁先到用谁）。 */
    class FlushOnce(private val callback: FlushCallback, private val onError: (Throwable) -> Unit) {
        private val lock = ReentrantLock()
        private var fired = false

        @Volatile
        var onResume: (() -> Unit)? = null

        val done: Boolean get() = lock.withLock { fired }

        fun resume(r: FlushResult) {
            val first = lock.withLock {
                if (fired) {
                    false
                } else {
                    fired = true
                    true
                }
            }
            if (!first) return
            onResume?.invoke()
            try {
                callback.onResult(r)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    private fun deliver(callback: FlushCallback, r: FlushResult) {
        aux.execute {
            try {
                callback.onResult(r)
            } catch (t: Throwable) {
                report(t)
            }
        }
    }

    /** 生效的上传 / 本地级别（远程配置钳制后；适配器早过滤用）。 */
    val effectiveLevels: Pair<LogLevel, LogLevel> get() = writer.levels

    fun setEnabled(enabled: Boolean) {
        safely {
            writer.setEnabled(enabled)
            workAsync {
                engine.enabled = enabled
                if (enabled) kickDrain()
            }
        }
    }

    /** 删 root 下全部内容并重建 install.json（新 install_id）与新会话。 */
    fun purgeLocal() {
        safely {
            transport.cancelAll()
            onWork {
                writer.abandonSession()
                engine.releaseUploadLock()
                engine.releaseSessionLock()
                engine.locks.close()
                for (name in Fs.list(root)) Fs.remove(File(root, name))
                engine.metas.clear()
                engine.embeddedDrops.clear()
                engine.embeddedClosed.clear()
                engine.pendingMapping = null
                engine.mapping = null
                engine.others.clear()
                engine.fails.clear()
                engine.inFlight = null
                engine.backoff = BackoffState()
                engine.ackedRanges.clear()
                engine.backfilledSegs.clear()
                engine.configCache = null
                engine.lastConfigFetchMono = null
                engine.install = null
                engine.current = null
                val ok = engine.bootstrap()
                ctl {
                    bootstrapped = ok
                    installId = engine.install?.installId
                    sessionNo = engine.current?.meta?.sessionNo ?: 0
                }
            }
            fetchConfig()
        }
    }

    val installId: String? get() = ctl { installId }

    /** install_id 前 8 位 + "-" + session_no。 */
    val supportCode: String? get() = ctl { installId?.let { it.take(8) + "-" + sessionNo } }

    // MARK: 配置变更（configure 在懒初始化之后）

    fun reconfigure(key: String, baseUrl: String, options: Options) {
        ctl { redact = options.redact }
        jobId = options.jobId
        workAsync {
            engine.key = key
            engine.baseUrl = baseUrl
            engine.host = HostDefaults.of(options)
            engine.sdkVersion = options.sdkVersion
            engine.applyEffective()
            engine.lastConfigFetchMono = null
            kickDrain()
            fetchConfig()
            reschedule()
        }
    }

    /** 旧实例收尾（进程名变化时由共享实例替换）：封段、物化、放锁、停调度。 */
    fun shutdown() {
        safely {
            writer.rotate(SealReason.SHUTDOWN)
            onWork {
                engine.processSeals()
                engine.releaseUploadLock()
                engine.releaseSessionLock()
            }
            writer.abandonSession()
            ctl {
                closed = true
                timer?.cancel(false)
                timer = null
            }
            platform.stopObserving(this)
            work.shutdown()
        }
    }

    private fun retryBootstrap() {
        val now = clock.monoMs()
        val go = ctl {
            if (bootstrapped || now < nextBootstrapMono) {
                false
            } else {
                nextBootstrapMono = now + 5000
                true
            }
        }
        if (!go) return
        workAsync {
            if (ctl { bootstrapped }) return@workAsync
            val ok = engine.bootstrap()
            ctl {
                bootstrapped = ok
                installId = engine.install?.installId
                sessionNo = engine.current?.meta?.sessionNo ?: 0
            }
            if (ok) startup()
        }
    }

    // MARK: 生命周期（§3.9）

    override fun platformEvent(event: PlatformEvent) {
        safely {
            when (event) {
                PlatformEvent.DID_ENTER_BACKGROUND -> enterBackground()
                PlatformEvent.WILL_ENTER_FOREGROUND -> {
                    ctl { stopRequested = false }
                    workAsync {
                        engine.setLastState("fg")
                        engine.applyEffective()
                        if (engine.effective.expired || engine.configPollDue(clock.monoMs())) fetchConfig()
                        kickDrain()
                        reschedule()
                    }
                }
                PlatformEvent.NETWORK_RESTORED -> {
                    // 只用于提前唤醒（不做可达性预检）：清掉退避等待，暂停（401 / 429）不动
                    workAsync {
                        engine.backoff.nextAtMonoMs = 0
                        engine.backoff.nextAtWallMs = 0
                        kickDrain()
                    }
                }
            }
        }
    }

    /**
     * 进后台：封段（段内有义务行）+ 排空；出站箱有待传批时排一个一次性 JobScheduler 作业兜底
     * （进程在后台被杀 / 冻结后，系统在有网时把我们拉起来补传）。
     */
    private fun enterBackground() {
        ctl { stopRequested = false }
        val token = platform.allowDiskWrites()
        try {
            writer.rotate(SealReason.BACKGROUND, onlyIfObligation = true)
        } finally {
            platform.restoreDiskPolicy(token)
        }
        workAsync {
            engine.setLastState("bg")
            engine.processSeals()
            if (engine.hasUploadWork()) platform.scheduleUploadJob(jobId)
        }
        aux.execute {
            guard {
                drainPatiently(BACKGROUND_BUDGET_MS)
                onWork { engine.releaseUploadLock() }
            }
        }
    }

    /** JobScheduler 作业：排空出站箱（拿 upload.lock，不与在途重复），结束时回调是否需要系统按退避重排。 */
    fun runUploadJob(budgetMs: Long, done: (Boolean) -> Unit) {
        ctl { stopRequested = false }
        try {
            aux.execute {
                var reschedule = false
                try {
                    drainPatiently(budgetMs)
                    reschedule = onWork {
                        engine.releaseUploadLock()
                        val reason = ctl { lastStopReason }
                        engine.hasUploadWork() && reason in JOB_RETRY_REASONS
                    }
                } catch (t: Throwable) {
                    report(t)
                } finally {
                    try {
                        done(reschedule)
                    } catch (t: Throwable) {
                        report(t)
                    }
                }
            }
        } catch (t: Throwable) {
            report(t)
            done(false)
        }
    }

    /** 系统停止作业：取消在途请求（不删批），让排空循环尽快退出。 */
    fun stopUploadJob() {
        safely {
            ctl { stopRequested = true }
            transport.cancelAll()
            workAsync { engine.releaseUploadLock() }
        }
    }

    // MARK: 排空

    fun afterSeal() {
        if (engine.processSeals()) kickDrain()
        reschedule()
    }

    fun kickDrain() {
        val start = ctl {
            if (closed) {
                false
            } else if (draining) {
                rekick = true
                false
            } else {
                draining = true
                true
            }
        }
        if (start) startDrainLoop()
    }

    private fun startDrainLoop() {
        try {
            net.execute { drainLoopSafe() }
        } catch (t: Throwable) {
            report(t)
            finishDrain()
        }
    }

    /** 触发一次排空并等它结束（已在排空则等下一轮结束）。 */
    fun drainNow() {
        val latch = CountDownLatch(1)
        val action = ctl {
            if (closed) {
                0
            } else {
                drainWaiters.add(latch)
                if (draining) {
                    rekick = true
                    1
                } else {
                    draining = true
                    2
                }
            }
        }
        if (action == 0) return
        if (action == 2) startDrainLoop()
        latch.await()
    }

    /** 排空直到出站箱无可发的批、或被暂停 / 退避挡住；相邻请求间隔（2 s）就地等待，最长 budgetMs。 */
    fun drainPatiently(budgetMs: Long) {
        val deadline = clock.monoMs() + budgetMs
        while (true) {
            drainNow()
            val (reason, wake) = ctl { Pair(lastStopReason, lastStopWake) }
            if (reason != "spacing" || wake == null || wake > deadline || ctl { stopRequested || closed }) return
            clock.sleep(maxOf(0, wake - clock.monoMs()))
        }
    }

    private fun drainLoopSafe() {
        try {
            drainLoop()
        } catch (t: Throwable) {
            report(t)
            try {
                onWork { engine.releaseUploadLock() }
            } catch (t2: Throwable) {
                report(t2)
            }
            finishDrain()
        }
    }

    private fun finishDrain() {
        val waiters = ctl {
            rekick = false
            draining = false
            val w = ArrayList(drainWaiters)
            drainWaiters.clear()
            w
        }
        for (w in waiters) w.countDown()
    }

    private fun drainLoop() {
        while (true) {
            while (true) {
                if (ctl { stopRequested }) {
                    ctl {
                        lastStopReason = "background_expired"
                        lastStopWake = null
                    }
                    break
                }
                val step = onWork { engine.nextSend() }
                if (step is SendStep.Stop) {
                    ctl {
                        lastStopReason = step.reason
                        lastStopWake = step.wakeMono
                    }
                    break
                }
                val send = step as SendStep.Send
                val resp = transport.send(send.request)
                val eff = onWork { engine.handleResponse(send.name, resp) }
                if (eff.fetchConfig) fetchConfig()
            }
            onWork {
                engine.releaseUploadLock()
                reschedule()
            }
            val again = ctl {
                if (rekick && !stopRequested) {
                    rekick = false
                    true
                } else {
                    false
                }
            }
            if (!again) {
                finishDrain()
                return
            }
        }
    }

    // MARK: 配置

    fun fetchConfig() {
        val go = ctl {
            if (configFetching || closed) {
                false
            } else {
                configFetching = true
                true
            }
        }
        if (!go) return
        try {
            aux.execute {
                try {
                    val req = onWork { engine.configRequest() }
                    if (req != null) {
                        val resp = transport.send(req)
                        val eff = onWork { engine.applyConfigResponse(resp) }
                        if (eff.sealed) kickDrain()
                    } else {
                        onWork { engine.lastConfigFetchMono = clock.monoMs() }
                    }
                } catch (t: Throwable) {
                    report(t)
                } finally {
                    ctl { configFetching = false }
                    workAsync { reschedule() }
                }
            }
        } catch (t: Throwable) {
            report(t)
            ctl { configFetching = false }
        }
    }

    // MARK: 调度（离线 / 退避时真正睡眠，不空转）

    fun reschedule() {
        val nowMono = clock.monoMs()
        val nowWall = clock.wallMs()
        val cands = ArrayList<Long>()
        writer.nextDeadline?.let { cands.add(it) }
        engine.uploadWakeMono()?.let { cands.add(it) }
        if (engine.key.isNotEmpty()) cands.add((engine.lastConfigFetchMono ?: nowMono) + Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        engine.configCache?.nextChangeMono(nowWall, nowMono)?.let { cands.add(it) }
        engine.nextQuarantineReleaseMono()?.let { cands.add(it) }
        val next = cands.minOrNull()
        ctl {
            if (closed) return
            if (timerTarget == next && timer != null) return
            timer?.cancel(false)
            timer = null
            timerTarget = next
            if (next == null) return
            val delay = clock.timerDelayMs(schedulerDelayMs(next, nowMono)) ?: return
            timer = work.schedule({ guard { tick() } }, delay, TimeUnit.MILLISECONDS)
        }
    }

    fun tick() {
        ctl {
            timer?.cancel(false)
            timer = null
            timerTarget = null
        }
        if (writer.checkDeadlines()) engine.processSeals()
        engine.applyEffective()
        engine.releaseQuarantine(false)
        if (engine.configPollDue(clock.monoMs())) fetchConfig()
        kickDrain()
        reschedule()
    }

    // MARK: 执行器工具

    /** 在引擎线程上同步执行（已在引擎线程上则直接执行）。 */
    fun <T> onWork(body: () -> T): T {
        if (onEngineThread.get() == true) return body()
        val f = work.submit(Callable { body() })
        try {
            return f.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun workAsync(body: () -> Unit) {
        try {
            work.execute { guard(body) }
        } catch (t: Throwable) {
            report(t)
        }
    }

    private inline fun guard(body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            report(t)
        }
    }

    private inline fun safely(body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            report(t)
        }
    }

    private fun report(t: Throwable) {
        if (t is InterruptedException) Thread.currentThread().interrupt()
        try {
            onInternalError?.invoke(t)
        } catch (e: Throwable) {
            // 出口本身失败：吞掉
        }
    }

    // MARK: 测试 / 诊断

    /** 等后台工作（引擎线程、排空、配置拉取）全部落定。 */
    fun settle() {
        repeat(400) {
            onWork {}
            if (!ctl { draining || configFetching }) {
                onWork {}
                if (!ctl { draining || configFetching }) return
            }
            if (ctl { draining }) drainNow() else Thread.sleep(1)
        }
    }

    /** 测试：推进假时钟后手动触发一次定时器。 */
    fun tickNow() {
        onWork { tick() }
        settle()
    }

    /** 测试：模拟进程死亡（不封段、不收尾；关流、放会话锁与根锁、停调度）。同 root 的新实例会把它当孤儿恢复。 */
    fun simulateCrash() {
        ctl {
            closed = true
            timer?.cancel(false)
            timer = null
        }
        onWork {
            writer.abandonSession()
            engine.releaseUploadLock()
            engine.releaseSessionLock()
            engine.locks.close()
        }
        platform.stopObserving(this)
    }

    /** 测试：停掉执行器（不收尾）。 */
    fun closeForTesting() {
        ctl {
            closed = true
            timer?.cancel(false)
            timer = null
        }
        try {
            onWork {
                writer.abandonSession()
                engine.releaseUploadLock()
                engine.releaseSessionLock()
                engine.locks.close()
            }
        } catch (t: Throwable) {
            // 已停
        }
        transport.cancelAll()
        work.shutdownNow()
        net.shutdownNow()
        aux.shutdownNow()
    }

    fun setFlushWindowForTesting(ms: Long) {
        ctl { flushWindowMs = ms }
    }

    val lastStop: Pair<String, Long?> get() = ctl { Pair(lastStopReason, lastStopWake) }

    val debugCounters: Pair<Long, Long>
        get() {
            val s = writer.snapshot
            return Pair(s.seq, s.oseq)
        }

    val debugOpenSegmentFile: File? get() = writer.currentSegmentFile

    companion object {
        const val FLUSH_WINDOW_MS = 15_000L

        /** 进后台时就地排空的预算（Android 进程进后台后通常还能跑一阵；之后交给 JobScheduler）。 */
        const val BACKGROUND_BUDGET_MS = 25_000L

        /** 一次后台作业的排空预算（系统单次作业上限约 10 min）。 */
        const val JOB_BUDGET_MS = 120_000L
        private const val KEEP_ALIVE_S = 30L
        private val WAITABLE = setOf("spacing", "backoff", "offline")
        private val JOB_RETRY_REASONS = setOf("offline", "backoff", "spacing", "locked", "background_expired")

        fun sanitizeProcessName(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '_')
            val out = Text.truncate(sb.toString(), Limits.PROCESS_BYTES).s
            return out.ifEmpty { "main" }
        }

        /** 定时器延迟 = max(next − now, 1 s)：候选已到期（如请求在途时的过去时刻）也不以 0 ms 自旋。 */
        fun schedulerDelayMs(next: Long, nowMono: Long): Long = maxOf(next - nowMono, ClientConstants.SCHEDULER_MIN_DELAY_MS)

        fun flushReason(stop: String): String = when (stop) {
            "offline" -> "offline"
            "backoff" -> "backoff"
            "paused", "upload_disabled", "not_configured", "locked", "disabled" -> "paused"
            else -> "timeout"
        }

        private fun factory(name: String, marker: ThreadLocal<Boolean>?): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { r ->
                Thread({
                    marker?.set(true)
                    r.run()
                }, "$name-${n.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
