package org.revdog.retriever

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.revdog.retriever.android.LifecycleTracker
import org.revdog.retriever.core.DropCounter
import org.revdog.retriever.core.Fs
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.Platform
import org.revdog.retriever.core.PreFile
import org.revdog.retriever.core.PreLog
import org.revdog.retriever.core.RootPurge
import org.revdog.retriever.core.SealReason
import org.revdog.retriever.core.TestHooks
import org.revdog.retriever.core.evictIfNeeded
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 0.3.0 两份盲审之后的修复（`docs/audit/2026-10-01-host-misuse/blind-review-*-0.3.0.md`；主代理裁决 A1–A8、B1–B9）。 */
class BlindReviewFixTests : RtvTest() {
    private fun lines(h: StaticHarness) = sessionLines(h.currentSessionDir())

    private val header = "{\"pre\":1,\"started_ms\":1790668000000,\"process\":\"main\",\"device\":{\"os\":\"android\",\"os_version\":\"15\"," +
        "\"model\":\"m\",\"app_version\":\"1\",\"build\":\"1\",\"locale\":\"en\",\"sdk\":\"retriever-android/0.3.0\"}}\n"

    private fun orphan(root: File, content: String): File {
        val dir = File(root, "pre").also { it.mkdirs() }
        return File(dir, "${java.util.UUID.randomUUID()}.jsonl").also { it.writeText(content) }
    }

    private fun childLockState(dir: File): String {
        val java = File(System.getProperty("java.home") ?: "", "bin/java").path
        val cp = System.getProperty("rtv.testClasspath") ?: error("缺 rtv.testClasspath")
        val p = ProcessBuilder(java, "-cp", cp, "org.revdog.retriever.KillHelperKt", "--probe-lock", dir.path).redirectError(File("/dev/null")).start()
        val out = String(p.inputStream.readBytes()).trim()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return out
    }

    // ---------------------------------------------------------------- A1 会话锁

    /** 收编后（P1b）、孤儿收编后（P1c）另一个进程看到当前会话仍是 locked（会话锁在专用文件 `lock` 上，meta 重写 / 读都不影响）。 */
    @Test
    fun a1_sessionLockSurvivesAdoption() {
        val a = StaticHarness()
        a.configure("")
        a.settle()
        assertEquals("P1a", "locked", childLockState(a.currentSessionDir()))

        val b = StaticHarness()
        b.log(LogLevel.WARN, "pre")
        b.configure("")
        b.settle()
        assertFalse(JsonIn.obj(File(b.currentSessionDir(), "meta.json").readBytes())!!.containsKey("pre"))
        assertEquals("P1b：收编提交后（meta.json 已重写）", "locked", childLockState(b.currentSessionDir()))

        val c = StaticHarness()
        orphan(c.root, header + "{\"r\":0,\"ts\":1790668000001,\"level\":\"warn\",\"msg\":\"orphan\"}\n")
        c.configure("")
        c.settle()
        assertEquals("P1c：孤儿收编（扫了认领）之后", "locked", childLockState(c.currentSessionDir()))
    }

    // ---------------------------------------------------------------- A3 从不 configure 的宿主

    /** 进程内首次建 pre 文件之前：拿得到锁的旧 pre 文件超过 8 个或 4 MB → 按 mtime 从最旧的删起；别的活进程的不碰。 */
    @Test
    fun a3_deadPreFilesTrimmedBeforeFirstCreate() {
        val h = StaticHarness()
        val dir = File(h.root, "pre").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        val dead = (0 until 10).map { i ->
            File(dir, "${java.util.UUID.randomUUID()}.jsonl").also {
                it.writeBytes(ByteArray(600 * 1024) { 0x20 })
                it.setLastModified(now - (100 - i) * 60_000L)
            }
        }
        // 一个活的（本 JVM 里开着、持锁，模拟别的活进程）：最旧，但不碰
        val live = PreFile.create(h.root)!!
        live.file.setLastModified(now - 1000L * 60_000L)
        try {
            h.log(LogLevel.WARN, "first")
            for (i in 0 until 4) assertFalse("最旧的 $i 删了", dead[i].exists())
            for (i in 4 until 10) assertTrue("$i 留着", dead[i].exists())
            assertTrue("活的不碰", live.file.exists())
            assertNotNull(PreLog.currentFileForTesting)
            assertEquals(0L, DropCounter.pending)
        } finally {
            live.close()
        }
    }

    // ---------------------------------------------------------------- A7 小项

