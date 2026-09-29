package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.BackoffState
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.SealReason
import org.revdog.retriever.core.materializeBackfill
import java.io.File

/** 段状态机与物化（方案 §3.4 / §3.5）。与 iOS SegmentTests 同一组用例。 */
class SegmentTests : RtvTest() {
    private fun segFiles(h: Harness): List<String> = (h.sessionDir().list() ?: emptyArray()).filter { it.startsWith("seg-") }.sorted()

    private fun header(f: File): Map<String, Any?> = JsonIn.obj(f.readText().split("\n")[0])!!

    private fun Harness.l(level: LogLevel, msg: String) = client.log(level, msg, null, null, null)

    @Test
    fun rotatesAt512KB() {
        val h = Harness(key = "")
        h.settle()
        while (segFiles(h).size < 2) h.l(LogLevel.DEBUG, "fill " + "x".repeat(200))
        h.settle()
        assertEquals(listOf("seg-000001.sealed", "seg-000002.open"), segFiles(h))
        val size = File(h.sessionDir(), "seg-000001.sealed").length()
        assertTrue(size >= Limits.SEGMENT_BYTES)
        assertTrue(size < Limits.SEGMENT_BYTES + 400)
        assertEquals(2L, int(header(File(h.sessionDir(), "seg-000002.open"))["seg_no"]))
        // 全是 debug（非义务行）→ 不产生批次
        assertEquals(emptyList<String>(), h.outboxFiles())
        val cursor = h.readJson(File(h.sessionDir(), "cursor.json"))
        assertEquals(0L, int(cursor["extracted_through_oseq"]))
        assertEquals("fg", cursor["last_state"])
    }

    @Test
    fun setUserSealsAndHeaderCarriesUser() {
        val h = Harness(key = "")
        h.settle()
        h.client.setUser("early") // 段内还没有行：直接改写 header，不封段
        h.l(LogLevel.WARN, "as early")
        h.client.setUser("u1")
        h.l(LogLevel.WARN, "as u1")
        h.client.setUser("u1") // 值没变：不封段
        h.l(LogLevel.WARN, "still u1")
        h.client.setUser(null)
        h.settle()
        val files = segFiles(h)
        assertEquals(listOf("seg-000001.sealed", "seg-000002.sealed", "seg-000003.open"), files)
        assertEquals("early", header(File(h.sessionDir(), files[0]))["user_id"])
        assertEquals("u1", header(File(h.sessionDir(), files[1]))["user_id"])
        assertTrue(header(File(h.sessionDir(), files[2])).let { it.containsKey("user_id") && it["user_id"] == null })
        val envs = h.envelopes().sortedBy { int(it["oseq_from"]) }
        assertEquals(2, envs.size)
        assertEquals("early", envs[0]["user_id"])
        assertEquals(listOf("as early"), envs[0].lines.map { it["msg"] })
        assertEquals("u1", envs[1]["user_id"])
        assertEquals(listOf("as u1", "still u1"), envs[1].lines.map { it["msg"] })
        assertEquals("u1", obj(envs[1]["mapping"])["user_id"])
    }

