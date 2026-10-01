package org.revdog.retriever.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// 批次物化（方案 §3.5）。与 iOS `Materializer.swift` 逐字一致。

internal class OLine(val seq: Long, val oseq: Long, val ts: Long, val rank: Int, val raw: ByteArray)

internal class Chunk(val user: String?) {
    val lines = ArrayList<OLine>()
    var bytes = 0
    var hasError = false
    var ctx: List<OLine> = emptyList()
    var ctxTruncated: Long? = null
    var extras = false
    var mapping = false
}

/**
 * 义务行 = 本会话 oseq ∈ (extracted_through_oseq, targetOseq]，按 oseq 连续取走；
 * 用户边界与 oseq 缺口（write_failed / 驱逐 / 损坏已记墓碑）处切批；解压后 ≤ 768 KB，超出按 oseq 切多批；
 * 本批含 error / fatal 时附 ctx（flush(includeContext = false) 的 noCtx 除外）；没有义务行 → 不产生批次。
 */
internal fun Engine.materialize(s: SessionRecord, targetOseq: Long, targetSeq: Long, noCtx: Boolean, ignoreCap: Boolean) {
    val now = clock.wallMs()
    val inst = install ?: return
    val cache = HashMap<Int, SegmentFile?>()
    fun load(info: SegInfo): SegmentFile? {
        if (cache.containsKey(info.segNo)) return cache[info.segNo]
        val f = Segments.read(info.file, false)
        cache[info.segNo] = f
        return f
    }

    val from = s.cursor.extractedThroughOseq + 1
    val ob = ArrayList<Pair<OLine, String?>>()
    if (targetOseq >= from) {
        val missing = ArrayList<SegInfo>()
        for (info in s.sealed) {
            if (!(info.obligCount > 0 && info.lastOseq >= from && info.firstOseq <= targetOseq)) continue
            // 读失败 ≠ 没有内容（ADR 0024 决定 3）：读失败（非 ENOENT）本次不推进、不删、留待下次；确已不存在 → 墓碑后推进
            val f = when (val r = Fs.readResult(info.file)) {
                is Fs.ReadResult.Ok -> Segments.parse(info.file, r.bytes, false)
                Fs.ReadResult.Missing -> {
                    missing.add(info)
                    continue
                }
                Fs.ReadResult.Failed -> null
            }
            if (f == null) {
                readRetryPending = true
                return
            }
            cache[info.segNo] = f
            for (l in f.lines) {
                if (l.oseq in from..targetOseq) {
                    ob.add(Pair(OLine(l.seq, l.oseq, l.ts, l.levelRank, f.data.copyOfRange(l.start, l.end)), info.userId))
                }
            }
        }
        if (missing.isNotEmpty()) {
            val tombs = missing.map {
                val a = maxOf(it.firstOseq, from)
                val b = minOf(it.lastOseq, targetOseq)
                DropEntry(s.meta.sessionId, a, b, b - a + 1, DropReason.CORRUPT, now, lastAckAge(now))
            }
            if (!appendDrops(tombs)) {
                readRetryPending = true
                return
            }
            s.sealed.removeAll(missing.toSet())
        }
        ob.sortBy { it.first.oseq }
    }
    if (ob.isEmpty()) {
        if (targetOseq >= from) {
            // 区间内的行都不在盘上（写失败 / 已驱逐 / 段文件消失，均已记墓碑）
            s.cursor.extractedThroughOseq = targetOseq
            writeCursor(s)
        }
        return
    }
    val hasError = ob.any { it.first.rank >= Level.ERROR }
    val cap = effective.config.dailyBatchCap
    if (!ignoreCap && !hasError && cap > 0 && todayBatches(now) >= cap) {
        // daily_batch_cap：超出的 p1 合并到下一次封段（p0 不受限；日志不丢只是攒大包，ADR 0005）
        return
    }

    val budget = Limits.BATCH_UNCOMPRESSED_BYTES_CLIENT
    val isCurrent = s === current

    // 头部字节上限估计：用最大位数的数字占位，外加 extras 的真实字节
    fun headerBytes(user: String?, extras: Extras?, withMapping: Boolean): Int {
        val big = 9_007_199_254_740_991L
        val h = EnvelopeHeader(
            Ids.BatchKind.PRIMARY, Ids.NAMESPACE, 9_999_999_999_999L, "2026-09-29", inst.installId,
            s.meta.sessionId, s.meta.sessionNo, s.meta.process, user, s.meta.device, big, big, big, big, big,
        )
        if (extras != null) {
            h.drops = extras.drops
            h.closedSessions = extras.closed
        }
        if (withMapping) h.mapping = MappingBlock(user, s.meta.device)
        return h.encodePrefix().size + 2
    }

    // extras（drops / closed_sessions）只随本次物化的第一批
    val extras = takeExtras()
    val chunks = ArrayList<Chunk>()
    var curChunk: Chunk? = null
    var curHeader = 0
    var prevOseq = -1L
    var prevUserSet = false
    var prevUser: String? = null
    var mappingPendingFor: PendingMapping? = null
    val digest = Engine.digest(s.meta.device)
    fun startChunk(user: String?): Chunk {
        val c = Chunk(user)
        c.extras = chunks.isEmpty() && curChunk == null
        val pf = mappingPendingFor
        if (isCurrent && needMapping(user, digest, now) && !(pf != null && pf.user == user && pf.digest == digest)) {
            c.mapping = true
            mappingPendingFor = PendingMapping(user, digest)
        }
        curHeader = headerBytes(user, if (c.extras) extras else null, c.mapping)
        curChunk = c
        return c
    }
    for ((l, user) in ob) {
        val cost = l.raw.size + 1
        val boundary = !prevUserSet || prevUser != user || l.oseq != prevOseq + 1
        val cc = curChunk
        val c: Chunk = if (cc == null) {
            startChunk(user)
        } else if (boundary || curHeader + cc.bytes + cost > budget) {
            chunks.add(cc)
            startChunk(user)
        } else {
            cc
        }
        c.lines.add(l)
        c.bytes += cost
        if (l.rank >= Level.ERROR) c.hasError = true
        prevOseq = l.oseq
        prevUser = user
        prevUserSet = true
    }
    curChunk?.let { chunks.add(it) }

    // ctx：附在最后一个含 error 的批
    val ctxTarget = if (noCtx) -1 else chunks.indexOfLast { it.hasError }
    if (ctxTarget >= 0) {
        val t = chunks[ctxTarget]
        val user = t.user
        val hb = headerBytes(user, if (t.extras) extras else null, t.mapping)
        val room = budget - hb - t.bytes
        val byteBudget = minOf(effective.config.contextBytes, room)
        val lineBudget = effective.config.contextLines
        val taken = ArrayList<OLine>()
        var takenBytes = 0
        var truncated = 0L
        var stop = false
        val through = s.cursor.ctxThroughSeq
        for (info in s.sealed.asReversed()) {
            if (info.lineCount == 0 || info.firstSeq > targetSeq) continue
            if (info.userId != user || info.lastSeq <= through) break
            if (info.lineCount == info.obligCount) continue
            if (stop && info.firstSeq > through && info.lastSeq <= targetSeq) {
                truncated += (info.lineCount - info.obligCount).toLong()
                continue
            }
            val f = load(info) ?: continue
            for (l in f.lines.asReversed()) {
                if (!(l.oseq == 0L && l.seq > through && l.seq <= targetSeq)) continue
                if (stop) {
                    truncated += 1
                    continue
                }
                val raw = Segments.withCtx(f.data, l.start, l.end)
                val cost = raw.size + 1
                if (taken.size < lineBudget && takenBytes + cost <= byteBudget) {
                    taken.add(OLine(l.seq, 0, l.ts, l.levelRank, raw))
                    takenBytes += cost
                } else {
                    stop = true
                    truncated += 1
                }
            }
        }
        t.ctx = taken.asReversed().toList()
        t.ctxTruncated = truncated
        s.cursor.ctxThroughSeq = maxOf(s.cursor.ctxThroughSeq, targetSeq)
    }

    // 写批：信封 JSON → gzip → outbox/tmp-* → sync → rename（提交点）；全部成功后原子写 cursor
    var committedThrough = s.cursor.extractedThroughOseq
    var usedExtras = false
    for (c in chunks) {
        val all = ArrayList<OLine>(c.lines)
        if (c.ctx.isNotEmpty()) {
            all.addAll(c.ctx)
            all.sortBy { it.seq }
        }
        val tsMin = all.minOf { it.ts }
        val oseqFrom = c.lines.first().oseq
        val bid = Ids.batchId(inst.installId, s.meta.sessionId, Ids.BatchKind.PRIMARY, oseqFrom) ?: break
        val h = EnvelopeHeader(
            Ids.BatchKind.PRIMARY, bid, now, Day.clientDay(tsMin, now), inst.installId, s.meta.sessionId,
            s.meta.sessionNo, s.meta.process, c.user, s.meta.device, all.first().seq, all.last().seq,
            oseqFrom, c.lines.last().oseq, c.ctxTruncated,
        )
        if (c.extras) {
            h.drops = extras.drops
            h.closedSessions = extras.closed
            usedExtras = true
        }
        if (c.mapping) h.mapping = MappingBlock(c.user, s.meta.device)
        if (writeBatch(h, all.map { it.raw }, if (c.hasError) 0 else 1) == null) {
            if (c.extras) usedExtras = false
            break
        }
        if (c.mapping) pendingMapping = PendingMapping(c.user, digest)
        countBatch(now)
        committedThrough = c.lines.last().oseq
    }
    if (!usedExtras) releaseExtras(extras)
    // 区间尾部若因缺口没有行，也视为已处理（墓碑已覆盖）
    if (committedThrough == ob.last().first.oseq) committedThrough = maxOf(committedThrough, targetOseq)
    s.cursor.extractedThroughOseq = committedThrough
    writeCursor(s)
}

