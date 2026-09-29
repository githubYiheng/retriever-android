package org.revdog.retriever.core

import java.io.File

// 出站箱（§3.2 / §3.7 / §3.8）：元数据、驱逐、隔离、413 切分。与 iOS `Outbox.swift` 逐字一致。

private val LINES_KEY = Bytes.ascii("\"lines\":[")

/** 解压并拆出信封头与行（行字节原样）。 */
internal class ParsedBatch(val header: EnvelopeHeader, val lines: List<ByteArray>)

internal fun parseBatch(gz: ByteArray): ParsedBatch? {
    val json = Gzip.decompress(gz) ?: return null
    val at = Bytes.find(LINES_KEY, json)
    if (at < 0) return null
    val headJson = json.copyOfRange(0, at + LINES_KEY.size) + Bytes.ascii("]}")
    val o = JsonIn.obj(headJson) ?: return null
    val h = EnvelopeHeader.decode(o) ?: return null
    // 顶层行数组：按括号深度切（字符串内的括号 / 引号转义要跳过）
    val lines = ArrayList<ByteArray>()
    var i = at + LINES_KEY.size
    var depth = 0
    var inStr = false
    var esc = false
    var start = -1
    while (i < json.size) {
        val c = json[i].toInt()
        if (inStr) {
            if (esc) esc = false else if (c == 0x5C) esc = true else if (c == 0x22) inStr = false
        } else if (c == 0x22) {
            inStr = true
        } else if (c == 0x7B) {
            if (depth == 0) start = i
            depth += 1
        } else if (c == 0x7D) {
            depth -= 1
            if (depth == 0 && start >= 0) {
                lines.add(json.copyOfRange(start, i + 1))
                start = -1
            }
        } else if (c == 0x5D && depth == 0) {
            break
        }
        i++
    }
    return ParsedBatch(h, lines)
}

internal fun Engine.loadMeta(name: String): BatchMeta? {
    val gz = Fs.read(File(outboxDir, name)) ?: return null
    val p = parseBatch(gz) ?: return null
    return batchMeta(name, p.header, p.lines, gz.size.toLong())
}

/** 与磁盘对齐（出站箱在多进程间共享）：新文件读元数据，消失的文件移出缓存。 */
internal fun Engine.reconcileOutbox() {
    val seen = HashSet<String>()
    for (name in Fs.list(outboxDir)) {
        val p = OutboxName.parse(name) ?: continue
        seen.add(name)
        if (metas[name] == null) {
            val m = loadMeta(name)
            metas[name] = m
                ?: // 读不出（位腐烂等）：保守当作含 warn 的 primary，交给服务端隔离
                BatchMeta(
                    name, p.prio, p.createdMs, p.batchId, Ids.BatchKind.PRIMARY, "", 0, 0, 0, true, p.prio == 0,
                    emptyList(), emptyList(), false, null, null, Fs.size(File(outboxDir, name)) ?: 0,
                )
        }
    }
    metas.keys.retainAll(seen)
}

/** 启动：清 tmp、扫出站箱、重建「在途携带」集合与今日批数。 */
internal fun Engine.scanOutboxAtStartup() {
    val nowWall = clock.wallMs()
    for (name in Fs.list(outboxDir)) {
        if (!name.startsWith("tmp-")) continue
        val f = File(outboxDir, name)
        // 自己进程名的 tmp 一定是崩溃残留；别的进程的只清 1 h 以前的（可能正在构建）
        if (name.startsWith("tmp-$processName-") || (Fs.mtimeMs(f) ?: 0) < nowWall - 3_600_000) Fs.remove(f)
    }
    for (name in Fs.list(root)) {
        if (!(name.startsWith(".") && name.contains(".tmp-"))) continue
        val f = File(root, name)
        if ((Fs.mtimeMs(f) ?: 0) < nowWall - 3_600_000) Fs.remove(f)
    }
    reconcileOutbox()
    embeddedDrops.clear()
    embeddedClosed.clear()
    pendingMapping = null
    val today = Day.fromMs(nowWall)
    todayDay = today
    todayCount = 0
    for (m in metas.values) {
        embeddedDrops.addAll(m.drops)
        embeddedClosed.addAll(m.closed.map { it.sessionId })
        if (m.hasMapping && m.mappingDigest != null) pendingMapping = PendingMapping(m.mappingUser, m.mappingDigest)
        if (m.prio <= 1 && Day.fromMs(m.createdMs) == today) todayCount += 1
    }
}

