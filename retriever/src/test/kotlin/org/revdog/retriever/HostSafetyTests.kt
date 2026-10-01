package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.PlatformEvent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * 宿主安全（ADR 0020；底稿 `docs/audit/2026-09-30-fix-batch/blind-sdk-host.md` §1 / §2 / §7）：
 * 宿主线程永不等待 SDK 后台线程；`setEnabled` 落盘、禁用即零联网；后台作业的停止标志只对当次作业生效。
 */
class HostSafetyTests : RtvTest() {
    private fun Harness.l(level: LogLevel, msg: String) = client.log(level, msg, null, null, null)

    private fun waitUntil(cond: () -> Boolean) {
        var n = 0
        while (!cond() && n < 2500) {
            Thread.sleep(2)
            n++
        }
    }

    /** 在另一个线程上跑宿主调用；返回它是否在 [ms] 内返回、以及耗时（纳秒）。 */
    private fun callWithin(ms: Long, body: () -> Unit): Pair<Boolean, Long> {
        val done = CountDownLatch(1)
        val took = AtomicLong()
        Thread {
            val t0 = System.nanoTime()
            body()
            took.set(System.nanoTime() - t0)
            done.countDown()
        }.start()
        return Pair(done.await(ms, TimeUnit.MILLISECONDS), took.get())
    }

    // ---------------------------------------------------------------- 决定 1：宿主线程永不等待

    /** 引擎线程被堵住（冷启动恢复、大批物化）时 fatal 立即返回；开闸后出站箱有 p0 批、段已封。旧实现在调用线程上无超时地等。 */
    @Test
    fun fatalReturnsWhileEngineBusy() {
        val h = Harness()
        h.settle()
        h.l(LogLevel.INFO, "warm up")
        val gate = h.blockEngine()
        val (returned, took) = callWithin(1000) { h.l(LogLevel.FATAL, "dying") }
        val before = h.outboxFiles("p0")
        gate.countDown()
        assertTrue("fatal 不等引擎线程", returned)
        assertTrue("took ${took / 1_000_000} ms", took < 50_000_000)
        assertEquals("开闸前还没物化", emptyList<String>(), before)
        h.settle()
        assertEquals(1, h.outboxFiles("p0").size)
        assertTrue(File(h.sessionDir(), "seg-000001.sealed").exists())
        assertEquals("fatal 只落盘不尝试上传", 0, h.transport.batchRequests.size)
    }

    /** fatal 在调用线程上直接排后台作业（引擎堵住也排）；key 为空、已禁用（本进程或别的进程的标记）时不排。 */
    @Test
    fun fatalArmsJobOnCallerThread() {
        val h = Harness(options = Options().apply { jobId = 4242 })
        h.settle()
        val gate = h.blockEngine()
        val caller = AtomicReference<String>()
        val (returned, _) = callWithin(1000) {
            caller.set(Thread.currentThread().name)
            h.l(LogLevel.FATAL, "dying")
        }
        val jobs = h.platform.scheduledJobs.toList()
        gate.countDown()
        assertTrue(returned)
        assertEquals(listOf(4242), jobs)
        assertEquals("在调用线程上排", listOf(caller.get()), h.platform.scheduleThreads.toList())
        h.settle()

        val noKey = Harness(key = "")
        noKey.settle()
        noKey.l(LogLevel.FATAL, "dying")
        noKey.settle()
        assertEquals(emptyList<Int>(), noKey.platform.scheduledJobs.toList())

        val disabled = Harness()
        disabled.settle()
        disabled.client.setEnabled(false)
        disabled.l(LogLevel.FATAL, "dying")
        disabled.settle()
        assertEquals(emptyList<Int>(), disabled.platform.scheduledJobs.toList())

        // 别的进程禁用了（标记在、本进程内存开关还开着）：行照写，不排作业
        val other = Harness()
        other.settle()
        assertTrue(other.disabledMarker.createNewFile())
        other.l(LogLevel.FATAL, "dying")
        other.settle()
        assertEquals(1L, other.client.debugCounters.first)
        assertEquals(emptyList<Int>(), other.platform.scheduledJobs.toList())
    }

