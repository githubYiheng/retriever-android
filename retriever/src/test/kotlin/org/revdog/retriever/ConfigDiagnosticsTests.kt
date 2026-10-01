package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.FakeTransport.Reply
import org.revdog.retriever.core.ConfigCheck
import org.revdog.retriever.core.ConfigCheck.BASE_URL_INVALID
import org.revdog.retriever.core.ConfigCheck.KEY_ENV_MISMATCH
import org.revdog.retriever.core.ConfigCheck.KEY_MALFORMED
import org.revdog.retriever.core.ConfigCheck.KEY_REJECTED
import org.revdog.retriever.core.ConfigCheck.KEY_TRIMMED
import org.revdog.retriever.core.ConfigCheck.NO_KEY
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.SealReason
import org.revdog.retriever.core.Text
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 配置诊断（ADR 0025；简报 §13 V1–V9）：key / baseUrl 的修剪与本地检查、服务端拒绝 key 时的 `key_rejected`、去重与出口防护。
 * 除 V1 外都走静态入口；出口换成记录 `(code, message, 线程名)` 的 sink（RtvTest 结束时复位、去重集合清空）。
 */
class ConfigDiagnosticsTests : RtvTest() {
    /** 取自 golden/apikey.json `valid[]`。 */
    private val testKey = "lk_test_my_app-2_ffffffffffffffffffffffffffffffff_0456af9e"
    private val testKey2 = "lk_test_ab_00000000000000000000000000000000_d7ae878c"
    private val liveKey = "lk_live_bible-bff_0123456789abcdef0123456789abcdef_86381e7d"

    /** 自定义主机（不判 env）。 */
    private val custom = "http://127.0.0.1:8787"

    private class Diag(val code: String, val message: String, val thread: String)

    private val got = CopyOnWriteArrayList<Diag>()

    private val codes: List<String> get() = got.map { it.code }

    private val rejected: List<String> get() = got.filter { it.code == KEY_REJECTED }.map { it.message }

    private fun harness(t: FakeTransport = FakeTransport()): StaticHarness {
        val h = StaticHarness(transport = t)
        ConfigDiagnostics.sink = { code, message -> got.add(Diag(code, message, Thread.currentThread().name)) }
        return h
    }

    private fun configure(key: String?, baseUrl: String?) {
        Retriever.configure(null, key, baseUrl, Options())
    }

    private fun sealAndDrain(h: StaticHarness) {
        h.client.writer.rotate(SealReason.TIMER)
        h.client.onWork { h.client.afterSeal() }
        h.settle()
    }

    private fun pausedCategories(h: StaticHarness): List<String> = h.client.onWork { h.client.engine.backoff.pausedCategories }

    private fun waitUntil(cond: () -> Boolean) {
        var n = 0
        while (!cond() && n < 2500) {
            Thread.sleep(2)
            n++
        }
    }

    private fun rejectedMsg(status: Int, reason: String, minutes: Int) =
        "server rejected the key (HTTP $status, reason=$reason); uploads paused for $minutes min; logs are kept locally"

    /** 消息绝不含 key 的任何部分（消息是写死的句子；这里兜底查前缀与各段）。 */
    private fun assertNoKeyLeak(vararg keys: String) {
        for (d in got) {
            assertFalse(d.message, d.message.contains("lk_"))
            for (k in keys) for (part in k.split('_').filter { it.length >= 6 }) assertFalse("${d.message} ⊃ $part", d.message.contains(part))
        }
    }

    // ---------------------------------------------------------------- V1

    /** golden/apikey.json：`crc32[]` 逐条相等、`valid[]` 判合法（env 正确、不出任何 code）、`invalid[]` 判 `key_malformed`。 */
    @Test
    fun v1_goldenApiKey() {
        val g = Repo.golden("apikey.json")
        val crcs = objs(g["crc32"])
        assertTrue(crcs.isNotEmpty())
        for (v in crcs) assertEquals("${v["source"]}", v["expect"], ConfigCheck.crc32Hex(Text.utf8(v["text"] as String)))
        val valid = objs(g["valid"])
        assertTrue(valid.isNotEmpty())
        for (v in valid) {
            val k = v["key"] as String
            assertEquals("${v["name"]}", v["crc"], ConfigCheck.crc32Hex(Text.utf8(v["crc_input"] as String)))
            assertEquals("${v["name"]}", v["env"], ConfigCheck.keyEnv(k))
            assertEquals("${v["name"]}", emptyList<String>(), ConfigCheck.check(k, k, custom))
        }
        val invalid = objs(g["invalid"])
        assertTrue(invalid.isNotEmpty())
        for (v in invalid) {
            val k = v["key"] as String
            assertNull("${v["name"]}", ConfigCheck.keyEnv(k))
            // 空 key 按第 1 条出 no_key（此时不再判 2–4）
            val expect = if (k.isEmpty()) listOf(NO_KEY) else listOf(KEY_MALFORMED)
            assertEquals("${v["name"]}", expect, ConfigCheck.check(k, k, custom))
        }
    }

