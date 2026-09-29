package org.revdog.retriever.core

import org.revdog.retriever.LogLevel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 重入保护（§3.3-6）：线程局部标志。redact 钩子内、SDK 内部路径上的 log() 直接忽略。 */
internal object Reentrancy {
    private val flag = ThreadLocal<Boolean>()

    var active: Boolean
        get() = flag.get() == true
        set(v) {
            if (v) flag.set(true) else flag.remove()
        }
}

/** 写失败区间（内存墓碑，可写时落盘）。 */
internal class FailedRange(val from: Long, var to: Long, var n: Long, val atMs: Long)

/**
 * 写入侧（宪法 R-1，方案 §3.3 / §3.4）：常开 `FileOutputStream(file, true)`（不套 Buffered），锁内只做
 * 「seq++（义务行 oseq++）+ 拼前缀 + 一次 write」。逐行不 sync；写失败 `RandomAccessFile.setLength` 回上一完整行、
 * 该行照常占 seq / oseq 并记内存墓碑 write_failed；绝不抛给宿主。与 iOS `Writer.swift` 逐字一致。
 */
internal class Writer(private val clock: Clock) {
    class Outcome {
        var written = false
        var rotated = false
        var fatal = false
        var deadlineChanged = false
        var tombstone = false
    }

    private val lock = ReentrantLock()

    // ---- 以下全部受 lock 保护 ----
    private var sessionDir: File? = null
    private var sessionId: String = ""
    private var out: FileOutputStream? = null
    private var cur: SegInfo = SegInfo(0, null, 0, File("/dev/null"))
    private var seq: Long = 0
    private var oseq: Long = 0
    private var uploadRank = LogLevel.WARN.rank
    private var localRank = LogLevel.DEBUG.rank
    private var enabled = true
    private var user: String? = null
    private var flushIntervalMs: Long = Limits.FLUSH_INTERVAL_S_DEFAULT * 1000L
    private var errorDeadline: Long? = null
    private var lastErrorSealMono: Long = Long.MIN_VALUE / 4
    private var warnDeadline: Long? = null
    private val pending = ArrayList<SealJob>()
    private val failed = ArrayList<FailedRange>()
    private var nextReopenMono: Long = 0

    // MARK: 会话

    /** 开始（或重开）一个会话：seq / oseq 从 0 起，打开 seg-000001.open。 */
    fun startSession(dir: File, sessionId: String) {
        lock.withLock {
            closeQuietly(out)
            out = null
            sessionDir = dir
            this.sessionId = sessionId
            seq = 0
            oseq = 0
            pending.clear()
            failed.clear()
            errorDeadline = null
            warnDeadline = null
            openSegmentLocked(1)
        }
    }

    /** 放弃当前会话（purgeLocal / 关停）：关流，不封段。 */
    fun abandonSession() {
        lock.withLock {
            closeQuietly(out)
            for (j in pending) closeQuietly(j.out)
            out = null
            pending.clear()
            failed.clear()
            sessionDir = null
        }
    }

    val currentSessionId: String get() = lock.withLock { sessionId }

    // MARK: 参数

    fun setLevels(upload: LogLevel, local: LogLevel, flushIntervalS: Int) {
        lock.withLock {
            uploadRank = upload.rank
            localRank = local.rank
            flushIntervalMs = flushIntervalS * 1000L
        }
    }

    fun setEnabled(on: Boolean) {
        lock.withLock { enabled = on }
    }

    val isEnabled: Boolean get() = lock.withLock { enabled }

    /** 快速预判：未启用或低于 local_level 的行不做任何工作（不占 seq）。 */
    fun accepts(level: LogLevel): Boolean = lock.withLock { enabled && level.rank >= localRank }

    /** 生效的上传 / 本地级别（远程配置钳制后；full_dump 期间上传级别为 debug）。 */
    val levels: Pair<LogLevel, LogLevel>
        get() = lock.withLock { Pair(LogLevel.ofRank(uploadRank), LogLevel.ofRank(localRank)) }

