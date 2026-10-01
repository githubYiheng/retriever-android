package org.revdog.retriever.core

import org.revdog.retriever.LogLevel
import org.revdog.retriever.LogLine
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

// 启动恢复（§3.4 ORPHAN）：旧会话截残行、判退出、unclean_fg 合成 error 并按 error 封段带 ctx、
// 旧会话终态写 sessions.jsonl（只为 last_oseq > 0 的会话写）；零行的会话目录直接删；段读不出的会话本次不动、下次重试
// （ADR 0019 决定 1 / 13）；旧 session_id / user_id / device 一律取持久化值；
// cursor = max(cursor 文件, 出站箱里本会话最大 oseq_to)。与 iOS `Recovery.swift` 逐字一致。

private val UNCLEAN_TAG = Bytes.ascii("\"tag\":\"rtv.unclean_exit\"")

internal fun Engine.recoverOldSessions() {
    val cur = current ?: return
    val now = clock.wallMs()
    val outMax = HashMap<String, Long>()
    for (m in metas.values) {
        if (m.kind == Ids.BatchKind.PRIMARY && m.sessionId.isNotEmpty()) {
            outMax[m.sessionId] = maxOf(outMax[m.sessionId] ?: 0, m.oseqTo)
        }
    }
    val existing = locks.withDirLock { Pair(readClosedLocked().map { it.sessionId }.toSet(), readDropsLocked()) }
    var latest: SessionMeta? = null

    for (sid in Fs.list(procDir)) {
        if (!Ids.isUuid(sid) || sid == cur.meta.sessionId) continue
        val dir = File(procDir, sid)
        // 活着的会话（别的进程 / 同进程另一实例持有 meta.json 的锁）不动
        val metaFile = File(dir, "meta.json")
        var lock: SessionLock? = null
        if (metaFile.isFile) lock = SessionLock.tryAcquire(metaFile) ?: continue
        try {
            val m = recoverOne(sid, dir, now, outMax, existing.first, existing.second)
            if (m != null && (latest == null || m.sessionNo > latest.sessionNo)) latest = m
        } finally {
            lock?.release()
        }
    }
    previousAppVersion = latest?.device?.appVersion
}

