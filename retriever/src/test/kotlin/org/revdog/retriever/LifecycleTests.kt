package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.PlatformEvent
import org.revdog.retriever.core.RootLocks
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 生命周期与后台兜底（方案 §3.9 Android 列；ADR 0003 决定 13）：进后台封段 + 排空 + 排一次性 JobScheduler 作业；
 * fatal 也排作业；作业里排空出站箱并回报是否需要系统重排；系统停止作业时取消在途请求、不删批。
 */
class LifecycleTests : RtvTest() {
    private fun cursor(h: Harness): Map<String, Any?> = h.readJson(File(h.sessionDir(), "cursor.json"))

    private fun waitUntil(cond: () -> Boolean) {
        var n = 0
        while (!cond() && n < 1000) {
            Thread.sleep(2)
            n++
        }
    }

    @Test
    fun backgroundSealsDrainsAndSchedulesJob() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Network // 离线：批留在出站箱，靠作业兜底
        h.client.log(LogLevel.WARN, "w", null, null, null)
        h.client.platformEvent(PlatformEvent.DID_ENTER_BACKGROUND)
        h.settle()
        assertEquals("bg", cursor(h)["last_state"])
        assertEquals("进后台封段并物化", 1, h.outboxFiles("p1").size)
        assertEquals("排空尝试过", 1, h.transport.batchRequests.size)
        assertEquals("后台时排了作业（默认 id 0x5254）", listOf(0x5254), h.platform.scheduledJobs.toList())
        // 上传锁已释放（另一个进程能拿到）
        val other = RootLocks(h.root)
        assertTrue(other.tryUploadLock())
        other.close()
    }

    @Test
    fun backgroundWithoutObligationDoesNotSealNorSchedule() {
        val h = Harness()
        h.settle()
        h.client.log(LogLevel.INFO, "just info", null, null, null)
        h.client.platformEvent(PlatformEvent.DID_ENTER_BACKGROUND)
        h.settle()
        assertEquals(1, h.client.writer.snapshot.segNo)
        assertEquals(0, h.transport.batchRequests.size)
        assertEquals("出站箱没有待传批：不排作业", 0, h.platform.scheduledJobs.size)
        assertEquals("bg", cursor(h)["last_state"])
    }

    @Test
    fun fatalSchedulesJobWithCustomId() {
        val o = Options().apply { jobId = 4242 }
        val h = Harness(options = o)
        h.settle()
        h.client.log(LogLevel.INFO, "ctx", null, null, null)
        h.client.log(LogLevel.FATAL, "dying", null, null, IllegalStateException("x"))
        // log() 返回时作业已在调用线程上排好（进程随后可能立即死亡）；p0 由引擎线程异步物化（ADR 0020 决定 1）
        assertEquals(listOf(4242), h.platform.scheduledJobs.toList())
        h.settle()
        assertEquals(1, h.outboxFiles("p0").size)
    }

    @Test
    fun backgroundLastStateMeansCleanExitOnNextLaunch() {
        val root = tempDir()
        val a = Harness(root = root, key = "")
        a.settle()
        a.client.log(LogLevel.WARN, "w", null, null, null)
        a.client.platformEvent(PlatformEvent.DID_ENTER_BACKGROUND)
        a.settle()
        val aSid = a.client.writer.currentSessionId
        a.client.simulateCrash()
        val b = Harness(root = root, key = "")
        b.settle()
        val closed = b.readJsonl("sessions.jsonl").first { it["session_id"] == aSid }
        assertEquals("clean_bg", closed["exit"])
        // 后台被杀是常态：不合成 unclean_exit
        assertFalse(b.envelopes().any { e -> e.lines.any { it["synthetic"] == true } })
    }

    @Test
    fun foregroundWritesLastStateAndDrains() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Network
        h.client.log(LogLevel.WARN, "w", null, null, null)
        h.client.platformEvent(PlatformEvent.DID_ENTER_BACKGROUND)
        h.settle()
        assertEquals(1, h.outboxFiles().size)
        h.transport.defaultReply = FakeTransport.Reply.Echo
        h.clock.advance(Limits.BACKOFF_MAX_MS)
        h.client.platformEvent(PlatformEvent.WILL_ENTER_FOREGROUND)
        h.settle()
        assertEquals("fg", cursor(h)["last_state"])
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    @Test
    fun uploadJobDrainsAndReportsReschedule() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Network
        h.client.log(LogLevel.ERROR, "e", null, null, null)
        h.seal()
        // 作业 1：离线 → 批还在、要求系统按退避重排
        val r1 = runJob(h)
        assertEquals(true, r1)
        assertEquals(1, h.outboxFiles().size)
        // 作业 2：有网 → 排空、不再重排
        h.transport.defaultReply = FakeTransport.Reply.Echo
        h.clock.advance(Limits.BACKOFF_MAX_MS)
        assertEquals(false, runJob(h))
        assertEquals(emptyList<String>(), h.outboxFiles())
        // 作业 3：没有 key（宿主没 configure）→ 什么都不做、不重排
        val h2 = Harness(key = "")
        h2.settle()
        h2.client.log(LogLevel.WARN, "w", null, null, null)
        h2.seal()
        assertEquals(false, runJob(h2))
        assertEquals(1, h2.outboxFiles().size)
    }

    @Test
    fun stoppedJobCancelsInFlightKeepsBatch() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Hang
        h.client.log(LogLevel.WARN, "w", null, null, null)
        h.seal()
        val done = CountDownLatch(1)
        val resched = AtomicReference<Boolean>()
        h.client.runUploadJob(60_000) {
            resched.set(it)
            done.countDown()
        }
        waitUntil { h.transport.hangingCount == 1 }
        assertEquals("排空已在途", 1, h.transport.hangingCount)
        // 系统回调 onStopJob
        h.client.stopUploadJob()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(h.transport.cancelCount >= 1)
        assertEquals("被停的作业要求重排", true, resched.get())
        h.settle()
        assertEquals("取消的请求不删批", 1, h.outboxFiles().size)
        val other = RootLocks(h.root)
        assertTrue("上传锁已释放", other.tryUploadLock())
        other.close()
        // 回前台：重新排空
        h.transport.defaultReply = FakeTransport.Reply.Echo
        h.clock.advance(Limits.BACKOFF_MAX_MS)
        h.client.platformEvent(PlatformEvent.WILL_ENTER_FOREGROUND)
        h.settle()
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    @Test
    fun backgroundDrainsSeveralBatchesAcrossSpacing() {
        val h = Harness(key = "")
        h.settle()
        for (i in 0 until 3) {
            h.client.log(LogLevel.WARN, "w$i", null, null, null)
            h.seal()
        }
        h.client.log(LogLevel.WARN, "in open segment", null, null, null)
        h.work { it.key = "lk_test_demo_abc_12345678" }
        h.client.platformEvent(PlatformEvent.DID_ENTER_BACKGROUND)
        waitUntil { h.outboxFiles().isEmpty() && h.transport.batchRequests.size == 4 }
        h.settle()
        assertEquals("3 个旧批 + 进后台封出的 1 批，间隔 2 s 就地等待", 4, h.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
    }

    private fun runJob(h: Harness): Boolean {
        val done = CountDownLatch(1)
        val r = AtomicReference<Boolean>()
        h.client.runUploadJob(60_000) {
            r.set(it)
            done.countDown()
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        h.settle()
        return r.get()
    }
}