    private class LoggingPlatform(private val d: FakePlatform) : Platform by d {
        @Volatile
        var armed = false

        override fun deviceFields(): Map<String, String> {
            if (armed) {
                armed = false
                Retriever.log(LogLevel.WARN, "from host code during configure")
            }
            return d.deviceFields()
        }
    }

    /** Y1：configure 线程上（交接之后）宿主代码打日志：不递归、计数（P4）。 */
    @Test
    fun a7_y1_logOnConfigureThreadCounted() {
        val root = tempDir("rtv-y1")
        val lp = LoggingPlatform(FakePlatform())
        Retriever.resetForTesting(Retriever.Env(root, lp, FakeClock()) { FakeTransport() })
        lp.armed = true
        Retriever.configure(null, "", Harness.BASE, Options())
        val c = Retriever.currentClient()!!
        c.settle()
        val ls = sessionLines(File(File(root, "proc-main"), c.writer.currentSessionId))
        assertEquals(1L, int(obj(ls.single { it["tag"] == DropCounter.TAG }["attrs"])["count"]))
        root.deleteRecursively()
    }

    /** Y2：收编期间的 fatal 排作业也按 10 s 窗口节流（P6）：100 条只排 1 次，行都在。 */
    @Test
    fun a7_y2_fatalDuringAdoptionThrottled() {
        val h = StaticHarness()
        h.log(LogLevel.INFO, "pre")
        val gate = CountDownLatch(1)
        val inHook = CountDownLatch(1)
        h.configure("lk_test_demo_abc_12345678", Options().apply {
            redact = {
                if (Thread.currentThread().name.startsWith("retriever-engine")) {
                    inHook.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                }
                it
            }
        })
        assertTrue(inHook.await(10, TimeUnit.SECONDS))
        val before = h.platform.scheduledJobs.size
        repeat(100) { Retriever.log(LogLevel.FATAL, "f$it") }
        assertEquals(1, h.platform.scheduledJobs.size - before)
        gate.countDown()
        h.settle()
        assertEquals(100, lines(h).count { (it["msg"] as? String)?.startsWith("f") == true })
    }

    /** Y6：`meta.pre` 所指文件判定不了（是目录 / stat 出错）= 本轮跳过（不重做、不当普通会话恢复）。 */
    @Test
    fun a7_y6_unknownCommitStateSkipsSession() {
        val h = StaticHarness()
        val sid = Ids.newV4()
        val dir = File(File(h.root, "proc-main"), sid).also { it.mkdirs() }
        val pre = "${Ids.newV4()}.jsonl"
        File(File(h.root, "pre"), pre).mkdirs()
        File(dir, "meta.json").writeText(
            "{\"session_id\":\"$sid\",\"session_no\":1,\"started_ms\":1,\"device\":{\"os\":\"a\",\"os_version\":\"b\",\"model\":\"c\"," +
                "\"app_version\":\"d\",\"build\":\"e\",\"locale\":\"f\",\"sdk\":\"g\"},\"process\":\"main\",\"pre\":\"$pre\"}",
        )
        File(dir, "seg-000001.open").writeText("{\"v\":1,\"seg_no\":1,\"user_id\":null,\"started_ms\":1}\n{\"seq\":1,\"oseq\":1,\"ts\":1,\"level\":\"warn\",\"msg\":\"partial\"}\n")
        h.configure("")
        h.settle()
        assertTrue("没当普通会话恢复（没封段）", File(dir, "seg-000001.open").exists())
        assertTrue(h.readJsonl("sessions.jsonl").none { it["session_id"] == sid })
        assertTrue(h.envelopes().none { it["session_id"] == sid })
    }

    /** Y8：configure 之前用户切换记录写失败 → pre 文件标坏（粘滞），之后的行计数，不挂到错的用户名下。 */
    @Test
    fun a7_y8_userRecordFailureMarksBroken() {
        val h = StaticHarness()
        h.log(LogLevel.WARN, "a")
        TestHooks.preWriteFault = { b -> String(b, Charsets.UTF_8).startsWith("{\"pre_user\"") }
        try {
            Retriever.setUser("u")
        } finally {
            TestHooks.preWriteFault = null
        }
        h.log(LogLevel.WARN, "b")
        h.configure("")
        h.settle()
        val ls = lines(h)
        assertEquals(listOf("a"), ls.filter { it["synthetic"] != true }.map { it["msg"] })
        assertEquals(1L, int(obj(ls.single { it["tag"] == DropCounter.TAG }["attrs"])["count"]))
    }

