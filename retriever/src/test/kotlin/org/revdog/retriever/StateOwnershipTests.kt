package org.revdog.retriever

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.revdog.retriever.core.ClosedSession
import org.revdog.retriever.core.DropEntry
import org.revdog.retriever.core.Fs
import org.revdog.retriever.core.Jsonl
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.MappingState
import org.revdog.retriever.core.SessionMeta
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * 本地状态自带真实归属（ADR 0019；底稿 `docs/audit/2026-09-30-fix-batch/blind-sdk-state.md` §2 / §6 / §7）：
 * install 身份从 meta.json 副本修复、无副本清空、清空先改名再删、配置属于请求时的身份、段读不出不推进、jsonl 追加失败截回。
 */
class StateOwnershipTests : RtvTest() {
    private fun Harness.l(level: LogLevel, msg: String) = client.log(level, msg, null, null, null)

    private fun segLines(f: File): List<Map<String, Any?>> = f.readText().split("\n").drop(1).filter { it.isNotEmpty() }.map { org.revdog.retriever.core.JsonIn.obj(it)!! }

    private fun waitUntil(cond: () -> Boolean) {
        var n = 0
        while (!cond() && n < 2500) {
            Thread.sleep(2)
            n++
        }
    }

    // ---------------------------------------------------------------- §2 install 身份

    /**
     * 旧会话有已确认批 + 出站箱 1 批 + 前台崩溃 → 损坏 install.json → 重启后 installId 不变、计数 = 旧 + 1、
     * 所有请求头 == 信封 install、新会话的批不重复发映射；留 `rtv.install_repaired`（ADR 0019 决定 7）。旧实现换新 id。
     */
    @Test
    fun corruptInstallJsonRepairsIdentityFromMeta() {
        val h = Harness()
        h.settle()
        h.l(LogLevel.WARN, "acked")
        h.sealAndDrain()
        assertEquals(1, h.transport.batchRequests.size)
        assertTrue(File(h.root, "mapping.json").exists())
        h.transport.defaultReply = FakeTransport.Reply.Network
        h.clock.advance(3000)
        h.l(LogLevel.WARN, "pending in outbox")
        h.sealAndDrain()
        assertEquals(1, h.outboxFiles().size)
        h.l(LogLevel.WARN, "unsealed, foreground")
        val old = h.client.installId!!
        val oldSid = h.client.writer.currentSessionId
        assertEquals(old, h.readJson(File(h.sessionDir(), "meta.json"))["install_id"])
        h.client.simulateCrash()
        File(h.root, "install.json").writeBytes("{\"install_id\":\"not-a-uuid".toByteArray())

        val h2 = Harness(root = h.root, clock = h.clock)
        h2.settle()
        assertEquals("不换 id", old, h2.client.installId)
        val inst = h2.readJson(File(h2.root, "install.json"))
        assertEquals(old, inst["install_id"])
        assertEquals("计数续上", 2L, int(inst["session_counter"]))
        assertTrue(h2.client.supportCode!!.endsWith("-2"))
        val repaired = segLines(File(h2.sessionDir(), "seg-000001.open")).single { it["tag"] == "rtv.install_repaired" }
        assertEquals("warn", repaired["level"])
        assertEquals("install.json unreadable; identity repaired from session meta", repaired["msg"])
        assertEquals(true, repaired["synthetic"])
        assertTrue("映射照常有效", File(h2.root, "mapping.json").exists())
        h2.sealAndDrain()
        repeat(4) { h2.tick(2000) }
        assertEquals(emptyList<String>(), h2.outboxFiles())
        val reqs = h2.transport.batchRequests
        assertEquals("旧出站箱批 + 旧会话恢复批 + 新会话批", 3, reqs.size)
        for (r in reqs) {
            val e = FakeTransport.envOf(r.body)!!
            assertEquals(old, e["install_id"])
            assertEquals(e["install_id"], r.headers["X-Rtv-Install"])
        }
        val own = reqs.map { FakeTransport.envOf(it.body)!! }.single { it["session_id"] == h2.client.writer.currentSessionId || it.lines.any { l -> l["tag"] == "rtv.install_repaired" } }
        assertNull("映射已确认过，新会话首批不重复发", own["mapping"])
        assertTrue(reqs.map { FakeTransport.envOf(it.body)!! }.any { it["session_id"] == oldSid && it.lines.any { l -> l["tag"] == "rtv.unclean_exit" } })
    }