    // ---------------------------------------------------------------- V2

    /** key 首部空格、尾部 `\n`，baseUrl 尾部空格：请求头是修剪后的 key、端点无空白、上传成功，`key_trimmed` 出一次。 */
    @Test
    fun v2_trimmedKeyAndBaseUrl() {
        val h = harness()
        configure(" $testKey\n", "${Harness.BASE}  ")
        h.settle()
        assertEquals(listOf(KEY_TRIMMED), codes)
        assertEquals("key had leading or trailing whitespace; it was trimmed", got[0].message)
        assertEquals(Ids.keyFingerprint(testKey), h.client.engine.keyFp)
        h.log(LogLevel.WARN, "w")
        sealAndDrain(h)
        val req = h.transport.batchRequests.single()
        assertEquals("Bearer $testKey", req.headers["Authorization"])
        assertEquals("${Harness.BASE}/v1/batches", req.url)
        assertTrue(h.transport.configRequests.all { it.url == "${Harness.BASE}/v1/config" && it.headers["Authorization"] == "Bearer $testKey" })
        assertEquals("已确认", emptyList<String>(), h.outboxFiles())
        // 同参数判定也用修剪后的值：换一种空白写法的重复 configure 是空操作（零请求），也不重复出诊断
        val n = h.transport.allRequests.size
        configure("　$testKey\t\u0085", " ${Harness.BASE} ")
        h.settle()
        assertEquals(n, h.transport.allRequests.size)
        assertEquals(listOf(KEY_TRIMMED), codes)
        assertNoKeyLeak(testKey)
    }

    // ---------------------------------------------------------------- V3

    /** 畸形 key：出 `key_malformed`，同参数再 configure 不重复出；请求照发，假服务端回 401 + reason → 再出 `key_rejected`。 */
    @Test
    fun v3_malformedKeyStillSent() {
        val t = FakeTransport()
        val h = harness(t)
        val bad = "lk_test_demo_abc_12345678"
        configure(bad, Harness.BASE)
        h.settle()
        assertEquals(listOf(KEY_MALFORMED), codes)
        assertEquals("key is not a valid Retriever key (format or checksum mismatch); the server will reject it", got[0].message)
        configure(bad, Harness.BASE)
        h.settle()
        assertEquals("同参数不重复出", listOf(KEY_MALFORMED), codes)
        t.defaultReply = Reply.Status(401, mapOf("reason" to "key_invalid"))
        h.log(LogLevel.WARN, "w")
        sealAndDrain(h)
        assertEquals("只出诊断不拦请求", "Bearer $bad", t.batchRequests.single().headers["Authorization"])
        assertEquals(listOf(KEY_MALFORMED, KEY_REJECTED), codes)
        assertEquals(rejectedMsg(401, "key_invalid", 60), got[1].message)
        assertNoKeyLeak(bad)
    }

    // ---------------------------------------------------------------- V4

    /** 空 key：出 `no_key`、零联网、本地照写；null 与纯空白（修剪后为空）同样只是 `no_key`（不另出 `key_trimmed`）。 */
    @Test
    fun v4_noKey() {
        val h = harness()
        configure("", Harness.BASE)
        h.log(LogLevel.ERROR, "e")
        assertTrue("本地照写", sessionLines(h.currentSessionDir()).any { it["msg"] == "e" })
        sealAndDrain(h)
        h.clock.advance(60_000)
        h.client.tickNow()
        assertEquals(listOf(NO_KEY), codes)
        assertEquals("no key configured; logs are written locally and never uploaded", got[0].message)
        assertEquals("零联网", 0, h.transport.allRequests.size)
        assertEquals("封段照常物化进出站箱", 1, h.outboxFiles().size)
        // 同一（no_key, 空指纹, baseUrl）：不重复
        configure(null, Harness.BASE)
        configure(" \n\t", Harness.BASE)
        assertEquals(listOf(NO_KEY), codes)
        // 换 baseUrl = 新的去重键：再出一次，纯空白 key 仍不出 key_trimmed
        configure(" \n", custom)
        h.settle()
        assertEquals(listOf(NO_KEY, NO_KEY), codes)
        assertEquals(0, h.transport.allRequests.size)
    }

