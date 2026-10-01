package org.revdog.retriever.core

import java.io.File
import java.io.FileOutputStream

// 本地状态文件与信封的数据模型（§3.2 / §3.5 / §3.6；字段名与顺序取 packages/core/src/envelope.ts，与 iOS Model.swift 逐字一致）。

/** 设备快照（进 meta.json 与信封 device；每个字段 ≤ 128 B）。 */
internal data class Device(
    val os: String,
    val osVersion: String,
    val model: String,
    val appVersion: String,
    val build: String,
    val locale: String,
    val sdk: String,
) {
    fun sanitized(): Device {
        fun t(s: String) = Text.truncate(Text.fixSurrogates(s), Limits.DEVICE_FIELD_BYTES).s
        return Device(t(os), t(osVersion), t(model), t(appVersion), t(build), t(locale), t(sdk))
    }

    fun encode(o: JsonOut) {
        o.raw("{\"os\":"); o.string(os)
        o.raw(",\"os_version\":"); o.string(osVersion)
        o.raw(",\"model\":"); o.string(model)
        o.raw(",\"app_version\":"); o.string(appVersion)
        o.raw(",\"build\":"); o.string(build)
        o.raw(",\"locale\":"); o.string(locale)
        o.raw(",\"sdk\":"); o.string(sdk)
        o.raw("}")
    }

    companion object {
        fun decode(v: Any?): Device? {
            val d = JsonIn.asObj(v) ?: return null
            fun s(k: String) = d[k] as? String
            return Device(
                s("os") ?: return null, s("os_version") ?: return null, s("model") ?: return null,
                s("app_version") ?: return null, s("build") ?: return null, s("locale") ?: return null,
                s("sdk") ?: return null,
            )
        }
    }
}

/** install.json：{install_id, session_counter, created_ms} */
internal data class InstallInfo(val installId: String, var sessionCounter: Long, val createdMs: Long) {
    fun encode(): ByteArray {
        val o = JsonOut()
        o.raw("{\"install_id\":"); o.string(installId)
        o.raw(",\"session_counter\":"); o.int(sessionCounter)
        o.raw(",\"created_ms\":"); o.int(createdMs)
        o.raw("}")
        return o.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): InstallInfo? {
            val o = JsonIn.obj(b) ?: return null
            val id = o["install_id"] as? String ?: return null
            if (!Ids.isUuid(id)) return null
            return InstallInfo(id, JsonIn.int64(o["session_counter"]) ?: 0, JsonIn.int64(o["created_ms"]) ?: 0)
        }
    }
}

/**
 * meta.json：会话开始时原子写。`install_id`（可选键，ADR 0019 决定 6）= bootstrap 时的 install 身份冗余副本，
 * install.json 损坏时据此修复；0.1.x 写的 meta 没有这个键（读作 null，不当副本）。
 */
internal data class SessionMeta(
    val sessionId: String,
    val sessionNo: Long,
    val startedMs: Long,
    val device: Device,
    val process: String,
    val installId: String? = null,
    /**
     * 可选键 `pre`（ADR 0023）：本会话正在收编的 pre 文件名（`<uuid>.jsonl`，在 `<root>/pre/` 下）。
     * 所指文件仍在 = 收编未提交（恢复时清掉已写的段与游标、从 pre 文件重做）；不在 = 已提交。提交后清掉。旧版忽略。
     */
    val pre: String? = null,
) {
    fun encode(): ByteArray {
        val o = JsonOut()
        o.raw("{\"session_id\":"); o.string(sessionId)
        o.raw(",\"session_no\":"); o.int(sessionNo)
        o.raw(",\"started_ms\":"); o.int(startedMs)
        o.raw(",\"device\":"); device.encode(o)
        o.raw(",\"process\":"); o.string(process)
        installId?.let { o.raw(",\"install_id\":"); o.string(it) }
        pre?.let { o.raw(",\"pre\":"); o.string(it) }
        o.raw("}")
        return o.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): SessionMeta? {
            val o = JsonIn.obj(b) ?: return null
            val sid = o["session_id"] as? String ?: return null
            if (!Ids.isUuid(sid)) return null
            val no = JsonIn.int64(o["session_no"]) ?: return null
            if (no < 1) return null
            val dev = Device.decode(o["device"]) ?: return null
            val iid = (o["install_id"] as? String)?.takeIf { Ids.isUuid(it) }
            val pre = (o["pre"] as? String)?.takeIf { isPreName(it) }
            return SessionMeta(sid, no, JsonIn.int64(o["started_ms"]) ?: 0, dev, (o["process"] as? String) ?: "main", iid, pre)
        }

        /** `<小写 uuid>.jsonl`（只认这个形状：meta 里的名字会被拼进路径）。 */
        fun isPreName(s: String): Boolean = s.length == 42 && s.endsWith(".jsonl") && Ids.isUuid(s.substring(0, 36))
    }
}

