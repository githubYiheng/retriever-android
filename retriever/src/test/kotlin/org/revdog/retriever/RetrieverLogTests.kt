package org.revdog.retriever

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.revdog.retriever.core.JsonIn

/** `android.util.Log` 替身：双写 logcat 与 Retriever；级别映射；Throwable 进 exc。 */
class RetrieverLogTests : RtvTest() {
    private class Call(val level: LogLevel, val msg: String, val tag: String?, val tr: Throwable?)

    private val logcat = ArrayList<Triple<Int, String?, String>>()
    private val calls = ArrayList<Call>()
    private lateinit var savedLogcat: (Int, String?, String, Throwable?) -> Int
    private lateinit var savedSink: (LogLevel, String, String?, Throwable?) -> Unit

    @Before
    fun hook() {
        savedLogcat = RetrieverLog.logcat
        savedSink = RetrieverLog.sink
        RetrieverLog.logcat = { p, tag, msg, _ ->
            logcat.add(Triple(p, tag, msg))
            msg.length + 1
        }
        RetrieverLog.sink = { level, msg, tag, tr -> calls.add(Call(level, msg, tag, tr)) }
    }

    @After
    fun unhook() {
        RetrieverLog.logcat = savedLogcat
        RetrieverLog.sink = savedSink
    }

    @Test
    fun levelMappingAndDoubleWrite() {
        assertEquals(2, RetrieverLog.v("T", "v"))
        RetrieverLog.d("T", "d")
        RetrieverLog.i("T", "i")
        RetrieverLog.w("T", "w")
        RetrieverLog.e("T", "e")
        RetrieverLog.wtf("T", "f")
        assertEquals(listOf(LogLevel.DEBUG, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL), calls.map { it.level })
        assertEquals(listOf("v", "d", "i", "w", "e", "f"), calls.map { it.msg })
        assertTrue("tag 原样", calls.all { it.tag == "T" })
        assertEquals(listOf(Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT), logcat.map { it.first })
    }

    @Test
    fun throwableOverloads() {
        val ex = IllegalStateException("boom")
        RetrieverLog.e("net", "request failed", ex)
        RetrieverLog.w("net", ex)
        RetrieverLog.wtf("net", ex)
        assertSame(ex, calls[0].tr)
        assertEquals("request failed", calls[0].msg)
        assertEquals("只有异常：msg = t.toString()", "java.lang.IllegalStateException: boom", calls[1].msg)
        assertEquals(LogLevel.WARN, calls[1].level)
        assertEquals(LogLevel.FATAL, calls[2].level)
        // Java 的 Log.e(TAG, e.getMessage(), e)：getMessage() 为 null 也不抛
        val noMsg = RuntimeException()
        RetrieverLog.e("net", null, noMsg)
        assertEquals("java.lang.RuntimeException", calls[3].msg)
        RetrieverLog.i(null, null)
        assertEquals("", calls[4].msg)
        assertEquals("null", logcat[4].third)
    }

    /** 先写 Retriever、再写 logcat（ADR 0020 决定 1）：`Log.wtf` 可能直接终止进程，先写 logcat 的话那一行进不了 Retriever。 */
    @Test
    fun wtfWritesSinkBeforeLogcat() {
        val order = ArrayList<String>()
        RetrieverLog.logcat = { _, _, _, _ ->
            order.add("logcat")
            1
        }
        RetrieverLog.sink = { _, _, _, _ -> order.add("sink") }
        RetrieverLog.wtf("T", "f")
        RetrieverLog.wtf("T", IllegalStateException("x"))
        RetrieverLog.e("T", "e")
        assertEquals(listOf("sink", "logcat", "sink", "logcat", "sink", "logcat"), order)
    }

    @Test
    fun endToEndIntoSegmentAndNeverThrows() {
        val h = Harness(key = "")
        RetrieverLog.sink = { level, msg, tag, tr -> h.client.log(level, msg, tag, null, tr) }
        RetrieverLog.w("billing", "slow", RuntimeException("x"))
        val line = JsonIn.obj(h.client.debugOpenSegmentFile!!.readText().split("\n")[1])!!
        assertEquals("warn", line["level"])
        assertEquals("billing", line["tag"])
        assertEquals("slow", line["msg"])
        assertEquals("java.lang.RuntimeException", obj(line["exc"])["type"])
        // 落点抛异常也不抛给宿主
        RetrieverLog.sink = { _, _, _, _ -> throw IllegalArgumentException("sink bug") }
        RetrieverLog.logcat = { _, _, _, _ -> throw IllegalStateException("logcat bug") }
        assertEquals(0, RetrieverLog.e("T", "x"))
    }
}
