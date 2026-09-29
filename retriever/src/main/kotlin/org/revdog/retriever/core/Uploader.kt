package org.revdog.retriever.core

import java.io.File
import kotlin.random.Random

// 出站队列与响应分类（§3.6 / §3.7，宪法 R-2 / R-3）。决策在引擎线程上，网络在引擎线程外。与 iOS `Uploader.swift` 逐字一致。

internal sealed class SendStep {
    class Send(val name: String, val request: HttpRequest) : SendStep()

    class Stop(val reason: String, val wakeMono: Long?) : SendStep()
}

internal class ResponseEffect {
    var fetchConfig = false
    var acked = false
}

internal class ConfigEffect {
    var sealed = false
    var backfill = false
}

/**
 * 选下一批：p0 > p1 > p2，同级 created_ms 升序（失败过的批让到后面，避免头阻塞）；单在途；相邻请求 ≥ 2 s；
 * 全局或对应类别未暂停；backfill 在计量网络上不传（backfill_networks = unmetered）。
 */
internal fun Engine.nextSend(): SendStep {
    val nowMono = clock.monoMs()
    if (key.isEmpty()) return SendStep.Stop("not_configured", null)
    if (!enabled) return SendStep.Stop("disabled", null)
    if (!effective.config.uploadEnabled) return SendStep.Stop("upload_disabled", null)
    val pauseActive = backoff.pausedUntilMono > nowMono
    if (pauseActive && "all" in backoff.pausedCategories) return SendStep.Stop("paused", backoff.pausedUntilMono)
    if (backoff.nextAtMonoMs > nowMono) {
        return SendStep.Stop(if (backoff.reason == "network") "offline" else "backoff", backoff.nextAtMonoMs)
    }
    val last = lastRequestMono
    if (last != null && nowMono < last + Limits.MIN_REQUEST_SPACING_MS) {
        return SendStep.Stop("spacing", last + Limits.MIN_REQUEST_SPACING_MS)
    }
    reconcileOutbox()
    val candidates = metas.values.filter { it.prio < 3 }
    if (candidates.isEmpty()) return SendStep.Stop("empty", null)
    val pausedCats: Set<String> = if (pauseActive) backoff.pausedCategories.toSet() else emptySet()
    val meteredBlock = effective.config.backfillNetworks == "unmetered" && platform.isExpensiveNetwork()
    val eligible = candidates.filter { m ->
        val c = m.category
        !(c != null && c in pausedCats) && !(m.kind == Ids.BatchKind.BACKFILL && meteredBlock)
    }.sortedWith(
        compareBy<BatchMeta>({ if ((fails[it.name]?.count ?: 0) > 0) 1 else 0 }, { it.prio }, { it.createdMs }, { it.name }),
    )
    val pick = eligible.firstOrNull()
        ?: return if (pausedCats.isNotEmpty()) SendStep.Stop("paused", backoff.pausedUntilMono) else SendStep.Stop("metered", null)
    if (!acquireUploadLock()) return SendStep.Stop("locked", nowMono + 60_000)
    val body = Fs.read(File(outboxDir, pick.name))
    val inst = install
    if (body == null || inst == null) {
        metas.remove(pick.name)
        return nextSend()
    }
    val req = HttpRequest(
        "POST", endpoint("v1/batches"),
        linkedMapOf(
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json",
            "Content-Encoding" to "gzip",
            "X-Rtv-Install" to inst.installId,
            "X-Rtv-Sent-Ms" to clock.wallMs().toString(),
            "X-Rtv-Sdk" to sdkHeader,
        ),
        body,
    )
    inFlight = pick.name
    lastRequestMono = nowMono
    return SendStep.Send(pick.name, req)
}

// MARK: 上传锁（多进程：只有拿到 upload.lock 的一方排空；每轮排空结束释放）

internal fun Engine.acquireUploadLock(): Boolean = locks.tryUploadLock()

internal fun Engine.releaseUploadLock() {
    locks.releaseUploadLock()
}

// MARK: 响应分类

internal fun Engine.handleResponse(name: String, response: HttpResponse?): ResponseEffect {
    val eff = ResponseEffect()
    if (inFlight == name) inFlight = null
    val meta = metas[name] ?: return eff
    if (response == null) {
        failure(name, null, "network", true)
        return eff
    }
    val body = JsonIn.obj(response.body)
    when (response.status) {
        in 200..299 -> {
            // 回显的 batch_id 与本批一致才算确认（防 captive portal）；stored 与 quarantined 都算
            if (body != null && (body["batch_id"] as? String) == meta.batchId) {
                ack(meta)
                eff.acked = true
                val e = body["config_etag"] as? String
                if (e != null && e != (configCache?.config?.etag ?: "")) eff.fetchConfig = true
            } else {
                failure(name, null, "echo_mismatch", true)
            }
        }
        401, 403 -> authPause()
        413 -> split413(name)
        429 -> categoryPause(body, response.headers["retry-after"])
        503 -> failure(name, retryAfter(body, response.headers["retry-after"]), "http_503", false)
        else -> failure(name, null, "http_${response.status}", true)
    }
    return eff
}

