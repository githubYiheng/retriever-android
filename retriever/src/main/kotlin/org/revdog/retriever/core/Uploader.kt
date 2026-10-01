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

    /** 响应属于请求时的身份，而身份已经变了：丢弃不生效，调用方重拉（ADR 0019 决定 12）。 */
    var stale = false
}

/** 配置请求与请求时的身份 (install_id, user_id)：响应只在身份未变时生效。 */
internal class ConfigFetch(val request: HttpRequest, val installId: String, val userId: String?)

/**
 * 选下一批：p0 > p1 > p2，同级 created_ms 升序（失败过的批让到后面，避免头阻塞）；单在途；相邻请求 ≥ 2 s；
 * 全局或对应类别未暂停。读不出的批本轮跳过、试下一个（不递归：reconcileOutbox 每轮都会把它载回）。
 */
internal fun Engine.nextSend(): SendStep {
    val nowMono = clock.monoMs()
    if (key.isEmpty()) return SendStep.Stop("not_configured", null)
    // 本进程内存开关 ∧ 盘上无禁用标记（别的进程禁用也在这里挡住，ADR 0020 决定 2）
    if (!uploadAllowed()) return SendStep.Stop("disabled", null)
    // 未 bootstrap（install.json 写不进 / 读不了）：不碰磁盘直接停，bootstrap 成功后由 startup 重新排空
    if (install == null) return SendStep.Stop("not_bootstrapped", null)
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
    val eligible = candidates.filter { m ->
        val c = m.category
        !(c != null && c in pausedCats)
    }.sortedWith(
        compareBy<BatchMeta>({ if ((fails[it.name]?.count ?: 0) > 0) 1 else 0 }, { it.prio }, { it.createdMs }, { it.name }),
    )
    // candidates 非空时 eligible 为空只可能是类别暂停
    if (eligible.isEmpty()) return SendStep.Stop("paused", backoff.pausedUntilMono)
    if (!acquireUploadLock()) return SendStep.Stop("locked", nowMono + 60_000)
    for (pick in eligible) {
        // 读不出（坏块 / 权限）：不删、不隔离、不计 fail，留在出站箱下一轮再试，最终由容量驱逐兜底
        val body = Fs.read(File(outboxDir, pick.name)) ?: continue
        val req = HttpRequest(
            "POST", endpoint("v1/batches"),
            linkedMapOf(
                "Authorization" to "Bearer $key",
                "Content-Type" to "application/json",
                "Content-Encoding" to "gzip",
                // 每个批以自身信封里的 install_id 上报（ADR 0019 决定 10）：多进程清空窗口里别的 install 的批不被隔离
                "X-Rtv-Install" to pick.installId,
                "X-Rtv-Sent-Ms" to clock.wallMs().toString(),
                "X-Rtv-Sdk" to sdkHeader,
            ),
            body,
        )
        inFlight = pick.name
        lastRequestMono = nowMono
        return SendStep.Send(pick.name, req)
    }
    return SendStep.Stop("unreadable", null)
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
                ack(meta, (body["status"] as? String) == "stored")
                eff.acked = true
                val e = body["config_etag"] as? String
                if (e != null && e != (configCache?.config?.etag ?: "")) eff.fetchConfig = true
            } else {
                failure(name, null, "echo_mismatch", true)
            }
        }
        // 只有服务端明确表态（JSON 对象且 reason 是字符串，未知值也算）才鉴权暂停；边缘 / WAF / captive portal
        // 替服务端回的 401 / 403（HTML、空体、无 reason）按「其它」退避并计 fail（ADR 0011）
        401, 403 -> if (body?.get("reason") is String) authPause() else failure(name, null, "http_${response.status}", true)
        // 切分写不出（磁盘满等）：原批保留，按普通失败退避（不计毒批，批本身没错），免得每 2 s 重发一次再 413
        413 -> if (!split413(name)) failure(name, null, "http_413", false)
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

private fun Engine.ack(meta: BatchMeta, stored: Boolean) {
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
        // 服务端只在 stored 时写映射：被隔离的批不算映射已确认；别的 install 的批也不算本 install 的（ADR 0019 决定 10）
        if (stored && meta.installId == install?.installId) {
            val m = MappingState(meta.mappingUser, d, nowWall)
            mapping = m
            Fs.writeAtomic(mappingFile, m.encode())
        }
        val p = pendingMapping
        if (p != null && p.user == meta.mappingUser && p.digest == d) pendingMapping = null
    }
    backoff.attempt = 0
    backoff.nextAtMonoMs = 0
    backoff.nextAtWallMs = 0
    backoff.lastAckMs = nowWall
    // 任何一次 2xx 都复位暂停倍增状态（否则几个月前的一次 401 会让下次直接从更长时长起步）；已到期的暂停一并清掉，
    // 仍在生效的类别暂停（429 info / backfill）不动（ADR 0011）
    backoff.reason = ""
    if (backoff.pausedUntilMono <= clock.monoMs()) {
        backoff.pausedCategories = emptyList()
        backoff.pausedUntilMono = 0
        backoff.pausedUntilMs = 0
    }
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

/** 401 / 403 带 reason：全局暂停 1 h 起倍增到 24 h；照常写本地、照常拉配置；不删任何文件。 */
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
    if (key.isEmpty() || !uploadAllowed() || !effective.config.uploadEnabled) return null
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

/**
 * 配置请求（null = 不拉：没 key、没 bootstrap、或已禁用——禁用期间零联网，ADR 0020 决定 2）。
 * 带上请求时的身份 (install_id, user_id)，响应据此判断还属不属于当前身份。
 */
internal fun Engine.configRequest(): ConfigFetch? {
    if (key.isEmpty() || !uploadAllowed()) return null
    val inst = install ?: return null
    val user = writer.currentUser
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
    user?.let { h["X-Rtv-User"] = percentEncode(it) }
    // 发起即记「上次尝试时刻」：请求在途期间轮询候选不再是过去时（否则调度器以 0 ms 自旋到响应回来）
    lastConfigFetchMono = clock.monoMs()
    return ConfigFetch(HttpRequest("GET", endpoint("v1/config"), h, null), inst.installId, user)
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

/**
 * 拉到配置：钳制后缓存并生效；拉不到 / 非 200 / 非对象 → 用缓存（不放大）。配置属于请求时的身份（ADR 0019 决定 12）：
 * 请求发出后 install_id 或 user_id 变了 → 丢弃（`stale`），调用方按新身份重拉。
 */
internal fun Engine.applyConfigResponse(fetch: ConfigFetch, r: HttpResponse?): ConfigEffect {
    lastConfigFetchMono = clock.monoMs()
    if (fetch.installId != install?.installId || fetch.userId != writer.currentUser) return ConfigEffect().apply { stale = true }
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

/**
 * 身份（setUser 的值）变了：缓存里的配置属于上一个身份，按过期处理——放大型字段立即回落保守默认（U-2 同一路径），
 * 直到新身份的响应到达（ADR 0019 决定 12）。
 */
internal fun Engine.identityChanged(): ConfigEffect {
    configCache = configCache?.staleForIdentity()
    return applyEffective()
}

internal fun Engine.configPollDue(nowMono: Long): Boolean {
    if (key.isEmpty()) return false
    val l = lastConfigFetchMono ?: return true
    return nowMono - l >= Limits.CONFIG_POLL_INTERVAL_S * 1000L
}
