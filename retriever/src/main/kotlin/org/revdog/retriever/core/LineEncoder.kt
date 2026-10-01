package org.revdog.retriever.core

import org.revdog.retriever.LogException
import org.revdog.retriever.LogLevel
import org.revdog.retriever.LogLine
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 行 JSON 手写编码（§3.1）。字段顺序固定：seq, oseq, ts, level, msg, tag, attrs, exc, ctx, synthetic, truncated；
 * 可选字段缺省不输出键。本编码器只产出 seq / oseq 之后的部分（`"ts":…}` + `\n`），seq / oseq 前缀在锁内拼上；
 * `ctx` 只在物化进信封时插入（段文件里没有）。与 iOS `LineEncoder.swift` 逐字一致。
 */
internal object LineEncoder {
    /** 行 JSON 里 body 之外可能出现的最大字节：`{"seq":<16 位>,"oseq":<16 位>,`（48 B）+ 物化时插入的 `"ctx":true,`（11 B）。 */
    const val RESERVED_BYTES = 48 + 11
    const val BODY_BUDGET = Limits.LINE_SERIALIZED_BYTES - RESERVED_BYTES

    class Encoded(
        /** `"ts":…}\n` */
        val body: ByteArray,
        val truncated: Boolean,
    )

    /**
     * 从宿主 Throwable 构造 exc（§3.1）：type = javaClass.name、message = message ?: ""、stack = 平台栈（空串视同无）。
     * `getMessage()` 抛异常（懒拼 message 的自定义异常）→ message 写占位串，返回值第二项 = true（该行标 truncated）；
     * 取栈抛任何东西 → 无栈。绝不抛给宿主（ADR 0024 决定 5）。
     */
    fun exception(t: Throwable, stackOf: (Throwable) -> String): Pair<LogException, Boolean> {
        var bad = false
        val message = try {
            t.message ?: ""
        } catch (e: Throwable) {
            bad = true
            ClientConstants.UNPRINTABLE
        }
        val stack = try {
            stackOf(t)
        } catch (e: Throwable) {
            ""
        }
        return Pair(LogException(t.javaClass.name, message, stack.ifEmpty { null }), bad)
    }

    /** `msg` 为 null 时的取值：有异常取 `toString()`（抛异常 → 占位串，second = true），否则空串。 */
    fun messageOf(msg: String?, error: Throwable?): Pair<String, Boolean> {
        if (msg != null) return Pair(msg, false)
        if (error == null) return Pair("", false)
        return try {
            Pair(error.toString(), false)
        } catch (e: Throwable) {
            Pair(ClientConstants.UNPRINTABLE, true)
        }
    }

    /**
     * 收编时给 redact 钩子看的行：把 pre 文件里的行体（`"ts":…}`，可带结尾 `\n`）解回 [LogLine]。
     * 返回 (行, synthetic, truncated)；解不出返回 null（该行按坏记录跳过）。attrs 的数字解成 Double（重编码时按 JS 数字格式，整数逐字节不变）。
     */
    fun decodeBody(body: ByteArray): Triple<LogLine, Boolean, Boolean>? {
        var end = body.size
        if (end > 0 && body[end - 1].toInt() == 0x0A) end -= 1
        val buf = ByteArray(end + 1)
        buf[0] = 0x7B
        System.arraycopy(body, 0, buf, 1, end)
        val o = JsonIn.obj(buf) ?: return null
        val ts = JsonIn.int64(o["ts"]) ?: return null
        val level = LogLevel.ofWire(o["level"] as? String) ?: return null
        val msg = o["msg"] as? String ?: return null
        val tag = o["tag"] as? String
        val attrs = JsonIn.asObj(o["attrs"])
        val exc = JsonIn.asObj(o["exc"])?.let { e ->
            LogException((e["type"] as? String) ?: "", (e["message"] as? String) ?: "", e["stack"] as? String)
        }
        return Triple(LogLine(ts, level, msg, tag, attrs, exc), o["synthetic"] == true, o["truncated"] == true)
    }

