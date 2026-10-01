package org.revdog.retriever.core

import org.revdog.retriever.LogLevel
import org.revdog.retriever.LogLine
import java.io.File

// 收编（ADR 0023；简报 §1.2）：把 pre 文件的记录按文件顺序经 writer 的正常追加路径写进会话——义务判定、seq / oseq、
// fatal 换段、error 去抖、warn 计时、用户边界都由同一个状态机产生。三处共用同一个函数：configure 时本进程的 pre 文件
// （引擎线程，先于 startup）、孤儿 pre 文件（startup 里、恢复旧会话之前）、收编中途崩溃后的重做。

/**
 * 逐条处理 pre 记录。`r` = 0 的行：有 redact 钩子则解码 → 调钩子（不持任何 SDK 锁；置重入标志，钩子里调 `log()` 被忽略；
 * 返回 null / 抛异常 → 丢弃该行；解不出 → 丢弃并计数）→ `ts` 取回原值、重编码（保留 synthetic，truncated 与原值取或）；
 * 无钩子或合成行不解码。然后按生效 local_level 过滤。`r` = 1 的行原样。钩子每条记录现取（收编中 reconfigure 换了钩子立即生效）。
 */
internal class Adopter(
    private val writer: Writer,
    private val redactOf: () -> ((LogLine) -> LogLine?)?,
    private val report: (Throwable) -> Unit,
) {
    var rotated = false
    var fatal = false
    var deadlineChanged = false
    var tombstone = false
    var records = 0
    var sawHeader = false
    var header: PreRecords.Rec.Header? = null

    /** 写会话时发现没有会话了（目录在收编中途消失）：停在这条记录。进程内收编由调用方从偏移 0 重新收编进新会话。 */
    var stalled = false
        private set

    /** 有行没写成（没有会话、段打不开、write 失败；不含 local_level 过滤、redact 丢弃）：临时 writer 的收编据此不提交。 */
    var failedWrites = false
        private set

    /** 已处理到的文件偏移（逐条推进：处理中途抛异常，重试时从这里接着来，不重复写已收编的记录）。 */
    var offset = 0L
        private set

    /** 处理 `data`（文件里从 `base` 开始的字节）里所有完整（`\n` 结尾）的记录；不完整的尾巴不算。 */
    fun feed(data: ByteArray, base: Long, allowRedact: Boolean = true) {
        var start = 0
        var i = 0
        while (i < data.size) {
            if (data[i].toInt() == 0x0A) {
                if (i > start) {
                    if (stalled) return
                    apply(PreRecords.parse(data, start, i), allowRedact)
                    if (stalled) return
                }
                start = i + 1
                offset = base + start
                if (lastApplied) {
                    lastApplied = false
                    TestHooks.adoption?.invoke("record:$records")
                }
            }
            i++
        }
    }

    private var lastApplied = false

    /** `data` 里有没有完整的、还没过 redact 的行（需要在锁外处理）。 */
    fun hasUnredacted(data: ByteArray): Boolean {
        if (redactOf() == null) return false
        var start = 0
        for (i in data.indices) {
            if (data[i].toInt() == 0x0A) {
                val r = PreRecords.parse(data, start, i)
                if (r is PreRecords.Rec.Line && !r.redacted && !r.synthetic) return true
                start = i + 1
            }
        }
        return false
    }

    private fun apply(r: PreRecords.Rec, allowRedact: Boolean) {
        when (r) {
            is PreRecords.Rec.Header -> {
                if (!sawHeader) header = r
                sawHeader = true
            }
            is PreRecords.Rec.User -> {
                sawHeader = true
                if (writer.adoptUser(r.id).rotated) rotated = true
            }
            is PreRecords.Rec.Line -> {
                sawHeader = true
                val hook = redactOf()
                if (r.redacted || r.synthetic) {
                    write(r.level, r.body, false, r.ts)
                } else if (hook == null) {
                    write(r.level, r.body, true, r.ts)
                } else {
                    check(allowRedact)
                    redacted(r, hook)
                }
            }
            PreRecords.Rec.Bad -> Unit
        }
        records += 1
        lastApplied = true
    }

    private fun redacted(r: PreRecords.Rec.Line, hook: (LogLine) -> LogLine?) {
        val decoded = LineEncoder.decodeBody(r.body)
        if (decoded == null) {
            // 有钩子却解不出来：不能不经脱敏就写，丢弃并计数
            DropCounter.add(r.level, r.ts)
            return
        }
        val (line, synthetic, truncated) = decoded
        val prev = Reentrancy.active
        Reentrancy.active = true
        val out = try {
            hook(line)
        } catch (t: Throwable) {
            // 钩子抛异常：该行丢弃（宁丢不漏 PII）
            report(t)
            null
        } finally {
            Reentrancy.active = prev
        }
        if (out == null) return
        val enc = try {
            // redact 不能改 ts（ADR 0024 决定 10）
            LineEncoder.encode(out.copy(ts = line.ts), synthetic, truncated)
        } catch (t: Throwable) {
            report(t)
            return
        }
        write(out.level, enc.body, true, line.ts)
    }

    private fun write(level: LogLevel, body: ByteArray, filter: Boolean, ts: Long) {
        val o = writer.adoptLine(level, body, filter)
        if (o.failed) failedWrites = true
        if (o.noSession) {
            stalled = true
            return
        }
        if (o.rotated) rotated = true
        if (o.fatal) fatal = true
        if (o.deadlineChanged) deadlineChanged = true
        if (o.tombstone) tombstone = true
    }
}