// MARK: 隔离（毒批）

internal fun Engine.quarantine(name: String) {
    val m = metas[name] ?: return
    val q = OutboxName.make(3, m.createdMs, m.batchId)
    val src = File(outboxDir, name)
    val dst = File(outboxDir, q)
    if (!src.renameTo(dst)) return
    Fs.touch(dst, clock.wallMs())
    metas.remove(name)
    m.name = q
    m.prio = 3
    metas[q] = m
    fails.remove(name)
}

/** q-* 在 24 h 后或 app_version 变化后回到队列。 */
internal fun Engine.releaseQuarantine(force: Boolean) {
    val now = clock.wallMs()
    for ((name, m) in metas.entries.toList()) {
        if (m.prio != 3) continue
        val f = File(outboxDir, name)
        val since = Fs.mtimeMs(f) ?: 0
        if (!(force || now - since >= ClientConstants.QUARANTINE_RETRY_MS)) continue
        val prio = if (m.kind == Ids.BatchKind.BACKFILL) 2 else if (m.hasError) 0 else 1
        val p = OutboxName.make(prio, m.createdMs, m.batchId)
        if (f.renameTo(File(outboxDir, p))) {
            metas.remove(name)
            m.name = p
            m.prio = prio
            metas[p] = m
        }
    }
}

/** 最早的隔离批再试时刻（单调）。 */
internal fun Engine.nextQuarantineReleaseMono(): Long? {
    val nowWall = clock.wallMs()
    val nowMono = clock.monoMs()
    var best: Long? = null
    for ((name, m) in metas) {
        if (m.prio != 3) continue
        val since = Fs.mtimeMs(File(outboxDir, name)) ?: nowWall
        val at = nowMono + maxOf(0, since + ClientConstants.QUARANTINE_RETRY_MS - nowWall)
        best = minOf(best ?: at, at)
    }
    return best
}

// MARK: 413：按 oseq（或 ctx）二分重物化（新的确定性 batch_id），原批删除

private class SplitLine(val raw: ByteArray, val seq: Long, val oseq: Long, val ts: Long, val rank: Int, val ctx: Boolean)