    val currentUser: String? get() = lock.withLock { user }

    // MARK: 热路径

    fun append(level: LogLevel, body: ByteArray): Outcome = lock.withLock { appendLocked(level, body, false) }

    /**
     * flush 的合成行（level error、tag rtv.flush、synthetic）：无视 local_level / upload_level 一定是义务行，
     * 写完立即封段。返回该行的 oseq（写失败 null，已记 write_failed 墓碑）。
     */
    fun appendFlushMarker(body: ByteArray, noCtx: Boolean): Long? = lock.withLock {
        val o = appendLocked(LogLevel.ERROR, body, true)
        if (!o.written) return@withLock null
        val marker = oseq
        rotateLocked(SealReason.FLUSH, noCtx)
        marker
    }

    private fun appendLocked(level: LogLevel, body: ByteArray, forced: Boolean): Outcome {
        val res = Outcome()
        if (!enabled || !(forced || level.rank >= localRank) || sessionDir == null) return res
        val now = clock.monoMs()
        if (out == null && now >= nextReopenMono) {
            if (!openSegmentLocked(if (cur.segNo == 0) 1 else cur.segNo)) nextReopenMono = now + 1000
        }
        seq += 1
        val oblig = forced || level.rank >= uploadRank
        if (oblig) oseq += 1
        val pre = LineEncoder.prefix(seq, if (oblig) oseq else 0)
        val buf = ByteArray(pre.size + body.size)
        System.arraycopy(pre, 0, buf, 0, pre.size)
        System.arraycopy(body, 0, buf, pre.size, body.size)
        var ok = false
        val o = out
        if (o != null) {
            ok = try {
                o.write(buf)
                true
            } catch (e: IOException) {
                false
            }
            if (!ok) Fs.truncate(cur.file, cur.bytes)
        }
        if (!ok) {
            if (oblig) {
                recordFailedLocked(oseq)
                res.tombstone = true
            }
            return res
        }
        res.written = true
        if (failed.isNotEmpty()) res.tombstone = true
        cur.bytes += buf.size
        if (cur.firstSeq == 0L) cur.firstSeq = seq
        cur.lastSeq = seq
        cur.lineCount += 1
        if (oblig) {
            if (cur.firstOseq == 0L) cur.firstOseq = oseq
            cur.lastOseq = oseq
            cur.obligCount += 1
            if (level.rank >= LogLevel.ERROR.rank) cur.hasError = true
        }
        if (forced) return res
        if (level == LogLevel.FATAL) {
            // fatal：立即封段并物化（调用方在锁外同步处理，只落盘不尝试上传）
            if (rotateLocked(SealReason.FATAL, false)) {
                res.rotated = true
                res.fatal = true
            }
            return res
        }
        if (oblig && level == LogLevel.ERROR && errorDeadline == null) {
            errorDeadline = maxOf(now + Limits.ERROR_DEBOUNCE_MS, lastErrorSealMono + Limits.ERROR_SEAL_MIN_INTERVAL_MS)
            res.deadlineChanged = true
        }
        if (oblig && cur.obligCount == 1 && warnDeadline == null) {
            warnDeadline = now + flushIntervalMs
            res.deadlineChanged = true
        }
        if (cur.bytes >= Limits.SEGMENT_BYTES) res.rotated = rotateLocked(SealReason.SIZE, false)
        return res
    }

    private fun recordFailedLocked(o: Long) {
        val at = clock.wallMs()
        val last = failed.lastOrNull()
        if (last != null && last.to + 1 == o) {
            last.to = o
            last.n += 1
        } else {
            failed.add(FailedRange(o, o, 1, at))
        }
    }

    // MARK: 换段

