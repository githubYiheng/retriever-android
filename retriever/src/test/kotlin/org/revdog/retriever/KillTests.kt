package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.Limits
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真杀进程验收 R-1（方案 §3.11）：子 JVM 写 N 行后 `Runtime.halt(137)`，父进程用同一 root 恢复：
 * N 行全部识别、oseq 连续、出站箱正确、sessions.jsonl 有 unclean_fg 且合成 error 已封段。与 iOS KillTests 同一组。
 */
class KillTests : RtvTest() {
    private fun runHelper(root: File, n: Int, torn: Boolean): Pair<Long, Long> {
        val java = File(System.getProperty("java.home") ?: "", "bin/java").path
        val cp = System.getProperty("rtv.testClasspath") ?: error("缺 rtv.testClasspath（build.gradle.kts 注入）")
        val cmd = listOf(java, "-cp", cp, "org.revdog.retriever.KillHelperKt", root.path, n.toString()) + (if (torn) listOf("--torn") else emptyList())
        val p = ProcessBuilder(cmd).redirectError(File("/dev/null")).start()
        val out = String(p.inputStream.readBytes(), Charsets.UTF_8)
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        assertEquals("子进程被 halt(137)", 137, p.exitValue())
        val parts = out.trim().split(" ")
        return Pair(parts[0].removePrefix("seq=").toLong(), parts[1].removePrefix("oseq=").toLong())
    }