/**
 * cursor.json：{extracted_through_oseq, ctx_through_seq, last_state: fg|bg, last_state_ms, closed_ms?}（原子写）。
 * `closed_ms`：恢复流程把旧会话终态写进 sessions.jsonl 之后打的「已结束」标记（同 iOS）。
 */
internal data class Cursor(
    var extractedThroughOseq: Long = 0,
    var ctxThroughSeq: Long = 0,
    var lastState: String? = null,
    var lastStateMs: Long = 0,
    var closedMs: Long? = null,
) {
    fun encode(): ByteArray {
        val o = JsonOut()
        o.raw("{\"extracted_through_oseq\":"); o.int(extractedThroughOseq)
        o.raw(",\"ctx_through_seq\":"); o.int(ctxThroughSeq)
        o.raw(",\"last_state\":"); o.stringOrNull(lastState)
        o.raw(",\"last_state_ms\":"); o.int(lastStateMs)
        closedMs?.let { o.raw(",\"closed_ms\":"); o.int(it) }
        o.raw("}")
        return o.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): Cursor? {
            val o = JsonIn.obj(b) ?: return null
            return Cursor(
                JsonIn.int64(o["extracted_through_oseq"]) ?: 0,
                JsonIn.int64(o["ctx_through_seq"]) ?: 0,
                o["last_state"] as? String,
                JsonIn.int64(o["last_state_ms"]) ?: 0,
                JsonIn.int64(o["closed_ms"]),
            )
        }
    }
}

/** 一个段的索引（封段时由写入侧统计；恢复 / 旧会话由读文件重建）。 */
internal data class SegInfo(
    val segNo: Int,
    var userId: String?,
    val startedMs: Long,
    var file: File,
    var firstSeq: Long = 0,
    var lastSeq: Long = 0,
    var firstOseq: Long = 0,
    var lastOseq: Long = 0,
    var lineCount: Int = 0,
    var obligCount: Int = 0,
    var hasError: Boolean = false,
    var bytes: Long = 0,
    /** 写入侧统计（同 iOS SegInfo）：error 及以上的行数、首末 ts——段随目录消失时按它并入没落盘的行计数。 */
    var errorLines: Int = 0,
    var firstTs: Long = 0,
    var lastTs: Long = 0,
) {
    companion object {
        fun from(f: SegmentFile, file: File): SegInfo {
            val s = SegInfo(f.segNo, f.header?.userId, f.header?.startedMs ?: 0, file)
            for (l in f.lines) {
                if (s.firstSeq == 0L) s.firstSeq = l.seq
                s.lastSeq = maxOf(s.lastSeq, l.seq)
                s.lineCount += 1
                if (l.oseq > 0) {
                    if (s.firstOseq == 0L) s.firstOseq = l.oseq
                    s.lastOseq = maxOf(s.lastOseq, l.oseq)
                    s.obligCount += 1
                    if (l.levelRank >= Level.ERROR) s.hasError = true
                }
            }
            s.bytes = f.data.size.toLong()
            return s
        }
    }
}

/** 级别序（debug < info < warn < error < fatal）；线上值 lower_snake。 */
internal object Level {
    const val DEBUG = 0
    const val INFO = 1
    const val WARN = 2
    const val ERROR = 3
    const val FATAL = 4
    val WIRE = arrayOf("debug", "info", "warn", "error", "fatal")

    fun rankOf(wire: String): Int = WIRE.indexOf(wire)
}

internal enum class SealReason { SIZE, USER, BACKGROUND, ERROR, FATAL, TIMER, FLUSH, FULL_DUMP, UPLOAD_ENABLED, SHUTDOWN, RECOVERY }

/** 封段任务：锁内换段时生成，引擎线程里 sync + rename + 物化。 */
internal class SealJob(
    val out: FileOutputStream?,
    val info: SegInfo,
    val reason: SealReason,
    /** flush(includeContext = false)：本次物化不附 ctx。 */
    val noCtx: Boolean,
    /** 换段时刻的全局 seq / oseq（物化的上界）。 */
    val seqAtSeal: Long,
    val oseqAtSeal: Long,
)

