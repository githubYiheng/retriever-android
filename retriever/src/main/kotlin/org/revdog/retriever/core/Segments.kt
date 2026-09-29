package org.revdog.retriever.core

import java.io.File

/** 段文件的一行（读侧索引）。 */
internal class SegLine(
    val seq: Long,
    /** 0 = 非义务行 */
    val oseq: Long,
    val ts: Long,
    val levelRank: Int,
    /** 行字节在 data 里的范围 [start, end)（不含 `\n`） */
    val start: Int,
    val end: Int,
)

internal class SegmentHeader(val segNo: Int, val userId: String?, val startedMs: Long) {
    fun encode(): ByteArray {
        val o = JsonOut()
        o.raw("{\"v\":1,\"seg_no\":"); o.int(segNo)
        o.raw(",\"user_id\":"); o.stringOrNull(userId)
        o.raw(",\"started_ms\":"); o.int(startedMs)
        o.raw("}\n")
        return o.toByteArray()
    }
}

internal class SegmentFile(
    val file: File,
    val segNo: Int,
    var header: SegmentHeader?,
    var data: ByteArray,
    val lines: MutableList<SegLine>,
    /** 最后一个完整行（或 header）结束处的字节偏移（截残行用）。 */
    var validEnd: Int,
    /** 无法解析的块数（残行、全 0 块、坏 JSON）。 */
    var corruptChunks: Int,
) {
    /** 尾部残行里能认出的 (seq, oseq)（前缀在同一次 write 里先写，残行通常保住前缀）。 */
    var tornSeq: Long? = null
    var tornOseq: Long? = null

    val sealed: Boolean get() = file.name.endsWith(".sealed")
}

/** 段文件读侧（§3.3-5 / §3.4）：按 `\n` 切行、逐行校验，丢弃尾部残行与全 0 块并计 corrupt。与 iOS `Segments` 逐字一致。 */
internal object Segments {
    class Parsed(val segNo: Int, val isOpen: Boolean)

    class Prefix(val seq: Long, val oseq: Long, val ts: Long, val levelRank: Int)

    fun name(segNo: Int, open: Boolean): String {
        val n = segNo.toString()
        return "seg-" + "0".repeat(maxOf(0, 6 - n.length)) + n + (if (open) ".open" else ".sealed")
    }

    /** `seg-000123.open|.sealed` → (123, isOpen) */
    fun parseName(name: String): Parsed? {
        if (!name.startsWith("seg-")) return null
        val isOpen: Boolean
        val stem: String
        if (name.endsWith(".open")) {
            isOpen = true
            stem = name.substring(4, name.length - 5)
        } else if (name.endsWith(".sealed")) {
            isOpen = false
            stem = name.substring(4, name.length - 7)
        } else {
            return null
        }
        val n = stem.toIntOrNull() ?: return null
        if (n <= 0) return null
        return Parsed(n, isOpen)
    }

    /** 读段文件。`validate` = 逐行 JSON 校验（恢复孤儿段时用；平时只解析前缀）。 */
    fun read(file: File, validate: Boolean): SegmentFile? {
        val p = parseName(file.name) ?: return null
        val data = Fs.read(file) ?: return null
        val seg = SegmentFile(file, p.segNo, null, data, ArrayList(), 0, 0)
        var start = 0
        var first = true
        val n = data.size
        while (start < n) {
            var end = start
            while (end < n && data[end].toInt() != 0x0A) end++
            var s = start
            while (s < end && data[s].toInt() == 0) s++
            if (end == n) {
                // 尾部残行（没有 `\n` 结尾）：计 corrupt；前缀在同一次 write 里先写，尽量认出 seq / oseq
                seg.corruptChunks += 1
                if (s < end) {
                    val pre = parsePrefix(data, s, end)
                    if (pre != null) {
                        seg.tornSeq = pre.seq
                        seg.tornOseq = pre.oseq
                    } else {
                        val so = parseSeqOnly(data, s, end)
                        if (so != null) {
                            seg.tornSeq = so.first
                            seg.tornOseq = so.second
                        }
                    }
                }
                break
            }
            seg.validEnd = end + 1
            if (s > start) seg.corruptChunks += 1 // 全 0 块
            if (s == end) {
                start = end + 1
                continue
            }
            if (first) {
                first = false
                if (s == start) {
                    val h = parseHeader(data, s, end)
                    if (h != null) {
                        seg.header = h
                        start = end + 1
                        continue
                    }
                }
            }
            val pre = parsePrefix(data, s, end)
            if (pre != null && (!validate || isValidJson(data, s, end))) {
                seg.lines.add(SegLine(pre.seq, pre.oseq, pre.ts, pre.levelRank, s, end))
            } else {
                seg.corruptChunks += 1
            }
            start = end + 1
        }
        return seg
    }

