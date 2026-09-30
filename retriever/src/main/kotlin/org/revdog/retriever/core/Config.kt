package org.revdog.retriever.core

import org.revdog.retriever.LogLevel
import org.revdog.retriever.Options

/** 远程配置（方案 §5；packages/core/src/config.ts）。与 iOS `Config.swift` 逐字一致。 */
internal data class RemoteConfig(
    val etag: String,
    val ttlS: Int,
    val uploadEnabled: Boolean,
    val uploadLevel: LogLevel,
    val localLevel: LogLevel,
    val contextLines: Int,
    val contextBytes: Int,
    val flushIntervalS: Int,
    val localCapBytes: Int,
    val fullDump: Boolean,
    val fullDumpTtlS: Int,
    val dailyBatchCap: Int,
)

/** 宿主在 configure 里给的默认（ADR 0004 / 0005）。`localLevel` / `dailyBatchCap` 可缺省。 */
internal data class HostDefaults(
    val uploadLevel: LogLevel,
    val localLevel: LogLevel? = null,
    val dailyBatchCap: Int? = null,
    /** 宿主 `localCapBytes`（缓存过期时 local_cap 放大回落的目标；不参与 clampConfig）。 */
    val localCapBytes: Int = Limits.LOCAL_CAP_BYTES_DEFAULT,
) {
    companion object {
        fun of(o: Options): HostDefaults =
            HostDefaults(o.uploadLevel, o.localLevel, o.dailyBatchCap, ConfigRules.clampHostCap(o.localCapBytes))
    }
}

internal object ConfigRules {
    /** full_dump_ttl_s 上限：覆盖默认 ttl × 7（= 7 天）。 */
    const val FULL_DUMP_TTL_S_MAX = Limits.OVERRIDE_TTL_S_DEFAULT * 7

    /** `clampConfig` 逐字移植（config.ts）：越界取边界，类型错 / 未知枚举取默认；raw 非对象 → 全默认。 */
    fun clamp(raw: Any?, host: HostDefaults): RemoteConfig {
        val r: Map<String, Any?> = JsonIn.asObj(raw) ?: emptyMap()
        val hostUpload = host.uploadLevel
        val hostLocal = host.localLevel ?: LogLevel.DEBUG
        val hostCap = clampInt(host.dailyBatchCap?.toDouble(), 0, Limits.DAILY_BATCH_CAP_MAX, Limits.DAILY_BATCH_CAP_DEFAULT)
        return RemoteConfig(
            etag = JsonIn.string(r["etag"]) ?: "",
            ttlS = clampInt(JsonIn.double(r["ttl_s"]), Limits.CONFIG_TTL_S_MIN, Limits.CONFIG_TTL_S_MAX, Limits.CONFIG_TTL_S_DEFAULT),
            uploadEnabled = JsonIn.bool(r["upload_enabled"]) ?: true,
            uploadLevel = LogLevel.ofWire(r["upload_level"] as? String) ?: hostUpload,
            localLevel = LogLevel.ofWire(r["local_level"] as? String) ?: hostLocal,
            contextLines = clampInt(JsonIn.double(r["context_lines"]), 0, Limits.CTX_LINES_MAX, Limits.CTX_LINES_DEFAULT),
            contextBytes = clampInt(JsonIn.double(r["context_bytes"]), 0, Limits.CTX_BYTES_MAX, Limits.CTX_BYTES_DEFAULT),
            flushIntervalS = clampInt(
                JsonIn.double(r["flush_interval_s"]), Limits.FLUSH_INTERVAL_S_MIN, Limits.FLUSH_INTERVAL_S_MAX,
                Limits.FLUSH_INTERVAL_S_DEFAULT,
            ),
            localCapBytes = clampInt(
                JsonIn.double(r["local_cap_bytes"]), Limits.LOCAL_CAP_BYTES_MIN, Limits.LOCAL_CAP_BYTES_MAX,
                Limits.LOCAL_CAP_BYTES_DEFAULT,
            ),
            fullDump = JsonIn.bool(r["full_dump"]) ?: false,
            fullDumpTtlS = clampInt(JsonIn.double(r["full_dump_ttl_s"]), 0, FULL_DUMP_TTL_S_MAX, 0),
            dailyBatchCap = clampInt(JsonIn.double(r["daily_batch_cap"]), 0, Limits.DAILY_BATCH_CAP_MAX, hostCap),
        )
    }

    /** 有限 number 先向下取整再夹到 [min, max]（按浮点比较，避免大数溢出）；其它一律取默认。 */
    fun clampInt(x: Double?, min: Int, max: Int, dflt: Int): Int {
        if (x == null || x.isNaN() || x.isInfinite()) return dflt
        val v = Math.floor(x)
        if (v <= min.toDouble()) return min
        if (v > max.toDouble()) return max
        return v.toInt()
    }