internal fun retryAfter(body: Map<String, Any?>?, header: String?): Int? {
    val v = JsonIn.double(body?.get("retry_after_s"))
    if (v != null && !v.isNaN() && !v.isInfinite()) return v.toInt()
    return header?.trim()?.toIntOrNull()
}

internal fun clampRetryAfterMs(s: Int): Long = s.coerceIn(Limits.RETRY_AFTER_MIN_S, Limits.RETRY_AFTER_MAX_S) * 1000L

internal fun jitter(ms: Long): Long {
    val f = (1 - Limits.BACKOFF_JITTER) + Random.nextDouble() * 2 * Limits.BACKOFF_JITTER
    return Math.round(ms * f)
}

/** backoff(attempt) = min(1 s × 2^attempt, 15 min) × (1 ± 20%) */
internal fun backoffMs(attempt: Int): Long {
    val shift = minOf(attempt, 30)
    val base = minOf(Limits.BACKOFF_BASE_MS shl shift, Limits.BACKOFF_MAX_MS)
    return jitter(base)
}

private fun Engine.ack(meta: BatchMeta) {
    val nowWall = clock.wallMs()
    Fs.remove(File(outboxDir, meta.name))
    metas.remove(meta.name)
    fails.remove(meta.name)
    for (f in fails.values) f.otherSuccess = true
    if (meta.kind == Ids.BatchKind.PRIMARY && meta.oseqFrom > 0) {
        ackedRanges.add(AckedRange(meta.sessionId, meta.oseqFrom, meta.oseqTo))
        if (ackedRanges.size > 512) ackedRanges.subList(0, ackedRanges.size - 512).clear()
    }
    // 删已报墓碑与会话终态（按原样匹配）
    if (meta.drops.isNotEmpty() || meta.closed.isNotEmpty()) {
        locks.withDirLock {
            if (meta.drops.isNotEmpty()) {
                val gone = meta.drops.toSet()
                Fs.writeAtomic(dropsFile, Jsonl.encodeDrops(readDropsLocked().filter { it !in gone }))
            }
            if (meta.closed.isNotEmpty()) {
                val gone = meta.closed.map { it.sessionId }.toSet()
                Fs.writeAtomic(sessionsFile, Jsonl.encodeClosed(readClosedLocked().filter { it.sessionId !in gone }))
            }
        }
        embeddedDrops.removeAll(meta.drops.toSet())
        embeddedClosed.removeAll(meta.closed.map { it.sessionId }.toSet())
    }
    val d = meta.mappingDigest
    if (meta.hasMapping && d != null) {
        val m = MappingState(meta.mappingUser, d, nowWall)
        mapping = m
        Fs.writeAtomic(mappingFile, m.encode())
        val p = pendingMapping
        if (p != null && p.user == meta.mappingUser && p.digest == d) pendingMapping = null
    }
    backoff.attempt = 0
    backoff.nextAtMonoMs = 0
    backoff.nextAtWallMs = 0
    backoff.lastAckMs = nowWall
    if ("all" !in backoff.pausedCategories) backoff.reason = ""
    persistBackoff()
}

/** 全局退避：next = now + max(clamp(Retry-After), backoff)；非 429 的失败计入该批 fail（毒批判定）。 */
private fun Engine.failure(name: String, retryAfterS: Int?, reason: String, countFail: Boolean) {
    val nowMono = clock.monoMs()
    val nowWall = clock.wallMs()
    var wait = backoffMs(backoff.attempt)
    if (retryAfterS != null) wait = maxOf(wait, clampRetryAfterMs(retryAfterS))
    backoff.attempt += 1
    backoff.nextAtMonoMs = nowMono + wait
    backoff.nextAtWallMs = nowWall + wait
    if (!backoff.reason.startsWith("auth:")) backoff.reason = reason
    persistBackoff()
    if (!countFail) return
    val f = fails.getOrPut(name) { FailCount(0, false) }
    f.count += 1
    // 同一批连续 5 次非 429 失败、且期间有别的批成功过 → 隔离；所有批都在失败则视为服务端故障，不隔离
    if (f.count >= Limits.POISON_CONSECUTIVE_FAILS && f.otherSuccess) quarantine(name)
}

/** 401 / 403：全局暂停 1 h 起倍增到 24 h；照常写本地、照常拉配置；不删任何文件。 */
private fun Engine.authPause() {
    val prev: Long? = if (backoff.reason.startsWith("auth:")) backoff.reason.substring(5).toLongOrNull() else null
    val dur = if (prev != null) minOf(prev * 2, Limits.PAUSE_401_MAX_MS) else Limits.PAUSE_401_BASE_MS
    backoff.pausedCategories = listOf("all")
    backoff.pausedUntilMono = clock.monoMs() + dur
    backoff.pausedUntilMs = clock.wallMs() + dur
    backoff.reason = "auth:$dur"
    persistBackoff()
}