    /** 读旧会话全部段里的行。 */
    private fun allLines(sessionDir: File): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for (n in (sessionDir.list() ?: emptyArray()).filter { it.startsWith("seg-") }.sorted()) {
            val ls = File(sessionDir, n).readText().split("\n").filter { it.isNotEmpty() }
            for ((i, l) in ls.withIndex()) if (i > 0) out.add(JsonIn.obj(l)!!)
        }
        return out
    }

    private fun oldSession(root: File, current: String): Pair<String, File> {
        val proc = File(root, "proc-main")
        val sid = proc.list()!!.first { it != current && it.length == 36 }
        return Pair(sid, File(proc, sid))
    }

    @Test
    fun killThenRecover() {
        val root = tempDir("rtv-kill")
        val (seq, oseq) = runHelper(root, 5000, false)
        assertEquals(5000L, seq)
        assertEquals(500L, oseq)

        val h = Harness(root = root, key = "")
        h.settle()
        val (sid, dir) = oldSession(root, h.client.writer.currentSessionId)

        // 全部段已封，无残留 .open
        val segs = dir.list()!!.filter { it.startsWith("seg-") }
        assertTrue("$segs", segs.all { it.endsWith(".sealed") })

        // 5000 行全部识别 + 合成 error（seq / oseq 接着编）
        val ls = allLines(dir)
        assertEquals(5001, ls.size)
        assertEquals((1L..5001L).toList(), ls.map { int(it["seq"]) })
        assertEquals((1L..501L).toList(), ls.filter { it.containsKey("oseq") }.map { int(it["oseq"]) })
        val synth = ls.last()
        assertEquals("rtv.unclean_exit", synth["tag"])
        assertEquals(true, synth["synthetic"])
        assertEquals("error", synth["level"])
        assertEquals("process ended while foregrounded", synth["msg"])

        // 会话终态
        val closed = h.readJsonl("sessions.jsonl").filter { it["session_id"] == sid }
        assertEquals(1, closed.size)
        assertEquals("unclean_fg", closed[0]["exit"])
        assertEquals(5001L, int(closed[0]["last_seq"]))
        assertEquals(501L, int(closed[0]["last_oseq"]))

        // 出站箱：旧会话 primary 批 oseq 区间首尾相接覆盖 1..501，合成 error 在带 ctx 的 p0 批里
        val envs = h.envelopes().filter { it["session_id"] == sid }
        val ranges = envs.map { Pair(int(it["oseq_from"]), int(it["oseq_to"])) }.sortedBy { it.first }
        assertEquals(1L, ranges.first().first)
        assertEquals(501L, ranges.last().second)
        for (i in 1 until ranges.size) assertEquals(ranges[i - 1].second + 1, ranges[i].first)
        val p0 = envs.first { e -> e.lines.any { it["tag"] == "rtv.unclean_exit" } }
        assertTrue(p0.name.startsWith("p0-"))
        assertEquals(Limits.CTX_LINES_DEFAULT, p0.lines.count { it["ctx"] == true })
        assertTrue(objs(p0["closed_sessions"]).any { it["session_id"] == sid })

        // 服务端校验器逐个通过
        assertAllValid(runValidator(envs))

        // 重启第二次：不再重复合成、不重复写终态
        val h2 = Harness(root = root, key = "")
        h2.settle()
        assertEquals(5001, allLines(dir).size)
        assertEquals(1, h2.readJsonl("sessions.jsonl").count { it["session_id"] == sid })
        assertEquals(envs.size, h2.envelopes().count { it["session_id"] == sid })
    }

    @Test
    fun tornLastLine() {
        val root = tempDir("rtv-torn")
        val (seq, oseq) = runHelper(root, 3000, true)
        assertEquals(3000L, seq)
        assertEquals(300L, oseq)
        val h = Harness(root = root, key = "")
        h.settle()
        val (sid, dir) = oldSession(root, h.client.writer.currentSessionId)
        val ls = allLines(dir)
        // 残行被截掉：3000 行完整 + 合成 error 的 seq 跳过残行占用的 3001
        assertEquals(3001, ls.size)
        assertEquals((1L..3000L).toList(), ls.dropLast(1).map { int(it["seq"]) })
        assertEquals(3002L, int(ls.last()["seq"]))
        assertEquals(302L, int(ls.last()["oseq"]))
        // 残行 oseq 301 记 corrupt 墓碑
        val drops = h.readJsonl("drops.jsonl").filter { it["session_id"] == sid }
        assertEquals(1, drops.size)
        assertEquals("corrupt", drops[0]["reason"])
        assertEquals(301L, int(drops[0]["oseq_from"]))
        assertEquals(301L, int(drops[0]["oseq_to"]))
        // 出站箱覆盖 1..300 与 302（缺口处切批）
        val envs = h.envelopes().filter { it["session_id"] == sid }
        val covered = envs.flatMap { e -> e.lines.filter { it.containsKey("oseq") && it["ctx"] != true }.map { int(it["oseq"]) } }.sorted()
        assertEquals((1L..300L).toList() + 302L, covered)
        assertAllValid(runValidator(envs))
    }

    /** 同一 root 上另一个还活着的实例：它的会话锁着，恢复流程不动它。 */
    @Test
    fun liveSessionIsNotRecovered() {
        val root = tempDir("rtv-live")
        val a = Harness(root = root, key = "")
        a.settle()
        a.client.log(LogLevel.WARN, "a alive", null, null, null)
        val b = Harness(root = root, key = "")
        b.settle()
        assertTrue("a 的 .open 段没被封", a.client.debugOpenSegmentFile!!.exists())
        assertEquals(0, b.readJsonl("sessions.jsonl").size)
        a.client.log(LogLevel.WARN, "a still writes", null, null, null)
        assertEquals(2L, a.client.debugCounters.second)
        // a 死后，下一次启动才恢复它（b 仍活着，不动）
        val aSid = a.client.writer.currentSessionId
        a.client.simulateCrash()
        val c = Harness(root = root, key = "")
        c.settle()
        val closed = c.readJsonl("sessions.jsonl")
        assertEquals(listOf(aSid), closed.map { it["session_id"] })
        assertEquals("unclean_fg", closed[0]["exit"])
        assertEquals("2 行 warn + 合成 error", 3L, int(closed[0]["last_oseq"]))
    }
}
