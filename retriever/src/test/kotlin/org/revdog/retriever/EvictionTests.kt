package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.ClientConstants
import org.revdog.retriever.core.ClosedSession
import org.revdog.retriever.core.DropEntry
import org.revdog.retriever.core.Fs
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.Jsonl
import org.revdog.retriever.core.Limits
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

    @Test
    fun dropsFileCappedAndMergedByReason() {
        val h = Harness(key = "")
        h.settle()
        val sid = h.client.writer.currentSessionId
        val entries = (1..1100).map { i ->
            val o = i * 2L
            DropEntry(sid, o, o, 1, if (i % 2 == 0) "write_failed" else "buffer_overflow", i.toLong(), -1)
        }
        h.work { it.appendDrops(entries) }
        val drops = h.readJsonl("drops.jsonl")
        assertTrue(drops.size <= ClientConstants.DROPS_FILE_MAX_ENTRIES)
        assertEquals("合并计数不丢", 1100L, drops.sumOf { int(it["n"]) })
        assertEquals(setOf("write_failed", "buffer_overflow"), drops.map { it["reason"] }.toSet())
        // 单批 ≤ 100 条，超出按 reason 合并
        h.l(LogLevel.WARN, "carrier")
        h.seal()
        val env = h.envelopes().first()
        val d = objs(env["drops"])
        assertTrue(d.size <= Limits.DROPS_PER_BATCH)
        assertEquals(1100L, d.sumOf { int(it["n"]) })
        assertAllValid(runValidator(listOf(env)))
    }

    @Test
    fun closedSessionsCappedAtTwenty() {
        val h = Harness(key = "")
        h.settle()
        val closed = (1..25).map { i -> ClosedSession(Ids.newV4(), i.toLong(), i * 1000L, i * 1000L + 500, 10, 2, "clean_bg") }
        Fs.append(File(h.root, "sessions.jsonl"), Jsonl.encodeClosed(closed))
        h.l(LogLevel.WARN, "carrier")
        h.seal()
        val env = h.envelopes().first()
        val cs = objs(env["closed_sessions"])
        assertEquals(Limits.CLOSED_SESSIONS_PER_BATCH, cs.size)
        assertEquals(5L, int(env["closed_sessions_dropped"]))
        assertEquals((6L..25L).toSet(), cs.map { int(it["session_no"]) }.toSet())
        assertEquals("更旧的 5 条已合并为计数", 20, h.readJsonl("sessions.jsonl").size)
        // 2xx 后删除已报条目
        h.enableUpload()
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals(0, h.readJsonl("sessions.jsonl").size)
    }
}