/** 429：按 categories 暂停到 now + retry_after_s（单调；钳制 1 s–1 h；±20% 抖动）；`all` = 全局。不计毒批。 */
private fun Engine.categoryPause(body: Map<String, Any?>?, header: String?) {
    val ra = retryAfter(body, header) ?: 60
    val wait = jitter(clampRetryAfterMs(ra))
    val cats = (JsonIn.asList(body?.get("categories")) ?: emptyList()).mapNotNull { it as? String }
    val known = cats.filter { it == "info" || it == "backfill" }
    backoff.pausedCategories = if ("all" in cats || known.isEmpty()) listOf("all") else known
    backoff.pausedUntilMono = clock.monoMs() + wait
    backoff.pausedUntilMs = clock.wallMs() + wait
    if (!backoff.reason.startsWith("auth:")) backoff.reason = "429:${(body?.get("reason") as? String) ?: ""}"
    persistBackoff()
}

/** 排空的下一次唤醒（退避 / 暂停到期）。 */
internal fun Engine.uploadWakeMono(): Long? {
    if (key.isEmpty() || !enabled || !effective.config.uploadEnabled) return null
    if (metas.values.none { it.prio < 3 }) return null
    val now = clock.monoMs()
    val w = ArrayList<Long>()
    if (backoff.nextAtMonoMs > now) w.add(backoff.nextAtMonoMs)
    if (backoff.pausedUntilMono > now) w.add(backoff.pausedUntilMono)
    val l = lastRequestMono
    if (l != null && l + Limits.MIN_REQUEST_SPACING_MS > now) w.add(l + Limits.MIN_REQUEST_SPACING_MS)
    return w.maxOrNull()
}

// MARK: 远程配置（§5）

internal fun Engine.configRequest(): HttpRequest? {
    if (key.isEmpty()) return null
    val inst = install ?: return null
    val h = linkedMapOf(
        "Authorization" to "Bearer $key",
        "X-Rtv-Install" to inst.installId,
        "X-Rtv-Sdk" to sdkHeader,
        "X-Rtv-App-Version" to device.appVersion,
        "X-Rtv-Upload-Level" to host.uploadLevel.wire,
        "X-Rtv-Local-Level" to (host.localLevel ?: org.revdog.retriever.LogLevel.DEBUG).wire,
        "X-Rtv-Daily-Batch-Cap" to (host.dailyBatchCap ?: 0).toString(),
        "X-Rtv-Local-Cap-Bytes" to host.localCapBytes.toString(),
    )
    // 值一律 percent-encode（ASCII 字母数字以外全部编码，服务端 decodeURIComponent）
    writer.currentUser?.let { h["X-Rtv-User"] = percentEncode(it) }
    return HttpRequest("GET", endpoint("v1/config"), h, null)
}

internal fun percentEncode(s: String): String {
    val b = Text.utf8(s)
    val sb = StringBuilder(b.size * 3)
    for (x in b) {
        val v = x.toInt() and 0xFF
        val c = v.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
            sb.append(c)
        } else {
            sb.append('%').append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 0xF])
        }
    }
    return sb.toString()
}

/** 拉到配置：钳制后缓存并生效；拉不到 / 非 200 / 非对象 → 用缓存（不放大）。 */
internal fun Engine.applyConfigResponse(r: HttpResponse?): ConfigEffect {
    lastConfigFetchMono = clock.monoMs()
    if (r == null || r.status != 200) return ConfigEffect()
    val o = JsonIn.obj(r.body) ?: return ConfigEffect()
    val cfg = ConfigRules.clamp(o, host)
    val nowWall = clock.wallMs()
    configCache = ConfigCache(cfg, nowWall, clock.monoMs())
    val file = JsonOut()
    file.raw("{\"fetched_ms\":"); file.int(nowWall)
    file.raw(",\"config\":"); file.raw(ConfigRules.encode(cfg))
    file.raw("}")
    Fs.writeAtomic(configFile, file.toByteArray())
    return applyEffective()
}

/** 重新计算生效配置并推给写入侧；full_dump 生效 / upload_enabled 变化触发封段，full_dump 生效生成 backfill。 */
internal fun Engine.applyEffective(): ConfigEffect {
    val eff = ConfigEffect()
    val old = effective
    effective = ConfigCache.effective(configCache, host, clock.wallMs(), clock.monoMs())
    writer.setLevels(effective.uploadLevel, effective.config.localLevel, effective.config.flushIntervalS)
    if (!old.fullDumpActive && effective.fullDumpActive) {
        writer.rotate(SealReason.FULL_DUMP)
        processSeals()
        materializeBackfill()
        eff.sealed = true
        eff.backfill = true
    }
    if (old.config.uploadEnabled != effective.config.uploadEnabled) {
        if (writer.rotate(SealReason.UPLOAD_ENABLED)) processSeals()
        eff.sealed = true
    }
    if (old.config.localCapBytes != effective.config.localCapBytes) evictIfNeeded()
    return eff
}

internal fun Engine.configPollDue(nowMono: Long): Boolean {
    if (key.isEmpty()) return false
    val l = lastConfigFetchMono ?: return true
    return nowMono - l >= Limits.CONFIG_POLL_INTERVAL_S * 1000L
}