    /** 有 meta 存在却读不了：同 install.json 读不了——本次失败、稍后重试，绝不重建（ADR 0019 决定 7）。 */
    @Test
    fun unreadableMetaDuringRepairFailsBootstrap() {
        val h = Harness(key = "")
        h.settle()
        h.client.simulateCrash()
        val meta = File(h.sessionDir(), "meta.json")
        assertTrue(meta.delete())
        assertTrue(meta.mkdir()) // 同名目录：存在但读抛 IOException
        val bad = "{\"install_id\":\"not-a-uuid".toByteArray()
        File(h.root, "install.json").writeBytes(bad)
        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        assertNull("本次 bootstrap 失败、稍后重试", h2.client.installId)
        assertArrayEquals("没被重建", bad, File(h.root, "install.json").readBytes())
    }

    /**
     * 修复身份时会话目录 stat 不了（无读 / 搜索权限，`File.exists()` 对 stat 错误返回 false）：同 meta 读不了——本次失败、
     * 稍后重试，不当成「无副本」清空 root（ADR 0019 决定 7）。旧实现用 exists() 判断，读不了被当成没有副本而清空。
     */
    @Test
    fun unsearchableSessionDirDuringRepairFailsBootstrap() {
        val h = Harness(key = "")
        h.settle()
        h.client.simulateCrash()
        val dir = h.sessionDir()
        val bad = "{\"install_id\":\"not-a-uuid".toByteArray()
        File(h.root, "install.json").writeBytes(bad)
        assertTrue(dir.setReadable(false, false) && dir.setExecutable(false, false))
        try {
            assumeFalse("root 用户不受目录权限限制，跳过", File(dir, "meta.json").exists())
            val h2 = Harness(root = h.root, key = "", clock = h.clock)
            h2.settle()
            assertNull("本次 bootstrap 失败、稍后重试", h2.client.installId)
            assertArrayEquals("没被清空重建", bad, File(h.root, "install.json").readBytes())
        } finally {
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }
    }

