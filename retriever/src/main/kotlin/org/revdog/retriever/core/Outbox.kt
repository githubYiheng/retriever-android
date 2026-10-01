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
                ?: // 读不出（位腐烂等）：保守当作含 warn 的 primary，交给服务端隔离；请求头的 install 只能取当前值
                BatchMeta(
                    name, p.prio, p.createdMs, p.batchId, install?.installId ?: "", Ids.BatchKind.PRIMARY, "", 0, 0, 0, true,
                    p.prio == 0, emptyList(), emptyList(), false, null, null, Fs.size(File(outboxDir, name)) ?: 0,
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
    val iid = install?.installId
    for (m in metas.values) {
        embeddedDrops.addAll(m.drops)
        embeddedClosed.addAll(m.closed.map { it.sessionId })
        // 别的 install 的批（多进程清空的窗口里写进来的）带的映射不算本 install 的在途映射
        if (m.hasMapping && m.mappingDigest != null && m.installId == iid) pendingMapping = PendingMapping(m.mappingUser, m.mappingDigest)
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

// MARK: 413：按 oseq（或 ctx）二分重物化，全部半批提交后才删原批（ADR 0019 决定 11）

private class SplitLine(val raw: ByteArray, val seq: Long, val oseq: Long, val ts: Long, val rank: Int, val ctx: Boolean)

/**
 * 切分 = 重物化：每个半批都是新名字的新批（batch_id = UUIDv5(ns, `<install>:<session>:primary:<oseq_from>:<oseq_to>`)，
 * install 取原批信封，created_ms 沿用原批——重切得到同名同字节的文件，可重入）。全部半批提交后才删原批；任一半写失败 →
 * 删掉本次已写的半批、原批原样保留，返回 false（调用方按普通失败退避，下次再发再 413 再切）。
 * 「单个义务行 + 上下文折半」只产出一个文件，同样用上面的名字（再次折半时与上一次同名、原子覆盖）。
 */
internal fun Engine.split413(name: String): Boolean {
    val file = File(outboxDir, name)
    val gz = Fs.read(file)
    val p = gz?.let { parseBatch(it) }
    if (p == null || p.header.kind != Ids.BatchKind.PRIMARY) {
        quarantine(name)
        return true
    }
    val ls = ArrayList<SplitLine>()
    for (raw in p.lines) {
        val pre = Segments.parsePrefix(raw, 0, raw.size) ?: continue
        // 是否 ctx 按行尾固定位置判断（ADR 0024 决定 9），attrs 里的 `ctx` 键不算
        ls.add(SplitLine(raw, pre.seq, pre.oseq, pre.ts, pre.levelRank, Segments.isCtxLine(raw, 0, raw.size)))
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
        return true
    }
    val written = ArrayList<String>()
    for ((h0, part) in parts) {
        val lines = part.sortedBy { it.seq }
        val bid = Ids.splitBatchId(h0.installId, h0.sessionId, h0.oseqFrom ?: 0, h0.oseqTo ?: 0)
        if (bid == null) {
            // 信封身份不合规（推导不出确定性 id）：切不了，交给服务端隔离（与 iOS 同口径）
            rollbackSplit(written, name)
            quarantine(name)
            return true
        }
        val hh = h0.copy(batchId = bid, seqFrom = lines.first().seq, seqTo = lines.last().seq, day = Day.clientDay(lines.minOf { l -> l.ts }, h0.createdMs))
        val hasErr = lines.any { !it.ctx && it.rank >= Level.ERROR }
        val m = writeBatch(hh, lines.map { l -> l.raw }, if (hasErr) 0 else 1)
        if (m == null) {
            rollbackSplit(written, name)
            return false
        }
        written.add(m.name)
    }
    if (name !in written) {
        Fs.remove(file)
        metas.remove(name)
    }
    fails.remove(name)
    return true
}

/** 413 切分没有全部写成：只删本次写出的半批，原批不动。 */
private fun Engine.rollbackSplit(written: List<String>, original: String) {
    for (w in written) {
        if (w == original) continue
        Fs.remove(File(outboxDir, w))
        metas.remove(w)
    }
}

// MARK: 驱逐（§3.8，宪法 R-5）

private class EvSeg(val file: File, val size: Long, val mtime: Long, val segNo: Int)

/**
 * 总量 = 各会话段 + 出站箱 + pre 文件（ADR 0023：configure 之前的行也计入本地总量，R-5）；两个额度（ADR 0010）：硬上限 = local_cap_bytes，是驱逐义务批的唯一依据；
 * 余量额度 = min(硬上限, 本方已占 + 可用空间 − 64 MB)（可用空间未知时 = 硬上限），只约束无义务类（RETAINED 段、p2）。
 * 顺序：RETAINED 段最旧优先（无义务不记墓碑；> 7 d 无条件删）→ p2（backfill_evicted）→（超硬上限时）q（quarantine_evicted）
 * → p1（buffer_overflow）→ p0（buffer_overflow）。当前 OPEN 段永不驱逐。
 */
internal fun Engine.evictIfNeeded() {
    // 恢复旧会话成功完成之前一律不驱逐：旧会话还没登记，它们的段被删时既不物化也不记墓碑（静默丢）。恢复被跳过的定时重试
    if (!recovered) return
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
    // pre 文件：计入总量；不属于任何活进程（flock 拿得到）且 mtime 超过 7 天的删掉（收编不了的孤儿，例如被别的进程名下的会话认领）。
    // 本进程正开着的（收编中）与别的活进程的不碰
    for (n in Fs.list(preDir)) {
        if (!n.endsWith(PreFile.SUFFIX)) continue
        val f = File(preDir, n)
        val size = Fs.size(f) ?: 0
        if (nowWall - (Fs.mtimeMs(f) ?: nowWall) > Limits.RING_MAX_AGE_DAYS * ClientConstants.DAY_MS) {
            val orphan = OrphanPre.tryAcquire(f)
            if (orphan != null) {
                f.delete()
                orphan.release()
                continue
            }
        }
        total += size
    }
    reconcileOutbox()
    for (m in metas.values) total += m.bytes

    val hardCap = effective.config.localCapBytes.toLong()
    val avail = platform.availableBytes(root)
    // 低磁盘只让出可再生的部分：义务批不能因余量不足在上传前消失（否则墓碑也永远送不出去）
    val softCap = if (avail != null) minOf(hardCap, maxOf(0, total + avail - ClientConstants.DISK_RESERVE_BYTES)) else hardCap
    sealed.sortWith(compareBy<EvSeg>({ it.mtime }, { it.segNo }))
    val maxAge = Limits.RING_MAX_AGE_DAYS * ClientConstants.DAY_MS
    val remaining = ArrayList<EvSeg>()
    for (s in sealed) {
        if (nowWall - s.mtime > maxAge) {
            if (evictSegment(s.file, nowWall)) total -= s.size
        } else {
            remaining.add(s)
        }
    }
    val cur = current
    if (total > softCap && cur != null && !writer.isAdopting && (cur.sealed.lastOrNull()?.lastOseq ?: 0) > cur.cursor.extractedThroughOseq) {
        // daily cap 推迟的义务行先物化，保证 RETAINED 段不带义务
        materialize(cur, cur.sealed.maxOfOrNull { it.lastOseq } ?: 0, cur.sealed.lastOrNull()?.lastSeq ?: 0, false, true)
        reconcileOutbox()
        total = remaining.sumOf { it.size } + metas.values.sumOf { it.bytes } +
            (writer.currentSegmentFile?.let { Fs.size(it) } ?: 0)
    }
    for (s in remaining) {
        if (total <= softCap) break
        if (evictSegment(s.file, nowWall)) total -= s.size
    }
    if (total > softCap) {
        for (prio in intArrayOf(2, 3, 1, 0)) {
            // p2 无义务，受余量额度约束；q / p1 / p0 只受硬上限约束
            val cap = if (prio == 2) softCap else hardCap
            val batch = metas.values.filter { it.prio == prio && it.name != inFlight }
                .sortedWith(compareBy<BatchMeta>({ it.createdMs }, { it.name }))
            for (m in batch) {
                if (total <= cap) break
                if (evictBatch(m, nowWall)) total -= m.bytes
            }
        }
    }
}

/** 驱逐一个段：有没物化的义务行先记墓碑，记成了再删；墓碑写不成本轮不驱逐它（返回 false）。 */
private fun Engine.evictSegment(file: File, now: Long): Boolean {
    for (s in ownSessions) {
        val idx = s.sealed.indexOfFirst { it.file.path == file.path }
        if (idx < 0) continue
        val info = s.sealed[idx]
        if (info.obligCount > 0 && info.lastOseq > s.cursor.extractedThroughOseq) {
            val from = maxOf(info.firstOseq, s.cursor.extractedThroughOseq + 1)
            val t = DropEntry(s.meta.sessionId, from, info.lastOseq, info.lastOseq - from + 1, DropReason.BUFFER_OVERFLOW, now, lastAckAge(now))
            if (!appendDrops(listOf(t))) return false
        }
        s.sealed.removeAt(idx)
        if (s !== current && s.sealed.isEmpty() && s.cursor.closedMs != null) {
            others.remove(s.meta.sessionId)
            Fs.remove(s.dir)
            return true
        }
        // seq 高水位：被驱逐段的行不可能再作 ctx，把它的 lastSeq 并入 ctx 游标语义正确，又不改磁盘格式；
        // 恢复时 maxSeq 取 max(盘上, ctx_through_seq)，旧段全被驱逐后合成行的 seq 也不会回退撞号
        if (info.lastSeq > s.cursor.ctxThroughSeq) {
            s.cursor.ctxThroughSeq = info.lastSeq
            writeCursor(s)
        }
        break
    }
    Fs.remove(file)
    // 别的进程 / 已关闭会话的目录空了就一并清掉
    val dir = file.parentFile ?: return true
    if (Fs.list(dir).none { Segments.parseName(it) != null } &&
        dir.path != current?.dir?.path && others[dir.name] == null && dir.parentFile?.path != procDir.path
    ) {
        Fs.remove(dir)
    }
    return true
}

/** 驱逐一个批：先记墓碑（义务批 / backfill），记成了再删；墓碑写不成本轮不驱逐它（返回 false）。 */
private fun Engine.evictBatch(m: BatchMeta, now: Long): Boolean {
    if (m.sessionId.isNotEmpty()) {
        val age = lastAckAge(now)
        val t = if (m.kind == Ids.BatchKind.BACKFILL) {
            DropEntry(m.sessionId, 0, 0, maxOf(m.lineCount, 1).toLong(), DropReason.BACKFILL_EVICTED, now, age)
        } else if (m.oseqFrom > 0) {
            val reason = if (m.prio == 3) DropReason.QUARANTINE_EVICTED else DropReason.BUFFER_OVERFLOW
            DropEntry(m.sessionId, m.oseqFrom, m.oseqTo, m.oseqTo - m.oseqFrom + 1, reason, now, age)
        } else {
            null
        }
        if (t != null && !appendDrops(listOf(t))) return false
    }
    Fs.remove(File(outboxDir, m.name))
    metas.remove(m.name)
    fails.remove(m.name)
    // 携带的墓碑 / 会话终态仍在 jsonl 里，放回待携带
    embeddedDrops.removeAll(m.drops.toSet())
    embeddedClosed.removeAll(m.closed.map { it.sessionId }.toSet())
    val p = pendingMapping
    if (m.hasMapping && p != null && p.user == m.mappingUser && p.digest == m.mappingDigest) pendingMapping = null
    return true
}