    fun clampHostCap(v: Long): Int =
        v.coerceIn(Limits.LOCAL_CAP_BYTES_MIN.toLong(), Limits.LOCAL_CAP_BYTES_MAX.toLong()).toInt()

    /** 序列化成与服务端同名字段的 JSON（缓存到 config.json）。 */
    fun encode(c: RemoteConfig): ByteArray {
        val o = JsonOut()
        o.raw("{\"etag\":"); o.string(c.etag)
        o.raw(",\"ttl_s\":"); o.int(c.ttlS)
        o.raw(",\"upload_enabled\":"); o.bool(c.uploadEnabled)
        o.raw(",\"upload_level\":"); o.string(c.uploadLevel.wire)
        o.raw(",\"local_level\":"); o.string(c.localLevel.wire)
        o.raw(",\"context_lines\":"); o.int(c.contextLines)
        o.raw(",\"context_bytes\":"); o.int(c.contextBytes)
        o.raw(",\"flush_interval_s\":"); o.int(c.flushIntervalS)
        o.raw(",\"local_cap_bytes\":"); o.int(c.localCapBytes)
        o.raw(",\"full_dump\":"); o.bool(c.fullDump)
        o.raw(",\"full_dump_ttl_s\":"); o.int(c.fullDumpTtlS)
        o.raw(",\"daily_batch_cap\":"); o.int(c.dailyBatchCap)
        o.raw("}")
        return o.toByteArray()
    }
}

/** 生效配置：缓存 + 到期回落（§5）。 */
internal data class EffectiveConfig(
    var config: RemoteConfig,
    val fullDumpActive: Boolean,
    /** 生效 upload_level（full_dump 生效期间降为 debug）。 */
    val uploadLevel: LogLevel,
    val expired: Boolean,
)

/** 缓存的远程配置。单调时钟只在本进程内有效；跨进程（重启）用墙钟兜底。 */
internal class ConfigCache(
    val config: RemoteConfig,
    val fetchedWallMs: Long,
    /** 本进程拉到时的单调时刻；从文件恢复的缓存为 null（改用墙钟）。 */
    val fetchedMonoMs: Long?,
) {
    fun elapsedMs(nowWall: Long, nowMono: Long): Long {
        val m = fetchedMonoMs
        return if (m != null) nowMono - m else nowWall - fetchedWallMs
    }

    /** 下一次生效配置可能变化的单调时刻（过期 / full_dump 到期），供调度器唤醒。 */
    fun nextChangeMono(nowWall: Long, nowMono: Long): Long? {
        val elapsed = elapsedMs(nowWall, nowMono)
        val cands = ArrayList<Long>()
        val ttl = config.ttlS * 1000L
        if (elapsed < ttl) cands.add(nowMono + (ttl - elapsed))
        if (config.fullDump) {
            val fd = config.fullDumpTtlS * 1000L
            if (elapsed < fd) cands.add(nowMono + (fd - elapsed))
        }
        return cands.minOrNull()
    }

    companion object {
        /**
         * 生效配置：过期后放大型字段（full_dump、低于宿主默认的 upload_level、高于默认的 context_*、
         * 低于默认的 flush_interval、高于宿主默认的 local_cap）回落到宿主默认 / 内置默认（宪法 U-2）。
         */
        fun effective(cache: ConfigCache?, host: HostDefaults, nowWall: Long, nowMono: Long): EffectiveConfig {
            if (cache == null) {
                val c = ConfigRules.clamp(null, host).copy(localCapBytes = host.localCapBytes)
                return EffectiveConfig(c, false, c.uploadLevel, true)
            }
            var c = cache.config
            val elapsed = cache.elapsedMs(nowWall, nowMono)
            val expired = elapsed < 0 || elapsed >= c.ttlS * 1000L
            if (expired) {
                c = c.copy(
                    fullDump = false,
                    fullDumpTtlS = 0,
                    uploadLevel = if (c.uploadLevel < host.uploadLevel) host.uploadLevel else c.uploadLevel,
                    contextLines = minOf(c.contextLines, Limits.CTX_LINES_DEFAULT),
                    contextBytes = minOf(c.contextBytes, Limits.CTX_BYTES_DEFAULT),
                    flushIntervalS = maxOf(c.flushIntervalS, Limits.FLUSH_INTERVAL_S_DEFAULT),
                    localCapBytes = minOf(c.localCapBytes, host.localCapBytes),
                )
            }
            val fullDumpActive = c.fullDump && elapsed >= 0 && elapsed < c.fullDumpTtlS * 1000L
            return EffectiveConfig(c, fullDumpActive, if (fullDumpActive) LogLevel.DEBUG else c.uploadLevel, expired)
        }
    }
}
