package org.revdog.retriever

import android.util.Log
import org.revdog.retriever.core.ConfigCheck
import org.revdog.retriever.core.Ids
import org.revdog.retriever.core.KeyRejection

/**
 * 配置诊断的出口（ADR 0025；简报 §13）：key / baseUrl 写错、服务端拒绝 key 时在 logcat 里说出来（tag `Retriever`，
 * `no_key` 用 `Log.i`，其余 `Log.w`）。接入期的错误必须在接入者眼前——key 错的时候日志流正好传不上去。
 * 同一（code, key 指纹, baseUrl）每进程最多出一次；禁用时照常出。出口抛任何东西都吞掉；调用方保证不在持 SDK 锁时调。
 */
internal object ConfigDiagnostics {
    const val TAG = "Retriever"

    private val lock = Any()

    /** 已出过的（code, key 指纹, baseUrl）。 */
    private val seen = HashSet<Triple<String, String, String>>()

    private val logcat: (String, String) -> Unit = { code, message ->
        if (code == ConfigCheck.NO_KEY) Log.i(TAG, message) else Log.w(TAG, message)
    }

    /** 出口：生产 = logcat；测试替换成记录 `(code, message)` 的 sink（[resetForTesting] 复位）。 */
    @Volatile
    var sink: (String, String) -> Unit = logcat

    /** configure / 再次 configure 之后：按修剪前后的值做本地检查，逐条出。`rawKey` = 宿主传入的原值（null 已按 "" 计）。 */
    fun configured(rawKey: String, key: String, baseUrl: String) {
        try {
            val codes = ConfigCheck.check(rawKey, key, baseUrl)
            if (codes.isEmpty()) return
            val fp = Ids.keyFingerprint(key)
            for (code in codes) emit(code, fp, baseUrl, ConfigCheck.message(code))
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    /** 服务端拒绝 key、进入鉴权暂停（请求用的是当前 key 指纹与 baseUrl）。 */
    fun rejected(r: KeyRejection) {
        try {
            emit(ConfigCheck.KEY_REJECTED, r.keyFp, r.baseUrl, ConfigCheck.rejectedMessage(r.status, r.reason, r.pauseMs))
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
    }

    private fun emit(code: String, keyFp: String, baseUrl: String, message: String) {
        val first = synchronized(lock) { seen.add(Triple(code, keyFp, baseUrl)) }
        if (!first) return
        try {
            sink(code, message)
        } catch (t: Throwable) {
            // 出口本身失败（普通 JVM 单测里 Log 可能抛）：吞掉
        }
    }

    /** 测试：清空去重集合（不动 sink）。 */
    fun clearSeenForTesting() {
        synchronized(lock) { seen.clear() }
    }

    /** 测试：sink 复位为 logcat，去重集合清空。 */
    fun resetForTesting() {
        sink = logcat
        clearSeenForTesting()
    }
}
