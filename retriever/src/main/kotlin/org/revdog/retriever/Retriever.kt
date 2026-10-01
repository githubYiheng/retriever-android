package org.revdog.retriever

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.StrictMode
import org.revdog.retriever.android.AndroidClock
import org.revdog.retriever.android.AndroidPlatform
import org.revdog.retriever.android.LifecycleTracker
import org.revdog.retriever.android.TaggedTransport
import org.revdog.retriever.core.Clock
import org.revdog.retriever.core.Device
import org.revdog.retriever.core.DropCounter
import org.revdog.retriever.core.HttpUrlTransport
import org.revdog.retriever.core.LineEncoder
import org.revdog.retriever.core.Markers
import org.revdog.retriever.core.Platform
import org.revdog.retriever.core.PreLog
import org.revdog.retriever.core.PreRecords
import org.revdog.retriever.core.Reentrancy
import org.revdog.retriever.core.RetrieverClient
import org.revdog.retriever.core.RootPurge
import org.revdog.retriever.core.Text
import org.revdog.retriever.core.Transport
import java.io.File

/**
 * 静态入口（方案 §3.10，三端同名）：转发到进程内共享实例。
 *
 * 目录 = `context.noBackupFilesDir/retriever/`（默认不进 Auto Backup）。**`configure` 之前没有实例**（ADR 0023）：
 * 那之前的 `log()` 只追加到 `<root>/pre/<uuid>.jsonl`（默认进程里 [RetrieverInitProvider] 在 Application.onCreate 之前就记下了
 * context），`configure` 时按本次 configure 的设定收编（过 redact、判上传义务、分 seq / oseq）。没有 context 的进程
 * （非默认进程、provider 被移除）在 configure 之前无处可写：行计数，之后以 `rtv.pre_init_dropped` 上报。
 * 公开入口绝不向宿主抛异常（ADR 0024 决定 5）：函数体整体兜住，含实参里调宿主代码的求值。
 */
public object Retriever {
    private const val DEFAULT_BASE_URL = "https://logs.revdog.org"
    private val lock = Any()

    @Volatile
    private var instance: RetrieverClient? = null

    /** 只存 Application context（拿得到时；不会泄漏 Activity）。 */
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prodEnv: Env? = null

    /** configure 之前宿主调过的 `setEnabled`（显式值；建实例时作为初值并落盘，ADR 0020 决定 2）。 */
    @Volatile
    private var pendingEnabled: Boolean? = null

    /** configure 之前禁用标记判定的短缓存（结果 + 单调时刻）：标记不存在是常态，别让每行都列一次目录。setEnabled 作废。 */
    @Volatile
    private var markerCache: Pair<Markers.State, Long>? = null

    /** 缓存有效期 1 s。 */
    private const val MARKER_CACHE_MS = 1000L

    /** 平台环境：root（null = 没有 context）、平台、时钟、传输。生产由 context 推出；测试注入。 */
    internal class Env(val root: File?, val platform: Platform, val clock: Clock, val transport: () -> Transport)

    @Volatile
    private var testEnv: Env? = null

    /** 测试：实例的内部异常出口。 */
    @Volatile
    internal var internalErrorSink: ((Throwable) -> Unit)? = null

