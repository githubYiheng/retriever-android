package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.RetrieverClient
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 写入纪律（宪法 R-1，方案 §3.3）。 */
class WriteDisciplineTests : RtvTest() {
    private fun readSegment(f: File): List<String> {
        // 用另一个流直接读（不经 sync）
        val b = FileInputStream(f).use { it.readBytes() }
        return String(b, Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }
    }

    private fun RetrieverClient.log(level: LogLevel, msg: String) = log(level, msg, null, null, null)

    @Test
    fun lineVisibleToOtherStreamImmediately() {
        val h = Harness(key = "")
        val f = h.client.debugOpenSegmentFile!!
        for (i in 1..50) {
            h.client.log(if (i % 5 == 0) LogLevel.WARN else LogLevel.INFO, "visible $i")
            val ls = readSegment(f)
            assertEquals("header + $i lines", i + 1, ls.size)
            val last = JsonIn.obj(ls.last())!!
            assertEquals(i.toLong(), int(last["seq"]))
            assertEquals("visible $i", last["msg"])
            assertEquals(i % 5 == 0, last.containsKey("oseq"))
        }
        // 段首行是 header
        val header = JsonIn.obj(readSegment(f)[0])!!
        assertEquals(1L, int(header["v"]))
        assertEquals(1L, int(header["seg_no"]))
        assertTrue(header.containsKey("user_id") && header["user_id"] == null)
    }

    class Boom : RuntimeException("bad thing")

    @Test
    fun fieldOrderAndOptionalKeys() {
        val h = Harness(key = "")
        h.client.log(LogLevel.ERROR, "boom", "net", mapOf("b" to true, "a" to 1.5, "c" to "x", "n" to null, "i" to 7), Boom())
        h.client.log(LogLevel.DEBUG, "plain")
        h.client.log(LogLevel.WARN, "no message", null, null, IllegalStateException())
        val ls = readSegment(h.client.debugOpenSegmentFile!!)
        assertTrue(ls[1], ls[1].startsWith("{\"seq\":1,\"oseq\":1,\"ts\":"))
        assertTrue(
            ls[1],
            ls[1].contains(
                "\"level\":\"error\",\"msg\":\"boom\",\"tag\":\"net\",\"attrs\":{\"a\":1.5,\"b\":true,\"c\":\"x\",\"i\":7,\"n\":null}," +
                    "\"exc\":{\"type\":\"org.revdog.retriever.WriteDisciplineTests\$Boom\",\"message\":\"bad thing\",\"stack\":\"",
            ),
        )
        assertTrue(ls[2], ls[2].startsWith("{\"seq\":2,\"ts\":"))
        assertFalse(ls[2].contains("tag"))
        assertFalse(ls[2].contains("truncated"))
        val exc = obj(JsonIn.obj(ls[1])!!["exc"])
        assertTrue((exc["stack"] as String).startsWith("org.revdog.retriever.WriteDisciplineTests\$Boom: bad thing"))
        // message 为 null → ""
        val exc3 = obj(JsonIn.obj(ls[3])!!["exc"])
        assertEquals("java.lang.IllegalStateException", exc3["type"])
        assertEquals("", exc3["message"])
    }

    @Test
    fun redactNullDoesNotConsumeSeq() {
        val o = Options().apply { redact = { line -> if ("secret" in line.msg) null else line } }
        val h = Harness(key = "", options = o)
        h.client.log(LogLevel.WARN, "a")
        h.client.log(LogLevel.WARN, "secret token")
        h.client.log(LogLevel.WARN, "b")
        assertEquals(2L, h.client.debugCounters.first)
        assertEquals(2L, h.client.debugCounters.second)
    }

    @Test
    fun redactCanRewriteAndReentrancyIgnored() {
        val box = arrayOfNulls<RetrieverClient>(1)
        val o = Options().apply {
            redact = { line ->
                box[0]?.log(LogLevel.ERROR, "from inside redact", null, null, null) // 重入：直接忽略
                line.copy(msg = line.msg.replace("pw=123", "pw=***"))
            }
        }
        val h = Harness(key = "", options = o)
        box[0] = h.client
        h.client.log(LogLevel.INFO, "login pw=123")
        assertEquals(1L, h.client.debugCounters.first)
        val ls = readSegment(h.client.debugOpenSegmentFile!!)
        assertEquals(2, ls.size)
        assertTrue(ls[1].contains("pw=***"))
    }

    @Test
    fun localLevelFilterDoesNotConsumeSeq() {
        val o = Options().apply { localLevel = LogLevel.INFO }
        val h = Harness(key = "", options = o)
        h.client.log(LogLevel.DEBUG, "dropped")
        h.client.log(LogLevel.INFO, "kept")
        h.client.log(LogLevel.DEBUG, "dropped")
        assertEquals(1L, h.client.debugCounters.first)
        assertEquals(0L, h.client.debugCounters.second)
    }