/** 孤儿 pre 文件被哪个会话认领了（meta.json 的 `pre`）：proc 目录名 + session_id。 */
internal class PreClaim(val procDir: String, val sessionId: String)

/**
 * 孤儿 pre 文件（flock 拿得到 = 写它的进程已死，且不是本进程的）：startup() 里、恢复旧会话之前收编（简报 §1.2-3 / 4）。
 * - 被本进程名下某个会话的 meta.pre 认领 = 那次收编没提交就崩了 → 清掉该会话已写的段与游标，从 pre 文件重做；
 * - 被别的进程名下的会话认领 → 留给那个进程（它下次启动重做）；
 * - 没人认领 = configure 之前进程就死了 → 按头记录新建一个会话目录（session_id 新生成，started_ms / device / process 取头记录，
 *   session_no 此时分配，meta.pre 指向它）。
 * 都用同一个收编函数写入（临时 writer，不碰活会话），提交（unlink pre 文件、清 meta.pre）后交给现有恢复流程：
 * 没有前后台记录 → 不合成 `rtv.unclean_exit`。禁用时照样收编（pre 行是已落盘的数据；上传仍被禁用挡住）。
 * 拿得到锁的孤儿一律收编、不看年龄；「7 天」只用于驱逐时删除没能收编的孤儿。
 */
internal fun Engine.adoptOrphans(redact: ((LogLine) -> LogLine?)?, report: (Throwable) -> Unit) {
    // 禁用时照样收编：pre 行是已落盘的数据，上传仍被禁用挡住
    if (install == null) return
    val names = Fs.list(preDir).filter { it.endsWith(PreFile.SUFFIX) }.sorted()
    if (names.isEmpty()) return
    val dead = ArrayList<OrphanPre>()
    // 拿得到锁的孤儿一律收编，不看年龄（「7 天」只用于驱逐时删除没能收编的孤儿）
    for (n in names) OrphanPre.tryAcquire(File(preDir, n))?.let { dead.add(it) }
    if (dead.isEmpty()) return
    val claims = preClaims()
    for (o in dead) {
        try {
            // 长度 0 = 已提交（unlink 失败时截成 0 的那种）：删掉，不收编、不重做
            if (o.length == 0L) {
                o.file.delete()
                continue
            }
            val claim = claims[o.file.name]
            when {
                claim == null -> adoptAsNewSession(o, redact, report)
                claim.procDir == procDir.name -> readopt(claim.sessionId, o, redact, report)
                else -> Unit
            }
        } catch (t: Throwable) {
            report(t)
        } finally {
            o.release()
        }
    }
}

/** 扫全部 `proc-*` 的会话 meta.json，收集 `pre` 认领（只在有死掉的孤儿 pre 文件时才扫）。 */
private fun Engine.preClaims(): Map<String, PreClaim> {
    val out = HashMap<String, PreClaim>()
    for (p in Fs.list(root)) {
        if (!p.startsWith("proc-")) continue
        val pdir = File(root, p)
        for (sid in Fs.list(pdir)) {
            // 当前会话跳过（它不会认领孤儿；也别开它的文件）
            if (!Ids.isUuid(sid) || sid == current?.meta?.sessionId) continue
            val m = Fs.read(File(File(pdir, sid), "meta.json"))?.let { SessionMeta.decode(it) } ?: continue
            m.pre?.let { out[it] = PreClaim(p, sid) }
        }
    }
    return out
}

