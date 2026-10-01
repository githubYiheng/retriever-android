package org.revdog.retriever

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.FakeTransport.Reply
import org.revdog.retriever.android.AndroidPlatform
import org.revdog.retriever.android.JobApi
import org.revdog.retriever.android.JobGate
import org.revdog.retriever.android.LifecycleTracker
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.PlatformEvent
import org.revdog.retriever.core.PlatformEventSink
import org.revdog.retriever.core.RootLocks
import org.revdog.retriever.core.SealReason
import org.revdog.retriever.core.batchMeta
import org.revdog.retriever.core.split413
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 宿主误用加固（ADR 0024 决定 3 / 4 / 7–11；简报 §4.4–4.6、§7、§8、§9；§11 N / P / Q / R / S / T）。 */
class HardeningTests : RtvTest() {
    private fun Harness.l(level: LogLevel, msg: String, attrs: Map<String, Any?>? = null) = client.log(level, msg, null, attrs, null)

    private fun waitUntil(cond: () -> Boolean) {
        var n = 0
        while (!cond() && n < 2500) {
            Thread.sleep(2)
            n++
        }
    }

    private fun cursor(h: Harness): Map<String, Any?> = h.readJson(File(h.sessionDir(), "cursor.json"))

    // ---------------------------------------------------------------- N