    // ---------------------------------------------------------------- V5

    /** `key_env_mismatch`：test key + 生产地址、live key + staging 地址各出一次（主机不分大小写）；方向对的与自定义主机不出。 */
    @Test
    fun v5_envMismatch() {
        val h = harness()
        configure(testKey, "https://logs.revdog.org")
        assertEquals(listOf(KEY_ENV_MISMATCH), codes)
        assertEquals(
            "key environment does not match the endpoint (test key with production endpoint, or live key with staging endpoint); the server will reject it",
            got[0].message,
        )
        configure(liveKey, "https://logs-staging.revdog.org/")
        assertEquals(listOf(KEY_ENV_MISMATCH, KEY_ENV_MISMATCH), codes)
        configure(testKey, "HTTPS://LOGS.REVDOG.ORG")
        assertEquals(3, codes.count { it == KEY_ENV_MISMATCH })
        // 方向对的、自定义主机：不出
        configure(liveKey, "https://logs.revdog.org")
        configure(testKey, "https://logs-staging.revdog.org")
        configure(testKey, custom)
        configure(liveKey, custom)
        h.settle()
        assertEquals(listOf(KEY_ENV_MISMATCH, KEY_ENV_MISMATCH, KEY_ENV_MISMATCH), codes)
        assertNoKeyLeak(testKey, liveKey)
        // 主机按 URL 的主机判（与 scheme 是否合法无关），顺序固定：4 在 5 之前
        assertEquals(listOf(KEY_ENV_MISMATCH, BASE_URL_INVALID), ConfigCheck.check(testKey, testKey, "ftp://logs.revdog.org"))
        assertEquals(listOf(KEY_TRIMMED, KEY_ENV_MISMATCH), ConfigCheck.check("$testKey\n", testKey, "https://logs.revdog.org/v1"))
    }

    // ---------------------------------------------------------------- V6

    /** `base_url_invalid`：缺 scheme、`ftp://x`、空串各出一次（重复 configure 不重复）；`http://127.0.0.1:<port>` 不出。 */
    @Test
    fun v6_baseUrlInvalid() {
        val h = harness()
        for (b in listOf("logs.revdog.org", "ftp://x", "")) {
            configure(testKey, b)
            configure(testKey, b)
        }
        h.settle()
        assertEquals(listOf(BASE_URL_INVALID, BASE_URL_INVALID, BASE_URL_INVALID), codes)
        assertEquals("baseUrl is not a valid http(s) URL; uploads will fail", got[0].message)
        // 纯空白 = 修剪后的空串：同一去重键，不再出
        configure(testKey, "  \n")
        configure(testKey, custom)
        h.settle()
        assertEquals(3, codes.size)
        assertEquals(custom, h.client.engine.baseUrl)
    }

    // ---------------------------------------------------------------- V7

    /**
     * 401 带 reason → `key_rejected`（状态码 / reason / 分钟数），在排空线程上出（不在引擎线程）；同 key 第二次暂停（403，倍增）不重复；
     * 401 无 reason（HTML 体）不出。
     */
    @Test
    fun v7_keyRejectedOncePerKey() {
        val t = FakeTransport()
        val h = harness(t)
        configure(testKey, Harness.BASE)
        h.settle()
        t.setScript(listOf(Reply.Raw(401, "<html><body>Access denied</body></html>".toByteArray())))
        h.log(LogLevel.WARN, "w1")
        sealAndDrain(h)
        assertEquals(1, t.batchRequests.size)
        assertEquals("HTML 401 不暂停、不出", emptyList<String>(), rejected)
        assertEquals(emptyList<String>(), pausedCategories(h))

        t.setScript(listOf(Reply.Status(401, mapOf("reason" to "key_revoked"))))
        h.clock.advance(5_000)
        h.client.tickNow()
        assertEquals(2, t.batchRequests.size)
        assertEquals(listOf("all"), pausedCategories(h))
        assertEquals(listOf(rejectedMsg(401, "key_revoked", 60)), rejected)
        val d = got.single { it.code == KEY_REJECTED }
        assertFalse(d.thread, d.thread.startsWith("retriever-engine"))

        // 暂停到期后同 key 再被拒（403，暂停倍增到 120 min）：仍在暂停，但不重复出
        t.setScript(listOf(Reply.Status(403, mapOf("reason" to "app_disabled"))))
        h.clock.advance(Limits.PAUSE_401_BASE_MS + 1_000)
        h.client.tickNow()
        assertEquals(3, t.batchRequests.size)
        assertEquals(listOf("all"), pausedCategories(h))
        assertEquals(1, rejected.size)
        assertNoKeyLeak(testKey)
    }