    /**
     * purgeLocal 立即返回（引擎堵住也不等）；回调里 installId 已是新值、root 已清；排在前面的排空不发要清掉的批。
     * 旧实现在调用线程上等引擎线程做完删除与重建。
     */
    @Test
    fun purgeReturnsImmediately() {
        val h = Harness()
        h.settle()
        h.l(LogLevel.WARN, "to be purged")
        h.seal()
        assertEquals(1, h.outboxFiles().size)
        val old = h.client.installId
        val gate = h.blockEngine()
        h.client.kickDrain() // 排空排在清空前面：它的选批卡在引擎线程上
        Thread.sleep(50)
        val inCallback = AtomicReference<String?>()
        val done = CountDownLatch(1)
        val (returned, took) = callWithin(1000) {
            h.client.purgeLocal {
                inCallback.set(h.client.installId)
                done.countDown()
            }
        }
        assertEquals("返回时还是旧值", old, h.client.installId)
        gate.countDown()
        assertTrue("purgeLocal 不等引擎线程", returned)
        assertTrue("took ${took / 1_000_000} ms", took < 50_000_000)
        assertTrue(done.await(10, TimeUnit.SECONDS))
        h.settle()
        assertNotEquals(old, inCallback.get())
        assertEquals(h.client.installId, inCallback.get())
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals("要清掉的批一个都没发", 0, h.transport.batchRequests.size)
    }

    /**
     * 清空进行中宿主照常 log()：新 root 建好、写入切到新会话之后才删旧 root——切换之前的行随旧 root 删掉（设计如此），
     * 同一会话内每一行都占 seq（log() 不空返回），切换之后的行全部在新会话的段里；不留 `.purge-*`。
     * 旧实现先 abandonSession、删旧 root，最后才建新会话，其间 log() 空返回、行无痕消失。
     */
    @Test
    fun logDuringPurgeIsNotSilentlyDropped() {
        val h = Harness()
        h.settle()
        h.l(LogLevel.WARN, "before purge")
        val old = h.client.installId
        val w = h.client.writer
        val gate = h.blockEngine()
        val done = CountDownLatch(1)
        h.client.purgeLocal { done.countDown() }
        gate.countDown()
        var n = 0
        var dropped = 0
        // 至少 50 行，一直写到清空回调到达（行间稍停，让清空的每一步都有行落在其间）
        while (n < 50 || (done.count > 0 && n < 3000)) {
            val sid0 = w.currentSessionId
            val seq0 = w.snapshot.seq
            val sid1 = w.currentSessionId
            h.l(LogLevel.WARN, "during $n")
            val sid2 = w.currentSessionId
            val seq1 = w.snapshot.seq
            val sid3 = w.currentSessionId
            // 前后都在同一会话（读计数时没碰上切换）：这一行必须占一个 seq
            if (sid0 == sid1 && sid1 == sid2 && sid2 == sid3 && seq1 != seq0 + 1) dropped += 1
            n += 1
            LockSupport.parkNanos(100_000)
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        h.l(LogLevel.WARN, "after purge")
        h.settle()
        assertEquals("同一会话内每行都占 seq（没有空返回）", 0, dropped)
        assertNotEquals(old, h.client.installId)
        val seg = h.client.debugOpenSegmentFile!!
        assertEquals("新会话的段在新 root 里", h.sessionDir(), seg.parentFile)
        val msgs = seg.readText().split("\n").drop(1).filter { it.isNotEmpty() }.map { JsonIn.obj(it)!!["msg"] as String }
        assertEquals("新段的行数 == 新会话的 seq 计数", h.client.debugCounters.first, msgs.size.toLong())
        // 新段 = 切换之后的连续尾巴：during k … during n-1、after purge
        val k = n - (msgs.size - 1)
        assertEquals((k until n).map { "during $it" } + "after purge", msgs)
        assertTrue(h.root.absoluteFile.parentFile!!.list()!!.none { it.startsWith(h.root.name + ".purge-") })
    }

    /**
     * 两次 purgeLocal 重叠：第一次做完、第二次还排着时，排空在取批前停在 purging、拉配置被拒；两个回调都到，
     * 两次清空之后只拉一次配置（新身份）。旧实现「清空中」是布尔值，第一次做完就清掉，第二次还没做时排空与拉配置照常放行。
     */
    @Test
    fun overlappingPurgesHoldNetworkUntilLast() {
        val h = Harness()
        h.settle()
        h.l(LogLevel.WARN, "to be purged")
        h.seal()
        assertEquals(1, h.outboxFiles().size)
        val c0 = h.transport.configRequests.size
        val old = h.client.installId
        val gateA = h.blockEngine()
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        h.client.purgeLocal { first.countDown() }
        // 第二次清空排在另一道闸后面：第一次做完之后它还没开始
        val gateB = CountDownLatch(1)
        h.client.workAsync { gateB.await(30, TimeUnit.SECONDS) }
        h.client.purgeLocal { second.countDown() }
        gateA.countDown()
        assertTrue(first.await(10, TimeUnit.SECONDS))
        h.client.kickDrain()
        h.client.fetchConfig()
        waitUntil { h.client.lastStop.first == "purging" }
        assertEquals("第二次清空还没做：排空停在 purging", "purging", h.client.lastStop.first)
        gateB.countDown()
        assertTrue(second.await(10, TimeUnit.SECONDS))
        h.settle()
        assertNotEquals(old, h.client.installId)
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals("要清掉的批一个都没发", 0, h.transport.batchRequests.size)
        assertEquals("两次清空之后只拉一次配置", c0 + 1, h.transport.configRequests.size)
        assertEquals(h.client.installId, h.transport.configRequests.last().headers["X-Rtv-Install"])
    }

    // ---------------------------------------------------------------- 决定 2：setEnabled 落盘

    /** 禁用跨重启有效：重建实例后 log() 不占 seq，启动之后 0 次上传、0 次拉配置（出站箱预置了批）。旧实现重启回到启用。 */
    @Test
    fun disabledPersistsAcrossRestart() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "queued before consent withdrawn")
        h.seal()
        h.client.setEnabled(false)
        h.settle()
        assertTrue("标记在 root 同级", h.disabledMarker.exists())
        assertFalse(File(h.root, "disabled").exists())
        h.client.simulateCrash()