private fun Engine.adoptAsNewSession(o: OrphanPre, redact: ((LogLine) -> LogLine?)?, report: (Throwable) -> Unit) {
    val data = o.readAll() ?: return
    // 没有任何完整记录（第一次写就撕裂）：没东西可收编，删掉
    if (data.none { it.toInt() == 0x0A }) {
        o.file.delete()
        return
    }
    val head = firstHeader(data)
    val sid = Ids.newV4()
    val no = allocateSessionNo() ?: return
    val dir = File(procDir, sid)
    if (!Fs.ensureDir(dir)) return
    // 缺头记录：started_ms 取首行 ts，没有行才取 mtime
    val started = head?.startedMs ?: firstLineTs(data) ?: (Fs.mtimeMs(o.file) ?: clock.wallMs())
    val meta = SessionMeta(sid, no, started, head?.device ?: device, head?.process ?: processName, install?.installId, o.file.name)
    if (!Fs.writeAtomic(File(dir, "meta.json"), meta.encode())) {
        Fs.remove(dir)
        return
    }
    adoptInto(dir, meta, data, o, redact, report)
}

private fun Engine.readopt(sid: String, o: OrphanPre, redact: ((LogLine) -> LogLine?)?, report: (Throwable) -> Unit) {
    val dir = File(procDir, sid)
    val meta = Fs.read(File(dir, "meta.json"))?.let { SessionMeta.decode(it) } ?: return
    // 会话还活着（别的实例持有会话锁）：不动
    val lock = SessionLock.tryAcquire(dir) ?: return
    try {
        val data = o.readAll() ?: return
        // 收编未提交：清掉该会话目录下已写的段与游标里的段进度（保留 last_state 等前后台 / 收尾字段），从 pre 文件重做
        for (n in Fs.list(dir)) if (Segments.parseName(n) != null) Fs.remove(File(dir, n))
        val cf = File(dir, "cursor.json")
        Fs.read(cf)?.let { Cursor.decode(it) }?.let { c ->
            Fs.writeAtomic(cf, c.copy(extractedThroughOseq = 0, ctxThroughSeq = 0).encode())
        }
        adoptInto(dir, meta, data, o, redact, report)
    } finally {
        lock.release()
    }
}

private fun firstLineTs(data: ByteArray): Long? {
    var start = 0
    for (i in data.indices) {
        if (data[i].toInt() == 0x0A) {
            val r = PreRecords.parse(data, start, i)
            if (r is PreRecords.Rec.Line) return r.ts
            start = i + 1
        }
    }
    return null
}

private fun firstHeader(data: ByteArray): PreRecords.Rec.Header? {
    val nl = data.indexOfFirst { it.toInt() == 0x0A }
    if (nl <= 0) return null
    return PreRecords.parse(data, 0, nl) as? PreRecords.Rec.Header
}

/**
 * 用临时 writer 把 `data` 收编进 `dir` 的会话：段按写入侧的状态机生成（未封的段留 `.open`，恢复流程统一封）。
 * 有任何行没写成（没有会话、段打不开、write 失败）→ 不提交：删掉这次写出的段，meta.pre 与 pre 文件原样保留，下次启动重做。
 * 提交 = unlink pre 文件（失败则截成 0），再清 meta.pre。提交失败 = 没提交（下次启动按认领重做，不重不丢）。
 */
private fun Engine.adoptInto(dir: File, meta: SessionMeta, data: ByteArray, o: OrphanPre, redact: ((LogLine) -> LogLine?)?, report: (Throwable) -> Unit) {
    val tw = Writer(clock, host)
    tw.setConfigSnapshot(configCache)
    tw.startSession(dir, meta.sessionId)
    val a = Adopter(tw, { redact }, report)
    a.feed(data, 0)
    tw.abandonSession()
    if (a.stalled || a.failedWrites) {
        for (n in Fs.list(dir)) if (Segments.parseName(n) != null) Fs.remove(File(dir, n))
        return
    }
    if (!o.commit()) return
    Fs.writeAtomic(File(dir, "meta.json"), meta.copy(pre = null).encode())
}
