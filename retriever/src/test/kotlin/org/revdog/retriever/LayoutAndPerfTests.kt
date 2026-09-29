package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.Ids
import java.io.File

class LayoutAndPerfTests : RtvTest() {
    /** 本地布局（方案 §3.2，含 iOS 加的 config.json / cursor.closed_ms / backoff.last_ack_ms）。 */
    @Test
    fun layoutAndInstallJson() {
        val t = FakeTransport().apply { configBody = mapOf("etag" to "e", "ttl_s" to 600) }
        val h = Harness(transport = t)
        h.settle()
        h.client.log(LogLevel.WARN, "w", null, null, null)
        h.sealAndDrain()
        val inst = h.readJson(File(h.root, "install.json"))
        assertTrue(Ids.isUuid(inst["install_id"] as String))
        assertEquals(1L, int(inst["session_counter"]))
        assertEquals(h.client.installId, inst["install_id"])
        assertEquals((inst["install_id"] as String).take(8) + "-1", h.client.supportCode)
        val meta = h.readJson(File(h.sessionDir(), "meta.json"))
        assertEquals(1L, int(meta["session_no"]))
        assertEquals("main", meta["process"])
        assertEquals("retriever-android/0.1.0", obj(meta["device"])["sdk"])
        assertEquals("android", obj(meta["device"])["os"])
        for (name in listOf("install.json", "config.json", "backoff.json", "mapping.json", "upload.lock", "outbox", "proc-main")) {
            assertTrue(name, File(h.root, name).exists())
        }
        assertTrue(h.readJson(File(h.root, "backoff.json")).containsKey("last_ack_ms"))
        assertTrue(obj(h.readJson(File(h.root, "config.json"))["config"]).containsKey("upload_level"))
        val files = h.sessionDir().list()!!.sorted()
        assertEquals(listOf("cursor.json", "meta.json", "seg-000001.sealed", "seg-000002.open"), files)
        // 第二次启动：计数器 +1，install_id 不变
        val h2 = Harness(root = h.root, key = "", clock = h.clock)
        h2.settle()
        assertEquals(h.client.installId, h2.client.installId)
        assertTrue(h2.client.supportCode!!.endsWith("-2"))
        // purgeLocal：新 install_id
        h2.client.purgeLocal()
        assertNotEquals(h.client.installId, h2.client.installId)
        assertTrue(h2.client.supportCode!!.endsWith("-1"))
        h2.client.log(LogLevel.WARN, "after purge", null, null, null)
        assertEquals(1L, h2.client.debugCounters.first)
        assertEquals(listOf("install.json", "outbox", "proc-main"), h2.root.list()!!.filter { !it.startsWith(".") && it != "upload.lock" }.sorted())
    }

    /** 性能（信息性）：log() 1 万次的 p99，打印到测试输出；断言 < 1 ms（JVM）。 */
    @Test
    fun logLatencyP99() {
        val h = Harness(key = "")
        h.settle()
        val c = h.client
        val samples = LongArray(10_000)
        for (i in 0 until 10_000) {
            val t0 = System.nanoTime()
            c.log(
                if (i % 10 == 0) LogLevel.WARN else LogLevel.INFO, "request finished", "net",
                mapOf("status" to 200, "path" to "/v1/plan", "ms" to (i % 300)), null,
            )
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        val p50 = samples[5_000] / 1000.0
        val p99 = samples[9_900] / 1000.0
        val max = samples.last() / 1000.0
        println(String.format("[perf] log() x10000 (JVM): p50=%.1fµs p99=%.1fµs max=%.1fµs", p50, p99, max))
        assertTrue("p99=$p99 µs", p99 < 1000)
    }
}
