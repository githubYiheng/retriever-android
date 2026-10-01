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
 * 「seq++（义务行 oseq++）+ 拼前缀 + 一次 write」。逐行不 sync；写失败 `RandomAccessFile.setLength` 回上一完整行（不 sync）、
 * 关掉写句柄按 1 s 节流重开（ADR 0020 决定 6），该行照常占 seq / oseq 并记内存墓碑 write_failed；绝不抛给宿主。
 *
 * 收编中（ADR 0023）：configure 时本进程有 pre 文件——之后的行（已过 redact、按生效 local_level 过滤）仍追加到 pre 文件
 * （`r` = 1），用户切换写切换记录；引擎线程按文件顺序经 [adoptLine] / [adoptUser]（即正常追加路径）写进会话，
 * 追到文件尾后在本锁内切到正常态（[commitAdoption]）。
 *
 * 生效级别 = 纯函数（不可变缓存快照 + 宿主默认 + 时刻），在本锁内重算：宿主线程上的 configure 同步生效（简报 §2）。
 * 与 iOS `Writer.swift` 逐字一致。
 */
internal class Writer(private val clock: Clock, host: HostDefaults = HostDefaults(LogLevel.WARN)) {
    class Outcome {
        var written = false
        var rotated = false
        var fatal = false
        var deadlineChanged = false
        var tombstone = false

        /** 没有会话（bootstrap 失败 / 目录消失 / 收编中写 pre 失败）：行没落盘，调用方计数（简报 §4.1）。 */
        var noSession = false

        /** 收编中：行已追加到 pre 文件，seq / oseq 等收编时再分配。 */
        var deferred = false

        /** 落盘失败（没有会话、段打不开、write 失败）——不含 local_level 过滤、禁用。 */
        var failed = false
    }

    /** setUser 的结果：`changed` = 值变了（身份变化，要重拉配置）；`rotated` = 因此封了段。 */
    class UserChange(val changed: Boolean, val rotated: Boolean)

    /** flush 标记行：`reused` = 已有一个等待中的标记且其后没有新义务行，没有追加新行（简报 §8）。 */
    class FlushMark(val sessionId: String, val oseq: Long, val reused: Boolean)

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

    /** 会话里当前段的用户（段头 user_id；收编时按切换记录推进）。 */
    private var user: String? = null

    /** 宿主最近一次设的用户（配置身份用）；正常态 == [user]，收编中可能领先。 */
    private var latestUser: String? = null
    private var flushIntervalMs: Long = Limits.FLUSH_INTERVAL_S_DEFAULT * 1000L
    private var errorDeadline: Long? = null
    private var lastErrorSealMono: Long = Long.MIN_VALUE / 4
    private var warnDeadline: Long? = null
    private var fatalWindowStart: Long? = null
    private var flushMarkOseq: Long = 0
    private val pending = ArrayList<SealJob>()
    private val failed = ArrayList<FailedRange>()
    private var nextReopenMono: Long = 0
    private var adopt: PreFile? = null
    private var hostDefaults: HostDefaults = host
    private var cache: ConfigCache? = null

    /** 会话目录在运行中消失（换段时打不开、目录已不在）：交给引擎线程重新 bootstrap（简报 §4.5）。在锁内调用，只能投递。 */
    @Volatile
    var onVanished: (() -> Unit)? = null

    init {
        recomputeLocked()
    }

    // MARK: 会话

    /** 开始（或重开）一个会话：关掉上一个会话的流（含没封完的段），seq / oseq 从 0 起，打开 seg-000001.open。 */
    fun startSession(dir: File, sessionId: String) {
        lock.withLock {
            closeQuietly(out)
            for (j in pending) closeQuietly(j.out)
            out = null
            sessionDir = dir
            this.sessionId = sessionId
            seq = 0
            oseq = 0
            pending.clear()
            failed.clear()
            errorDeadline = null
            warnDeadline = null
            fatalWindowStart = null
            flushMarkOseq = 0
            openSegmentLocked(1)
        }
    }

    /** 放弃当前会话（purgeLocal / 目录消失 / 关停）：关流，不封段。 */
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

    val hasSession: Boolean get() = lock.withLock { sessionDir != null }

