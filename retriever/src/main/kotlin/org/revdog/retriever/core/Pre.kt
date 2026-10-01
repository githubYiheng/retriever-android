package org.revdog.retriever.core

import org.revdog.retriever.LogLevel
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// configure 之前（ADR 0023；简报 §1.1）：没有实例，`log()` 只把行追加到 `<默认 root>/pre/<uuid>.jsonl`；
// 没落盘的行进程内计数（ADR 0024 决定 1；简报 §4）。与 iOS 同口径（文件名、记录格式、数字）。

/**
 * 没落盘的行（简报 §4.1）：总行数、error 及以上行数、最小 / 最大 ts。进程内、静态、按来源合并
 * （pre 文件超限 / 写失败 / 无 context、已 configure 却没有会话、level 为 null、内部异常）。禁用期间的行不计。
 * 一旦有可写会话就以合成 warn `rtv.pre_init_dropped` 上报并清零；只在内存（进程在此之前死掉则丢——承诺边界）。
 */
internal object DropCounter {
    class Snapshot(val count: Long, val errorCount: Long, val firstTs: Long, val lastTs: Long)

    private val lock = Any()
    private var count = 0L
    private var errorCount = 0L
    private var firstTs = 0L
    private var lastTs = 0L

    fun add(level: LogLevel?, ts: Long) {
        synchronized(lock) {
            if (count == 0L) {
                firstTs = ts
                lastTs = ts
            } else {
                firstTs = minOf(firstTs, ts)
                lastTs = maxOf(lastTs, ts)
            }
            count += 1
            if (level != null && level.rank >= LogLevel.ERROR.rank) errorCount += 1
        }
    }

    /** 取走并清零（没有计数返回 null）。 */
    fun take(): Snapshot? = synchronized(lock) {
        if (count == 0L) return null
        val s = Snapshot(count, errorCount, firstTs, lastTs)
        count = 0
        errorCount = 0
        s
    }

    /** 合成行没写成（没有会话了）：放回去，下次再报。 */
    fun putBack(s: Snapshot) {
        synchronized(lock) {
            if (count == 0L) {
                firstTs = s.firstTs
                lastTs = s.lastTs
            } else {
                firstTs = minOf(firstTs, s.firstTs)
                lastTs = maxOf(lastTs, s.lastTs)
            }
            count += s.count
            errorCount += s.errorCount
        }
    }

    fun reset() {
        synchronized(lock) {
            count = 0
            errorCount = 0
            firstTs = 0
            lastTs = 0
        }
    }

    val pending: Long get() = synchronized(lock) { count }

    const val TAG = "rtv.pre_init_dropped"
    const val MSG = "lines dropped before a session was available"
}

/**
 * pre 文件的记录（每条一行，`\n` 结尾；简报 §1.1）：
 * - 头记录（首行）：`{"pre":1,"started_ms":N,"process":"<p>","device":{os,os_version,model,app_version,build,locale,sdk}}`
 * - 用户切换：`{"pre_user":"<清洗后的 id>"}` / `{"pre_user":null}`
 * - 行：`{"r":0,` 或 `{"r":1,` + 行体（LineEncoder 的 `"ts":…}`，不含 seq / oseq）。`r` = 是否已过 redact。
 *   收编时把 7 字节前缀换成 seq / oseq 前缀即得段文件的行——无 redact 钩子时只做字节拼接、不解码。
 */
internal object PreRecords {
    val LINE0: ByteArray = Bytes.ascii("{\"r\":0,")
    val LINE1: ByteArray = Bytes.ascii("{\"r\":1,")
    private val HEADER: ByteArray = Bytes.ascii("{\"pre\":")
    private val USER: ByteArray = Bytes.ascii("{\"pre_user\":")
    private val TS: ByteArray = Bytes.ascii("\"ts\":")
    private val LEVEL: ByteArray = Bytes.ascii(",\"level\":\"")
    private val SYNTH: List<ByteArray> = listOf(Bytes.ascii(",\"synthetic\":true}"), Bytes.ascii(",\"synthetic\":true,\"truncated\":true}"))

    fun header(startedMs: Long, process: String, device: Device): ByteArray {
        val o = JsonOut(256)
        o.raw("{\"pre\":1,\"started_ms\":"); o.int(startedMs)
        o.raw(",\"process\":"); o.string(process)
        o.raw(",\"device\":"); device.encode(o)
        o.raw("}\n")
        return o.toByteArray()
    }

