package org.revdog.retriever

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.ClientConstants
import org.revdog.retriever.core.ClosedSession
import org.revdog.retriever.core.DropEntry
import org.revdog.retriever.core.Engine
import org.revdog.retriever.core.Fs
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.Jsonl
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.SealReason
import org.revdog.retriever.core.evictIfNeeded
import org.revdog.retriever.core.materializeBackfill
import org.revdog.retriever.core.quarantine
import java.io.File

/** 容量与驱逐（方案 §3.8；宪法 R-5）。与 iOS EvictionTests 同一组。 */
class EvictionTests : RtvTest() {
    private fun Harness.l(level: LogLevel, msg: String) = client.log(level, msg, null, null, null)

    /** 准备：RETAINED 段 ×2（无义务）、p2、q、p1、p0 各一。 */
    private fun prepare(): Harness {
        val h = Harness(key = "")
        h.settle()
        // 先生成一个 p2（只覆盖第一段），再塞两段 RETAINED 作驱逐对象
        h.l(LogLevel.DEBUG, "backfill me")
        h.seal()
        h.work { it.materializeBackfill() }
        repeat(2) {
            val seg = h.client.writer.snapshot.segNo
            while (h.client.writer.snapshot.segNo == seg) h.l(LogLevel.DEBUG, "d".repeat(300))
            h.settle()
        }
        h.l(LogLevel.WARN, "to quarantine")
        h.seal()
        h.work { e -> e.quarantine(e.metas.values.first { it.prio == 1 }.name) }
        h.clock.advance(1)
        h.l(LogLevel.WARN, "p1")
        h.seal()
        h.clock.advance(1)
        h.l(LogLevel.ERROR, "p0")
        h.seal()
        assertEquals(listOf("p0", "p1", "p2", "q-"), h.outboxFiles().map { it.take(2) }.sorted())
        return h
    }

    @Test
    fun evictionOrderAndTombstones() {
        val h = prepare()
        val sid = h.client.writer.currentSessionId
        val dir = h.sessionDir()
        fun sealedSegs() = (dir.list() ?: emptyArray()).filter { it.endsWith(".sealed") }.sorted()
        assertEquals(6, sealedSegs().size)
        // 阶段 1：上限刚好容下「出站箱全部 − p2」+ 当前段 → 删光 RETAINED 段，再删 p2
        val (segBytes, openBytes, box) = h.work { e ->
            val segs = e.current!!.sealed.sumOf { it.file.length() }
            val open = e.writer.currentSegmentFile!!.length()
            val b = HashMap<String, Long>()
            for (m in e.metas.values) b[m.name.take(2)] = m.bytes
            Triple(segs, open, b)
        }
        assertTrue(segBytes > 1_000_000)
        val cap1 = openBytes + box["q-"]!! + box["p1"]!! + box["p0"]!!
        h.work { e ->
            e.effective.config = e.effective.config.copy(localCapBytes = cap1.toInt())
            e.evictIfNeeded()
        }
        assertEquals(emptyList<String>(), sealedSegs())
        assertEquals(listOf("p0", "p1", "q-"), h.outboxFiles().map { it.take(2) }.sorted())
        var drops = h.readJsonl("drops.jsonl")
        assertEquals("RETAINED 段无义务：不记墓碑", listOf("backfill_evicted"), drops.map { it["reason"] })
        assertEquals(0L, int(drops[0]["oseq_from"]))
        assertEquals(0L, int(drops[0]["oseq_to"]))
        assertEquals(1L, int(drops[0]["n"]))
        // 阶段 2：上限 0 → q → p1 → p0 依次驱逐；当前 OPEN 段永不驱逐
        h.work { e ->
            e.effective.config = e.effective.config.copy(localCapBytes = 0)
            e.evictIfNeeded()
        }
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertTrue(h.client.debugOpenSegmentFile!!.exists())
        drops = h.readJsonl("drops.jsonl")
        assertEquals(listOf("backfill_evicted", "quarantine_evicted", "buffer_overflow", "buffer_overflow"), drops.map { it["reason"] })
        assertEquals(listOf(0L, 1L, 2L, 3L), drops.map { int(it["oseq_from"]) })
        assertTrue(drops.all { it["session_id"] == sid })
        assertTrue(drops.all { int(it["last_ack_age_ms"]) == -1L })
        // 墓碑随下一个 primary 批上报，服务端校验通过
        h.work { e -> e.effective.config = e.effective.config.copy(localCapBytes = Limits.LOCAL_CAP_BYTES_DEFAULT) }
        h.l(LogLevel.WARN, "carrier")
        h.seal()
        val env = h.envelopes().first()
        assertEquals(4, objs(env["drops"]).size)
        val results = runValidator(listOf(env))
        assertAllValid(results)
        assertEquals(4L, int(obj(results[0]["stats"])["dropsN"]))
    }