internal object DropReason {
    const val BUFFER_OVERFLOW = "buffer_overflow"
    const val WRITE_FAILED = "write_failed"
    const val CORRUPT = "corrupt"
    const val BACKFILL_EVICTED = "backfill_evicted"
    const val QUARANTINE_EVICTED = "quarantine_evicted"
}

/** 墓碑：{session_id, oseq_from, oseq_to, n, reason, at_ms, last_ack_age_ms} */
internal data class DropEntry(
    val sessionId: String,
    val oseqFrom: Long,
    val oseqTo: Long,
    val n: Long,
    val reason: String,
    val atMs: Long,
    val lastAckAgeMs: Long,
) {
    fun encode(o: JsonOut) {
        o.raw("{\"session_id\":"); o.string(sessionId)
        o.raw(",\"oseq_from\":"); o.int(oseqFrom)
        o.raw(",\"oseq_to\":"); o.int(oseqTo)
        o.raw(",\"n\":"); o.int(n)
        o.raw(",\"reason\":"); o.string(reason)
        o.raw(",\"at_ms\":"); o.int(atMs)
        o.raw(",\"last_ack_age_ms\":"); o.int(lastAckAgeMs)
        o.raw("}")
    }

    companion object {
        fun decode(v: Any?): DropEntry? {
            val o = JsonIn.asObj(v) ?: return null
            val sid = o["session_id"] as? String ?: return null
            val r = o["reason"] as? String ?: return null
            val f = JsonIn.int64(o["oseq_from"]) ?: return null
            val t = JsonIn.int64(o["oseq_to"]) ?: return null
            val n = JsonIn.int64(o["n"]) ?: return null
            return DropEntry(sid, f, t, n, r, JsonIn.int64(o["at_ms"]) ?: 0, JsonIn.int64(o["last_ack_age_ms"]) ?: -1)
        }
    }
}

internal object SessionExit {
    const val CLEAN_BG = "clean_bg"
    const val UNCLEAN_FG = "unclean_fg"
    const val UNKNOWN = "unknown"
}

/** 会话终态：{session_id, session_no, started_ms, ended_ms, last_seq, last_oseq, exit} */
internal data class ClosedSession(
    val sessionId: String,
    val sessionNo: Long,
    val startedMs: Long,
    val endedMs: Long,
    val lastSeq: Long,
    val lastOseq: Long,
    val exit: String,
) {
    fun encode(o: JsonOut) {
        o.raw("{\"session_id\":"); o.string(sessionId)
        o.raw(",\"session_no\":"); o.int(sessionNo)
        o.raw(",\"started_ms\":"); o.int(startedMs)
        o.raw(",\"ended_ms\":"); o.int(endedMs)
        o.raw(",\"last_seq\":"); o.int(lastSeq)
        o.raw(",\"last_oseq\":"); o.int(lastOseq)
        o.raw(",\"exit\":"); o.string(exit)
        o.raw("}")
    }

    companion object {
        fun decode(v: Any?): ClosedSession? {
            val o = JsonIn.asObj(v) ?: return null
            val sid = o["session_id"] as? String ?: return null
            val no = JsonIn.int64(o["session_no"]) ?: return null
            val ex = o["exit"] as? String ?: return null
            return ClosedSession(
                sid, no, JsonIn.int64(o["started_ms"]) ?: 0, JsonIn.int64(o["ended_ms"]) ?: 0,
                JsonIn.int64(o["last_seq"]) ?: 0, JsonIn.int64(o["last_oseq"]) ?: 0, ex,
            )
        }
    }
}

/** jsonl 文件（drops.jsonl / sessions.jsonl）的读写。 */
internal object Jsonl {
    fun read(f: File): List<Map<String, Any?>> {
        val b = Fs.read(f) ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        var start = 0
        for (i in b.indices) {
            if (b[i].toInt() == 0x0A) {
                if (i > start) JsonIn.obj(b.copyOfRange(start, i))?.let { out.add(it) }
                start = i + 1
            }
        }
        return out
    }

    fun encodeDrops(d: List<DropEntry>): ByteArray {
        val o = JsonOut()
        for (e in d) {
            e.encode(o)
            o.raw("\n")
        }
        return o.toByteArray()
    }

    fun encodeClosed(c: List<ClosedSession>): ByteArray {
        val o = JsonOut()
        for (e in c) {
            e.encode(o)
            o.raw("\n")
        }
        return o.toByteArray()
    }
}