    // MARK: 参数

    /** 宿主默认（只存这一份，引擎读它）。configure / reconfigure 在宿主线程上同步更新并重算生效级别。 */
    var host: HostDefaults
        get() = lock.withLock { hostDefaults }
        set(h) {
            lock.withLock {
                hostDefaults = h
                recomputeLocked()
            }
        }

    /** 引擎线程每次 applyEffective 推新的缓存快照。 */
    fun setConfigSnapshot(c: ConfigCache?) {
        lock.withLock {
            cache = c
            recomputeLocked()
        }
    }

    private fun recomputeLocked() {
        val e = ConfigCache.effective(cache, hostDefaults, clock.wallMs(), clock.monoMs())
        uploadRank = e.uploadLevel.rank
        localRank = e.config.localLevel.rank
        flushIntervalMs = e.config.flushIntervalS * 1000L
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

    /** 配置身份用的用户（宿主最近一次设的值）。 */
    val currentUser: String? get() = lock.withLock { latestUser }

    /** configure 时交来的初始用户：没有 pre 文件 → 会话直接以它开始；有 pre 文件 → 会话从 null 起，按切换记录推进。 */
    fun initUser(u: String?, sessionToo: Boolean) {
        lock.withLock {
            latestUser = u
            if (sessionToo) user = u
        }
    }

    // MARK: 收编（ADR 0023）

    fun beginAdopt(pf: PreFile) {
        lock.withLock { adopt = pf }
    }

    val adoptingFile: PreFile? get() = lock.withLock { adopt }

    val isAdopting: Boolean get() = lock.withLock { adopt != null }

    /**
     * 收编：一行经正常追加路径写进会话（不论是否收编中）。pre 行是已落盘的数据：禁用时照样写（上传仍被禁用挡住）；
     * `filter` = 按生效 local_level 过滤（configure 之前写的 r = 0 行；r = 1 行写入时已过滤，原样）。
     */
    fun adoptLine(level: LogLevel, body: ByteArray, filter: Boolean): Outcome = lock.withLock {
        if (filter && level.rank < localRank) Outcome() else appendLocked(level, body, false, true)
    }

    /** 收编：一条用户切换记录经 setUser 路径作用于会话（不动 [latestUser]）。 */
    fun adoptUser(u: String?): UserChange = lock.withLock { sessionUserLocked(u) }

    /** 目录消失后从偏移 0 重新收编：会话用户回到 pre 文件开头的 null（[latestUser] 不动）。 */
    fun resetSessionUserForReadoption() {
        lock.withLock { user = null }
    }

    /**
     * 目录消失时随之没了的段：当前 `.open` 段与还没封完的段（行数、error 行数、首末 ts），由调用方并入没落盘的行计数。
     * 收编中不算（那些都是收编写进去的行，pre 文件提交前完整，会从头重新收编）。
     */
    fun takeLostSegments(): List<SegInfo> = lock.withLock {
        if (adopt != null) return@withLock emptyList()
        val out = ArrayList<SegInfo>()
        for (j in pending) if (j.info.lineCount > 0) out.add(j.info)
        if (cur.lineCount > 0) out.add(cur)
        cur = SegInfo(cur.segNo, user, cur.startedMs, cur.file)
        out
    }

    enum class Commit { DONE, MORE, FAILED }

    /**
     * 收编提交（在本锁与 pre 文件的锁内）：`tail` 读出 `pos()` 之后追加的剩余记录交给调用方处理——只含已过 redact 的行与用户记录时
     * 调用方当场写完返回 true；剩余里还有没过 redact 的行（configure 前的迟到行）返回 false，调用方在锁外处理完再来（MORE）。
     * 然后 unlink pre 文件（提交点；unlink 失败则截成 0 长度）→ 切到正常态。两者都失败 = FAILED：保持收编中，调用方稍后重试。
     */
    fun commitAdoption(pf: PreFile, pos: () -> Long, tail: (ByteArray) -> Boolean): Commit = lock.withLock {
        pf.locked {
            if (adopt !== pf) return@locked Commit.DONE
            if (pf.length > pos()) {
                val rest = pf.readFrom(pos()) ?: return@locked Commit.FAILED
                if (!tail(rest)) return@locked Commit.MORE
            }
            TestHooks.adoption?.invoke("before_commit")
            if (!pf.unlinkAndClose()) return@locked Commit.FAILED
            TestHooks.adoption?.invoke("after_unlink")
            adopt = null
            if (latestUser != user) sessionUserLocked(latestUser)
            Commit.DONE
        }
    }

    /** 收编放弃（purgeLocal：pre 文件随本地数据一起清掉）。 */
    fun abortAdoption(): PreFile? = lock.withLock {
        val pf = adopt
        adopt = null
        user = latestUser
        pf
    }

    // MARK: 热路径

    fun append(level: LogLevel, body: ByteArray): Outcome = lock.withLock {
        val pf = adopt
        if (pf != null) appendPreLocked(pf, level, body) else appendLocked(level, body, false)
    }

    /** 收编中：已过 redact 的行按生效 local_level 过滤后追加到 pre 文件（`r` = 1）。 */
    private fun appendPreLocked(pf: PreFile, level: LogLevel, body: ByteArray): Outcome {
        val res = Outcome()
        if (!enabled || level.rank < localRank) return res
        if (pf.append(PreRecords.line(true, body), false) == PreFile.Put.WRITTEN) {
            res.written = true
            res.deferred = true
        } else {
            res.noSession = true
            res.failed = true
        }
        return res
    }

    /**
     * flush 的合成行（level error、tag rtv.flush、synthetic）：无视 local_level / upload_level 一定是义务行，写完立即封段。
     * 已有一个等待中的标记且其后没有新写入的义务行（oseq 没动）→ 复用它，不追加、不换段（简报 §8）。
     * 返回标记（写失败 null，已记 write_failed 墓碑）。
     */
    fun appendFlushMarker(body: ByteArray, noCtx: Boolean): FlushMark? = lock.withLock {
        if (flushMarkOseq > 0 && oseq == flushMarkOseq && sessionDir != null) return@withLock FlushMark(sessionId, flushMarkOseq, true)
        val o = appendLocked(LogLevel.ERROR, body, true)
        if (!o.written) return@withLock null
        flushMarkOseq = oseq
        rotateLocked(SealReason.FLUSH, noCtx)
        FlushMark(sessionId, oseq, false)
    }

    /**
     * SDK 自己的计数合成行（`rtv.pre_init_dropped`）：强制写入、强制义务（不受 local_level / upload_level 影响，像 flush 标记），
     * 不因它按大小换段。禁用时不写。
     */
    fun appendForced(level: LogLevel, body: ByteArray): Outcome = lock.withLock { appendLocked(level, body, true) }

    /** 等待结束：之后的 flush 追加新标记。 */
    fun clearFlushMarker(sid: String, marker: Long) {
        lock.withLock { if (sessionId == sid && flushMarkOseq == marker) flushMarkOseq = 0 }
    }

    /** `adopted`：收编的行（已过滤 / 已落过盘）——不看启用开关与 local_level。 */
    private fun appendLocked(level: LogLevel, body: ByteArray, forced: Boolean, adopted: Boolean = false): Outcome {
        val res = Outcome()
        if (!adopted && (!enabled || !(forced || level.rank >= localRank))) return res
        if (sessionDir == null) {
            res.noSession = true
            res.failed = true
            return res
        }
        val now = clock.monoMs()
        if (out == null && now >= nextReopenMono) {
            if (!reopenLocked()) nextReopenMono = now + REOPEN_THROTTLE_MS
            if (sessionDir == null) {
                res.noSession = true
                res.failed = true
                return res
            }
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
            if (!ok) {
                // 回到上一完整行；关掉写句柄，节流期内的行不再碰磁盘（磁盘满时不让每次 log() 都做一轮 I/O）
                Fs.truncate(cur.file, cur.bytes)
                closeQuietly(o)
                out = null
                nextReopenMono = now + REOPEN_THROTTLE_MS
            }
        }
        if (!ok) {
            res.failed = true
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
        val ts = LineEncoder.tsOf(body)
        if (cur.lineCount == 0) {
            cur.firstTs = ts
            cur.lastTs = ts
        } else {
            cur.firstTs = minOf(cur.firstTs, ts)
            cur.lastTs = maxOf(cur.lastTs, ts)
        }
        if (level.rank >= LogLevel.ERROR.rank) cur.errorLines += 1
        cur.lineCount += 1
        if (oblig) {
            if (cur.firstOseq == 0L) cur.firstOseq = oseq
            cur.lastOseq = oseq
            cur.obligCount += 1
            if (level.rank >= LogLevel.ERROR.rank) cur.hasError = true
        }
        if (forced) return res
        if (level == LogLevel.FATAL) {
            val ws = fatalWindowStart
            if (ws == null || now - ws >= ClientConstants.FATAL_SEAL_WINDOW_MS) {
                // 窗口内第一条 fatal：立即换段；封段物化由调用方投递到引擎线程、不等待（ADR 0020 决定 1），只落盘不尝试上传
                if (rotateLocked(SealReason.FATAL, false)) {
                    res.rotated = true
                    res.fatal = true
                    fatalWindowStart = now
                }
                return res
            }
            // 10 s 内的后续 fatal：照常逐行落盘，不强制换段、不重复排作业，并入 error 的去抖封段（简报 §8）
        }
        if (oblig && level.rank >= LogLevel.ERROR.rank && errorDeadline == null) {
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
            checkVanishedLocked(dir)
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

    /** 打不开段文件且会话目录已不在（root 被删、别的进程清空）：放弃会话（之后的行计数），交给引擎线程重新 bootstrap。 */
    private fun checkVanishedLocked(dir: File) {
        if (dir.isDirectory || !Fs.isMissing(dir)) return
        sessionDir = null
        onVanished?.invoke()
    }

    /**
     * 写失败之后重开：段里已有行就接着追加（失败时已截回上一完整行，header 不重写）；还没有行则按新段重开。
     */
    private fun reopenLocked(): Boolean {
        if (cur.segNo == 0 || cur.lineCount == 0) return openSegmentLocked(if (cur.segNo == 0) 1 else cur.segNo)
        out = try {
            FileOutputStream(cur.file, true)
        } catch (e: IOException) {
            sessionDir?.let { checkVanishedLocked(it) }
            null
        }
        return out != null
    }

    /** 锁内换段：当前段（有行才换；写句柄已因写失败关掉也照封）交给引擎线程封，立即打开下一段。 */
    private fun rotateLocked(reason: SealReason, noCtx: Boolean): Boolean {
        if (sessionDir == null || cur.lineCount <= 0) return false
        pending.add(SealJob(out, cur, reason, noCtx, seq, oseq))
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

    /**
     * setUser：值变化即封段；当前段还没有行时直接改写 header（用户边界 = 段边界）。
     * 返回「值变了」与「封了段」两件事：身份变化与是否封段无关（ADR 0019 决定 12）。
     * 收编中：只记最新值并往 pre 文件追加一条切换记录，会话的用户边界等收编按顺序推进。
     */
    fun setUser(u: String?): UserChange = lock.withLock {
        if (u == latestUser) return@withLock UserChange(false, false)
        latestUser = u
        val pf = adopt
        if (pf != null) {
            // 写失败：标坏，之后的行计数，绝不挂到错的用户名下
            if (pf.append(PreRecords.user(u), false) == PreFile.Put.FAILED) pf.markBroken()
            return@withLock UserChange(true, false)
        }
        UserChange(true, sessionUserLocked(u).rotated)
    }

    /** 会话层的用户切换（段边界）。 */
    private fun sessionUserLocked(u: String?): UserChange {
        if (u == user) return UserChange(false, false)
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
                return UserChange(true, false)
            }
        }
        if (out == null && cur.lineCount == 0) {
            // 段还没开成（或写失败后关了）且没有行：重开时按新 user 写 header
            cur.userId = u
            return UserChange(true, false)
        }
        return UserChange(true, rotateLocked(SealReason.USER, false))
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

    private companion object {
        /** 段打不开 / 写失败之后多久再试一次（期间的行只计数记墓碑，不碰磁盘）。 */
        const val REOPEN_THROTTLE_MS = 1000L
    }
}
