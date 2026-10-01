package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.RetrieverClient
import org.revdog.retriever.core.acquireUploadLock
import org.revdog.retriever.core.releaseUploadLock
import java.io.File

/** 多进程（方案 §3.2 / §3.4）：各进程独立会话目录；install.json / 出站箱共享；只有持有 upload.lock 的一方排空。 */
class MultiProcessTests : RtvTest() {
    @Test
    fun processesHaveOwnSessionDirsAndShareOutbox() {
        val root = tempDir()
        val main = Harness(root = root, key = "")
        main.settle()
        val remote = Harness(root = root, key = "", options = Options().apply { processName = "remote" })
        remote.settle()
        assertEquals("remote", remote.client.processName)
        assertTrue(File(root, "proc-main").isDirectory)
        assertTrue(File(root, "proc-remote").isDirectory)
        // 共享 install_id，session_no 各自递增
        assertEquals(main.client.installId, remote.client.installId)
        assertNotEquals(main.client.supportCode, remote.client.supportCode)
        main.client.log(LogLevel.WARN, "from main", null, null, null)
        remote.client.log(LogLevel.WARN, "from remote", null, null, null)
        main.seal()
        remote.seal()
        val envs = main.envelopes()
        assertEquals(setOf("main", "remote"), envs.map { it["process"] }.toSet())
        // 远端进程的会话不被主进程的恢复流程当孤儿
        val main2 = Harness(root = root, key = "")
        main2.settle()
        assertTrue(File(remote.sessionDir(), "meta.json").exists())
        assertEquals(0, main2.readJsonl("sessions.jsonl").count { it["session_id"] == remote.client.writer.currentSessionId })
        assertAllValid(runValidator(envs))
    }

    @Test
    fun uploadLockIsExclusiveAcrossInstances() {
        val root = tempDir()
        val a = Harness(root = root, key = "")
        a.settle()
        val b = Harness(root = root, key = "", options = Options().apply { processName = "worker" })
        b.settle()
        assertTrue(a.work { it.acquireUploadLock() })
        assertTrue("同一实例重入", a.work { it.acquireUploadLock() })
        assertFalse(b.work { it.acquireUploadLock() })
        // 拿不到锁的一方不发请求（locked，1 分钟后再试）
        b.work { it.key = "lk_test_demo_abc_12345678" }
        b.client.log(LogLevel.WARN, "w", null, null, null)
        b.sealAndDrain()
        assertEquals(0, b.transport.batchRequests.size)
        assertEquals("locked", b.client.lastStop.first)
        a.work { it.releaseUploadLock() }
        b.tick(60_000)
        assertEquals(1, b.transport.batchRequests.size)
        assertEquals(emptyList<String>(), b.outboxFiles())
    }

    /**
     * 禁用标记在 root 同级、多进程共享（ADR 0020 决定 2）：A 禁用后，B 的上传 / 拉配置在下一次决策时就停（每次决策 stat 一次）；
     * B 的写入要到它自己调用或重启才停。A 重新启用后 B 照常上传。
     */
    @Test
    fun markerSeenAcrossProcesses() {
        val root = tempDir()
        val a = Harness(root = root, key = "") // A 不上传：出站箱共享，免得 A 重新启用时替 B 把批传了
        a.settle()
        val b = Harness(root = root, options = Options().apply { processName = "worker" })
        b.settle()
        val cfg0 = b.transport.configRequests.size
        a.client.setEnabled(false)
        a.settle()
        assertTrue(a.disabledMarker.exists())
        assertTrue("B 的内存开关不受影响", b.client.isEnabled)
        b.client.log(LogLevel.WARN, "b still writes", null, null, null)
        assertEquals(1L, b.client.debugCounters.second)
        b.sealAndDrain()
        assertEquals("disabled", b.client.lastStop.first)
        assertEquals(0, b.transport.batchRequests.size)
        b.tick(31L * 60 * 1000)
        assertEquals("B 也不拉配置", cfg0, b.transport.configRequests.size)
        a.client.setEnabled(true)
        a.settle()
        b.tick(2000)
        assertEquals(1, b.transport.batchRequests.size)
        assertEquals(emptyList<String>(), b.outboxFiles())
    }

    /**
     * 多进程下 purgeLocal 只保证调用进程（ADR 0019 后果）：B 内存里还是旧 install，但它排空时以每个批自身信封里的 install 上报
     * （ADR 0019 决定 10），A 清空后写的新 install 的批不会因为「请求头与信封不符」出错。
     */
    @Test
    fun otherProcessUploadsWithEnvelopeInstallAfterPurge() {
        val root = tempDir()
        val a = Harness(root = root, key = "")
        a.settle()
        val b = Harness(root = root, key = "", options = Options().apply { processName = "worker" })
        b.settle()
        val old = a.client.installId
        a.purgeBlocking()
        val fresh = a.client.installId!!
        assertNotEquals(old, fresh)
        assertEquals("B 内存里的 install 不变（写进 README 的限制）", old, b.client.installId)
        a.client.log(LogLevel.WARN, "written after purge", null, null, null)
        a.seal()
        assertEquals(fresh, a.envelopes().single()["install_id"])
        b.enableUpload()
        assertEquals(1, b.transport.batchRequests.size)
        val r = b.transport.batchRequests[0]
        assertEquals(fresh, FakeTransport.envOf(r.body)!!["install_id"])
        assertEquals("请求头 == 信封，不是 B 内存里的旧 install", fresh, r.headers["X-Rtv-Install"])
        assertEquals(emptyList<String>(), a.outboxFiles())
    }

    @Test
    fun processNameSanitized() {
        assertEquals("main", RetrieverClient.sanitizeProcessName(""))
        assertEquals("remote", RetrieverClient.sanitizeProcessName("remote"))
        assertEquals("com.foo_bar_1", RetrieverClient.sanitizeProcessName("com.foo:bar 1"))
        assertEquals("___", RetrieverClient.sanitizeProcessName("日志/"))
        assertEquals(64, RetrieverClient.sanitizeProcessName("x".repeat(100)).length)
    }
}
