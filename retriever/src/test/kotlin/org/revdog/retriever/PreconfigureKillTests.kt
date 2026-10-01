package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.JsonIn
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真杀进程（子 JVM `Runtime.halt(137)`，沿用 KillHelper；简报 §11 D / E / J）：configure 之前被杀 → 下次启动收编为独立会话；
 * 收编中途被杀 → 重做后不重不丢、提交前没有批次进出站箱；别的活进程的 pre 文件不碰。
 */
class PreconfigureKillTests : RtvTest() {
    private fun helper(vararg args: String): Process {
        val java = File(System.getProperty("java.home") ?: "", "bin/java").path
        val cp = System.getProperty("rtv.testClasspath") ?: error("缺 rtv.testClasspath（build.gradle.kts 注入）")
        return ProcessBuilder(listOf(java, "-cp", cp, "org.revdog.retriever.KillHelperKt") + args).redirectError(File("/dev/null")).start()
    }

    private fun runToHalt(vararg args: String) {
        val p = helper(*args)
        p.inputStream.readBytes()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        assertEquals("子进程被 halt(137)", 137, p.exitValue())
    }

    /** KillHelper.preLines(n) 的级别规则。 */
    private fun levelOf(i: Int): String = when {
        i % 50 == 0 -> "error"
        i % 10 == 0 -> "warn"
        i % 2 == 0 -> "info"
        else -> "debug"
    }

    private fun otherSession(h: StaticHarness): File = h.sessionDirs().single { it.name != h.client.writer.currentSessionId }

    private fun assertAdoptedExactly(dir: File, n: Int) {
        val ls = sessionLines(dir).filter { it["synthetic"] != true }
        assertEquals((1..n).map { "pre $it" }, ls.map { it["msg"] })
        assertEquals((1..n).map { levelOf(it) }, ls.map { it["level"] })
        assertEquals("seq 连续", (1L..n.toLong()).toList(), ls.map { int(it["seq"]) })
        val oseqs = ls.filter { it.map.containsKey("oseq") }.map { int(it["oseq"]) }
        assertEquals("按 configure(INFO) 判定：偶数行是义务行，oseq 连续", (1L..(n / 2).toLong()).toList(), oseqs)
        // 用户边界：第 n/2 + 1 行起是 killed-user
        val heads = segHeaders(dir)
        assertEquals(listOf(null, "killed-user"), heads.map { it["user_id"] }.distinct())
    }

    /** D：configure 之前进程被杀 → 下次启动收编为独立会话：行数相等、按当时 configure 的级别判定、无 rtv.unclean_exit。 */
    @Test
    fun d_killedBeforeConfigureAdoptedAsOwnSession() {
        val root = tempDir("rtv-prekill")
        runToHalt("--pre", root.path, "300")
        val pre = File(root, "pre").listFiles()!!.single()
        val h = StaticHarness(root = root)
        h.configure("", Options().apply { uploadLevel = LogLevel.INFO })
        h.settle()
        assertFalse("提交 = unlink", pre.exists())
        val dir = otherSession(h)
        assertAdoptedExactly(dir, 300)
        val meta = JsonIn.obj(File(dir, "meta.json").readBytes())!!
        assertFalse(meta.containsKey("pre"))
        assertEquals("main", meta["process"])
        assertTrue("孤儿会话没有前后台记录：不合成 unclean_exit", sessionLines(dir).none { it["tag"] == "rtv.unclean_exit" })
        val sid = dir.name
        val covered = h.envelopes().filter { it["session_id"] == sid }.flatMap { e -> e.lines.filter { it["ctx"] != true }.map { int(it["oseq"]) } }.sorted()
        assertEquals((1L..150L).toList(), covered)
        assertEquals("unknown", h.readJsonl("sessions.jsonl").single { it["session_id"] == sid }["exit"])
        assertEquals("用户边界 = 批边界", setOf(null, "killed-user"), h.envelopes().filter { it["session_id"] == sid }.map { it["user_id"] }.toSet())
        assertAllValid(runValidator(h.envelopes().filter { it["session_id"] == sid }))
        // 再启动一次：不重复收编
        val h2 = h.restart()
        h2.configure("")
        h2.settle()
        assertEquals(300, sessionLines(dir).count { it["synthetic"] != true })
    }