    /** `forceTruncated`：行在编码之前就已经丢过东西（值求值出错、收编前已截断）。 */
    fun encode(line: LogLine, synthetic: Boolean = false, forceTruncated: Boolean = false): Encoded {
        var truncated = forceTruncated

        val mc = Text.truncate(line.msg, Limits.LINE_MSG_BYTES)
        var msg = mc.s
        truncated = truncated || mc.truncated

        var tag: String? = null
        line.tag?.let {
            val tc = Text.truncate(it, Limits.LINE_TAG_BYTES)
            tag = tc.s
            truncated = truncated || tc.truncated
        }

        var attrsJson: ByteArray? = null
        val a = line.attrs
        if (a != null) {
            // 宿主的 map：size / 迭代都可能抛，全部在 encodeAttrs 里兜住
            val r = try {
                encodeAttrs(a)
            } catch (t: Throwable) {
                Pair(null, true)
            }
            attrsJson = r.first
            truncated = truncated || r.second
        }

        var exc: LogException? = null
        line.exc?.let { e ->
            val t1 = Text.truncate(e.type, Limits.LINE_EXC_TYPE_BYTES)
            val t2 = Text.truncate(e.message, Limits.LINE_EXC_MESSAGE_BYTES)
            var stack = e.stack
            var t3 = false
            if (stack != null && Text.utf8Len(stack) > Limits.LINE_EXC_STACK_BYTES) {
                stack = headTail(stack)
                t3 = true
            }
            exc = LogException(t1.s, t2.s, stack)
            truncated = truncated || t1.truncated || t2.truncated || t3
        }

        // 单行上限：超出先删 attrs、再截 stack、再截 msg（按转义后字节核算）。
        var body = build(line.ts, line.level, msg, tag, attrsJson, exc, synthetic, truncated)
        if (body.size - 1 > BODY_BUDGET && attrsJson != null) {
            attrsJson = null
            truncated = true
            body = build(line.ts, line.level, msg, tag, null, exc, synthetic, true)
        }
        val e0 = exc
        val s0 = e0?.stack
        if (body.size - 1 > BODY_BUDGET && e0 != null && s0 != null) {
            // 再截 stack：仍按「头 + 标记 + 尾」保住 `Caused by`，按转义后字节核算
            val excess = body.size - 1 - BODY_BUDGET
            val current = JsonOut.escapedLength(s0) - 2
            exc = e0.copy(stack = headTailEscaped(s0, current - excess))
            truncated = true
            body = build(line.ts, line.level, msg, tag, null, exc, synthetic, true)
        }
        if (body.size - 1 > BODY_BUDGET) {
            val excess = body.size - 1 - BODY_BUDGET
            val current = JsonOut.escapedLength(msg) - 2
            msg = Text.truncateEscaped(msg, max(current - excess, 0))
            truncated = true
            body = build(line.ts, line.level, msg, tag, null, exc, synthetic, true)
        }
        return Encoded(body, truncated)
    }

    /** stack 超长：头 8 KB + 标记 + 尾（8 KB − 标记字节，保证总长 ≤ 16 KB 的服务端硬上限）。 */
    fun headTail(s: String): String {
        val marker = ClientConstants.STACK_MARKER
        val head = Text.truncate(s, Limits.LINE_EXC_STACK_HEAD_BYTES).s
        val tailBudget = Limits.LINE_EXC_STACK_BYTES - Text.utf8Len(head) - Text.utf8Len(marker)
        val tail = Text.suffix(s, minOf(Limits.LINE_EXC_STACK_TAIL_BYTES, tailBudget))
        return head + marker + tail
    }

    /** 转义后 ≤ budget 的「头 + 标记 + 尾」；budget 连标记都放不下时返回 null（去掉 stack）。 */
    fun headTailEscaped(s: String, budget: Int): String? {
        val marker = ClientConstants.STACK_MARKER
        val markerEsc = JsonOut.escapedLength(marker) - 2
        val room = budget - markerEsc
        if (room < 2) return if (budget > 0) Text.truncateEscaped(s, budget) else null
        val head = Text.truncateEscaped(s, room / 2)
        val headEsc = JsonOut.escapedLength(head) - 2
        val tail = Text.suffixEscaped(s, room - headEsc)
        return head + marker + tail
    }

