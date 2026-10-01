package org.revdog.retriever.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.revdog.retriever.LogLevel
import org.revdog.retriever.Retriever
import org.revdog.retriever.RetrieverLog
import timber.log.Timber
import java.io.File

/**
 * 手测面板（界面全部用代码搭：没有 layout xml、没有 AppCompat —— 示例只依赖 SDK 本身）。
 * 显示 installId / supportCode / 上传级别 / 出站箱待传数；按钮演示三种写法、flush、setUser、崩溃恢复、5000 行压测。
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var lastAction: TextView
    private var user: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    enum class Kind { PAYMENT }

    class PaymentDeclined(code: Int) : RuntimeException("payment declined ($code)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        if (savedInstanceState == null) ScenarioRunner.runIfRequested(intent?.getStringExtra(EXTRA_SCENARIO))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ScenarioRunner.runIfRequested(intent.getStringExtra(EXTRA_SCENARIO))
    }

    override fun onResume() {
        super.onResume()
        main.post(refresher)
    }

    override fun onPause() {
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private fun buildContentView(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 14f }
        lastAction = TextView(this).apply { textSize = 12f }
        col.addView(status)
        if (!ExampleApp.hasKey) {
            col.addView(TextView(this).apply { text = getString(R.string.no_key_hint) })
        }
        col.addView(lastAction)

        button(col, "记 debug×50（Retriever.log）") {
            for (i in 0 until 50) Retriever.log(LogLevel.DEBUG, "debug line $i", "example")
            done("写了 50 行 debug（低于上传级别：只随 error 批作 ctx）")
        }
        button(col, "记 info（Timber）") {
            Timber.tag("example.timber").i("user opened plan page %s", "yearly")
            done("Timber info")
        }
        button(col, "记 warn（RetrieverLog）") {
            RetrieverLog.w("net", "slow response 3.2s")
            done("RetrieverLog.w")
        }
        button(col, "记 error（带 attrs 与 Throwable）") {
            Retriever.log(
                LogLevel.ERROR, "purchase failed", "billing",
                mapOf("sku" to "pro_yearly", "retry" to false, "ms" to 3200, "kind" to Kind.PAYMENT.name),
                PaymentDeclined(7),
            )
            done("error：2 s 去抖后封段并上传（带 ctx）")
        }
        button(col, "模拟大量日志（5000 行）") {
            Thread {
                for (i in 0 until 5000) {
                    Retriever.log(if (i % 100 == 0) LogLevel.WARN else LogLevel.DEBUG, "bulk $i " + "x".repeat(80), "bulk")
                }
            }.start()
            done("后台写 5000 行")
        }
        button(col, "上报问题（flush）") {
            done("flush 中…")
            Retriever.flush { r -> main.post { done("flush → $r") } }
        }
        button(col, "setUser 切换") {
            user = if (user == null) "demo-user-1" else null
            Retriever.setUser(user)
            done("setUser(${user ?: "null"})：封段，用户边界 = 批边界")
        }
        button(col, "崩溃（throw RuntimeException）") {
            Retriever.log(LogLevel.WARN, "about to crash on purpose", "example")
            throw RuntimeException("RetrieverExample: 主动崩溃（下次启动会合成 rtv.unclean_exit 并补传）")
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun done(s: String) {
        lastAction.text = s
        refresh()
    }

    private fun refresh() {
        status.text = getString(
            R.string.status_format,
            Retriever.installId ?: "—",
            Retriever.supportCode ?: "—",
            outboxPending(),
            Retriever.uploadLevel.name.lowercase(),
            Retriever.localLevel.name.lowercase(),
            user ?: "（未登录）",
        )
    }

    /**
     * 演示用：直接数出站箱里的 p*.gz（布局见方案 §3.2；宿主 app 不需要这么做）。主线程上的磁盘读自己放行 StrictMode：
     * `strictmode` 场景之后主线程带着 detectAll + penaltyDeath，示例自己的读盘不能把验收搅乱。
     */
    private fun outboxPending(): Int {
        val old = StrictMode.allowThreadDiskReads()
        try {
            return File(noBackupFilesDir, "retriever/outbox").list()?.count { it.startsWith("p") && it.endsWith(".gz") } ?: 0
        } finally {
            StrictMode.setThreadPolicy(old)
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
    }
}
