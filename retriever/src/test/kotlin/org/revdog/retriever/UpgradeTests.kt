package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.Device
import org.revdog.retriever.core.Engine
import org.revdog.retriever.core.EnvelopeHeader
import org.revdog.retriever.core.Gzip
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.OutboxName
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 旧版本原地升级到 0.3.0（主代理补充项 U）：0.2.0 / 0.1.x 写下的本地状态逐字节构造（格式取 HEAD 的实现），升级后第一次启动
 * 不崩、不丢、不换 install；backoff / mapping / 配置缓存没有 key 指纹字段 = 视为与当前 key 相同并补写，不触发复位。
 */
class UpgradeTests : RtvTest() {
    private val key = "lk_test_demo_abc_12345678"

    private class Layout(val installId: String, val oldSid: String, val batchName: String, val device: Device)

    /** 0.2.0 设备快照（sdk 字段是旧版本号；FakePlatform 的其它字段）。 */
    private fun device(sdk: String) = Device("android", "15", "Google Pixel-test", "1.2.3", "45", "zh-CN", sdk)

    /**
     * 0.2.0 的目录布局：install.json、有未封段的前台旧会话（meta 带 install_id、cursor last_state = fg）、出站箱一个 p1 批（覆盖
     * 旧会话 oseq 1..2）、config.json（无 from_host / key_fp）、backoff.json（无 key_fp；[auth] = 鉴权暂停中，否则普通退避）、
     * mapping.json（无 key_fp；对新版本设备快照已确认）、drops.jsonl / sessions.jsonl、purge 残留。[v01] = 0.1.x：meta 无 install_id、
     * backoff 无 last_ack_ms。
     */
    private fun writeLayout(root: File, now: Long, auth: Boolean, v01: Boolean): Layout {
        val iid = Ids.newV4()
        val sid = Ids.newV4()
        val dev = device(if (v01) "retriever-android/0.1.2" else "retriever-android/0.2.0")
        root.mkdirs()
        File(root, "install.json").writeText("{\"install_id\":\"$iid\",\"session_counter\":7,\"created_ms\":${now - 86_400_000}}")
        val dir = File(File(root, "proc-main"), sid).also { it.mkdirs() }
        val devJson = "{\"os\":\"android\",\"os_version\":\"15\",\"model\":\"Google Pixel-test\",\"app_version\":\"1.2.3\",\"build\":\"45\",\"locale\":\"zh-CN\",\"sdk\":\"${dev.sdk}\"}"
        File(dir, "meta.json").writeText(
            "{\"session_id\":\"$sid\",\"session_no\":7,\"started_ms\":${now - 60_000},\"device\":$devJson,\"process\":\"main\"" +
                (if (v01) "" else ",\"install_id\":\"$iid\"") + "}",
        )
        File(dir, "cursor.json").writeText("{\"extracted_through_oseq\":2,\"ctx_through_seq\":2,\"last_state\":\"fg\",\"last_state_ms\":${now - 30_000}}")
        // seg-000001.sealed：已物化进出站箱的两行；seg-000002.open：未封段（oseq 3 + 一行 debug + 撕裂的残行）
        File(dir, "seg-000001.sealed").writeText(
            "{\"v\":1,\"seg_no\":1,\"user_id\":null,\"started_ms\":${now - 60_000}}\n" +
                "{\"seq\":1,\"oseq\":1,\"ts\":${now - 59_000},\"level\":\"warn\",\"msg\":\"old 1\"}\n" +
                "{\"seq\":2,\"oseq\":2,\"ts\":${now - 58_000},\"level\":\"error\",\"msg\":\"old 2\"}\n",
        )
        File(dir, "seg-000002.open").writeText(
            "{\"v\":1,\"seg_no\":2,\"user_id\":null,\"started_ms\":${now - 50_000}}\n" +
                "{\"seq\":3,\"oseq\":3,\"ts\":${now - 40_000},\"level\":\"warn\",\"msg\":\"old 3 unsealed\"}\n" +
                "{\"seq\":4,\"ts\":${now - 39_000},\"level\":\"debug\",\"msg\":\"old 4 debug\"}\n" +
                "{\"seq\":5,\"oseq\":4,\"ts\":${now - 38_000},\"level\":\"warn\",\"msg\":\"torn",
        )
        // 出站箱：旧会话 oseq 1..2 的批（0.2.0 的物化格式）
        val bid = Ids.batchId(iid, sid, Ids.BatchKind.PRIMARY, 1)!!
        val created = now - 57_000
        val h = EnvelopeHeader(Ids.BatchKind.PRIMARY, bid, created, org.revdog.retriever.core.Day.clientDay(now - 59_000, created), iid, sid, 7, "main", null, dev, 1, 2, 1, 2, 0)
        val lines = listOf(
            "{\"seq\":1,\"oseq\":1,\"ts\":${now - 59_000},\"level\":\"warn\",\"msg\":\"old 1\"}",
            "{\"seq\":2,\"oseq\":2,\"ts\":${now - 58_000},\"level\":\"error\",\"msg\":\"old 2\"}",
        ).map { it.toByteArray() }
        val name = OutboxName.make(0, created, bid)
        File(root, "outbox").mkdirs()
        File(File(root, "outbox"), name).writeBytes(Gzip.compress(h.encode(lines))!!)
        File(root, "config.json").writeText(
            "{\"fetched_ms\":${now - 60_000},\"config\":{\"etag\":\"old-etag\",\"ttl_s\":1800,\"upload_enabled\":true,\"upload_level\":\"info\"," +
                "\"local_level\":\"debug\",\"context_lines\":200,\"context_bytes\":131072,\"flush_interval_s\":300,\"local_cap_bytes\":20971520," +
                "\"full_dump\":false,\"full_dump_ttl_s\":0,\"daily_batch_cap\":0}}",
        )
        val lastAck = if (v01) "" else ",\"last_ack_ms\":${now - 120_000}"
        File(root, "backoff.json").writeText(
            if (auth) {
                "{\"attempt\":0,\"next_at_wall_ms\":0,\"next_at_mono_ms\":0,\"paused_until_ms\":${now + 3_600_000},\"paused_categories\":[\"all\"],\"reason\":\"auth:3600000\"$lastAck}"
            } else {
                "{\"attempt\":2,\"next_at_wall_ms\":${now + 4000},\"next_at_mono_ms\":0,\"paused_until_ms\":0,\"paused_categories\":[],\"reason\":\"network\"$lastAck}"
            },
        )
        val newDev = device("retriever-android/${RetrieverVersion.CURRENT}")
        File(root, "mapping.json").writeText("{\"user_id\":null,\"device_digest\":\"${Engine.digest(newDev)}\",\"acked_ms\":${now - 3_600_000}}")
        File(root, "drops.jsonl").writeText("{\"session_id\":\"${Ids.newV4()}\",\"oseq_from\":9,\"oseq_to\":9,\"n\":1,\"reason\":\"corrupt\",\"at_ms\":1,\"last_ack_age_ms\":-1}\n")
        File(root.absoluteFile.parentFile, root.name + ".purge-${Ids.newV4()}").mkdirs()
        return Layout(iid, sid, name, dev)
    }