    private fun openSegmentLocked(segNo: Int): Boolean {
        val dir = sessionDir ?: return false
        val file = File(dir, Segments.name(segNo, true))
        val started = clock.wallMs()
        cur = SegInfo(segNo, user, started, file)
        val header = SegmentHeader(segNo, user, started).encode()
        val f = try {
            if (file.exists()) Fs.truncate(file, 0)
            FileOutputStream(file, true)
        } catch (e: IOException) {
            out = null
            return false
        }
        try {
            f.write(header)
        } catch (e: IOException) {
            closeQuietly(f)
            out = null
            return false
        }
        out = f
        cur.bytes = header.size.toLong()
        return true
    }

    /** 锁内换段：当前段（有行才换）交给引擎线程封，立即打开下一段。 */
    private fun rotateLocked(reason: SealReason, noCtx: Boolean): Boolean {
        val o = out ?: return false
        if (cur.lineCount <= 0) return false
        pending.add(SealJob(o, cur, reason, noCtx, seq, oseq))
        if (cur.hasError) lastErrorSealMono = clock.monoMs()
        errorDeadline = null
        warnDeadline = null
        out = null
        openSegmentLocked(cur.segNo + 1)
        return true
    }

    /**
     * 外部触发的封段（flush / full_dump / upload_enabled / 进后台 / 关停）。
     * `onlyIfObligation`：进后台、warn 计时只在段内有义务行时封。
     */
    fun rotate(reason: SealReason, onlyIfObligation: Boolean = false): Boolean = lock.withLock {
        if (onlyIfObligation && cur.obligCount == 0) return@withLock false
        rotateLocked(reason, false)
    }

    /** setUser：值变化即封段；当前段还没有行时直接改写 header（用户边界 = 段边界）。 */
    fun setUser(u: String?): Boolean = lock.withLock {
        if (u == user) return@withLock false
        user = u
        val o = out
        if (o != null && cur.lineCount == 0) {
            val header = SegmentHeader(cur.segNo, u, cur.startedMs).encode()
            val ok = Fs.truncate(cur.file, 0) && try {
                o.write(header)
                true
            } catch (e: IOException) {
                false
            }
            if (ok) {
                cur.userId = u
                cur.bytes = header.size.toLong()
                return@withLock false
            }
        }
        if (out == null) {
            cur.userId = u
            return@withLock false
        }
        rotateLocked(SealReason.USER, false)
    }

    /** 定时器：error 去抖到期 / warn 计时到期（仅当有义务行）。 */
    fun checkDeadlines(): Boolean = lock.withLock {
        val now = clock.monoMs()
        val d = errorDeadline
        if (d != null && now >= d) {
            errorDeadline = null
            if (rotateLocked(SealReason.ERROR, false)) return@withLock true
        }
        val w = warnDeadline
        if (w != null && now >= w) {
            warnDeadline = null
            if (cur.obligCount > 0) return@withLock rotateLocked(SealReason.TIMER, false)
        }
        false
    }

    val nextDeadline: Long?
        get() = lock.withLock { listOfNotNull(errorDeadline, warnDeadline).minOrNull() }

    fun takePendingSeals(): List<SealJob> = lock.withLock {
        val p = ArrayList(pending)
        pending.clear()
        p
    }

    fun takeFailed(): List<FailedRange> = lock.withLock {
        val f = ArrayList(failed)
        failed.clear()
        f
    }

    fun putBackFailed(f: List<FailedRange>) {
        lock.withLock { failed.addAll(0, f) }
    }

    /** 测试 / 诊断用快照。 */
    class Snapshot(val seq: Long, val oseq: Long, val segNo: Int, val segBytes: Long, val segLines: Int, val user: String?)

    val snapshot: Snapshot
        get() = lock.withLock { Snapshot(seq, oseq, cur.segNo, cur.bytes, cur.lineCount, user) }

    /** 测试：关掉当前段的流，模拟写失败（ENOSPC / 目录不可写）。 */
    fun debugBreakStream() {
        lock.withLock { closeQuietly(out) }
    }

    val currentSegmentFile: File?
        get() = lock.withLock { if (out != null) cur.file else null }

    private fun closeQuietly(o: FileOutputStream?) {
        try {
            o?.close()
        } catch (e: IOException) {
            // 忽略
        }
    }
}