    /**
     * attrs：先对宿主的 map 取浅拷贝快照，且只取前 32 个非 null 键（迭代顺序；先截到上限再排序，百万级键不全排序）；
     * 键按字典序；贪心装入直到序列化 ≤ 4096 B。值：String / Boolean / null 原样；
     * 整数型（Byte / Short / Int / Long / AtomicInteger / AtomicLong / BigInteger / 无小数位的 BigDecimal）|v| ≤ 2^53 − 1
     * 输出 JSON 数字、超出输出十进制字符串（ADR 0020 决定 3，golden `attrs.json`；值没丢，不打 truncated）；
     * 其余 Number 按 JS 数字格式（非有限数转 "NaN" / "Infinity" / "-Infinity"）；
     * 数组 / Collection / Map 渲染成紧凑 JSON 文本作字符串值（有上限：文本超过 4096 B 跳过该键、嵌套超过 8 层写占位串；方案 §3.1）；
     * 其它 `toString()`。逐值防护（ADR 0024 决定 5）：单个值求值抛任何东西（toString / toDouble 抛异常、间接循环引用的
     * StackOverflowError）→ 该值写占位串 `"<unprintable>"` 并标 truncated，行照常落盘；拷贝快照时并发修改 → 已拿到的键照用、标 truncated。
     * 字符串先按预算截再转义：UTF-16 长度超过预算的键 / 值转义后必然超预算，直接跳过，不整串转义（超长值不做数倍分配）。
     * 返回 (JSON 或 null, 是否截断)。
     */
    fun encodeAttrs(attrs: Map<String, Any?>): Pair<ByteArray?, Boolean> {
        var truncated = false
        val keys = ArrayList<String>(minOf(attrs.size, Limits.LINE_ATTRS_KEYS))
        val vals = HashMap<String, Any?>()
        try {
            for (e in attrs.entries) {
                val k = e.key
                // Java 调用方可能塞 null 键：跳过（绝不因宿主的 map 抛异常丢整行）
                @Suppress("SENSELESS_COMPARISON")
                if (k == null) {
                    truncated = true
                    continue
                }
                if (keys.size >= Limits.LINE_ATTRS_KEYS) {
                    truncated = true
                    break
                }
                keys.add(k)
                vals[k] = e.value
            }
        } catch (t: Throwable) {
            // 并发修改 / 宿主 map 的迭代器抛异常
            truncated = true
        }
        keys.sort()
        val out = JsonOut(256)
        out.byte(0x7B)
        var count = 0
        for (k in keys) {
            if (k.length > Limits.LINE_ATTRS_BYTES) {
                truncated = true
                continue
            }
            var item = JsonOut(32)
            if (count > 0) item.raw(",")
            item.string(k)
            item.raw(":")
            val r = try {
                value(item, vals[k])
            } catch (t: Throwable) {
                item = JsonOut(32)
                if (count > 0) item.raw(",")
                item.string(k)
                item.raw(":")
                item.string(ClientConstants.UNPRINTABLE)
                VALUE_UNPRINTABLE
            }
            if (r == VALUE_SKIP) {
                truncated = true
                continue
            }
            if (r == VALUE_UNPRINTABLE) truncated = true
            if (out.size + item.size + 1 > Limits.LINE_ATTRS_BYTES) {
                truncated = true
                continue
            }
            out.raw(item.toByteArray())
            count += 1
        }
        if (count == 0) return Pair(null, truncated || keys.isNotEmpty())
        out.byte(0x7D)
        return Pair(out.toByteArray(), truncated)
    }

    private const val VALUE_OK = 0
    private const val VALUE_SKIP = 1
    private const val VALUE_UNPRINTABLE = 2

