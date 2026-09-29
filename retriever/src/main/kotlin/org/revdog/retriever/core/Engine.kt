package org.revdog.retriever.core

import org.revdog.retriever.Options
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** 一个会话的物化状态（meta + cursor + 已封段索引）。 */
internal class SessionRecord(
    val meta: SessionMeta,
    val dir: File,
    var cursor: Cursor,
    /** 已封段（RETAINED），按 seg_no 升序。 */
    val sealed: MutableList<SegInfo>,
)

internal class FailCount(var count: Int, var otherSuccess: Boolean)

internal class AckedRange(val sessionId: String, val from: Long, val to: Long)

internal class PendingMapping(val user: String?, val digest: String)

/** 本批要带的 drops / closed_sessions / closed_sessions_dropped。 */
internal class Extras(val drops: List<DropEntry>, val closed: List<ClosedSession>, val dropped: Long)

/**
 * 后台状态机：封段处理、物化、出站箱、驱逐、恢复、配置、上传队列决策。
 * **只在引擎线程上访问**（RetrieverClient 保证串行），所以不加锁。网络请求在引擎线程外发。与 iOS `Engine.swift` 逐字一致。
 */
internal class Engine(
    val root: File,
    val processName: String,
    var key: String,
    var baseUrl: String,
    options: Options,
    val clock: Clock,
    val platform: Platform,
    val writer: Writer,
) {
    val procDir = File(root, "proc-$processName")
    val outboxDir = File(root, "outbox")
    val locks = RootLocks(root)

    var host: HostDefaults = HostDefaults.of(options)
    var sdkVersion: String = options.sdkVersion

    var install: InstallInfo? = null
    var current: SessionRecord? = null
    val others = HashMap<String, SessionRecord>()
    val device: Device

    val metas = HashMap<String, BatchMeta>()
    val embeddedDrops = HashSet<DropEntry>()
    val embeddedClosed = HashSet<String>()
    var pendingMapping: PendingMapping? = null
    var mapping: MappingState? = null

    var configCache: ConfigCache? = null
    var effective: EffectiveConfig
    var lastConfigFetchMono: Long? = null

    var backoff = BackoffState()
    val fails = HashMap<String, FailCount>()
    var inFlight: String? = null
    var lastRequestMono: Long? = null

    /** 当前会话 meta.json 上的锁（进程存活期间一直持有；进程死亡由内核释放）。 */
    var sessionLock: SessionLock? = null

    /** 最近被 2xx 确认的 primary 区间（flush 判断「这一批已确认」用；只留最近 512 个）。 */
    val ackedRanges = ArrayList<AckedRange>()

    /** 本进程已生成过 backfill 的段（"<session_id>:<seg_no>"），full_dump 反复生效时不重复生成。 */
    val backfilledSegs = HashSet<String>()

    /** 本进程写出的批文件计数（判断「这次封段有没有产生批次」）。 */
    var batchesWritten = 0
    var todayDay = ""
    var todayCount = 0
    var enabled = true
    var previousAppVersion: String? = null

    init {
        val f = platform.deviceFields()
        device = Device(
            f["os"] ?: "", f["os_version"] ?: "", f["model"] ?: "", f["app_version"] ?: "",
            f["build"] ?: "", f["locale"] ?: "", "retriever-android/${options.sdkVersion}",
        ).sanitized()
        effective = ConfigCache.effective(null, host, clock.wallMs(), clock.monoMs())
    }

    // MARK: 路径

    val installFile: File get() = File(root, "install.json")
    val backoffFile: File get() = File(root, "backoff.json")
    val mappingFile: File get() = File(root, "mapping.json")
    val dropsFile: File get() = File(root, "drops.jsonl")
    val sessionsFile: File get() = File(root, "sessions.jsonl")

    /** 远程配置缓存（§5「拉不到用缓存、重启后用墙钟兜底」需要持久化；同 iOS）。 */
    val configFile: File get() = File(root, "config.json")

    val sdkHeader: String get() = "retriever-android/$sdkVersion"

    fun endpoint(path: String): String = baseUrl.trimEnd('/') + "/" + path

    // MARK: 启动（同步：log() 在构造返回后立即可用）

    /** 建目录、install.json（首次生成 createNewFile 临时文件 → sync → rename，失败方重读）、计数器 +1、新会话。 */
    fun bootstrap(): Boolean {
        if (!Fs.ensureDir(root) || !Fs.ensureDir(procDir) || !Fs.ensureDir(outboxDir)) return false
        val result = locks.withDirLock { bumpInstallLocked() } ?: return false
        val inst = result.first
        install = inst
        val now = clock.wallMs()
        val sid = Ids.newV4()
        val dir = File(procDir, sid)
        if (!Fs.ensureDir(dir)) return false
        val meta = SessionMeta(sid, result.second, now, device, processName)
        Fs.writeAtomic(File(dir, "meta.json"), meta.encode())
        lockSessionDir(dir)
        val fg = platform.isForeground()
        val cursor = Cursor(lastState = fg?.let { if (it) "fg" else "bg" }, lastStateMs = now)
        val rec = SessionRecord(meta, dir, cursor, ArrayList())
        current = rec
        writeCursor(rec)
        writer.startSession(dir, sid)
        loadPersistentState()
        return true
    }

    fun lockSessionDir(dir: File) {
        releaseSessionLock()
        sessionLock = SessionLock.tryAcquire(File(dir, "meta.json"))
    }

    fun releaseSessionLock() {
        sessionLock?.release()
        sessionLock = null
    }

    private fun bumpInstallLocked(): Pair<InstallInfo, Long>? {
        val inst: InstallInfo = Fs.read(installFile)?.let { InstallInfo.decode(it) } ?: (createInstallLocked() ?: return null)
        inst.sessionCounter += 1
        if (!Fs.writeAtomic(installFile, inst.encode())) return null
        return Pair(inst, inst.sessionCounter)
    }

    private fun createInstallLocked(): InstallInfo? {
        val info = InstallInfo(Ids.newV4(), 0, clock.wallMs())
        val tmp = File(root, ".install.json.tmp-${Ids.newV4()}")
        try {
            if (tmp.createNewFile()) {
                FileOutputStream(tmp).use { out ->
                    out.write(info.encode())
                    out.fd.sync()
                }
                if (installFile.exists() || !tmp.renameTo(installFile)) tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
        }
        // 失败方（或并发的另一方已写）重读
        return Fs.read(installFile)?.let { InstallInfo.decode(it) }
    }

    private fun loadPersistentState() {
        val nowWall = clock.wallMs()
        val nowMono = clock.monoMs()
        Fs.read(backoffFile)?.let { b -> BackoffState.decodeColdStart(b, nowWall, nowMono)?.let { backoff = it } }
        Fs.read(mappingFile)?.let { mapping = MappingState.decode(it) }
        Fs.read(configFile)?.let { b ->
            val o = JsonIn.obj(b)
            val fetched = JsonIn.int64(o?.get("fetched_ms"))
            if (o != null && fetched != null) configCache = ConfigCache(ConfigRules.clamp(o["config"], host), fetched, null)
        }
        effective = ConfigCache.effective(configCache, host, nowWall, nowMono)
        writer.setLevels(effective.uploadLevel, effective.config.localLevel, effective.config.flushIntervalS)
    }

    // MARK: 状态文件

    fun writeCursor(s: SessionRecord) {
        Fs.writeAtomic(File(s.dir, "cursor.json"), s.cursor.encode())
    }

    fun persistBackoff() {
        Fs.writeAtomic(backoffFile, backoff.encode())
    }

    fun setLastState(state: String) {
        val cur = current ?: return
        cur.cursor.lastState = state
        cur.cursor.lastStateMs = clock.wallMs()
        writeCursor(cur)
    }

    // MARK: 封段处理（sync → rename → 物化 → 原子写 cursor）

    /** 处理写入侧交来的全部封段任务；返回是否有新批次产生。 */
    fun processSeals(): Boolean {
        val jobs = writer.takePendingSeals()
        val cur = current
        if (jobs.isEmpty() || cur == null) {
            flushTombstones()
            return false
        }
        for (j in jobs) {
            try {
                j.out?.fd?.sync()
            } catch (e: IOException) {
                // 流已坏（写失败路径）：照常 rename
            }
            try {
                j.out?.close()
            } catch (e: IOException) {
                // 忽略
            }
            val info = j.info
            val openFile = info.file
            val sealedFile = File(openFile.parentFile, Segments.name(info.segNo, false))
            if (openFile.renameTo(sealedFile)) info.file = sealedFile
            if (openFile.parentFile == cur.dir) cur.sealed.add(info)
        }
        val last = jobs[jobs.size - 1]
        val noCtx = jobs.any { it.noCtx }
        val ignoreCap = jobs.any { it.reason == SealReason.FLUSH || it.reason == SealReason.FATAL || it.reason == SealReason.SHUTDOWN }
        val before = batchesWritten
        materialize(cur, last.oseqAtSeal, last.seqAtSeal, noCtx, ignoreCap)
        flushTombstones()
        evictIfNeeded()
        return batchesWritten != before
    }

    // MARK: 墓碑（drops.jsonl：追加写，上限 1000 条超出按 reason 合并）

    fun lastAckAge(at: Long): Long = if (backoff.lastAckMs < 0) -1 else maxOf(0, at - backoff.lastAckMs)

    /** 写入侧的 write_failed 墓碑：可写时落盘。 */
    fun flushTombstones() {
        val failed = writer.takeFailed()
        val cur = current
        if (failed.isEmpty() || cur == null) return
        val entries = failed.map {
            DropEntry(cur.meta.sessionId, it.from, it.to, it.n, DropReason.WRITE_FAILED, it.atMs, lastAckAge(it.atMs))
        }
        if (!appendDrops(entries)) writer.putBackFailed(failed)
    }

    fun appendDrops(entries: List<DropEntry>): Boolean {
        if (entries.isEmpty()) return true
        return locks.withDirLock {
            if (!Fs.append(dropsFile, Jsonl.encodeDrops(entries))) return@withDirLock false
            val all = readDropsLocked()
            if (all.size > ClientConstants.DROPS_FILE_MAX_ENTRIES) {
                // 已嵌进出站箱批次的条目原样保留（2xx 后按原样删除），其余合并
                val embedded = all.filter { it in embeddedDrops }
                val rest = all.filter { it !in embeddedDrops }
                val merged = mergeDrops(rest, maxOf(1, ClientConstants.DROPS_FILE_MAX_ENTRIES - embedded.size))
                Fs.writeAtomic(dropsFile, Jsonl.encodeDrops(embedded + merged))
            }
            true
        }
    }

    fun readDropsLocked(): List<DropEntry> = Jsonl.read(dropsFile).mapNotNull { DropEntry.decode(it) }

    fun readClosedLocked(): List<ClosedSession> = Jsonl.read(sessionsFile).mapNotNull { ClosedSession.decode(it) }

    // MARK: 杂项

    fun isAcked(sessionId: String, oseq: Long): Boolean =
        ackedRanges.any { it.sessionId == sessionId && it.from <= oseq && oseq <= it.to }

    fun todayBatches(now: Long): Int {
        val d = Day.fromMs(now)
        if (d != todayDay) {
            todayDay = d
            todayCount = 0
        }
        return todayCount
    }

    fun countBatch(now: Long) {
        todayBatches(now)
        todayCount += 1
    }

    /** 所有自己进程目录里的会话记录（当前 + 旧）。 */
    val ownSessions: List<SessionRecord>
        get() {
            val out = ArrayList<SessionRecord>()
            current?.let { out.add(it) }
            out.addAll(others.values.sortedBy { it.meta.sessionNo })
            return out
        }

    /** 有可上传的批（进后台 / fatal 时据此排 JobScheduler 作业）。 */
    fun hasUploadWork(): Boolean =
        key.isNotEmpty() && enabled && effective.config.uploadEnabled && metas.values.any { it.prio < 3 }

    companion object {
        /** 按 reason 合并计数：先按 (session_id, reason) 合并区间，仍超限再按 reason 合并。 */
        fun mergeDrops(entries: List<DropEntry>, limit: Int): List<DropEntry> {
            if (entries.size <= limit) return entries
            fun merge(es: List<DropEntry>, key: (DropEntry) -> String): List<DropEntry> {
                val acc = LinkedHashMap<String, DropEntry>()
                for (e in es) {
                    val k = key(e)
                    val m = acc[k]
                    acc[k] = if (m == null) {
                        e
                    } else {
                        m.copy(
                            oseqFrom = minOf(m.oseqFrom, e.oseqFrom),
                            oseqTo = maxOf(m.oseqTo, e.oseqTo),
                            n = m.n + e.n,
                            atMs = maxOf(m.atMs, e.atMs),
                            lastAckAgeMs = maxOf(m.lastAckAgeMs, e.lastAckAgeMs),
                        )
                    }
                }
                return acc.values.toList()
            }
            val bySession = merge(entries) { "${it.sessionId}|${it.reason}" }
            if (bySession.size <= limit) return bySession
            return merge(bySession) { it.reason }
        }

        fun digest(d: Device): String {
            val o = JsonOut()
            d.encode(o)
            val h = MessageDigest.getInstance("SHA-256").digest(o.toByteArray())
            val sb = StringBuilder(16)
            for (k in 0 until 8) {
                val x = h[k].toInt() and 0xFF
                sb.append("0123456789abcdef"[x shr 4]).append("0123456789abcdef"[x and 0xF])
            }
            return sb.toString()
        }
    }
}
