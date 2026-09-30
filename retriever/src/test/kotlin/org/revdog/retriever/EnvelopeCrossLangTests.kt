package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.DropEntry
import org.revdog.retriever.core.Limits

/** 跨语言信封校验：Kotlin 物化出的 ≥ 6 种批（原始字节）都要过 packages/core 的 validateEnvelope（服务端同一份代码）。 */
class EnvelopeCrossLangTests : RtvTest() {
    private class Expect(val name: String, val env: Env, val lines: Int, val errors: Int, val warn: Boolean)

    private val rank = mapOf("debug" to 0, "info" to 1, "warn" to 2, "error" to 3, "fatal" to 4)

    private fun expect(name: String, env: Env): Expect {
        val ls = env.lines
        val errors = ls.count { (rank[it["level"]] ?: 0) >= 3 && it["ctx"] != true }
        val warn = ls.any { (rank[it["level"]] ?: 0) >= 2 }
        return Expect(name, env, ls.size, errors, warn)
    }

    class Boom : RuntimeException("boom")

    @Test
    fun materializedBatchesPassServerValidator() {
        val cases = ArrayList<Expect>()

        // 1. 纯 warn（attrs 各种值、CJK + emoji）
        run {
            val h = Harness(key = "")
            h.settle()
            h.client.log(LogLevel.INFO, "i", null, null, null)
            h.client.log(LogLevel.WARN, "w1", "net", mapOf("ms" to 3200, "ok" to false, "u" to "/v1", "ratio" to 0.25, "none" to null), null)
            h.client.log(LogLevel.WARN, "w2 日志🐶", null, null, null)
            h.seal()
            val e = h.envelopes().first()
            assertEquals(2, e.lines.size)
            assertNull(e["ctx_truncated"])
            cases.add(expect("pure_warn", e))
        }

        // 2 / 3. error 带 ctx 跨段；ctx 被字节预算截断
        run {
            val h = Harness(key = "")
            h.settle()
            while (h.client.writer.snapshot.segNo == 1) h.client.log(LogLevel.DEBUG, "old " + "o".repeat(2000), null, null, null)
            for (i in 0 until 10) h.client.log(LogLevel.INFO, "new $i", null, null, null)
            h.client.log(LogLevel.ERROR, "failed", "billing", null, Boom())
            h.tick(2000)
            val e = h.envelopes("p0").first()
            val ctx = e.lines.filter { it["ctx"] == true }
            assertTrue("跨段", ctx.size > 10)
            assertTrue("字节预算先到", ctx.size < Limits.CTX_LINES_DEFAULT)
            assertTrue(int(e["ctx_truncated"]) > 0)
            assertTrue(ctx.sumOf { toJson(it).toByteArray().size + 1 } <= Limits.CTX_BYTES_DEFAULT + 2000)
            cases.add(expect("error_ctx_cross_segment_truncated", e))
        }

        // 4. drops / closed_sessions / mapping（旧会话恢复 + 墓碑 + 映射）
        run {
            val root = tempDir()
            val a = Harness(root = root, key = "")
            a.settle()
            a.client.log(LogLevel.INFO, "before crash", null, null, null)
            a.client.log(LogLevel.WARN, "a warn", null, null, null)
            val aSid = a.client.writer.currentSessionId
            a.work { it.appendDrops(listOf(DropEntry(aSid, 7, 9, 3, "buffer_overflow", 1, 86_400_000))) }
            a.client.simulateCrash()
            val b = Harness(root = root, key = "") // 同一 root 再启动：a 的会话成了孤儿（last_state = fg）
            b.settle()
            b.client.setUser("u_1024")
            b.client.log(LogLevel.WARN, "b warn", null, null, null)
            b.seal()
            val envs = b.envelopes()
            // 墓碑 7..9 高于盘上最大 oseq 1：恢复按墓碑续编（发版前审查 A5），2..6 记 corrupt，
            // 合成行 oseq = 10 与 1 不相接 → 两批：oseq 1 的批带 drops / closed_sessions，合成行单独成 p0
            val recs = envs.filter { it["session_id"] == aSid }
            assertEquals(2, recs.size)
            val rec = recs.single { it.map.containsKey("closed_sessions") }
            val closedA = objs(rec["closed_sessions"]).first()
            assertEquals("unclean_fg", closedA["exit"])
            assertEquals(10L, int(closedA["last_oseq"]))
            assertEquals(listOf("buffer_overflow" to 7L, "corrupt" to 2L), objs(rec["drops"]).map { it["reason"] to int(it["oseq_from"]) })
            assertNull("旧会话批不带 mapping", rec["mapping"])
            val synth = recs.single { it !== rec }
            assertTrue(synth.lines.any { it["synthetic"] == true && int(it["oseq"]) == 10L })
            assertNull(synth["mapping"])
            val cur = envs.first { it["session_id"] != aSid }
            assertEquals("u_1024", obj(cur["mapping"])["user_id"])
            assertEquals("u_1024", cur["user_id"])
            assertEquals("android", obj(cur["device"])["os"])
            assertEquals("retriever-android/${RetrieverVersion.CURRENT}", obj(cur["device"])["sdk"])
            cases.add(expect("recovered_with_drops_closed", rec))
            cases.add(expect("recovered_unclean_exit", synth))
            cases.add(expect("with_mapping", cur))
        }

        // 5. 413 切分后的两半
        run {
            val h = Harness()
            h.settle()
            h.transport.setScript(listOf(FakeTransport.Reply.Status(413, mapOf("reason" to "too_large", "max_bytes" to 1_048_576))))
            h.transport.defaultReply = FakeTransport.Reply.Status(503)
            h.client.log(LogLevel.DEBUG, "ctx", null, null, null)
            for (i in 1..5) h.client.log(LogLevel.WARN, "w$i", null, null, null)
            h.sealAndDrain()
            val halves = h.envelopes().sortedBy { int(it["oseq_from"]) }
            assertEquals(2, halves.size)
            assertEquals(int(halves[0]["oseq_to"]) + 1, int(halves[1]["oseq_from"]))
            cases.add(expect("split_413_a", halves[0]))
            cases.add(expect("split_413_b", halves[1]))
        }

        // 6. backfill（full_dump 生效）
        run {
            val h = Harness(key = "")
            h.settle()
            for (i in 0 until 20) h.client.log(if (i % 2 == 0) LogLevel.DEBUG else LogLevel.INFO, "history $i", null, null, null)
            h.client.log(LogLevel.WARN, "w", null, null, null)
            h.seal()
            h.transport.configBody = mapOf("etag" to "e1", "ttl_s" to 600, "full_dump" to true, "full_dump_ttl_s" to 3600)
            h.transport.defaultReply = FakeTransport.Reply.Status(503) // 不确认，批留在出站箱里供检查
            h.enableUpload()
            val bf = h.envelopes("p2").first()
            assertEquals("backfill", bf["kind"])
            assertNull(bf["oseq_from"])
            assertEquals(20, bf.lines.size)
            assertTrue(bf.lines.all { !it.containsKey("oseq") && !it.containsKey("ctx") })
            // 生效后新写的 debug 行直接是义务行
            h.client.log(LogLevel.DEBUG, "now obligation", null, null, null)
            assertEquals(2L, h.client.debugCounters.second)
            cases.add(expect("backfill", bf))
        }

        // 7. fatal（只落盘）
        run {
            val h = Harness(key = "")
            h.settle()
            h.client.log(LogLevel.INFO, "last words", null, null, null)
            h.client.log(LogLevel.FATAL, "fatal", null, null, IllegalStateException("D"))
            cases.add(expect("fatal", h.envelopes("p0").first()))
        }

        assertTrue(cases.size >= 6)
        val results = runValidator(cases.map { it.env })
        assertEquals(cases.size, results.size)
        for ((c, r) in cases.zip(results)) {
            assertEquals("${c.name}: $r", true, r["ok"])
            val st = obj(r["stats"])
            assertEquals(c.name, c.lines.toLong(), int(st["lines"]))
            assertEquals(c.name, c.errors.toLong(), int(st["errors"]))
            assertEquals(c.name, c.warn, st["hasWarnOrAbove"])
        }
        println("[validator] " + cases.zip(results).joinToString(" ") { (c, r) -> "${c.name}=${int(obj(r["stats"])["lines"])}" })
    }
}
