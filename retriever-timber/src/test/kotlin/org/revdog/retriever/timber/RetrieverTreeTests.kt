package org.revdog.retriever.timber

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.revdog.retriever.LogLevel
import timber.log.Timber

/** Timber 适配（方案 §3.1 级别映射 / §3.10）：映射、栈剥离进 exc、isLoggable 早过滤、tag。 */
class RetrieverTreeTests {
    private class Call(val level: LogLevel, val msg: String, val tag: String?, val t: Throwable?)

    private val calls = ArrayList<Call>()

    @Volatile
    private var local = LogLevel.DEBUG

    private fun tree() = RetrieverTree({ level, msg, tag, t -> calls.add(Call(level, msg, tag, t)) }, { local })

    @After
    fun uproot() {
        Timber.uprootAll()
    }

    @Test
    fun levelMapping() {
        val t = tree()
        t.v("v")
        t.d("d")
        t.i("i")
        t.w("w")
        t.e("e")
        t.wtf("f")
        assertEquals(listOf(LogLevel.DEBUG, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL), calls.map { it.level })
        assertEquals(listOf("v", "d", "i", "w", "e", "f"), calls.map { it.msg })
        assertEquals(LogLevel.FATAL, RetrieverTree.levelOf(Log.ASSERT))
        assertEquals(LogLevel.DEBUG, RetrieverTree.levelOf(Log.VERBOSE))
    }

    @Test
    fun stackStrippedFromMessageIntoExc() {
        val ex = IllegalStateException("payment declined")
        val t = tree()
        t.e(ex, "purchase failed %s", "sku_pro")
        assertEquals("Timber 拼在尾部的栈被剥掉", "purchase failed sku_pro", calls[0].msg)
        assertSame("Throwable 原样交给 SDK（exc 由 SDK 生成）", ex, calls[0].t)
        assertFalse(calls[0].msg.contains("\tat "))
        // 只有异常没有消息：Timber 把 message 设成整段栈 → msg = t.toString()
        t.w(ex)
        assertEquals("java.lang.IllegalStateException: payment declined", calls[1].msg)
        assertSame(ex, calls[1].t)
        // 没有异常：message 原样
        t.i("plain %d", 3)
        assertEquals("plain 3", calls[2].msg)
        assertNull(calls[2].t)
    }

    @Test
    fun isLoggableEarlyFilterSkipsFormatting() {
        local = LogLevel.WARN
        var formatted = 0
        val arg = object {
            override fun toString(): String {
                formatted++
                return "x"
            }
        }
        val t = tree()
        t.d("debug %s", arg)
        t.i("info %s", arg)
        assertEquals("低于本地级别：不格式化、不进 Retriever", 0, formatted)
        assertEquals(0, calls.size)
        t.w("warn %s", arg)
        t.wtf("assert %s", arg)
        assertEquals(1 + 1, formatted)
        assertEquals(listOf(LogLevel.WARN, LogLevel.FATAL), calls.map { it.level })
    }

    @Test
    fun tagFromTimber() {
        val t = tree()
        Timber.plant(t)
        Timber.tag("billing").w("slow")
        Timber.i("no tag")
        assertEquals("billing", calls[0].tag)
        assertEquals("slow", calls[0].msg)
        assertNull("没设 tag 的调用不沿用上一个", calls[1].tag)
    }

    /** 落点 / 宿主异常抛任何 Throwable 都不抛进宿主的 Timber 调用（ADR 0024 决定 5）。 */
    @Test
    fun neverThrows() {
        val t = RetrieverTree({ _, _, _, _ -> throw AssertionError("sink") }, { LogLevel.DEBUG })
        t.log(Log.ERROR, "tag", "msg", null)
        val evil = object : RuntimeException("x") {
            override fun printStackTrace(s: java.io.PrintWriter) = throw IllegalStateException("printStackTrace")
        }
        RetrieverTree({ _, _, _, _ -> }, { LogLevel.DEBUG }).log(Log.ERROR, "tag", "msg", evil)
    }

    @Test
    fun unmatchedSuffixKeptAsIs() {
        // message 不是 Timber 拼出来的形状（例如子类改写了 prepareLog）：原样保留，异常照样进 exc
        val ex = RuntimeException("x")
        assertEquals("custom message", RetrieverTree.strip("custom message", ex))
        assertEquals("custom message\nnot the stack", RetrieverTree.strip("custom message\nnot the stack", ex))
        // 宿主经 Timber 的 vararg 入口传 Throwable 作格式参数：不算异常，不剥
        tree().log(Log.ERROR, "code %s", ex)
        assertEquals("code java.lang.RuntimeException: x", calls[0].msg)
        assertNull(calls[0].t)
    }
}
