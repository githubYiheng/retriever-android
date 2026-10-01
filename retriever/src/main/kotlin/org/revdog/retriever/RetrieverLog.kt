package org.revdog.retriever

import android.util.Log

/**
 * `android.util.Log` 的同名替身：双写 logcat 与 Retriever。宿主只把 `Log.` 换成 `RetrieverLog.`，调用处不改。
 * 级别：v / d → debug、i → info、w → warn、e → error、wtf → fatal；tag 原样作 Retriever 的 tag；
 * Throwable 进 `exc`（type = 类名、message、stack = `printStackTrace` 文本）。返回值同 `Log`（写入 logcat 的字节数）。
 */
public object RetrieverLog {
    /** 测试注入点：logcat 与 Retriever 两个落点。 */
    internal var logcat: (Int, String?, String, Throwable?) -> Int = { p, tag, msg, tr -> defaultLogcat(p, tag, msg, tr) }
    internal var sink: (LogLevel, String, String?, Throwable?) -> Unit = { level, msg, tag, tr ->
        Retriever.log(level, msg, tag, null, tr)
    }

    @JvmStatic
    @JvmOverloads
    public fun v(tag: String?, msg: String?, tr: Throwable? = null): Int = emit(Log.VERBOSE, LogLevel.DEBUG, tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    public fun d(tag: String?, msg: String?, tr: Throwable? = null): Int = emit(Log.DEBUG, LogLevel.DEBUG, tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    public fun i(tag: String?, msg: String?, tr: Throwable? = null): Int = emit(Log.INFO, LogLevel.INFO, tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    public fun w(tag: String?, msg: String?, tr: Throwable? = null): Int = emit(Log.WARN, LogLevel.WARN, tag, msg, tr)

    /** 同 `Log.w(tag, tr)`：只有异常没有消息。 */
    @JvmStatic
    public fun w(tag: String?, tr: Throwable?): Int = emit(Log.WARN, LogLevel.WARN, tag, null, tr)

    @JvmStatic
    @JvmOverloads
    public fun e(tag: String?, msg: String?, tr: Throwable? = null): Int = emit(Log.ERROR, LogLevel.ERROR, tag, msg, tr)

    @JvmStatic
    @JvmOverloads
    public fun wtf(tag: String?, msg: String?, tr: Throwable? = null): Int = emit(Log.ASSERT, LogLevel.FATAL, tag, msg, tr)

    /** 同 `Log.wtf(tag, tr)`。 */
    @JvmStatic
    public fun wtf(tag: String?, tr: Throwable?): Int = emit(Log.ASSERT, LogLevel.FATAL, tag, null, tr)

    /**
     * 先写 Retriever、再写 logcat（ADR 0020 决定 1）：`Log.wtf` 按系统配置可能直接终止进程，先写 logcat 的话那一行就进不了 Retriever。
     * msg 为 null（Java 的 `e.getMessage()`）：logcat 照 `Log` 的习惯写 "null"；Retriever 里有异常取 `tr.toString()`
     * （它抛异常 → 交给 Retriever 取占位串）。两个落点抛任何东西都不抛给宿主（ADR 0024 决定 5）。
     */
    private fun emit(priority: Int, level: LogLevel, tag: String?, msg: String?, tr: Throwable?): Int {
        try {
            val m = if (msg.isNullOrEmpty() && tr != null) {
                try {
                    tr.toString()
                } catch (t: Throwable) {
                    null
                }
            } else {
                msg ?: ""
            }
            sink(level, m ?: "<unprintable>", tag, tr)
        } catch (e: Throwable) {
            // 绝不抛给宿主
        }
        return try {
            logcat(priority, tag, if (msg == null && tr != null) "" else msg.toString(), tr)
        } catch (e: Throwable) {
            0
        }
    }

    private fun defaultLogcat(priority: Int, tag: String?, msg: String, tr: Throwable?): Int = when (priority) {
        Log.VERBOSE -> if (tr != null) Log.v(tag, msg, tr) else Log.v(tag, msg)
        Log.DEBUG -> if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)
        Log.INFO -> if (tr != null) Log.i(tag, msg, tr) else Log.i(tag, msg)
        Log.WARN -> if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        Log.ERROR -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        else -> if (tr != null) Log.wtf(tag, msg, tr) else Log.wtf(tag, msg)
    }
}
