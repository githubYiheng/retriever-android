package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.LineEncoder
import org.revdog.retriever.core.Limits
import org.revdog.retriever.core.Segments
import org.revdog.retriever.core.Text

/** UTF-8 截断（§3.1）：按字节、只在码点边界；单行序列化 ≤ 16 KB。与 iOS TruncationTests 同一组用例。 */
class TruncationTests {
    private fun line(level: LogLevel, msg: String, tag: String? = null, attrs: Map<String, Any?>? = null, exc: LogException? = null, ts: Long = 1) =
        LogLine(ts, level, msg, tag, attrs, exc)

    private fun decode(e: LineEncoder.Encoded, seq: Long = 9_007_199_254_740_991, oseq: Long = 9_007_199_254_740_991): Pair<Map<String, Any?>, Int> {
        val full = LineEncoder.prefix(seq, oseq) + e.body.copyOfRange(0, e.body.size - 1)
        // 物化 ctx 行时再插一个字段：上限也要容得下
        val withCtx = Segments.withCtx(full, 0, full.size)
        return Pair(JsonIn.obj(withCtx)!!, withCtx.size)
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun msgCjkTruncatedAtCodePoint() {
        val e = LineEncoder.encode(line(LogLevel.INFO, "日".repeat(1400))) // 4200 B
        assertTrue(e.truncated)
        val (o, _) = decode(e)
        val m = o["msg"] as String
        assertEquals(4095, utf8(m)) // 1365 × 3 B，第 1366 个放不下
        assertEquals(true, o["truncated"])
        assertTrue(m.all { it == '日' })
    }

    @Test
    fun emojiNotSplit() {
        val e = LineEncoder.encode(line(LogLevel.INFO, "a" + "🐶".repeat(1100), tag = "🐱".repeat(20)))
        val (o, _) = decode(e)
        assertEquals(1 + 1023 * 4, utf8(o["msg"] as String))
        assertEquals(64, utf8(o["tag"] as String))
        assertEquals(16, (o["tag"] as String).codePointCount(0, (o["tag"] as String).length))
    }

    @Test
    fun loneSurrogateBecomesReplacementChar() {
        val broken = "a\uD83Db"
        assertEquals(5, Text.utf8Len(broken)) // 与服务端 utf8Len 一致：孤立代理项 3 B
        val e = LineEncoder.encode(line(LogLevel.INFO, broken))
        val (o, _) = decode(e)
        assertEquals("a�b", o["msg"])
        assertEquals(5, Text.utf8Len("a�b"))
        assertEquals("a�b", String(Text.utf8(broken), Charsets.UTF_8))
    }

    @Test
    fun controlCharsEscapedLikeJsonStringify() {
        val e = LineEncoder.encode(line(LogLevel.INFO, "q\"b\\n\n t\t\u0001\u007f/ "))
        val s = String(e.body, Charsets.UTF_8)
        assertTrue(s, s.contains("\"msg\":\"q\\\"b\\\\n\\n t\\t\\u0001"))
        assertTrue(s, s.contains("\u007f/ \""))
    }

    @Test
    fun stackHeadAndTail() {
        val head = "h".repeat(10_000)
        val tail = "t".repeat(9_000) + "Caused by: IOError"
        val e = LineEncoder.encode(line(LogLevel.ERROR, "x", exc = LogException("T", "m", head + tail)))
        val (o, _) = decode(e)
        val stack = obj(o["exc"])["stack"] as String
        assertTrue(utf8(stack) <= Limits.LINE_EXC_STACK_BYTES)
        // 字段级：头 8 KB + 标记 + 尾（总长 ≤ 16 KB）
        val field = LineEncoder.headTail(head + tail)
        assertTrue(field.startsWith("h".repeat(8192) + "\n…[truncated]…\n"))
        assertEquals(Limits.LINE_EXC_STACK_BYTES, utf8(field))
        // 行级：满额 stack 放不进 16 KB 的行，再按「头 + 标记 + 尾」收缩，尾部 `Caused by` 保住
        assertTrue(stack.startsWith("h".repeat(7900)))
        assertTrue(stack.contains("\n…[truncated]…\n"))
        assertTrue(stack.endsWith("Caused by: IOError"))
        assertEquals(true, o["truncated"])
    }

    @Test
    fun excFieldLimits() {
        val e = LineEncoder.encode(line(LogLevel.ERROR, "x", exc = LogException("類".repeat(100), "m".repeat(2000), null)))
        val (o, _) = decode(e)
        val exc = obj(o["exc"])
        assertTrue(utf8(exc["type"] as String) <= 256)
        assertEquals(1024, utf8(exc["message"] as String))
        assertNull(exc["stack"])
    }

    @Test
    fun attrsKeysAndBytesLimits() {
        val attrs = LinkedHashMap<String, Any?>()
        for (i in 0 until 40) attrs[String.format("k%02d", i)] = i.toDouble()
        attrs["inf"] = Double.POSITIVE_INFINITY
        attrs["nan"] = Double.NaN
        val (o, _) = decode(LineEncoder.encode(line(LogLevel.INFO, "x", attrs = attrs)))
        assertEquals(32, obj(o["attrs"]).size)
        assertEquals(true, o["truncated"])
        // 非有限数转 string；其它类型 toString
        val (o2, _) = decode(
            LineEncoder.encode(
                line(
                    LogLevel.INFO, "x",
                    attrs = mapOf("inf" to Float.POSITIVE_INFINITY, "nan" to Double.NaN, "n" to Double.NEGATIVE_INFINITY, "l" to 42L, "obj" to listOf(1, 2)),
                ),
            ),
        )
        val a2 = obj(o2["attrs"])
        assertEquals("Infinity", a2["inf"])
        assertEquals("NaN", a2["nan"])
        assertEquals("-Infinity", a2["n"])
        assertEquals(42.0, a2["l"])
        // 集合渲染成紧凑 JSON 文本（0.3.0：原先是 toString 的 "[1, 2]"；简报 §5）
        assertEquals("[1,2]", a2["obj"])
        // Java map 里的 null 键跳过（标 truncated），其它键照常
        @Suppress("UNCHECKED_CAST")
        val withNullKey = (java.util.HashMap<String?, Any?>().apply { put(null, 1); put("ok", 2) } as Map<String, Any?>)
        val (o4, _) = decode(LineEncoder.encode(line(LogLevel.INFO, "x", attrs = withNullKey)))
        assertEquals(mapOf("ok" to 2.0), obj(o4["attrs"]))
        assertEquals(true, o4["truncated"])
        // 序列化 ≤ 4096：贪心装入
        val big = mapOf("a" to "x".repeat(3000), "b" to "y".repeat(3000), "c" to true)
        val (o3, _) = decode(LineEncoder.encode(line(LogLevel.INFO, "x", attrs = big)))
        val a3 = obj(o3["attrs"])
        assertEquals(setOf("a", "c"), a3.keys)
        assertTrue(toJson(a3).toByteArray().size <= 4096)
    }

    @Test
    fun serializedLineCapped16KB() {
        // attrs 4 KB + stack 16 KB（控制字符，转义 6 倍）+ msg 4 KB ASCII → 先删 attrs、再截 stack，msg 保住
        val attrs = LinkedHashMap<String, Any?>()
        for (i in 0 until 8) attrs["k$i"] = "a".repeat(450)
        val l = LogLine(
            1_790_668_800_000, LogLevel.ERROR, "m".repeat(4000), "t".repeat(64), attrs,
            LogException("T".repeat(256), "\u0002".repeat(1024), "HEAD" + "\u0003".repeat(20_000) + "Caused by: X"),
        )
        val (o, n) = decode(LineEncoder.encode(l))
        assertTrue(n <= Limits.LINE_SERIALIZED_BYTES)
        assertNull("attrs 先整个删掉", o["attrs"])
        assertEquals(true, o["truncated"])
        assertEquals(4000, utf8(o["msg"] as String))
        val stack = obj(o["exc"])["stack"] as String
        assertTrue(stack.startsWith("HEAD"))
        assertTrue(stack.endsWith("Caused by: X"))

        // msg 本身转义后就超（4000 个控制字符 = 24 KB）→ stack 去掉后再截 msg
        val l2 = LogLine(1, LogLevel.ERROR, "\u0001".repeat(4000), null, null, LogException("T", "m", "s".repeat(5000)))
        val (o2, n2) = decode(LineEncoder.encode(l2))
        assertTrue(n2 <= Limits.LINE_SERIALIZED_BYTES)
        assertTrue((o2["msg"] as String).length < 4000)
        assertEquals(true, o2["truncated"])
    }

    /**
     * 超长 attrs 值先按预算判再转义（ADR 0020 决定 3）：20 MB 的值该键被跳过并打 truncated，其它键照常，耗时有上限
     * （旧实现先整串转义，按 6 倍分配）。
     */
    @Test
    fun hugeAttrValueBounded() {
        val huge = "\"".repeat(20 * 1024 * 1024)
        val attrs = mapOf("a" to "kept", "huge" to huge, "z" to 7L)
        val t0 = System.nanoTime()
        val (json, truncated) = LineEncoder.encodeAttrs(attrs)
        val took = System.nanoTime() - t0
        assertEquals("{\"a\":\"kept\",\"z\":7}", String(json!!, Charsets.UTF_8))
        assertTrue(truncated)
        assertTrue("took ${took / 1_000_000} ms", took < 20_000_000)
        // 整行编码同样有上限
        val t1 = System.nanoTime()
        val e = LineEncoder.encode(line(LogLevel.INFO, "x", attrs = mapOf("huge" to StringBuilder(huge))))
        assertTrue(System.nanoTime() - t1 < 200_000_000)
        val (o, _) = decode(e)
        assertNull(o["attrs"])
        assertEquals(true, o["truncated"])
    }

    /** 与服务端逐字节核算：validator 的 JSON.stringify(行) ≤ 16384（含 ctx 字段与最大位数的 seq / oseq）。 */
    @Test
    fun worstCaseLinesFitServerLimit() {
        for (ch in listOf("a", "\u0001", "日", "🐶", "\"", "\uD800")) {
            val l = LogLine(
                9_999_999_999_999, LogLevel.FATAL, ch.repeat(5000), ch.repeat(100), mapOf("x" to ch.repeat(5000)),
                LogException(ch.repeat(300), ch.repeat(2000), ch.repeat(30_000)),
            )
            val (_, n) = decode(LineEncoder.encode(l, synthetic = true))
            assertTrue(ch, n <= Limits.LINE_SERIALIZED_BYTES)
        }
    }

    @Test
    fun truncateHelpers() {
        assertEquals("ab", Text.truncate("abc", 2).s)
        assertEquals("", Text.truncate("é", 1).s)
        assertEquals(3, utf8(Text.suffix("xyz日本", 4)))
        assertEquals("🐶", Text.suffix("a🐶", 4))
        assertEquals("u1", Text.sanitizeUserId("u\u0000\u00851"))
        assertEquals(126, utf8(Text.sanitizeUserId("用".repeat(50))!!))
        assertEquals("a�", Text.sanitizeUserId("a\uDC00"))
    }
}