    /** ADR 0010：可用空间 10 MB（余量额度算成 0）时刚物化的 error 批留在出站箱并被上传，RETAINED 段让出。 */
    @Test
    fun lowDiskKeepsFreshObligationBatchAndUploadsIt() {
        val h = Harness()
        h.settle()
        h.platform.available = 10L * 1024 * 1024
        h.l(LogLevel.DEBUG, "context")
        h.l(LogLevel.ERROR, "boom")
        h.seal(SealReason.ERROR)
        assertEquals("义务批不因磁盘余量被驱逐", 1, h.outboxFiles("p0").size)
        assertEquals("RETAINED 段被驱逐", emptyList<String>(), (h.sessionDir().list() ?: emptyArray()).filter { it.endsWith(".sealed") })
        assertTrue(h.client.debugOpenSegmentFile!!.exists())
        assertEquals(0, h.readJsonl("drops.jsonl").size)
        h.tick(2000)
        assertEquals(1, h.transport.batchRequests.size)
        assertEquals(listOf("context", "boom"), FakeTransport.envOf(h.transport.batchRequests[0].body)!!.lines.map { it["msg"] })
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    /** ADR 0010：低磁盘只驱逐 RETAINED 段与 p2（记 backfill_evicted）；超过 local_cap_bytes 时仍按 q → p1 → p0 驱逐并记墓碑。 */
    @Test
    fun lowDiskEvictsOnlyNonObligationUntilHardCap() {
        val h = prepare()
        val dir = h.sessionDir()
        fun sealedSegs() = (dir.list() ?: emptyArray()).filter { it.endsWith(".sealed") }.sorted()
        assertEquals(6, sealedSegs().size)
        h.platform.available = 10L * 1024 * 1024
        h.work { it.evictIfNeeded() }
        assertEquals(emptyList<String>(), sealedSegs())
        assertEquals(listOf("p0", "p1", "q-"), h.outboxFiles().map { it.take(2) }.sorted())
        assertEquals(listOf("backfill_evicted"), h.readJsonl("drops.jsonl").map { it["reason"] })
        // 再次驱逐（磁盘仍紧张）：义务批与隔离批不动
        h.work { it.evictIfNeeded() }
        assertEquals(listOf("p0", "p1", "q-"), h.outboxFiles().map { it.take(2) }.sorted())
        // 硬上限压到 0：q → p1 → p0 依次驱逐，当前 OPEN 段永不驱逐
        h.work { e ->
            e.effective.config = e.effective.config.copy(localCapBytes = 0)
            e.evictIfNeeded()
        }
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertTrue(h.client.debugOpenSegmentFile!!.exists())
        val drops = h.readJsonl("drops.jsonl")
        assertEquals(listOf("backfill_evicted", "quarantine_evicted", "buffer_overflow", "buffer_overflow"), drops.map { it["reason"] })
        assertEquals(listOf(0L, 1L, 2L, 3L), drops.map { int(it["oseq_from"]) })
    }

    @Test
    fun segmentsOlderThanSevenDaysDeletedUnconditionally() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.DEBUG, "old")
        h.seal()
        val seg = File(h.sessionDir(), "seg-000001.sealed")
        assertTrue(seg.exists())
        Fs.touch(seg, h.clock.wallMs() - 8 * ClientConstants.DAY_MS)
        h.work { it.evictIfNeeded() }
        assertFalse(seg.exists())
        assertEquals(0, h.readJsonl("drops.jsonl").size)
    }

    /**
     * drops.jsonl 到上限只做无损合并（ADR 0019 决定 4）：同会话同 reason 相接 / 重叠的并成一条、n = 并集长度；稀疏的不并宽；
     * 仍超出删最旧的未在途条目。旧实现按 (会话, reason) 并成 [min, max]，盖住真实缺口。
     */
    @Test
    fun dropsFileCapOnlyLosslessMerge() {
        val h = Harness(key = "")
        h.settle()
        val sid = h.client.writer.currentSessionId
        // 1100 条稀疏单点（间隔 1，互不相接）+ 3 组相接的区间（各可并成一条）+ 一个别的会话的同区间（不跨会话合并）
        val sparse = (1..1100).map { i -> DropEntry(sid, i * 2L, i * 2L, 1, "write_failed", i.toLong(), -1) }
        val adjacent = listOf(
            DropEntry(sid, 10_000, 10_004, 5, "buffer_overflow", 1, -1), DropEntry(sid, 10_005, 10_009, 5, "buffer_overflow", 2, -1),
            DropEntry(sid, 20_000, 20_009, 10, "corrupt", 3, -1), DropEntry(sid, 20_005, 20_019, 15, "corrupt", 4, -1),
        )
        val other = Ids.newV4()
        val foreign = DropEntry(other, 10_000, 10_004, 5, "buffer_overflow", 5, -1)
        val input = sparse + adjacent + foreign
        h.work { it.appendDrops(input) }
        val drops = h.readJsonl("drops.jsonl").map { DropEntry.decode(it)!! }
        assertEquals(ClientConstants.DROPS_FILE_MAX_ENTRIES, drops.size)
        // 每条 n == 区间长度、session 是真实归属
        for (d in drops) assertEquals("$d", d.oseqTo - d.oseqFrom + 1, d.n)
        assertTrue(drops.contains(DropEntry(sid, 10_000, 10_009, 10, "buffer_overflow", 2, -1)))
        assertTrue(drops.contains(DropEntry(sid, 20_000, 20_019, 20, "corrupt", 4, -1)))
        assertTrue("不跨会话合并", drops.contains(foreign))
        // 没有任何条目覆盖输入里不存在的 oseq
        val inputCover = input.filter { it.sessionId == sid }.flatMap { (it.oseqFrom..it.oseqTo).toList() }.toSet()
        for (d in drops.filter { it.sessionId == sid }) assertTrue("$d", inputCover.containsAll((d.oseqFrom..d.oseqTo).toList()))
        // 仍超出删的是最旧的（文件最前面的稀疏点），最新的保留
        assertFalse(drops.any { it.oseqFrom == 2L })
        assertTrue(drops.any { it.oseqFrom == 2200L })
    }

    /** 无损合并只收合法区间：oseq_from < 1 的非 backfill 条目（如 0..0、n = 1）原样保留、不参与合并（同 iOS）。 */
    @Test
    fun capDropsKeepsInvalidRangeUnmerged() {
        val sid = Ids.newV4()
        val zero = DropEntry(sid, 0, 0, 1, "corrupt", 1, -1)
        val all = listOf(zero, DropEntry(sid, 1, 1, 1, "corrupt", 2, -1), DropEntry(sid, 2, 2, 1, "corrupt", 3, -1))
        assertEquals(listOf(zero, DropEntry(sid, 1, 2, 2, "corrupt", 3, -1)), Engine.capDrops(all, emptySet(), 2))
    }

    /** 每批按文件顺序带最旧的 100 条墓碑：各自会话与精确区间，不合并；带不完的留给下一批（ADR 0019 决定 2）。 */
    @Test
    fun dropsCarriedUnmergedOldestFirst() {
        val h = Harness(key = "")
        h.settle()
        val entries = (1..150).map { i -> DropEntry(Ids.newV4(), 5, 7, 3, "buffer_overflow", i.toLong(), -1) }
        h.work { it.appendDrops(entries) }
        val before = File(h.root, "drops.jsonl").readBytes()
        h.l(LogLevel.WARN, "carrier 1")
        h.seal()
        assertArrayEquals("携带不改写文件", before, File(h.root, "drops.jsonl").readBytes())
        val first = objs(h.envelopes().single()["drops"])
        assertEquals(Limits.DROPS_PER_BATCH, first.size)
        assertEquals(entries.take(100).map { it.sessionId }, first.map { it["session_id"] })
        assertTrue(first.all { int(it["oseq_from"]) == 5L && int(it["oseq_to"]) == 7L && int(it["n"]) == 3L })
        h.clock.advance(1)
        h.l(LogLevel.WARN, "carrier 2")
        h.seal()
        val second = objs(h.envelopes().single { e -> e.lines.any { it["msg"] == "carrier 2" } }["drops"])
        assertEquals(entries.drop(100).map { it.sessionId }, second.map { it["session_id"] })
        assertAllValid(runValidator(h.envelopes()))
        // 两批都确认后文件清空
        h.enableUpload()
        h.tick(2000)
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals(0, h.readJsonl("drops.jsonl").size)
    }

    /**
     * 30 条非空终态：第一批最旧 20 条、第二批 10 条；全程没有 closed_sessions_dropped，携带前后文件字节不变（ADR 0019 决定 2）。
     * 旧实现第一批带 20 条、把更旧的 10 条从文件里删掉只留一个计数。
     */
    @Test
    fun terminalsCarriedOldestFirstNeverDropped() {
        val h = Harness(key = "")
        h.settle()
        val closed = (1..30).map { i -> ClosedSession(Ids.newV4(), i.toLong(), i * 1000L, i * 1000L + 500, 10, 2, "clean_bg") }
        Fs.append(File(h.root, "sessions.jsonl"), Jsonl.encodeClosed(closed))
        val before = File(h.root, "sessions.jsonl").readBytes()
        h.l(LogLevel.WARN, "carrier 1")
        h.seal()
        val env1 = h.envelopes().single()
        assertEquals((1L..20L).toList(), objs(env1["closed_sessions"]).map { int(it["session_no"]) })
        assertFalse(env1.map.containsKey("closed_sessions_dropped"))
        assertArrayEquals("携带不改写文件", before, File(h.root, "sessions.jsonl").readBytes())
        h.clock.advance(1)
        h.l(LogLevel.WARN, "carrier 2")
        h.seal()
        val env2 = h.envelopes().single { e -> e.lines.any { it["msg"] == "carrier 2" } }
        assertEquals((21L..30L).toList(), objs(env2["closed_sessions"]).map { int(it["session_no"]) })
        assertFalse(env2.map.containsKey("closed_sessions_dropped"))
        assertAllValid(runValidator(listOf(env1, env2)))
        // 2xx 后删除已报条目
        h.enableUpload()
        h.tick(2000)
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals(0, h.readJsonl("sessions.jsonl").size)
    }

    /**
     * 一个有数据的后台会话 + 25 个空的后台会话（Android 每次后台拉起都是一个会话）：空会话不写终态、目录直接删，
     * 真实会话的终态随载体批上报（ADR 0019 决定 1）。旧实现 26 条终态、真实会话那条被挤掉并从文件删除。
     */
    @Test
    fun realTerminalSurvivesEmptySessions() {
        val root = tempDir()
        val p = FakePlatform().apply { foreground = false }
        val real = Harness(root = root, key = "", platform = p)
        real.settle()
        real.l(LogLevel.WARN, "real bg work")
        val realSid = real.client.writer.currentSessionId
        real.client.simulateCrash()
        val clock = real.clock
        repeat(25) {
            clock.advance(1000)
            val empty = Harness(root = root, key = "", platform = p, clock = clock)
            empty.settle()
            empty.client.simulateCrash()
        }
        val last = Harness(root = root, key = "", platform = p, clock = clock)
        last.settle()
        val terms = last.readJsonl("sessions.jsonl")
        assertEquals(listOf(realSid), terms.map { it["session_id"] })
        assertTrue(terms.none { int(it["last_oseq"]) == 0L })
        assertEquals("空会话目录已删，只剩真实会话与当前会话", setOf(realSid, last.client.writer.currentSessionId), File(root, "proc-main").list()!!.toSet())
        last.l(LogLevel.WARN, "carrier")
        last.seal()
        val carriers = last.envelopes().filter { e -> objs(e["closed_sessions"]).any { it["session_id"] == realSid } }
        assertEquals(1, carriers.size)
    }

    /** sessions.jsonl 上限 1000 条：超出删最旧的未在途条目（ADR 0019 决定 3）。 */
    @Test
    fun sessionsFileCapDropsOldest() {
        val h = Harness(key = "")
        h.settle()
        val closed = (1..1005).map { i -> ClosedSession(Ids.newV4(), i.toLong(), i * 1000L, i * 1000L + 500, 10, 2, "clean_bg") }
        h.work { it.appendClosed(closed) }
        val left = h.readJsonl("sessions.jsonl").map { int(it["session_no"]) }
        assertEquals((6L..1005L).toList(), left)
    }
}
