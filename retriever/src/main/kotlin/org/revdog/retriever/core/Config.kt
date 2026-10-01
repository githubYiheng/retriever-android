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
            // 缺省 = 宿主值（对齐 packages/core clampConfig 的 hostLocalCap；ADR 0022）
            localCapBytes = clampInt(
                JsonIn.double(r["local_cap_bytes"]), Limits.LOCAL_CAP_BYTES_MIN, Limits.LOCAL_CAP_BYTES_MAX, host.localCapBytes,
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

    /** 宿主型字段（packages/core `HOST_FIELDS`，顺序固定）。 */
    val HOST_FIELDS: List<String> = listOf("upload_level", "local_level", "local_cap_bytes", "daily_batch_cap")

    /** 响应 / 缓存文件里的 `from_host`（ADR 0022）：只认已知的宿主型字段名；缺失或不是数组 = 空集（旧服务端 / 旧缓存）。 */
    fun fromHost(v: Any?): Set<String> {
        val l = JsonIn.asList(v) ?: return emptySet()
        val names = l.mapNotNull { it as? String }.toSet()
        return HOST_FIELDS.filterTo(LinkedHashSet()) { it in names }
    }

    /** `from_host` 里的字段取**当前**宿主默认（按 clamp 规则钳制），其余保留缓存值。 */
    fun withHost(c: RemoteConfig, fields: Set<String>, host: HostDefaults): RemoteConfig {
        if (fields.isEmpty()) return c
        val h = clamp(null, host)
        return c.copy(
            uploadLevel = if ("upload_level" in fields) h.uploadLevel else c.uploadLevel,
            localLevel = if ("local_level" in fields) h.localLevel else c.localLevel,
            localCapBytes = if ("local_cap_bytes" in fields) h.localCapBytes else c.localCapBytes,
            dailyBatchCap = if ("daily_batch_cap" in fields) h.dailyBatchCap else c.dailyBatchCap,
        )
    }

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

/**
 * 缓存的远程配置（不可变快照：引擎线程换新对象，写入侧在宿主线程上读同一个快照重算级别）。
 * 单调时钟只在本进程内有效；跨进程（重启）用墙钟兜底。
 */
internal class ConfigCache(
    val config: RemoteConfig,
    val fetchedWallMs: Long,
    /** 本进程拉到时的单调时刻；从文件恢复的缓存为 null（改用墙钟）。 */
    val fetchedMonoMs: Long?,
    /** 身份（install_id, user_id, key 指纹, baseUrl）变了：按过期处理直到新身份的响应到达（ADR 0019 决定 12 / ADR 0024 决定 7）。 */
    val identityStale: Boolean = false,
    /** 响应的 `from_host`（ADR 0022）：这些宿主型字段远程没给，生效时取当前宿主默认。 */
    val fromHost: Set<String> = emptySet(),
    /** 拉到这份配置时的 key 指纹与 baseUrl（写进 config.json；读回时与当前不同 = 身份过期）。 */
    val keyFp: String = "",
    val baseUrl: String = "",
    /** 从旧版本的 config.json 读来（没有 key_fp / base_url）：按与当前相同处理，调用方补写当前值。 */
    val legacyIdentity: Boolean = false,
    /** 拉到这份配置时的用户（清洗后，可为 null）：冷启动时与实例的初始用户不同 → 按身份过期（放大型字段回落、startup 重拉）。 */
    val userId: String? = null,
) {
    fun elapsedMs(nowWall: Long, nowMono: Long): Long {
        val m = fetchedMonoMs
        return if (m != null) nowMono - m else nowWall - fetchedWallMs
    }

    /** 同一份配置，按过期处理（放大型字段立即回落）。 */
    fun staleForIdentity(): ConfigCache = ConfigCache(config, fetchedWallMs, fetchedMonoMs, true, fromHost, keyFp, baseUrl, userId = userId)

    /** config.json：`{"fetched_ms":N,"key_fp":"…","base_url":"…","config":{12 字段},"from_host":[…]}`（from_host 按 HOST_FIELDS 顺序）。 */
    fun encodeFile(): ByteArray {
        val o = JsonOut()
        o.raw("{\"fetched_ms\":"); o.int(fetchedWallMs)
        o.raw(",\"key_fp\":"); o.string(keyFp)
        o.raw(",\"base_url\":"); o.string(baseUrl)
        o.raw(",\"user_id\":"); o.stringOrNull(userId)
        o.raw(",\"config\":"); o.raw(ConfigRules.encode(config))
        o.raw(",\"from_host\":[")
        ConfigRules.HOST_FIELDS.filter { it in fromHost }.forEachIndexed { i, f ->
            if (i > 0) o.raw(",")
            o.string(f)
        }
        o.raw("]}")
        return o.toByteArray()
    }

    /** 下一次生效配置可能变化的单调时刻（过期 / full_dump 到期），供调度器唤醒。 */
    fun nextChangeMono(nowWall: Long, nowMono: Long): Long? {
        if (identityStale) return null
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
         * 读 config.json；读不出 / 不是对象 / 没有 fetched_ms 返回 null。身份对不上 → 按身份过期处理。
         * 旧版本写的文件：没有 `from_host` = 空集（远程值照旧全量生效，同升级前）；没有 `key_fp` / `base_url` = 与当前相同（`legacyIdentity`）。
         */
        fun decodeFile(b: ByteArray, host: HostDefaults, keyFp: String, baseUrl: String, user: String?): ConfigCache? {
            val o = JsonIn.obj(b) ?: return null
            val fetched = JsonIn.int64(o["fetched_ms"]) ?: return null
            val fp = o["key_fp"] as? String
            val base = o["base_url"] as? String
            // 两个键都没有 = 旧版本写的：视为当前目标（补写、不复位）；只要有且任一不符 = 身份过期
            val legacy = !o.containsKey("key_fp") && !o.containsKey("base_url")
            val same = legacy || (fp == keyFp && base == baseUrl)
            // 用户：缺键 = null；与实例的初始用户不同 = 身份过期
            val cachedUser = o["user_id"] as? String
            return ConfigCache(
                ConfigRules.clamp(o["config"], host), fetched, null, !same || cachedUser != user, ConfigRules.fromHost(o["from_host"]),
                if (same) keyFp else fp ?: "", if (same) baseUrl else base ?: "", legacy, cachedUser,
            )
        }

        /**
         * 生效配置（纯函数：不可变缓存快照 + 宿主默认 + 时刻）：`from_host` 里的字段取当前宿主默认，其余取缓存值（ADR 0022）；
         * 再做过期回落——放大型字段（full_dump、低于宿主默认的 upload_level、高于默认的 context_*、
         * 低于默认的 flush_interval、高于宿主默认的 local_cap）回落到宿主默认 / 内置默认（宪法 U-2）。
         * 只因身份变化（identityStale）按过期处理时 local_cap 不回落：它不放大上传，回落只会立即驱逐上一个身份攒下的义务批。
         */
        fun effective(cache: ConfigCache?, host: HostDefaults, nowWall: Long, nowMono: Long): EffectiveConfig {
            if (cache == null) {
                val c = ConfigRules.clamp(null, host)
                return EffectiveConfig(c, false, c.uploadLevel, true)
            }
            var c = ConfigRules.withHost(cache.config, cache.fromHost, host)
            val elapsed = cache.elapsedMs(nowWall, nowMono)
            val ttlExpired = elapsed < 0 || elapsed >= c.ttlS * 1000L
            val expired = cache.identityStale || ttlExpired
            if (expired) {
                c = c.copy(
                    fullDump = false,
                    fullDumpTtlS = 0,
                    uploadLevel = if (c.uploadLevel < host.uploadLevel) host.uploadLevel else c.uploadLevel,
                    contextLines = minOf(c.contextLines, Limits.CTX_LINES_DEFAULT),
                    contextBytes = minOf(c.contextBytes, Limits.CTX_BYTES_DEFAULT),
                    flushIntervalS = maxOf(c.flushIntervalS, Limits.FLUSH_INTERVAL_S_DEFAULT),
                    localCapBytes = if (ttlExpired) minOf(c.localCapBytes, host.localCapBytes) else c.localCapBytes,
                )
            }
            val fullDumpActive = c.fullDump && elapsed >= 0 && elapsed < c.fullDumpTtlS * 1000L
            return EffectiveConfig(c, fullDumpActive, if (fullDumpActive) LogLevel.DEBUG else c.uploadLevel, expired)
        }
    }
}