/** 映射块：user_id 或设备摘要相对 mapping.json 变化、或距 acked_ms ≥ 24 h。 */
internal fun Engine.needMapping(user: String?, digest: String, now: Long): Boolean {
    val p = pendingMapping
    if (p != null && p.user == user && p.digest == digest) return false
    val m = mapping ?: return true
    // 确认它的请求用的是别的 key / 服务端：对当前 key 按未确认（ADR 0024 决定 7）；旧版本写的（没有这两个键）按相同
    if (!sameIdentity(m.hasIdentity, m.keyFp, m.baseUrl)) return true
    return m.userId != user || m.deviceDigest != digest || now - m.ackedMs >= Limits.MAPPING_REFRESH_MS
}

/**
 * 取本批要带的 drops（≤ 100）与 closed_sessions（≤ 20）：按文件顺序取最旧的、未在途的前 N 条；携带不改写文件、
 * 不合并、不计数，带不完的留给下一批（ADR 0019 决定 2）。条目留在 jsonl 里直到携带它的批 2xx；在途的用内存集合排除，避免重复携带。
 */
internal fun Engine.takeExtras(): Extras = locks.withDirLock {
    val drops = readDropsLocked().filter { it !in embeddedDrops }.take(Limits.DROPS_PER_BATCH)
    embeddedDrops.addAll(drops)
    val closed = readClosedLocked().filter { it.sessionId !in embeddedClosed }.take(Limits.CLOSED_SESSIONS_PER_BATCH)
    embeddedClosed.addAll(closed.map { it.sessionId })
    Extras(drops, closed)
} ?: Extras(emptyList(), emptyList())

