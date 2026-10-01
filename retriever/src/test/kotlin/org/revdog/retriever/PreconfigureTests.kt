package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.revdog.retriever.core.DropCounter
import org.revdog.retriever.core.Fs
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.PreFile
import org.revdog.retriever.core.SessionMeta
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * configure 之前没有实例（ADR 0023；简报 §1 / §4.1–4.3；§11 A / B / C / F / G / H / I / L / M）。全部走静态入口 `Retriever.*`。
 * D / E / J（真杀进程、别的活进程）在 PreconfigureKillTests。
 */
class PreconfigureTests : RtvTest() {
    private fun lines(h: StaticHarness): List<SegLineRec> = sessionLines(h.currentSessionDir())

    private fun msgs(h: StaticHarness): List<Any?> = lines(h).map { it["msg"] }

    private fun dropped(h: StaticHarness): Map<String, Any?>? = lines(h).singleOrNull { it["tag"] == DropCounter.TAG }?.map

    // ---------------------------------------------------------------- A

    /** 先 log 后 configure(INFO)：info 及以上有 oseq 且从 1 连续，debug 照写不占 oseq；ts 保持写入时刻；pre 文件已删、meta 无 pre。 */
    @Test
    fun a_linesBeforeConfigureJudgedByThisConfigure() {
        val h = StaticHarness()
        val ts = ArrayList<Long>()
        for ((i, l) in listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR).withIndex()) {
            ts.add(h.clock.wallMs())
            h.log(l, "pre $i")
            h.clock.advance(1000)
        }
        assertNull("configure 之前没有实例", Retriever.currentClient())
        assertEquals(1, h.preFiles.size)
        assertEquals(LogLevel.WARN, Retriever.uploadLevel)
        assertEquals(LogLevel.DEBUG, Retriever.localLevel)
        assertNull(Retriever.installId)
        assertNull(Retriever.supportCode)
        h.configure("", Options().apply { uploadLevel = LogLevel.INFO })
        h.settle()
        val ls = lines(h)
        assertEquals(listOf("pre 0", "pre 1", "pre 2", "pre 3"), ls.map { it["msg"] })
        assertEquals(listOf(1L, 2L, 3L, 4L), ls.map { int(it["seq"]) })
        assertEquals(listOf(-999L, 1L, 2L, 3L), ls.map { int(it["oseq"]) })
        assertEquals("ts 保持写入时刻", ts, ls.map { int(it["ts"]) })
        assertEquals("提交 = unlink pre 文件", emptyList<File>(), h.preFiles)
        assertFalse(JsonIn.obj(File(h.currentSessionDir(), "meta.json").readBytes())!!.containsKey("pre"))
        // configure 之后照常写，oseq 接着编
        h.log(LogLevel.INFO, "after")
        assertEquals(4L, int(lines(h).last()["oseq"]))
    }

    @Test
    fun a_uploadLevelErrorAndLocalLevelInfo() {
        val h = StaticHarness()
        h.log(LogLevel.DEBUG, "d")
        h.log(LogLevel.WARN, "w")
        h.log(LogLevel.ERROR, "e")
        h.configure("", Options().apply { uploadLevel = LogLevel.ERROR })
        h.settle()
        val ls = lines(h)
        assertEquals(listOf("d", "w", "e"), ls.map { it["msg"] })
        assertEquals("warn 无 oseq", listOf(-999L, -999L, 1L), ls.map { int(it["oseq"]) })

        val h2 = StaticHarness()
        h2.log(LogLevel.DEBUG, "d")
        h2.log(LogLevel.INFO, "i")
        h2.configure("", Options().apply { localLevel = LogLevel.INFO })
        h2.settle()
        assertEquals("debug 行不出现、不占 seq", listOf("i"), msgs(h2))
        assertEquals(1L, int(lines(h2).single()["seq"]))
    }

    // ---------------------------------------------------------------- B

    /** configure 之前的行经过 redact：改写生效、null / 抛异常丢弃；钩子里调 log 不死锁、被忽略；synthetic / truncated 保留。 */
    @Test
    fun b_preLinesGoThroughRedact() {
        val h = StaticHarness()
        var calls = 0
        val opts = Options().apply {
            uploadLevel = LogLevel.INFO
            redact = { l ->
                calls++
                when {
                    "drop" in l.msg -> null
                    "boom" in l.msg -> throw IllegalStateException("hook bug")
                    "reenter" in l.msg -> {
                        Retriever.log(LogLevel.ERROR, "from inside hook")
                        l
                    }
                    "secret" in l.msg -> l.copy(msg = l.msg.replace("secret", "***"))
                    "long" in l.msg -> l.copy(msg = "short")
                    else -> l
                }
            }
        }
        for (m in listOf("a secret", "please drop", "boom", "reenter", "long " + "x".repeat(5000), "plain")) h.log(LogLevel.INFO, m)
        h.configure("", opts)
        h.settle()
        val ls = lines(h)
        assertEquals(listOf("a ***", "reenter", "short", "plain"), ls.map { it["msg"] })
        assertEquals("收编的行按文件顺序编号", listOf(1L, 2L, 3L, 4L), ls.map { int(it["seq"]) })
        assertEquals("截断标记与原值取或", true, ls[2]["truncated"])
        assertEquals(6, calls)
        assertEquals("钩子抛异常走内部出口", 1, h.errors.size)
        h.errors.clear()
    }

    /** 合成行不经 redact（收编中的 bootstrap 合成行进 pre 文件 r = 1）；无钩子时行体字节不变。 */
    @Test
    fun b_syntheticSkipsRedactAndBytesUnchangedWithoutHook() {
        val h = StaticHarness()
        h.configure("")
        h.settle()
        // 下一次启动 install.json 损坏 → bootstrap 写合成 warn rtv.install_repaired（收编中 → pre 文件 r = 1）
        val h2 = h.restart()
        File(h2.root, "install.json").writeBytes("{\"install_id\":\"bad".toByteArray())
        h2.log(LogLevel.WARN, "pre line", attrs = mapOf("n" to 1.25, "s" to "中文 😀", "big" to 9_007_199_254_740_993L))
        h2.configure("", Options().apply { redact = { null } })
        h2.settle()
        val ls = lines(h2)
        assertEquals("钩子丢光 configure 之前的行；合成行照写", listOf("rtv.install_repaired"), ls.map { it["tag"] })
        assertEquals(true, ls.single()["synthetic"])

        // 无钩子：段里的行 = pre 记录去掉 `{"r":0,` 换上 seq / oseq 前缀，其余逐字节相同
        val h3 = StaticHarness()
        h3.log(LogLevel.WARN, "bytes", attrs = mapOf("n" to 1.25, "s" to "中文 😀\u0001", "big" to 9_007_199_254_740_993L), error = IllegalStateException("x"))
        val rec = h3.preFiles.single().readText().split("\n").first { it.startsWith("{\"r\":0,") }
        h3.configure("")
        h3.settle()
        val raw = lines(h3).single().raw
        assertEquals(rec.removePrefix("{\"r\":0,"), raw.substringAfter("\"oseq\":1,"))
    }

    // ---------------------------------------------------------------- C

    /** configure 之前 setUser：用户边界与段头 user_id 正确；configure 之前 setUser（文件还没建）→ 写在头记录之后。 */
    @Test
    fun c_userBoundariesBeforeConfigure() {
        val h = StaticHarness()
        Retriever.setUser("early")
        h.log(LogLevel.WARN, "as early")
        Retriever.setUser(null)
        h.log(LogLevel.WARN, "anon")
        Retriever.setUser("u1")
        h.log(LogLevel.WARN, "as u1")
        Retriever.setUser("u2")
        h.configure("")
        h.settle()
        val dir = h.currentSessionDir()
        val heads = segHeaders(dir)
        assertEquals(listOf("early", null, "u1", "u2"), heads.map { it["user_id"] })
        val bySeg = (dir.list()!!.filter { it.startsWith("seg-") }.sortedBy { it.substring(4, 10).toInt() })
            .map { n -> File(dir, n).readText().split("\n").drop(1).filter { it.isNotEmpty() }.map { JsonIn.obj(it)!!["msg"] } }
        assertEquals(listOf(listOf("as early"), listOf("anon"), listOf("as u1"), emptyList()), bySeg)
        assertEquals("u2", h.client.writer.currentUser)
        h.log(LogLevel.WARN, "as u2")
        h.client.writer.rotate(org.revdog.retriever.core.SealReason.TIMER)
        h.client.onWork { h.client.engine.processSeals() }
        assertEquals(listOf("early", null, "u1", "u2"), h.envelopes().sortedBy { int(it["seq_from"]) }.map { it["user_id"] })
    }

    /** configure 之前写 error：收编后在去抖时间内封段；fatal：立即封段（启动后出站箱就有 p0）。 */
    @Test
    fun c_errorDebounceAndFatalSealAfterAdoption() {
        val h = StaticHarness()
        h.log(LogLevel.INFO, "ctx")
        h.log(LogLevel.ERROR, "pre error")
        h.configure("")
        h.settle()
        assertEquals(emptyList<String>(), h.outboxFiles())
        h.clock.advance(Limits.ERROR_DEBOUNCE_MS)
        h.client.tickNow()
        val p0 = h.envelopes("p0").single()
        assertEquals(listOf("ctx", "pre error"), p0.lines.map { it["msg"] })
        assertEquals(true, p0.lines[0]["ctx"])

        val h2 = StaticHarness()
        h2.log(LogLevel.INFO, "ctx")
        h2.log(LogLevel.FATAL, "pre fatal")
        h2.configure("")
        h2.settle()
        assertEquals("fatal 立即换段，提交后物化", listOf("ctx", "pre fatal"), h2.envelopes("p0").single().lines.map { it["msg"] })
    }

    // ---------------------------------------------------------------- F

    /**
     * configure 之前：零驱逐、零联网、零后台作业、不恢复旧会话（出站箱超过默认 20 MB 上限也不动）；configure(localCap = 50 MB) 后按 50 MB。
     */
    @Test
    fun f_nothingHappensBeforeConfigure() {
        val h = StaticHarness()
        h.configure("")
        h.log(LogLevel.WARN, "old session line")
        h.settle()
        val oldSid = h.client.writer.currentSessionId
        val h2 = h.restart()
        // 出站箱塞 25 MB（> 默认 20 MB）
        val rnd = java.util.Random(3)
        for (i in 0 until 5) File(h2.outbox, "p1-${1_790_000_000_000L + i}-${java.util.UUID.randomUUID()}.gz").writeBytes(ByteArray(5 * 1024 * 1024).also { rnd.nextBytes(it) })
        val before = h2.outboxFiles()
        for (i in 0 until 50) h2.log(LogLevel.ERROR, "pre $i")
        h2.log(LogLevel.FATAL, "pre fatal")
        assertNull(Retriever.currentClient())
        assertEquals(before, h2.outboxFiles())
        assertEquals("零联网", 0, h2.transport.allRequests.size)
        assertEquals("零后台作业", 0, h2.platform.scheduledJobs.size)
        assertTrue("不恢复旧会话", File(File(h2.root, "proc-main"), oldSid).isDirectory)
        assertFalse(File(h2.root, "sessions.jsonl").exists())
        assertFalse(File(h2.root, "drops.jsonl").exists())
        h2.configure("", Options().apply { localCapBytes = 50L * 1024 * 1024 })
        h2.settle()
        assertEquals(50 * 1024 * 1024, h2.client.engine.effective.config.localCapBytes)
        assertTrue("按 50 MB：一个旧批都没驱逐", h2.outboxFiles().containsAll(before))
        assertTrue(h2.readJsonl("drops.jsonl").none { it["reason"] == "buffer_overflow" })
    }

    // ---------------------------------------------------------------- G

    /** pre 文件 1 MB 上限：超出的行计数，`rtv.pre_init_dropped` 的四项计数正确。 */
    @Test
    fun g_capOverflowCounted() {
        val h = StaticHarness()
        val n = 700
        val big = "x".repeat(2000)
        val ts = ArrayList<Long>()
        for (i in 0 until n) {
            ts.add(h.clock.wallMs())
            h.log(if (i % 10 == 0) LogLevel.ERROR else LogLevel.INFO, "$i $big")
            h.clock.advance(1)
        }
        val size = h.preFiles.single().length()
        assertTrue("$size", size <= PreFile.MAX_BYTES && size > PreFile.MAX_BYTES - 3000)
        h.configure("")
        h.settle()
        val adopted = lines(h).count { it["synthetic"] != true }
        assertTrue(adopted in 1 until n)
        val d = obj(dropped(h)!!["attrs"])
        assertEquals((n - adopted).toLong(), int(d["count"]))
        assertEquals((adopted until n).count { it % 10 == 0 }.toLong(), int(d["error_count"]))
        assertEquals(ts[adopted], int(d["first_ts"]))
        assertEquals(ts[n - 1], int(d["last_ts"]))
        val line = dropped(h)!!
        assertEquals("warn", line["level"])
        assertEquals(DropCounter.MSG, line["msg"])
        assertEquals(true, line["synthetic"])
        assertEquals(0L, DropCounter.pending)
    }

    /** 写失败（pre 目录建不出来）与没有 context：计数；禁用期间不计。 */
    @Test
    fun g_writeFailureNoContextAndDisabled() {
        val h = StaticHarness()
        File(h.root, "pre").also { it.parentFile!!.mkdirs() }.writeText("not a dir")
        h.log(LogLevel.ERROR, "lost 1")
        h.clock.advance(5)
        h.log(LogLevel.INFO, "lost 2")
        File(h.root, "pre").delete()
        h.configure("")
        h.settle()
        val d = obj(dropped(h)!!["attrs"])
        assertEquals(mapOf("count" to 2.0, "error_count" to 1.0, "first_ts" to h.clock.wallMs() - 5.0, "last_ts" to h.clock.wallMs().toDouble()), d)

        // 没有 context（非默认进程 / provider 被移除）：无处可写，只计数；之后有了 context 再 configure
        val h2 = StaticHarness(noContext = true)
        h2.log(LogLevel.WARN, "no context 1")
        h2.log(LogLevel.FATAL, "no context 2")
        assertFalse(File(h2.root, "pre").exists())
        Retriever.setEnvForTesting(h2.env())
        h2.configure("")
        h2.settle()
        assertEquals(2L, int(obj(dropped(h2)!!["attrs"])["count"]))
        assertEquals(1L, int(obj(dropped(h2)!!["attrs"])["error_count"]))

        // 禁用期间：不写、不计
        val h3 = StaticHarness()
        Retriever.setEnabled(false)
        h3.log(LogLevel.ERROR, "not written")
        assertFalse(File(h3.root, "pre").exists())
        assertEquals(0L, DropCounter.pending)
        Retriever.setEnabled(true)
        h3.configure("")
        h3.settle()
        assertNull(dropped(h3))
    }

    /**
     * 孤儿 pre 文件：拿得到锁的一律收编，不看年龄（一周没开 app 的用户上次 configure 之前的行不丢）；没能收编的（被别的进程名认领）
     * 超过 7 天在驱逐时删；pre 文件计入本地总量（认领着没收编的那份让总量超上限 → 驱逐出站箱批）。
     */
    @Test
    fun g_orphanPreAgeAndTotal() {
        val h = StaticHarness()
        val preDir = File(h.root, "pre").also { it.mkdirs() }
        val header = "{\"pre\":1,\"started_ms\":1,\"process\":\"main\",\"device\":{\"os\":\"android\",\"os_version\":\"15\",\"model\":\"m\",\"app_version\":\"1\",\"build\":\"1\",\"locale\":\"en\",\"sdk\":\"x\"}}\n"
        val eightDays = h.clock.wallMs() - 8L * 24 * 3600 * 1000
        // 先单独看「老孤儿照样收编」（没有容量压力）
        val h0 = StaticHarness()
        val old = File(File(h0.root, "pre").also { it.mkdirs() }, "${java.util.UUID.randomUUID()}.jsonl")
        old.writeText(header + "{\"r\":0,\"ts\":1,\"level\":\"warn\",\"msg\":\"ancient\"}\n")
        old.setLastModified(eightDays)
        h0.configure("")
        h0.settle()
        assertFalse("超过 7 天的孤儿照样收编", old.exists())
        assertTrue(h0.sessionDirs().any { d -> sessionLines(d).any { it["msg"] == "ancient" } })
        StaticHarness(root = h.root, clock = h.clock, transport = h.transport, platform = h.platform)
        // 认领它们的会话在别的进程名下（那个进程下次启动才重做）：本进程不收编；年轻的计入总量，超过 7 天的驱逐时删
        val dev = org.revdog.retriever.core.Device("android", "15", "m", "1", "1", "en", "x")
        fun claimed(bytes: ByteArray): File {
            val f = File(preDir, "${java.util.UUID.randomUUID()}.jsonl")
            f.writeBytes(bytes)
            val sid = java.util.UUID.randomUUID().toString()
            val d = File(File(h.root, "proc-other"), sid).also { it.mkdirs() }
            File(d, "meta.json").writeBytes(SessionMeta(sid, 1, 1, dev, "other", null, f.name).encode())
            return f
        }
        val big = claimed(ByteArray(3 * 1024 * 1024) { 0x20 })
        val stale = claimed((header + "{\"r\":0,\"ts\":1,\"level\":\"warn\",\"msg\":\"stale\"}\n").toByteArray())
        stale.setLastModified(eightDays)
        // 一个 1 MB 的出站箱批：只有把 3 MB 的 pre 文件算进总量才超过 2 MB 上限
        val batch = File(h.outbox.also { it.mkdirs() }, "p1-1790000000000-${java.util.UUID.randomUUID()}.gz")
        batch.writeBytes(ByteArray(1024 * 1024).also { java.util.Random(1).nextBytes(it) })
        h.configure("", Options().apply { localCapBytes = 2L * 1024 * 1024 })
        h.settle()
        assertTrue("别的进程名认领的不收编", big.exists())
        assertFalse("没能收编、超过 7 天的驱逐时删", stale.exists())
        assertFalse("pre 文件计入总量：出站箱批被驱逐", batch.exists())
    }

    // ---------------------------------------------------------------- H

    /** 禁用标记在：configure 之前零写入、零计数，isEnabled = false。 */
    @Test
    fun h_markerBlocksPreWrites() {
        val h = StaticHarness()
        val marker = File(h.root.absoluteFile.parentFile, h.root.name + ".disabled")
        assertTrue(marker.createNewFile())
        try {
            assertFalse(Retriever.isEnabled)
            h.log(LogLevel.ERROR, "x")
            assertFalse(File(h.root, "pre").exists())
            assertEquals(0L, DropCounter.pending)
            h.configure("")
            h.settle()
            assertFalse(h.client.isEnabled)
            assertEquals(0L, h.client.debugCounters.first)
        } finally {
            marker.delete()
        }
    }

    /** 标记判定未知（父目录列不出）→ 按禁用；判定恢复后（bootstrap 成功 / 定时唤醒）重判。 */
    @Test
    fun h_unknownMarkerFailsClosedThenRejudged() {
        val parent = tempDir("rtv-parent")
        val root = File(parent, "retriever")
        val h = StaticHarness(root = root)
        assertTrue(parent.setReadable(false, false))
        try {
            assumeFalse("root 用户列得出，跳过", parent.list() != null)
            assertFalse(Retriever.isEnabled)
            h.log(LogLevel.ERROR, "unknown → not written")
            assertFalse(File(root, "pre").exists())
            h.configure("")
            h.settle()
            assertFalse(h.client.isEnabled)
            assertTrue(h.client.markerUnknown)
            h.log(LogLevel.ERROR, "still not written")
            assertEquals(0L, h.client.debugCounters.first)
        } finally {
            parent.setReadable(true, false)
        }
        h.client.tickNow()
        assertTrue("判定恢复：重判为启用", h.client.isEnabled)
        h.log(LogLevel.ERROR, "written")
        assertEquals(listOf("written"), msgs(h))
        parent.deleteRecursively()
    }

    /** configure 之前 purgeLocal：pre 文件与 root 都清（改名再删）、计数清零、回调触发；之后的行重新建 pre 文件。 */
    @Test
    fun h_purgeBeforeConfigure() {
        val h = StaticHarness()
        h.configure("")
        h.settle()
        val h2 = h.restart()
        h2.log(LogLevel.WARN, "to purge")
        Retriever.setEnvForTesting(StaticHarness(root = File(h2.root.parentFile, "absent-" + h2.root.name), clock = h2.clock).env(noContext = true))
        Retriever.log(LogLevel.WARN, "counted")
        Retriever.setEnvForTesting(h2.env())
        assertEquals(1L, DropCounter.pending)
        assertTrue(File(h2.root, "install.json").exists())
        val done = CountDownLatch(1)
        Retriever.purgeLocal { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertFalse("root 清了", File(h2.root, "install.json").exists())
        assertEquals(emptyList<File>(), h2.preFiles)
        assertEquals(0L, DropCounter.pending)
        assertTrue(h2.root.absoluteFile.parentFile!!.list()!!.none { it.startsWith(h2.root.name + ".purge-") })
        h2.log(LogLevel.WARN, "after purge")
        h2.configure("")
        h2.settle()
        assertEquals(listOf("after purge"), msgs(h2))
        // null 回调也不崩
        Retriever.purgeLocal(null)
    }

    // ---------------------------------------------------------------- I

    /** 第二次 configure 改 processName：被忽略 + `rtv.reconfigure_ignored`，行继续进原会话。同参数重复 configure：零请求。 */
    @Test
    fun i_identityFixedAtFirstConfigure() {
        val t = FakeTransport().apply { configBody = mapOf("etag" to "e", "ttl_s" to 1800) }
        val h = StaticHarness(transport = t)
        h.configure("lk_test_demo_abc_12345678")
        h.settle()
        val sid = h.client.writer.currentSessionId
        val client = h.client
        h.log(LogLevel.WARN, "before")
        h.configure("lk_test_demo_abc_12345678", Options().apply { processName = "other" })
        h.log(LogLevel.WARN, "after")
        h.settle()
        assertTrue("同一个实例", client === Retriever.currentClient())
        assertEquals("main", h.client.processName)
        assertEquals(sid, h.client.writer.currentSessionId)
        assertFalse(File(h.root, "proc-other").exists())
        val ls = lines(h)
        assertEquals(listOf("before", RetrieverClientConsts.IGNORED_MSG, "after"), ls.map { it["msg"] })
        val w = ls[1]
        assertEquals("rtv.reconfigure_ignored", w["tag"])
        assertEquals(mapOf("field" to "process_name"), w["attrs"])
        assertEquals(true, w["synthetic"])
        // 同参数重复 configure：零请求、零合成行
        val n0 = t.allRequests.size
        val seq0 = h.client.debugCounters.first
        repeat(5) { h.configure("lk_test_demo_abc_12345678") }
        h.settle()
        assertEquals(n0, t.allRequests.size)
        assertEquals(seq0, h.client.debugCounters.first)
        // 同参数但换了 redact：只更新 redact
        h.configure("lk_test_demo_abc_12345678", Options().apply { redact = { it.copy(msg = "masked") } })
        h.log(LogLevel.WARN, "secret")
        assertEquals("masked", lines(h).last()["msg"])
        assertEquals(n0, t.allRequests.size)
    }

    // ---------------------------------------------------------------- L

    /** 并发：多线程 log 与 configure 并发，seq / oseq 无重无缺、行不丢。 */
    @Test
    fun l_concurrentLogAndConfigure() {
        val h = StaticHarness()
        val pool = Executors.newFixedThreadPool(9)
        val start = CountDownLatch(1)
        val threads = 8
        val per = 400
        repeat(threads) { t ->
            pool.execute {
                start.await()
                for (i in 0 until per) Retriever.log(if (i % 3 == 0) LogLevel.WARN else LogLevel.DEBUG, "t$t i$i")
            }
        }
        pool.execute {
            start.await()
            Thread.sleep(2)
            h.configure("", Options().apply { redact = { it } })
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        h.settle()
        val ls = lines(h)
        assertEquals(threads * per, ls.size)
        assertEquals((1L..(threads * per).toLong()).toList(), ls.map { int(it["seq"]) })
        val oseqs = ls.filter { it.map.containsKey("oseq") }.map { int(it["oseq"]) }
        assertEquals((1L..oseqs.size.toLong()).toList(), oseqs)
        assertEquals(threads * (0 until per).count { it % 3 == 0 }, oseqs.size)
        // 每个线程自己的行顺序不乱
        for (t in 0 until threads) assertEquals((0 until per).map { "t$t i$it" }, ls.map { it["msg"] as String }.filter { it.startsWith("t$t ") })
        assertEquals(0L, DropCounter.pending)
        assertEquals(emptyList<File>(), h.preFiles)
    }

    // ---------------------------------------------------------------- M

    /** 没有会话时的 log()（bootstrap 失败：meta.json 写不成）：计数、重试成功后上报；不留「有会话、没 meta」的目录。 */
    @Test
    fun m_noSessionCountedAndMetaRequired() {
        val h = StaticHarness()
        Fs.writeAtomicFaultForTesting = { it.name == "meta.json" }
        h.configure("")
        h.settle()
        assertFalse("meta 写不成 = bootstrap 失败", h.client.isBootstrapped)
        assertNull(Retriever.installId)
        for (d in h.sessionDirs()) assertTrue("没有无 meta 的会话目录：$d", File(d, "meta.json").exists())
        h.log(LogLevel.ERROR, "lost e")
        h.log(LogLevel.INFO, "lost i")
        Fs.writeAtomicFaultForTesting = null
        h.clock.advance(5000)
        h.log(LogLevel.WARN, "triggers retry")
        h.settle()
        assertTrue(h.client.isBootstrapped)
        assertNotNull(Retriever.installId)
        val d = obj(dropped(h)!!["attrs"])
        assertEquals(3L, int(d["count"]))
        assertEquals(1L, int(d["error_count"]))
        h.log(LogLevel.WARN, "written")
        assertEquals(listOf(DropCounter.MSG, "written"), msgs(h))
    }
}

/** 测试里引用的合成行文案。 */
internal object RetrieverClientConsts {
    const val IGNORED_MSG = org.revdog.retriever.core.RetrieverClient.RECONFIGURE_IGNORED_MSG
}

/** 主代理定稿的两端对齐口径（简报补充 B）：提交点、禁用时照样收编、解不出的行计数、往返无损。 */
class PreAlignmentTests : RtvTest() {
    private fun orphan(root: File, vararg records: String): File {
        val dir = File(root, "pre").also { it.mkdirs() }
        val f = File(dir, "${java.util.UUID.randomUUID()}.jsonl")
        f.writeText(
            "{\"pre\":1,\"started_ms\":1790668000000,\"process\":\"main\",\"device\":{\"os\":\"android\",\"os_version\":\"15\",\"model\":\"m\"," +
                "\"app_version\":\"1\",\"build\":\"1\",\"locale\":\"en\",\"sdk\":\"retriever-android/0.3.0\"}}\n" + records.joinToString("") { it + "\n" },
        )
        return f
    }

    private fun adoptedOrphan(h: StaticHarness): File = h.sessionDirs().single { it.name != h.client.writer.currentSessionId }

    /** unlink 失败时把自己的句柄截成 0 长度 = 已提交；长度 0 的 pre 文件不收编、不重做（直接删）。 */
    @Test
    fun commitByTruncateWhenUnlinkFails() {
        val root = tempDir()
        val pf = PreFile.create(root)!!
        assertEquals(PreFile.Put.WRITTEN, pf.append("{\"pre_user\":null}\n".toByteArray(), true))
        val dir = File(root, "pre")
        assertTrue(dir.setWritable(false))
        try {
            org.junit.Assume.assumeFalse("root 用户删得掉，跳过", pf.file.let { File(dir, "probe").let { p -> runCatching { p.createNewFile() }.getOrDefault(false) } })
            assertTrue(pf.unlinkAndClose())
            assertTrue(pf.file.exists())
            assertEquals(0L, pf.file.length())
        } finally {
            dir.setWritable(true)
        }
        val h = StaticHarness(root = root)
        h.configure("")
        h.settle()
        assertFalse("长度 0 = 已提交：删掉", pf.file.exists())
        assertEquals(1, h.sessionDirs().size)
    }

    /** 禁用状态下照样收编孤儿 pre 文件（已落盘的数据），上传仍被禁用挡住。 */
    @Test
    fun orphanAdoptedWhileDisabled() {
        val h = StaticHarness()
        val f = orphan(h.root, "{\"r\":0,\"ts\":1790668000001,\"level\":\"warn\",\"msg\":\"orphan w\"}")
        val marker = File(h.root.absoluteFile.parentFile, h.root.name + ".disabled")
        assertTrue(marker.createNewFile())
        try {
            h.configure("lk_test_demo_abc_12345678")
            h.settle()
            assertFalse(f.exists())
            assertEquals(listOf("orphan w"), sessionLines(adoptedOrphan(h)).map { it["msg"] })
            assertEquals(0, h.transport.batchRequests.size)
        } finally {
            marker.delete()
        }
    }

    /** 有 redact 钩子而 pre 行解不出来：丢弃并计数（之后以 rtv.pre_init_dropped 上报）。 */
    @Test
    fun undecodableLineWithHookDroppedAndCounted() {
        val h = StaticHarness()
        orphan(h.root, "{\"r\":0,\"ts\":1790668000002,\"level\":\"error\",\"msg\":}", "{\"r\":0,\"ts\":1790668000003,\"level\":\"warn\",\"msg\":\"ok\"}")
        h.configure("", Options().apply { redact = { it } })
        h.settle()
        assertEquals(listOf("ok"), sessionLines(adoptedOrphan(h)).map { it["msg"] })
        h.client.tickNow()
        val d = sessionLines(h.currentSessionDir()).single { it["tag"] == DropCounter.TAG }
        assertEquals(mapOf("count" to 1.0, "error_count" to 1.0, "first_ts" to 1790668000002.0, "last_ts" to 1790668000002.0), d["attrs"])
    }

    /**
     * 往返无损（简报补充 B-17）：恒等 redact 钩子下「解码 → 钩子 → 重编码」与输入逐字节相同。iOS 的夹具
     * `sdk/ios/Tests/RetrieverTests/Fixtures/pre-roundtrip.json` 出现后一并回放；没有时只跑本端这组行体。
     */
    @Test
    fun preRoundtripLossless() {
        val bodies = ArrayList<Pair<String, String>>()
        val enc = org.revdog.retriever.core.LineEncoder
        fun add(name: String, l: LogLine, synthetic: Boolean = false) {
            bodies.add(Pair(name, String(enc.encode(l, synthetic).body, Charsets.UTF_8).trimEnd('\n')))
        }
        add("plain", LogLine(1790668800000, LogLevel.INFO, "hello", null, null, null))
        add("full", LogLine(1, LogLevel.ERROR, "m \"q\" \\ \u0001 中文 😀", "tag", mapOf("a" to 1.5, "b" to true, "c" to null, "d" to 9_007_199_254_740_993L, "e" to -0.000001, "f" to "s"), LogException("T", "msg", "line1\n\tat x")))
        add("synthetic", LogLine(2, LogLevel.WARN, "lines dropped", "rtv.pre_init_dropped", mapOf("count" to 3), null), synthetic = true)
        add("truncated", LogLine(3, LogLevel.DEBUG, "x".repeat(5000), null, null, null))
        add("lone surrogate", LogLine(4, LogLevel.INFO, "a\uD800b", null, mapOf("k" to "\uDC00"), null))
        add("numbers", LogLine(5, LogLevel.FATAL, "n", null, mapOf("big" to 1e21, "small" to 1.5e-7, "neg" to -5, "frac" to 123.456), null))
        val fixture = File(Repo.root, "sdk/ios/Tests/RetrieverTests/Fixtures/pre-roundtrip.json")
        if (fixture.exists()) for (v in objs(JsonIn.parse(fixture.readBytes()))) bodies.add(Pair(v["name"] as String, v["body"] as String))
        for ((name, body) in bodies) {
            val bytes = (body + "\n").toByteArray(Charsets.UTF_8)
            val (line, synthetic, truncated) = enc.decodeBody(bytes) ?: error("$name 解不出")
            val out = enc.encode(line.copy(), synthetic, truncated)
            assertEquals(name, body, String(out.body, Charsets.UTF_8).trimEnd('\n'))
        }
    }
}

/** 主代理复审补充（2026-10-01）：configure 之前 purge 的改名兜底、收编中途目录消失续接、bootstrap 定时重试、标记判定短缓存。 */
class PreReviewFixTests : RtvTest() {
    private fun lines(h: StaticHarness) = sessionLines(h.currentSessionDir())

    /** configure 之前的 purgeLocal：改名失败（root 所在目录只读）→ 逐项删除 root 下的内容，删完才回调。 */
    @Test
    fun purgeBeforeConfigureFallsBackToDeleteInPlace() {
        val parent = tempDir("rtv-ro-parent")
        val root = File(parent, "retriever")
        val h = StaticHarness(root = root)
        h.log(LogLevel.WARN, "to purge")
        File(root, "install.json").writeText("{}")
        File(root, "outbox").mkdirs()
        File(File(root, "outbox"), "p1-1-x.gz").writeText("x")
        assertTrue(parent.setWritable(false, false))
        try {
            assumeFalse("root 用户改得了名，跳过", File(parent, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })
            val done = CountDownLatch(1)
            Retriever.purgeLocal { done.countDown() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue("root 还在（改名失败）", root.isDirectory)
            assertEquals("但里面清空了", emptyList<String>(), root.list()!!.toList())
            assertTrue(parent.list()!!.none { it.startsWith("retriever.purge-") })
        } finally {
            parent.setWritable(true, false)
        }
        h.log(LogLevel.WARN, "after purge")
        h.configure("")
        h.settle()
        assertEquals(listOf("after purge"), lines(h).map { it["msg"] })
        parent.deleteRecursively()
    }

    /**
     * 收编进行到一半 root 被删（A6）：pre 文件提交前完整，新会话建好后从偏移 0 重新收编进新会话——全部 pre 行都在新会话里、
     * 不计数、不丢；提交时 unlink 得「已不在」算成功。
     */
    @Test
    fun rootVanishedMidAdoptionReadoptsFromStart() {
        val h = StaticHarness()
        for (i in 0 until 40) h.log(if (i == 20) LogLevel.FATAL else LogLevel.INFO, "pre $i")
        val fired = java.util.concurrent.atomic.AtomicBoolean()
        org.revdog.retriever.core.TestHooks.adoption = { p -> if (p == "record:11" && fired.compareAndSet(false, true)) h.root.deleteRecursively() }
        h.configure("", Options().apply { uploadLevel = LogLevel.INFO })
        h.settle()
        org.revdog.retriever.core.TestHooks.adoption = null
        val ls = lines(h)
        assertEquals("全部 40 行都在新会话里", (0 until 40).map { "pre $it" }, ls.filter { it["synthetic"] != true }.map { it["msg"] })
        assertEquals(1, ls.count { it["tag"] == "rtv.root_vanished" })
        assertEquals("不计数", 0, ls.count { it["tag"] == DropCounter.TAG })
        assertEquals(0L, DropCounter.pending)
        assertEquals(emptyList<File>(), h.preFiles)
        assertEquals((1L..ls.size.toLong()).toList(), ls.map { int(it["seq"]) })
        assertFalse("meta.pre 已清", JsonIn.obj(File(h.currentSessionDir(), "meta.json").readBytes())!!.containsKey("pre"))
    }

    /** bootstrap 失败后不靠宿主再写一行：调度器 5 s 唤醒一次重试，成功后照常补报计数、startup。 */
    @Test
    fun bootstrapRetriedByTimerWithoutLog() {
        val h = StaticHarness()
        Fs.writeAtomicFaultForTesting = { it.name == "meta.json" }
        h.configure("")
        h.settle()
        assertFalse(h.client.isBootstrapped)
        h.log(LogLevel.ERROR, "lost")
        h.client.onWork { h.client.reschedule() }
        assertTrue("调度器要求了定时唤醒：${h.clock.timerDelays}", h.clock.timerDelays.any { it in 1000..5000 })
        h.clock.advance(5000)
        h.client.tickNow()
        assertFalse("还写不成：继续失败", h.client.isBootstrapped)
        Fs.writeAtomicFaultForTesting = null
        h.clock.advance(5000)
        h.client.tickNow()
        assertTrue(h.client.isBootstrapped)
        val d = obj(lines(h).single { it["tag"] == DropCounter.TAG }["attrs"])
        assertEquals(1L, int(d["count"]))
    }

    /** configure 之前标记判定缓存 1 s（不让每行都列一次目录）；setEnabled 作废缓存。 */
    @Test
    fun preMarkerJudgementCachedOneSecond() {
        val h = StaticHarness()
        val marker = File(h.root.absoluteFile.parentFile, h.root.name + ".disabled")
        try {
            h.log(LogLevel.WARN, "a")
            assertTrue(marker.createNewFile())
            h.log(LogLevel.WARN, "b (cached: not yet seen)")
            h.clock.advance(1000)
            h.log(LogLevel.WARN, "c (marker seen)")
            assertTrue(marker.delete())
            h.log(LogLevel.WARN, "d (still cached as present)")
            Retriever.setEnabled(true)
            h.log(LogLevel.WARN, "e")
            h.configure("")
            h.settle()
            assertEquals(listOf("a", "b (cached: not yet seen)", "e"), lines(h).map { it["msg"] })
        } finally {
            marker.delete()
        }
    }
}