    private fun upgrade(auth: Boolean, v01: Boolean = false, disabled: Boolean = false): Pair<StaticHarness, Layout> {
        val clock = FakeClock()
        val t = FakeTransport().apply {
            configBody = mapOf("etag" to "new", "ttl_s" to 1800)
            configGate = CountDownLatch(1) // 配置响应先不回：看升级后读到的缓存
        }
        val root = tempDir("rtv-upgrade")
        val layout = writeLayout(root, clock.wallMs(), auth, v01)
        val marker = File(root.absoluteFile.parentFile, root.name + ".disabled")
        if (disabled) marker.createNewFile()
        val h = StaticHarness(root = root, clock = clock, transport = t)
        h.configure(key, Options().apply { uploadLevel = LogLevel.WARN })
        h.client.onWork { }
        return Pair(h, layout)
    }

    private fun finish(h: StaticHarness) {
        h.transport.configGate?.countDown()
        h.transport.configGate = null
        h.settle()
        File(h.root.absoluteFile.parentFile, h.root.name + ".disabled").delete()
    }

    @Test
    fun u_v020UpgradeKeepsEverything() {
        val (h, l) = upgrade(auth = false)
        try {
            // install 不变、计数续上
            assertEquals(l.installId, Retriever.installId)
            assertEquals(8L, int(JsonIn.obj(File(h.root, "install.json").readBytes())!!["session_counter"]))
            assertTrue(Retriever.supportCode!!.endsWith("-8"))
            // 配置缓存照用（无 from_host = 远程值全量生效、无 key_fp = 当前 key），并补写当前指纹
            val c = h.client.engine.configCache!!
            assertFalse("没有被当成换了 key", c.identityStale)
            assertEquals("old-etag", c.config.etag)
            assertEquals(emptySet<String>(), c.fromHost)
            assertEquals(LogLevel.INFO, Retriever.uploadLevel)
            val cfg = JsonIn.obj(File(h.root, "config.json").readBytes())!!
            assertEquals(Ids.keyFingerprint(key), cfg["key_fp"])
            assertEquals(Harness.BASE, cfg["base_url"])
            // backoff：普通退避不清（升级不复位），补写指纹
            val b = JsonIn.obj(File(h.root, "backoff.json").readBytes())!!
            assertEquals(2L, int(b["attempt"]))
            assertEquals("network", b["reason"])
            assertEquals(Ids.keyFingerprint(key), b["key_fp"])
            // mapping：已确认的不因缺指纹而重发
            val m = JsonIn.obj(File(h.root, "mapping.json").readBytes())!!
            assertEquals(Ids.keyFingerprint(key), m["key_fp"])
            // 旧会话照常恢复：截残行（oseq 4 记 corrupt）、前台死亡合成 unclean_exit、oseq 接着编
            val old = sessionLines(File(File(h.root, "proc-main"), l.oldSid))
            assertEquals(listOf("old 1", "old 2", "old 3 unsealed", "old 4 debug", "process ended while foregrounded"), old.map { it["msg"] })
            assertEquals(listOf(1L, 2L, 3L, -999L, 5L), old.map { int(it["oseq"]) })
            assertEquals("unclean_fg", h.readJsonl("sessions.jsonl").single { it["session_id"] == l.oldSid }["exit"])
            assertTrue(h.readJsonl("drops.jsonl").any { it["session_id"] == l.oldSid && int(it["oseq_from"]) == 4L && it["reason"] == "corrupt" })
            assertTrue("purge 残留清掉", h.root.absoluteFile.parentFile!!.list()!!.none { it.startsWith(h.root.name + ".purge-") })
        } finally {
            finish(h)
        }
        // 退避到期后旧批与恢复出的批照常上传；新会话的批不重发映射
        h.log(LogLevel.WARN, "new session line")
        h.client.writer.rotate(org.revdog.retriever.core.SealReason.TIMER)
        h.client.onWork { h.client.afterSeal() }
        h.clock.advance(5000)
        repeat(6) {
            h.client.tickNow()
            h.clock.advance(2000)
        }
        assertEquals(emptyList<String>(), h.outboxFiles())
        val sent = h.transport.batchRequests.map { FakeTransport.envOf(it.body)!! }
        assertTrue("旧出站箱批照常上传", sent.any { it["batch_id"] == Ids.batchId(l.installId, l.oldSid, Ids.BatchKind.PRIMARY, 1) })
        val covered = sent.filter { it["session_id"] == l.oldSid }.flatMap { e -> e.lines.filter { it["ctx"] != true }.map { int(it["oseq"]) } }.sorted()
        assertEquals("旧会话 oseq 连续（4 是残行墓碑）", listOf(1L, 2L, 3L, 5L), covered)
        val own = sent.single { it["session_id"] == h.client.writer.currentSessionId }
        assertNull("映射已确认过：新会话首批不重发", own["mapping"])
        for (r in h.transport.batchRequests) assertEquals(FakeTransport.envOf(r.body)!!["install_id"], r.headers["X-Rtv-Install"])
    }

