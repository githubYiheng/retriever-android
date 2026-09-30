package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.ConfigCache
import org.revdog.retriever.core.ConfigRules
import org.revdog.retriever.core.Day
import org.revdog.retriever.core.HostDefaults
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.JsonOut
import org.revdog.retriever.core.Limits

/** golden 向量（packages/core/golden）：三端与服务端逐字一致。 */
class GoldenTests {
    @Test
    fun idsUuidv5() {
        val g = Repo.golden("ids.json")
        assertEquals(Ids.NAMESPACE, g["retriever_namespace"])
        val vs = objs(g["uuidv5"])
        assertTrue(vs.isNotEmpty())
        for (v in vs) assertEquals("${v["name"]}", v["expect"], Ids.uuidv5(v["namespace"] as String, v["name"] as String))
    }

    @Test
    fun idsBatchId() {
        val g = Repo.golden("ids.json")
        for (v in objs(g["batch_id"])) {
            val kind = Ids.BatchKind.of(v["kind"] as String)!!
            val n = int(v["n"])
            val iid = v["install_id"] as String
            val sid = v["session_id"] as String
            assertEquals(v["name"], Ids.batchIdName(iid, sid, kind, n))
            assertEquals(v["expect"], Ids.batchId(iid, sid, kind, n))
        }
        for (v in objs(g["batch_id_invalid"])) {
            val kind = Ids.BatchKind.of(v["kind"] as String)!!
            // n 非整数（1.5）在 Long 接口里不可表达：同样视为拒绝
            val n = JsonIn.int64(v["n"]) ?: continue
            assertNull("${v["why"]}", Ids.batchId(v["install_id"] as String, v["session_id"] as String, kind, n))
        }
    }

    /** backfill 切分 batch_id（三段 name）：与 `uuidv5(ns, "<install>:<session>:backfill:<seg_no>:<seq_from>")` 一致。 */
    @Test
    fun backfillSplitBatchIdName() {
        val iid = "3f2c9a4e-8b1d-4c7a-9e5f-0a1b2c3d4e5f"
        val sid = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        val bid = Ids.backfillSplitBatchId(iid, sid, 3, 1201)
        assertEquals(Ids.uuidv5(Ids.NAMESPACE, "$iid:$sid:backfill:3:1201"), bid)
        assertNotEquals(Ids.batchId(iid, sid, Ids.BatchKind.BACKFILL, 3), bid)
        assertTrue(Ids.isUuid(bid!!))
        assertNull(Ids.backfillSplitBatchId(iid.uppercase(), sid, 3, 1))
    }

    @Test
    fun idsUuidRegex() {
        val g = Repo.golden("ids.json")
        for (s in (g["uuid_valid"] as List<*>)) assertTrue("$s", Ids.isUuid(s as String))
        for (v in objs(g["uuid_invalid"])) assertFalse("${v["why"]}", Ids.isUuid(v["value"] as String))
        // 新生成的 v4 必须过 UUID_RE
        repeat(100) { assertTrue(Ids.isUuid(Ids.newV4())) }
    }

    @Test
    fun clientDay() {
        val g = Repo.golden("object-key.json")
        val vs = objs(g["client_day"])
        assertTrue(vs.isNotEmpty())
        for (v in vs) assertEquals("${v["name"]}", v["expect"], Day.clientDay(int(v["ts_min_ms"]), int(v["created_ms"])))
        for (v in objs(g["day_from_ms"])) assertEquals(v["expect"], Day.fromMs(int(v["ms"])))
        // 负数也按 floor（1969-12-31）
        assertEquals("1969-12-31", Day.fromMs(-1))
    }

    @Test
    fun configClamp() {
        val g = Repo.golden("config.json")
        val vs = objs(g["clamp"])
        assertEquals(50, vs.size)
        for (v in vs) {
            val name = v["name"] as String
            val h = obj(v["host"])
            val host = HostDefaults(
                LogLevel.ofWire(h["uploadLevel"] as? String) ?: LogLevel.WARN,
                LogLevel.ofWire(h["localLevel"] as? String),
                JsonIn.int64(h["dailyBatchCap"])?.toInt(),
            )
            val c = ConfigRules.clamp(v["raw"], host)
            val e = obj(v["expect"])
            assertEquals(name, e["etag"], c.etag)
            assertEquals(name, int(e["ttl_s"]), c.ttlS.toLong())
            assertEquals(name, e["upload_enabled"], c.uploadEnabled)
            assertEquals(name, e["upload_level"], c.uploadLevel.wire)
            assertEquals(name, e["local_level"], c.localLevel.wire)
            assertEquals(name, int(e["context_lines"]), c.contextLines.toLong())
            assertEquals(name, int(e["context_bytes"]), c.contextBytes.toLong())
            assertEquals(name, int(e["flush_interval_s"]), c.flushIntervalS.toLong())
            assertEquals(name, int(e["local_cap_bytes"]), c.localCapBytes.toLong())
            assertEquals(name, e["full_dump"], c.fullDump)
            assertEquals(name, int(e["full_dump_ttl_s"]), c.fullDumpTtlS.toLong())
            assertEquals(name, int(e["daily_batch_cap"]), c.dailyBatchCap.toLong())
            assertEquals(name, 12, e.keys.size)
            // 缓存文件往返：encode 后再 clamp 得到同一份
            assertEquals(name, c, ConfigRules.clamp(JsonIn.obj(ConfigRules.encode(c)), host))
        }
    }

