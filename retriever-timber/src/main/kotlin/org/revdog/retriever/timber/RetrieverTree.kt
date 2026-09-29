package org.revdog.retriever.timber

import android.util.Log
import org.revdog.retriever.LogLevel
import org.revdog.retriever.Retriever
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Timber 适配器（方案 §3.10）：`Timber.plant(RetrieverTree())`，宿主现有 `Timber.x(...)` 调用不改。
 *
 * - 级别：VERBOSE / DEBUG → debug、INFO → info、WARN → warn、ERROR → error、ASSERT → fatal；tag = Timber 的 tag。
 * - `isLoggable` 用 `Retriever.localLevel` 早过滤（低于本地级别的行连格式化都不做，也不占 seq）。
 * - Timber 在 message 尾部拼了 `"\n" + 栈`（`prepareLog`）：这里把它剥掉，栈放进 `exc`
 *   （type = 异常类名、message、stack = `Log.getStackTraceString`，由 SDK 生成）。只有异常没有消息时 msg = `t.toString()`。
 */
public class RetrieverTree internal constructor(
    private val emit: (LogLevel, String, String?, Throwable?) -> Unit,
    private val localLevel: () -> LogLevel,
) : Timber.Tree() {
    public constructor() : this(
        { level, msg, tag, t -> Retriever.log(level, msg, tag, null, t) },
        { Retriever.localLevel },
    )

    override fun isLoggable(tag: String?, priority: Int): Boolean = levelOf(priority) >= localLevel()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = levelOf(priority)
        if (level < localLevel()) return
        val msg = if (t == null) message else strip(message, t)
        emit(level, msg, tag, t)
    }

    internal companion object {
        fun levelOf(priority: Int): LogLevel = when (priority) {
            Log.VERBOSE, Log.DEBUG -> LogLevel.DEBUG
            Log.INFO -> LogLevel.INFO
            Log.WARN -> LogLevel.WARN
            Log.ERROR -> LogLevel.ERROR
            Log.ASSERT -> LogLevel.FATAL
            else -> if (priority < Log.VERBOSE) LogLevel.DEBUG else LogLevel.FATAL
        }

        /** 剥掉 Timber 拼在尾部的栈（与 Timber 5 `getStackTraceString` 同算法：StringWriter + printStackTrace）。 */
        fun strip(message: String, t: Throwable): String {
            val stack = timberStack(t)
            if (message == stack) return t.toString()
            val suffix = "\n" + stack
            if (message.endsWith(suffix)) return message.substring(0, message.length - suffix.length)
            return message
        }

        private fun timberStack(t: Throwable): String {
            val sw = StringWriter(256)
            val pw = PrintWriter(sw, false)
            t.printStackTrace(pw)
            pw.flush()
            return sw.toString()
        }
    }
}