    /** 写一个 attrs 值；返回 OK / SKIP（放不下，跳过该键）/ UNPRINTABLE（写了占位串）。可能抛：调用方逐值兜住。 */
    private fun value(item: JsonOut, v: Any?): Int {
        when (v) {
            null -> item.raw("null")
            is Boolean -> item.bool(v)
            is Byte, is Short, is Int, is Long, is AtomicInteger, is AtomicLong -> integer(item, (v as Number).toLong())
            is BigInteger -> integer(item, v)
            is BigDecimal -> if (v.scale() <= 0) integer(item, v.toBigInteger()) else number(item, v)
            is Number -> number(item, v)
            is String -> {
                // 一个 UTF-16 单元至少 1 个 UTF-8 字节：长度超过预算的值放不下，先判再转义
                if (v.length > Limits.LINE_ATTRS_BYTES) return VALUE_SKIP
                item.string(v)
            }
            is CharSequence -> {
                if (v.length > Limits.LINE_ATTRS_BYTES) return VALUE_SKIP
                item.string(v.toString())
            }
            is Array<*>, is IntArray, is LongArray, is ShortArray, is ByteArray, is CharArray, is FloatArray, is DoubleArray,
            is BooleanArray, is Collection<*>, is Map<*, *> -> {
                val j = JsonOut(64)
                if (!render(j, v, 0)) return VALUE_SKIP
                if (j.size > Limits.LINE_ATTRS_BYTES) return VALUE_SKIP
                item.string(String(j.toByteArray(), Charsets.UTF_8))
            }
            else -> {
                val s = v.toString()
                if (s.length > Limits.LINE_ATTRS_BYTES) return VALUE_SKIP
                item.string(s)
            }
        }
        return VALUE_OK
    }

    private class TooDeep : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * 有上限的 JSON 渲染（数组 / 集合 → `[…]`、map → `{"k":…}`，元素规则同 attrs 值；其它对象 `toString()` 作字符串）。
     * 文本超过 4096 B 立即停止返回 false（调用方跳过该键）；嵌套超过 8 层（含循环引用）抛出 → 占位串。
     */
    private fun render(o: JsonOut, v: Any?, depth: Int): Boolean {
        if (o.size > Limits.LINE_ATTRS_BYTES) return false
        if (depth > ClientConstants.ATTR_RENDER_MAX_DEPTH) throw TooDeep()
        when (v) {
            null -> o.raw("null")
            is Boolean -> o.bool(v)
            is Byte, is Short, is Int, is Long, is AtomicInteger, is AtomicLong -> integer(o, (v as Number).toLong())
            is BigInteger -> integer(o, v)
            is BigDecimal -> if (v.scale() <= 0) integer(o, v.toBigInteger()) else number(o, v)
            is Number -> number(o, v)
            is CharSequence -> {
                if (v.length > Limits.LINE_ATTRS_BYTES) return false
                o.string(v.toString())
            }
            is Array<*> -> return seq(o, v.size, depth) { v[it] }
            is IntArray -> return seq(o, v.size, depth) { v[it] }
            is LongArray -> return seq(o, v.size, depth) { v[it] }
            is ShortArray -> return seq(o, v.size, depth) { v[it] }
            is ByteArray -> return seq(o, v.size, depth) { v[it] }
            is CharArray -> return seq(o, v.size, depth) { v[it].toString() }
            is FloatArray -> return seq(o, v.size, depth) { v[it] }
            is DoubleArray -> return seq(o, v.size, depth) { v[it] }
            is BooleanArray -> return seq(o, v.size, depth) { v[it] }
            is Collection<*> -> {
                o.byte(0x5B)
                var first = true
                for (x in v) {
                    if (!first) o.byte(0x2C)
                    first = false
                    if (!render(o, x, depth + 1)) return false
                }
                o.byte(0x5D)
            }
            is Map<*, *> -> {
                o.byte(0x7B)
                var first = true
                for ((k, x) in v) {
                    if (!first) o.byte(0x2C)
                    first = false
                    val ks = k?.toString() ?: "null"
                    if (ks.length > Limits.LINE_ATTRS_BYTES) return false
                    o.string(ks)
                    o.byte(0x3A)
                    if (!render(o, x, depth + 1)) return false
                }
                o.byte(0x7D)
            }
            else -> {
                val s = v.toString()
                if (s.length > Limits.LINE_ATTRS_BYTES) return false
                o.string(s)
            }
        }
        return o.size <= Limits.LINE_ATTRS_BYTES
    }

