package org.revdog.retriever.example

import android.app.Application
import org.revdog.retriever.LogLevel
import org.revdog.retriever.Options
import org.revdog.retriever.Retriever
import org.revdog.retriever.timber.RetrieverTree
import timber.log.Timber

/** 三种接入方式：直接调 `Retriever.log`、Timber（`RetrieverTree`）、`RetrieverLog`（android.util.Log 替身）。 */
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1. 第一条日志之前 configure（key 为空 = 只写本地不上传）
        Retriever.configure(this, BuildConfig.RETRIEVER_KEY, BuildConfig.RETRIEVER_BASE_URL, Options())
        // 2. Timber：宿主现有 Timber.x(...) 调用不改
        Timber.plant(RetrieverTree())
        Retriever.log(LogLevel.INFO, "example app launched", "example")
    }

    companion object {
        val hasKey: Boolean get() = BuildConfig.RETRIEVER_KEY.isNotEmpty()
    }
}
