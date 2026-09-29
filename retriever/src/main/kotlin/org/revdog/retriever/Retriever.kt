package org.revdog.retriever

import android.annotation.SuppressLint
import android.content.Context
import org.revdog.retriever.android.AndroidClock
import org.revdog.retriever.android.AndroidPlatform
import org.revdog.retriever.core.HttpUrlTransport
import org.revdog.retriever.core.RetrieverClient
import java.io.File

/**
 * 静态入口（方案 §3.10，三端同名）：转发到进程内共享实例。
 *
 * 目录 = `context.noBackupFilesDir/retriever/`（默认不进 Auto Backup）。`configure` 之前的 `log()` 也落盘
 * （存储层不依赖 key）：默认进程里 [RetrieverInitProvider] 在 Application.onCreate 之前就记下了 context；
 * 其它进程在 `configure` 之前没有 context，那之前的行写不进来——非主进程请在 Application.onCreate 第一行 configure。
 */
public object Retriever {
    private const val DEFAULT_BASE_URL = "https://logs.revdog.org"
    private val lock = Any()

    @Volatile
    private var instance: RetrieverClient? = null

    /** 只存 Application context（不会泄漏 Activity）。 */
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var appContext: Context? = null
    private var platform: AndroidPlatform? = null

    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, key: String, baseUrl: String = DEFAULT_BASE_URL, options: Options = Options()) {
        try {
            val app = context.applicationContext ?: context
            synchronized(lock) {
                appContext = app
                val p = platformFor(app)
                val token = p.allowDiskWrites()
                try {
                    val name = RetrieverClient.sanitizeProcessName(options.processName ?: p.autoProcessName())
                    val c = instance
                    if (c != null) {
                        if (c.processName == name) {
                            c.reconfigure(key, baseUrl, options)
                            return
                        }
                        // 进程名变了（processName 应在第一条日志之前 configure）：旧实例封段收尾，新实例接管
                        c.shutdown()
                    }
                    instance = RetrieverClient(rootOf(app), key, baseUrl, options, AndroidClock, HttpUrlTransport(), p)
                } finally {
                    p.restoreDiskPolicy(token)
                }
            }
        } catch (t: RuntimeException) {
            // 绝不抛给宿主
        }
    }

    @JvmStatic
    public fun setUser(id: String?) {
        client()?.setUser(id)
    }

    /**
     * 记一行。`msg` 可为 null（Java 常见的 `e.getMessage()`）：有异常时取 `error.toString()`，否则空串——绝不抛给宿主。
     * `error` → `exc`（type = 类名、message、stack = `Log.getStackTraceString`）。
     */
    @JvmStatic
    @JvmOverloads
    public fun log(level: LogLevel, msg: String?, tag: String? = null, attrs: Map<String, Any?>? = null, error: Throwable? = null) {
        client()?.log(level, msg ?: error?.toString() ?: "", tag, attrs, error)
    }

    /** 「上报问题」：15 s 内确认 → [FlushResult.Stored]，否则 [FlushResult.Pending]（原因）。回调在 SDK 后台线程上。 */
    @JvmStatic
    @JvmOverloads
    public fun flush(includeContext: Boolean = true, callback: FlushCallback) {
        val c = client()
        if (c == null) {
            Thread { callback.onResult(FlushResult.Pending("paused")) }.start()
            return
        }
        c.flush(includeContext, callback)
    }

    /** 用户同意 / 撤回：false 时不写不传（不占 seq、不计到达率）。 */
    @JvmStatic
    public fun setEnabled(enabled: Boolean) {
        client()?.setEnabled(enabled)
    }

    /** 清空本地（新 install_id）。 */
    @JvmStatic
    public fun purgeLocal() {
        client()?.purgeLocal()
    }

    @JvmStatic
    public val installId: String?
        get() = client()?.installId

    /** install_id 前 8 位 + "-" + session_no（给客服报障用）。 */
    @JvmStatic
    public val supportCode: String?
        get() = client()?.supportCode

    /** 生效的自动上传级别（远程配置钳制后；full_dump 期间为 debug）。未 configure 时 = Options 默认。 */
    @JvmStatic
    public val uploadLevel: LogLevel
        get() = client()?.effectiveLevels?.first ?: LogLevel.WARN

    /** 生效的本地落盘级别（远程配置钳制后）。适配器用它早过滤。未 configure 时 = Options 默认。 */
    @JvmStatic
    public val localLevel: LogLevel
        get() = client()?.effectiveLevels?.second ?: LogLevel.DEBUG

    // ---- 内部 ----

    internal fun attachContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext ?: context
    }

    /** 作业服务用：拿（必要时懒建）实例。 */
    internal fun clientFor(context: Context): RetrieverClient? {
        attachContext(context)
        return client()
    }

    internal fun currentClient(): RetrieverClient? = instance

    private fun client(): RetrieverClient? {
        instance?.let { return it }
        val app = appContext ?: return null
        return try {
            synchronized(lock) {
                instance?.let { return it }
                val p = platformFor(app)
                val token = p.allowDiskWrites()
                try {
                    RetrieverClient(rootOf(app), "", DEFAULT_BASE_URL, Options(), AndroidClock, HttpUrlTransport(), p)
                        .also { instance = it }
                } finally {
                    p.restoreDiskPolicy(token)
                }
            }
        } catch (t: RuntimeException) {
            null
        }
    }

    private fun platformFor(app: Context): AndroidPlatform = platform ?: AndroidPlatform(app).also { platform = it }

    private fun rootOf(app: Context): File = File(app.noBackupFilesDir, "retriever")
}