/**
 * mapping.json：{user_id, device_digest, acked_ms, key_fp?, base_url?}（上次被服务端确认的映射）。
 * `key_fp` / `base_url`（ADR 0024 决定 7）= 确认它的那次请求所用的 key 指纹与 baseUrl；与当前不同（含旧文件没有这两个键）= 未确认，重发。
 */
internal data class MappingState(
    val userId: String?,
    val deviceDigest: String,
    val ackedMs: Long,
    val keyFp: String? = null,
    val baseUrl: String? = null,
    /** 文件里有 key_fp / base_url 键（旧版本写的两个都没有 = 视为当前目标）。 */
    val hasIdentity: Boolean = true,
) {
    fun encode(): ByteArray {
        val o = JsonOut()
        o.raw("{\"user_id\":"); o.stringOrNull(userId)
        o.raw(",\"device_digest\":"); o.string(deviceDigest)
        o.raw(",\"acked_ms\":"); o.int(ackedMs)
        o.raw(",\"key_fp\":"); o.stringOrNull(keyFp)
        o.raw(",\"base_url\":"); o.stringOrNull(baseUrl)
        o.raw("}")
        return o.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): MappingState? {
            val o = JsonIn.obj(b) ?: return null
            val d = o["device_digest"] as? String ?: return null
            return MappingState(
                o["user_id"] as? String, d, JsonIn.int64(o["acked_ms"]) ?: 0, o["key_fp"] as? String, o["base_url"] as? String,
                o.containsKey("key_fp") || o.containsKey("base_url"),
            )
        }
    }
}

/** 信封里的 mapping 块。 */
internal data class MappingBlock(val userId: String?, val device: Device)

/** 信封头（lines 之前的全部字段），字段顺序取 envelope.ts 的 Envelope。 */
internal data class EnvelopeHeader(
    val kind: Ids.BatchKind,
    var batchId: String,
    val createdMs: Long,
    var day: String,
    val installId: String,
    val sessionId: String,
    val sessionNo: Long,
    val process: String,
    val userId: String?,
    val device: Device,
    var seqFrom: Long,
    var seqTo: Long,
    var oseqFrom: Long?,
    var oseqTo: Long?,
    var ctxTruncated: Long?,
    var drops: List<DropEntry> = emptyList(),
    var closedSessions: List<ClosedSession> = emptyList(),
    /** 0.2.0 起物化不再写（ADR 0019 决定 2）；只为 0.1.x 留在出站箱里的旧批被 413 切分时原样带上。 */
    var closedSessionsDropped: Long = 0,
    var mapping: MappingBlock? = null,
) {
    /** 输出到 `"lines":[` 为止（含）。 */
    fun encodePrefix(): ByteArray {
        val o = JsonOut(1024)
        o.raw("{\"v\":1,\"kind\":"); o.string(kind.wire)
        o.raw(",\"batch_id\":"); o.string(batchId)
        o.raw(",\"created_ms\":"); o.int(createdMs)
        o.raw(",\"day\":"); o.string(day)
        o.raw(",\"install_id\":"); o.string(installId)
        o.raw(",\"session_id\":"); o.string(sessionId)
        o.raw(",\"session_no\":"); o.int(sessionNo)
        o.raw(",\"process\":"); o.string(process)
        o.raw(",\"user_id\":"); o.stringOrNull(userId)
        o.raw(",\"device\":"); device.encode(o)
        o.raw(",\"seq_from\":"); o.int(seqFrom)
        o.raw(",\"seq_to\":"); o.int(seqTo)
        val f = oseqFrom
        val t = oseqTo
        if (f != null && t != null) {
            o.raw(",\"oseq_from\":"); o.int(f)
            o.raw(",\"oseq_to\":"); o.int(t)
        }
        ctxTruncated?.let { o.raw(",\"ctx_truncated\":"); o.int(it) }
        if (drops.isNotEmpty()) {
            o.raw(",\"drops\":[")
            drops.forEachIndexed { i, d ->
                if (i > 0) o.raw(",")
                d.encode(o)
            }
            o.raw("]")
        }
        if (closedSessions.isNotEmpty()) {
            o.raw(",\"closed_sessions\":[")
            closedSessions.forEachIndexed { i, c ->
                if (i > 0) o.raw(",")
                c.encode(o)
            }
            o.raw("]")
        }
        if (closedSessionsDropped > 0) {
            o.raw(",\"closed_sessions_dropped\":"); o.int(closedSessionsDropped)
        }
        mapping?.let { m ->
            o.raw(",\"mapping\":{\"user_id\":"); o.stringOrNull(m.userId)
            o.raw(",\"device\":"); m.device.encode(o)
            o.raw("}")
        }
        o.raw(",\"lines\":[")
        return o.toByteArray()
    }

    /** 整个信封：头 + 行（逗号分隔）+ `]}`。 */
    fun encode(lines: List<ByteArray>): ByteArray {
        val prefix = encodePrefix()
        val o = JsonOut(prefix.size + lines.sumOf { it.size + 1 } + 2)
        o.raw(prefix)
        lines.forEachIndexed { i, l ->
            if (i > 0) o.byte(0x2C)
            o.raw(l)
        }
        o.byte(0x5D)
        o.byte(0x7D)
        return o.toByteArray()
    }

    companion object {
        fun decode(o: Map<String, Any?>): EnvelopeHeader? {
            val kind = Ids.BatchKind.of(o["kind"] as? String) ?: return null
            val bid = o["batch_id"] as? String ?: return null
            val created = JsonIn.int64(o["created_ms"]) ?: return null
            val day = o["day"] as? String ?: return null
            val iid = o["install_id"] as? String ?: return null
            val sid = o["session_id"] as? String ?: return null
            val sno = JsonIn.int64(o["session_no"]) ?: return null
            val proc = o["process"] as? String ?: return null
            val dev = Device.decode(o["device"]) ?: return null
            val h = EnvelopeHeader(
                kind, bid, created, day, iid, sid, sno, proc, o["user_id"] as? String, dev,
                JsonIn.int64(o["seq_from"]) ?: 0, JsonIn.int64(o["seq_to"]) ?: 0,
                JsonIn.int64(o["oseq_from"]), JsonIn.int64(o["oseq_to"]), JsonIn.int64(o["ctx_truncated"]),
            )
            h.drops = (JsonIn.asList(o["drops"]) ?: emptyList()).mapNotNull { DropEntry.decode(it) }
            h.closedSessions = (JsonIn.asList(o["closed_sessions"]) ?: emptyList()).mapNotNull { ClosedSession.decode(it) }
            h.closedSessionsDropped = JsonIn.int64(o["closed_sessions_dropped"]) ?: 0
            val m = JsonIn.asObj(o["mapping"])
            if (m != null) {
                val md = Device.decode(m["device"])
                if (md != null) h.mapping = MappingBlock(m["user_id"] as? String, md)
            }
            return h
        }
    }
}

