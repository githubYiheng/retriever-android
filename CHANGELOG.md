# Changelog

Retriever monorepo `sdk/android`。版本号遵循语义化版本：修订号 = 只修 bug；次版本 = 公开 API 只增；主版本 = 公开 API 有减或改。

## [0.1.2] - 2026-09-30

发版前审查（`docs/audit/2026-09-30-prerelease/`）的修复，与 iOS `retriever-ios` 0.1.4 同口径。公开 API 不变。

- 修复：选批不再递归。原先出站箱有批、而 install 为空（`install.json` 写不进 / 读不了）或某个批文件读不出时，排空会无限递归（栈溢出、上传永久卡住）；现在 install 为空直接停在 `not_bootstrapped`，读不出的批本轮跳过、照发后面的批（不删、不计失败，留给下一轮与容量驱逐），全部读不出停在 `unreadable`。
- 修复：`install.json` 读到了但解析不了（位腐烂、0 字节）时不再永久失效：在同一把目录锁内原子重建（新 install_id、会话计数从 0 起），新会话里写一条合成 warn `rtv.install_reset`。读失败（权限、directBoot 未解锁、I/O 错误）仍按原样本次失败、稍后重试，绝不重建。
- 修复：拉配置在途期间调度器不再以 0 ms 空转。配置请求一构造就记下「上次尝试时刻」；另加调度地板：任何候选已到期时定时器也至少等 1 s。
- 修复：低磁盘时义务批不再在上传前被驱逐（ADR 0010）。原先「可用空间 + 已占 < 64 MB」（`getAllocatableBytes` 已扣系统低存储阈值，约 0.5 GiB 以下即触发）时上限算成 0，刚物化的批连同墓碑一起被删；现在磁盘余量只约束 RETAINED 段与 p2 backfill，隔离批 / p1 / p0 只受 `local_cap_bytes` 约束。
- 修复：只有响应体是 JSON 对象且带字符串 `reason` 的 401 / 403 才进入 1 h 起倍增到 24 h 的鉴权暂停（ADR 0011）；HTML、空体、无 `reason` 的 401 / 403（WAF、captive portal 等）按普通失败退避（≤ 15 min）并计入毒批判定。任何一次 2xx 确认都复位倍增状态，已到期的暂停一并清掉。
- 修复：崩溃恢复时序号高水位不再只看盘上。旧段已被驱逐时，合成的 `rtv.unclean_exit` 原先会与已上传的 oseq 撞号而永不上传、会话终态 `last_oseq` 低报；现在 oseq 取 max(盘上, 已物化水位, 本会话墓碑)，驱逐段时把其 `lastSeq` 并入 ctx 游标作 seq 高水位。
- 修复：异常栈改用 `printStackTrace` 文本，不再用 `Log.getStackTraceString`（cause 链含 `UnknownHostException` 时返回空栈；cause 成环时调用线程死循环）。取栈失败返回空栈，不抛给宿主。

## [0.1.1] - 2026-09-30

- 删除：backfill 批的网络类型判定（原先 `backfill_networks = unmetered` 时计量网络上不传 backfill）与远程配置字段 `backfill_networks`（ADR 0009：任何能力都不再考虑网络类型，该传就传）。backfill 批与其它批按同一套队列 / 退避规则上传；服务端旧配置里残留该字段按未知字段忽略。`ACCESS_NETWORK_STATE` 权限保留（网络恢复提前唤醒仍要用）。
- 随包带 LICENSE（MIT）。

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