    /** 0.2.0 的鉴权暂停：升级不清（缺指纹 = 同一把 key），暂停期内不上传。 */
    @Test
    fun u_authPauseSurvivesUpgrade() {
        val (h, _) = upgrade(auth = true)
        try {
            val b = h.client.engine.backoff
            assertEquals(listOf("all"), b.pausedCategories)
            assertEquals("auth:3600000", b.reason)
            h.client.kickDrain()
            h.client.onWork { }
        } finally {
            finish(h)
        }
        assertEquals("paused", h.client.lastStop.first)
        assertEquals(0, h.transport.batchRequests.size)
        assertEquals(Ids.keyFingerprint(key), JsonIn.obj(File(h.root, "backoff.json").readBytes())!!["key_fp"])
    }

    /** 0.2.0 的禁用标记：升级后照样禁用（不写、不传），标记不动。 */
    @Test
    fun u_disabledMarkerSurvivesUpgrade() {
        val (h, l) = upgrade(auth = false, disabled = true)
        try {
            assertFalse(Retriever.isEnabled)
            h.log(LogLevel.ERROR, "not written")
            assertEquals(0L, h.client.debugCounters.first)
            assertEquals(l.installId, Retriever.installId)
            assertTrue(File(h.root.absoluteFile.parentFile, h.root.name + ".disabled").exists())
        } finally {
            finish(h)
        }
        assertEquals(0, h.transport.allRequests.count { it.url.endsWith("/v1/batches") })
        assertTrue("旧批留着", h.outboxFiles().contains(l.batchName))
    }

    /** 0.1.x 布局（meta 无 install_id、backoff 无 last_ack_ms）：同样照读。 */
    @Test
    fun u_v01UpgradeKeepsInstall() {
        val (h, l) = upgrade(auth = false, v01 = true)
        try {
            assertEquals(l.installId, Retriever.installId)
            val old = sessionLines(File(File(h.root, "proc-main"), l.oldSid))
            assertEquals(5, old.size)
            assertEquals("rtv.unclean_exit", old.last()["tag"])
            assertEquals(-1L, int(JsonIn.obj(File(h.root, "backoff.json").readBytes())!!["last_ack_ms"]))
            assertFalse(h.client.engine.configCache!!.identityStale)
        } finally {
            finish(h)
        }
    }
}
