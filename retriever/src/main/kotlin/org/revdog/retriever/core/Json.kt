package org.revdog.retriever.core

import kotlin.math.abs
import kotlin.math.max

/**
 * 手写 JSON 输出：控制字段顺序、转义与字节核算（与 JS `JSON.stringify` 逐字节一致：
 * 只转义 `"`、`\`、C0 控制字符；`\b \t \n \f \r` 用短形式，其余 `\u00xx` 小写十六进制；非 ASCII 原样 UTF-8）。
 * 与 iOS `JSONOut` 同口径。
 */
internal class JsonOut(capacity: Int = 256) {
    private var buf = ByteArray(max(capacity, 16))
    var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(max(buf.size * 2, size + n))
    }

    fun byte(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    /** 只用于 ASCII 字面量与数字。 */
    fun raw(s: String) {
        ensure(s.length)
        for (ch in s) buf[size++] = ch.code.toByte()
    }

    fun raw(b: ByteArray) {
        raw(b, 0, b.size)
    }

    fun raw(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
    }

    fun string(s: String) {
        byte(0x22)
        escaped(s)
        byte(0x22)
    }

    fun stringOrNull(s: String?) {
        if (s == null) raw("null") else string(s)
    }

    fun int(v: Long) {
        raw(v.toString())
    }

    fun int(v: Int) {
        raw(v.toString())
    }

    fun bool(v: Boolean) {
        raw(if (v) "true" else "false")
    }

    fun number(d: Double) {
        raw(jsNumber(d))
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    /** 原样 UTF-8（不转义）。 */
    fun utf8(s: String) {
        ensure(s.length * 3)
        var i = 0
        val len = s.length
        while (i < len) {
            val c = s[i].code
            if (c < 0x80) {
                buf[size++] = c.toByte()
            } else {
                i = putNonAscii(s, i, c)
            }
            i++
        }
    }

    /** 转义后的 UTF-8（逐 UTF-8 字节转义，非 ASCII 原样）。 */
    fun escaped(s: String) {
        ensure(s.length * 6)
        var i = 0
        val len = s.length
        while (i < len) {
            val c = s[i].code
            if (c < 0x80) {
                when (c) {
                    0x22 -> { buf[size++] = 0x5C; buf[size++] = 0x22 }
                    0x5C -> { buf[size++] = 0x5C; buf[size++] = 0x5C }
                    0x08 -> { buf[size++] = 0x5C; buf[size++] = 0x62 }
                    0x09 -> { buf[size++] = 0x5C; buf[size++] = 0x74 }
                    0x0A -> { buf[size++] = 0x5C; buf[size++] = 0x6E }
                    0x0C -> { buf[size++] = 0x5C; buf[size++] = 0x66 }
                    0x0D -> { buf[size++] = 0x5C; buf[size++] = 0x72 }
                    else -> if (c < 0x20) {
                        buf[size++] = 0x5C
                        buf[size++] = 0x75
                        buf[size++] = 0x30
                        buf[size++] = 0x30
                        buf[size++] = HEX[c shr 4]
                        buf[size++] = HEX[c and 0xF]
                    } else {
                        buf[size++] = c.toByte()
                    }
                }
            } else {
                i = putNonAscii(s, i, c)
            }
            i++
        }
    }

    /** 写一个非 ASCII 码点（调用方已 ensure）；返回最后消费的下标。 */
    private fun putNonAscii(s: String, i: Int, c: Int): Int {
        if (c < 0x800) {
            buf[size++] = (0xC0 or (c shr 6)).toByte()
            buf[size++] = (0x80 or (c and 0x3F)).toByte()
            return i
        }
        if (Text.isHigh(c) && i + 1 < s.length && Text.isLow(s[i + 1].code)) {
            val cp = 0x10000 + ((c - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
            buf[size++] = (0xF0 or (cp shr 18)).toByte()
            buf[size++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
            buf[size++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
            buf[size++] = (0x80 or (cp and 0x3F)).toByte()
            return i + 1
        }
        val v = if (Text.isHigh(c) || Text.isLow(c)) 0xFFFD else c
        buf[size++] = (0xE0 or (v shr 12)).toByte()
        buf[size++] = (0x80 or ((v shr 6) and 0x3F)).toByte()
        buf[size++] = (0x80 or (v and 0x3F)).toByte()
        return i
    }

    companion object {
        val HEX: ByteArray = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

        /** 字符串值（含两侧引号）序列化后的字节数。 */
        fun escapedLength(s: String): Int {
            var n = 2
            var i = 0
            val len = s.length
            while (i < len) {
                val c = s[i].code
                if (c < 0x80) {
                    n += Text.escapedAsciiWidth(c)
                } else if (c < 0x800) {
                    n += 2
                } else if (Text.isHigh(c) && i + 1 < len && Text.isLow(s[i + 1].code)) {
                    n += 4
                    i++
                } else {
                    n += 3
                }
                i++
            }
            return n
        }

        /**
         * ECMAScript Number::toString（JSON.stringify 的数字格式），保证与服务端校验的字节核算一致。调用方保证 d 有限。
         * 数字串取 `Double.toString`（JDK 19+ 为最短往返；旧运行时偶有多一位，只影响 attrs 数字的位数，不影响合法性）。
         */
        fun jsNumber(d: Double): String {
            if (d == 0.0) return "0"
            if (d == Math.rint(d) && abs(d) < 9_007_199_254_740_992.0) return d.toLong().toString()
            val desc = abs(d).toString()
            var mantissa = desc
            var exp = 0
            val e = desc.indexOfFirst { it == 'E' || it == 'e' }
            if (e >= 0) {
                mantissa = desc.substring(0, e)
                exp = desc.substring(e + 1).toInt()
            }
            var intPart = mantissa
            var fracPart = ""
            val dot = mantissa.indexOf('.')
            if (dot >= 0) {
                intPart = mantissa.substring(0, dot)
                fracPart = mantissa.substring(dot + 1)
            }
            val digits = StringBuilder(intPart + fracPart)
            var n = intPart.length + exp
            while (digits.length > 1 && digits[0] == '0') {
                digits.deleteCharAt(0)
                n -= 1
            }
            while (digits.length > 1 && digits[digits.length - 1] == '0') digits.deleteCharAt(digits.length - 1)
            val k = digits.length
            val ds = digits.toString()
            val out = if (n in k..21) {
                ds + "0".repeat(n - k)
            } else if (n in 1..21) {
                ds.substring(0, n) + "." + ds.substring(n)
            } else if (n > -6 && n <= 0) {
                "0." + "0".repeat(-n) + ds
            } else {
                val ee = n - 1
                val sign = if (ee >= 0) "+" else "-"
                if (k == 1) ds + "e" + sign + abs(ee) else ds[0] + "." + ds.substring(1) + "e" + sign + abs(ee)
            }
            return if (d < 0) "-$out" else out
        }
    }
}

/**
 * 读 JSON（本地状态文件、信封头、服务端响应）：只依赖 stdlib 的递归下降解析。
 * 结果：对象 = `Map<String, Any?>`（LinkedHashMap，重复键后者覆盖，同 JS）、数组 = `List<Any?>`、
 * 数字 = `Double`（同 JS `typeof number`）、布尔 = `Boolean`、null = `null`。解析失败返回 [INVALID]。
 */
internal object JsonIn {
    val INVALID: Any = Any()

    fun parse(bytes: ByteArray): Any? = parse(String(bytes, Charsets.UTF_8))

    fun parse(text: String): Any? = try {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) INVALID else v
    } catch (e: JsonError) {
        INVALID
    } catch (e: NumberFormatException) {
        INVALID
    }

    fun obj(bytes: ByteArray): Map<String, Any?>? = asObj(parse(bytes))

    fun obj(text: String): Map<String, Any?>? = asObj(parse(text))

    @Suppress("UNCHECKED_CAST")
    fun asObj(v: Any?): Map<String, Any?>? = if (v is LinkedHashMap<*, *>) v as Map<String, Any?> else null

    fun asList(v: Any?): List<Any?>? = if (v is List<*>) v else null

    fun double(v: Any?): Double? = v as? Double

    fun bool(v: Any?): Boolean? = v as? Boolean

    fun string(v: Any?): String? = v as? String

    /** 有限、整数、|v| ≤ 2^53 − 1 的数（与 iOS `JSONIn.int64` 同口径）。 */
    fun int64(v: Any?): Long? {
        val d = v as? Double ?: return null
        if (d.isNaN() || d.isInfinite() || d != Math.rint(d) || abs(d) > 9_007_199_254_740_991.0) return null
        return d.toLong()
    }

    private class JsonError : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > 512 || i >= s.length) throw JsonError()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw JsonError()
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonError()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw JsonError()
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw JsonError()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == '}') {
                    i++
                    return m
                }
                throw JsonError()
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                ws()
                l.add(value(depth + 1))
                ws()
                if (i >= s.length) throw JsonError()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == ']') {
                    i++
                    return l
                }
                throw JsonError()
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonError()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonError()
                        when (s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError()
                                var v = 0
                                for (k in 0 until 4) {
                                    val h = Character.digit(s[i + k], 16)
                                    if (h < 0) throw JsonError()
                                    v = (v shl 4) or h
                                }
                                i += 4
                                sb.append(v.toChar())
                            }
                            else -> throw JsonError()
                        }
                    }
                    c.code < 0x20 -> throw JsonError()
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) throw JsonError()
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else {
                throw JsonError()
            }
            if (i < s.length && s[i] == '.') {
                i++
                val f = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == f) throw JsonError()
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val f = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == f) throw JsonError()
            }
            return s.substring(start, i).toDouble()
        }
    }
}