    @Test
    fun errorDebounceTwoSecondsAndTenSecondSpacing() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.ERROR, "e1")
        h.l(LogLevel.DEBUG, "after e1")
        h.tick(1999)
        assertEquals(emptyList<String>(), h.outboxFiles())
        h.tick(1)
        assertEquals(1, h.outboxFiles("p0").size)
        // 第二个 error：距上次 error 封段不足 10 s → 推到 10 s
        h.tick(1000)
        h.l(LogLevel.ERROR, "e2")
        h.tick(2000)
        assertEquals(1, h.outboxFiles("p0").size)
        h.tick(6999)
        assertEquals(1, h.outboxFiles("p0").size)
        h.tick(1)
        assertEquals(2, h.outboxFiles("p0").size)
    }

    @Test
    fun fatalSealsImmediatelyWithoutUpload() {
        val h = Harness()
        h.settle()
        val before = h.transport.batchRequests.size
        h.l(LogLevel.INFO, "ctx before fatal")
        h.l(LogLevel.FATAL, "crashing")
        // log() 返回时批已在出站箱（同步物化），且没有发起上传
        assertEquals(1, h.outboxFiles("p0").size)
        assertEquals(before, h.transport.batchRequests.size)
        val e = h.envelopes("p0").first()
        assertEquals(listOf("ctx before fatal", "crashing"), e.lines.map { it["msg"] })
        assertEquals(true, e.lines.first()["ctx"])
    }

    @Test
    fun warnTimerFlushInterval() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.INFO, "not obligation")
        h.tick(400_000)
        assertEquals("没有义务行不封", emptyList<String>(), h.outboxFiles())
        h.l(LogLevel.WARN, "w")
        h.tick(299_999)
        assertEquals(emptyList<String>(), h.outboxFiles())
        h.tick(1)
        assertEquals(1, h.outboxFiles("p1").size)
    }

    @Test
    fun ctxAcrossSegmentsAndDedupedByCtxThroughSeq() {
        val h = Harness(key = "")
        h.settle()
        // 段 1 塞满 debug，段 2 再写 50 行 debug，然后 error
        while (segFiles(h).size < 2) h.l(LogLevel.DEBUG, "old " + "o".repeat(300))
        val seg1Last = h.client.debugCounters.first
        for (i in 0 until 50) h.l(LogLevel.DEBUG, "new $i")
        h.l(LogLevel.ERROR, "e1")
        h.tick(2000)
        val e1 = h.envelopes("p0").first()
        val ctx1 = e1.lines.filter { it["ctx"] == true }
        assertEquals(Limits.CTX_LINES_DEFAULT, ctx1.size)
        assertTrue("ctx 跨段取", ctx1.any { int(it["seq"]) <= seg1Last })
        assertTrue(ctx1.any { int(it["seq"]) > seg1Last })
        assertTrue(int(e1["ctx_truncated"]) > 0)
        assertTrue(ctx1.none { it.containsKey("oseq") })
        // 行按 seq 升序，ctx 与义务行交错
        val seqs = e1.lines.map { int(it["seq"]) }
        assertEquals(seqs.sorted(), seqs)
        // 第二个 error 只带新的 ctx
        val through = int(e1["seq_to"])
        for (i in 0 until 5) h.l(LogLevel.INFO, "between $i")
        h.l(LogLevel.ERROR, "e2")
        h.tick(10_000)
        val all = h.envelopes("p0")
        assertEquals(2, all.size)
        val e2 = all.first { int(it["oseq_from"]) == 2L }
        val ctx2 = e2.lines.filter { it["ctx"] == true }
        assertEquals((0 until 5).map { "between $it" }, ctx2.map { it["msg"] })
        assertTrue(ctx2.all { int(it["seq"]) > through })
        assertEquals(0L, int(e2["ctx_truncated"]))
        assertAllValid(runValidator(all))
    }

    @Test
    fun dailyCapMergesP1AndSplitsAt768KB() {
        val o = Options().apply { dailyBatchCap = 1 }
        val h = Harness(key = "", options = o)
        h.settle()
        h.l(LogLevel.WARN, "first")
        h.seal()
        assertEquals(1, h.outboxFiles("p1").size)
        // 超出上限的 p1 推迟：每段 ~512 KB 的 warn，封 3 段
        val big = "w".repeat(4000)
        repeat(3) {
            val seg = h.client.writer.snapshot.segNo
            while (h.client.writer.snapshot.segNo == seg) h.l(LogLevel.WARN, big)
            h.settle()
        }
        assertEquals("p1 被推迟合并", 1, h.outboxFiles().size)
        // p0 不受限，并把推迟的义务行一起物化（按 768 KB 切多批，oseq 首尾相接）
        h.l(LogLevel.ERROR, "boom")
        h.seal(SealReason.ERROR)
        val envs = h.envelopes().sortedBy { int(it["oseq_from"]) }
        assertTrue(envs.size >= 4)
        for (i in 1 until envs.size) assertEquals(int(envs[i - 1]["oseq_to"]) + 1, int(envs[i]["oseq_from"]))
        assertEquals(h.client.debugCounters.second, int(envs.last()["oseq_to"]))
        for (e in envs) assertTrue(e.raw.size <= Limits.BATCH_UNCOMPRESSED_BYTES_CLIENT)
        assertEquals(1, h.outboxFiles("p0").size)
        assertAllValid(runValidator(envs))
    }

    private fun flushLine(e: Env): Map<String, Any?>? = e.lines.firstOrNull { it["tag"] == "rtv.flush" }

    @Test
    fun flushWithContextStoredAfterAck() {
        val h = Harness()
        h.settle()
        h.l(LogLevel.INFO, "user tapped report")
        h.l(LogLevel.DEBUG, "detail")
        assertEquals(FlushResult.Stored, h.flushBlocking(true))
        val e = FakeTransport.envOf(h.transport.batchRequests.last().body)!!
        val marker = flushLine(e)!!
        assertEquals("error", marker["level"])
        assertEquals("flush", marker["msg"])
        assertEquals(true, marker["synthetic"])
        assertEquals(1L, int(marker["oseq"]))
        assertEquals(listOf("user tapped report", "detail"), e.lines.filter { it["ctx"] == true }.map { it["msg"] })
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    @Test
    fun flushWithoutContextAndAboveUploadLevel() {
        val o = Options().apply {
            uploadLevel = LogLevel.FATAL
            localLevel = LogLevel.FATAL
        }
        val h = Harness(options = o)
        h.settle()
        h.l(LogLevel.FATAL, "earlier fatal")
        h.l(LogLevel.INFO, "filtered by localLevel")
        h.settle()
        val n0 = h.transport.batchRequests.size
        assertEquals(FlushResult.Stored, h.flushBlocking(false))
        assertTrue(h.transport.batchRequests.size > n0)
        val e = h.transport.batchRequests.mapNotNull { FakeTransport.envOf(it.body) }.first { flushLine(it) != null }
        assertEquals("includeContext = false 不带 ctx", 0, e.lines.count { it["ctx"] == true })
        assertNull(e["ctx_truncated"])
        assertNotNull("uploadLevel 高于 error 也是义务行", flushLine(e)!!["oseq"])
    }

    @Test
    fun flushPendingReasons() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Network
        assertEquals(FlushResult.Pending("offline"), h.flushBlocking())
        // 退避回到 0 后测 401 → paused
        h.work { it.backoff = BackoffState() }
        h.transport.defaultReply = FakeTransport.Reply.Status(401, mapOf("reason" to "key_invalid"))
        h.tick(3000)
        assertEquals(FlushResult.Pending("paused"), h.flushBlocking())
        // 503 + Retry-After 1 h：退避超出 15 s 窗口 → backoff
        h.work { it.backoff = BackoffState() }
        h.transport.defaultReply = FakeTransport.Reply.Status(503, mapOf("reason" to "storage", "retry_after_s" to 3600), mapOf("retry-after" to "3600"))
        h.tick(3000)
        assertEquals(FlushResult.Pending("backoff"), h.flushBlocking())
        // 在途请求挂起 → 真实时间兜底 timeout
        h.work { it.backoff = BackoffState() }
        h.transport.defaultReply = FakeTransport.Reply.Hang
        h.client.setFlushWindowForTesting(200)
        h.clock.advance(3000) // 越过 2 s 间隔，让 flush 的批立即在途
        assertEquals(FlushResult.Pending("timeout"), h.flushBlocking())
        assertEquals(1, h.transport.hangingCount)
        h.transport.defaultReply = FakeTransport.Reply.Network
        h.transport.cancelAll()
        h.settle()
        h.client.setEnabled(false)
        assertEquals(FlushResult.Pending("disabled"), h.flushBlocking())
        assertEquals("FlushResult 的字符串形式与 iOS 一致", "pending(\"disabled\")", FlushResult.Pending("disabled").toString())
        assertEquals("stored", FlushResult.Stored.toString())
    }
}