    /** Y9：收编抛异常 → 置重试、startup 照常；5 s 后接着收编，不重复写已收编的记录。 */
    @Test
    fun a7_y9_adoptionExceptionDoesNotBlockStartup() {
        val h = StaticHarness()
        for (i in 0 until 10) h.log(LogLevel.WARN, "pre $i")
        val fired = AtomicBoolean()
        TestHooks.adoption = { p -> if (p == "record:4" && fired.compareAndSet(false, true)) throw IllegalStateException("boom") }
        h.configure("")
        h.settle()
        assertTrue("startup 照常执行", h.client.engine.recovered)
        assertTrue(h.client.writer.isAdopting)
        h.clock.advance(5000)
        h.client.tickNow()
        assertFalse(h.client.writer.isAdopting)
        assertEquals((0 until 10).map { "pre $it" }, lines(h).map { it["msg"] })
        assertEquals(1, h.errors.size)
        h.errors.clear()
    }

    /** Y10：tracker 的回调体兜 Throwable（宿主 Activity 覆写的 hashCode / isChangingConfigurations 抛异常）。 */
    @Test
    fun a7_y10_trackerCallbacksSwallowThrowables() {
        val captured = AtomicReference<Application.ActivityLifecycleCallbacks>()
        val app = object : Application() {
            override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
                captured.set(callback)
            }
        }
        val evil = object : Activity() {
            override fun hashCode(): Int = throw IllegalStateException("hashCode")

            override fun isChangingConfigurations(): Boolean = throw IllegalStateException("isChangingConfigurations")
        }
        LifecycleTracker().install(app, true) { null }
        val cb = captured.get()!!
        cb.onActivityStarted(evil)
        cb.onActivityStopped(evil)
        cb.onActivityCreated(evil, Bundle())
    }

    /** Y12：purge 改名失败的兜底只删 purge 时刻列出的条目；之后新建的 pre 文件与状态不碰。 */
    @Test
    fun a7_y12_purgeFallbackDeletesOnlyListedEntries() {
        val root = tempDir()
        File(root, "pre").mkdirs()
        File(File(root, "pre"), "old.jsonl").writeText("x")
        File(root, "install.json").writeText("{}")
        val listed = RootPurge.snapshot(root)
        val fresh = File(File(root, "pre"), "new.jsonl").also { it.writeText("y") }
        val state = File(root, "outbox").also { it.mkdirs() }
        RootPurge.deleteListed(listed)
        assertFalse(File(File(root, "pre"), "old.jsonl").exists())
        assertFalse(File(root, "install.json").exists())
        assertTrue("purge 之后新建的 pre 文件留着", fresh.exists())
        assertTrue(state.isDirectory)
        root.deleteRecursively()
    }

    /** Y15：收编中 reconfigure 换了 redact：之后的 r = 0 行立即用新钩子。 */
    @Test
    fun a7_y15_redactSwappedMidAdoption() {
        val h = StaticHarness()
        for (i in 0 until 6) h.log(LogLevel.WARN, "pre $i")
        val fired = AtomicBoolean()
        TestHooks.adoption = { p ->
            if (p == "record:3" && fired.compareAndSet(false, true)) {
                Retriever.configure(null, "", Harness.BASE, Options().apply { redact = { it.copy(msg = "new") } })
            }
        }
        h.configure("", Options().apply { redact = { it } })
        h.settle()
        assertEquals(listOf("pre 0", "pre 1", "new", "new", "new", "new"), lines(h).map { it["msg"] })
    }

    // ---------------------------------------------------------------- A8 与 iOS 对齐

    /** (1) configure 之前 setEnabled(true) 而标记删不掉：不记显式 true、保持禁用（P5）。 */
    @Test
    fun a8_1_setEnabledTrueWithUndeletableMarkerStaysDisabled() {
        val parent = tempDir("rtv-a8")
        val root = File(parent, "retriever").also { it.mkdirs() }
        val marker = File(parent, "retriever.disabled").also { it.createNewFile() }
        val h = StaticHarness(root = root)
        assertTrue(parent.setWritable(false, false))
        try {
            assumeFalse("root 用户删得掉，跳过", File(parent, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })
            Retriever.setEnabled(true)
            assertTrue(marker.exists())
            assertFalse(Retriever.isEnabled)
            h.log(LogLevel.WARN, "not written")
            assertEquals(emptyList<File>(), h.preFiles)
            h.configure("")
            h.settle()
            assertFalse(Retriever.isEnabled)
        } finally {
            parent.setWritable(true, false)
            parent.deleteRecursively()
        }
    }

    /** (8) 孤儿缺头记录：started_ms 取首行 ts；(11) 读 pre 记录时用户 id / process / device 再清洗。 */
    @Test
    fun a8_8_11_orphanStartedMsAndResanitize() {
        val h = StaticHarness()
        val noHeader = orphan(h.root, "{\"r\":0,\"ts\":1790000000123,\"level\":\"warn\",\"msg\":\"no header\"}\n")
        orphan(
            h.root,
            header.replace("\"process\":\"main\"", "\"process\":\"a:b\"") + "{\"pre_user\":\"\\u0001u\\u0002\"}\n" +
                "{\"r\":0,\"ts\":1790668000002,\"level\":\"warn\",\"msg\":\"dirty\"}\n",
        )
        h.configure("")
        h.settle()
        assertFalse(noHeader.exists())
        val dirs = h.sessionDirs().filter { it.name != h.client.writer.currentSessionId }
        val a = dirs.single { d -> sessionLines(d).any { it["msg"] == "no header" } }
        assertEquals(1790000000123L, int(JsonIn.obj(File(a, "meta.json").readBytes())!!["started_ms"]))
        val b = dirs.single { d -> sessionLines(d).any { it["msg"] == "dirty" } }
        assertEquals("a_b", JsonIn.obj(File(b, "meta.json").readBytes())!!["process"])
        assertTrue(segHeaders(b).any { it["user_id"] == "u" })
    }

    // ---------------------------------------------------------------- B

    /** B1：恢复旧会话成功完成之前一律不驱逐（总闸门）。 */
    @Test
    fun b1_noEvictionBeforeRecovery() {
        val h = Harness(key = "")
        h.settle()
        h.client.log(LogLevel.WARN, "w", null, null, null)
        h.seal()
        val box = h.outboxFiles()
        assertEquals(1, box.size)
        h.work { e ->
            e.recovered = false
            e.effective.config = e.effective.config.copy(localCapBytes = 0)
            e.evictIfNeeded()
        }
        assertEquals("恢复没做完：不驱逐", box, h.outboxFiles())
        h.work { e ->
            e.recovered = true
            e.evictIfNeeded()
        }
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    /**
     * B1 场景（同 iOS E6 / E7）：旧会话有「已封未物化」的义务段且超过 7 天 + configure 之前 log → setUser → log（收编中换段）：
     * 旧会话的义务行必须进批或有墓碑，零静默。
     */
    @Test
    fun b1_oldSealedObligationNeverSilentlyLost() {
        val h = StaticHarness()
        val sid = Ids.newV4()
        val dir = File(File(h.root, "proc-main"), sid).also { it.mkdirs() }
        File(dir, "meta.json").writeText(
            "{\"session_id\":\"$sid\",\"session_no\":1,\"started_ms\":1,\"device\":{\"os\":\"a\",\"os_version\":\"b\",\"model\":\"c\"," +
                "\"app_version\":\"d\",\"build\":\"e\",\"locale\":\"f\",\"sdk\":\"g\"},\"process\":\"main\"}",
        )
        File(dir, "cursor.json").writeText("{\"extracted_through_oseq\":0,\"ctx_through_seq\":0,\"last_state\":\"bg\",\"last_state_ms\":1}")
        val seg = File(dir, "seg-000001.sealed")
        seg.writeText(
            "{\"v\":1,\"seg_no\":1,\"user_id\":null,\"started_ms\":1}\n" +
                (1..3).joinToString("") { "{\"seq\":$it,\"oseq\":$it,\"ts\":$it,\"level\":\"warn\",\"msg\":\"old $it\"}\n" },
        )
        seg.setLastModified(System.currentTimeMillis() - 8L * 24 * 3600 * 1000)
        h.platform.available = 1L
        h.log(LogLevel.WARN, "p1")
        Retriever.setUser("u")
        h.log(LogLevel.WARN, "p2")
        h.configure("")
        h.settle()
        val inBatch = h.envelopes().filter { it["session_id"] == sid }.flatMap { e -> e.lines.map { int(it["oseq"]) } }.toSet()
        val tombs = h.readJsonl("drops.jsonl").filter { it["session_id"] == sid }.flatMap { (int(it["oseq_from"])..int(it["oseq_to"])).toList() }.toSet()
        for (o in 1L..3L) assertTrue("oseq $o 进批或有墓碑", o in inBatch || o in tombs)
    }

    /** B2：1 MB 上限只管 configure 之前；pre 写满后 configure，收编期间宿主写的 20 条 error 全部落盘（P2），计数不变。 */
    @Test
    fun b2_postConfigureLinesNotCapped() {
        val h = StaticHarness()
        val big = "x".repeat(3000)
        var i = 0
        while (DropCounter.pending == 0L && i < 5000) h.log(LogLevel.INFO, "fill ${i++} $big")
        val counted = DropCounter.pending
        assertTrue(counted > 0)
        val fired = AtomicBoolean()
        TestHooks.adoption = { p ->
            if (p == "record:2" && fired.compareAndSet(false, true)) {
                val t = Thread { repeat(20) { Retriever.log(LogLevel.ERROR, "during adoption $it") } }
                t.start()
                t.join()
            }
        }
        h.configure("", Options().apply { uploadLevel = LogLevel.INFO })
        h.settle()
        val ls = lines(h)
        assertEquals((0 until 20).map { "during adoption $it" }, ls.map { it["msg"] as String }.filter { it.startsWith("during") })
        assertEquals(counted, int(obj(ls.single { it["tag"] == DropCounter.TAG }["attrs"])["count"]))
    }

    /** B3：临时 writer 收编（孤儿）有行没写成 → 不提交：删掉这次写出的段，meta.pre 与 pre 文件原样保留，下次启动重做。 */
    @Test
    fun b3_tempWriterFailureDoesNotCommit() {
        val h = StaticHarness()
        val f = orphan(
            h.root,
            header + "{\"r\":0,\"ts\":1790668000001,\"level\":\"warn\",\"msg\":\"a\"}\n{\"r\":0,\"ts\":1790668000002,\"level\":\"warn\",\"msg\":\"b\"}\n" +
                "{\"pre_user\":\"u\"}\n{\"r\":0,\"ts\":1790668000003,\"level\":\"warn\",\"msg\":\"c\"}\n",
        )
        val proc = File(h.root, "proc-main")
        val fired = AtomicBoolean()
        TestHooks.adoption = { p ->
            if (p == "record:3" && fired.compareAndSet(false, true)) proc.listFiles()?.forEach { it.setWritable(false, false) }
        }
        try {
            h.configure("")
            h.settle()
            assumeFalse("root 用户写得进只读目录，跳过", proc.listFiles()!!.any { File(it, "probe").let { p -> runCatching { p.createNewFile() }.getOrDefault(false) } })
        } finally {
            TestHooks.adoption = null
            proc.listFiles()?.forEach { it.setWritable(true, false) }
        }
        assertTrue("pre 文件原样保留", f.exists())
        val od = h.sessionDirs().single { it.name != h.client.writer.currentSessionId }
        assertEquals(f.name, JsonIn.obj(File(od, "meta.json").readBytes())!!["pre"])
        // 这次写出的段：能删就删（本测试里目录只读、删不掉，留到重做时清）；不管删没删成，都没物化、没进出站箱
        assertTrue("没物化", h.envelopes().none { it["session_id"] == od.name })
        assertTrue(h.readJsonl("sessions.jsonl").none { it["session_id"] == od.name })
        val h2 = h.restart()
        h2.configure("")
        h2.settle()
        assertFalse(f.exists())
        assertEquals(listOf("a", "b", "c"), sessionLines(od).map { it["msg"] })
    }

    /** B4：配置缓存记用户：缓存是用户 A 拉的（远程明确给 upload_level = debug），离线冷启动、configure 之前 setUser("B") → B 的 info 无 oseq。 */
    @Test
    fun b4_configCacheBelongsToUser() {
        val t = FakeTransport().apply { configBody = mapOf("etag" to "e", "ttl_s" to 1800, "upload_level" to "debug") }
        val h = StaticHarness(transport = t)
        h.configure("lk_test_demo_abc_12345678")
        Retriever.setUser("A")
        h.settle()
        val cfg = JsonIn.obj(File(h.root, "config.json").readBytes())!!
        assertEquals("A", cfg["user_id"])
        assertEquals(listOf("fetched_ms", "key_fp", "base_url", "user_id", "config", "from_host"), cfg.keys.toList())
        val t2 = FakeTransport().apply { configGate = CountDownLatch(1) }
        val h2 = h.restart(t2)
        Retriever.setUser("B")
        h2.log(LogLevel.INFO, "b info")
        h2.configure("lk_test_demo_abc_12345678")
        h2.client.onWork { }
        assertTrue(h2.client.engine.configCache!!.identityStale)
        assertEquals(-999L, int(lines(h2).single { it["msg"] == "b info" }["oseq"]))
        t2.configGate!!.countDown()
        t2.configGate = null
        h2.settle()
    }

    /** B5：flush 兜底计时器不排在引擎线程上：引擎被堵 1.5 s，300 ms 窗口到期照样回 Pending("timeout")。 */
    @Test
    fun b5_flushTimerFiresWhileEngineBlocked() {
        val h = Harness()
        h.settle()
        h.client.setFlushWindowForTesting(300)
        val gate = h.blockEngine()
        val got = AtomicReference<FlushResult>()
        val done = CountDownLatch(1)
        h.client.flush(true) { r ->
            got.set(r)
            done.countDown()
        }
        val inTime = done.await(1200, TimeUnit.MILLISECONDS)
        gate.countDown()
        assertTrue("窗口到期就回，不等引擎线程", inTime)
        assertEquals(FlushResult.Pending("timeout"), got.get())
        h.settle()
    }

    /** B6：`rtv.pre_init_dropped` 强制写入、强制义务（不受 local_level / upload_level 影响）。 */
    @Test
    fun b6_droppedLineForcedObligation() {
        val h = StaticHarness(noContext = true)
        h.log(LogLevel.DEBUG, "lost 1")
        h.log(LogLevel.DEBUG, "lost 2")
        Retriever.setEnvForTesting(h.env())
        h.configure("", Options().apply {
            localLevel = LogLevel.ERROR
            uploadLevel = LogLevel.FATAL
        })
        h.settle()
        val d = lines(h).single { it["tag"] == DropCounter.TAG }
        assertEquals(1L, int(d["oseq"]))
        assertEquals(2L, int(obj(d["attrs"])["count"]))
    }

    /** B7：驱逐先记墓碑、记成了再删；墓碑写不成本轮不驱逐该项。 */
    @Test
    fun b7_evictionWritesTombstoneFirst() {
        val h = Harness(key = "")
        h.settle()
        h.client.log(LogLevel.WARN, "w", null, null, null)
        h.seal()
        val box = h.outboxFiles()
        val drops = File(h.root, "drops.jsonl")
        assertTrue(drops.mkdir())
        h.work { e ->
            e.effective.config = e.effective.config.copy(localCapBytes = 0)
            e.evictIfNeeded()
        }
        assertEquals("墓碑写不成：不驱逐", box, h.outboxFiles())
        assertTrue(drops.delete())
        h.work { e -> e.evictIfNeeded() }
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals("buffer_overflow", h.readJsonl("drops.jsonl").single()["reason"])
    }

    /** B9：bootstrap 重试时保留待写的合成行（install.json 修复的 `rtv.install_repaired`）。 */
    @Test
    fun b9_pendingSyntheticSurvivesBootstrapRetry() {
        val h = StaticHarness()
        h.configure("")
        h.log(LogLevel.WARN, "first run")
        h.settle()
        val iid = Retriever.installId
        val h2 = h.restart()
        File(h2.root, "install.json").writeBytes("{\"install_id\":\"bad".toByteArray())
        Fs.writeAtomicFaultForTesting = { it.name == "meta.json" }
        h2.configure("")
        h2.settle()
        assertFalse(h2.client.isBootstrapped)
        Fs.writeAtomicFaultForTesting = null
        h2.clock.advance(5000)
        h2.client.tickNow()
        assertTrue(h2.client.isBootstrapped)
        assertEquals(iid, Retriever.installId)
        assertEquals(1, lines(h2).count { it["tag"] == "rtv.install_repaired" })
    }

    /** B6 / A4 配套：被 local_level 过滤的级别也不影响计数行；null level 的计数只在下一次唤醒时一条上报。 */
    @Test
    fun b6_a4_nullLevelReportedOnceAtTick() {
        val h = StaticHarness()
        h.configure("", Options().apply { localLevel = LogLevel.ERROR })
        h.settle()
        repeat(7) { Retriever.log(null, "x") }
        assertEquals(0, lines(h).count { it["tag"] == DropCounter.TAG })
        h.client.tickNow()
        val d = lines(h).single { it["tag"] == DropCounter.TAG }
        assertEquals(7L, int(obj(d["attrs"])["count"]))
        assertNull(lines(h).firstOrNull { it["msg"] == "x" })
        h.client.writer.rotate(SealReason.TIMER)
    }
}