/**
 * 字节级小工具：在我们写出的 JSON 文本里找模式（字符串值内的 `"` 一律被转义，
 * 所以以 `"` 开头的键模式不会在字符串值内部误中）。
 */
internal object Bytes {
    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    fun find(pattern: ByteArray, hay: ByteArray, from: Int = 0, to: Int = hay.size): Int {
        if (pattern.isEmpty() || to - from < pattern.size) return -1
        val first = pattern[0]
        val last = to - pattern.size
        var i = from
        while (i <= last) {
            if (hay[i] == first) {
                var j = 1
                while (j < pattern.size && hay[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
    }

    fun contains(pattern: ByteArray, hay: ByteArray, from: Int = 0, to: Int = hay.size): Boolean =
        find(pattern, hay, from, to) >= 0

    fun hasSuffix(hay: ByteArray, from: Int, to: Int, suffix: ByteArray): Boolean {
        if (to - from < suffix.size) return false
        val base = to - suffix.size
        for (k in suffix.indices) if (hay[base + k] != suffix[k]) return false
        return true
    }

    fun startsWith(hay: ByteArray, from: Int, to: Int, prefix: ByteArray): Boolean {
        if (to - from < prefix.size) return false
        for (k in prefix.indices) if (hay[from + k] != prefix[k]) return false
        return true
    }
}
