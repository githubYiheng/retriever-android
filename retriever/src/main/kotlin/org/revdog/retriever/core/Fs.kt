package org.revdog.retriever.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 文件系统纪律（§3.2 / §3.3）：
 * - 目录 = `noBackupFilesDir/retriever/`（默认不进 Auto Backup，无需改宿主 manifest）；
 * - 状态文件一律 tmp → `FileDescriptor.sync()` → `renameTo`；段文件常开 `FileOutputStream(file, true)`，不套任何 Buffered。
 */
internal object Fs {
    fun ensureDir(f: File): Boolean = f.isDirectory || f.mkdirs() || f.isDirectory

    fun exists(f: File): Boolean = f.exists()

    fun remove(f: File) {
        f.deleteRecursively()
    }

    fun list(dir: File): List<String> = dir.list()?.toList() ?: emptyList()

    fun read(f: File): ByteArray? = try {
        if (f.isFile) f.readBytes() else null
    } catch (e: IOException) {
        null
    }

    fun size(f: File): Long? = if (f.exists()) f.length() else null

    fun mtimeMs(f: File): Long? = if (f.exists()) f.lastModified() else null

    fun touch(f: File, wallMs: Long) {
        f.setLastModified(maxOf(wallMs, 0))
    }

    /** 原子写：同目录 tmp → write → sync → rename；失败不留半成品。 */
    fun writeAtomic(f: File, bytes: ByteArray): Boolean {
        val tmp = File(f.parentFile, ".${f.name}.tmp-${Ids.newV4()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
        } catch (e: IOException) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return false
        }
        return true
    }

    /** 追加一段字节（一次 write）；文件不存在则创建。 */
    fun append(f: File, bytes: ByteArray): Boolean = try {
        FileOutputStream(f, true).use { it.write(bytes) }
        true
    } catch (e: IOException) {
        false
    }

    /** 截断到 size（写失败回到上一完整行 / 截残行）。 */
    fun truncate(f: File, size: Long): Boolean = try {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(size)
            raf.fd.sync()
        }
        true
    } catch (e: IOException) {
        false
    }
}

/**
 * 根目录上的进程间锁（§3.2 `upload.lock`）：同一个 `upload.lock` 文件、同一个常开 channel 上的两个字节区间——
 * [0,1) = 上传者锁（`FileChannel.tryLock`，只在排空出站箱期间持有）；[1,2) = 根级共享状态的读改写锁
 * （install.json 计数器、drops.jsonl、sessions.jsonl；iOS 用根目录 fd 上的 flock，Java 不能锁目录）。
 *
 * fcntl 锁在进程内关闭同一文件的任何 fd 都会被释放，所以整个引擎只用这一个 channel，不在别处打开 upload.lock。
 * 进程内（同一 JVM 两个实例，只在测试里出现）另用按根路径的 ReentrantLock 串行化，避免 OverlappingFileLockException。
 */
internal class RootLocks(private val root: File) {
    private var raf: RandomAccessFile? = null
    private var uploadLock: FileLock? = null
    private val jvmLock: ReentrantLock = JVM_LOCKS.computeIfAbsent(root.absolutePath) { ReentrantLock() }

    val lockFile: File get() = File(root, "upload.lock")

    private fun channel(): FileChannel? {
        raf?.let { return it.channel }
        return try {
            RandomAccessFile(lockFile, "rw").also { raf = it }.channel
        } catch (e: IOException) {
            null
        }
    }

    fun <T> withDirLock(body: () -> T): T {
        if (jvmLock.isHeldByCurrentThread) return body()
        jvmLock.lock()
        try {
            val fl: FileLock? = try {
                channel()?.lock(1, 1, false)
            } catch (e: IOException) {
                null
            } catch (e: OverlappingFileLockException) {
                null
            }
            try {
                return body()
            } finally {
                try {
                    fl?.release()
                } catch (e: IOException) {
                    // 释放失败：channel 关闭时内核一并释放
                }
            }
        } finally {
            jvmLock.unlock()
        }
    }

    fun tryUploadLock(): Boolean {
        if (uploadLock != null) return true
        val fl = try {
            channel()?.tryLock(0, 1, false)
        } catch (e: IOException) {
            null
        } catch (e: OverlappingFileLockException) {
            null
        }
        uploadLock = fl
        return fl != null
    }

    fun releaseUploadLock() {
        val fl = uploadLock ?: return
        uploadLock = null
        try {
            fl.release()
        } catch (e: IOException) {
            // 同上
        }
    }

    val holdsUploadLock: Boolean get() = uploadLock != null

    /** purgeLocal / 关停：释放全部锁并关 channel（之后按需重开）。 */
    fun close() {
        releaseUploadLock()
        try {
            raf?.close()
        } catch (e: IOException) {
            // 忽略
        }
        raf = null
    }

    companion object {
        private val JVM_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    }
}

/**
 * 会话目录锁（iOS 用会话目录 fd 上的 flock）：锁住 `<session>/meta.json`。进程存活期间一直持有，进程死亡由内核释放；
 * 恢复流程拿不到某个旧会话的锁 = 该会话还活着（别的进程 / 同进程另一实例），跳过不动。
 */
internal class SessionLock private constructor(private val raf: RandomAccessFile, private val lock: FileLock) {
    fun release() {
        try {
            lock.release()
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
        /** 拿不到（被占用）返回 null；文件不存在也返回 null（不创建）。 */
        fun tryAcquire(meta: File): SessionLock? {
            if (!meta.isFile) return null
            val raf = try {
                RandomAccessFile(meta, "rw")
            } catch (e: IOException) {
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
            return SessionLock(raf, fl)
        }
    }
}