internal fun Engine.releaseExtras(e: Extras) {
    embeddedDrops.removeAll(e.drops.toSet())
    embeddedClosed.removeAll(e.closed.map { it.sessionId }.toSet())
}

/** 写一个批文件；返回元数据（失败 null，不留半成品：tmp 在失败时删除，启动时也清理）。 */
internal fun Engine.writeBatch(h: EnvelopeHeader, lines: List<ByteArray>, prio: Int): BatchMeta? {
    val json = h.encode(lines)
    val gz = Gzip.compress(json) ?: return null
    val tmp = File(outboxDir, "tmp-$processName-${Ids.newV4()}")
    val ok = try {
        FileOutputStream(tmp).use { out ->
            out.write(gz)
            out.fd.sync()
        }
        true
    } catch (e: IOException) {
        false
    }
    val name = OutboxName.make(prio, h.createdMs, h.batchId)
    val dest = File(outboxDir, name)
    if (!ok || !tmp.renameTo(dest)) {
        tmp.delete()
        return null
    }
    val meta = batchMeta(name, h, lines, gz.size.toLong())
    metas[name] = meta
    batchesWritten += 1
    return meta
}

/**
 * 批的元数据。级别与是否 ctx 按位置判断（ADR 0024 决定 9）：级别取行首固定前缀里解析出的值，ctx 看行尾固定位置——
 * attrs 里的同名键（`level`、`ctx`）不影响优先级。解析不出前缀的行（读不出的旧批）保守按 warn 计。
 */
