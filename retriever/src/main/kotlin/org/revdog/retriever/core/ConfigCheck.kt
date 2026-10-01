package org.revdog.retriever.core

import java.net.URL
import java.util.zip.CRC32

/**
 * 配置诊断（ADR 0025；简报 §13）：key / baseUrl 的修剪、纯函数检查、`key_rejected` 的 reason 清洗与写死的消息。
 * **只出诊断，不改行为**：请求照发，服务端是唯一裁决者（key 格式将来演进时 SDK 不会比服务端更严）。
 * 消息是写死的英文句子，绝不含 key 的任何部分。code 与消息与 iOS `ConfigCheck.swift` 逐字一致。
 */
internal object ConfigCheck {
    const val NO_KEY = "no_key"
    const val KEY_TRIMMED = "key_trimmed"
    const val KEY_MALFORMED = "key_malformed"
    const val KEY_ENV_MISMATCH = "key_env_mismatch"
    const val BASE_URL_INVALID = "base_url_invalid"
    const val KEY_REJECTED = "key_rejected"

    /** 生产 / staging 的入口主机（只判这两个，其它主机不判 env）。 */
    const val PRODUCTION_HOST = "logs.revdog.org"
    const val STAGING_HOST = "logs-staging.revdog.org"

    /** 同 `packages/core/src/apikey.ts` 的 `API_KEY_RE`；整串匹配（Java 的 `$` 会放过结尾换行，所以用 matchEntire）。 */
    private val KEY_RE = Regex("lk_(live|test)_([a-z0-9][a-z0-9_-]{1,31})_([0-9a-f]{32})_([0-9a-f]{8})")

    /** reason 清洗后的上限（字符）。 */
    private const val REASON_MAX = 40

    /**
     * 去掉首尾的空白与控制字符：码点 ≤ 0x20、0x7F–0x9F（C0 / 空格 / DEL / C1），以及 Unicode White_Space
     * （U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、U+202F、U+205F、U+3000；其余 White_Space 已落在前两段）。都在 BMP，代理项不会命中。
     * configure / 再次 configure 拿到的 key 与 baseUrl 先过它，之后一切用途（指纹、请求头、端点、同参数判定）都用修剪后的值。
     */
    fun trim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && trimmable(s[start].code)) start++
        while (end > start && trimmable(s[end - 1].code)) end--
        return if (start == 0 && end == s.length) s else s.substring(start, end)
    }

    private fun trimmable(c: Int): Boolean =
        c <= 0x20 || c in 0x7F..0x9F || c == 0xA0 || c == 0x1680 || c in 0x2000..0x200A ||
            c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000

    /**
     * 本地检查（顺序固定）：`rawKey` = 宿主传入的原值（null 已按 "" 计），`key` / `baseUrl` = 修剪后的值。
     * 1 `no_key`（key 为空，此时不再判 2–4）→ 2 `key_trimmed` → 3 `key_malformed` → 4 `key_env_mismatch`（key 合法时）→ 5 `base_url_invalid`。
     */
    fun check(rawKey: String, key: String, baseUrl: String): List<String> {
        val out = ArrayList<String>(3)
        val url = parse(baseUrl)
        if (key.isEmpty()) {
            out.add(NO_KEY)
        } else {
            if (rawKey != key) out.add(KEY_TRIMMED)
            val env = keyEnv(key)
            if (env == null) {
                out.add(KEY_MALFORMED)
            } else {
                // 只判两个已知主机；自定义主机（自建、本机）不判
                val host = url?.host?.lowercase()
                if ((host == PRODUCTION_HOST && env == "test") || (host == STAGING_HOST && env == "live")) out.add(KEY_ENV_MISMATCH)
            }
        }
        if (!isHttpUrl(url)) out.add(BASE_URL_INVALID)
        return out
    }

    /**
     * key 合法时返回 env（`live` | `test`），否则 null：整串匹配 [KEY_RE]，且末 8 位 = 最后一个 `_` 之前全部 UTF-8 字节的 CRC-32/IEEE。
     * app 可含 `_`：末尾固定 42 字符 = `_<32hex>_<8hex>`，正则回溯后切分无歧义。
     */
    fun keyEnv(key: String): String? {
        val m = KEY_RE.matchEntire(key) ?: return null
        val crc = m.groupValues[4]
        val body = key.substring(0, key.length - crc.length - 1)
        if (crc32Hex(Text.utf8(body)) != crc) return null
        return m.groupValues[1]
    }

    /** CRC-32/IEEE（反射多项式 0xEDB88320，init / xorout 0xFFFFFFFF），8 位小写十六进制。 */
    fun crc32Hex(bytes: ByteArray): String {
        val c = CRC32()
        c.update(bytes)
        return java.lang.Long.toHexString(c.value).padStart(8, '0')
    }

    /** 按绝对 URL 解析 baseUrl；解析不了（缺 scheme、未知 scheme、端口非法……）= null。用与传输相同的 `java.net.URL`：它解析不了的地址，上传也发不出去。 */
    private fun parse(baseUrl: String): URL? = try {
        URL(baseUrl)
    } catch (t: Throwable) {
        null
    }

    /** scheme ∈ {http, https}（不分大小写）且主机非空的绝对 URL。 */
    private fun isHttpUrl(u: URL?): Boolean {
        if (u == null) return false
        val scheme = u.protocol?.lowercase()
        return (scheme == "http" || scheme == "https") && !u.host.isNullOrEmpty()
    }

    /** 五个本地检查的消息（两端逐字一致）。 */
    fun message(code: String): String = when (code) {
        NO_KEY -> "no key configured; logs are written locally and never uploaded"
        KEY_TRIMMED -> "key had leading or trailing whitespace; it was trimmed"
        KEY_MALFORMED -> "key is not a valid Retriever key (format or checksum mismatch); the server will reject it"
        KEY_ENV_MISMATCH ->
            "key environment does not match the endpoint (test key with production endpoint, or live key with staging endpoint); the server will reject it"
        BASE_URL_INVALID -> "baseUrl is not a valid http(s) URL; uploads will fail"
        else -> code
    }

    /** `key_rejected` 的消息：reason 先清洗；分钟数 = 本次暂停时长向上取整到分钟。 */
    fun rejectedMessage(status: Int, reason: String, pauseMs: Long): String {
        val minutes = (maxOf(pauseMs, 0L) + 59_999L) / 60_000L
        return "server rejected the key (HTTP $status, reason=${sanitizeReason(reason)}); uploads paused for $minutes min; logs are kept locally"
    }

    /** reason 清洗：只留 `[a-z0-9_]`（其它字符一律去掉，大写也去掉、不转小写）、最多 40 字符；清洗后为空 = `unknown`。 */
    fun sanitizeReason(reason: String): String {
        val sb = StringBuilder(minOf(reason.length, REASON_MAX))
        for (ch in reason) {
            if (sb.length >= REASON_MAX) break
            if (ch in 'a'..'z' || ch in '0'..'9' || ch == '_') sb.append(ch)
        }
        return if (sb.isEmpty()) "unknown" else sb.toString()
    }
}

/** 服务端拒绝 key、本次进入鉴权暂停（`key_rejected` 诊断的素材）：状态码、原始 reason、暂停时长、请求所用的 key 指纹与 baseUrl。 */
internal class KeyRejection(val status: Int, val reason: String, val pauseMs: Long, val keyFp: String, val baseUrl: String)
