package org.revdog.retriever

import android.content.ContextWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.revdog.retriever.FakeTransport.Reply
import org.revdog.retriever.android.AndroidPlatform
import org.revdog.retriever.core.DropEntry
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.PlatformEvent
import org.revdog.retriever.core.RetrieverClient
import org.revdog.retriever.core.SealReason
import org.revdog.retriever.core.configPollDue
import org.revdog.retriever.core.configRequest
import org.revdog.retriever.core.evictIfNeeded
import org.revdog.retriever.core.stackTraceText
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch

/**
 * 发版前审查（`docs/audit/2026-09-30-prerelease/` A1–A5 + Android 独有的异常栈）的回归测试。
 * 每条在修复前分别是：递归爆栈 / 递归爆栈 / install 永久失效 / 0 ms 自旋 / 进入 1 h 暂停 / 合成行撞号永不上传 / 丢栈或死循环。
 * A3（低磁盘驱逐）在 EvictionTests。
 */
class PrereleaseRegressionTests : RtvTest() {
    private fun Harness.l(level: LogLevel, msg: String) = client.log(level, msg, null, null, null)

    private fun waitUntil(cond: () -> Boolean) {
        var n = 0
        while (!cond() && n < 2500) {
            Thread.sleep(2)
            n++
        }
    }

    private fun segLines(f: File): List<Map<String, Any?>> = f.readText().split("\n").drop(1).filter { it.isNotEmpty() }.map { JsonIn.obj(it)!! }

    // ---------------------------------------------------------------- A1 选批不递归

