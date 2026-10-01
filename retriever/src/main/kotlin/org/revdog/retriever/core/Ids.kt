package org.revdog.retriever.core

import java.security.MessageDigest
import java.util.UUID

/** 标识符（packages/core/src/ids.ts）：UUID_RE 整串匹配、RFC 4122 UUIDv5、确定性 batch_id。与 iOS `IDs` 同口径。 */
internal object Ids {
    /** Retriever 的 UUIDv5 命名空间（固定常量，永不改）。 */
    const val NAMESPACE = "9a1d7c3e-5b2f-4e8a-8c6d-2f1e0b3a7d95"

    /** `^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$`，整串匹配（不依赖 `$` 的行尾语义）。 */
    fun isUuid(s: String): Boolean {
        if (s.length != 36) return false
        for (i in 0 until 36) {
            val c = s[i]
            when (i) {
                8, 13, 18, 23 -> if (c != '-') return false
                14 -> if (c !in '1'..'8') return false
                else -> if (c !in '0'..'9' && c !in 'a'..'f') return false
            }
        }
        return true
    }

    fun uuidBytes(s: String): ByteArray? {
        val hex = s.replace("-", "")
        if (hex.length != 32) return null
        val out = ByteArray(16)
        for (k in 0 until 16) {
            val hi = Character.digit(hex[2 * k], 16)
            val lo = Character.digit(hex[2 * k + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[k] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun format(b: ByteArray): String {
        val sb = StringBuilder(36)
        for (k in b.indices) {
            if (k == 4 || k == 6 || k == 8 || k == 10) sb.append('-')
            val x = b[k].toInt() and 0xFF
            sb.append(HEX[x shr 4]).append(HEX[x and 0xF])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** RFC 4122 UUIDv5（SHA-1），name 按 UTF-8。namespace 必须满足 isUuid。 */
    fun uuidv5(namespace: String, name: String): String? {
        if (!isUuid(namespace)) return null
        val ns = uuidBytes(namespace) ?: return null
        val md = MessageDigest.getInstance("SHA-1")
        md.update(ns)
        md.update(Text.utf8(name))
        val b = md.digest().copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        return format(b)
    }

    enum class BatchKind(val wire: String) {
        PRIMARY("primary"),
        BACKFILL("backfill"),
        ;

        companion object {
            fun of(s: String?): BatchKind? = entries.firstOrNull { it.wire == s }
        }
    }

    /** `${install_id}:${session_id}:${kind}:${n}`；n = primary 的 oseq_from / backfill 的 seg_no。 */
    fun batchIdName(installId: String, sessionId: String, kind: BatchKind, n: Long): String =
        "$installId:$sessionId:${kind.wire}:$n"

    /** 输入不合规（非小写 UUID、n 为负）返回 null（TS 侧抛错）。 */
    fun batchId(installId: String, sessionId: String, kind: BatchKind, n: Long): String? {
        if (!isUuid(installId) || !isUuid(sessionId) || n < 0) return null
        return uuidv5(NAMESPACE, batchIdName(installId, sessionId, kind, n))
    }

    /**
     * backfill 段超 768 KB 按 seq 切多批时的 batch_id：
     * UUIDv5(RETRIEVER_NAMESPACE, `${install_id}:${session_id}:backfill:${seg_no}:${seq_from}`)（主代理 2026-09-29 裁决，同 iOS）。
     */
    fun backfillSplitBatchId(installId: String, sessionId: String, segNo: Long, seqFrom: Long): String? {
        if (!isUuid(installId) || !isUuid(sessionId) || segNo < 0 || seqFrom < 1) return null
        return uuidv5(NAMESPACE, "$installId:$sessionId:backfill:$segNo:$seqFrom")
    }

    /** 413 半批：`${install_id}:${session_id}:primary:${oseq_from}:${oseq_to}`（packages/core `splitBatchIdName`）。 */
    fun splitBatchIdName(installId: String, sessionId: String, oseqFrom: Long, oseqTo: Long): String =
        "$installId:$sessionId:primary:$oseqFrom:$oseqTo"

    /**
     * 413 切分出的半批 batch_id（ADR 0019 决定 11；golden `ids.json` `split_batch_id`）：区间两端都进名字，必与未切分批
     * （名字以 oseq_from 结尾）不同名，重切得到同名批。install_id 取原批信封。输入不合规（非 UUID、区间不是 1 ≤ from ≤ to）返回 null。
     */
    fun splitBatchId(installId: String, sessionId: String, oseqFrom: Long, oseqTo: Long): String? {
        if (!isUuid(installId) || !isUuid(sessionId) || oseqFrom < 1 || oseqTo < oseqFrom) return null
        return uuidv5(NAMESPACE, splitBatchIdName(installId, sessionId, oseqFrom, oseqTo))
    }

    /** 新的随机 UUID（v4，小写）。 */
    fun newV4(): String = UUID.randomUUID().toString()

    /**
     * key 指纹（ADR 0024 决定 7）：`sha256(key 的 UTF-8)` 的前 16 位小写十六进制；key 为空 → 空串。
     * 退避 / 映射 / 配置缓存的身份各记一份，换 key 就不继承旧 key 的账。
     */
    fun keyFingerprint(key: String): String {
        if (key.isEmpty()) return ""
        val h = MessageDigest.getInstance("SHA-256").digest(Text.utf8(key))
        val sb = StringBuilder(16)
        for (k in 0 until 8) {
            val x = h[k].toInt() and 0xFF
            sb.append(HEX[x shr 4]).append(HEX[x and 0xF])
        }
        return sb.toString()
    }
}

/** 日期（packages/core/src/object-key.ts）：ms UTC → `YYYY-MM-DD`；客户端 day 规则。不用 java.time（minSdk 24）。 */
internal object Day {
    fun fromMs(ms: Long): String {
        // floor 除法（负数也正确），再用 Howard Hinnant 的 civil_from_days（与 iOS 同算法）。
        val days = Math.floorDiv(ms, ClientConstants.DAY_MS)
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        var y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        if (m <= 2) y += 1
        return pad(y, 4) + "-" + pad(m, 2) + "-" + pad(d, 2)
    }

    private fun pad(v: Long, w: Int): String {
        val s = v.toString()
        return if (s.length >= w) s else "0".repeat(w - s.length) + s
    }

    /** day = UTC 日期(clamp(批内最早行 ts, created − 30 d, created))。 */
    fun clientDay(tsMinMs: Long, createdMs: Long): String {
        val lo = createdMs - Limits.DAY_CLAMP_PAST_DAYS_CLIENT * ClientConstants.DAY_MS
        val t = minOf(maxOf(tsMinMs, lo), createdMs)
        return fromMs(t)
    }
}
