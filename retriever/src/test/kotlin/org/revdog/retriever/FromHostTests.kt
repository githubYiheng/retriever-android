package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.ConfigCache
import org.revdog.retriever.core.ConfigRules
import org.revdog.retriever.core.HostDefaults
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.Limits
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 缓存只记远程明确给的值（ADR 0022；简报 §2 / §11 K）。假服务端开回显模式（按请求头补齐四项宿主默认并给出 from_host，与 ingest 一致）。
 */
class FromHostTests : RtvTest() {
    private fun oseqOf(h: StaticHarness, msg: String): Long =
        int(sessionLines(h.currentSessionDir()).last { it["msg"] == msg }["oseq"])

    /** 上一次以 [first] 拉到缓存（未过期，回显）；这次 configure([second])，配置请求挂着不回。返回第二次的夹具。 */
    private fun cachedThenReconfigured(first: LogLevel, second: LogLevel, body: Map<String, Any?> = mapOf("etag" to "e", "ttl_s" to 1800)): StaticHarness {
        val t = FakeTransport().apply {
            echoHost = true
            configBody = body
        }
        val h = StaticHarness(transport = t)
        h.configure("lk_test_demo_abc_12345678", Options().apply { uploadLevel = first })
        h.settle()
        val cfg = JsonIn.obj(File(h.root, "config.json").readBytes())!!
        assertEquals("回显进了响应", body["upload_level"] ?: first.name.lowercase(), obj(cfg["config"])["upload_level"])
        val t2 = FakeTransport().apply {
            echoHost = true
            configBody = body
            configGate = CountDownLatch(1)
        }
        val h2 = h.restart(t2)
        h2.log(LogLevel.INFO, "before configure")
        h2.configure("lk_test_demo_abc_12345678", Options().apply { uploadLevel = second })
        h2.log(LogLevel.INFO, "after configure")
        h2.client.onWork { }
        return h2
    }

    private fun release(h: StaticHarness) {
        h.transport.configGate?.countDown()
        h.transport.configGate = null
        h.settle()
    }

    @Test
    fun k_cachedEchoDoesNotOverrideNewHostLevel() {
        val h = cachedThenReconfigured(LogLevel.INFO, LogLevel.WARN)
        assertEquals(LogLevel.WARN, Retriever.uploadLevel)
        assertEquals("configure 之前的 info 无 oseq", -999L, oseqOf(h, "before configure"))
        assertEquals(-999L, oseqOf(h, "after configure"))
        release(h)

        val h2 = cachedThenReconfigured(LogLevel.WARN, LogLevel.INFO)
        assertEquals(LogLevel.INFO, Retriever.uploadLevel)
        assertEquals(1L, oseqOf(h2, "before configure"))
        assertEquals(2L, oseqOf(h2, "after configure"))
        release(h2)
    }

    /** 远程明确给了 upload_level（不在 from_host 里）：不随宿主变。 */
    @Test
    fun k_explicitRemoteValueStays() {
        val h = cachedThenReconfigured(LogLevel.WARN, LogLevel.INFO, mapOf("etag" to "e", "ttl_s" to 1800, "upload_level" to "error"))
        assertEquals(LogLevel.ERROR, Retriever.uploadLevel)
        assertEquals(-999L, oseqOf(h, "after configure"))
        val cfg = JsonIn.obj(File(h.root, "config.json").readBytes())!!
        assertEquals(listOf("local_level", "local_cap_bytes", "daily_batch_cap"), cfg["from_host"])
        release(h)
    }

    /** reconfigure 在宿主线程上同步生效：返回后立即写的行按新级别（引擎线程被堵住也成立）。 */
    @Test
    fun k_reconfigureLevelsSyncEvenWhenEngineBlocked() {
        val h = StaticHarness()
        h.configure("")
        h.settle()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        h.client.workAsync {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            h.log(LogLevel.INFO, "warn level")
            Retriever.configure(null, "", Harness.BASE, Options().apply { uploadLevel = LogLevel.INFO })
            h.log(LogLevel.INFO, "info level")
            val raw = h.client.debugOpenSegmentFile!!.readText().split("\n").filter { it.isNotEmpty() }.drop(1).map { JsonIn.obj(it)!! }
            assertEquals(listOf(-999L, 1L), raw.map { int(it["oseq"]) })
            assertEquals(LogLevel.INFO, Retriever.uploadLevel)
        } finally {
            gate.countDown()
        }
        h.settle()
    }