internal fun Engine.split413(name: String) {
    val file = File(outboxDir, name)
    val gz = Fs.read(file)
    val p = gz?.let { parseBatch(it) }
    if (p == null || p.header.kind != Ids.BatchKind.PRIMARY) {
        quarantine(name)
        return
    }
    val ls = ArrayList<SplitLine>()
    for (raw in p.lines) {
        val pre = Segments.parsePrefix(raw, 0, raw.size) ?: continue
        ls.add(SplitLine(raw, pre.seq, pre.oseq, pre.ts, pre.levelRank, Bytes.contains(CTX_MARK, raw)))
    }
    val oblig = ls.filter { !it.ctx && it.oseq > 0 }
    val ctx = ls.filter { it.ctx }.toMutableList()
    val h = p.header
    val parts = ArrayList<Pair<EnvelopeHeader, List<SplitLine>>>()
    if (oblig.size >= 2) {
        val mid = oblig[oblig.size / 2 - 1].oseq
        val a = oblig.filter { it.oseq <= mid }
        val b = oblig.filter { it.oseq > mid }
        val aErr = a.any { it.rank >= Level.ERROR }
        val bErr = b.any { it.rank >= Level.ERROR }
        val ctxToA = aErr && !bErr
        val ha = h.copy(oseqFrom = a.first().oseq, oseqTo = a.last().oseq)
        val hb = h.copy(
            oseqFrom = b.first().oseq, oseqTo = b.last().oseq, drops = emptyList(), closedSessions = emptyList(),
            closedSessionsDropped = 0, mapping = null,
        )
        if (ctxToA) hb.ctxTruncated = null else ha.ctxTruncated = null
        parts.add(Pair(ha, a + (if (ctxToA) ctx else emptyList())))
        parts.add(Pair(hb, b + (if (ctxToA) emptyList() else ctx)))
    } else if (oblig.size == 1 && ctx.isNotEmpty()) {
        ctx.sortBy { it.seq }
        val drop = maxOf(1, ctx.size / 2)
        repeat(drop) { ctx.removeAt(0) }
        val ha = h.copy(ctxTruncated = (h.ctxTruncated ?: 0) + drop)
        parts.add(Pair(ha, oblig + ctx))
    } else {
        quarantine(name)
        return
    }
    val written = ArrayList<String>()
    for ((h0, part) in parts) {
        val lines = part.sortedBy { it.seq }
        val inst = install ?: continue
        val bid = Ids.batchId(inst.installId, h0.sessionId, Ids.BatchKind.PRIMARY, h0.oseqFrom ?: continue) ?: continue
        val hh = h0.copy(
            batchId = bid, seqFrom = lines.first().seq, seqTo = lines.last().seq,
            day = Day.clientDay(lines.minOf { it.ts }, h0.createdMs),
        )
        val hasErr = lines.any { !it.ctx && it.rank >= Level.ERROR }
        writeBatch(hh, lines.map { it.raw }, if (hasErr) 0 else 1)?.let { written.add(it.name) }
    }
    if (written.size != parts.size) return
    if (name !in written) {
        Fs.remove(file)
        metas.remove(name)
    }
    fails.remove(name)
}

// MARK: 驱逐（§3.8，宪法 R-5）

private class EvSeg(val file: File, val size: Long, val mtime: Long, val segNo: Int)

/**
 * 总量 = 各会话段 + 出站箱；上限 = min(local_cap_bytes, 本方已占 + 可用空间 − 64 MB 余量)。
 * 顺序：RETAINED 段最旧优先（无义务不记墓碑；> 7 d 无条件删）→ p2（backfill_evicted）→ q（quarantine_evicted）
 * → p1（buffer_overflow）→ p0（buffer_overflow）。当前 OPEN 段永不驱逐。
 */
internal fun Engine.evictIfNeeded() {
    val nowWall = clock.wallMs()
    val sealed = ArrayList<EvSeg>()
    var total = 0L
    for (procName in Fs.list(root)) {
        if (!procName.startsWith("proc-")) continue
        val pdir = File(root, procName)
        for (sid in Fs.list(pdir)) {
            if (!Ids.isUuid(sid)) continue
            val sdir = File(pdir, sid)
            for (f in Fs.list(sdir)) {
                val pn = Segments.parseName(f) ?: continue
                val file = File(sdir, f)
                val size = Fs.size(file) ?: 0
                total += size
                if (!pn.isOpen) sealed.add(EvSeg(file, size, Fs.mtimeMs(file) ?: nowWall, pn.segNo))
            }
        }
    }
    reconcileOutbox()
    for (m in metas.values) total += m.bytes

    var cap = effective.config.localCapBytes.toLong()
    val avail = platform.availableBytes(root)
    if (avail != null) cap = minOf(cap, maxOf(0, total + avail - ClientConstants.DISK_RESERVE_BYTES))
    sealed.sortWith(compareBy<EvSeg>({ it.mtime }, { it.segNo }))
    val tombs = ArrayList<DropEntry>()
    val maxAge = Limits.RING_MAX_AGE_DAYS * ClientConstants.DAY_MS
    val remaining = ArrayList<EvSeg>()
    for (s in sealed) {
        if (nowWall - s.mtime > maxAge) {
            total -= s.size
            evictSegment(s.file, nowWall, tombs)
        } else {
            remaining.add(s)
        }
    }
    val cur = current
    if (total > cap && cur != null && (cur.sealed.lastOrNull()?.lastOseq ?: 0) > cur.cursor.extractedThroughOseq) {
        // daily cap 推迟的义务行先物化，保证 RETAINED 段不带义务
        materialize(cur, cur.sealed.maxOfOrNull { it.lastOseq } ?: 0, cur.sealed.lastOrNull()?.lastSeq ?: 0, false, true)
        reconcileOutbox()
        total = remaining.sumOf { it.size } + metas.values.sumOf { it.bytes } +
            (writer.currentSegmentFile?.let { Fs.size(it) } ?: 0)
    }
    for (s in remaining) {
        if (total <= cap) break
        total -= s.size
        evictSegment(s.file, nowWall, tombs)
    }
    if (total > cap) {
        for (prio in intArrayOf(2, 3, 1, 0)) {
            val batch = metas.values.filter { it.prio == prio && it.name != inFlight }
                .sortedWith(compareBy<BatchMeta>({ it.createdMs }, { it.name }))
            for (m in batch) {
                if (total <= cap) break
                total -= m.bytes
                evictBatch(m, nowWall, tombs)
            }
        }
    }
    if (tombs.isNotEmpty()) appendDrops(tombs)
}