    /**
     * 无副本（只留 0.1.x 写的、不带 install_id 的 meta）+ jsonl + mapping + 出站箱 → 清空：重启后 root 只剩新状态，
     * 首批带映射、无外来终态 / 墓碑，`rtv.install_reset` 的 attrs 记作废数量（ADR 0019 决定 8）。
     */
    @Test
    fun corruptInstallJsonWithoutEvidencePurges() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "old batch")
        h.seal()
        h.l(LogLevel.WARN, "old unsealed")
        val oldSid = h.client.writer.currentSessionId
        h.work { e ->
            e.appendDrops(listOf(DropEntry(oldSid, 9, 9, 1, "corrupt", 1, -1)))
            e.appendClosed(listOf(ClosedSession(org.revdog.retriever.core.Ids.newV4(), 1, 1, 2, 3, 1, "clean_bg")))
        }
        Fs.writeAtomic(File(h.root, "mapping.json"), MappingState(null, "0123456789abcdef", h.clock.wallMs()).encode())
        val old = h.client.installId!!
        h.client.simulateCrash()
        // 降级成 0.1.x 的 meta（没有 install_id 键）
        val metaFile = File(h.sessionDir(), "meta.json")
        val m = SessionMeta.decode(metaFile.readBytes())!!
        metaFile.writeBytes(m.copy(installId = null).encode())
        File(h.root, "install.json").writeBytes(ByteArray(0))

        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        val iid = h2.client.installId!!
        assertNotEquals(old, iid)
        assertEquals(emptyList<String>(), h2.outboxFiles())
        assertFalse(File(h2.root, "drops.jsonl").exists())
        assertFalse(File(h2.root, "sessions.jsonl").exists())
        assertFalse(File(h2.root, "mapping.json").exists())
        assertEquals(listOf(h2.client.writer.currentSessionId), File(h2.root, "proc-main").list()!!.toList())
        assertTrue("改名出去的旧 root 已删", h2.root.absoluteFile.parentFile!!.list()!!.none { it.startsWith(h2.root.name + ".purge-") })
        val reset = segLines(h2.client.debugOpenSegmentFile!!).single { it["tag"] == "rtv.install_reset" }
        assertEquals("install.json unreadable; local state discarded", reset["msg"])
        assertEquals(mapOf("batches" to 1.0, "sessions" to 1.0), reset["attrs"])
        h2.seal()
        val env = h2.envelopes().single()
        assertEquals(iid, env["install_id"])
        assertTrue("首批带映射", env.map.containsKey("mapping"))
        assertFalse("无外来终态", env.map.containsKey("closed_sessions"))
        assertFalse("无外来墓碑", env.map.containsKey("drops"))
        assertAllValid(runValidator(listOf(env)))
    }

    /** 启动时清掉 `<root>.purge-*` 残留；purgeLocal 之后 root 下只有新 install 的文件（ADR 0019 决定 9）。 */
    @Test
    fun purgeLeftoverIsCleaned() {
        val root = tempDir()
        val leftover = File(root.absoluteFile.parentFile, root.name + ".purge-x")
        assertTrue(File(leftover, "proc-main").mkdirs())
        File(leftover, "install.json").writeText("{}")
        val h = Harness(root = root)
        h.settle()
        assertFalse(leftover.exists())
        h.l(LogLevel.WARN, "before purge")
        h.transport.defaultReply = FakeTransport.Reply.Network
        h.sealAndDrain()
        assertEquals(1, h.outboxFiles().size)
        val old = h.client.installId
        h.purgeBlocking()
        assertNotEquals(old, h.client.installId)
        assertEquals(listOf("install.json", "outbox", "proc-main"), root.list()!!.filter { !it.startsWith(".") && it != "upload.lock" }.sorted())
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertTrue(root.absoluteFile.parentFile!!.list()!!.none { it.startsWith(root.name + ".purge-") })
    }

    // ---------------------------------------------------------------- §6 配置属于请求时的身份

    /**
     * 启动拉取挂起 → setUser("U") → 放行后出现第二个带 `X-Rtv-User: U` 的配置请求；同值 setUser 不产生额外请求（ADR 0019 决定 12）。
     * 旧实现 setUser 不拉配置，在途请求还会吞掉新请求。
     */
    @Test
    fun setUserRefetchesConfigForNewIdentity() {
        val t = FakeTransport().apply { configBody = mapOf("etag" to "e0", "ttl_s" to 600) }
        val gate = CountDownLatch(1)
        t.configGate = gate
        val h = Harness(transport = t)
        waitUntil { t.configRequests.size == 1 }
        assertEquals(1, t.configRequests.size)
        assertNull(t.configRequests[0].headers["X-Rtv-User"])
        h.client.setUser("U")
        t.configGate = null
        gate.countDown()
        h.settle()
        waitUntil { t.configRequests.size >= 2 }
        h.settle()
        assertEquals(2, t.configRequests.size)
        assertEquals("U", t.configRequests[1].headers["X-Rtv-User"])
        h.client.setUser("U")
        h.settle()
        assertEquals("同值 setUser 不重拉", 2, t.configRequests.size)
        h.client.setUser(null)
        h.settle()
        assertEquals("登出也是身份变化", 3, t.configRequests.size)
        assertNull(t.configRequests[2].headers["X-Rtv-User"])
    }

    /** A 的配置响应在切到 B 之后才到：丢弃不生效并按 B 重拉（B 下 full_dump 不激活，也不生成 backfill）。 */
    @Test
    fun staleResponseForPreviousUserNotApplied() {
        val t = FakeTransport().apply { configBody = mapOf("etag" to "e0", "ttl_s" to 600) }
        val h = Harness(transport = t)
        h.settle()
        h.l(LogLevel.DEBUG, "history for backfill")
        h.seal()
        val gate = CountDownLatch(1)
        t.configGate = gate
        t.configBody = mapOf("etag" to "for-a", "ttl_s" to 600, "full_dump" to true, "full_dump_ttl_s" to 3600)
        h.client.setUser("A")
        waitUntil { t.configRequests.size == 2 }
        t.configBody = mapOf("etag" to "for-b", "ttl_s" to 600)
        h.client.setUser("B")
        t.configGate = null
        gate.countDown()
        waitUntil { t.configRequests.size >= 3 }
        h.settle()
        assertEquals(3, t.configRequests.size)
        assertEquals("A", t.configRequests[1].headers["X-Rtv-User"])
        assertEquals("B", t.configRequests[2].headers["X-Rtv-User"])
        val eff = h.work { it.effective }
        assertFalse("A 的 full_dump 没作用到 B", eff.fullDumpActive)
        assertEquals("for-b", eff.config.etag)
        assertEquals(0, h.outboxFiles("p2").size)
    }

    /** 身份变化的那一刻配置缓存按过期处理：上一个用户的 full_dump 立即回落，不等新响应（ADR 0019 决定 12）。 */
    @Test
    fun identityChangeExpiresCachedConfigImmediately() {
        val t = FakeTransport().apply { configBody = mapOf("etag" to "e0", "ttl_s" to 600) }
        val h = Harness(transport = t)
        h.settle()
        t.configBody = mapOf("etag" to "for-a", "ttl_s" to 600, "full_dump" to true, "full_dump_ttl_s" to 3600)
        h.client.setUser("A")
        h.settle()
        assertTrue(h.work { it.effective.fullDumpActive })
        assertEquals(LogLevel.DEBUG, h.client.effectiveLevels.first)
        val gate = CountDownLatch(1)
        t.configGate = gate
        try {
            h.client.setUser("B")
            waitUntil { t.configRequests.last().headers["X-Rtv-User"] == "B" }
            val eff = h.work { it.effective }
            assertFalse("新响应还没到，A 的放大型字段已回落", eff.fullDumpActive)
            assertTrue(eff.expired)
            assertEquals(LogLevel.WARN, h.client.effectiveLevels.first)
        } finally {
            t.configGate = null
            gate.countDown()
        }
        h.settle()
    }

    /**
     * 身份变化只回落放大上传的字段，不回落 local_cap_bytes：缓存的远程上限 = 2× 宿主默认、出站箱义务批超过宿主默认，
     * setUser 之后不驱逐、不记 buffer_overflow；真正的 TTL 过期照旧回落到宿主默认并驱逐。
     * 旧实现 setUser 把上限立即压回宿主默认，义务批被删。
     */
    @Test
    fun setUserDoesNotEvictOnCapFallback() {
        val hostCap = Limits.LOCAL_CAP_BYTES_MIN
        val t = FakeTransport().apply {
            configBody = mapOf("etag" to "big", "ttl_s" to 600, "upload_enabled" to false, "local_cap_bytes" to 2 * hostCap)
        }
        val h = Harness(transport = t, options = Options().apply { localCapBytes = hostCap.toLong() })
        h.settle()
        assertEquals(2 * hostCap, h.work { it.effective.config.localCapBytes })
        // 随机内容（gzip 压不动）攒出超过宿主默认的义务批
        val rnd = java.util.Random(7)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        fun noise() = String(CharArray(3500) { alphabet[rnd.nextInt(alphabet.length)] })
        fun boxBytes() = h.outboxFiles().sumOf { File(h.outbox, it).length() }
        while (boxBytes() < hostCap + hostCap / 4) {
            repeat(100) { h.l(LogLevel.WARN, noise()) }
            h.seal()
        }
        fun overflow() = h.readJsonl("drops.jsonl").filter { it["reason"] == "buffer_overflow" }
        assertEquals(emptyList<Map<String, Any?>>(), overflow())
        val before = h.outboxFiles()
        val gate = CountDownLatch(1)
        t.configGate = gate
        try {
            h.client.setUser("b")
            waitUntil { t.configRequests.last().headers["X-Rtv-User"] == "b" }
            val eff = h.work { it.effective }
            assertTrue("身份变了：其它放大型字段按过期处理", eff.expired)
            assertEquals("上限不随身份回落", 2 * hostCap, eff.config.localCapBytes)
        } finally {
            t.configGate = null
            gate.countDown()
        }
        h.settle()
        assertEquals("一个批都没删", before, h.outboxFiles())
        assertEquals(emptyList<Map<String, Any?>>(), overflow())
        // 真正的 TTL 过期：上限回落宿主默认，照旧驱逐并记墓碑
        t.configBody = null
        h.tick(600_000L + 1000)
        assertEquals(hostCap, h.work { it.effective.config.localCapBytes })
        assertTrue(overflow().isNotEmpty())
        assertTrue(h.outboxFiles().size < before.size)
    }

    // ---------------------------------------------------------------- §7 同一段代码里的同类问题

    /**
     * 恢复时墓碑写不进 drops.jsonl（同名目录：追加失败）：终态照写，但不打「已收尾」标记（cursor 无 closed_ms），
     * 下次启动（能写了）重试收尾。旧实现忽略墓碑追加的结果照样收尾。
     */
    @Test
    fun recoveryTombstoneAppendFailureKeepsSessionOpen() {
        val h = Harness(key = "")
        h.settle()
        for (i in 1..3) h.l(LogLevel.WARN, "w$i")
        val sid = h.client.writer.currentSessionId
        val dir = h.sessionDir()
        h.client.simulateCrash()
        // 抠掉 w2：恢复时 oseq 2 是缺口，要记 corrupt 墓碑
        val seg = File(dir, "seg-000001.open")
        seg.writeText(seg.readText().split("\n").filter { !it.contains("\"msg\":\"w2\"") }.joinToString("\n"))
        val drops = File(h.root, "drops.jsonl")
        assertTrue(drops.mkdir())

        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        assertEquals("终态写成了", 1, h2.readJsonl("sessions.jsonl").count { it["session_id"] == sid })
        assertFalse("墓碑没写成：不打已收尾", h2.readJson(File(dir, "cursor.json")).containsKey("closed_ms"))
        h2.client.simulateCrash()

        assertTrue(drops.delete())
        val h3 = Harness(root = h.root, key = "", clock = h.clock)
        h3.settle()
        assertTrue("能写了：下次启动收尾", h3.readJson(File(dir, "cursor.json")).containsKey("closed_ms"))
        assertEquals("终态不重复", 1, h3.readJsonl("sessions.jsonl").count { it["session_id"] == sid })
    }

    /**
     * 恢复时段文件读不出：不推进 cursor、不删会话目录、不写终态，下次启动重试（ADR 0019 决定 13）。
     * 旧实现跳过读不出的段，整个会话目录被删、义务行无墓碑消失。
     */
    @Test
    fun unreadableSegmentKeepsSessionForRetry() {
        val h = Harness(key = "")
        h.settle()
        for (i in 1..3) h.l(LogLevel.WARN, "w$i")
        val sid = h.client.writer.currentSessionId
        val dir = h.sessionDir()
        val seg = File(dir, "seg-000001.open")
        val aside = File(h.root, "aside.open")
        h.client.simulateCrash()
        assertTrue(seg.renameTo(aside))
        assertTrue(seg.mkdir()) // 同名目录：读不出
        val cursorBefore = File(dir, "cursor.json").readBytes()

        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        assertTrue("会话目录还在", dir.isDirectory)
        assertArrayEquals("cursor 没动", cursorBefore, File(dir, "cursor.json").readBytes())
        assertEquals(0, h2.readJsonl("sessions.jsonl").size)
        assertTrue(h2.envelopes().none { it["session_id"] == sid })
        h2.client.simulateCrash()

        // 段恢复可读：下次启动正常收尾
        assertTrue(seg.delete())
        assertTrue(aside.renameTo(seg))
        val h3 = Harness(root = h.root, key = "", clock = h.clock)
        h3.settle()
        val covered = h3.envelopes().filter { it["session_id"] == sid }
            .flatMap { e -> e.lines.filter { it.containsKey("oseq") && it["ctx"] != true }.map { int(it["oseq"]) } }.sorted()
        assertEquals("3 行 warn + 合成 unclean_exit", listOf(1L, 2L, 3L, 4L), covered)
        // h2 自己是前台零行崩溃的会话，也有终态（先合成再判零行）；这里只看读不出的那个会话
        assertEquals(1, h3.readJsonl("sessions.jsonl").count { it["session_id"] == sid })
    }

    /** jsonl 追加写到一半失败：截回追加前的长度，下一条不会与半行粘连（ADR 0019 决定 5）。 */
    @Test
    fun jsonlAppendFailureTruncatesBack() {
        val h = Harness(key = "")
        h.settle()
        val sid = h.client.writer.currentSessionId
        val d1 = DropEntry(sid, 1, 1, 1, "corrupt", 1, -1)
        val d2 = DropEntry(sid, 2, 2, 1, "corrupt", 2, -1)
        val d3 = DropEntry(sid, 3, 3, 1, "corrupt", 3, -1)
        assertTrue(h.work { it.appendDrops(listOf(d1)) })
        val before = File(h.root, "drops.jsonl").readBytes()
        Fs.appendFaultForTesting = 10
        try {
            assertFalse(h.work { it.appendDrops(listOf(d2)) })
        } finally {
            Fs.appendFaultForTesting = null
        }
        assertArrayEquals("截回追加前的长度", before, File(h.root, "drops.jsonl").readBytes())
        assertTrue(h.work { it.appendDrops(listOf(d3)) })
        assertEquals(listOf(d1, d3), h.readJsonl("drops.jsonl").map { DropEntry.decode(it) })
        // sessions.jsonl 同一实现
        val c = ClosedSession(sid, 1, 1, 2, 3, 1, "clean_bg")
        Fs.appendFaultForTesting = 5
        try {
            assertFalse(h.work { it.appendClosed(listOf(c)) })
        } finally {
            Fs.appendFaultForTesting = null
        }
        assertEquals(0L, File(h.root, "sessions.jsonl").let { if (it.exists()) it.length() else 0L })
        Fs.append(File(h.root, "sessions.jsonl"), Jsonl.encodeClosed(listOf(c)))
        assertEquals(1, h.readJsonl("sessions.jsonl").size)
    }
}