        val h2 = Harness(root = h.root, clock = h.clock)
        assertFalse(h2.client.isEnabled)
        h2.l(LogLevel.ERROR, "not written")
        assertEquals(0L, h2.client.debugCounters.first)
        h2.settle()
        h2.tick(Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        h2.client.platformEvent(PlatformEvent.WILL_ENTER_FOREGROUND)
        h2.settle()
        assertEquals(0, h2.transport.batchRequests.size)
        assertEquals(0, h2.transport.configRequests.size)
        // 只有预置的那一批留在本地：禁用期间恢复不合成 unclean_exit（合成行也是写入，ADR 0020 决定 2），旧会话零行直接删
        val queued = h2.outboxFiles().size
        assertEquals(1, queued)
        // 重新同意：删标记、排空、拉配置
        h2.client.setEnabled(true)
        h2.settle()
        assertFalse(h2.disabledMarker.exists())
        assertTrue("重新启用后拉配置", h2.transport.configRequests.isNotEmpty())
        h2.tick(2000)
        assertEquals(queued, h2.transport.batchRequests.size)
        assertEquals(emptyList<String>(), h2.outboxFiles())
    }

    /** 禁用期间不拉配置：推进 31 min 再发回前台事件，拉配置 0 次。 */
    @Test
    fun disabledStopsConfigPoll() {
        val h = Harness()
        h.settle()
        val n0 = h.transport.configRequests.size
        h.client.setEnabled(false)
        h.settle()
        h.tick(31L * 60 * 1000)
        h.client.platformEvent(PlatformEvent.WILL_ENTER_FOREGROUND)
        h.settle()
        h.tick(31L * 60 * 1000)
        assertEquals(n0, h.transport.configRequests.size)
    }

    /** 禁用状态下清空：新 install_id；0 次请求；标记仍在（它在 root 外面）。 */
    @Test
    fun purgeWhileDisabled() {
        val h = Harness()
        h.settle()
        val n0 = h.transport.configRequests.size
        h.client.setEnabled(false)
        h.settle()
        val old = h.client.installId
        h.purgeBlocking()
        assertNotEquals(old, h.client.installId)
        assertTrue(h.disabledMarker.exists())
        assertFalse(h.client.isEnabled)
        assertEquals(n0, h.transport.configRequests.size)
        assertEquals(0, h.transport.batchRequests.size)
    }

    /** configure 之前的 setEnabled：暂存，建实例时作为初值（写入立即按它生效）并落盘。 */
    @Test
    fun pendingEnabledAppliedAtConstruction() {
        val h = Harness(key = "", initialEnabled = false)
        assertFalse(h.client.isEnabled)
        h.l(LogLevel.ERROR, "not written")
        assertEquals(0L, h.client.debugCounters.first)
        h.settle()
        assertTrue(h.disabledMarker.exists())
        // 暂存值为 true 时删掉旧标记
        h.client.simulateCrash()
        val h2 = Harness(root = h.root, key = "", clock = h.clock, initialEnabled = true)
        assertTrue(h2.client.isEnabled)
        h2.settle()
        assertFalse(h2.disabledMarker.exists())
        // 静态入口：还没有实例（本测试进程里没有 context）时暂存
        Retriever.setEnabled(false)
        assertFalse(Retriever.isEnabled)
        Retriever.setEnabled(true)
        assertTrue(Retriever.isEnabled)
    }