    /**
     * E：收编中途被杀（第 57 条记录之后 / 提交之前）→ 重做后不重不丢；被杀时出站箱里没有这个会话的批。重做保留游标里的前后台记录
     * （被杀时在前台）→ 按现有规则合成 `rtv.unclean_exit`。
     */
    @Test
    fun e_killedMidAdoptionRedone() {
        for (point in listOf("record:57", "before_commit")) {
            val root = tempDir("rtv-adoptkill")
            runToHalt("--adopt-crash", root.path, "200", point)
            assertTrue("$point：pre 文件在提交前始终完整", File(root, "pre").listFiles()!!.size == 1)
            assertEquals("$point：提交前没有批次进出站箱", emptyList<String>(), (File(root, "outbox").list() ?: emptyArray()).filter { it.endsWith(".gz") })
            val crashed = File(root, "proc-main").listFiles()!!.single()
            assertTrue(JsonIn.obj(File(crashed, "meta.json").readBytes())!!.containsKey("pre"))
            val h = StaticHarness(root = root)
            h.configure("", Options().apply { uploadLevel = LogLevel.INFO })
            h.settle()
            assertEquals(emptyList<File>(), h.preFiles)
            assertEquals("重做进同一个会话", crashed.name, otherSession(h).name)
            assertAdoptedExactly(crashed, 200)
            assertEquals("$point：前台死亡合成 unclean_exit", 1, sessionLines(crashed).count { it["tag"] == "rtv.unclean_exit" })
            val covered = h.envelopes().filter { it["session_id"] == crashed.name }
                .flatMap { e -> e.lines.filter { it["ctx"] != true }.map { int(it["oseq"]) } }.sorted()
            assertEquals("$point：不重不丢", (1L..101L).toList(), covered)
            Retriever.resetForTesting(null)
        }
    }

    /** E：unlink 之后、清 meta.pre 之前被杀 = 已提交：按普通会话恢复（前台死亡照常合成 unclean_exit），不重复收编。 */
    @Test
    fun e_killedAfterCommitPointRecoveredNormally() {
        val root = tempDir("rtv-adoptkill2")
        runToHalt("--adopt-crash", root.path, "200", "after_unlink")
        assertEquals(emptyList<String>(), File(root, "pre").list()!!.toList())
        val crashed = File(root, "proc-main").listFiles()!!.single()
        val h = StaticHarness(root = root)
        h.configure("", Options().apply { uploadLevel = LogLevel.INFO })
        h.settle()
        assertAdoptedExactly(crashed, 200)
        assertEquals(1, sessionLines(crashed).count { it["tag"] == "rtv.unclean_exit" })
        val covered = h.envelopes().filter { it["session_id"] == crashed.name }
            .flatMap { e -> e.lines.filter { it["ctx"] != true }.map { int(it["oseq"]) } }.sorted()
        assertEquals((1L..101L).toList(), covered)
    }

    /**
     * E（B10）：收编期间宿主还在写（configure 之后的 r = 1 行追加到 pre 文件尾）时被杀：重做后全部行恰好出现一次、顺序正确、oseq 连续。
     * 「unlink 之后、清 meta.pre 之前」的崩溃点见 [e_killedAfterCommitPointRecoveredNormally]（after_unlink）。
     */
    @Test
    fun e_killedWhileHostWritingDuringAdoption() {
        val root = tempDir("rtv-adoptkill3")
        runToHalt("--adopt-crash-writing", root.path, "200", "record:60")
        val crashed = File(root, "proc-main").listFiles()!!.single()
        val h = StaticHarness(root = root)
        h.configure("", Options().apply { uploadLevel = LogLevel.INFO })
        h.settle()
        assertEquals(emptyList<File>(), h.preFiles)
        val ls = sessionLines(crashed).filter { it["synthetic"] != true }
        assertEquals((1..200).map { "pre $it" } + (0 until 10).map { "post $it" }, ls.map { it["msg"] })
        assertEquals((1L..210L).toList(), ls.map { int(it["seq"]) })
        val covered = h.envelopes().filter { it["session_id"] == crashed.name }
            .flatMap { e -> e.lines.filter { it["ctx"] != true }.map { int(it["oseq"]) } }.sorted()
        assertEquals("pre 偶数行 100 + post 10 + 前台死亡的 unclean_exit", (1L..111L).toList(), covered)
    }

    /** J：别的活进程的 pre 文件（持 flock）不碰——不收编、不按年龄删；它死后下次启动才收编。 */
    @Test
    fun j_liveProcessPreFileUntouched() {
        val root = tempDir("rtv-live-pre")
        val p = helper("--pre-hold", root.path, "40")
        try {
            val ready = p.inputStream.bufferedReader().readLine()
            assertEquals("ready", ready)
            val pre = File(root, "pre").listFiles()!!.single()
            val bytes = pre.readBytes()
            pre.setLastModified(System.currentTimeMillis() - 8L * 24 * 3600 * 1000)
            val h = StaticHarness(root = root)
            h.configure("")
            h.settle()
            h.client.onWork { h.client.engine.processSeals() }
            assertTrue(pre.exists())
            assertTrue(bytes.contentEquals(pre.readBytes()))
            assertEquals("没有替它建会话", 1, h.sessionDirs().size)
            p.destroyForcibly()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            pre.setLastModified(System.currentTimeMillis())
            val h2 = h.restart()
            h2.configure("", Options().apply { uploadLevel = LogLevel.INFO })
            h2.settle()
            assertFalse(pre.exists())
            val adopted = h2.sessionDirs().filter { it.name != h2.client.writer.currentSessionId }
                .single { d -> sessionLines(d).any { it["msg"] == "pre 1" } }
            assertAdoptedExactly(adopted, 40)
        } finally {
            p.destroyForcibly()
        }
    }
}