/** 出站箱批文件的元数据（物化时填入缓存；启动时解压扫描重建）。 */
internal data class BatchMeta(
    var name: String,
    /** 0 / 1 / 2 = p0 / p1 / p2；3 = q */
    var prio: Int,
    val createdMs: Long,
    val batchId: String,
    /** 信封里的 install_id：请求头 `X-Rtv-Install` 取它（ADR 0019 决定 10）；读不出的兜底元数据取当前 install。 */
    val installId: String,
    val kind: Ids.BatchKind,
    val sessionId: String,
    val oseqFrom: Long,
    val oseqTo: Long,
    val lineCount: Int,
    val hasWarnOrAbove: Boolean,
    val hasError: Boolean,
    val drops: List<DropEntry>,
    val closed: List<ClosedSession>,
    /** 批里带了 mapping 块（值是其中的 user_id，可能为 null）。 */
    val hasMapping: Boolean,
    val mappingUser: String?,
    val mappingDigest: String?,
    val bytes: Long,
) {
    /** 429 `["info","backfill"]` 暂停的类别：p2 = backfill；不含 warn 以上的 primary = info。 */
    val category: String?
        get() = if (kind == Ids.BatchKind.BACKFILL) "backfill" else if (hasWarnOrAbove) null else "info"
}

/** 出站箱文件名：`p{0|1|2}-<created_ms>-<batch_id>.gz` / `q-<created_ms>-<batch_id>.gz`。 */
internal object OutboxName {
    class Parts(val prio: Int, val createdMs: Long, val batchId: String)

    fun make(prio: Int, createdMs: Long, batchId: String): String =
        (if (prio >= 3) "q" else "p$prio") + "-$createdMs-$batchId.gz"

    fun parse(name: String): Parts? {
        if (!name.endsWith(".gz")) return null
        val stem = name.substring(0, name.length - 3)
        val a = stem.indexOf('-')
        if (a < 0) return null
        val b = stem.indexOf('-', a + 1)
        if (b < 0) return null
        val created = stem.substring(a + 1, b).toLongOrNull() ?: return null
        val prio = when (stem.substring(0, a)) {
            "p0" -> 0
            "p1" -> 1
            "p2" -> 2
            "q" -> 3
            else -> return null
        }
        val bid = stem.substring(b + 1)
        if (!Ids.isUuid(bid)) return null
        return Parts(prio, created, bid)
    }
}