    /**
     * setEnabled(false) 之后零联网，不等引擎线程把开关同步过去：落盘那一跳之前已排进引擎的选批 / 拉配置也看宿主刚设的开关。
     * 旧实现 uploadAllowed() 读引擎线程上的镜像，排在落盘之前的选批与拉配置照发。
     */
    @Test
    fun disableTakesEffectBeforeEngineCatchesUp() {
        val h = Harness(key = "")
        h.settle()
        h.l(LogLevel.WARN, "queued")
        h.seal()
        assertEquals(1, h.outboxFiles().size)
        val gate = h.blockEngine()
        // 开上传，并让排空的选批、拉配置的请求构造都排进引擎队列（在 setEnabled 的落盘之前）
        h.client.reconfigure("lk_test_demo_abc_12345678", Harness.BASE, Options(), h.client.processName)
        h.client.kickDrain()
        h.client.fetchConfig()
        Thread.sleep(50)
        h.client.setEnabled(false)
        gate.countDown()
        h.settle()
        assertEquals(0, h.transport.batchRequests.size)
        assertEquals(0, h.transport.configRequests.size)
        assertEquals(1, h.outboxFiles().size)
        assertTrue(h.disabledMarker.exists())
    }

    /** setEnabled(false) 取消排着的后台作业；禁用时进后台不排作业。 */
    @Test
    fun disableCancelsJob() {
        val h = Harness(options = Options().apply { jobId = 77 })
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Network
        h.l(LogLevel.WARN, "pending")
        h.sealAndDrain()
        assertEquals(1, h.outboxFiles().size)
        h.client.setEnabled(false)
        h.settle()
        assertEquals(listOf(77), h.platform.cancelledJobs.toList())
        h.client.platformEvent(PlatformEvent.DID_ENTER_BACKGROUND)
        h.settle()
        assertEquals(emptyList<Int>(), h.platform.scheduledJobs.toList())
    }

    /** 标记写失败（父目录不可写）：内存照样禁用，调度 tick 重试直到写成。 */
    @Test
    fun markerWriteRetry() {
        val parent = tempDir("rtv-parent")
        try {
            val h = Harness(root = File(parent, "retriever"), key = "")
            h.settle()
            assertTrue(parent.setWritable(false))
            try {
                assumeFalse("root 用户写得进去，跳过", File(parent, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })
                h.client.setEnabled(false)
                h.settle()
                assertFalse(h.client.isEnabled)
                assertFalse(h.disabledMarker.exists())
                assertTrue(h.work { it.markerPending })
                h.l(LogLevel.WARN, "not written")
                assertEquals(0L, h.client.debugCounters.first)
            } finally {
                parent.setWritable(true)
            }
            h.tick()
            assertTrue(h.disabledMarker.exists())
            assertFalse(h.work { it.markerPending })
        } finally {
            Harness.closeAll()
            parent.deleteRecursively()
        }
    }

    /** 禁用状态下启动 20 次：会话都是零行，目录直接删、不写终态，sessions.jsonl 行数不增（ADR 0019 决定 1）。 */
    @Test
    fun emptySessionsNotRecorded() {
        val root = tempDir()
        val first = Harness(root = root, key = "")
        first.settle()
        first.client.setEnabled(false)
        first.settle()
        first.client.simulateCrash()
        var last: Harness = first
        repeat(20) {
            val h = Harness(root = root, key = "", clock = first.clock)
            h.settle()
            h.client.simulateCrash()
            last = h
        }
        assertEquals(0, last.readJsonl("sessions.jsonl").size)
        assertEquals(listOf(last.client.writer.currentSessionId), File(root, "proc-main").list()!!.toList())
    }

    // ---------------------------------------------------------------- 决定 6：作业停止标志只对当次作业生效

    /**
     * 系统停止作业后，之后的排空（封段触发，不经前后台切换）照常上传；迟到的 onStopJob（作业已结束）不挡任何排空。
     * 旧实现停止标志常驻，同进程的排空一直被挡到下一次前后台切换。
     */
    @Test
    fun stoppedJobDoesNotBlockLaterDrains() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Hang
        h.l(LogLevel.WARN, "w1")
        h.seal()
        val done = CountDownLatch(1)
        val resched = AtomicReference<Boolean>()
        h.client.runUploadJob(60_000) {
            resched.set(it)
            done.countDown()
        }
        waitUntil { h.transport.hangingCount == 1 }
        h.client.stopUploadJob()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(true, resched.get())
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Echo
        h.clock.advance(Limits.BACKOFF_MAX_MS)
        h.l(LogLevel.WARN, "w2")
        h.sealAndDrain()
        h.tick(2000)
        assertEquals("被停的作业之后，封段触发的排空照常上传", emptyList<String>(), h.outboxFiles())
        // 迟到的停止回调：作业早已结束，不挡之后的排空
        h.client.stopUploadJob()
        h.clock.advance(2000)
        h.l(LogLevel.WARN, "w3")
        h.sealAndDrain()
        assertEquals(emptyList<String>(), h.outboxFiles())
    }
}
