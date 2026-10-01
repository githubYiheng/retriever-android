package org.revdog.retriever.core

/**
 * UTF-8 字节核算与码点边界截断（方案开头：所有长度按 UTF-8 字节，只在码点边界截断）。
 *
 * Java 字符串可能含孤立代理项：一律按 U+FFFD（3 字节）计、编码时写 `EF BF BD`
 * —— 与服务端 `utf8Len` / `TextEncoder` 逐字一致，也与 iOS（桥接时孤立代理项已变成 U+FFFD）一致。
 */
internal object Text {
    class Cut(val s: String, val truncated: Boolean)

    /** 与 packages/core `utf8Len` 逐字一致：只有「高位代理后紧跟低位代理」计 4 字节；孤立代理项 3 字节。 */
    fun utf8Len(s: String): Int {
        var n = 0
        var i = 0
        val len = s.length
        while (i < len) {
            val c = s[i].code
            if (c < 0x80) {
                n += 1
            } else if (c < 0x800) {
                n += 2
            } else if (isHigh(c) && i + 1 < len && isLow(s[i + 1].code)) {
                n += 4
                i++
            } else {
                n += 3
            }
            i++
        }
        return n
    }

    fun isHigh(c: Int): Boolean = c in 0xD800..0xDBFF

    fun isLow(c: Int): Boolean = c in 0xDC00..0xDFFF

    /** 位置 i 处码点占用的 UTF-16 单元数（代理对 = 2）。 */
    private fun unitsAt(s: String, i: Int): Int =
        if (isHigh(s[i].code) && i + 1 < s.length && isLow(s[i + 1].code)) 2 else 1

    /** 位置 i 处码点的 UTF-8 宽度。 */
    private fun widthAt(s: String, i: Int): Int {
        val c = s[i].code
        return when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            isHigh(c) && i + 1 < s.length && isLow(s[i + 1].code) -> 4
            else -> 3
        }
    }

    /** 位置 i 处码点「JSON 转义后」的字节数（与逐 UTF-8 字节转义的 JsonOut 一致）。 */
    private fun escapedWidthAt(s: String, i: Int): Int {
        val c = s[i].code
        if (c < 0x80) return escapedAsciiWidth(c)
        return widthAt(s, i)
    }

    fun escapedAsciiWidth(c: Int): Int = when (c) {
        0x22, 0x5C, 0x08, 0x09, 0x0A, 0x0C, 0x0D -> 2
        in 0 until 0x20 -> 6
        else -> 1
    }

    /** 截到 ≤ maxBytes 字节（码点边界）。 */
    fun truncate(s: String, maxBytes: Int): Cut {
        if (s.length.toLong() * 3 <= maxBytes || utf8Len(s) <= maxBytes) return Cut(s, false)
        var n = 0
        var i = 0
        while (i < s.length) {
            val w = widthAt(s, i)
            if (n + w > maxBytes) break
            n += w
            i += unitsAt(s, i)
        }
        return Cut(s.substring(0, i), true)
    }

    /** 保留尾部 ≤ maxBytes 字节（码点边界）。 */
    fun suffix(s: String, maxBytes: Int): String {
        if (utf8Len(s) <= maxBytes) return s
        var n = 0
        var start = s.length
        while (start > 0) {
            val prev = prevStart(s, start)
            val w = widthAt(s, prev)
            if (n + w > maxBytes) break
            n += w
            start = prev
        }
        return s.substring(start)
    }

    /** 截到「JSON 转义后（不含引号）」≤ budget 字节，码点边界。 */
    fun truncateEscaped(s: String, budget: Int): String {
        if (budget <= 0) return ""
        var n = 0
        var i = 0
        while (i < s.length) {
            val w = escapedWidthAt(s, i)
            if (n + w > budget) break
            n += w
            i += unitsAt(s, i)
        }
        return s.substring(0, i)
    }

    /** 保留尾部「JSON 转义后（不含引号）」≤ budget 字节，码点边界。 */
    fun suffixEscaped(s: String, budget: Int): String {
        if (budget <= 0) return ""
        var n = 0
        var start = s.length
        while (start > 0) {
            val prev = prevStart(s, start)
            val w = escapedWidthAt(s, prev)
            if (n + w > budget) break
            n += w
            start = prev
        }
        return s.substring(start)
    }

    /** end 之前那个码点的起点（代理对整体后退）。 */
    private fun prevStart(s: String, end: Int): Int {
        val p = end - 1
        if (p > 0 && isLow(s[p].code) && isHigh(s[p - 1].code)) return p - 1
        return p
    }

    /** 孤立代理项换成 U+FFFD（与 iOS 字符串语义一致；用于 user_id / device 等会被读回比较的值）。 */
    fun fixSurrogates(s: String): String {
        var i = 0
        var bad = false
        while (i < s.length) {
            val c = s[i].code
            if (isHigh(c) && i + 1 < s.length && isLow(s[i + 1].code)) {
                i += 2
                continue
            }
            if (isHigh(c) || isLow(c)) {
                bad = true
                break
            }
            i++
        }
        if (!bad) return s
        val sb = StringBuilder(s.length)
        i = 0
        while (i < s.length) {
            val c = s[i].code
            if (isHigh(c) && i + 1 < s.length && isLow(s[i + 1].code)) {
                sb.append(s[i]).append(s[i + 1])
                i += 2
                continue
            }
            sb.append(if (isHigh(c) || isLow(c)) '�' else s[i])
            i++
        }
        return sb.toString()
    }

    /**
     * user_id：≤ 128 B、不含 C0 / DEL / C1 控制字符（服务端校验规则；不合规的批会进隔离区，所以在源头清洗）。
     * 清洗后为空（`""`、纯空白、纯控制字符）= null（ADR 0024 决定 10）；空白 = Unicode White_Space（`Char.isWhitespace`）。
     */
    fun sanitizeUserId(s: String?): String? {
        if (s == null) return null
        val sb = StringBuilder(s.length)
        for (ch in fixSurrogates(s)) if (!isControl(ch.code)) sb.append(ch)
        val out = truncate(sb.toString(), Limits.USER_ID_BYTES).s
        return if (out.isBlank()) null else out
    }

    fun isControl(v: Int): Boolean = v <= 0x1F || (v in 0x7F..0x9F)

    /** UTF-8 编码（孤立代理项 → EF BF BD；Java 自带编码器会写成 `?`）。 */
    fun utf8(s: String): ByteArray {
        val o = JsonOut(s.length + 8)
        o.utf8(s)
        return o.toByteArray()
    }
}
