package org.revdog.retriever.core

import org.revdog.retriever.LogLevel
import org.revdog.retriever.LogLine
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

/** 本批要带的 drops / closed_sessions（ADR 0019 决定 2：按文件顺序取最旧的未在途 N 条，不合并、不计数）。 */
internal class Extras(val drops: List<DropEntry>, val closed: List<ClosedSession>)

/**
 * 后台状态机：封段处理、物化、出站箱、驱逐、恢复、配置、上传队列决策。
 * **只在引擎线程上访问**（RetrieverClient 保证串行），所以不加锁。网络请求在引擎线程外发。与 iOS `Engine.swift` 逐字一致。
 * 例外：`key` / `effective` 是 volatile，fatal 在调用线程上据此判断要不要直接排后台作业（ADR 0020 决定 1）。
 */
internal class Engine(
    val root: File,
    val processName: String,
    key: String,
    var baseUrl: String,
    options: Options,
    val clock: Clock,
    val platform: Platform,
    val writer: Writer,
) {
    val procDir = File(root, "proc-$processName")
    val outboxDir = File(root, "outbox")
    val locks = RootLocks(root)

    @Volatile
    var key: String = key

    /** key 指纹（ADR 0024 决定 7）：sha256(key) 前 16 位十六进制，key 为空 = 空串。 */
    val keyFp: String get() = Ids.keyFingerprint(key)

    /** 宿主默认只存一份（简报 §2）：在写入侧，configure 在宿主线程上同步改它；引擎从同一处读。 */
    val host: HostDefaults get() = writer.host
    var sdkVersion: String = options.sdkVersion

    /** 本实例收编的 pre 文件名（bootstrap 时记进 meta.json 的 `pre`；提交 / 放弃后为 null）。 */
    var adoptPre: String? = null

    /** 会话目录 / 段文件在运行中消失（封段时 rename 失败且文件已不在）：交给客户端重新 bootstrap（简报 §4.5）。 */
    var onVanished: (() -> Unit)? = null

    /** 物化时段文件读失败（非 ENOENT）：本次不推进，定时重试（ADR 0024 决定 3）。 */
    var readRetryPending = false

    /** 本次 bootstrap 之后恢复旧会话已经成功做完：之前一律不驱逐（拿不到 root 锁跳过的不算，定时重试）。 */
    var recovered = false

    /** bootstrap 判出的待写合成行（install 修复 / 重建）：bootstrap 没成功时留着，重试成功后照写。 */
    private val pendingSynth = ArrayList<Triple<String, String, Map<String, Any?>?>>()

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

    @Volatile
    var effective: EffectiveConfig

    /** 上次尝试拉配置的单调时刻（构造请求时记一次、响应回来再记一次；null = 本进程还没拉过）。 */
    var lastConfigFetchMono: Long? = null

    var backoff = BackoffState()
    val fails = HashMap<String, FailCount>()
    var inFlight: String? = null
    var lastRequestMono: Long? = null

    /** 当前会话目录 `lock` 文件上的锁（进程存活期间一直持有；进程死亡由内核释放）。 */
    var sessionLock: SessionLock? = null

    /** 最近被 2xx 确认的 primary 区间（flush 判断「这一批已确认」用；只留最近 512 个）。 */
    val ackedRanges = ArrayList<AckedRange>()

    /** 本进程已生成过 backfill 的段（"<session_id>:<seg_no>"），full_dump 反复生效时不重复生成。 */
    val backfilledSegs = HashSet<String>()

    /** 本进程写出的批文件计数（判断「这次封段有没有产生批次」）。 */
    var batchesWritten = 0
    var todayDay = ""
    var todayCount = 0

    /** 本进程的内存开关（引擎线程上的镜像；写入侧以 Writer 的开关为准）。启动时由盘上标记初始化。 */
    var enabled = true

    /** 禁用标记写失败（ENOSPC 等）：内存照样禁用，每次调度 tick 重试（ADR 0020 决定 2）。 */
    var markerPending = false
    var previousAppVersion: String? = null

    init {
        val f = platform.deviceFields()
        device = Device(
            f["os"] ?: "", f["os_version"] ?: "", f["model"] ?: "", f["app_version"] ?: "",
            f["build"] ?: "", f["locale"] ?: "", "retriever-android/${options.sdkVersion}",
        ).sanitized()
        effective = ConfigCache.effective(null, writer.host, clock.wallMs(), clock.monoMs())
    }

    // MARK: 路径

    val installFile: File get() = File(root, "install.json")
    val backoffFile: File get() = File(root, "backoff.json")
    val mappingFile: File get() = File(root, "mapping.json")
    val dropsFile: File get() = File(root, "drops.jsonl")
    val sessionsFile: File get() = File(root, "sessions.jsonl")

    /** 远程配置缓存（§5「拉不到用缓存、重启后用墙钟兜底」需要持久化；同 iOS）。 */
    val configFile: File get() = File(root, "config.json")

    /** `setEnabled(false)` 的落盘标记：root **同级**的空文件 `<root>.disabled`，存在 = 禁用（ADR 0020 决定 2）；清空 root 碰不到它。 */
    val disabledFile: File get() = Markers.file(root)

    /** configure 之前的行所在目录（`<root>/pre/`，ADR 0023）。 */
    val preDir: File get() = File(root, PreFile.DIR)

    val sdkHeader: String get() = "retriever-android/$sdkVersion"

    fun endpoint(path: String): String = baseUrl.trimEnd('/') + "/" + path

    // MARK: 启动（同步：log() 在构造返回后立即可用）

    /**
     * 建目录、install.json（首次生成 createNewFile 临时文件 → sync → rename，失败方重读；读到但解析不了 = 损坏，
     * 同一把锁内从会话 meta.json 的副本修复身份，没有副本才清空 root 新建，都留痕，ADR 0019 决定 7 / 8）、计数器 +1、新会话。
     * meta.json 必须写成，写不成 = bootstrap 失败（ADR 0024 决定 2：不允许「有会话、没 meta」）。收编中时 meta 记 `pre`。
     */
    fun bootstrap(): Boolean {
        if (!Fs.ensureDir(root) || !Fs.ensureDir(procDir) || !Fs.ensureDir(outboxDir)) return false
        val result = locks.withDirLock { bumpInstallLocked() } ?: return false
        // install 修复 / 重建的合成行先记下：后面的步骤失败、bootstrap 重试时照样写（install.json 已经修好，重试判不出来了）
        if (result.repaired) pendingSynth.add(Triple("install.json unreadable; identity repaired from session meta", "rtv.install_repaired", null))
        result.reset?.let { r ->
            pendingSynth.add(Triple("install.json unreadable; local state discarded", "rtv.install_reset", mapOf("batches" to r.batches, "sessions" to r.sessions)))
        }
        // 清空时 root 整个改了名：锁 channel 还开在旧的 upload.lock 上，关掉，之后按需在新 root 重开
        if (result.reset != null) locks.close()
        val inst = result.info
        val now = clock.wallMs()
        val sid = Ids.newV4()
        val dir = File(procDir, sid)
        if (!Fs.ensureDir(dir)) return false
        // 先加会话锁、再写 meta：别的进程的恢复流程永远看不到「有 meta、没锁」的活会话
        lockSessionDir(dir)
        val meta = SessionMeta(sid, result.sessionNo, now, device, processName, inst.installId, adoptPre)
        if (!Fs.writeAtomic(File(dir, "meta.json"), meta.encode())) {
            releaseSessionLock()
            Fs.remove(dir)
            return false
        }
        install = inst
        val fg = platform.isForeground()
        val cursor = Cursor(lastState = fg?.let { if (it) "fg" else "bg" }, lastStateMs = now)
        val rec = SessionRecord(meta, dir, cursor, ArrayList())
        current = rec
        writeCursor(rec)
        writer.startSession(dir, sid)
        loadPersistentState()
        // 留一条合成行，服务端据此解释这台设备的状态（同 rtv.flush 合成行的写法）；收编中时进 pre 文件（r = 1），收编时排在 configure 之前的行之后
        for ((msg, tag, attrs) in pendingSynth) syntheticWarn(now, msg, tag, attrs)
        pendingSynth.clear()
        return true
    }

    /** 收编提交后清掉 meta.json 的 `pre`（写不成也无妨：所指文件已不在 = 已提交）。 */
    fun clearMetaPre(rec: SessionRecord) {
        if (rec.meta.pre == null) return
        val m = rec.meta.copy(pre = null)
        Fs.writeAtomic(File(rec.dir, "meta.json"), m.encode())
    }

    /** 给孤儿 pre 文件分配一个 session_no（install.json 计数器 +1；install 已换了就不分配）。 */
    fun allocateSessionNo(): Long? {
        val iid = install?.installId ?: return null
        return locks.withDirLock {
            val inst = Fs.read(installFile)?.let { InstallInfo.decode(it) } ?: return@withDirLock null
            if (inst.installId != iid) return@withDirLock null
            inst.sessionCounter += 1
            if (!Fs.writeAtomic(installFile, inst.encode())) null else inst.sessionCounter
        }
    }

    /** 清空 / 目录消失后重建之前：丢掉内存里属于旧 root 的全部状态。 */
    fun resetState() {
        metas.clear()
        embeddedDrops.clear()
        embeddedClosed.clear()
        pendingMapping = null
        mapping = null
        others.clear()
        fails.clear()
        inFlight = null
        backoff = BackoffState()
        ackedRanges.clear()
        backfilledSegs.clear()
        configCache = null
        lastConfigFetchMono = null
        install = null
        current = null
        readRetryPending = false
        recovered = false
        pendingSynth.clear()
    }

    private fun syntheticWarn(now: Long, msg: String, tag: String, attrs: Map<String, Any?>?) {
        val enc = LineEncoder.encode(LogLine(now, LogLevel.WARN, msg, tag, attrs, null), synthetic = true)
        writer.append(LogLevel.WARN, enc.body)
    }

    fun lockSessionDir(dir: File) {
        releaseSessionLock()
        sessionLock = SessionLock.tryAcquire(dir)
    }

    fun releaseSessionLock() {
        sessionLock?.release()
        sessionLock = null
    }

    /** 无副本时作废的出站箱批数与会话目录数（写进 `rtv.install_reset` 的 attrs）。 */
    private class ResetCounts(val batches: Int, val sessions: Int)

    private class Bumped(val info: InstallInfo, val sessionNo: Long, val repaired: Boolean, val reset: ResetCounts?)

    /**
     * 区分「读不到」与「读到了但解析不了」：不存在 → 首次创建；存在但读失败（权限 / directBoot 下 CE 不可读 / I/O）→
     * 本次失败、稍后重试，**绝不重建**（否则一次瞬时读错就换掉 install_id）；读到字节（含 0 字节）却解析不了 → 损坏：
     * install_id 是「这一份数据容器」的身份，单个文件坏了不换 id——从 meta.json 的副本修复；只有找不到任何副本才清空重建。
     */
    private fun bumpInstallLocked(): Bumped? {
        var repaired = false
        var reset: ResetCounts? = null
        val inst: InstallInfo = if (!installFile.exists()) {
            createInstallLocked() ?: return null
        } else {
            val bytes = readBytesOrNull(installFile) ?: return null
            InstallInfo.decode(bytes) ?: when (val r = repairFromMetasLocked()) {
                Repair.Failed -> return null
                is Repair.Found -> {
                    repaired = true
                    r.info
                }
                Repair.None -> {
                    reset = discardRootLocked() ?: return null
                    createInstallLocked() ?: return null
                }
            }
        }
        inst.sessionCounter += 1
        if (!Fs.writeAtomic(installFile, inst.encode())) return null
        return Bumped(inst, inst.sessionCounter, repaired, reset)
    }

    /** null = 读失败（与「不存在」「读到 0 字节」区分开）。 */
    private fun readBytesOrNull(f: File): ByteArray? = try {
        f.readBytes()
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private sealed class Repair {
        class Found(val info: InstallInfo) : Repair()

        object None : Repair()

        object Failed : Repair()
    }

    /**
     * ADR 0019 决定 7：扫全部 `proc-<name>/<sid>/meta.json`，取 `started_ms` 最大且带 `install_id` 的那个 id，
     * 会话计数器 = 该 id 下最大的 session_no（调用方再 +1 后原子重写 install.json）。有 meta 存在却读不了 → Failed
     * （同「install.json 读不了」：本次失败、稍后重试，绝不重建）；没有任何带 install_id 的 meta → None。
     */
    private fun repairFromMetasLocked(): Repair {
        val found = ArrayList<SessionMeta>()
        // 目录列不出（I/O 错误）也是 Failed：列成空会被误判「无副本」而清空 root
        for (p in Fs.listStrict(root) ?: return Repair.Failed) {
            if (!p.startsWith("proc-")) continue
            val pdir = File(root, p)
            for (sid in Fs.listStrict(pdir) ?: return Repair.Failed) {
                if (!Ids.isUuid(sid)) continue
                // 列会话目录判断 meta 在不在：File.exists() 对 stat 错误（无权限 / I/O）也返回 false，会把读不了误判成「无副本」
                val sdir = File(pdir, sid)
                val names = Fs.listStrict(sdir) ?: return Repair.Failed
                if ("meta.json" !in names) continue
                val b = readBytesOrNull(File(sdir, "meta.json")) ?: return Repair.Failed
                val m = SessionMeta.decode(b) ?: continue
                if (m.installId != null) found.add(m)
            }
        }
        val id = found.maxByOrNull { it.startedMs }?.installId ?: return Repair.None
        val mine = found.filter { it.installId == id }
        return Repair.Found(InstallInfo(id, mine.maxOf { it.sessionNo }, mine.minOf { it.startedMs }))
    }

    /**
     * ADR 0019 决定 8：没有任何副本 → 容器无法归属 → 按清空处理（决定 9 同一实现）：先数作废的出站箱批与会话目录，
     * 再把 root 整个改名移走、重建空目录。改名后的目录由启动流程删除（[removePurgeLeftovers]）。
     */
    private fun discardRootLocked(): ResetCounts? {
        val batches = Fs.list(outboxDir).count { OutboxName.parse(it) != null }
        var sessions = 0
        for (p in Fs.list(root)) if (p.startsWith("proc-")) sessions += Fs.list(File(root, p)).count { Ids.isUuid(it) }
        if (moveRootAside() == null) return null
        if (!Fs.ensureDir(root) || !Fs.ensureDir(procDir) || !Fs.ensureDir(outboxDir)) return null
        return ResetCounts(batches, sessions)
    }

    /** 首次生成：tmp（createNewFile）→ sync → rename；已存在（并发的另一方先写了）则放弃自己的，重读。 */
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
        return Fs.read(installFile)?.let { InstallInfo.decode(it) }
    }

    // MARK: 清空（ADR 0019 决定 9：先改名再删）

    /** root 整个改名为同级的 `<root>.purge-<uuid>`（与 configure 之前的 purge 同一实现，[RootPurge]）。 */
    fun moveRootAside(): File? = RootPurge.moveAside(root)

    /** 启动时清掉上次清空没删完的 `<root>.purge-*`。 */
    fun removePurgeLeftovers() {
        RootPurge.removeLeftovers(root)
    }

    // MARK: 启用状态（ADR 0020 决定 2）

    /** 禁用标记三态（ADR 0024 决定 6）：未知按禁用处理。 */
    fun markerState(): Markers.State = Markers.state(root)

    /** 标记在或判定不了（未知按禁用，fail-closed）。 */
    fun disabledMarked(): Boolean = markerState() != Markers.State.ABSENT

    /**
     * 上传 / 拉配置 / 排后台作业 = 本进程内存开关 ∧ 盘上无标记（每次决策判一次：别的进程的禁用在这里生效；判定不了按禁用）。
     * 内存开关读 Writer 的（setEnabled 在调用线程上同步改它）；[enabled] 要等引擎线程那一跳，落盘之前已排队的选批 / 拉配置会漏过去。
     */
    fun uploadAllowed(): Boolean = writer.isEnabled && !disabledMarked()

    /** 写禁用标记（空文件，createNewFile 原子创建）；失败记 [markerPending]，由调度 tick 重试。 */
    fun markDisabled() {
        markerPending = !Markers.mark(root)
    }

    /** 删禁用标记；删不掉返回 false（调用方保持禁用：宁可不传，也不误传）。 */
    fun clearDisabledMarker(): Boolean {
        markerPending = false
        return Markers.clear(root)
    }

    /**
     * 读跨启动状态。退避 / 映射 / 配置缓存都带 key 指纹 + baseUrl（ADR 0024 决定 7）：与当前不同——退避与暂停不继承、
     * 映射按未确认（needMapping 比对指纹）、配置缓存按身份过期并在启动时重拉。
     * 旧版本（0.1.x / 0.2.x）写的文件没有这两个键：视为与当前相同（升级本身不清退避、不重发映射、不丢配置缓存），并把当前值补写进去。
     */
    private fun loadPersistentState() {
        val nowWall = clock.wallMs()
        val nowMono = clock.monoMs()
        val fp = keyFp
        Fs.read(backoffFile)?.let { b ->
            BackoffState.decodeColdStart(b, nowWall, nowMono)?.let { s ->
                if (sameIdentity(s.hasIdentity, s.keyFp, s.baseUrl)) {
                    backoff = s
                    if (!s.hasIdentity) persistBackoff()
                } else {
                    backoff = s.forIdentity(fp, baseUrl)
                    persistBackoff()
                }
            }
        }
        Fs.read(mappingFile)?.let { b ->
            val m = MappingState.decode(b)
            mapping = m
            if (m != null && !m.hasIdentity) {
                val filled = m.copy(keyFp = fp, baseUrl = baseUrl, hasIdentity = true)
                mapping = filled
                Fs.writeAtomic(mappingFile, filled.encode())
            }
        }
        Fs.read(configFile)?.let { b ->
            val c = ConfigCache.decodeFile(b, host, fp, baseUrl, writer.currentUser)
            configCache = c
            if (c != null && c.legacyIdentity) Fs.writeAtomic(configFile, c.encodeFile())
        }
        effective = ConfigCache.effective(configCache, host, nowWall, nowMono)
        writer.setConfigSnapshot(configCache)
    }

    /** 文件里记的身份与当前相同；两个键都没有（旧版本写的，`present` = false）按相同处理；只要有且任一不符 = 不同。 */
    fun sameIdentity(present: Boolean, fp: String?, base: String?): Boolean = !present || (fp == keyFp && base == baseUrl)

    /**
     * configure / reconfigure 后（引擎线程）：key 指纹或 baseUrl 变了 → 清鉴权暂停与退避、映射按未确认、配置缓存按身份过期
     * （调用方立即重拉）。返回是否变了。
     */
    fun applyIdentity(newKey: String, newBaseUrl: String): Boolean {
        val oldFp = keyFp
        val oldBase = baseUrl
        key = newKey
        baseUrl = newBaseUrl
        if (keyFp == oldFp && baseUrl == oldBase) return false
        backoff = backoff.forIdentity(keyFp, baseUrl)
        persistBackoff()
        configCache = configCache?.staleForIdentity()
        lastConfigFetchMono = null
        return true
    }

    // MARK: 状态文件

    fun writeCursor(s: SessionRecord) {
        Fs.writeAtomic(File(s.dir, "cursor.json"), s.cursor.encode())
    }

    fun persistBackoff() {
        backoff.keyFp = keyFp
        backoff.baseUrl = baseUrl
        Fs.writeAtomic(backoffFile, backoff.encode())
    }

    fun setLastState(state: String) {
        val cur = current ?: return
        cur.cursor.lastState = state
        cur.cursor.lastStateMs = clock.wallMs()
        writeCursor(cur)
    }

    // MARK: 封段处理（sync → rename → 物化 → 原子写 cursor）

    /**
     * 处理写入侧交来的全部封段任务；返回是否有新批次产生。收编提交之前什么都不做（会话 S 不物化、不记墓碑，ADR 0023）——
     * 封段任务留在写入侧，提交后再处理。
     */
    fun processSeals(): Boolean {
        if (writer.isAdopting) return false
        val jobs = writer.takePendingSeals()
        val cur = current
        if (jobs.isEmpty() || cur == null) {
            flushTombstones()
            return false
        }
        var vanished = false
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
            if (openFile.renameTo(sealedFile)) {
                info.file = sealedFile
            } else if (Fs.isMissing(openFile) && !sealedFile.exists() && !cur.dir.isDirectory) {
                // 会话目录在运行中被删（root 被删、别的进程清空）：这一段的行随之没了，按段计数（同 iOS），重新 bootstrap（简报 §4.5）
                vanished = true
                if (info.lineCount > 0) {
                    DropCounter.putBack(DropCounter.Snapshot(info.lineCount.toLong(), info.errorLines.toLong(), info.firstTs, info.lastTs))
                }
                continue
            }
            if (openFile.parentFile == cur.dir) cur.sealed.add(info)
        }
        if (vanished) onVanished?.invoke()
        val last = jobs[jobs.size - 1]
        val noCtx = jobs.any { it.noCtx }
        val ignoreCap = jobs.any { it.reason == SealReason.FLUSH || it.reason == SealReason.FATAL || it.reason == SealReason.SHUTDOWN }
        val before = batchesWritten
        materialize(cur, last.oseqAtSeal, last.seqAtSeal, noCtx, ignoreCap)
        flushTombstones()
        evictIfNeeded()
        return batchesWritten != before
    }

    /** 物化积压（段读失败没推进的义务行）：定时器上重试。 */
    fun retryBacklog() {
        val cur = current ?: return
        if (writer.isAdopting) return
        val lastOseq = cur.sealed.maxOfOrNull { it.lastOseq } ?: 0
        if (lastOseq <= cur.cursor.extractedThroughOseq) {
            readRetryPending = false
            return
        }
        readRetryPending = false
        materialize(cur, lastOseq, cur.sealed.lastOrNull()?.lastSeq ?: 0, false, false)
    }

    // MARK: 墓碑与终态（drops.jsonl / sessions.jsonl：追加写，各自上限 1000 条）

    fun lastAckAge(at: Long): Long = if (backoff.lastAckMs < 0) -1 else maxOf(0, at - backoff.lastAckMs)

    /** 写入侧的 write_failed 墓碑：可写时落盘（收编提交之前不记：未提交的收编重做时 oseq 会重新分配）。 */
    fun flushTombstones() {
        if (writer.isAdopting) return
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
                Fs.writeAtomic(dropsFile, Jsonl.encodeDrops(capDrops(all, embeddedDrops, ClientConstants.DROPS_FILE_MAX_ENTRIES)))
            }
            true
        } ?: false
    }

    /** 会话终态落盘（只为 last_oseq > 0 的会话写，调用方保证）；超上限删最旧的未在途条目。 */
    fun appendClosed(entries: List<ClosedSession>): Boolean {
        if (entries.isEmpty()) return true
        return locks.withDirLock {
            if (!Fs.append(sessionsFile, Jsonl.encodeClosed(entries))) return@withDirLock false
            val all = readClosedLocked()
            if (all.size > ClientConstants.DROPS_FILE_MAX_ENTRIES) {
                Fs.writeAtomic(sessionsFile, Jsonl.encodeClosed(capClosed(all, embeddedClosed, ClientConstants.DROPS_FILE_MAX_ENTRIES)))
            }
            true
        } ?: false
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

    /** 所有自己进程目录里的会话记录（当前 + 旧；旧的按 started_ms，孤儿 pre 文件收编出的会话 session_no 是事后分配的）。 */
    val ownSessions: List<SessionRecord>
        get() {
            val out = ArrayList<SessionRecord>()
            current?.let { out.add(it) }
            out.addAll(others.values.sortedWith(compareBy<SessionRecord>({ it.meta.startedMs }, { it.meta.sessionNo })))
            return out
        }

    /** 有可上传的批（进后台时据此排 JobScheduler 作业）。 */
    fun hasUploadWork(): Boolean =
        key.isNotEmpty() && uploadAllowed() && effective.config.uploadEnabled && metas.values.any { it.prio < 3 }

    companion object {
        /** 清空时 root 改名的中缀：`<root>.purge-<uuid>`。 */
        const val PURGE_INFIX = ".purge-"

        /**
         * drops.jsonl 超上限（ADR 0019 决定 4）：先无损合并——同会话、同 reason、区间相接或重叠的并成一条（n = 并集长度），
         * `backfill_evicted`（恒 0..0）同会话 n 相加；仍超出删最旧的未在途条目（服务端显示为无解释缺口）。
         * 在途条目（已嵌进出站箱批，2xx 后按原样删除）与不满足 `oseq_from ≥ 1 ∧ n == oseq_to − oseq_from + 1` 的条目
         * （0.1.x 有损合并过的、区间不合法的）原样保留、不参与合并。结果保持文件顺序（合并出的条目占其最早一条的位置）。同 iOS。
         */
        fun capDrops(all: List<DropEntry>, inFlight: Set<DropEntry>, limit: Int): List<DropEntry> {
            if (all.size <= limit) return all
            val slots = ArrayList<Pair<Int, DropEntry>>()
            val groups = LinkedHashMap<String, ArrayList<Pair<Int, DropEntry>>>()
            all.forEachIndexed { i, e ->
                val lossless = e.reason == DropReason.BACKFILL_EVICTED || (e.oseqFrom >= 1 && e.n == e.oseqTo - e.oseqFrom + 1)
                if (e in inFlight || !lossless) {
                    slots.add(Pair(i, e))
                } else {
                    groups.getOrPut("${e.sessionId}|${e.reason}") { ArrayList() }.add(Pair(i, e))
                }
            }
            fun join(a: DropEntry, b: DropEntry, to: Long, n: Long): DropEntry =
                a.copy(oseqTo = to, n = n, atMs = maxOf(a.atMs, b.atMs), lastAckAgeMs = maxOf(a.lastAckAgeMs, b.lastAckAgeMs))
            for (g in groups.values) {
                if (g[0].second.reason == DropReason.BACKFILL_EVICTED) {
                    var m = g[0].second
                    for (k in 1 until g.size) m = join(m, g[k].second, m.oseqTo, m.n + g[k].second.n)
                    slots.add(Pair(g[0].first, m))
                    continue
                }
                val sorted = g.sortedWith(compareBy<Pair<Int, DropEntry>>({ it.second.oseqFrom }, { it.first }))
                var pos = sorted[0].first
                var cur = sorted[0].second
                for (k in 1 until sorted.size) {
                    val (p, e) = sorted[k]
                    if (e.oseqFrom <= cur.oseqTo + 1) {
                        val to = maxOf(cur.oseqTo, e.oseqTo)
                        cur = join(cur, e, to, to - cur.oseqFrom + 1)
                        pos = minOf(pos, p)
                    } else {
                        slots.add(Pair(pos, cur))
                        pos = p
                        cur = e
                    }
                }
                slots.add(Pair(pos, cur))
            }
            slots.sortBy { it.first }
            var excess = slots.size - limit
            val out = ArrayList<DropEntry>(slots.size)
            for ((_, e) in slots) {
                if (excess > 0 && e !in inFlight) excess -= 1 else out.add(e)
            }
            return out
        }

        /** sessions.jsonl 超上限（ADR 0019 决定 3）：删最旧的未在途条目（这些会话在服务端归 unknown，不误报）。 */
        fun capClosed(all: List<ClosedSession>, inFlight: Set<String>, limit: Int): List<ClosedSession> {
            var excess = all.size - limit
            if (excess <= 0) return all
            val out = ArrayList<ClosedSession>(all.size)
            for (c in all) {
                if (excess > 0 && c.sessionId !in inFlight) excess -= 1 else out.add(c)
            }
            return out
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