    private inline fun seq(o: JsonOut, n: Int, depth: Int, at: (Int) -> Any?): Boolean {
        o.byte(0x5B)
        for (i in 0 until n) {
            if (i > 0) o.byte(0x2C)
            if (!render(o, at(i), depth + 1)) return false
        }
        o.byte(0x5D)
        return o.size <= Limits.LINE_ATTRS_BYTES
    }

    /** 2^53 − 1：JS `Number.MAX_SAFE_INTEGER`，查看端 `JSON.parse` 能精确表示的最大整数。 */
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val MAX_SAFE_BIG: BigInteger = BigInteger.valueOf(MAX_SAFE_INTEGER)

    private fun integer(o: JsonOut, v: Long) {
        if (v in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) o.int(v) else o.string(v.toString())
    }

    private fun integer(o: JsonOut, v: BigInteger) {
        if (v.abs() <= MAX_SAFE_BIG) o.raw(v.toString()) else o.string(v.toString())
    }

    private fun number(o: JsonOut, v: Number) {
        val d = v.toDouble()
        if (d.isNaN()) {
            o.string("NaN")
        } else if (d.isInfinite()) {
            o.string(if (d > 0) "Infinity" else "-Infinity")
        } else {
            o.number(d)
        }
    }

    fun build(
        ts: Long,
        level: LogLevel,
        msg: String,
        tag: String?,
        attrs: ByteArray?,
        exc: LogException?,
        synthetic: Boolean,
        truncated: Boolean,
    ): ByteArray {
        val o = JsonOut(64 + msg.length * 3)
        o.raw("\"ts\":"); o.int(ts)
        o.raw(",\"level\":\""); o.raw(level.wire); o.raw("\"")
        o.raw(",\"msg\":"); o.string(msg)
        if (tag != null) {
            o.raw(",\"tag\":"); o.string(tag)
        }
        if (attrs != null) {
            o.raw(",\"attrs\":"); o.raw(attrs)
        }
        if (exc != null) {
            o.raw(",\"exc\":{\"type\":"); o.string(exc.type)
            o.raw(",\"message\":"); o.string(exc.message)
            exc.stack?.let { o.raw(",\"stack\":"); o.string(it) }
            o.raw("}")
        }
        if (synthetic) o.raw(",\"synthetic\":true")
        if (truncated) o.raw(",\"truncated\":true")
        o.raw("}\n")
        return o.toByteArray()
    }

    /** 行体开头固定为 `"ts":<整数>`：按位置取 ts（解析不出 = 0）。 */
    fun tsOf(body: ByteArray): Long {
        var i = 5
        if (body.size < 6 || body[0].toInt() != 0x22 || body[1].toInt() != 0x74) return 0
        var neg = false
        if (i < body.size && body[i].toInt() == 0x2D) {
            neg = true
            i++
        }
        var v = 0L
        var n = 0
        while (i < body.size && body[i] >= 0x30 && body[i] <= 0x39 && n < 18) {
            v = v * 10 + (body[i] - 0x30)
            i++
            n++
        }
        return if (neg) -v else v
    }

    /** 锁内拼前缀：`{"seq":N,` 或 `{"seq":N,"oseq":M,`。 */
    fun prefix(seq: Long, oseq: Long): ByteArray {
        val o = JsonOut(48)
        o.raw("{\"seq\":"); o.int(seq)
        if (oseq > 0) {
            o.raw(",\"oseq\":"); o.int(oseq)
        }
        o.raw(",")
        return o.toByteArray()
    }
}