    @Test
    fun a1_installMissingStopsNotBootstrappedAndKeepsBatch() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Network
        h.l(LogLevel.WARN, "w")
        h.sealAndDrain()
        assertEquals(1, h.outboxFiles().size)
        val sent = h.transport.batchRequests.size
        // bootstrap 失败的状态（install.json 写不进 / 读不了）
        h.work { it.install = null }
        h.transport.defaultReply = Reply.Echo
        h.tick(60_000)
        assertEquals(Pair("not_bootstrapped", null as Long?), h.client.lastStop)
        assertEquals("不碰出站箱", sent, h.transport.batchRequests.size)
        assertEquals(1, h.outboxFiles().size)
    }

    @Test
    fun a1_unreadableBatchSkippedOthersStillSent() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "older, unreadable")
        h.seal()
        h.clock.advance(10)
        h.l(LogLevel.WARN, "newer, readable")
        h.seal()
        val names = h.outboxFiles()
        assertEquals(2, names.size)
        // JVM 上造「读不出」：同名换成目录（排在前面，先被选中）
        val bad = File(h.outbox, names[0])
        assertTrue(bad.delete())
        assertTrue(bad.mkdir())
        h.enableUpload()
        assertEquals(1, h.transport.batchRequests.size)
        assertEquals(listOf("newer, readable"), FakeTransport.envOf(h.transport.batchRequests[0].body)!!.lines.map { it["msg"] })
        assertEquals("读不出的不删", listOf(names[0]), h.outboxFiles())
        assertTrue(bad.isDirectory)
        assertNull("不计 fail", h.work { it.fails[names[0]] })
        // 全部读不出：停在 unreadable，不发请求
        h.tick(2000)
        assertEquals(Pair("unreadable", null as Long?), h.client.lastStop)
        assertEquals(1, h.transport.batchRequests.size)
        assertTrue(bad.isDirectory)
    }

    /**
     * 「无副本」前提（ADR 0019 决定 8）：会话 meta.json 都没了（或都是 0.1.x 写的、不带 install_id）时 install.json 损坏 →
     * 容器无法归属，清空 root 新建 install，留 `rtv.install_reset`（attrs 带作废数量）。有副本时走修复、不换 id，见 StateOwnershipTests。
     */
    @Test
    fun a1_corruptInstallJsonRebuiltWithSyntheticLine() {
        for (bad in listOf("{\"install_id\":\"not-a-uuid".toByteArray(), ByteArray(0))) {
            val h = Harness(key = "")
            h.settle()
            val old = h.client.installId!!
            val oldSid = h.client.writer.currentSessionId
            h.client.simulateCrash()
            for (d in File(h.root, "proc-main").listFiles()!!) File(d, "meta.json").delete()
            File(h.root, "install.json").writeBytes(bad)
            val h2 = Harness(root = h.root, key = "", clock = h.clock)
            h2.settle()
            val iid = h2.client.installId
            assertNotNull("bootstrap 成功", iid)
            assertTrue(Ids.isUuid(iid!!))
            assertNotEquals(old, iid)
            val inst = h2.readJson(File(h2.root, "install.json"))
            assertEquals(iid, inst["install_id"])
            assertEquals("计数器从 0 起再 +1", 1L, int(inst["session_counter"]))
            assertTrue(h2.client.supportCode!!.endsWith("-1"))
            assertFalse("旧会话随 root 清掉", File(File(h2.root, "proc-main"), oldSid).exists())
            val reset = segLines(h2.client.debugOpenSegmentFile!!).single { it["tag"] == "rtv.install_reset" }
            assertEquals("warn", reset["level"])
            assertEquals("install.json unreadable; local state discarded", reset["msg"])
            assertEquals(mapOf("batches" to 0.0, "sessions" to 1.0), reset["attrs"])
            assertEquals(true, reset["synthetic"])
            assertEquals("默认上传级别下是义务行", 1L, int(reset["oseq"]))
            // 随新 install_id 的批上报
            h2.seal()
            val env = h2.envelopes().single { it["session_id"] == h2.client.writer.currentSessionId }
            assertEquals(iid, env["install_id"])
            assertTrue(env.lines.any { it["tag"] == "rtv.install_reset" })
            Harness.closeAll()
        }
    }

    @Test
    fun a1_installJsonReadFailureDoesNotRebuild() {
        val h = Harness(key = "")
        h.settle()
        h.client.simulateCrash()
        val f = File(h.root, "install.json")
        assertTrue(f.delete())
        assertTrue(f.mkdir()) // 同名目录：存在但读抛 IOException
        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        assertNull("本次 bootstrap 失败、稍后重试", h2.client.installId)
        assertTrue("没被重建", f.isDirectory)
    }

    @Test
    fun a1_installJsonPermissionDeniedDoesNotRebuild() {
        val h = Harness(key = "")
        h.settle()
        h.client.simulateCrash()
        val f = File(h.root, "install.json")
        val saved = f.readBytes()
        f.setReadable(false)
        try {
            assumeFalse("root 用户读得到，跳过", f.canRead())
            val h2 = Harness(root = h.root, key = "", clock = h.clock)
            h2.settle()
            assertNull(h2.client.installId)
        } finally {
            f.setReadable(true)
        }
        assertArrayEquals("原文件未被替换", saved, f.readBytes())
    }

    // ---------------------------------------------------------------- A2 拉配置在途不自旋

    @Test
    fun a2_configRequestMarksAttemptImmediately() {
        val h = Harness()
        h.settle()
        h.clock.advance(Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        assertTrue(h.work { it.configPollDue(it.clock.monoMs()) })
        val dueAfter = h.work { e ->
            assertNotNull(e.configRequest())
            e.configPollDue(e.clock.monoMs())
        }
        assertFalse("构造请求即记为上次尝试时刻", dueAfter)
    }

    @Test
    fun a2_schedulerFloorWhileConfigFetchInFlight() {
        val now = 5_000_000L
        assertEquals(1000L, RetrieverClient.schedulerDelayMs(now - 60_000, now))
        assertEquals(1000L, RetrieverClient.schedulerDelayMs(now, now))
        assertEquals(1000L, RetrieverClient.schedulerDelayMs(now + 300, now))
        assertEquals(5000L, RetrieverClient.schedulerDelayMs(now + 5000, now))

        val t = FakeTransport()
        val h = Harness(transport = t)
        h.settle()
        val n0 = t.configRequests.size
        h.clock.advance(Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        val gate = CountDownLatch(1)
        t.configGate = gate
        try {
            h.clock.timerDelays.clear()
            // 离开 ≥ 30 min 回前台：到期拉配置，请求挂着不回
            h.client.platformEvent(PlatformEvent.WILL_ENTER_FOREGROUND)
            waitUntil { t.configRequests.size > n0 }
            assertEquals(n0 + 1, t.configRequests.size)
            h.client.onWork { h.client.reschedule() }
            assertFalse(h.work { it.configPollDue(it.clock.monoMs()) })
            val delays = h.clock.timerDelays.toList()
            assertTrue(delays.isNotEmpty())
            assertTrue("$delays", delays.all { it >= 1000 })
            assertTrue("在途时轮询候选是 30 min 之后", delays.last() >= Limits.CONFIG_POLL_INTERVAL_S * 1000L - 1000)
        } finally {
            gate.countDown()
        }
        h.settle()
    }

    // ---------------------------------------------------------------- A4 鉴权暂停只认带 reason 的 JSON

    @Test
    fun a4_authStatusWithoutJsonReasonBacksOffLikeOthers() {
        val cases = listOf(
            Reply.Status(403) to "http_403",
            Reply.Raw(401, "<html><body>Access denied | Cloudflare</body></html>".toByteArray()) to "http_401",
            Reply.Status(403, mapOf("error" to "forbidden")) to "http_403",
            Reply.Status(401, mapOf("reason" to 7)) to "http_401",
        )
        for ((reply, reason) in cases) {
            val h = Harness()
            h.settle()
            h.transport.setScript(listOf(reply))
            h.l(LogLevel.WARN, "w")
            h.sealAndDrain()
            assertEquals(1, h.transport.batchRequests.size)
            val b = h.backoff
            assertEquals(reason, b.reason)
            assertEquals("没有暂停类别", emptyList<String>(), b.pausedCategories)
            assertEquals(0L, b.pausedUntilMono)
            assertEquals(1, b.attempt)
            val wait = b.nextAtMonoMs - h.nowMono
            assertTrue("$reason $wait", wait in 800..1200)
            val name = h.outboxFiles().single()
            assertEquals("计入该批 fail", 1, h.work { it.fails[name]?.count })
            // 退避到期即重试（不是 1 h）
            h.tick(2000)
            assertEquals(2, h.transport.batchRequests.size)
            assertEquals(emptyList<String>(), h.outboxFiles())
            Harness.closeAll()
        }
    }

    @Test
    fun a4_authPauseWithReasonDoublesAndResetsAfterAck() {
        val h = Harness()
        h.settle()
        val denied = Reply.Status(401, mapOf("reason" to "key_invalid"))
        h.transport.setScript(listOf(denied))
        h.l(LogLevel.WARN, "w1")
        h.sealAndDrain()
        var b = h.backoff
        assertEquals(listOf("all"), b.pausedCategories)
        assertEquals(3_600_000L, b.pausedUntilMono - h.nowMono)
        // 到期后再遇 → 2 h
        h.transport.setScript(listOf(denied))
        h.tick(3_600_000)
        assertEquals(2, h.transport.batchRequests.size)
        assertEquals(7_200_000L, h.backoff.pausedUntilMono - h.nowMono)
        // 到期后成功确认一次 → 倍增状态复位、已到期的暂停清掉
        h.tick(7_200_000)
        assertEquals(3, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
        b = h.backoff
        assertEquals("", b.reason)
        assertEquals(emptyList<String>(), b.pausedCategories)
        assertEquals(0L, b.pausedUntilMono)
        assertEquals(0L, b.pausedUntilMs)
        val persisted = h.readJson(File(h.root, "backoff.json"))
        assertEquals("", persisted["reason"])
        assertEquals(0L, int(persisted["paused_until_ms"]))
        // 再遇带 reason 的 401 → 从 1 h 起步
        h.clock.advance(Limits.MIN_REQUEST_SPACING_MS)
        h.transport.setScript(listOf(Reply.Status(403, mapOf("reason" to "app_disabled"))))
        h.l(LogLevel.WARN, "w2")
        h.sealAndDrain()
        assertEquals(4, h.transport.batchRequests.size)
        assertEquals(3_600_000L, h.backoff.pausedUntilMono - h.nowMono)
    }

    // ---------------------------------------------------------------- A5 恢复时 oseq / seq 高水位

    /** 会话义务行已物化并确认 → RETAINED 段被驱逐 → 前台崩溃；openLines = OPEN 段里的非义务行数。 */
    private fun crashAfterEviction(openLines: Int): Triple<Harness, String, Pair<Long, Long>> {
        val h = Harness()
        h.settle()
        for (i in 1..3) h.l(LogLevel.WARN, "w$i")
        h.l(LogLevel.DEBUG, "d")
        h.sealAndDrain()
        assertEquals(1, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
        val sid = h.client.writer.currentSessionId
        val cursorFile = File(h.sessionDir(), "cursor.json")
        val extracted = int(h.readJson(cursorFile)["extracted_through_oseq"])
        assertEquals(3L, extracted)
        val evictedLastSeq = h.work { it.current!!.sealed.single().lastSeq }
        h.platform.available = 10L * 1024 * 1024
        h.work { it.evictIfNeeded() }
        assertEquals(emptyList<String>(), (h.sessionDir().list() ?: emptyArray()).filter { it.endsWith(".sealed") })
        assertEquals("被驱逐段的 lastSeq 并入 ctx 游标", evictedLastSeq, int(h.readJson(cursorFile)["ctx_through_seq"]))
        for (i in 0 until openLines) h.l(LogLevel.DEBUG, "after eviction $i")
        assertEquals("fg", h.readJson(cursorFile)["last_state"])
        h.client.simulateCrash()
        return Triple(h, sid, Pair(extracted, evictedLastSeq))
    }

    @Test
    fun a5_uncleanExitAfterEvictionContinuesOseq() {
        val (h, sid, marks) = crashAfterEviction(openLines = 2)
        val (extracted, evictedLastSeq) = marks
        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        val env = h2.envelopes().single { it["session_id"] == sid }
        val synth = env.lines.single { it["tag"] == "rtv.unclean_exit" }
        assertEquals(extracted + 1, int(synth["oseq"]))
        assertEquals(extracted + 1, int(env["oseq_from"]))
        assertEquals(extracted + 1, int(env["oseq_to"]))
        assertTrue(int(synth["seq"]) > evictedLastSeq)
        assertEquals("OPEN 段的非义务行作 ctx 随行", 2, env.lines.count { it["ctx"] == true })
        val closed = h2.readJsonl("sessions.jsonl").single { it["session_id"] == sid }
        assertEquals("unclean_fg", closed["exit"])
        assertEquals(extracted + 1, int(closed["last_oseq"]))
        assertEquals(int(synth["seq"]), int(closed["last_seq"]))
        assertAllValid(runValidator(listOf(env)))
    }

    @Test
    fun a5_seqHighWaterSurvivesEvictionWithEmptyOpenSegment() {
        val (h, sid, marks) = crashAfterEviction(openLines = 0)
        val (extracted, evictedLastSeq) = marks
        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        val env = h2.envelopes().single { it["session_id"] == sid }
        val synth = env.lines.single { it["tag"] == "rtv.unclean_exit" }
        assertEquals(extracted + 1, int(synth["oseq"]))
        assertEquals("盘上已无行：seq 接着被驱逐段编", evictedLastSeq + 1, int(synth["seq"]))
        val closed = h2.readJsonl("sessions.jsonl").single { it["session_id"] == sid }
        assertEquals(extracted + 1, int(closed["last_oseq"]))
        assertEquals(evictedLastSeq + 1, int(closed["last_seq"]))
    }

    /** 尾部义务行写失败且墓碑已落盘（盘上最大 oseq 低于墓碑）：合成行不复用被墓碑覆盖的 oseq，也不另记 corrupt。 */
    @Test
    fun a5_tombstoneAboveDiskMaxRaisesHighWater() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "on disk")
        val sid = h.client.writer.currentSessionId
        h.work { it.appendDrops(listOf(DropEntry(sid, 2, 3, 2, "write_failed", h.clock.wallMs(), -1))) }
        h.client.simulateCrash()
        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        val envs = h2.envelopes().filter { it["session_id"] == sid }
        val synth = envs.flatMap { it.lines }.single { it["tag"] == "rtv.unclean_exit" }
        assertEquals(4L, int(synth["oseq"]))
        assertEquals(4L, int(h2.readJsonl("sessions.jsonl").single { it["session_id"] == sid }["last_oseq"]))
        assertEquals("墓碑已覆盖 2..3，不另记 corrupt", listOf("write_failed"), h2.readJsonl("drops.jsonl").filter { it["session_id"] == sid }.map { it["reason"] })
    }

    // ---------------------------------------------------------------- 异常栈（Android 独有）

    @Test(timeout = 10_000)
    fun stackTextKeepsUnknownHostAndTerminatesOnCycles() {
        val net = IOException("sync failed", UnknownHostException("logs.revdog.org"))
        val s = stackTraceText(net)
        assertTrue(s, s.contains("java.net.UnknownHostException: logs.revdog.org"))
        assertTrue(s.contains("sync failed"))
        // cause 成环
        val a = RuntimeException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)
        val c = stackTraceText(a)
        assertTrue(c, c.contains("CIRCULAR REFERENCE"))
        // 取栈本身抛异常：空串，不外抛
        val evil = object : RuntimeException("x") {
            override fun toString(): String = throw IllegalStateException("toString boom")
        }
        assertEquals("", stackTraceText(evil))
        // AndroidPlatform 走同一个实现（android.jar 桩返回默认值，只调纯 JVM 的那个方法）
        val p = AndroidPlatform(ContextWrapper(null))
        assertEquals(s, p.stackTraceString(net))
        assertTrue(p.stackTraceString(a).contains("CIRCULAR REFERENCE"))
    }

    @Test
    fun stackOfNetworkErrorReachesExc() {
        val h = Harness(key = "")
        h.settle()
        h.client.log(LogLevel.ERROR, "net", null, null, IOException("sync failed", UnknownHostException("logs.revdog.org")))
        h.seal(SealReason.ERROR)
        val line = h.envelopes("p0").single().lines.single { it["msg"] == "net" }
        val exc = obj(line["exc"])
        assertEquals("java.io.IOException", exc["type"])
        assertTrue((exc["stack"] as String).contains("Caused by: java.net.UnknownHostException"))
    }
}
