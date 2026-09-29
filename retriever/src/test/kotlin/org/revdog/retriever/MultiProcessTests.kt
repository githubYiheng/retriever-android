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

    @Test
    fun processNameSanitized() {
        assertEquals("main", RetrieverClient.sanitizeProcessName(""))
        assertEquals("remote", RetrieverClient.sanitizeProcessName("remote"))
        assertEquals("com.foo_bar_1", RetrieverClient.sanitizeProcessName("com.foo:bar 1"))
        assertEquals("___", RetrieverClient.sanitizeProcessName("日志/"))
        assertEquals(64, RetrieverClient.sanitizeProcessName("x".repeat(100)).length)
    }
}