    @Test
    fun setEnabledFalseWritesNothing() {
        val h = Harness(key = "")
        h.client.setEnabled(false)
        h.client.log(LogLevel.ERROR, "nope")
        assertEquals(0L, h.client.debugCounters.first)
        h.client.setEnabled(true)
        h.client.log(LogLevel.ERROR, "yes")
        assertEquals(1L, h.client.debugCounters.first)
    }

    @Test
    fun writeFailureRecordsTombstoneAndNeverThrows() {
        val h = Harness(key = "")
        h.settle() // 启动任务里的 flushTombstones 先跑完，两次失败才会合并成一个区间
        h.client.log(LogLevel.WARN, "ok 1")
        // 模拟写失败：关掉当前段的流（write 抛 IOException）
        val f = h.client.debugOpenSegmentFile!!
        h.client.writer.debugBreakStream()
        h.client.log(LogLevel.WARN, "lost 2")
        h.client.log(LogLevel.WARN, "lost 3")
        h.client.log(LogLevel.INFO, "lost, no oseq")
        assertEquals(4L, h.client.debugCounters.first)
        assertEquals(3L, h.client.debugCounters.second)
        h.work { it.flushTombstones() }
        val drops = h.readJsonl("drops.jsonl")
        assertEquals("$drops", 1, drops.size)
        assertEquals("write_failed", drops[0]["reason"])
        assertEquals(2L, int(drops[0]["oseq_from"]))
        assertEquals(3L, int(drops[0]["oseq_to"]))
        assertEquals(2L, int(drops[0]["n"]))
        assertEquals(-1L, int(drops[0]["last_ack_age_ms"]))
        // 段文件里没有半行
        assertEquals(2, readSegment(f).size)
    }

    @Test
    fun concurrentLoggingKeepsSeqUnique() {
        val h = Harness(key = "")
        val c = h.client
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(8) { t ->
            pool.execute {
                start.await()
                for (i in 0 until 500) c.log(if (i % 3 == 0) LogLevel.WARN else LogLevel.DEBUG, "t$t i$i", null, null, null)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(4000L, c.debugCounters.first)
        h.settle()
        val dir = h.sessionDir()
        val seqs = ArrayList<Long>()
        val oseqs = ArrayList<Long>()
        for (n in (dir.list() ?: emptyArray()).filter { it.startsWith("seg-") }.sorted()) {
            for ((i, l) in readSegment(File(dir, n)).withIndex()) {
                if (i == 0) continue
                val o = JsonIn.obj(l)!!
                seqs.add(int(o["seq"]))
                if (o.containsKey("oseq")) oseqs.add(int(o["oseq"]))
            }
        }
        assertEquals((1L..4000L).toList(), seqs)
        assertEquals((1L..oseqs.size.toLong()).toList(), oseqs)
    }

    /** Android 主线程写入包 StrictMode.allowThreadDiskWrites()：每次落盘前放行、之后恢复（成对）。 */
    @Test
    fun strictModeAllowanceWrapsEveryWrite() {
        val h = Harness(key = "")
        val before = h.platform.diskAllowances.get()
        for (i in 0 until 10) h.client.log(LogLevel.INFO, "line $i")
        h.client.setUser("u1")
        assertEquals(before + 11, h.platform.diskAllowances.get())
        assertEquals(h.platform.diskAllowances.get(), h.platform.diskRestores.get())
        // 被 localLevel / setEnabled 过滤的行不碰磁盘
        h.client.setEnabled(false)
        h.client.log(LogLevel.ERROR, "filtered")
        assertEquals(before + 11, h.platform.diskAllowances.get())
    }

    /** redact 钩子抛异常：该行丢弃（宁丢不漏 PII）、不占 seq、绝不抛给宿主；异常走内部出口。 */
    @Test
    fun redactThrowingDropsLineAndNeverThrows() {
        val o = Options().apply { redact = { line -> if ("bad" in line.msg) throw IllegalArgumentException("hook bug") else line } }
        val h = Harness(key = "", options = o)
        h.client.log(LogLevel.WARN, "ok")
        h.client.log(LogLevel.WARN, "bad line")
        h.client.log(LogLevel.WARN, "ok 2")
        assertEquals(2L, h.client.debugCounters.first)
        assertEquals(1, h.errors.size)
        assertNotNull(h.errors[0] as? IllegalArgumentException)
        h.errors.clear()
        assertNull(null)
    }
}