    fun user(u: String?): ByteArray {
        val o = JsonOut(64)
        o.raw("{\"pre_user\":"); o.stringOrNull(u)
        o.raw("}\n")
        return o.toByteArray()
    }

    /** `body` = LineEncoder 的行体（`"ts":…}\n`）。 */
    fun line(redacted: Boolean, body: ByteArray): ByteArray {
        val p = if (redacted) LINE1 else LINE0
        val out = ByteArray(p.size + body.size)
        System.arraycopy(p, 0, out, 0, p.size)
        System.arraycopy(body, 0, out, p.size, body.size)
        return out
    }

    sealed class Rec {
        class Header(val startedMs: Long, val process: String, val device: Device) : Rec()

        class User(val id: String?) : Rec()

        /** `body` = 行体 + `\n`（可直接交给 Writer）；`ts` / `level` 按行首固定位置解析；`synthetic` 按行尾固定位置判断。 */
        class Line(val redacted: Boolean, val body: ByteArray, val ts: Long, val level: LogLevel, val synthetic: Boolean) : Rec()

        object Bad : Rec()
    }

    /** 解析 `[s, e)`（不含 `\n`）一条记录。按前缀分类，不按子串嗅探。 */
    fun parse(d: ByteArray, s: Int, e: Int): Rec {
        if (Bytes.startsWith(d, s, e, LINE0) || Bytes.startsWith(d, s, e, LINE1)) {
            val redacted = d[s + 5].toInt() == 0x31
            val bs = s + LINE0.size
            val (ts, level) = tsLevelAt(d, bs, e) ?: return Rec.Bad
            val body = ByteArray(e - bs + 1)
            System.arraycopy(d, bs, body, 0, e - bs)
            body[body.size - 1] = 0x0A
            val synthetic = SYNTH.any { Bytes.hasSuffix(d, s, e, it) }
            return Rec.Line(redacted, body, ts, level, synthetic)
        }
        if (Bytes.startsWith(d, s, e, USER)) {
            val o = JsonIn.obj(d.copyOfRange(s, e)) ?: return Rec.Bad
            if (!o.containsKey("pre_user")) return Rec.Bad
            val v = o["pre_user"]
            if (v != null && v !is String) return Rec.Bad
            // 读回时再清洗一遍（文件可能被别的版本 / 外部改过）
            return Rec.User(Text.sanitizeUserId(v as String?))
        }
        if (Bytes.startsWith(d, s, e, HEADER)) {
            val o = JsonIn.obj(d.copyOfRange(s, e)) ?: return Rec.Bad
            val started = JsonIn.int64(o["started_ms"]) ?: return Rec.Bad
            val dev = Device.decode(o["device"]) ?: return Rec.Bad
            return Rec.Header(started, RetrieverClient.sanitizeProcessName((o["process"] as? String) ?: "main"), dev.sanitized())
        }
        return Rec.Bad
    }

    /** 行体开头固定为 `"ts":<整数>,"level":"<级别>"`：按位置取 ts 与级别。 */
    private fun tsLevelAt(d: ByteArray, from: Int, end: Int): Pair<Long, LogLevel>? {
        if (!Bytes.startsWith(d, from, end, TS)) return null
        var i = from + TS.size
        var neg = false
        if (i < end && d[i].toInt() == 0x2D) {
            neg = true
            i++
        }
        val digits = i
        var v = 0L
        while (i < end && d[i] >= 0x30 && d[i] <= 0x39) {
            v = v * 10 + (d[i] - 0x30)
            i++
        }
        if (i == digits || i - digits > 18) return null
        if (!Bytes.startsWith(d, i, end, LEVEL)) return null
        i += LEVEL.size
        var j = i
        while (j < end && d[j].toInt() != 0x22) j++
        if (j >= end) return null
        val level = LogLevel.ofWire(String(d, i, j - i, Charsets.US_ASCII)) ?: return null
        return Pair(if (neg) -v else v, level)
    }
}

/**
 * 本进程的 pre 文件（`<root>/pre/<uuid>.jsonl`）：进程内首次写时创建并持 flock（表示写它的进程还活着）。
 * 追加由本对象自己的小锁串行化（与 client 无关）。读与写都走同一个 channel：同一进程里开再关同一文件的任何 fd
 * 都会释放本进程持有的 fcntl 锁，所以收编读本进程的文件不另开 fd。
 */
internal class PreFile private constructor(val file: File, private val raf: RandomAccessFile, private val flock: FileLock) {
    enum class Put { WRITTEN, FULL, FAILED, CLOSED }

