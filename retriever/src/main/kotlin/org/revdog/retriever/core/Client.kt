package org.revdog.retriever.core

import org.revdog.retriever.FlushCallback
import org.revdog.retriever.FlushResult
import org.revdog.retriever.LogLevel
import org.revdog.retriever.LogLine
import org.revdog.retriever.Options
import java.io.File
import java.io.IOException
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 实例（静态 API 转发到进程内共享实例；测试直接建实例：临时目录 + 假 transport + 假时钟 + 假平台）。
 * 只在 `configure` 时建（ADR 0023：configure 之前没有实例）；身份（processName / root）在首次 configure 定死。
 *
 * 并发模型（与 iOS RetrieverClient 同构）：
 * - 热路径 `log()` 只碰 [Writer]（ReentrantLock，锁内一次 write），不进引擎线程、不等待任何后台工作；
 * - 其余一切磁盘状态（收编、封段、物化、出站箱、驱逐、恢复、配置、队列决策）在单线程引擎执行器上（也承担定时器）；
 * - 网络在引擎线程外：排空循环在 net 线程、配置拉取 / flush 等待 / 后台排空在 aux 线程，发送前后各回引擎线程一次；
 * - 宿主线程永不等待 SDK 后台线程（ADR 0020 决定 1）：fatal、purgeLocal 都是投递后立即返回；
 * - 任何时候不在持 SDK 锁时调宿主代码（redact、回调）。
 *
 * `initialEnabled`：`configure` 之前宿主调过的 `setEnabled`（null = 按盘上标记三态判定，未知按禁用）。
 * `initialUser`：`configure` 之前 `setUser` 的值（已清洗）。`preFile`：本进程 configure 之前写的 pre 文件（要收编）。
 * 构造完由调用方 [start]（静态入口先发布实例再 start：收编提交之后迟到的静态 `log()` 才一定看得到实例）。
 */