/** backfill（full_dump 生效；主代理 2026-09-29 裁决）：RETAINED 段中全部非义务行按段成批，不受 ctx_through_seq 限制。 */
class BackfillTests : RtvTest() {
    @Test
    fun backfillIncludesLinesAlreadySentAsCtx() {
        val h = Harness(key = "")
        h.settle()
        for (i in 0 until 5) h.client.log(LogLevel.DEBUG, "before error $i", null, null, null)
        h.client.log(LogLevel.ERROR, "e", null, null, null)
        h.tick(2000)
        val p0 = h.envelopes("p0").first()
        assertEquals(5, p0.lines.count { it["ctx"] == true })
        h.client.log(LogLevel.INFO, "after", null, null, null)
        h.seal()
        h.work { it.materializeBackfill() }
        val bfs = h.envelopes("p2").sortedBy { int(it["seq_from"]) }
        assertEquals("每段一批", 2, bfs.size)
        assertEquals("已作为 ctx 上传过的行也回传", (0 until 5).map { "before error $it" }, bfs[0].lines.map { it["msg"] })
        assertEquals(listOf("after"), bfs[1].lines.map { it["msg"] })
        val iid = h.client.installId!!
        val sid = h.client.writer.currentSessionId
        assertEquals(org.revdog.retriever.core.Ids.batchId(iid, sid, org.revdog.retriever.core.Ids.BatchKind.BACKFILL, 1), bfs[0]["batch_id"])
        assertEquals(org.revdog.retriever.core.Ids.batchId(iid, sid, org.revdog.retriever.core.Ids.BatchKind.BACKFILL, 2), bfs[1]["batch_id"])
        // 同一进程内不重复生成
        h.work { it.materializeBackfill() }
        assertEquals(2, h.outboxFiles("p2").size)
        assertAllValid(runValidator(bfs))
    }

    @Test
    fun oversizedSegmentSplitsBySeqWithThreePartName() {
        val h = Harness(key = "")
        h.settle()
        for (i in 0 until 30) h.client.log(LogLevel.DEBUG, "d$i " + "x".repeat(300), null, null, null)
        h.seal()
        h.work { it.materializeBackfill(4096) }
        val parts = h.envelopes("p2").sortedBy { int(it["seq_from"]) }
        assertTrue(parts.size > 1)
        val iid = h.client.installId!!
        val sid = h.client.writer.currentSessionId
        var next = 1L
        for (p in parts) {
            assertEquals("按 seq 首尾相接", next, int(p["seq_from"]))
            next = int(p["seq_to"]) + 1
            val name = "$iid:$sid:backfill:1:${int(p["seq_from"])}"
            assertEquals(org.revdog.retriever.core.Ids.uuidv5(org.revdog.retriever.core.Ids.NAMESPACE, name), p["batch_id"])
            assertTrue(p.raw.size <= 4096 + 400)
            assertFalse(p.map.containsKey("oseq_from"))
        }
        assertEquals(31L, next)
        assertAllValid(runValidator(parts))
    }
}
