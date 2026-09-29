package org.revdog.retriever.core

import org.revdog.retriever.LogException
import org.revdog.retriever.LogLevel
import org.revdog.retriever.LogLine
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

    /** 从宿主 Throwable 构造 exc（§3.1）：type = javaClass.name、message = message ?: ""、stack = 平台栈（空串视同无）。 */
    fun exception(t: Throwable, stackOf: (Throwable) -> String): LogException {
        val stack = try {
            stackOf(t)
        } catch (e: RuntimeException) {
            ""
        }
        return LogException(t.javaClass.name, t.message ?: "", stack.ifEmpty { null })
    }

    fun encode(line: LogLine, synthetic: Boolean = false): Encoded {
        var truncated = false

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
        if (a != null && a.isNotEmpty()) {
            val r = encodeAttrs(a)
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
     * attrs：键按字典序；≤ 32 键；贪心装入直到序列化 ≤ 4096 B。值：String / Boolean / null 原样，
     * Number 按 JS 数字格式（非有限数转 "NaN" / "Infinity" / "-Infinity"），其它 `toString()`。
     * 返回 (JSON 或 null, 是否截断)。
     */
    fun encodeAttrs(attrs: Map<String, Any?>): Pair<ByteArray?, Boolean> {
        var truncated = false
        // Java 调用方可能塞 null 键：跳过（绝不因宿主的 map 抛异常丢整行）
        val present = ArrayList<String>(attrs.size)
        for (k in attrs.keys) {
            @Suppress("SENSELESS_COMPARISON")
            if (k != null) present.add(k)
        }
        if (present.size != attrs.size) truncated = true
        var keys: List<String> = present.sorted()
        if (keys.size > Limits.LINE_ATTRS_KEYS) {
            keys = keys.subList(0, Limits.LINE_ATTRS_KEYS)
            truncated = true
        }
        val out = JsonOut(256)
        out.byte(0x7B)
        var count = 0
        for (k in keys) {
            val item = JsonOut(32)
            if (count > 0) item.raw(",")
            item.string(k)
            item.raw(":")
            when (val v = attrs[k]) {
                null -> item.raw("null")
                is String -> item.string(v)
                is Boolean -> item.bool(v)
                is Number -> {
                    val d = v.toDouble()
                    if (d.isNaN()) {
                        item.string("NaN")
                    } else if (d.isInfinite()) {
                        item.string(if (d > 0) "Infinity" else "-Infinity")
                    } else {
                        item.number(d)
                    }
                }
                else -> item.string(v.toString())
            }
            if (out.size + item.size + 1 > Limits.LINE_ATTRS_BYTES) {
                truncated = true
                continue
            }
            out.raw(item.toByteArray())
            count += 1
        }
        if (count == 0) return Pair(null, truncated || attrs.isNotEmpty())
        out.byte(0x7D)
        return Pair(out.toByteArray(), truncated)
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
