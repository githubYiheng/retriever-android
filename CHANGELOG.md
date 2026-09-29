# Changelog

Retriever monorepo `sdk/android`。版本号遵循语义化版本：修订号 = 只修 bug；次版本 = 公开 API 只增；主版本 = 公开 API 有减或改。

## [0.1.0] - 2026-09-29

首个版本。协议 v1（信封 `v: 1`），minSdk 24，Kotlin 语言版本 2.0（stdlib 2.0.21），JVM 17。与 iOS `retriever-ios` 0.1.0 同规格。

### 新增
- **`retriever` 传输层本体**（依赖只有 kotlin-stdlib）：
  - `Retriever.configure` / `setUser` / `log` / `flush` / `setEnabled` / `purgeLocal` / `installId` / `supportCode` /
    `uploadLevel` / `localLevel`；`Options`（uploadLevel、localLevel、dailyBatchCap、localCapBytes、redact、processName、jobId、sdkVersion）。
  - 写入纪律：`log()` 返回前一次 `write` 落盘（常开 `FileOutputStream(file, true)`，无用户态缓冲）；进程被杀 / 崩溃不丢（真杀进程测试守着）；
    写入处包 `StrictMode.allowThreadDiskWrites()`。
  - 本地环（段 512 KB，默认 20 MB / 7 天）+ 出站箱（gzip 请求体即文件，确定性 batch_id，2xx 回显才删）；目录 `noBackupFilesDir/retriever/`。
  - 义务序号 oseq、error 带上下文（≤ 200 行 / 128 KB）、墓碑与会话终态上报、崩溃后合成 `rtv.unclean_exit`。
  - 退避与暂停（401 / 413 切分 / 429 按类别 / 503）、毒批隔离、固定驱逐顺序、远程配置（钳制、过期回落、full_dump + backfill 只走非计量网络）。
  - 生命周期：进后台封段 + 排空 + 一次性 JobScheduler 作业兜底（`RetrieverUploadJobService`）；fatal 也排作业；多进程各自会话目录、共享出站箱与 upload.lock。
  - `RetrieverInitProvider`：默认进程里 `configure` 之前的 `log()` 也落盘。
- **`RetrieverLog`**（本体内）：`android.util.Log` 的同名替身，双写 logcat 与 Retriever。
- **`retriever-timber`**：`RetrieverTree`（timber 5.0.1；`isLoggable` 早过滤、栈从 message 剥进 `exc`）。
- **示例 app**（`example/`，minSdk 26）：三种接入写法、flush、setUser、崩溃恢复、压测；`--es scenario error|user|bulk|crash|flush` 验收场景。