    /**
     * 首次调用：建实例（同步 bootstrap 会话）并收编 configure 之前的行。之后的调用：同参数 = 只更新 redact；
     * `processName` 与首次不同 → 忽略这一项并留合成 warn `rtv.reconfigure_ignored`，其余参数照常生效（宿主默认在本线程上同步生效）。
     * 参数都可空：key null = ""（只写本地不上传）、baseUrl null = 默认、options null = 默认值。
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context?, key: String?, baseUrl: String? = DEFAULT_BASE_URL, options: Options? = null) {
        try {
            val opts = options ?: Options()
            val k = key ?: ""
            val base = baseUrl ?: DEFAULT_BASE_URL
            if (context != null) attach(context, false)
            synchronized(lock) {
                val e = env() ?: return
                val root = e.root ?: return
                val p = e.platform
                val token = p.allowDiskWrites()
                try {
                    val name = RetrieverClient.sanitizeProcessName(opts.processName ?: p.autoProcessName())
                    val c = instance
                    if (c != null) {
                        c.reconfigure(k, base, opts, name)
                        return
                    }
                    val pf = PreLog.handoff()
                    val client = try {
                        RetrieverClient(root, k, base, opts, e.clock, e.transport(), p, pendingEnabled, PreLog.pendingUser, pf)
                    } catch (t: Throwable) {
                        PreLog.handoffFailed()
                        throw t
                    }
                    client.onInternalError = internalErrorSink
                    instance = client
                    PreLog.configured()
                    pendingEnabled = null
                    client.start()
                } finally {
                    p.restoreDiskPolicy(token)
                }
            }
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    @JvmStatic
    public fun setUser(id: String?) {
        try {
            instance?.let {
                it.setUser(id)
                return
            }
            synchronized(lock) {
                instance?.let {
                    it.setUser(id)
                    return
                }
                // configure 之前：清洗后记内存；本进程 pre 文件已存在则写一条用户切换记录（随后创建时写在头记录之后）
                val u = Text.sanitizeUserId(id)
                val p = env()?.platform
                val token = p?.allowDiskWrites()
                try {
                    PreLog.setUser(u)
                } finally {
                    p?.restoreDiskPolicy(token)
                }
            }
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    /**
     * 记一行。`level` 为 null → 丢弃并计数；`msg` 可为 null（Java 常见的 `e.getMessage()`）：有异常时取 `error.toString()`
     * （它抛异常 → 占位串），否则空串。`error` → `exc`（type = 类名、message、stack = `printStackTrace` 文本）。绝不抛给宿主。
     */
    @JvmStatic
    @JvmOverloads
    public fun log(level: LogLevel?, msg: String?, tag: String? = null, attrs: Map<String, Any?>? = null, error: Throwable? = null) {
        try {
            val c = instance
            if (c != null) {
                c.log(level, msg, tag, attrs, error)
                return
            }
            preLog(level, msg, tag, attrs, error)
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    /**
     * 「上报问题」：15 s 内确认 → [FlushResult.Stored]，否则 [FlushResult.Pending]（原因）。回调在 SDK 后台线程上、必回、
     * 抛什么都吞掉；未 configure → `Pending("paused")`。已有等待中的 flush 且之后没有新义务行 → 挂到同一个等待上。
     */
    @JvmStatic
    @JvmOverloads
    public fun flush(includeContext: Boolean = true, callback: FlushCallback?) {
        try {
            val c = instance
            if (c != null) {
                c.flush(includeContext, callback)
                return
            }
            callBack(callback, FlushResult.Pending("paused"))
        } catch (t: Throwable) {
            callBack(callback, FlushResult.Pending("timeout"))
        }
    }

    /**
     * 用户同意 / 撤回：false 时不写、不传、不拉配置、不排后台作业（不占 seq、不计到达率）。**跨重启有效**：
     * 落盘为 `noBackupFilesDir/retriever.disabled` 标记，直到 `setEnabled(true)`。立即返回（configure 之前标记在本线程上落盘 / 删除）。
     * 同一 root 的其它进程在下一次上传 / 拉配置 / 排作业时看到标记；它们的写入要到它们自己调用或重启才停。
     * 撤回同意的完整组合 = `setEnabled(false)` + `purgeLocal()` + `setUser(null)`（purge 不清用户）。
     */
    @JvmStatic
    public fun setEnabled(enabled: Boolean) {
        try {
            instance?.let {
                it.setEnabled(enabled)
                return
            }
            synchronized(lock) {
                instance?.let {
                    it.setEnabled(enabled)
                    return
                }
                markerCache = null
                val e = env()
                val root = e?.root
                if (e == null || root == null) {
                    // 没有 context：只记内存值（建实例时作为初值并落盘）
                    pendingEnabled = enabled
                    return
                }
                val ok = allowed(e) { if (enabled) Markers.clear(root) else Markers.mark(root) }
                // 标记删不掉：不记显式 true、保持禁用（fail-closed，按盘上标记判定）；写不成：内存照样禁用（实例在引擎线程上重试）
                pendingEnabled = if (enabled && !ok) null else enabled
            }
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    /** 当前是否启用：有实例 = 本进程的开关；configure 之前 = 宿主显式值 ?? 盘上标记三态判定（判定不了 = 禁用）。 */
    @JvmStatic
    public val isEnabled: Boolean
        get() = try {
            instance?.isEnabled ?: pendingEnabled ?: env().let { e -> if (e == null) false else allowed(e) { markerState(e) } == Markers.State.ABSENT }
        } catch (t: Throwable) {
            false
        }

    /**
     * 清空本地（新 install_id）。**立即返回**，清空在 SDK 后台线程上完成：返回时 [installId] 还是旧值；
     * 要在完成时做事用 [purgeLocal]（带回调的重载）。回调之前写的行可能随清空一起删除。不改变启用状态、不清用户。
     * configure 之前：删本进程 pre 文件、清零没落盘的计数，按「改名再删」清默认 root。
     */
    @JvmStatic
    public fun purgeLocal() {
        purgeLocal(null)
    }

    /** 同 [purgeLocal]；清空完成后在 SDK 后台线程上调 [callback]（此时 [installId] 已是新值；null = 不回调）。 */
    @JvmStatic
    public fun purgeLocal(callback: Runnable?) {
        try {
            instance?.let {
                it.purgeLocal(callback)
                return
            }
            synchronized(lock) {
                instance?.let {
                    it.purgeLocal(callback)
                    return
                }
                DropCounter.reset()
                val e = env()
                val root = e?.root
                var moved: RootPurge.Result = RootPurge.Absent
                var listed: List<File> = emptyList()
                if (e != null && root != null) {
                    allowed(e) {
                        PreLog.discard()
                        moved = RootPurge.move(root)
                        // 改名失败：在锁内列出此刻的条目，后台只删这些（purge 之后新建的 pre 文件、configure 建的状态不碰）
                        if (moved == RootPurge.Failed) listed = RootPurge.snapshot(root)
                    }
                }
                val result = moved
                val entries = listed
                // 删除在后台线程上做，删完再回调：改名成功删移走的目录；改名失败退回逐项删除（同实例的 purge）
                background {
                    when (result) {
                        is RootPurge.Moved -> org.revdog.retriever.core.Fs.remove(result.dir)
                        RootPurge.Failed -> RootPurge.deleteListed(entries)
                        RootPurge.Absent -> Unit
                    }
                    try {
                        callback?.run()
                    } catch (t: Throwable) {
                        // 绝不抛给宿主
                    }
                }
            }
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    @JvmStatic
    public val installId: String?
        get() = try {
            instance?.installId
        } catch (t: Throwable) {
            null
        }

    /** install_id 前 8 位 + "-" + session_no（给客服报障用）。configure 之前为 null。 */
    @JvmStatic
    public val supportCode: String?
        get() = try {
            instance?.supportCode
        } catch (t: Throwable) {
            null
        }

    /** 生效的自动上传级别（远程配置钳制后；full_dump 期间为 debug）。configure 之前 = warn（不建任何东西）。 */
    @JvmStatic
    public val uploadLevel: LogLevel
        get() = try {
            instance?.effectiveLevels?.first ?: LogLevel.WARN
        } catch (t: Throwable) {
            LogLevel.WARN
        }

    /** 生效的本地落盘级别（远程配置钳制后）。适配器用它早过滤。configure 之前 = debug（全收）。 */
    @JvmStatic
    public val localLevel: LogLevel
        get() = try {
            instance?.effectiveLevels?.second ?: LogLevel.DEBUG
        } catch (t: Throwable) {
            LogLevel.DEBUG
        }

    // ---- 内部 ----

    /** init provider：记下 Application context，并在任何 Activity 之前注册进程级前后台 tracker（无 I/O）。 */
    internal fun attachFromProvider(context: Context) {
        attach(context, true)
    }

    private fun attach(context: Context, fromProvider: Boolean) {
        try {
            val app = context.applicationContext ?: context
            if (testEnv == null) {
                synchronized(lock) {
                    if (appContext == null || (fromProvider && appContext !is Application && app is Application)) {
                        appContext = app
                        prodEnv = null
                    }
                }
            }
            // provider：在任何 Activity 之前注册进程级 tracker；configure 在 attachBaseContext 里传 base 时，provider 稍后给出
            // Application 也在这里补注册（简报 §1.4）。configure 给的 Application 由实例创建时注册（初值取进程重要性）
            if (fromProvider) (app as? Application)?.let { a -> LifecycleTracker.shared.install(a, true) { null } }
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    /** 作业服务用：只拿已有实例（configure 之前不建；宿主在 Application.onCreate 里 configure，它先于 Service 创建）。 */
    internal fun clientFor(context: Context): RetrieverClient? {
        attach(context, false)
        return instance
    }

    internal fun currentClient(): RetrieverClient? = instance

    private fun env(): Env? {
        testEnv?.let { return it }
        prodEnv?.let { return it }
        val app = appContext ?: return null
        return synchronized(lock) {
            prodEnv ?: run {
                // noBackupFilesDir 的首次调用会 exists() / mkdir：放行 StrictMode（宿主开着 detectAll().penaltyDeath() 也不死）
                val old = StrictMode.allowThreadDiskWrites()
                try {
                    Env(File(app.noBackupFilesDir, "retriever"), AndroidPlatform(app), AndroidClock) { TaggedTransport(HttpUrlTransport()) }
                } finally {
                    StrictMode.setThreadPolicy(old)
                }
            }.also { prodEnv = it }
        }
    }

    /** 宿主线程上碰磁盘处放行 StrictMode（磁盘读写 + unbuffered IO）。 */
    private inline fun <T> allowed(e: Env, body: () -> T): T {
        val token = e.platform.allowDiskWrites()
        try {
            return body()
        } finally {
            e.platform.restoreDiskPolicy(token)
        }
    }

    /** 禁用标记三态（每次判一次：别的进程的禁用立即生效）；没有 context = 判定不了。 */
    private fun markerState(e: Env?): Markers.State {
        val root = e?.root ?: return Markers.State.UNKNOWN
        return Markers.state(root)
    }

    /** configure 之前的 log() 用：判定结果缓存 1 s（实例路径只看内存开关，不走这里）。 */
    private fun cachedMarkerState(e: Env, clock: Clock): Markers.State {
        val now = clock.monoMs()
        markerCache?.let { (s, at) -> if (now - at in 0 until MARKER_CACHE_MS) return s }
        val s = markerState(e)
        markerCache = Pair(s, now)
        return s
    }

    /**
     * configure 之前的 `log()`（ADR 0023）：编码成行体（同 LineEncoder，含截断；不含 seq / oseq），一次 write 追加到本进程 pre 文件。
     * 级别不过滤（全收）。禁用（显式 false 或标记在 / 判定不了）→ 不写不计；没有 context、超 1 MB、写失败、level 为 null → 计数。
     * fatal 只写行（不排后台作业）。与 configure 并发时：文件已交出 / 已提交 → 改走实例。
     */
    private fun preLog(level: LogLevel?, msg: String?, tag: String?, attrs: Map<String, Any?>?, error: Throwable?) {
        if (Reentrancy.active) return
        val e = env()
        val clock = e?.clock ?: AndroidClock
        val now = clock.wallMs()
        val explicit = pendingEnabled
        if (explicit == false) return
        val root = e?.root
        if (root == null) {
            // 没有 context：无处可写，只计数（标记判定不了，只有宿主显式禁用才不计）
            DropCounter.add(level, now)
            return
        }
        if (explicit == null && allowed(e) { cachedMarkerState(e, clock) } != Markers.State.ABSENT) return
        if (level == null) {
            DropCounter.add(null, now)
            return
        }
        var res = PreLog.Res.DROPPED
        Reentrancy.active = true
        try {
            val m = LineEncoder.messageOf(msg, error)
            val exc = error?.let { LineEncoder.exception(it) { t -> e.platform.stackTraceString(t) } }
            val enc = LineEncoder.encode(LogLine(now, level, m.first, tag, attrs, exc?.first), forceTruncated = m.second || exc?.second == true)
            val record = PreRecords.line(false, enc.body)
            val token = e.platform.allowDiskWrites()
            res = try {
                PreLog.appendLine(root, record) { header(e, now) }
            } finally {
                e.platform.restoreDiskPolicy(token)
            }
        } catch (t: Throwable) {
            res = PreLog.Res.DROPPED
        } finally {
            Reentrancy.active = false
        }
        when (res) {
            PreLog.Res.WRITTEN -> Unit
            PreLog.Res.DROPPED -> DropCounter.add(level, now)
            PreLog.Res.REDIRECT -> instance?.log(level, msg, tag, attrs, error) ?: DropCounter.add(level, now)
            PreLog.Res.WAIT -> {
                // 本线程就是正在 configure 的那个（宿主代码在 configure 里被回调又打日志）：等不到自己，计数返回
                if (Thread.holdsLock(lock)) {
                    DropCounter.add(level, now)
                    return
                }
                // configure 正在另一个线程上进行、本进程还没有 pre 文件：等它发布实例（宿主线程等宿主线程，不等 SDK 后台线程）
                synchronized(lock) {}
                instance?.log(level, msg, tag, attrs, error) ?: preLog(level, msg, tag, attrs, error)
            }
        }
    }

    private fun header(e: Env, now: Long): ByteArray {
        val p = e.platform
        val f = try {
            p.deviceFields()
        } catch (t: Throwable) {
            emptyMap()
        }
        val device = Device(
            f["os"] ?: "", f["os_version"] ?: "", f["model"] ?: "", f["app_version"] ?: "", f["build"] ?: "", f["locale"] ?: "",
            "retriever-android/${RetrieverVersion.CURRENT}",
        ).sanitized()
        val process = try {
            RetrieverClient.sanitizeProcessName(p.autoProcessName())
        } catch (t: Throwable) {
            "main"
        }
        return PreRecords.header(now, process, device)
    }

    private fun callBack(cb: FlushCallback?, r: FlushResult) {
        if (cb == null) return
        background {
            try {
                cb.onResult(r)
            } catch (t: Throwable) {
                // 绝不抛给宿主
            }
        }
    }

    private fun background(body: () -> Unit) {
        try {
            Thread({
                try {
                    body()
                } catch (t: Throwable) {
                    // 绝不抛给宿主
                }
            }, "retriever-callback").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            // 起不了线程：放弃
        }
    }

    // ---- 测试 ----

    /** 测试：模拟进程重启——关掉实例（不收尾）、清空静态状态；`env` 非 null 时之后用它代替 context。 */
    internal fun resetForTesting(env: Env?) {
        synchronized(lock) {
            try {
                instance?.closeForTesting()
            } catch (t: Throwable) {
                // 忽略
            }
            instance = null
            appContext = null
            prodEnv = null
            pendingEnabled = null
            markerCache = null
            testEnv = env
            internalErrorSink = null
            PreLog.resetForTesting()
            DropCounter.reset()
        }
    }

    /** 测试：只换环境（模拟「之前没有 context，现在有了」），不动计数与 pre 状态。 */
    internal fun setEnvForTesting(env: Env?) {
        synchronized(lock) {
            testEnv = env
            markerCache = null
        }
    }
}