    private val lock = ReentrantLock()
    private var size = 0L
    private var full = false

    /** 用户切换记录写失败：之后任何记录都不再写（粘滞），免得之后的行挂到错的用户名下。 */
    private var broken = false
    private var closed = false

    val name: String get() = file.name

    val isClosed: Boolean get() = lock.withLock { closed }

    val length: Long get() = lock.withLock { size }

    /**
     * 一次 write 追加。`capped` = 交接（configure）之前的记录（r = 0 行与 configure 之前的用户记录）：1 MB 上限（含头记录）——
     * 追加后不超过 1048576 字节就写；否则这条不写（调用方计数）并把文件标满，之后的 capped 记录都不再写（粘滞，免得更小的行越过
     * 没写成的用户切换记录）。交接之后（收编中）的 r = 1 行与用户记录不受上限约束。写失败截回追加前的长度。
     */
    fun append(bytes: ByteArray, capped: Boolean): Put = lock.withLock {
        if (closed) return Put.CLOSED
        if (broken) return Put.FAILED
        if (capped && (full || size + bytes.size > MAX_BYTES)) {
            full = true
            return Put.FULL
        }
        try {
            if (TestHooks.preWriteFault?.invoke(bytes) == true) throw IOException("injected pre write failure")
            raf.seek(size)
            raf.write(bytes)
            size += bytes.size
            Put.WRITTEN
        } catch (e: IOException) {
            try {
                raf.setLength(size)
            } catch (e2: IOException) {
                // 读侧会把残行当坏记录跳过
            }
            Put.FAILED
        }
    }

    /** 用户切换记录没写成：标坏（粘滞），之后的记录一律不写（调用方计数）。 */
    fun markBroken() {
        lock.withLock { broken = true }
    }

    /**
     * 读 `[from, 读时的长度)`；读失败返回 null。锁内只取长度快照，定位读在锁外（同一 channel 的 pread，不动写位置）——
     * 收编读 1 MB 时不挡住宿主线程上的追加。
     */
    fun readFrom(from: Long): ByteArray? {
        val end = lock.withLock { if (closed) return null else size }
        return readRange(from, end)
    }

    private fun readRange(from: Long, end: Long): ByteArray? {
        val n = end - from
        if (n <= 0) return ByteArray(0)
        if (n > Int.MAX_VALUE) return null
        val buf = ByteBuffer.allocate(n.toInt())
        return try {
            var pos = from
            while (buf.hasRemaining()) {
                val r = raf.channel.read(buf, pos)
                if (r < 0) break
                pos += r
            }
            if (buf.hasRemaining()) null else buf.array()
        } catch (e: IOException) {
            null
        }
    }

    /** 在本文件的锁内做事（收编提交：读剩余记录、切正常态、unlink 必须与追加互斥）。 */
    fun <T> locked(body: () -> T): T = lock.withLock(body)

    /**
     * 提交点 / 清空：unlink 成功（或文件已不在），或 unlink 失败但把自己持有的句柄截成 0 长度成功（恢复按「长度 0 = 已提交」判）
     * → 关闭（放 flock）返回 true；两者都失败返回 false，文件仍开着、内容完整（调用方稍后重试）。
     */
    fun unlinkAndClose(): Boolean = lock.withLock {
        if (closed) return true
        val gone = try {
            file.delete() || !file.exists()
        } catch (e: SecurityException) {
            false
        }
        val ok = gone || try {
            raf.setLength(0)
            size = 0
            true
        } catch (e: IOException) {
            false
        }
        if (ok) closeLocked()
        ok
    }

    /** 关闭但不删（测试模拟进程死亡；或文件交出后不再由本对象管理）。 */
    fun close() {
        lock.withLock { closeLocked() }
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        try {
            flock.release()
        } catch (e: IOException) {
            // 关 channel 时内核一并释放
        }
        try {
            raf.close()
        } catch (e: IOException) {
            // 忽略
        }
        LIVE.remove(file.absolutePath)
    }