    /** 请求在途时宿主默认变了：响应按身份不符丢弃并重拉；缓存记的是新宿主默认那次的响应。 */
    @Test
    fun k_inflightResponseForOldHostDiscarded() {
        val gate = CountDownLatch(1)
        val t = FakeTransport().apply {
            echoHost = true
            configBody = mapOf("etag" to "e", "ttl_s" to 1800, "full_dump" to true, "full_dump_ttl_s" to 600)
            configGate = gate
        }
        val h = StaticHarness(transport = t)
        h.configure("lk_test_demo_abc_12345678", Options().apply { uploadLevel = LogLevel.WARN })
        var n = 0
        while (t.configRequests.isEmpty() && n++ < 2000) Thread.sleep(2)
        assertEquals("warn", t.configRequests.single().headers["X-Rtv-Upload-Level"])
        Retriever.configure(null, "lk_test_demo_abc_12345678", Harness.BASE, Options().apply { uploadLevel = LogLevel.ERROR })
        t.configBody = mapOf("etag" to "e2", "ttl_s" to 1800)
        t.configGate = null
        gate.countDown()
        n = 0
        while (t.configRequests.size < 2 && n++ < 2000) Thread.sleep(2)
        h.settle()
        assertEquals(2, t.configRequests.size)
        assertEquals("error", t.configRequests[1].headers["X-Rtv-Upload-Level"])
        val eff = h.client.engine.effective
        assertFalse("旧宿主默认那次的响应（含 full_dump）没生效", eff.fullDumpActive)
        assertEquals("e2", eff.config.etag)
        assertEquals(LogLevel.ERROR, Retriever.uploadLevel)
    }

    /** golden `from_host[]` 回放：测试里的 hostDerivedFields 移植与服务端一致；SDK 生效配置对 from_host 字段取当前宿主值，其余取缓存。 */
    @Test
    fun goldenFromHostReplay() {
        val g = Repo.golden("config.json")
        val vs = objs(g["from_host"])
        assertTrue(vs.isNotEmpty())
        val hostA = HostDefaults(LogLevel.INFO, LogLevel.INFO, 5, 3 * 1024 * 1024)
        val hostB = HostDefaults(LogLevel.ERROR, LogLevel.WARN, 77, 9 * 1024 * 1024)
        for (v in vs) {
            val name = v["name"] as String
            val expect = (v["expect"] as List<*>).map { it as String }
            assertEquals(name, expect, hostDerivedFields(v["raw"]))
            val fromHost = ConfigRules.fromHost(expect)
            assertEquals(name, expect, fromHost.toList())
            val cached = ConfigRules.clamp(v["raw"], hostA)
            val cache = ConfigCache(cached, 0, 0, false, fromHost)
            for (host in listOf(hostA, hostB)) {
                val hc = ConfigRules.clamp(null, host)
                val e = ConfigCache.effective(cache, host, 0, 0).config
                assertEquals(name, if ("upload_level" in expect) hc.uploadLevel else cached.uploadLevel, e.uploadLevel)
                assertEquals(name, if ("local_level" in expect) hc.localLevel else cached.localLevel, e.localLevel)
                assertEquals(name, if ("local_cap_bytes" in expect) hc.localCapBytes else cached.localCapBytes, e.localCapBytes)
                assertEquals(name, if ("daily_batch_cap" in expect) hc.dailyBatchCap else cached.dailyBatchCap, e.dailyBatchCap)
            }
        }
        // 缺省 / 非数组 / 未知名字：空集或只认已知字段
        assertEquals(emptySet<String>(), ConfigRules.fromHost(null))
        assertEquals(emptySet<String>(), ConfigRules.fromHost("upload_level"))
        assertEquals(setOf("local_level"), ConfigRules.fromHost(listOf("bogus", "local_level", 3.0)))
        // clamp 的 local_cap_bytes 缺省 = 宿主值（对齐 packages/core）
        assertEquals(3 * 1024 * 1024, ConfigRules.clamp(emptyMap<String, Any?>(), hostA).localCapBytes)
        assertEquals(Limits.LOCAL_CAP_BYTES_DEFAULT, ConfigRules.clamp(emptyMap<String, Any?>(), HostDefaults(LogLevel.WARN)).localCapBytes)
    }
}