internal class RetrieverClient(
    val root: File,
    key: String,
    baseUrl: String,
    options: Options,
    val clock: Clock,
    val transport: Transport,
    val platform: Platform,
    initialEnabled: Boolean? = null,
    initialUser: String? = null,
    preFile: PreFile? = null,
) : PlatformEventSink {
    val processName: String = sanitizeProcessName(options.processName ?: platform.autoProcessName())
    val writer = Writer(clock, HostDefaults.of(options))
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

    /** flush 兜底计时器专用（不排在引擎线程上：引擎被堵时窗口到期照样回 Pending("timeout")）。 */
    private val timers = ScheduledThreadPoolExecutor(1, factory("retriever-timer", null)).apply {
        setKeepAliveTime(KEEP_ALIVE_S, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }

    private val ctl = Control()

    @Volatile
    var jobId: Int = options.jobId

    /** 内部异常的出口（生产：吞掉，绝不抛给宿主；测试：记录并断言为空）。 */
    @Volatile
    var onInternalError: ((Throwable) -> Unit)? = null

    /** 服务端拒绝当前 key、进入鉴权暂停时的诊断出口（静态入口接到系统日志，ADR 0025）。在排空线程上、不持任何 SDK 锁时调。 */
    @Volatile
    var onKeyRejected: ((KeyRejection) -> Unit)? = null

    /** 宿主在本实例上最后一次显式 setEnabled 的值（null = 没调过，按盘上标记判定；初值 = 构造时的 `initialEnabled`）。 */
    @Volatile
    var requestedEnabled: Boolean? = initialEnabled

    /** 禁用标记判定未知（且宿主没显式设过）：按禁用处理，每次 bootstrap 成功、每次定时唤醒重判（ADR 0024 决定 6）。 */
    @Volatile
    var markerUnknown = false
        private set

    /** 宿主线程上最近一次 configure 的参数（同参数重复 configure 判空操作用，简报 §1.3）。 */
    @Volatile
    private var cfgKey = key

    @Volatile
    private var cfgBaseUrl = baseUrl

    @Volatile
    private var cfgSdkVersion = options.sdkVersion

    /** SDK 自己取消在途请求的次数（purge、作业被停）：发请求前后不同 = 这次失败是我们取消的，不计毒批（ADR 0024 决定 10）。 */
    private val cancelEpoch = AtomicLong()

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

        /** 本进程正在跑后台作业（[runUploadJob] 开始到结束）。 */
        var jobRunning = false

        /** 系统停止了当次作业：只挡当次作业的排空，作业结束即复位（ADR 0020 决定 6）。 */
        var stopRequested = false

        /** 进行中的 purgeLocal 个数（已调用、还没做完）：> 0 时排空在每次取批前检查并退出、不拉配置（ADR 0020 决定 1）。 */
        var purging = 0
        var timer: ScheduledFuture<*>? = null
        var timerTarget: Long? = null
        var configFetching = false

        /** 身份变了而配置请求在途：在途结束后再拉一次（ADR 0019 决定 12）。 */
        var configRefetch = false
        var tombstoneScheduled = false
        var closed = false

        /** 收编没做完（pre 文件读失败）：定时重试。 */
        var adoptRetry = false

        /** 目录消失的处理已排上。 */
        var vanishScheduled = false

        /** 目录消失后的重建还没写 `rtv.root_vanished`（重建失败、等下次 bootstrap 成功）。 */
        var vanishedWarnPending = false

        /** 同 id 的后台作业是别人的：只记一次诊断。 */
        var jobConflictReported = false

        /** 收编中 fatal 上次排作业的时刻。 */
        var lastDeferredFatalJobMono: Long? = null

        /** 等待中的 flush（键 = "<session_id>:<标记 oseq>"）：其后没有新义务行的 flush 挂到同一个等待上（简报 §8）。 */
        val flushGroups = HashMap<String, FlushGroup>()

        /** flush 等待窗口（测试可调小）。 */
        var flushWindowMs: Long = FLUSH_WINDOW_MS
    }

    /** 一个等待中的 flush 标记与挂在它上面的回调。 */
    class FlushGroup {
        val waiters = ArrayList<FlushOnce>()
        var result: FlushResult? = null
    }

    private inline fun <T> ctl(body: Control.() -> T): T = ctl.lock.withLock { ctl.body() }

    init {
        ctl.redact = options.redact
        writer.onVanished = { scheduleVanished() }
        engine.onVanished = { scheduleVanished() }
        if (preFile != null) {
            writer.beginAdopt(preFile)
            engine.adoptPre = preFile.name
        }
        // 没有 pre 文件：会话直接以 configure 之前设的用户开始；有 pre 文件：用户边界由切换记录按顺序推进
        writer.initUser(initialUser, preFile == null)
        // 启用状态在同步 bootstrap 之前定下（暂存值优先，否则看盘上标记，未知按禁用），写入立即按它生效；暂存值在引擎线程上落盘
        applyEnabled(initialEnabled, engine.markerState())
        // 同步建会话：构造返回后 log() 立即落盘（收编中时先进 pre 文件）
        val ok = engine.bootstrap()
        ctl {
            bootstrapped = ok
            installId = engine.install?.installId
            sessionNo = engine.current?.meta?.sessionNo ?: 0
            if (!ok) nextBootstrapMono = clock.monoMs() + ClientConstants.ADOPT_RETRY_MS
        }
        platform.startObserving(this)
    }

    /** 实例已发布：引擎线程上先收编、再 startup；没有要收编的就当场补报没落盘的行（简报 §4.2）。 */
    fun start() {
        workAsync {
            adoptOwnPreSafely()
            startup()
        }
        if (requestedEnabled != null) workAsync { persistEnabled() }
        if (!writer.isAdopting && ctl { bootstrapped }) emitDropped()
    }

    private fun applyEnabled(explicit: Boolean?, marker: Markers.State) {
        val on = explicit ?: (marker == Markers.State.ABSENT)
        markerUnknown = explicit == null && marker == Markers.State.UNKNOWN
        writer.setEnabled(on)
        engine.enabled = on
    }

    /** 每次 bootstrap 成功后重设写入开关 = 宿主显式值 ?? 标记三态判定（ADR 0024 决定 6）。 */
    private fun rejudgeEnabled() {
        applyEnabled(requestedEnabled, engine.markerState())
    }

    // MARK: 启动（引擎线程）

    /**
     * 收编本进程 configure 之前的 pre 文件（引擎线程，先于 startup；ADR 0023）：逐条读、按文件顺序经正常追加路径写进当前会话；
     * 追到文件尾后在 writer 锁内处理追赶期间新追加的剩余记录、切到正常态、unlink（提交点）、清 meta.pre。
     * 剩余记录里有没过 redact 的行（configure 前迟到的静态 log()）就放锁再来一轮——钩子永远在锁外调。
     */
    /** 收编抛了异常也不卡住：置重试，startup 照常执行。 */
    private fun adoptOwnPreSafely() {
        try {
            adoptOwnPre()
        } catch (t: Throwable) {
            report(t)
            ctl { adoptRetry = true }
        }
    }

    private fun adoptOwnPre() {
        val pf = writer.adoptingFile ?: return
        if (!ctl { bootstrapped }) return
        // 进度（a.offset）跨重试保留：读失败 / 提交失败 / 抛异常后 5 s 接着来，不重复写已收编的记录；钩子每条记录现取
        val a = adopter ?: Adopter(writer, { ctl { redact } }, ::report).also { adopter = it }
        while (true) {
            val data = pf.readFrom(a.offset)
            if (data == null) {
                ctl { adoptRetry = true }
                return
            }
            a.feed(data, a.offset)
            // 收编中途会话目录消失：停在这里，handleVanished 重建会话后从偏移 0 重新收编进新会话
            if (a.stalled) return
            val r = writer.commitAdoption(pf, { a.offset }) { tail ->
                if (a.hasUnredacted(tail)) {
                    false
                } else {
                    a.feed(tail, a.offset, false)
                    !a.stalled
                }
            }
            if (a.stalled) return
            if (r == Writer.Commit.DONE) break
            if (r == Writer.Commit.FAILED) {
                ctl { adoptRetry = true }
                return
            }
        }
        adopter = null
        ctl { adoptRetry = false }
        engine.adoptPre = null
        engine.current?.let { engine.clearMetaPre(it) }
        emitDropped()
    }

    /** 本进程 pre 文件的收编进度（引擎线程上用）。 */
    private var adopter: Adopter? = null

    private fun startup() {
        engine.removePurgeLeftovers()
        if (!ctl { bootstrapped }) {
            // 没建成会话：定时重试（不只靠宿主再写一行）
            reschedule()
            return
        }
        engine.scanOutboxAtStartup()
        engine.adoptOrphans(ctl { redact }, ::report)
        engine.recoverOldSessions()
        val prev = engine.previousAppVersion
        engine.releaseQuarantine(prev != null && prev != engine.device.appVersion)
        // 收编出的封段（fatal 立即换段、满段、用户边界）在提交之后才物化
        engine.processSeals()
        engine.flushTombstones()
        engine.evictIfNeeded()
        kickDrain()
        fetchConfig()
        reschedule()
    }

    // MARK: 宿主 API

    /**
     * 记一行。`level` 为 null（Java 宿主把自家级别映射丢了）→ 丢弃并计数；`msg` 为 null 时有异常取 `toString()`（抛异常 → 占位串）。
     * 没落盘的行（没有会话、写 pre 失败、内部异常）计数，之后以 `rtv.pre_init_dropped` 上报（ADR 0024 决定 1）。
     */
    fun log(level: LogLevel?, msg: String?, tag: String?, attrs: Map<String, Any?>?, error: Throwable?) {
        if (Reentrancy.active) return
        Reentrancy.active = true
        val handled = BooleanArray(1)
        try {
            if (level == null) {
                handled[0] = true
                // 只计数，由下一次调度唤醒 / bootstrap / 收编提交统一上报（一条），不每条写一行合成行
                if (writer.isEnabled) DropCounter.add(null, clock.wallMs())
                return
            }
            logInner(level, msg, tag, attrs, error, handled)
        } catch (t: Throwable) {
            if (!handled[0] && level != null && writer.isEnabled) DropCounter.add(level, clock.wallMs())
            report(t)
        } finally {
            Reentrancy.active = false
        }
    }

    private fun logInner(level: LogLevel, msg: String?, tag: String?, attrs: Map<String, Any?>?, error: Throwable?, handled: BooleanArray) {
        if (!writer.accepts(level)) {
            handled[0] = true
            return
        }
        if (!ctl { bootstrapped }) retryBootstrap()
        val ts = clock.wallMs()
        val m = LineEncoder.messageOf(msg, error)
        val exc = error?.let { LineEncoder.exception(it) { t -> platform.stackTraceString(t) } }
        var line = LogLine(ts, level, m.first, tag, attrs, exc?.first)
        val r = ctl { redact }
        if (r != null) {
            // 钩子抛异常：该行丢弃（宁丢不漏 PII），异常走内部出口；返回 null = 宿主丢弃。都不计数
            val out = try {
                r(line)
            } catch (t: Throwable) {
                handled[0] = true
                report(t)
                return
            }
            if (out == null) {
                handled[0] = true
                return
            }
            // redact 不能改 ts（ADR 0024 决定 10）
            line = out.copy(ts = ts)
            if (!writer.accepts(line.level)) {
                handled[0] = true
                return
            }
        }
        val enc = LineEncoder.encode(line, forceTruncated = m.second || exc?.second == true)
        val token = platform.allowDiskWrites()
        val out = try {
            val o = writer.append(line.level, enc.body)
            handled[0] = true
            // fatal：进程随后可能立即死亡，兜底补传作业在调用线程上直接排（只有 10 s 窗口内第一条；标记的 stat 也在 StrictMode 放行范围内）。
            // 收编中的 fatal 先进 pre 文件，进程死了下次启动重新收编，作业同样有用
            if ((o.fatal || (o.deferred && line.level == LogLevel.FATAL && deferredFatalDue())) && fatalJobArmed()) scheduleJob()
            o
        } finally {
            platform.restoreDiskPolicy(token)
        }
        if (out.noSession) DropCounter.add(line.level, ts)
        if (out.fatal) {
            // fatal：调用线程上只写行与换段，封段物化投递到引擎线程、不等待（ADR 0020 决定 1）——行在 log() 返回前已交给内核，
            // 进程死了由下次启动的恢复物化出同一个 batch_id。只落盘不尝试上传
            workAsync { engine.processSeals() }
            return
        }
        afterAppend(out)
    }

    /** 写入后的跟进：封了段 → 物化排空；期限变了 → 重排定时器；有待落盘的墓碑 → 1 s 后落盘。 */
    private fun afterAppend(out: Writer.Outcome) {
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
                try {
                    work.schedule({
                        guard {
                            ctl { tombstoneScheduled = false }
                            engine.flushTombstones()
                        }
                    }, 1, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    ctl { tombstoneScheduled = false }
                    report(t)
                }
            }
        }
    }

    /** 收编中的 fatal 排作业也按 10 s 窗口节流（窗口内第一条排）。 */
    private fun deferredFatalDue(): Boolean {
        val now = clock.monoMs()
        return ctl {
            val last = lastDeferredFatalJobMono
            if (last != null && now - last < ClientConstants.FATAL_SEAL_WINDOW_MS) {
                false
            } else {
                lastDeferredFatalJobMono = now
                true
            }
        }
    }

    /** fatal 在调用线程上排后台作业的条件：key 非空、已启用（内存开关 ∧ 无标记）、远程 upload_enabled。 */
    private fun fatalJobArmed(): Boolean =
        engine.key.isNotEmpty() && writer.isEnabled && engine.effective.config.uploadEnabled && !engine.disabledMarked()

    /** 排后台作业；同 id 是别人的作业或系统拒绝 → 不替换，只记一次诊断（ADR 0024 决定 11）。 */
    private fun scheduleJob() {
        val id = jobId
        if (platform.scheduleUploadJob(id)) return
        val first = ctl {
            if (jobConflictReported) {
                false
            } else {
                jobConflictReported = true
                true
            }
        }
        if (first) report(IllegalStateException("upload job $id not scheduled: id held by another component or rejected by the system"))
    }

    /** SDK 自己的合成 warn（不经 redact；收编中进 pre 文件）。 */
    private fun syntheticWarnNow(msg: String, tag: String, attrs: Map<String, Any?>?) {
        val enc = LineEncoder.encode(LogLine(clock.wallMs(), LogLevel.WARN, msg, tag, attrs, null), synthetic = true)
        val token = platform.allowDiskWrites()
        val out = try {
            writer.append(LogLevel.WARN, enc.body)
        } finally {
            platform.restoreDiskPolicy(token)
        }
        afterAppend(out)
    }

    /**
     * 有可写的会话了（bootstrap 成功且没在收编、或收编提交后）：把没落盘的行的计数写成一条合成 warn `rtv.pre_init_dropped`
     * （attrs count / error_count / first_ts / last_ts）并清零（ADR 0024 决定 1）。禁用时不写，计数留着。
     */
    private fun emitDropped() {
        if (!writer.isEnabled || writer.isAdopting || !writer.hasSession) return
        val s = DropCounter.take() ?: return
        val attrs = linkedMapOf<String, Any?>("count" to s.count, "error_count" to s.errorCount, "first_ts" to s.firstTs, "last_ts" to s.lastTs)
        val enc = LineEncoder.encode(LogLine(clock.wallMs(), LogLevel.WARN, DropCounter.MSG, DropCounter.TAG, attrs, null), synthetic = true)
        val token = platform.allowDiskWrites()
        // 强制写入、强制义务（不受 local_level / upload_level 影响，像 flush 标记；不因它按大小换段）
        val out = try {
            writer.appendForced(LogLevel.WARN, enc.body)
        } finally {
            platform.restoreDiskPolicy(token)
        }
        if (out.noSession || (!out.written && !out.tombstone)) {
            DropCounter.putBack(s)
            return
        }
        afterAppend(out)
    }

    /** 登录 / 登出：值变化即封段；身份变了（与是否封段无关）→ 配置缓存按过期处理并立即按新身份重拉（ADR 0019 决定 12）。 */
    fun setUser(id: String?) {
        safely {
            val u = Text.sanitizeUserId(id)
            val token = platform.allowDiskWrites()
            val ch = try {
                writer.setUser(u)
            } finally {
                platform.restoreDiskPolicy(token)
            }
            if (ch.rotated) workAsync { afterSeal() }
            if (ch.changed) {
                workAsync { if (engine.identityChanged().sealed) kickDrain() }
                fetchConfig(refetch = true)
            }
        }
    }

    /**
     * 「上报问题」：向当前段追加合成行（error / tag rtv.flush / synthetic，一定是义务行），立即封段并排空。
     * 该批 15 s 内被 2xx 确认 → Stored；否则 Pending(offline | backoff | paused | timeout | disabled)。
     * 回调必回（ADR 0024 决定 5）：兜底计时器先于其它工作排上；实例关闭 / 内部异常时回 Pending("timeout")；回调抛什么都吞掉。
     * 已有等待中的 flush 且其标记之后没有新义务行 → 挂到同一个等待上，不追加标记、不换段（简报 §8）。收编中：挪到收编之后。
     */
    fun flush(includeContext: Boolean, callback: FlushCallback?) {
        val once = FlushOnce(callback) { report(it) }
        try {
            armFlushTimer(once)
            if (!writer.isEnabled) {
                resumeAsync(once, FlushResult.Pending("disabled"))
                return
            }
            if (writer.isAdopting) {
                // 标记行要排在收编出的行之后：收编是引擎线程上的第一件事，排在它后面
                workAsync { flushMarked(once, includeContext) }
                return
            }
            flushMarked(once, includeContext)
        } catch (t: Throwable) {
            report(t)
            resumeAsync(once, FlushResult.Pending("timeout"))
        }
    }

    private fun armFlushTimer(once: FlushOnce) {
        val window = ctl { flushWindowMs }
        try {
            val timer = timers.schedule({ resumeAsync(once, FlushResult.Pending("timeout")) }, window, TimeUnit.MILLISECONDS)
            once.onResume = { timer.cancel(false) }
        } catch (t: Throwable) {
            // 执行器已关：立即回
            resumeAsync(once, FlushResult.Pending("timeout"))
        }
    }

    private fun flushMarked(once: FlushOnce, includeContext: Boolean) {
        try {
            if (!writer.isEnabled) {
                resumeAsync(once, FlushResult.Pending("disabled"))
                return
            }
            // 收编还没提交（读失败 / 提交失败等着重试）：标记行不能越过还没收编的行直接写进会话
            if (writer.isAdopting) {
                resumeAsync(once, FlushResult.Pending("timeout"))
                return
            }
            val enc = LineEncoder.encode(LogLine(clock.wallMs(), LogLevel.ERROR, "flush", "rtv.flush", null, null), synthetic = true)
            val token = platform.allowDiskWrites()
            val mark = try {
                writer.appendFlushMarker(enc.body, !includeContext)
            } finally {
                platform.restoreDiskPolicy(token)
            }
            if (mark == null) {
                resumeAsync(once, FlushResult.Pending(if (writer.isEnabled) "timeout" else "disabled"))
                return
            }
            val key = "${mark.sessionId}:${mark.oseq}"
            var done: FlushResult? = null
            val start = ctl {
                val g = flushGroups[key]
                if (g != null) {
                    done = g.result
                    if (done == null) g.waiters.add(once)
                    false
                } else {
                    flushGroups[key] = FlushGroup().also { it.waiters.add(once) }
                    true
                }
            }
            val d = done
            if (d != null) {
                resumeAsync(once, d)
                return
            }
            if (!start) return
            val window = ctl { flushWindowMs }
            try {
                aux.execute {
                    var r: FlushResult = FlushResult.Pending("timeout")
                    try {
                        onWork { engine.processSeals() }
                        r = flushWait(mark.sessionId, mark.oseq, window)
                    } catch (t: Throwable) {
                        report(t)
                    } finally {
                        resolveFlush(key, mark, r)
                    }
                }
            } catch (t: Throwable) {
                report(t)
                resolveFlush(key, mark, FlushResult.Pending("timeout"))
            }
        } catch (t: Throwable) {
            report(t)
            resumeAsync(once, FlushResult.Pending("timeout"))
        }
    }

    private fun resolveFlush(key: String, mark: Writer.FlushMark, r: FlushResult) {
        val waiters = ctl {
            val g = flushGroups.remove(key)
            g?.result = r
            g?.waiters?.toList() ?: emptyList()
        }
        writer.clearFlushMarker(mark.sessionId, mark.oseq)
        for (w in waiters) w.resume(r)
    }

    private fun flushWait(sid: String, marker: Long, window: Long): FlushResult {
        val deadline = clock.monoMs() + window
        while (true) {
            if (!writer.isEnabled) return FlushResult.Pending("disabled")
            drainNow()
            if (onWork { engine.isAcked(sid, marker) }) return FlushResult.Stored
            if (!writer.isEnabled) return FlushResult.Pending("disabled")
            val (reason, wake) = ctl { Pair(lastStopReason, lastStopWake) }
            val now = clock.monoMs()
            if (now < deadline && reason in WAITABLE && wake != null && wake <= deadline && !ctl { stopRequested || closed }) {
                clock.sleep(maxOf(0, wake - now))
                continue
            }
            return FlushResult.Pending(flushReason(reason))
        }
    }

    /** 只回调一次（flush 的等待与超时兜底谁先到用谁）；回调抛任何东西都吞掉。 */
    class FlushOnce(private val callback: FlushCallback?, private val onError: (Throwable) -> Unit) {
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
            try {
                onResume?.invoke()
            } catch (t: Throwable) {
                onError(t)
            }
            try {
                callback?.onResult(r)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /** 在 SDK 后台线程上回调（aux 拒绝时另起线程）。 */
    private fun resumeAsync(once: FlushOnce, r: FlushResult) {
        try {
            aux.execute { once.resume(r) }
        } catch (t: Throwable) {
            try {
                Thread({ once.resume(r) }, "retriever-callback").apply { isDaemon = true }.start()
            } catch (t2: Throwable) {
                report(t2)
            }
        }
    }

    private fun deliver(callback: Runnable) {
        val run = Runnable {
            try {
                callback.run()
            } catch (t: Throwable) {
                report(t)
            }
        }
        try {
            aux.execute(run)
        } catch (t: Throwable) {
            try {
                Thread(run, "retriever-callback").apply { isDaemon = true }.start()
            } catch (t2: Throwable) {
                report(t2)
            }
        }
    }

    /** 生效的上传 / 本地级别（远程配置钳制后；适配器早过滤用）。 */
    val effectiveLevels: Pair<LogLevel, LogLevel> get() = writer.levels

    /**
     * 用户同意 / 撤回（ADR 0020 决定 2）：先改本进程内存开关（写入立即停 / 恢复），落盘在引擎线程上——
     * false：写 root 同级标记、取消排着的后台作业（在途的那一个请求不取消）；true：删标记后排空、拉配置，删不掉则保持禁用。
     */
    fun setEnabled(enabled: Boolean) {
        safely {
            requestedEnabled = enabled
            markerUnknown = false
            writer.setEnabled(enabled)
            workAsync { persistEnabled() }
        }
    }

    /** 本进程的启用状态（内存开关；启动时由盘上标记初始化）。 */
    val isEnabled: Boolean get() = writer.isEnabled

    /** 引擎线程：把内存开关的当前值落到盘上（连着调几次只按最后一次的值落盘）。 */
    private fun persistEnabled() {
        if (writer.isEnabled) {
            val marked = engine.disabledMarked()
            if (!engine.clearDisabledMarker()) {
                // 标记删不掉：保持禁用（宁可不传，也不误传）
                writer.setEnabled(false)
                engine.enabled = false
                return
            }
            val was = engine.enabled && !marked
            engine.enabled = true
            if (!was) {
                kickDrain()
                fetchConfig()
            }
            emitDropped()
        } else {
            engine.enabled = false
            engine.markDisabled()
            // 在跑的作业不取消：它的排空在下一次选批时停在 disabled，在途的那一个请求照常完成、不被打断
            if (!ctl { jobRunning }) platform.cancelUploadJob(jobId)
        }
        reschedule()
    }

    /**
     * 清空本地（ADR 0019 决定 9 / ADR 0020 决定 1）：调用线程上只取消在途请求并置「清空中」（排空每次取批前检查并退出），
     * 删除与重建投递到引擎线程，立即返回；做完后在 SDK 后台线程上调 [callback]（此时 installId 已是新值）。
     * 不碰禁用标记（它在 root 外面）；禁用状态下清空后不拉配置。不清用户（撤回同意 = setEnabled(false) + purgeLocal() + setUser(null)）。
     */
    fun purgeLocal(callback: Runnable?) {
        ctl { purging += 1 }
        try {
            cancelEpoch.incrementAndGet()
            transport.cancelAll()
            work.execute {
                try {
                    purgeNow()
                } catch (t: Throwable) {
                    report(t)
                } finally {
                    ctl { purging -= 1 }
                    // 新 install 的配置：有在途请求也要在它结束后再拉一次（在途的那份属于旧身份，会被丢弃）
                    fetchConfig(refetch = true)
                    callback?.let { deliver(it) }
                }
            }
        } catch (t: Throwable) {
            report(t)
            ctl { purging -= 1 }
            callback?.let { deliver(it) }
        }
    }

    /**
     * 引擎线程：root 先改名移走（与 install.json 无副本时的清空同一实现），bootstrap 新 root（新 install_id，写入切到新会话），
     * 最后才删移走的旧 root——删除只在替代者建好之后。其间 log() 照常写进旧会话（随旧 root 删掉，设计如此），不空返回。
     * 收编中：pre 文件属于本地数据，一并清掉。
     */
    private fun purgeNow() {
        writer.abortAdoption()?.unlinkAndClose()
        adopter = null
        engine.adoptPre = null
        // 清空也清零没落盘的行的计数（它们属于被清掉的本地数据）
        DropCounter.reset()
        ctl { adoptRetry = false }
        engine.releaseUploadLock()
        engine.releaseSessionLock()
        engine.locks.close()
        // 先改名再删：一步原子，中途被杀也不会新旧状态混用。改名失败（极少见，如 root 所在目录只读）时退回逐项删除——
        // 宁可失去原子性也要把本地数据清掉（与 iOS 同口径）；原地删之前先放弃当前会话，免得写入在要删的目录里再建段
        val moved = engine.moveRootAside()
        if (moved == null && root.exists()) {
            report(IOException("purgeLocal: cannot move ${root.path} aside; deleting in place"))
            writer.abandonSession()
            for (name in Fs.list(root)) Fs.remove(File(root, name))
        }
        engine.resetState()
        // bootstrap 里 writer.startSession 关掉旧会话的流、切到新会话
        val ok = engine.bootstrap()
        // 新 root 没建成：不再往旧会话写（同未 bootstrap 的状态，由 retryBootstrap 重试；期间的行计数），旧状态照删
        if (!ok) writer.abandonSession()
        ctl {
            bootstrapped = ok
            installId = engine.install?.installId
            sessionNo = engine.current?.meta?.sessionNo ?: 0
        }
        if (ok) {
            // 新 root 里没有旧会话：恢复算做完（驱逐照常）
            engine.recovered = true
            rejudgeEnabled()
            emitDropped()
        }
        moved?.let { Fs.remove(it) }
    }

    val installId: String? get() = ctl { installId }

    /** install_id 前 8 位 + "-" + session_no。 */
    val supportCode: String? get() = ctl { installId?.let { it.take(8) + "-" + sessionNo } }

    // MARK: 再次 configure（身份在首次 configure 定死，简报 §1.3）

    /**
     * 再次 configure：`processName`（已按自动名解析）与现有实例不同 → 忽略这一项并留合成 warn `rtv.reconfigure_ignored`
     * （attrs field = process_name），其余参数照常生效。同参数（key、baseUrl、宿主默认、sdkVersion、jobId 都没变）→ 只更新 redact。
     * 否则宿主线程上同步更新宿主默认并重算生效级别（之后立即写的行按新级别）；key / baseUrl / 排空 / 重拉配置在引擎线程。
     */
    fun reconfigure(key: String, baseUrl: String, options: Options, resolvedProcessName: String) {
        safely {
            ctl { redact = options.redact }
            if (resolvedProcessName != processName) {
                syntheticWarnNow(RECONFIGURE_IGNORED_MSG, RECONFIGURE_IGNORED_TAG, mapOf("field" to "process_name"))
            }
            val host = HostDefaults.of(options)
            if (key == cfgKey && baseUrl == cfgBaseUrl && host == writer.host && options.sdkVersion == cfgSdkVersion && options.jobId == jobId) {
                return@safely
            }
            cfgKey = key
            cfgBaseUrl = baseUrl
            cfgSdkVersion = options.sdkVersion
            jobId = options.jobId
            writer.host = host
            workAsync {
                engine.applyIdentity(key, baseUrl)
                engine.sdkVersion = options.sdkVersion
                engine.applyEffective()
                engine.lastConfigFetchMono = null
                kickDrain()
                // 在途请求带的是旧宿主默认 / 旧 key：它回来时按身份不符丢弃并重拉
                fetchConfig(refetch = true)
                reschedule()
            }
        }
    }

    private fun retryBootstrap() {
        val now = clock.monoMs()
        val go = ctl {
            if (bootstrapped || closed || now < nextBootstrapMono) {
                false
            } else {
                nextBootstrapMono = now + ClientConstants.ADOPT_RETRY_MS
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
            if (ok) afterRebootstrap() else reschedule()
        }
    }

    /** bootstrap 失败之后重试成功（或目录消失后重建成功）：重判开关、补写合成行、收编、startup。 */
    private fun afterRebootstrap() {
        rejudgeEnabled()
        if (ctl { vanishedWarnPending.also { vanishedWarnPending = false } }) {
            syntheticWarnNow(ROOT_VANISHED_MSG, ROOT_VANISHED_TAG, null)
        }
        adoptOwnPreSafely()
        emitDropped()
        startup()
    }

    // MARK: 目录在运行中消失（简报 §4.5）

    private fun scheduleVanished() {
        val go = ctl {
            if (vanishScheduled || closed) {
                false
            } else {
                vanishScheduled = true
                true
            }
        }
        if (go) workAsync { handleVanished() }
    }

    /**
     * 会话目录 / root 在运行中消失（换段时打不开、封段时段文件已被 unlink）：引擎线程上重新 bootstrap（新会话），
     * 写合成 warn `rtv.root_vanished`；期间的行（没有会话）按 [DropCounter] 计数，重建后随 `rtv.pre_init_dropped` 上报。
     */
    private fun handleVanished() {
        ctl { vanishScheduled = false }
        val cur = engine.current
        if (cur != null && cur.dir.isDirectory && writer.hasSession && root.isDirectory) return
        // 宿主正常写进旧会话（已被删的 inode）的行：当前 .open 段与待封段，按段的行数 / error 行数 / 首末 ts 计数（同 iOS）。
        // 收编中：那些都是收编写进去的行，pre 文件提交前完整（句柄仍有效，哪怕路径已不在）→ 新会话建好后从偏移 0 重新收编，不计数、不丢
        for (seg in writer.takeLostSegments()) {
            DropCounter.putBack(DropCounter.Snapshot(seg.lineCount.toLong(), seg.errorLines.toLong(), seg.firstTs, seg.lastTs))
        }
        if (writer.isAdopting) {
            adopter = null
            writer.resetSessionUserForReadoption()
        }
        writer.abandonSession()
        engine.releaseUploadLock()
        engine.releaseSessionLock()
        engine.locks.close()
        engine.resetState()
        ctl { vanishedWarnPending = true }
        val ok = engine.bootstrap()
        ctl {
            bootstrapped = ok
            installId = engine.install?.installId
            sessionNo = engine.current?.meta?.sessionNo ?: 0
        }
        if (!ok) {
            writer.abandonSession()
            ctl { nextBootstrapMono = clock.monoMs() + ClientConstants.ADOPT_RETRY_MS }
            reschedule()
            return
        }
        afterRebootstrap()
    }

    // MARK: 生命周期（§3.9）

    override fun platformEvent(event: PlatformEvent) {
        safely {
            when (event) {
                PlatformEvent.DID_ENTER_BACKGROUND -> enterBackground()
                PlatformEvent.WILL_ENTER_FOREGROUND -> {
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
     * （进程在后台被杀 / 冻结后，系统在有网时把我们拉起来补传）。禁用时不排作业。
     */
    private fun enterBackground() {
        val token = platform.allowDiskWrites()
        try {
            writer.rotate(SealReason.BACKGROUND, onlyIfObligation = true)
        } finally {
            platform.restoreDiskPolicy(token)
        }
        workAsync {
            engine.setLastState("bg")
            engine.processSeals()
            if (engine.hasUploadWork()) scheduleJob()
        }
        try {
            aux.execute {
                guard {
                    drainPatiently(BACKGROUND_BUDGET_MS)
                    onWork { engine.releaseUploadLock() }
                }
            }
        } catch (t: Throwable) {
            report(t)
        }
    }

    /** JobScheduler 作业：排空出站箱（拿 upload.lock，不与在途重复），结束时回调是否需要系统按退避重排。 */
    fun runUploadJob(budgetMs: Long, done: (Boolean) -> Unit) {
        ctl {
            jobRunning = true
            stopRequested = false
        }
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
                    // 停止标志只对当次作业的排空生效（ADR 0020 决定 6）：作业一结束就复位，不挡之后的排空
                    ctl {
                        jobRunning = false
                        stopRequested = false
                    }
                    try {
                        done(reschedule)
                    } catch (t: Throwable) {
                        report(t)
                    }
                }
            }
        } catch (t: Throwable) {
            report(t)
            ctl {
                jobRunning = false
                stopRequested = false
            }
            try {
                done(false)
            } catch (t2: Throwable) {
                report(t2)
            }
        }
    }

    /** 系统停止作业：挡住当次作业的排空、取消在途请求（不删批、不计毒批失败）；作业已经结束时（迟到的回调）什么都不做。 */
    fun stopUploadJob() {
        safely {
            val running = ctl {
                if (jobRunning) stopRequested = true
                jobRunning
            }
            if (running) {
                cancelEpoch.incrementAndGet()
                transport.cancelAll()
                workAsync { engine.releaseUploadLock() }
            }
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
                val halt = ctl {
                    if (purging > 0) "purging" else if (stopRequested) "background_expired" else null
                }
                if (halt != null) {
                    ctl {
                        lastStopReason = halt
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
                if (ctl { purging > 0 }) {
                    // 取批之后、发出之前清空开始了：这一批要随 root 一起清掉，不发
                    onWork { if (engine.inFlight == send.name) engine.inFlight = null }
                    ctl {
                        lastStopReason = "purging"
                        lastStopWake = null
                    }
                    break
                }
                val epoch = cancelEpoch.get()
                val resp = transport.send(send.request)
                val cancelled = resp == null && cancelEpoch.get() != epoch
                val eff = onWork { engine.handleResponse(send.name, resp, send.keyFp, send.baseUrl, cancelled) }
                // 进入鉴权暂停：引擎线程外、不持锁时出 `key_rejected`（出口抛什么都吞掉）
                eff.keyRejected?.let { r ->
                    try {
                        onKeyRejected?.invoke(r)
                    } catch (t: Throwable) {
                        // 出口本身失败：吞掉
                    }
                }
                if (eff.fetchConfig) fetchConfig()
            }
            onWork {
                engine.releaseUploadLock()
                reschedule()
            }
            val again = ctl {
                if (rekick && !stopRequested && purging == 0) {
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

    /**
     * 拉配置（同一时刻最多一个在途）。`refetch`：身份变了——有在途请求时置「待重拉」，在途结束后再拉一次；
     * 响应到达时身份已变（stale）同样再拉一次（ADR 0019 决定 12）。禁用时 [configRequest] 返回 null，不联网。
     * 清空进行中不拉（清空做完时会再调一次）。
     */
    fun fetchConfig(refetch: Boolean = false) {
        val go = ctl {
            if (closed || purging > 0) {
                false
            } else if (configFetching) {
                if (refetch) configRefetch = true
                false
            } else {
                configFetching = true
                true
            }
        }
        if (!go) return
        try {
            aux.execute {
                var again = false
                try {
                    val fetch = onWork { engine.configRequest() }
                    if (fetch != null) {
                        val resp = transport.send(fetch.request)
                        val eff = onWork { engine.applyConfigResponse(fetch, resp) }
                        if (eff.sealed) kickDrain()
                        again = eff.stale
                    } else {
                        onWork { engine.lastConfigFetchMono = clock.monoMs() }
                    }
                } catch (t: Throwable) {
                    report(t)
                } finally {
                    again = ctl {
                        configFetching = false
                        val r = (again || configRefetch) && purging == 0
                        configRefetch = false
                        r
                    }
                    if (again) fetchConfig() else workAsync { reschedule() }
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
        if (engine.key.isNotEmpty() && engine.enabled) cands.add((engine.lastConfigFetchMono ?: nowMono) + Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        // 禁用标记没写成：保证 60 s 内有一次 tick 重试（禁用时没有拉配置的 tick）
        if (engine.markerPending && !engine.enabled) cands.add(nowMono + ClientConstants.MARKER_RETRY_MS)
        // 标记判定未知 / 段读失败没推进：定时重试；收编没做完（读失败 / 提交失败）：5 s 后重试
        if (markerUnknown || engine.readRetryPending) cands.add(nowMono + ClientConstants.RETRY_MS)
        if (ctl { adoptRetry }) cands.add(nowMono + ClientConstants.ADOPT_RETRY_MS)
        // 没建成会话（bootstrap 失败 / 目录消失后重建失败）：5 s 后重试
        if (!ctl { bootstrapped }) cands.add(maxOf(nowMono, ctl { nextBootstrapMono }))
        // 恢复旧会话被跳过（拿不到 root 锁）：5 s 后重试（之前一律不驱逐）
        if (ctl { bootstrapped } && !engine.recovered) cands.add(nowMono + ClientConstants.ADOPT_RETRY_MS)
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
            timer = try {
                work.schedule({ guard { tick() } }, delay, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun tick() {
        ctl {
            timer?.cancel(false)
            timer = null
            timerTarget = null
        }
        // 没建成会话：重试 bootstrap（成功后照常 afterRebootstrap / startup）
        if (!ctl { bootstrapped }) {
            retryBootstrap()
            reschedule()
            return
        }
        // 恢复旧会话上次被跳过：重试，做完再按上限驱逐
        if (!engine.recovered) {
            engine.recoverOldSessions()
            if (engine.recovered) engine.evictIfNeeded()
        }
        // 禁用标记判定未知（宿主没显式设过）：重判（ADR 0024 决定 6）
        if (markerUnknown && requestedEnabled == null) {
            rejudgeEnabled()
            if (writer.isEnabled) emitDropped()
        }
        // 禁用标记上次没写成：每次 tick 重试
        if (engine.markerPending && !engine.enabled) engine.markDisabled()
        if (ctl { adoptRetry } && writer.isAdopting) {
            adoptOwnPreSafely()
            if (!writer.isAdopting) engine.processSeals()
        }
        if (engine.readRetryPending) engine.retryBacklog()
        // 内部异常等没落盘的行：定时唤醒时补报
        if (DropCounter.pending > 0) emitDropped()
        if (writer.checkDeadlines()) engine.processSeals()
        engine.applyEffective()
        engine.releaseQuarantine(false)
        if (engine.configPollDue(clock.monoMs())) fetchConfig()
        kickDrain()
        reschedule()
    }

    // MARK: 执行器工具

    /** 在引擎线程上同步执行（已在引擎线程上则直接执行）。只给 SDK 自己的后台线程与测试用，宿主线程上的入口一律不用它。 */
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

    /** 测试：模拟进程死亡（不封段、不收尾；关流、放会话锁与根锁与 pre 文件的锁、停调度）。同 root 的新实例会把它当孤儿恢复。 */
    fun simulateCrash() {
        ctl {
            closed = true
            timer?.cancel(false)
            timer = null
        }
        onWork {
            writer.adoptingFile?.close()
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
                writer.adoptingFile?.close()
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
        timers.shutdownNow()
        platform.stopObserving(this)
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

    val isBootstrapped: Boolean get() = ctl { bootstrapped }

    companion object {
        const val FLUSH_WINDOW_MS = 15_000L

        /** 进后台时就地排空的预算（Android 进程进后台后通常还能跑一阵；之后交给 JobScheduler）。 */
        const val BACKGROUND_BUDGET_MS = 25_000L

        /** 一次后台作业的排空预算（系统单次作业上限约 10 min）。 */
        const val JOB_BUDGET_MS = 120_000L
        private const val KEEP_ALIVE_S = 30L
        private val WAITABLE = setOf("spacing", "backoff", "offline")
        private val JOB_RETRY_REASONS = setOf("offline", "backoff", "spacing", "locked", "background_expired")

        const val RECONFIGURE_IGNORED_TAG = "rtv.reconfigure_ignored"
        const val RECONFIGURE_IGNORED_MSG = "identity field change ignored after first configure"
        const val ROOT_VANISHED_TAG = "rtv.root_vanished"
        const val ROOT_VANISHED_MSG = "session directory vanished; new session started"

        fun sanitizeProcessName(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '_')
            val out = Text.truncate(sb.toString(), Limits.PROCESS_BYTES).s
            return out.ifEmpty { "main" }
        }

        /** 定时器延迟 = max(next − now, 1 s)：候选已到期（如请求在途时的过去时刻）也不以 0 ms 自旋。 */
        fun schedulerDelayMs(next: Long, nowMono: Long): Long = maxOf(next - nowMono, ClientConstants.SCHEDULER_MIN_DELAY_MS)

        /** 排空停下的原因 → flush 的 Pending 原因（不新增公开原因值）。 */
        fun flushReason(stop: String): String = when (stop) {
            "offline" -> "offline"
            "backoff" -> "backoff"
            "disabled" -> "disabled"
            "paused", "upload_disabled", "not_configured", "locked" -> "paused"
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