    /** 段读失败（非 ENOENT）不推进游标、不记墓碑，定时重试；段文件确已不存在 → 记墓碑后推进。 */
    @Test
    fun n_readFailureVsMissingSegment() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "a")
        h.l(LogLevel.WARN, "b")
        val seg = h.client.debugOpenSegmentFile!!
        val content = seg.readBytes()
        h.client.writer.debugBreakStream()
        assertTrue(seg.delete() && seg.mkdir())
        h.client.writer.rotate(SealReason.TIMER)
        h.client.onWork { h.engine.processSeals() }
        assertEquals("读失败不推进", 0L, int(cursor(h)["extracted_through_oseq"]))
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals(emptyList<Map<String, Any?>>(), h.readJsonl("drops.jsonl"))
        val sealed = File(h.sessionDir(), "seg-000001.sealed")
        assertTrue(sealed.isDirectory && sealed.delete())
        sealed.writeBytes(content)
        h.tick(60_000)
        assertEquals(listOf("a", "b"), h.envelopes().single().lines.map { it["msg"] })
        assertEquals(2L, int(cursor(h)["extracted_through_oseq"]))

        // 段文件被删：墓碑后推进
        h.l(LogLevel.WARN, "c")
        val seg2 = h.client.debugOpenSegmentFile!!
        h.client.writer.debugBreakStream()
        assertTrue(seg2.delete())
        h.client.writer.rotate(SealReason.TIMER)
        h.client.onWork { h.engine.processSeals() }
        h.settle()
        val d = h.readJsonl("drops.jsonl").single()
        assertEquals("corrupt", d["reason"])
        assertEquals(3L, int(d["oseq_from"]))
        assertEquals(3L, int(d["oseq_to"]))
        assertEquals(3L, int(cursor(h)["extracted_through_oseq"]))
    }

    /** root 被删：换段时发现 → 引擎线程重新 bootstrap，新会话写 `rtv.root_vanished`；期间的行计数并上报。 */
    @Test
    fun n_rootVanishedRebootstraps() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "before")
        val oldSid = h.client.writer.currentSessionId
        val gate = h.blockEngine()
        h.root.deleteRecursively()
        h.client.writer.rotate(SealReason.TIMER)
        h.l(LogLevel.ERROR, "lost while vanished")
        gate.countDown()
        h.settle()
        val sid = h.client.writer.currentSessionId
        assertTrue(sid != oldSid)
        val ls = sessionLines(h.sessionDir())
        assertEquals(listOf("rtv.root_vanished", "rtv.pre_init_dropped"), ls.map { it["tag"] })
        assertEquals("session directory vanished; new session started", ls[0]["msg"])
        // 旧会话 .open 段里的 1 行（写进了被删的 inode，A5 按段计）+ 目录消失期间的 1 行
        assertEquals(2L, int(obj(ls[1]["attrs"])["count"]))
        assertEquals(1L, int(obj(ls[1]["attrs"])["error_count"]))
        h.l(LogLevel.WARN, "after")
        assertEquals("after", sessionLines(h.sessionDir()).last()["msg"])
    }

    /** 目录锁拿不到（upload.lock 打不开）：不执行临界区。 */
    @Test
    fun n_dirLockUnavailableSkipsCriticalSection() {
        val root = tempDir()
        assertTrue(File(root, "upload.lock").mkdir())
        var ran = false
        val r = RootLocks(root).withDirLock { ran = true }
        assertNull(r)
        assertFalse(ran)
    }

    // ---------------------------------------------------------------- P

    /** 换 key：鉴权暂停清除、立即可传；映射重发。 */
    @Test
    fun p_keyChangeClearsPauseAndResendsMapping() {
        val h = Harness(key = "lk_test_a_old_00000001")
        h.settle()
        h.l(LogLevel.WARN, "w1")
        h.sealAndDrain()
        assertNotNull("首批带映射", FakeTransport.envOf(h.transport.batchRequests.last().body)!!["mapping"])
        h.transport.setScript(listOf(Reply.Status(401, mapOf("reason" to "key_revoked"))))
        h.clock.advance(2000)
        h.l(LogLevel.WARN, "w2")
        h.sealAndDrain()
        assertEquals(listOf("all"), h.backoff.pausedCategories)
        h.client.reconfigure("lk_test_b_new_00000002", Harness.BASE, Options(), h.client.processName)
        h.settle()
        assertEquals("换 key 清暂停", emptyList<String>(), h.backoff.pausedCategories)
        h.tick(2000)
        assertEquals(emptyList<String>(), h.outboxFiles())
        val last = h.transport.batchRequests.last()
        assertEquals("Bearer lk_test_b_new_00000002", last.headers["Authorization"])
        h.l(LogLevel.WARN, "w3")
        h.clock.advance(2000)
        h.sealAndDrain()
        assertNotNull("映射对新 key 重发", FakeTransport.envOf(h.transport.batchRequests.last().body)!!["mapping"])
        assertEquals(Ids.keyFingerprint("lk_test_b_new_00000002"), h.readJson(File(h.root, "backoff.json"))["key_fp"])
    }

    /** 旧 key 在途请求的 401 不暂停新 key。 */
    @Test
    fun p_inflight401ForOldKeyDoesNotPause() {
        val h = Harness(key = "lk_test_a_old_00000001")
        h.settle()
        val gate = CountDownLatch(1)
        h.transport.setScript(listOf(Reply.Gated(gate, Reply.Status(401, mapOf("reason" to "key_revoked")))))
        h.l(LogLevel.WARN, "w")
        h.client.writer.rotate(SealReason.TIMER)
        h.client.onWork { h.client.afterSeal() }
        waitUntil { h.transport.batchRequests.isNotEmpty() }
        h.client.reconfigure("lk_test_b_new_00000002", Harness.BASE, Options(), h.client.processName)
        h.client.onWork { }
        gate.countDown()
        h.settle()
        assertEquals(emptyList<String>(), h.backoff.pausedCategories)
        h.tick(2000)
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals("Bearer lk_test_b_new_00000002", h.transport.batchRequests.last().headers["Authorization"])
    }

    // ---------------------------------------------------------------- Q

    /** fatal 连打 100 条：1 次立即换段、1 次排作业，其余并入 error 去抖；10 s 窗口过后再立即换段。 */
    @Test
    fun q_fatalBurstThrottled() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Network
        repeat(100) { h.l(LogLevel.FATAL, "f$it") }
        h.settle()
        assertEquals(1, h.outboxFiles("p0").size)
        assertEquals(1, h.platform.scheduledJobs.size)
        assertEquals(2, h.client.writer.snapshot.segNo)
        h.tick(10_000)
        assertEquals("其余 99 条随 error 去抖封段", 2, h.outboxFiles("p0").size)
        assertEquals(100, h.envelopes("p0").sumOf { e -> e.lines.count { it["ctx"] != true } })
        h.l(LogLevel.FATAL, "next window")
        h.settle()
        assertEquals(3, h.outboxFiles("p0").size)
        assertEquals(2, h.platform.scheduledJobs.size)
    }

    /** flush 连调 100 次（第一次还在等）：1 条标记行，100 个回调都回。 */
    @Test
    fun q_flushBurstCoalesced() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Hang
        h.client.setFlushWindowForTesting(500)
        h.l(LogLevel.INFO, "before")
        val got = AtomicInteger()
        val done = CountDownLatch(100)
        repeat(100) {
            h.client.flush(true) {
                got.incrementAndGet()
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals(100, got.get())
        h.transport.defaultReply = Reply.Network
        h.transport.cancelAll()
        h.settle()
        val markers = (h.sessionDir().list() ?: emptyArray()).filter { it.startsWith("seg-") }
            .sumOf { n -> File(h.sessionDir(), n).readText().split("\n").count { it.contains("\"tag\":\"rtv.flush\"") } }
        assertEquals(1, markers)
    }

    // ---------------------------------------------------------------- R

    /** attrs 键名 ctx / level / synthetic / tag 的值「像」标记：物化优先级、413 切分、恢复判重都不受影响（按位置解析）。 */
    @Test
    fun r_attrsDoNotSpoofParsedFields() {
        val spoof = linkedMapOf<String, Any?>("a" to 1, "ctx" to true, "level" to "error", "synthetic" to true, "tag" to "rtv.unclean_exit")
        // 批的级别 / ctx：按行首前缀与行尾位置
        val raw = listOf(
            "{\"seq\":1,\"oseq\":1,\"ts\":1,\"level\":\"debug\",\"msg\":\"m\",\"attrs\":{\"a\":1,\"ctx\":true,\"level\":\"error\"}}",
        ).map { it.toByteArray() }
        val h0 = org.revdog.retriever.core.EnvelopeHeader(
            Ids.BatchKind.PRIMARY, Ids.newV4(), 1, "2026-10-01", Ids.newV4(), Ids.newV4(), 1, "main", null,
            org.revdog.retriever.core.Device("a", "b", "c", "d", "e", "f", "g"), 1, 1, 1, 1, null,
        )
        val m = batchMeta("p1-1-${h0.batchId}.gz", h0, raw, 10)
        assertFalse("attrs 的 level 不算", m.hasError)
        assertFalse(m.hasWarnOrAbove)
        assertEquals("info", m.category)

        // 413 切分：两条义务行（attrs 里有 ,"ctx":true）照常二分
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Network
        h.l(LogLevel.WARN, "w1", spoof)
        h.l(LogLevel.WARN, "w2", spoof)
        h.seal()
        val name = h.outboxFiles().single()
        assertTrue(h.work { it.split413(name) })
        val halves = h.envelopes().sortedBy { int(it["oseq_from"]) }
        assertEquals(listOf(Pair(1L, 1L), Pair(2L, 2L)), halves.map { Pair(int(it["oseq_from"]), int(it["oseq_to"])) })
        assertEquals(listOf("w1", "w2"), halves.map { it.lines.single()["msg"] })

        // 恢复判重：最后一行 attrs 里有 "tag":"rtv.unclean_exit" 不算已合成
        val k = Harness(key = "")
        k.settle()
        k.l(LogLevel.WARN, "last", spoof)
        val sid = k.client.writer.currentSessionId
        k.client.simulateCrash()
        val k2 = Harness(root = k.root, key = "", clock = k.clock)
        k2.settle()
        val ls = sessionLines(File(File(k.root, "proc-main"), sid))
        assertEquals(listOf("last", "process ended while foregrounded"), ls.map { it["msg"] })
    }

    // ---------------------------------------------------------------- S

    /** redact 改 ts（负数、> 2^53）：行照常、ts 为原值（configure 之后与收编两条路径）。 */
    @Test
    fun s_redactCannotChangeTs() {
        val h = Harness(key = "", options = Options().apply { redact = { l -> l.copy(ts = if ("neg" in l.msg) -5 else Long.MAX_VALUE) } })
        h.l(LogLevel.WARN, "neg")
        h.l(LogLevel.WARN, "huge")
        val ls = sessionLines(h.sessionDir())
        assertEquals(listOf(h.clock.wallMs(), h.clock.wallMs()), ls.map { int(it["ts"]) })

        val s = StaticHarness()
        val t0 = s.clock.wallMs()
        s.log(LogLevel.WARN, "neg pre")
        s.configure("", Options().apply { redact = { l -> l.copy(ts = -1) } })
        s.settle()
        assertEquals(t0, int(sessionLines(s.currentSessionDir()).single()["ts"]))
    }

    /** setUser("") / 纯空白 / 纯控制字符 = nil（configure 之前与之后）。 */
    @Test
    fun s_blankUserIsNil() {
        val s = StaticHarness()
        Retriever.setUser("  \t ")
        Retriever.setUser("u")
        Retriever.setUser("\u0000\u0001")
        s.log(LogLevel.WARN, "x")
        s.configure("")
        s.settle()
        assertNull(s.client.writer.currentUser)
        Retriever.setUser("u2")
        Retriever.setUser("")
        assertNull(s.client.writer.currentUser)
        assertEquals(null, org.revdog.retriever.core.Text.sanitizeUserId(" "))
        assertEquals("a b", org.revdog.retriever.core.Text.sanitizeUserId("a b"))
    }

    /** SDK 自己取消的请求（作业被系统停）不计入毒批失败次数。 */
    @Test
    fun s_sdkCancelledRequestNotCountedAsFailure() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = Reply.Hang
        h.l(LogLevel.WARN, "w")
        h.seal()
        val name = h.outboxFiles().single()
        val done = CountDownLatch(1)
        h.client.runUploadJob(60_000) { done.countDown() }
        waitUntil { h.transport.hangingCount == 1 }
        h.client.stopUploadJob()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        h.settle()
        assertNull(h.work { it.fails[name] })
        assertEquals("也不动退避（B8）", 0, h.backoff.attempt)
        assertEquals(0L, h.backoff.nextAtMonoMs)
        assertEquals(listOf(name), h.outboxFiles())
    }

    // ---------------------------------------------------------------- T

    private class FakeJobs(var pending: String?) : JobApi {
        val scheduled = CopyOnWriteArrayList<Boolean>()
        val cancelled = CopyOnWriteArrayList<Int>()
        var persistThrows = false

        override fun pendingService(id: Int): String? = pending

        override fun schedule(id: Int, persisted: Boolean): Boolean {
            if (persisted && persistThrows) throw IllegalArgumentException("requires RECEIVE_BOOT_COMPLETED")
            scheduled.add(persisted)
            return true
        }

        override fun cancel(id: Int) {
            cancelled.add(id)
        }
    }

    /** 同 id 的宿主作业存在时不被取消 / 替换；缺 RECEIVE_BOOT_COMPLETED 时退回非持久作业。 */
    @Test
    fun t_jobOwnership() {
        val ours = RetrieverUploadJobService::class.java.name
        val host = FakeJobs("com.host.SyncJobService")
        val g = JobGate(host, ours)
        assertFalse(g.schedule(0x5254))
        g.cancel(0x5254)
        assertEquals(emptyList<Boolean>(), host.scheduled.toList())
        assertEquals(emptyList<Int>(), host.cancelled.toList())

        val mine = FakeJobs(ours)
        val g2 = JobGate(mine, ours)
        assertTrue("自己的作业已排：不重排", g2.schedule(1))
        assertEquals(emptyList<Boolean>(), mine.scheduled.toList())
        g2.cancel(1)
        assertEquals(listOf(1), mine.cancelled.toList())

        val none = FakeJobs(null).apply { persistThrows = true }
        assertTrue(JobGate(none, ours).schedule(2))
        assertEquals("退回非持久", listOf(false), none.scheduled.toList())

        // 实例：同 id 被别人占着 → 不替换，只记一次诊断
        val h = Harness()
        h.settle()
        h.platform.scheduleResult = false
        h.l(LogLevel.FATAL, "f1")
        h.clock.advance(20_000)
        h.l(LogLevel.FATAL, "f2")
        h.settle()
        assertEquals(1, h.errors.count { it.message?.contains("not scheduled") == true })
        h.errors.clear()
    }

    /** 前后台：Activity 已 onStart 之后才 configure → 前台；A → B 切换不误报后台；没见过 onStart 的 Activity 的 onStop 不参与。 */
    @Test
    fun t_lifecycleTracker() {
        val tracker = LifecycleTracker()
        tracker.install(Application(), true) { null }
        assertEquals(false, tracker.current())
        val a = Activity()
        val b = Activity()
        tracker.onStarted(a)
        // 之后才 configure：实例从 tracker 取当前状态
        assertTrue(AndroidPlatform(ContextWrapper(null), tracker).isForeground())
        val events = CopyOnWriteArrayList<PlatformEvent>()
        tracker.subscribe(object : PlatformEventSink {
            override fun platformEvent(event: PlatformEvent) {
                events.add(event)
            }
        })
        tracker.onStarted(b)
        tracker.onStopped(a)
        assertEquals("A → B 不误报后台", emptyList<PlatformEvent>(), events.toList())
        tracker.onStopped(b)
        assertEquals(listOf(PlatformEvent.DID_ENTER_BACKGROUND), events.toList())

        // tracker 注册晚于某个 Activity 的 onStart（provider 被移除）：那个 Activity 的 onStop 不参与计数
        val late = LifecycleTracker()
        late.install(Application(), false) { true }
        val seen = CopyOnWriteArrayList<PlatformEvent>()
        late.subscribe(object : PlatformEventSink {
            override fun platformEvent(event: PlatformEvent) {
                seen.add(event)
            }
        })
        val c = Activity()
        val d = Activity()
        late.onStarted(d)
        late.onStopped(c)
        assertEquals(emptyList<PlatformEvent>(), seen.toList())
        assertEquals(true, late.current())
        late.onStopped(d)
        assertEquals(listOf(PlatformEvent.DID_ENTER_BACKGROUND), seen.toList())
        assertTrue(JsonIn.obj("{}") != null)
    }
}