/**
 * backoff.json：{attempt, next_at_wall_ms, next_at_mono_ms, paused_until_ms, paused_categories, reason, last_ack_ms, key_fp?, base_url?}
 * （`last_ack_ms`：墓碑 last_ack_age_ms 需要跨启动的「上次 2xx」时刻，同 iOS）。
 * `key_fp` / `base_url`（ADR 0024 决定 7）：这份退避 / 暂停属于哪把 key、哪个服务端；与当前不同（含旧文件没有这两个键）= 不继承。
 */
internal data class BackoffState(
    var attempt: Int = 0,
    var nextAtWallMs: Long = 0,
    var nextAtMonoMs: Long = 0,
    var pausedUntilMs: Long = 0,
    var pausedUntilMono: Long = 0,
    var pausedCategories: List<String> = emptyList(),
    var reason: String = "",
    var lastAckMs: Long = -1,
    var keyFp: String? = null,
    var baseUrl: String? = null,
    /** 文件里有 key_fp / base_url 键（旧版本写的两个都没有 = 视为当前目标）。 */
    var hasIdentity: Boolean = true,
) {
    fun encode(): ByteArray {
        val o = JsonOut()
        o.raw("{\"attempt\":"); o.int(attempt)
        o.raw(",\"next_at_wall_ms\":"); o.int(nextAtWallMs)
        o.raw(",\"next_at_mono_ms\":"); o.int(nextAtMonoMs)
        o.raw(",\"paused_until_ms\":"); o.int(pausedUntilMs)
        o.raw(",\"paused_categories\":[")
        pausedCategories.forEachIndexed { i, c ->
            if (i > 0) o.raw(",")
            o.string(c)
        }
        o.raw("],\"reason\":"); o.string(reason)
        o.raw(",\"last_ack_ms\":"); o.int(lastAckMs)
        o.raw(",\"key_fp\":"); o.stringOrNull(keyFp)
        o.raw(",\"base_url\":"); o.stringOrNull(baseUrl)
        o.raw("}")
        return o.toByteArray()
    }

    /** 换了 key 指纹或 baseUrl：清鉴权暂停、类别暂停与退避（`last_ack_ms` 保留：它只用来算墓碑的「距上次确认」）。 */
    fun forIdentity(fp: String, base: String): BackoffState = BackoffState(lastAckMs = lastAckMs, keyFp = fp, baseUrl = base)

    companion object {
        /** 冷启动：单调时钟不跨进程，用墙钟换算；next_at 晚于 now + 15 min（时钟回拨）截断。 */
        fun decodeColdStart(b: ByteArray, nowWall: Long, nowMono: Long): BackoffState? {
            val o = JsonIn.obj(b) ?: return null
            val s = BackoffState()
            s.attempt = (JsonIn.int64(o["attempt"]) ?: 0).toInt()
            s.nextAtWallMs = JsonIn.int64(o["next_at_wall_ms"]) ?: 0
            s.pausedUntilMs = JsonIn.int64(o["paused_until_ms"]) ?: 0
            s.pausedCategories = (JsonIn.asList(o["paused_categories"]) ?: emptyList()).mapNotNull { it as? String }
            s.reason = (o["reason"] as? String) ?: ""
            s.lastAckMs = JsonIn.int64(o["last_ack_ms"]) ?: -1
            s.keyFp = o["key_fp"] as? String
            s.baseUrl = o["base_url"] as? String
            s.hasIdentity = o.containsKey("key_fp") || o.containsKey("base_url")
            val nextWait = minOf(maxOf(s.nextAtWallMs - nowWall, 0), Limits.BACKOFF_MAX_MS)
            s.nextAtWallMs = if (nextWait > 0) nowWall + nextWait else 0
            s.nextAtMonoMs = if (nextWait > 0) nowMono + nextWait else 0
            val pauseWait = minOf(maxOf(s.pausedUntilMs - nowWall, 0), Limits.PAUSE_401_MAX_MS)
            s.pausedUntilMs = if (pauseWait > 0) nowWall + pauseWait else 0
            s.pausedUntilMono = if (pauseWait > 0) nowMono + pauseWait else 0
            if (pauseWait == 0L) s.pausedCategories = emptyList()
            return s
        }
    }
}
