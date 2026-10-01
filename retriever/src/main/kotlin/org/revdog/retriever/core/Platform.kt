package org.revdog.retriever.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

// 可注入的平台边界：时钟、传输、生命周期 / 后台作业 / 网络 / 磁盘 / StrictMode / 栈。测试用假实现替换，
// 引擎核心因此是纯 JVM 可测的（android.* 只出现在 org.revdog.retriever.android 里）。

/** 时钟：墙钟（行 ts、created_ms）与单调时钟（退避、暂停、配置 ttl、去抖）。 */
internal interface Clock {
    fun wallMs(): Long

    fun monoMs(): Long

    /** 流程内的短等待（flush / 后台排空等待相邻请求间隔）。 */
    fun sleep(ms: Long)

    /** 调度器定时唤醒的真实延迟；null = 不自动唤醒（测试假时钟，手动 tick）。 */
    fun timerDelayMs(ms: Long): Long?
}

internal class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)

internal class HttpResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

/** 传输：专用 HTTP 客户端（不经宿主的 OkHttp / 拦截器，避免把上传请求再记成日志，§3.3-6）。 */
internal interface Transport {
    /** 网络错误（超时、TLS、离线、被取消）返回 null。 */
    fun send(request: HttpRequest): HttpResponse?

    /** 取消在途请求（作业被系统停止）。 */
    fun cancelAll()
}

internal enum class PlatformEvent { DID_ENTER_BACKGROUND, WILL_ENTER_FOREGROUND, NETWORK_RESTORED }

internal interface PlatformEventSink {
    fun platformEvent(event: PlatformEvent)
}

/** 平台钩子（§3.9）。Android 实现在 `AndroidPlatform`；测试用假实现。 */
internal interface Platform {
    /** 设备快照字段（不含 sdk）：os, os_version, model, app_version, build, locale。 */
    fun deviceFields(): Map<String, String>

    /** 启动时是否在前台（写初始 last_state）；null = 无前后台概念 → last_state 留空，退出判 unknown。 */
    fun isForeground(): Boolean?

    /** 自动进程名：主进程 "main"，其它取进程名 `:` 之后的后缀。 */
    fun autoProcessName(): String

    /** 开始把生命周期与网络恢复事件送给 sink（同一平台对象只注册一次，sink 可替换）。 */
    fun startObserving(sink: PlatformEventSink)

    fun stopObserving(sink: PlatformEventSink)

    /** 可用磁盘空间（null = 未知，不按空间收缩上限）。 */
    fun availableBytes(dir: File): Long?

    /**
     * 排一个一次性后台上传作业（JobScheduler：网络约束、持久化；缺开机权限退回非持久）。同 id 已有**我们自己的**作业在排 / 在跑
     * 则不动；同 id 是别人的作业一律不碰（ADR 0024 决定 11）。返回 false = 没排上（别人占了这个 id、或系统拒绝），调用方记一次诊断。
     */
    fun scheduleUploadJob(jobId: Int): Boolean

    /** 取消排着的后台上传作业（`setEnabled(false)`，ADR 0020 决定 2）——只取消我们自己的。 */
    fun cancelUploadJob(jobId: Int)

    /** Throwable → 栈文本（Android：[stackTraceText]）。 */
    fun stackTraceString(t: Throwable): String

    /** 宿主线程上碰磁盘处放行 StrictMode（Android：磁盘读写 + API 26+ 的 unbuffered IO），返回旧策略令牌。 */
    fun allowDiskWrites(): Any?

    fun restoreDiskPolicy(token: Any?)
}

/**
 * 栈文本 = `StringWriter` + `printStackTrace`（JDK 自带环检测，成环处输出 `[CIRCULAR REFERENCE: …]` 后正常结束）。
 * 不用 `Log.getStackTraceString`：cause 链含 UnknownHostException 时它返回空串（网络错误恰恰丢栈），cause 成环时死循环。
 * 取栈本身失败（宿主异常的 toString / printStackTrace 抛出、cause 链极深导致 StackOverflowError）返回空串，绝不抛给宿主。
 */
internal fun stackTraceText(t: Throwable): String = try {
    val sw = StringWriter(256)
    val pw = PrintWriter(sw, false)
    t.printStackTrace(pw)
    pw.flush()
    sw.toString()
} catch (e: Throwable) {
    ""
}

/**
 * `HttpURLConnection` 传输：不缓存、连接 / 读超时 30 s、不跟随重定向（服务端不回 3xx）、固定长度流式上传。
 * 每个请求独立连接；`cancelAll` 断开在途连接（系统停止后台作业时）。
 */
internal class HttpUrlTransport : Transport {
    private val inflight: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())

    override fun send(request: HttpRequest): HttpResponse? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(request.url).openConnection() as HttpURLConnection
            inflight.add(conn)
            conn.requestMethod = request.method
            conn.useCaches = false
            conn.connectTimeout = ClientConstants.REQUEST_TIMEOUT_MS
            conn.readTimeout = ClientConstants.REQUEST_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            for ((k, v) in request.headers) conn.setRequestProperty(k, v)
            val body = request.body
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val headers = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null && v.isNotEmpty()) headers[k.lowercase()] = v[v.size - 1]
            }
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            val data = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            return HttpResponse(status, headers, data)
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        } finally {
            if (conn != null) {
                inflight.remove(conn)
                conn.disconnect()
            }
        }
    }

    override fun cancelAll() {
        val all = synchronized(inflight) { inflight.toList() }
        for (c in all) {
            try {
                c.disconnect()
            } catch (e: RuntimeException) {
                // 忽略
            }
        }
    }

    private fun readLimited(s: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
