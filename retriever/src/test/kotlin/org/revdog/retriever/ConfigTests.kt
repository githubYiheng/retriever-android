package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.PlatformEvent
import java.net.URLDecoder

/** 远程配置（方案 §5；宪法 U-1 / U-2）。与 iOS ConfigTests 同一组。 */
class ConfigTests : RtvTest() {
    @Test
    fun fetchHeadersAndApply() {
        val t = FakeTransport()
        t.configBody = mapOf("etag" to "e1", "ttl_s" to 600, "upload_level" to "info", "local_level" to "info", "flush_interval_s" to 60)
        val o = Options().apply { dailyBatchCap = 7 }
        val h = Harness(options = o, transport = t)
        h.settle()
        h.client.setUser("张三 u/1")
        h.tick(Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        val r = t.configRequests.last()
        assertEquals("GET", r.method)
        assertEquals("https://logs-test.invalid/v1/config", r.url)
        assertEquals("Bearer lk_test_demo_abc_12345678", r.headers["Authorization"])
        assertEquals(h.client.installId, r.headers["X-Rtv-Install"])
        assertEquals("retriever-android/0.1.0", r.headers["X-Rtv-Sdk"])
        assertEquals("1.2.3", r.headers["X-Rtv-App-Version"])
        assertEquals("warn", r.headers["X-Rtv-Upload-Level"])
        assertEquals("debug", r.headers["X-Rtv-Local-Level"])
        assertEquals("7", r.headers["X-Rtv-Daily-Batch-Cap"])
        assertEquals((20 * 1024 * 1024).toString(), r.headers["X-Rtv-Local-Cap-Bytes"])
        assertEquals("%E5%BC%A0%E4%B8%89%20u%2F1", r.headers["X-Rtv-User"])
        assertEquals("张三 u/1", URLDecoder.decode(r.headers["X-Rtv-User"], "UTF-8"))
        // 生效：info 行成义务行，debug 行不写；公开只读级别同步
        assertEquals(LogLevel.INFO, h.client.effectiveLevels.first)
        assertEquals(LogLevel.INFO, h.client.effectiveLevels.second)
        val before = h.client.debugCounters
        h.client.log(LogLevel.DEBUG, "filtered", null, null, null)
        h.client.log(LogLevel.INFO, "obligation now", null, null, null)
        assertEquals(before.first + 1, h.client.debugCounters.first)
        assertEquals(before.second + 1, h.client.debugCounters.second)
        // 缓存到 config.json，重启后按墙钟兜底
        val h2 = Harness(root = h.root, clock = h.clock, transport = FakeTransport())
        h2.settle()
        val eff = h2.work { it.effective }
        assertEquals(LogLevel.INFO, eff.uploadLevel)
        assertEquals(60, eff.config.flushIntervalS)
    }

    @Test
    fun malformedConfigFallsBackWithoutAmplifying() {
        val t = FakeTransport()
        t.configBody = mapOf("upload_level" to "VERBOSE", "context_lines" to "lots", "full_dump" to "yes", "local_cap_bytes" to -5)
        val h = Harness(transport = t)
        h.settle()
        val eff = h.work { it.effective }
        assertEquals(LogLevel.WARN, eff.uploadLevel)
        assertFalse(eff.fullDumpActive)
        assertEquals(Limits.CTX_LINES_DEFAULT, eff.config.contextLines)
        assertEquals(Limits.LOCAL_CAP_BYTES_MIN, eff.config.localCapBytes)
    }

    @Test
    fun configEtagChangeInBatchResponseTriggersFetch() {
        val t = FakeTransport()
        t.configBody = mapOf("etag" to "etag-0", "ttl_s" to 1800)
        val h = Harness(transport = t)
        h.settle()
        val n0 = t.configRequests.size
        h.client.log(LogLevel.WARN, "a", null, null, null)
        h.sealAndDrain()
        assertEquals("etag 未变不拉", n0, t.configRequests.size)
        t.configEtag = "etag-1"
        h.client.log(LogLevel.WARN, "b", null, null, null)
        h.sealAndDrain()
        h.tick(2000)
        h.settle()
        assertEquals(n0 + 1, t.configRequests.size)
    }

    @Test
    fun uploadDisabledWritesButDoesNotSend() {
        val t = FakeTransport()
        t.configBody = mapOf("etag" to "x", "upload_enabled" to false)
        val h = Harness(transport = t)
        h.settle()
        h.client.log(LogLevel.WARN, "kept locally", null, null, null)
        h.sealAndDrain()
        assertEquals(0, t.batchRequests.size)
        assertEquals(1, h.outboxFiles().size)
        assertEquals(FlushResult.Pending("paused"), h.flushBlocking())
        assertEquals("flush 的批也只落盘", 2, h.outboxFiles().size)
        // 恢复：upload_enabled 变化触发封段并排空
        t.configBody = mapOf("etag" to "y", "upload_enabled" to true)
        h.client.log(LogLevel.WARN, "more", null, null, null)
        h.tick(Limits.CONFIG_POLL_INTERVAL_S * 1000L)
        h.tick(2000)
        h.tick(2000)
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals(3, t.batchRequests.size)
    }

    @Test
    fun backfillWaitsForUnmeteredNetwork() {
        val t = FakeTransport()
        val h = Harness(key = "", transport = t)
        h.settle()
        h.client.log(LogLevel.DEBUG, "history", null, null, null)
        h.seal()
        h.platform.expensive = true
        t.configBody = mapOf("etag" to "fd", "full_dump" to true, "full_dump_ttl_s" to 3600)
        h.enableUpload()
        assertEquals(1, h.outboxFiles("p2").size)
        assertEquals("计量网络上不传 backfill", 0, t.batchRequests.size)
        assertEquals("metered", h.client.lastStop.first)
        h.platform.expensive = false
        h.client.platformEvent(PlatformEvent.NETWORK_RESTORED)
        h.settle()
        assertEquals(1, t.batchRequests.size)
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertTrue(h.client.effectiveLevels.first == LogLevel.DEBUG)
    }
}
