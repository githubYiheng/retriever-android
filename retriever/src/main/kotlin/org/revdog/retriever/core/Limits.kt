package org.revdog.retriever.core

/** 线上限与默认值：逐字取自 packages/core/src/limits.ts（改数 = 改契约，同 commit 改方案）。与 iOS `Limits.swift` 同值。 */
internal object Limits {
    // ---- 单行（§3.1）----
    const val LINE_MSG_BYTES = 4096
    const val LINE_TAG_BYTES = 64
    const val LINE_ATTRS_KEYS = 32
    const val LINE_ATTRS_BYTES = 4096
    const val LINE_EXC_TYPE_BYTES = 256
    const val LINE_EXC_MESSAGE_BYTES = 1024
    const val LINE_EXC_STACK_BYTES = 16 * 1024
    const val LINE_EXC_STACK_HEAD_BYTES = 8 * 1024
    const val LINE_EXC_STACK_TAIL_BYTES = 8 * 1024
    const val LINE_SERIALIZED_BYTES = 16 * 1024

    // ---- 段与批（§3.4 / §3.5）----
    const val SEGMENT_BYTES = 512 * 1024
    const val BATCH_UNCOMPRESSED_BYTES_CLIENT = 768 * 1024
    const val CTX_LINES_DEFAULT = 200
    const val CTX_LINES_MAX = 500
    const val CTX_BYTES_DEFAULT = 128 * 1024
    const val CTX_BYTES_MAX = 256 * 1024
    const val DROPS_PER_BATCH = 100
    const val CLOSED_SESSIONS_PER_BATCH = 20
    const val USER_ID_BYTES = 128
    const val PROCESS_BYTES = 64
    const val DEVICE_FIELD_BYTES = 128

    // ---- 日期（§4）----
    const val DAY_CLAMP_PAST_DAYS_CLIENT = 30

    // ---- 队列与退避（§3.7）----
    const val MIN_REQUEST_SPACING_MS = 2000L
    const val BACKOFF_BASE_MS = 1000L
    const val BACKOFF_MAX_MS = 15L * 60 * 1000
    const val BACKOFF_JITTER = 0.2
    const val RETRY_AFTER_MIN_S = 1
    const val RETRY_AFTER_MAX_S = 3600
    const val PAUSE_401_BASE_MS = 60L * 60 * 1000
    const val PAUSE_401_MAX_MS = 24L * 60 * 60 * 1000
    const val POISON_CONSECUTIVE_FAILS = 5
    const val ERROR_DEBOUNCE_MS = 2000L
    const val ERROR_SEAL_MIN_INTERVAL_MS = 10_000L
    const val RING_MAX_AGE_DAYS = 7

    // ---- 远程配置默认与钳制（§5）----
    const val FLUSH_INTERVAL_S_DEFAULT = 300
    const val FLUSH_INTERVAL_S_MIN = 30
    const val FLUSH_INTERVAL_S_MAX = 3600
    const val LOCAL_CAP_BYTES_DEFAULT = 20 * 1024 * 1024
    const val LOCAL_CAP_BYTES_MIN = 2 * 1024 * 1024
    const val LOCAL_CAP_BYTES_MAX = 100 * 1024 * 1024
    const val DAILY_BATCH_CAP_DEFAULT = 0
    const val DAILY_BATCH_CAP_MAX = 10_000
    const val CONFIG_TTL_S_DEFAULT = 1800
    const val CONFIG_TTL_S_MIN = 60
    const val CONFIG_TTL_S_MAX = 86_400
    const val OVERRIDE_TTL_S_DEFAULT = 86_400
    const val CONFIG_POLL_INTERVAL_S = 1800
    const val MAPPING_REFRESH_MS = 24L * 60 * 60 * 1000
}

/** 方案正文里有、limits.ts 未收录的客户端数字（§3.2 / §3.6 / §3.7 / §3.8）。与 iOS `ClientConstants` 同值。 */
internal object ClientConstants {
    /**
     * §3.8 `drops.jsonl` 与 `sessions.jsonl` 各自的条数上限（ADR 0019 决定 3 / 4）：墓碑先做无损合并，仍超出删最旧的未在途条目；
     * 终态超出删最旧的未在途条目。
     */
    const val DROPS_FILE_MAX_ENTRIES = 1000

    /** 禁用标记没写成时的重试间隔（ADR 0020 决定 2：每次调度 tick 重试，这里保证有 tick）。与 iOS `markerRetryMs` 同值。 */
    const val MARKER_RETRY_MS = 60_000L

    /** §3.8 可用空间余量。 */
    const val DISK_RESERVE_BYTES = 64L * 1024 * 1024

    /** §3.7 隔离批 24 h 后再试。 */
    const val QUARANTINE_RETRY_MS = 24L * 60 * 60 * 1000

    /** 调度地板：任何候选已到期时也至少等 1 s，杜绝 0 ms 自旋。 */
    const val SCHEDULER_MIN_DELAY_MS = 1000L

    /** §3.9 传输连接 / 读超时。 */
    const val REQUEST_TIMEOUT_MS = 30_000

    /** §3.1 stack 超长时中间插入的标记。 */
    const val STACK_MARKER = "\n…[truncated]…\n"
    const val DAY_MS = 24L * 60 * 60 * 1000
}