    /** 缓存过期后放大型字段回落（宪法 U-2）。 */
    @Test
    fun expiredCacheRevertsAmplifyingFields() {
        val host = HostDefaults(LogLevel.WARN)
        val raw = linkedMapOf<String, Any?>(
            "ttl_s" to 60.0, "upload_level" to "debug", "context_lines" to 500.0, "context_bytes" to 262144.0,
            "flush_interval_s" to 30.0, "full_dump" to true, "full_dump_ttl_s" to 3600.0, "local_cap_bytes" to 104857600.0,
        )
        val cfg = ConfigRules.clamp(raw, host)
        val cache = ConfigCache(cfg, 0, 1000)
        val live = ConfigCache.effective(cache, host, 0, 1000 + 59_000)
        assertTrue(live.fullDumpActive)
        assertEquals(LogLevel.DEBUG, live.uploadLevel)
        assertEquals(500, live.config.contextLines)
        val exp = ConfigCache.effective(cache, host, 0, 1000 + 60_000)
        assertFalse(exp.fullDumpActive)
        assertEquals(LogLevel.WARN, exp.uploadLevel)
        assertEquals(Limits.CTX_LINES_DEFAULT, exp.config.contextLines)
        assertEquals(Limits.CTX_BYTES_DEFAULT, exp.config.contextBytes)
        assertEquals(Limits.FLUSH_INTERVAL_S_DEFAULT, exp.config.flushIntervalS)
        assertEquals(Limits.LOCAL_CAP_BYTES_DEFAULT, exp.config.localCapBytes)
        // 重启后（无单调基准）用墙钟兜底
        val cold = ConfigCache(cfg, 5000, null)
        assertTrue(ConfigCache.effective(cold, host, 5000 + 30_000, 0).fullDumpActive)
        assertFalse(ConfigCache.effective(cold, host, 5000 + 61_000, 0).fullDumpActive)
        raw["upload_level"] = "error"
        val quiet = ConfigCache(ConfigRules.clamp(raw, host), 0, 0)
        // 高于宿主默认（更少上传）的级别过期后不回落（只回落放大型）
        assertEquals(LogLevel.ERROR, ConfigCache.effective(quiet, host, 0, 999_999).uploadLevel)
    }

    /**
     * JS Number::toString 格式（attrs 数字）。与 iOS 同一组向量，去掉 5e-324：Java 的 Double.toString 对
     * Double.MIN_VALUE 规定输出 "4.9E-324"（非最短），只影响 attrs 里的次正规数位数，服务端不比较格式。
     */
    @Test
    fun jsNumberFormatting() {
        val cases = listOf(
            0.0 to "0", -0.0 to "0", 1.0 to "1", -5.0 to "-5", 0.1 to "0.1", 123.456 to "123.456", 1e21 to "1e+21",
            1e20 to "100000000000000000000", 1.5e-7 to "1.5e-7", 1e-7 to "1e-7", 0.000001 to "0.000001",
            "123456789012345680000".toDouble() to "123456789012345680000", "9007199254740993".toDouble() to "9007199254740992",
            2.5e300 to "2.5e+300", -1.25 to "-1.25",
        )
        for ((d, s) in cases) assertEquals("$d", s, JsonOut.jsNumber(d))
    }

    @Test
    fun jsonParserStrictness() {
        assertEquals(JsonIn.INVALID, JsonIn.parse("{\"a\":1} x"))
        assertEquals(JsonIn.INVALID, JsonIn.parse("{\"a\":01}"))
        assertEquals(JsonIn.INVALID, JsonIn.parse("{\"a\":\"\n\"}"))
        val o = JsonIn.obj("{\"a\":1,\"a\":2,\"s\":\"\\u65e5\\ud83d\\udc36\",\"n\":null,\"b\":false,\"l\":[1.5,-2e3]}")!!
        assertEquals(2.0, o["a"])
        assertEquals("日🐶", o["s"])
        assertTrue(o.containsKey("n"))
        assertNull(o["n"])
        assertEquals(false, o["b"])
        assertEquals(listOf(1.5, -2000.0), o["l"])
        assertNull(JsonIn.int64(1.5))
        assertNull(JsonIn.int64(9_007_199_254_740_992.0))
        assertEquals(9_007_199_254_740_991L, JsonIn.int64(9_007_199_254_740_991.0))
    }

    @Test
    fun versionMatchesGradle() {
        assertEquals(System.getProperty("rtv.versionName"), RetrieverVersion.CURRENT)
        assertEquals("retriever-android/${RetrieverVersion.CURRENT}", Harness(key = "").engine.sdkHeader)
        Harness.closeAll()
    }
}
