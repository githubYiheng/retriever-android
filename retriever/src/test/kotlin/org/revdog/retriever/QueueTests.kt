package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.FakeTransport.Reply
import org.revdog.retriever.core.ClientConstants
import org.revdog.retriever.core.HttpRequest
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.materializeBackfill
import org.revdog.retriever.core.quarantine
import java.io.File

/** 出站队列与响应分类（方案 §3.6 / §3.7；宪法 R-2 / R-3）。假 transport 按脚本回响应，断言队列动作。与 iOS QueueTests 同一组。 */
class QueueTests : RtvTest() {
    private fun env(r: HttpRequest): Env = FakeTransport.envOf(r.body)!!

    private fun Harness.l(level: LogLevel, msg: String) = client.log(level, msg, null, null, null)

    @Test
    fun ackWithEchoDeletesFile() {
        val h = Harness()
        h.settle()
        h.l(LogLevel.INFO, "ctx")
        h.l(LogLevel.WARN, "w1")
        h.sealAndDrain()
        assertEquals(1, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
        val r = h.transport.batchRequests[0]
        assertEquals("POST", r.method)
        assertEquals("https://logs-test.invalid/v1/batches", r.url)
        assertEquals("Bearer lk_test_demo_abc_12345678", r.headers["Authorization"])
        assertEquals("application/json", r.headers["Content-Type"])
        assertEquals("gzip", r.headers["Content-Encoding"])
        assertEquals(h.client.installId, r.headers["X-Rtv-Install"])
        assertEquals(h.clock.wallMs().toString(), r.headers["X-Rtv-Sent-Ms"])
        assertEquals("retriever-android/${RetrieverVersion.CURRENT}", r.headers["X-Rtv-Sdk"])
        val e = env(r)
        assertEquals("primary", e["kind"])
        assertEquals(1L, int(e["oseq_from"]))
        assertEquals("纯 warn 批不带 ctx", 1, e.lines.size)
        assertEquals("w1", e.lines.first()["msg"])
        val b = h.backoff
        assertEquals(0, b.attempt)
        assertEquals(h.clock.wallMs(), b.lastAckMs)
        // mapping 块已被确认 → mapping.json
        val m = h.readJson(File(h.root, "mapping.json"))
        assertTrue(m.containsKey("user_id") && m["user_id"] == null)
        assertEquals(h.clock.wallMs(), int(m["acked_ms"]))
    }

    @Test
    fun echoMismatchKeepsFileAndBacksOff() {
        val h = Harness()
        h.settle()
        h.transport.setScript(listOf(Reply.EchoWrong))
        h.l(LogLevel.WARN, "w")
        h.sealAndDrain()
        assertEquals(1, h.transport.batchRequests.size)
        assertEquals(1, h.outboxFiles().size)
        val b = h.backoff
        val wait = b.nextAtMonoMs - h.nowMono
        assertEquals(1, b.attempt)
        assertTrue("$wait", wait in 800..1200)
        assertEquals("echo_mismatch", b.reason)
        // 退避期内不发（且相邻请求 ≥ 2 s）
        h.tick(700)
        assertEquals(1, h.transport.batchRequests.size)
        h.tick(1300)
        assertEquals(2, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
        // backoff.json 已持久化
        assertEquals(0L, int(h.readJson(File(h.root, "backoff.json"))["attempt"]))
    }

    @Test
    fun unauthorizedPausesOneHourThenDoubles() {
        val h = Harness()
        h.settle()
        h.transport.setScript(listOf(Reply.Status(401, mapOf("reason" to "key_invalid")), Reply.Status(403, mapOf("reason" to "app_disabled"))))
        h.l(LogLevel.WARN, "w")
        h.sealAndDrain()
        var b = h.backoff
        assertEquals(listOf("all"), b.pausedCategories)
        assertEquals(3_600_000L, b.pausedUntilMono - h.nowMono)
        assertEquals("不删任何文件", 1, h.outboxFiles().size)
        // 照常写本地
        h.l(LogLevel.WARN, "still written")
        assertEquals(2L, h.client.debugCounters.second)
        h.tick(3_599_000)
        assertEquals(1, h.transport.batchRequests.size)
        h.tick(1_000)
        assertEquals(2, h.transport.batchRequests.size)
        b = h.backoff
        assertEquals(7_200_000L, b.pausedUntilMono - h.nowMono)
        assertEquals("warn 计时封出的第二批也在，均未删", 2, h.outboxFiles().size)
        // 暂停期间照常拉配置
        val cfgBefore = h.transport.configRequests.size
        h.tick(Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        assertTrue(h.transport.configRequests.size > cfgBefore)
        assertEquals(2, h.transport.batchRequests.size)
        // 24 h 封顶
        repeat(6) {
            h.work { e ->
                e.backoff.pausedUntilMono = 0
                e.backoff.pausedCategories = emptyList()
            }
            h.transport.setScript(listOf(Reply.Status(401)))
            h.tick(Limits.MIN_REQUEST_SPACING_MS)
        }
        b = h.backoff
        assertEquals(Limits.PAUSE_401_MAX_MS, b.pausedUntilMono - h.nowMono)
    }

    @Test
    fun tooLargeSplitsIntoContiguousHalves() {
        val h = Harness()
        h.settle()
        h.transport.setScript(listOf(Reply.Status(413, mapOf("reason" to "too_large", "max_bytes" to 1_048_576)), Reply.Echo, Reply.Echo))
        for (i in 1..6) h.l(if (i == 5) LogLevel.ERROR else LogLevel.WARN, "w$i")
        h.l(LogLevel.DEBUG, "ctx after")
        h.sealAndDrain()
        assertEquals(1, h.transport.batchRequests.size)
        val original = env(h.transport.batchRequests[0])
        assertEquals(1L, int(original["oseq_from"]))
        assertEquals(6L, int(original["oseq_to"]))
        assertEquals(2, h.outboxFiles().size)
        h.tick(2000)
        h.tick(2000)
        assertEquals(3, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
        val a = env(h.transport.batchRequests[1])
        val b = env(h.transport.batchRequests[2])
        val halves = listOf(a, b).sortedBy { int(it["oseq_from"]) }
        assertEquals(1L, int(halves[0]["oseq_from"]))
        assertEquals(3L, int(halves[0]["oseq_to"]))
        assertEquals(4L, int(halves[1]["oseq_from"]))
        assertEquals(6L, int(halves[1]["oseq_to"]))
        val iid = h.client.installId!!
        val sid = original["session_id"] as String
        assertEquals(Ids.batchId(iid, sid, Ids.BatchKind.PRIMARY, 1), halves[0]["batch_id"])
        assertEquals(Ids.batchId(iid, sid, Ids.BatchKind.PRIMARY, 4), halves[1]["batch_id"])
        // ctx 跟着含 error 的那一半；p0 先发
        assertEquals(4L, int(a["oseq_from"]))
        assertTrue(halves[1].lines.any { it["ctx"] == true })
        assertFalse(halves[0].lines.any { it["ctx"] == true })
        assertAllValid(runValidator(listOf(a, b)))
    }

    @Test
    fun rateLimitedInfoBackfillPausesOnlyThoseCategories() {
        val o = Options().apply { uploadLevel = LogLevel.INFO }
        val h = Harness(options = o)
        h.settle()
        h.transport.setScript(
            listOf(Reply.Status(429, mapOf("reason" to "quota", "categories" to listOf("info", "backfill"), "retry_after_s" to 60), mapOf("retry-after" to "60"))),
        )
        h.l(LogLevel.INFO, "pure info")
        h.sealAndDrain()
        assertEquals(1, h.transport.batchRequests.size)
        // backfill 批（debug 行在 info 模式下不是义务行）
        h.l(LogLevel.DEBUG, "debug 1")
        h.l(LogLevel.DEBUG, "debug 2")
        h.seal()
        h.work { it.materializeBackfill() }
        assertEquals(1, h.outboxFiles("p2").size)
        // p0 照发
        h.l(LogLevel.ERROR, "boom")
        h.sealAndDrain()
        h.tick(2000)
        assertEquals(2, h.transport.batchRequests.size)
        assertTrue(env(h.transport.batchRequests[1]).lines.any { it["level"] == "error" })
        h.tick(2000)
        assertEquals("info / backfill 仍暂停", 2, h.transport.batchRequests.size)
        assertEquals(1, h.outboxFiles("p1").size)
        assertEquals(1, h.outboxFiles("p2").size)
        val b = h.backoff
        assertEquals(setOf("info", "backfill"), b.pausedCategories.toSet())
        assertEquals("429 不计退避与毒批", 0, b.attempt)
        h.tick(73_000)
        h.tick(2000)
        assertEquals(4, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    @Test
    fun rateLimitedAllPausesEverything() {
        val h = Harness()
        h.settle()
        h.transport.setScript(listOf(Reply.Status(429, mapOf("reason" to "rate", "categories" to listOf("all"), "retry_after_s" to 30), mapOf("retry-after" to "30"))))
        h.l(LogLevel.ERROR, "e")
        h.sealAndDrain()
        val b = h.backoff
        assertEquals(listOf("all"), b.pausedCategories)
        val wait = b.pausedUntilMono - h.nowMono
        assertTrue("$wait", wait in 24_000..36_000)
        h.tick(23_000)
        assertEquals(1, h.transport.batchRequests.size)
        h.tick(14_000)
        assertEquals(2, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    @Test
    fun serviceUnavailableBackoffCurve() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Status(503, mapOf("reason" to "storage"))
        h.l(LogLevel.WARN, "w")
        h.sealAndDrain()
        val curve = ArrayList<Long>()
        for (i in 0 until 14) {
            val b = h.backoff
            val base = minOf(1000L shl i, Limits.BACKOFF_MAX_MS)
            val wait = b.nextAtMonoMs - h.nowMono
            curve.add(wait)
            assertTrue("attempt $i: $wait", wait >= base * 8 / 10 && wait <= base * 12 / 10)
            assertEquals(i + 1, b.attempt)
            h.tick(maxOf(wait, 2000))
            assertEquals(i + 2, h.transport.batchRequests.size)
        }
        println("[backoff] 503 curve (ms): $curve")
        assertEquals("503 不隔离、不删", 1, h.outboxFiles("p1").size)
        // Retry-After 大于退避时取 Retry-After（钳制 1 s–1 h）
        h.work { it.backoff.attempt = 0 }
        h.transport.defaultReply = Reply.Status(503, mapOf("reason" to "storage", "retry_after_s" to 120), mapOf("retry-after" to "120"))
        h.tick(Limits.BACKOFF_MAX_MS * 2)
        assertEquals(120_000L, h.backoff.nextAtMonoMs - h.nowMono)
    }

    @Test
    fun poisonBatchQuarantinedAfterFiveFailuresWhenOthersSucceed() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "poison")
        h.seal()
        h.clock.advance(10)
        h.l(LogLevel.WARN, "good")
        h.seal()
        val names = h.outboxFiles()
        assertEquals(2, names.size)
        val poisonId = names[0].substring(3 + 13 + 1, names[0].length - 3)
        h.transport.responder = { bid -> if (bid == poisonId) Reply.Status(500) else Reply.Echo }
        h.enableUpload()
        for (i in 0 until 30) {
            if (h.outboxFiles("q-").isNotEmpty()) break
            h.tick(Limits.BACKOFF_MAX_MS + 1)
        }
        assertEquals(5, h.transport.batchRequests.count { FakeTransport.batchId(it.body) == poisonId })
        assertEquals(1, h.outboxFiles("q-").size)
        assertTrue(h.outboxFiles("q-")[0].contains(poisonId))
        assertEquals(0, h.outboxFiles("p").size)
        // 隔离后不再发
        val before = h.transport.batchRequests.size
        h.tick(3_600_000)
        assertEquals(before, h.transport.batchRequests.size)
    }

    @Test
    fun allFailingIsServerOutageNotPoison() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Status(500)
        h.l(LogLevel.WARN, "w")
        h.sealAndDrain()
        repeat(8) { h.tick(Limits.BACKOFF_MAX_MS + 1) }
        assertEquals(9, h.transport.batchRequests.size)
        assertEquals(1, h.outboxFiles("p1").size)
        assertEquals(0, h.outboxFiles("q-").size)
    }

    @Test
    fun minimumSpacingTwoSeconds() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "a")
        h.seal()
        h.l(LogLevel.WARN, "b")
        h.seal()
        h.enableUpload()
        assertEquals(1, h.transport.batchRequests.size)
        h.tick(1999)
        assertEquals(1, h.transport.batchRequests.size)
        h.tick(1)
        assertEquals(2, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    @Test
    fun priorityOrder() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "p1 older")
        h.seal()
        h.l(LogLevel.DEBUG, "for backfill")
        h.seal()
        h.work { it.materializeBackfill() }
        h.clock.advance(5)
        h.l(LogLevel.ERROR, "p0")
        h.seal()
        h.clock.advance(5)
        h.l(LogLevel.WARN, "p1 newer")
        h.seal()
        assertEquals(listOf("p0", "p1", "p1", "p2"), h.outboxFiles().map { it.take(2) }.sorted())
        h.enableUpload()
        repeat(4) { h.tick(2000) }
        val order = h.transport.batchRequests.map { r ->
            val e = env(r)
            if (e["kind"] == "backfill") "p2" else e.lines.firstOrNull { !it.containsKey("ctx") }?.get("msg") as? String ?: "?"
        }
        assertEquals(listOf("p0", "p1 older", "p1 newer", "p2"), order)
    }

    @Test
    fun offlineNetworkErrorBacksOffWithoutSpinning() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Network
        h.l(LogLevel.WARN, "w")
        h.sealAndDrain()
        assertEquals(1, h.transport.batchRequests.size)
        // 退避期内反复 tick 不发请求
        repeat(5) { h.tick(100) }
        assertEquals(1, h.transport.batchRequests.size)
        // 网络恢复信号：提前唤醒
        h.transport.defaultReply = Reply.Echo
        h.client.platformEvent(org.revdog.retriever.core.PlatformEvent.NETWORK_RESTORED)
        h.settle()
        h.tick(2000)
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    @Test
    fun backoffPersistsAcrossRestartWithClamp() {
        val h = Harness()
        h.settle()
        h.work { e ->
            e.backoff.attempt = 12
            e.backoff.nextAtWallMs = e.clock.wallMs() + 3_600_000 // 时钟回拨等造成的远期
            e.persistBackoff()
        }
        val h2 = Harness(root = h.root, clock = h.clock)
        h2.settle()
        val b = h2.backoff
        assertEquals(12, b.attempt)
        assertEquals(Limits.BACKOFF_MAX_MS, b.nextAtMonoMs - h2.nowMono)
    }

    @Test
    fun quarantinedBatchReturnsAfter24h() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "q")
        h.seal()
        h.work { e -> e.quarantine(e.metas.values.first().name) }
        assertEquals(1, h.outboxFiles("q-").size)
        // mtime 按假时钟写入：24 h 后回到队列并被上传
        h.enableUpload()
        assertEquals(0, h.transport.batchRequests.size)
        h.tick(ClientConstants.QUARANTINE_RETRY_MS + 1)
        h.tick(2000)
        assertEquals(1, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
    }
}