    fun isValidJson(data: ByteArray, s: Int, e: Int): Boolean = JsonIn.obj(data.copyOfRange(s, e)) != null

    private val HEADER_PREFIX = Bytes.ascii("{\"v\":")

    fun parseHeader(data: ByteArray, s: Int, e: Int): SegmentHeader? {
        if (!Bytes.startsWith(data, s, e, HEADER_PREFIX)) return null
        val o = JsonIn.obj(data.copyOfRange(s, e)) ?: return null
        val segNo = JsonIn.int64(o["seg_no"]) ?: return null
        return SegmentHeader(segNo.toInt(), o["user_id"] as? String, JsonIn.int64(o["started_ms"]) ?: 0)
    }

    private val P_SEQ = Bytes.ascii("{\"seq\":")
    private val P_OSEQ = Bytes.ascii("\"oseq\":")
    private val P_TS = Bytes.ascii("\"ts\":")
    private val P_LEVEL = Bytes.ascii(",\"level\":\"")

    /** 解析我方写出的固定前缀 `{"seq":N[,"oseq":M],"ts":T,"level":"L"`。 */
    fun parsePrefix(d: ByteArray, from: Int, end: Int): Prefix? {
        var i = from
        fun expect(p: ByteArray): Boolean {
            if (end - i < p.size) return false
            for (k in p.indices) if (d[i + k] != p[k]) return false
            i += p.size
            return true
        }
        fun number(): Long? {
            var v = 0L
            var any = false
            while (i < end && d[i] >= 0x30 && d[i] <= 0x39) {
                v = v * 10 + (d[i] - 0x30)
                i++
                any = true
            }
            return if (any) v else null
        }
        if (!expect(P_SEQ)) return null
        val seq = number() ?: return null
        if (i >= end || d[i].toInt() != 0x2C) return null
        i++
        var oseq = 0L
        if (expect(P_OSEQ)) {
            val o = number() ?: return null
            if (i >= end || d[i].toInt() != 0x2C) return null
            oseq = o
            i++
        }
        if (!expect(P_TS)) return null
        val ts = number() ?: return null
        if (!expect(P_LEVEL)) return null
        var j = i
        while (j < end && d[j].toInt() != 0x22) j++
        if (j >= end) return null
        val rank = Level.rankOf(String(d, i, j - i, Charsets.US_ASCII))
        if (rank < 0) return null
        return Prefix(seq, oseq, ts, rank)
    }

    /** 残行只剩前缀一部分时尽量认出 seq / oseq。 */
    fun parseSeqOnly(d: ByteArray, from: Int, end: Int): Pair<Long, Long>? {
        if (!Bytes.startsWith(d, from, end, P_SEQ)) return null
        var i = from + P_SEQ.size
        var seq = 0L
        var any = false
        while (i < end && d[i] >= 0x30 && d[i] <= 0x39) {
            seq = seq * 10 + (d[i] - 0x30)
            i++
            any = true
        }
        if (!any || i >= end || d[i].toInt() != 0x2C) return null
        i++
        var oseq = 0L
        if (Bytes.startsWith(d, i, end, P_OSEQ)) {
            i += P_OSEQ.size
            var v = 0L
            var got = false
            while (i < end && d[i] >= 0x30 && d[i] <= 0x39) {
                v = v * 10 + (d[i] - 0x30)
                i++
                got = true
            }
            // 数字后必须跟逗号才可信（否则可能被截在数字中间）
            if (got && i < end && d[i].toInt() == 0x2C) oseq = v
        }
        return Pair(seq, oseq)
    }

    private val CTX_INSERT_BEFORE: List<ByteArray> = listOf(
        Bytes.ascii(",\"synthetic\":true,\"truncated\":true}"),
        Bytes.ascii(",\"synthetic\":true}"),
        Bytes.ascii(",\"truncated\":true}"),
    )
    private val CTX_FIELD = Bytes.ascii(",\"ctx\":true")

    /**
     * 物化 ctx 行：在固定位置（exc 之后、synthetic / truncated 之前）插入 `"ctx":true`。
     * 段里的非义务行没有 oseq，字节原样保留，只插一个字段。
     */
    fun withCtx(raw: ByteArray, s: Int, e: Int): ByteArray {
        for (suf in CTX_INSERT_BEFORE) {
            if (Bytes.hasSuffix(raw, s, e, suf)) {
                val cut = e - suf.size
                val out = JsonOut(e - s + CTX_FIELD.size)
                out.raw(raw, s, cut - s)
                out.raw(CTX_FIELD)
                out.raw(suf)
                return out.toByteArray()
            }
        }
        val out = JsonOut(e - s + CTX_FIELD.size)
        out.raw(raw, s, e - 1 - s)
        out.raw(CTX_FIELD)
        out.byte(0x7D)
        return out.toByteArray()
    }
}