private fun Engine.evictSegment(file: File, now: Long, tombs: MutableList<DropEntry>) {
    for (s in ownSessions) {
        val idx = s.sealed.indexOfFirst { it.file.path == file.path }
        if (idx < 0) continue
        val info = s.sealed[idx]
        if (info.obligCount > 0 && info.lastOseq > s.cursor.extractedThroughOseq) {
            val from = maxOf(info.firstOseq, s.cursor.extractedThroughOseq + 1)
            tombs.add(
                DropEntry(
                    s.meta.sessionId, from, info.lastOseq, info.lastOseq - from + 1, DropReason.BUFFER_OVERFLOW, now,
                    lastAckAge(now),
                ),
            )
        }
        s.sealed.removeAt(idx)
        if (s !== current && s.sealed.isEmpty() && s.cursor.closedMs != null) {
            others.remove(s.meta.sessionId)
            Fs.remove(s.dir)
            return
        }
        break
    }
    Fs.remove(file)
    // 别的进程 / 已关闭会话的目录空了就一并清掉
    val dir = file.parentFile ?: return
    if (Fs.list(dir).none { Segments.parseName(it) != null } &&
        dir.path != current?.dir?.path && others[dir.name] == null && dir.parentFile?.path != procDir.path
    ) {
        Fs.remove(dir)
    }
}

private fun Engine.evictBatch(m: BatchMeta, now: Long, tombs: MutableList<DropEntry>) {
    Fs.remove(File(outboxDir, m.name))
    metas.remove(m.name)
    fails.remove(m.name)
    // 携带的墓碑 / 会话终态仍在 jsonl 里，放回待携带
    embeddedDrops.removeAll(m.drops.toSet())
    embeddedClosed.removeAll(m.closed.map { it.sessionId }.toSet())
    val p = pendingMapping
    if (m.hasMapping && p != null && p.user == m.mappingUser && p.digest == m.mappingDigest) pendingMapping = null
    if (m.sessionId.isEmpty()) return
    val age = lastAckAge(now)
    if (m.kind == Ids.BatchKind.BACKFILL) {
        tombs.add(DropEntry(m.sessionId, 0, 0, maxOf(m.lineCount, 1).toLong(), DropReason.BACKFILL_EVICTED, now, age))
    } else if (m.oseqFrom > 0) {
        val reason = if (m.prio == 3) DropReason.QUARANTINE_EVICTED else DropReason.BUFFER_OVERFLOW
        tombs.add(DropEntry(m.sessionId, m.oseqFrom, m.oseqTo, m.oseqTo - m.oseqFrom + 1, reason, now, age))
    }
}