    companion object {
        /** 1 MB 上限（简报 §1.1）。 */
        const val MAX_BYTES = 1024L * 1024

        const val DIR = "pre"
        const val SUFFIX = ".jsonl"

        /** 本 JVM 里还开着的 pre 文件（绝对路径）：孤儿扫描与驱逐先按它跳过，不在本进程里开再关这些文件。 */
        private val LIVE: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun isLiveHere(f: File): Boolean = f.absolutePath in LIVE

        /** 在 `<root>/pre/` 下独占新建（权限 0600）并加锁；失败返回 null（调用方计数）。 */
        fun create(root: File): PreFile? {
            val dir = File(root, DIR)
            if (!Fs.ensureDir(dir)) return null
            val f = File(dir, Ids.newV4() + SUFFIX)
            try {
                if (!f.createNewFile()) return null
                f.setReadable(false, false)
                f.setWritable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
            } catch (e: IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
            val raf = try {
                RandomAccessFile(f, "rw")
            } catch (e: IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
            val fl = try {
                raf.channel.tryLock()
            } catch (e: IOException) {
                null
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (fl == null) {
                try {
                    raf.close()
                } catch (e: IOException) {
                    // 忽略
                }
                f.delete()
                return null
            }
            LIVE.add(f.absolutePath)
            return PreFile(f, raf, fl)
        }
    }
}

/**
 * 别的（可能已死的）进程留下的 pre 文件：拿得到 flock = 写它的进程已死（孤儿）。持锁期间读、收编、unlink；
 * 读也走这把锁所在的 channel。拿不到（别的进程活着；或同 JVM 里有人持有，只在测试里出现）返回 null。
 */
internal class OrphanPre private constructor(val file: File, private val raf: RandomAccessFile, private val flock: FileLock) {
    fun readAll(): ByteArray? = try {
        val n = raf.channel.size()
        if (n > Int.MAX_VALUE) {
            null
        } else {
            val buf = ByteBuffer.allocate(n.toInt())
            var pos = 0L
            while (buf.hasRemaining()) {
                val r = raf.channel.read(buf, pos)
                if (r < 0) break
                pos += r
            }
            if (buf.hasRemaining()) null else buf.array()
        }
    } catch (e: IOException) {
        null
    }

    val length: Long
        get() = try {
            raf.channel.size()
        } catch (e: IOException) {
            -1
        }

    /** 提交：unlink，失败则截成 0 长度（恢复按「长度 0 = 已提交」判）。都失败返回 false。 */
    fun commit(): Boolean {
        val gone = try {
            file.delete() || !file.exists()
        } catch (e: SecurityException) {
            false
        }
        if (gone) return true
        return try {
            raf.setLength(0)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun release() {
        try {
            flock.release()
        } catch (e: IOException) {
            // 忽略
        }
        try {
            raf.close()
        } catch (e: IOException) {
            // 忽略
        }
    }

    companion object {
        fun tryAcquire(f: File): OrphanPre? {
            if (PreFile.isLiveHere(f) || !f.isFile) return null
            val raf = try {
                RandomAccessFile(f, "rw")
            } catch (e: IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
            val fl = try {
                raf.channel.tryLock()
            } catch (e: IOException) {
                null
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (fl == null) {
                try {
                    raf.close()
                } catch (e: IOException) {
                    // 忽略
                }
                return null
            }
            // 打开与加锁之间对方可能已提交（unlink 后放锁）：锁住的是已删的 inode，放弃（否则会重复收编）
            if (!f.isFile) {
                try {
                    fl.release()
                    raf.close()
                } catch (e: IOException) {
                    // 忽略
                }
                return null
            }
            return OrphanPre(f, raf, fl)
        }
    }
}

/**
 * 进程内 configure 之前的状态（静态）：本进程的 pre 文件与 configure 之前 `setUser` 的值。
 * 状态机：OPEN（没有实例；按需建文件）→ HANDED（configure 进行中，文件已交给新实例）→ DONE（实例已发布）。
 * HANDED / DONE 时静态路径上迟到的 `log()`：文件还开着就照常追加（`r` = 0，收编时过 redact），否则改走实例（REDIRECT）；
 * HANDED 而没有文件时等 configure 结束（WAIT）——不新建一个实例看不到的文件。
 */
internal object PreLog {
    enum class Res { WRITTEN, DROPPED, REDIRECT, WAIT }

    private enum class State { OPEN, HANDED, DONE }

    private val lock = Any()
    private var state = State.OPEN
    private var file: PreFile? = null
    private var user: String? = null

    val pendingUser: String? get() = synchronized(lock) { user }

    /** configure 之前的 `log()`：`record` = [PreRecords.line] 的结果；`header` 只在新建文件时调用。 */
    fun appendLine(root: File, record: ByteArray, header: () -> ByteArray): Res = synchronized(lock) {
        when (state) {
            State.OPEN -> {
                val f = file
                if (f == null) {
                    createWith(root, record, header)
                } else {
                    put(f.append(record, true))
                }
            }
            State.HANDED -> {
                val f = file ?: return Res.WAIT
                val r = f.append(record, true)
                if (r == PreFile.Put.CLOSED) Res.REDIRECT else put(r)
            }
            State.DONE -> {
                val f = file ?: return Res.REDIRECT
                val r = f.append(record, true)
                if (r == PreFile.Put.CLOSED) Res.REDIRECT else put(r)
            }
        }
    }

    /**
     * 进程内首次建 pre 文件之前：`pre/` 下拿得到锁的旧文件（不含别的活进程的）总量超过 4 MB 或个数超过 8 个时，按 mtime 从最旧的删起，
     * 直到都不超（R-5：从不 configure 的宿主不让 pre 文件无限累积）。不计数——这些行从未判定过义务，同本地环段到期驱逐。
     */
    private fun trimDead(root: File) {
        val dir = File(root, PreFile.DIR)
        val dead = ArrayList<OrphanPre>()
        try {
            for (n in Fs.list(dir)) if (n.endsWith(PreFile.SUFFIX)) OrphanPre.tryAcquire(File(dir, n))?.let { dead.add(it) }
            if (dead.size <= MAX_DEAD_FILES && dead.sumOf { it.length.coerceAtLeast(0) } <= MAX_DEAD_BYTES) return
            dead.sortWith(compareBy<OrphanPre>({ Fs.mtimeMs(it.file) ?: 0L }, { it.file.name }))
            var count = dead.size
            var bytes = dead.sumOf { it.length.coerceAtLeast(0) }
            for (o in dead) {
                if (count <= MAX_DEAD_FILES && bytes <= MAX_DEAD_BYTES) break
                val len = o.length.coerceAtLeast(0)
                if (o.file.delete()) {
                    count -= 1
                    bytes -= len
                }
            }
        } finally {
            for (o in dead) o.release()
        }
    }

    /** 死掉的 pre 文件的总量 / 个数上限（两端同值）。 */
    const val MAX_DEAD_BYTES = 4L * 1024 * 1024
    const val MAX_DEAD_FILES = 8

    private fun put(r: PreFile.Put): Res = when (r) {
        PreFile.Put.WRITTEN -> Res.WRITTEN
        PreFile.Put.CLOSED -> Res.REDIRECT
        else -> Res.DROPPED
    }

    /** 首次写：头记录 + （有的话）当前用户 + 这一行，一次 write。写不成就删掉文件，下次再建。 */
    private fun createWith(root: File, record: ByteArray, header: () -> ByteArray): Res {
        trimDead(root)
        val f = PreFile.create(root) ?: return Res.DROPPED
        val h = header()
        val u = user?.let { PreRecords.user(it) } ?: ByteArray(0)
        val all = ByteArray(h.size + u.size + record.size)
        System.arraycopy(h, 0, all, 0, h.size)
        System.arraycopy(u, 0, all, h.size, u.size)
        System.arraycopy(record, 0, all, h.size + u.size, record.size)
        return when (f.append(all, true)) {
            PreFile.Put.WRITTEN -> {
                file = f
                Res.WRITTEN
            }
            else -> {
                f.unlinkAndClose()
                Res.DROPPED
            }
        }
    }

    /** configure 之前的 `setUser`（已清洗）：记内存；文件已存在则追加一条用户切换记录（随后创建时写在头记录之后）。 */
    fun setUser(u: String?) {
        synchronized(lock) {
            if (u == user) return
            user = u
            if (state == State.OPEN) {
                val f = file
                // 写失败（满了已经粘滞）：标坏，之后的行计数，绝不挂到错的用户名下
                if (f != null && f.append(PreRecords.user(u), true) == PreFile.Put.FAILED) f.markBroken()
            }
        }
    }

    /** configure：取走本进程的 pre 文件（可能为 null）交给新实例。 */
    fun handoff(): PreFile? = synchronized(lock) {
        state = State.HANDED
        file
    }

    /** 实例已发布。 */
    fun configured() {
        synchronized(lock) { state = State.DONE }
    }

    /** configure 失败（没建成实例）：回到 OPEN，文件留给下一次 configure。 */
    fun handoffFailed() {
        synchronized(lock) { if (state == State.HANDED) state = State.OPEN }
    }

    /** configure 之前的 purgeLocal：删本进程 pre 文件（下次写时重建；用户值保留）。 */
    fun discard() {
        synchronized(lock) {
            file?.unlinkAndClose()
            file = null
        }
    }

    /** 测试：模拟进程重启（关掉文件、不删，回到初始状态）。 */
    fun resetForTesting() {
        synchronized(lock) {
            file?.close()
            file = null
            user = null
            state = State.OPEN
        }
    }

    val currentFileForTesting: File? get() = synchronized(lock) { file?.file }
}

/**
 * 禁用标记（ADR 0020 决定 2；ADR 0024 决定 6 三态）：root 同级空文件 `<root>.disabled`。
 * 判定：存在 → PRESENT；`exists()` 为假时列父目录——列不出（无权限、I/O、首次解锁前）→ UNKNOWN（按禁用处理），
 * 列得出且不含它 → ABSENT（`exists()` 对 stat 错误也返回 false，不能只靠它）。
 */
internal object Markers {
    enum class State { PRESENT, ABSENT, UNKNOWN }

    fun file(root: File): File = File(root.absoluteFile.parentFile, root.name + ".disabled")

    fun state(root: File): State {
        val f = file(root)
        if (f.exists()) return State.PRESENT
        val names = f.parentFile?.list() ?: return State.UNKNOWN
        return if (f.name in names) State.PRESENT else State.ABSENT
    }

    /** 写标记（createNewFile 原子创建）。 */
    fun mark(root: File): Boolean {
        val f = file(root)
        return try {
            f.createNewFile() || f.exists()
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** 删标记；删不掉返回 false。 */
    fun clear(root: File): Boolean {
        val f = file(root)
        return try {
            !f.exists() || f.delete() || !f.exists()
        } catch (e: SecurityException) {
            false
        }
    }
}

/** 清空（ADR 0019 决定 9）：root 整个改名为同级 `<root>.purge-<uuid>` 再删。实例与 configure 之前的 purge 共用。 */
internal object RootPurge {
    const val INFIX = ".purge-"

    /** 返回改名后的目录；root 不存在或改名失败返回 null。 */
    fun moveAside(root: File): File? = (move(root) as? Moved)?.dir

    sealed class Result

    class Moved(val dir: File) : Result()

    /** root 本来就不在：没东西可清。 */
    object Absent : Result()

    /** 改名失败（root 所在目录只读等）：调用方退回逐项删除。 */
    object Failed : Result()

    fun move(root: File): Result {
        val parent = root.absoluteFile.parentFile ?: return Failed
        if (!root.exists()) return Absent
        val dst = File(parent, root.name + INFIX + Ids.newV4())
        return if (root.renameTo(dst)) Moved(dst) else Failed
    }

    /** 改名失败时的兜底：逐项删除 root 下的内容（宁可失去原子性也要把本地数据清掉；与实例的 purge、iOS 同口径）。 */
    fun deleteContents(root: File) {
        for (name in Fs.list(root)) Fs.remove(File(root, name))
    }

    /** 改名失败时先列出 root 下此刻的全部条目（文件与目录，深度优先）——之后只删这些，purge 之后新建的 pre 文件与状态不碰。 */
    fun snapshot(root: File): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File) {
            for (n in Fs.list(d)) {
                val f = File(d, n)
                if (f.isDirectory) walk(f)
                out.add(f)
            }
        }
        walk(root)
        return out
    }

    /** 删 [snapshot] 列出的条目：文件直接删，目录只在已空时删（里面有 purge 之后新建的东西就留着）。 */
    fun deleteListed(entries: List<File>) {
        for (f in entries) {
            try {
                if (f.isDirectory) {
                    if (f.list()?.isEmpty() == true) f.delete()
                } else {
                    f.delete()
                }
            } catch (e: SecurityException) {
                // 删不掉的留着
            }
        }
    }

    fun removeLeftovers(root: File) {
        val parent = root.absoluteFile.parentFile ?: return
        val prefix = root.name + INFIX
        for (n in Fs.list(parent)) if (n.startsWith(prefix)) Fs.remove(File(parent, n))
    }
}

/** 测试注入点（生产恒为 null）：收编各步骤的崩溃点（`record:<n>` / `before_commit` / `after_unlink`）。 */
internal object TestHooks {
    @Volatile
    var adoption: ((String) -> Unit)? = null

    /** 非 null 且返回 true：这次 pre 文件追加按写失败处理（模拟 I/O 错误）。生产恒为 null。 */
    @Volatile
    var preWriteFault: ((ByteArray) -> Boolean)? = null
}