/** 恢复一个旧会话；返回它的 meta（用于判断 app_version 变化）。 */
private fun Engine.recoverOne(
    sid: String,
    dir: File,
    now: Long,
    outMax: Map<String, Long>,
    existingClosed: Set<String>,
    existingDrops: List<DropEntry>,
): SessionMeta? {
    val segNames = Fs.list(dir).mapNotNull { n -> Segments.parseName(n)?.let { Triple(it.segNo, it.isOpen, n) } }
        .sortedWith(compareBy<Triple<Int, Boolean, String>>({ it.first }, { if (it.second) 1 else 0 }))
    val meta = Fs.read(File(dir, "meta.json"))?.let { SessionMeta.decode(it) }
    if (meta == null) {
        if (segNames.isEmpty()) Fs.remove(dir)
        return null
    }
    val cursor = Fs.read(File(dir, "cursor.json"))?.let { Cursor.decode(it) } ?: Cursor()
    cursor.extractedThroughOseq = maxOf(cursor.extractedThroughOseq, outMax[sid] ?: 0)

    val files = ArrayList<SegmentFile>()
    for ((_, isOpen, n) in segNames) {
        val file = File(dir, n)
        // 段读不出（I/O 错误）：本次不动这个会话——不推进 cursor、不删目录、不写终态，下次启动重试（ADR 0019 决定 13）。
        // 跳过它继续的话，没读到的义务行会被当成「不在盘上」、cursor 被推过，无墓碑消失
        val f = Segments.read(file, isOpen) ?: return meta
        if (isOpen && f.validEnd < f.data.size) {
            // 截残行：回到最后一个完整行
            Fs.truncate(file, f.validEnd.toLong())
            f.data = f.data.copyOfRange(0, f.validEnd)
        }
        files.add(f)
    }

    var maxSeq = 0L
    var maxOseq = 0L
    var maxTs = meta.startedMs
    val present = HashSet<Long>()
    for (f in files) {
        for (l in f.lines) {
            maxSeq = maxOf(maxSeq, l.seq)
            maxTs = maxOf(maxTs, l.ts)
            if (l.oseq > 0) {
                maxOseq = maxOf(maxOseq, l.oseq)
                present.add(l.oseq)
            }
        }
        f.tornSeq?.let { maxSeq = maxOf(maxSeq, it) }
        f.tornOseq?.let { if (it > 0) maxOseq = maxOf(maxOseq, it) }
    }
    // 旧段已被驱逐时盘上最大值偏低：并入已物化水位（cursor 已与出站箱取过 max）、本会话墓碑与 ctx 游标（驱逐时并入了
    // 被驱逐段的 lastSeq）。否则合成的 unclean_exit 与已上传序号撞号、永不上传，终态 last_oseq 低报
    maxSeq = maxOf(maxSeq, cursor.ctxThroughSeq)
    val tombMax = existingDrops.filter { it.sessionId == sid }.maxOfOrNull { it.oseqTo } ?: 0
    maxOseq = maxOf(maxOseq, cursor.extractedThroughOseq, tombMax)

    if (maxSeq == 0L && maxOseq == 0L) {
        // 零行（盘上无行、也没有任何已物化 / 已驱逐 / 墓碑的痕迹；Android 后台拉起的空进程多是这样）：无数据、无终态可写，
        // 直接删目录（ADR 0019 决定 1），不留到 7 天年龄驱逐、也不在每次冷启动重读。
        // 判零行在合成 rtv.unclean_exit 之前：禁用期间启动的会话必是零行，不能事后由已启用的进程替它合成一条带时刻的崩溃证据
        // 上报（撤回同意 = 不写不传，ADR 0020 决定 2）；代价是启用期「一行未写就前台崩溃」不留痕
        Fs.remove(dir)
        return meta
    }

    val newTombs = ArrayList<DropEntry>()
    var exit = SessionExit.UNKNOWN
    if (cursor.closedMs == null) {
        // 缺口（残行、全 0 块、未落盘的 write_failed）计 corrupt 墓碑；已有墓碑覆盖的不重复记
        val covered = existingDrops.filter { it.sessionId == sid }
        fun isCovered(o: Long) = covered.any { it.oseqFrom <= o && o <= it.oseqTo }
        var o = cursor.extractedThroughOseq + 1
        while (o <= maxOseq) {
            if (o in present || isCovered(o)) {
                o += 1
                continue
            }
            val start = o
            while (o + 1 <= maxOseq && (o + 1) !in present && !isCovered(o + 1)) o += 1
            newTombs.add(DropEntry(sid, start, o, o - start + 1, DropReason.CORRUPT, now, lastAckAge(now)))
            o += 1
        }
        exit = when (cursor.lastState) {
            "fg" -> SessionExit.UNCLEAN_FG
            "bg" -> SessionExit.CLEAN_BG
            else -> SessionExit.UNKNOWN
        }
        val lastFile = files.lastOrNull()
        val lastLine = lastFile?.lines?.lastOrNull()
        val alreadySynth = lastLine != null && Bytes.contains(UNCLEAN_TAG, lastFile.data, lastLine.start, lastLine.end)
        // 合成行也是写入：只看本进程内存开关（禁用 = 不写，ADR 0020 决定 2）
        if (exit == SessionExit.UNCLEAN_FG && !alreadySynth && enabled) {
            val oblig = LogLevel.ERROR.rank >= effective.uploadLevel.rank
            val seq = maxSeq + 1
            val oseq = if (oblig) maxOseq + 1 else 0
            val ts = maxOf(maxTs, cursor.lastStateMs)
            val enc = LineEncoder.encode(
                LogLine(ts, LogLevel.ERROR, "process ended while foregrounded", "rtv.unclean_exit", null, null),
                synthetic = true,
            )
            val bytes = LineEncoder.prefix(seq, oseq) + enc.body
            val target: File
            if (lastFile != null && !lastFile.sealed) {
                target = lastFile.file
            } else {
                val no = (lastFile?.segNo ?: 0) + 1
                target = File(dir, Segments.name(no, true))
                Fs.writeAtomic(target, SegmentHeader(no, lastFile?.header?.userId, ts).encode())
            }
            if (Fs.append(target, bytes)) {
                maxSeq = seq
                if (oseq > 0) maxOseq = oseq
                val idx = files.indexOfFirst { it.file.path == target.path }
                val nf = Segments.read(target, false)
                if (nf != null) {
                    if (idx >= 0) files[idx] = nf else files.add(nf)
                }
            }
        }
    }

    if (cursor.closedMs == null) {
        val rec = SessionRecord(meta, dir, cursor, sealAll(files).toMutableList())
        // 终态只为有义务行的会话写（ADR 0019 决定 1）：last_oseq = 0 的会话服务端无可结算，写了只会挤占有数据会话的终态
        val closedOk = if (sid !in existingClosed && maxOseq > 0) {
            val c = ClosedSession(
                sid, meta.sessionNo, meta.startedMs, maxOf(maxTs, cursor.lastStateMs, meta.startedMs), maxSeq, maxOseq,
                exit,
            )
            appendClosed(listOf(c))
        } else {
            true
        }
        val dropsOk = if (newTombs.isEmpty()) true else appendDrops(newTombs)
        // 按 error 封段带 ctx（合成 error 在批内 → 自动附 ctx）。墓碑没写成就不物化：物化会把 cursor 推过缺口，下次启动的
        // 缺口扫描从 cursor + 1 开始就再也找不到它；整个会话留到下次启动重试（同 ADR 0019 决定 13 的做法）
        if (dropsOk) materialize(rec, maxOseq, maxSeq, false, true)
        // 终态或墓碑没写成（磁盘满等）：不打「已收尾」标记，下次启动重试收尾（合成行有防重复），不静默丢终态
        if (closedOk && dropsOk) {
            rec.cursor.closedMs = now
            writeCursor(rec)
        }
        register(rec)
    } else {
        val rec = SessionRecord(meta, dir, cursor, sealAll(files).toMutableList())
        if (maxOseq > cursor.extractedThroughOseq) materialize(rec, maxOseq, maxSeq, false, true)
        register(rec)
    }
    return meta
}

private fun Engine.register(rec: SessionRecord) {
    if (rec.sealed.isEmpty()) Fs.remove(rec.dir) else others[rec.meta.sessionId] = rec
}

/** 旧会话的 .open 段一律 sync + rename 为 .sealed；返回段索引。 */
private fun sealAll(files: List<SegmentFile>): List<SegInfo> {
    val out = ArrayList<SegInfo>()
    for (f in files) {
        var file = f.file
        if (!f.sealed) {
            try {
                RandomAccessFile(file, "rw").use { it.fd.sync() }
            } catch (e: IOException) {
                // 照常 rename
            }
            val dst = File(file.parentFile, Segments.name(f.segNo, false))
            if (file.renameTo(dst)) file = dst
        }
        out.add(SegInfo.from(f, file))
    }
    return out.sortedBy { it.segNo }
}