    /** 旧 key 在途请求的 401（带 reason）：不暂停新 key，也不出 `key_rejected`。 */
    @Test
    fun v7_inflight401ForOldKeyNotReported() {
        val t = FakeTransport()
        val h = harness(t)
        configure(testKey, Harness.BASE)
        h.settle()
        val gate = CountDownLatch(1)
        t.setScript(listOf(Reply.Gated(gate, Reply.Status(401, mapOf("reason" to "key_revoked")))))
        h.log(LogLevel.WARN, "w")
        h.client.writer.rotate(SealReason.TIMER)
        h.client.onWork { h.client.afterSeal() }
        waitUntil { t.batchRequests.isNotEmpty() }
        configure(testKey2, Harness.BASE)
        h.client.onWork { }
        gate.countDown()
        h.settle()
        assertEquals(emptyList<String>(), rejected)
        assertEquals(emptyList<String>(), pausedCategories(h))
        h.clock.advance(3_000)
        h.client.tickNow()
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals("Bearer $testKey2", t.batchRequests.last().headers["Authorization"])
        assertEquals(emptyList<String>(), rejected)
    }

    // ---------------------------------------------------------------- V8

    /** reason 清洗：只留 `[a-z0-9_]`（大写、空格、非 ASCII 去掉）、最多 40 字符、清洗后为空 = `unknown`。 */
    @Test
    fun v8_reasonSanitized() {
        val t = FakeTransport()
        val h = harness(t)
        val cases = listOf(
            "Key_Invalid" to "ey_nvalid",
            "key invalid now" to "keyinvalidnow",
            "a".repeat(30) + "_" + "b".repeat(30) to "a".repeat(30) + "_" + "b".repeat(9),
            "!! -- ÄÖ 中文 \n" to "unknown",
            "" to "unknown",
        )
        for ((i, c) in cases.withIndex()) {
            // 每例换一个 key：换 key 清掉上一例的暂停，（code, 指纹, baseUrl）也是新的
            configure("lk_test_v8_case_$i", Harness.BASE)
            h.settle()
            t.setScript(listOf(Reply.Status(401, mapOf("reason" to c.first))))
            h.log(LogLevel.WARN, "w$i")
            h.clock.advance(3_000)
            sealAndDrain(h)
            assertEquals(c.first, i + 1, rejected.size)
            assertEquals(c.first, rejectedMsg(401, c.second, 60), rejected.last())
            assertEquals(c.first, c.second, ConfigCheck.sanitizeReason(c.first))
        }
        // 分钟数向上取整
        assertEquals(rejectedMsg(403, "x", 2), ConfigCheck.rejectedMessage(403, "x", 60_001))
        assertEquals(rejectedMsg(403, "x", 1440), ConfigCheck.rejectedMessage(403, "x", Limits.PAUSE_401_MAX_MS))
    }

    // ---------------------------------------------------------------- V9

    /** 出口抛异常（sink 抛 Error）：configure 不抛、实例照建、修剪照做、两条诊断都尝试过；`key_rejected` 时抛也不打断排空。 */
    @Test
    fun v9_sinkThrowsSwallowed() {
        val t = FakeTransport()
        val h = harness(t)
        val calls = AtomicInteger()
        ConfigDiagnostics.sink = { _, _ ->
            calls.incrementAndGet()
            throw Error("sink boom")
        }
        configure(" lk_bad\n", Harness.BASE)
        assertNotNull(Retriever.currentClient())
        h.settle()
        assertEquals("key_trimmed + key_malformed 都尝试过", 2, calls.get())
        assertEquals("lk_bad", h.client.engine.key)
        t.setScript(listOf(Reply.Status(401, mapOf("reason" to "key_invalid"))))
        h.log(LogLevel.WARN, "w")
        sealAndDrain(h)
        assertEquals(3, calls.get())
        assertEquals("Bearer lk_bad", t.batchRequests.single().headers["Authorization"])
        assertEquals(listOf("all"), pausedCategories(h))
        // 排空没被出口打断：换成合法 key 后照常上传
        configure(testKey, Harness.BASE)
        h.clock.advance(3_000)
        h.client.tickNow()
        assertEquals(emptyList<String>(), h.outboxFiles())
        assertEquals("Bearer $testKey", t.batchRequests.last().headers["Authorization"])
        assertEquals(3, calls.get())
    }
}
