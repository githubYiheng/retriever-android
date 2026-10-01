package org.revdog.retriever

// 宿主 API 的值类型（方案 §3.10，三端同名）。入口在 Retriever.kt。

/** 日志级别；线上值 lower_snake（`debug` … `fatal`）。顺序 debug < info < warn < error < fatal。 */
public enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    ;

    internal val rank: Int get() = ordinal
    internal val wire: String get() = WIRE[ordinal]

    internal companion object {
        private val WIRE = arrayOf("debug", "info", "warn", "error", "fatal")

        fun ofRank(r: Int): LogLevel = entries[r.coerceIn(0, 4)]

        fun ofWire(s: String?): LogLevel? = entries.firstOrNull { it.wire == s }
    }
}

/** 异常（§3.1 `exc`）。Throwable 由 SDK 转换：type = `javaClass.name`、message = `message ?: ""`、stack = `printStackTrace` 文本（取栈失败为 null）。 */
public data class LogException(
    val type: String,
    val message: String,
    val stack: String?,
)

/**
 * 给 `redact` 钩子看的行（落盘前）。seq / oseq 由 SDK 在落盘时分配，钩子不可改。
 * `attrs` 的值只认 String / Number / Boolean / null（其它 `toString()`）。
 */
public data class LogLine(
    val ts: Long,
    val level: LogLevel,
    val msg: String,
    val tag: String?,
    val attrs: Map<String, Any?>?,
    val exc: LogException?,
)

/**
 * 宿主选项（§3.10；ADR 0004 / 0005）。可变属性 + 默认值，Java 友好。只在 `configure` 时读一次。
 * 引用类型的 setter 接受 null（= 默认值；Java / 平台类型传 null 不抛，ADR 0024 决定 5）；Kotlin 照常用属性赋值。
 */
public class Options {
    /** 自动上传级别（ADR 0004）：该级别及以上的行是义务行（有 oseq）。远程配置可覆盖。 */
    public var uploadLevel: LogLevel = LogLevel.WARN
        @JvmSynthetic @JvmName("uploadLevelKt")
        set

    /** 本地落盘级别（§3.3-9）：以下的行不写、不占 seq。远程配置可覆盖。 */
    public var localLevel: LogLevel = LogLevel.DEBUG
        @JvmSynthetic @JvmName("localLevelKt")
        set

    /** 每日包数软上限（ADR 0005）；0 = 不限。远程配置可覆盖（钳制 0–10000）。 */
    public var dailyBatchCap: Int = 0

    /** 本地总量上限（宿主默认，远程可改，钳制 2–100 MB）。 */
    public var localCapBytes: Long = 20L * 1024 * 1024

    /**
     * 落盘前同步调用；返回 null = 丢弃（不占 seq、不记墓碑）；抛异常 = 丢弃该行。钩子内调 `log()` 视为重入直接忽略。
     * 不能改 `ts`（改了也取回原值）。configure 之前的行在收编时过它——可能在 SDK 线程上被调：须线程安全、要快。
     */
    public var redact: ((LogLine) -> LogLine?)? = null

    /** 会话目录 `proc-<name>` 与信封 `process`；null = 自动：主进程 "main"，其它取进程名 `:` 之后的后缀。 */
    public var processName: String? = null

    /** 后台兜底 JobScheduler 作业 id（宿主可改，避免与自己的作业冲突）。 */
    public var jobId: Int = 0x5254

    /** 进 `device.sdk = "retriever-android/<ver>"` 与 `X-Rtv-Sdk`。 */
    public var sdkVersion: String = RetrieverVersion.CURRENT
        @JvmSynthetic @JvmName("sdkVersionKt")
        set

    /** Java 侧 setter：null = 默认 warn。 */
    public fun setUploadLevel(level: LogLevel?) {
        uploadLevel = level ?: LogLevel.WARN
    }

    /** Java 侧 setter：null = 默认 debug。 */
    public fun setLocalLevel(level: LogLevel?) {
        localLevel = level ?: LogLevel.DEBUG
    }

    /** Java 侧 setter：null = 默认（本 SDK 版本）。 */
    public fun setSdkVersion(version: String?) {
        sdkVersion = version ?: RetrieverVersion.CURRENT
    }
}

/**
 * `flush()` 的结果：`Stored` = flush 产生的批 15 s 内已被服务端确认；`Pending` 附原因（offline / backoff / paused / timeout / disabled；
 * 未 configure = paused，等待中被禁用 = disabled）。
 */
public sealed class FlushResult {
    public data object Stored : FlushResult() {
        override fun toString(): String = "stored"
    }

    public data class Pending(val reason: String) : FlushResult() {
        override fun toString(): String = "pending(\"$reason\")"
    }
}

/** `flush()` 回调（在 SDK 的后台线程上调用）。 */
public fun interface FlushCallback {
    public fun onResult(result: FlushResult)
}

/** SDK 版本号（唯一来源）：进 `device.sdk = "retriever-android/<ver>"` 与 `X-Rtv-Sdk`；必须 == gradle.properties 的 VERSION_NAME。 */
public object RetrieverVersion {
    public const val CURRENT: String = "0.3.0"
}