internal fun batchMeta(name: String, h: EnvelopeHeader, lines: List<ByteArray>, bytes: Long): BatchMeta {
    var warn = false
    var err = false
    for (l in lines) {
        val rank = Segments.parsePrefix(l, 0, l.size)?.levelRank ?: Level.WARN
        if (rank >= Level.WARN) warn = true
        if (rank >= Level.ERROR && !Segments.isCtxLine(l, 0, l.size)) err = true
    }
    val prio = OutboxName.parse(name)?.prio ?: 1
    val m = h.mapping
    return BatchMeta(
        name, prio, h.createdMs, h.batchId, h.installId, h.kind, h.sessionId, h.oseqFrom ?: 0, h.oseqTo ?: 0, lines.size, warn, err,
        h.drops, h.closedSessions, m != null, m?.userId, m?.let { Engine.digest(it.device) }, bytes,
    )
}

// MARK: backfill（full_dump 生效时；§3.5 / §5）

/**
 * 对 RETAINED 段中**全部非义务行**按段生成 backfill 批（p2，不带 oseq）；与已上传 ctx 重复的行由读侧按
 * (session_id, seq) 去重（主代理 2026-09-29 裁决）。每段一批，batch_id = `…:backfill:<seg_no>`；
 * 超 768 KB 的段按 seq 切多批，batch_id = `…:backfill:<seg_no>:<seq_from>`。本进程内同一段只生成一次。
 */
internal fun Engine.materializeBackfill(budget: Int = Limits.BATCH_UNCOMPRESSED_BYTES_CLIENT) {
    val inst = install ?: return
    val now = clock.wallMs()
    for (s in ownSessions) {
        for (info in s.sealed.toList()) {
            if (info.lineCount <= info.obligCount) continue
            val key = "${s.meta.sessionId}:${info.segNo}"
            if (key in backfilledSegs) continue
            val f = Segments.read(info.file, false) ?: continue
            val lines = f.lines.filter { it.oseq == 0L }
                .map { OLine(it.seq, 0, it.ts, it.levelRank, f.data.copyOfRange(it.start, it.end)) }
                .sortedBy { it.seq }
            if (lines.isEmpty()) {
                backfilledSegs.add(key)
                continue
            }
            fun header(part: List<OLine>, batchId: String): EnvelopeHeader = EnvelopeHeader(
                Ids.BatchKind.BACKFILL, batchId, now, Day.clientDay(part.minOf { it.ts }, now), inst.installId,
                s.meta.sessionId, s.meta.sessionNo, s.meta.process, info.userId, s.meta.device,
                part.first().seq, part.last().seq, null, null, null,
            )
            // 按 seq 切：头部按最大位数占位估算
            val headBytes = header(lines, Ids.NAMESPACE).encodePrefix().size + 2 + 64
            val parts = ArrayList<ArrayList<OLine>>()
            parts.add(ArrayList())
            var size = 0
            for (l in lines) {
                val cost = l.raw.size + 1
                if (parts.last().isNotEmpty() && headBytes + size + cost > budget) {
                    parts.add(ArrayList())
                    size = 0
                }
                parts.last().add(l)
                size += cost
            }
            var ok = true
            for (part in parts) {
                val bid = if (parts.size == 1) {
                    Ids.batchId(inst.installId, s.meta.sessionId, Ids.BatchKind.BACKFILL, info.segNo.toLong())
                } else {
                    Ids.backfillSplitBatchId(inst.installId, s.meta.sessionId, info.segNo.toLong(), part.first().seq)
                }
                if (bid == null || writeBatch(header(part, bid), part.map { it.raw }, 2) == null) {
                    ok = false
                    break
                }
            }
            if (!ok) return
            backfilledSegs.add(key)
        }
    }
}
