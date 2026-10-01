# Changelog

Retriever monorepo `sdk/android`。版本号遵循语义化版本：修订号 = 只修 bug；次版本 = 公开 API 只增；主版本 = 公开 API 有减或改。

## [0.3.0] - 2026-10-01

configure 顺序与宿主误用加固批（ADR 0022「配置响应标明取自宿主默认的字段」、ADR 0023「configure 之前没有实例」、ADR 0024「宿主误用加固」），
与 iOS `retriever-ios` 0.3.0 同口径。公开 API 只增（参数由非空放宽为可空，源码与二进制都兼容）；0.1.x / 0.2.x 留在盘上的全部状态照读。

### 迁移说明
- **多数宿主无需改代码。** 照旧在 `Application.onCreate` 里 `configure`；之前为了「先 configure 再打日志」做的初始化顺序调整可以保留，但不再必要。
- 单元测试的宿主进程会跑宿主的 `Application.onCreate`：测试里别注入真 key。开发机切换环境（staging ↔ 生产 / 换 app 的 key）前先 `purgeLocal()` 或卸载。
- 撤回同意的完整组合是 `setEnabled(false)` + `purgeLocal()` + `setUser(null)`（`purgeLocal` 不清用户）。
- 用 WorkManager / 自有 JobScheduler 作业的宿主：确认 `Options.jobId`（默认 `0x5254`）不在你的 id 区间里——同 id 是别人的作业时 SDK 不再替换它，只是排不上兜底作业。

### 行为变化（宿主请看）
- **`configure` 之前没有实例**：之前的 `log()` 只追加到 `noBackupFilesDir/retriever/pre/<uuid>.jsonl`，`configure` 时按**本次 configure** 的设定收编
  （过 redact、判上传义务、分 seq / oseq、error 去抖 / fatal 换段 / 用户边界照常）。原先懒建一个按内置默认 Options 判定的实例，先 log 后 configure 的宿主
  启动头几行是否上传取决于缓存新旧与线程调度，还会按默认上限提前驱逐、按默认级别恢复旧会话。configure 之前不驱逐、不联网、不排作业、不恢复旧会话；
  `installId` / `supportCode` 为 null，`uploadLevel` / `localLevel` 为 warn / debug；`flush` 回 `Pending("paused")`；`purgeLocal` 真清（pre 文件与 root）。
  pre 文件上限 1 MB；configure 之前进程就死 → 之后某次启动 configure 时作为独立会话收编上传。
- **redact 也作用于 configure 之前的行**（在收编时、可能在 SDK 线程上调）：redact 必须线程安全；它改 `ts` 无效（取回原值）。
- **实例身份只认首次 `configure`**：之后改 `processName` 被忽略并留合成 warn `rtv.reconfigure_ignored`；「换进程名 → 关旧实例建新实例」的路径删除。
  参数完全相同的重复 `configure` 只更新 redact、不再发配置请求。再次 `configure` 改的级别在调用线程上同步生效（之后立即写的行按新级别）。
- **配置缓存只记远程明确给的值**：响应的 `from_host`（服务端已上线）列出的宿主型字段永远取当前宿主默认——宿主改了 Options 立即、确定地生效，
  不再被上次请求头的回显覆盖到缓存过期。配置请求的身份加上四项宿主默认与 key 指纹：请求在途时它们变了，响应丢弃并重拉。
  SDK 钳制 `local_cap_bytes` 的缺省改为宿主值（对齐服务端权威实现）。
- **没落盘的行必须计数**：configure 之前超 1 MB / 写失败 / 拿不到 context、已 configure 却没有会话（bootstrap 失败、目录被删）、level 为 null、
  内部异常——一律计数，有可写会话后写合成 warn `rtv.pre_init_dropped`（attrs `count` / `error_count` / `first_ts` / `last_ts`）。原先静默丢弃。
  建会话必须确认 `meta.json` 写成，写不成按失败重试。
- **公开入口不抛、不崩宿主**：`configure` 的 context / key / baseUrl / options、`log` 的 level、`flush` / `purgeLocal` 的回调、`Options` 的引用类型 setter
  都可传 null；`msg ?: error.toString()` 这类会调宿主代码的求值也在兜底之内；attrs / 异常逐值防护（`toString` / `toDouble` / `getMessage` 抛异常、
  循环引用、并发修改 → 该值写 `"<unprintable>"` 并标 `truncated`，行照常落盘）；flush 回调必回、任何路径都吞掉回调抛的 Throwable；
  `RetrieverLog` 的落点吞掉 Error。
- attrs 里的数组 / Collection / Map 渲染成紧凑 JSON 文本（`[1,2,3]`），原先是 `[I@1a2b3c` / `[1, 2]`；超过 32 键时取 map 迭代顺序的前 32 个再排序（原先全排序后取前 32 个）。
- **禁用标记 fail-closed**：标记判定不了（目录列不出、I/O 错误、首次解锁前）按禁用处理，之后每次建会话成功、每次定时唤醒重判；configure 之前的
  `isEnabled` 也按标记判定（拿不到 context 时判定不了 = false）。configure 之前的 `setEnabled` 当场落盘 / 删除标记。
- **换 key 不带旧 key 的账**：`backoff.json`、`mapping.json`、`config.json` 记 key 指纹（sha256 前 16 位十六进制）与 baseUrl；换了就清鉴权暂停与退避、
  映射重发、配置按新身份重拉；旧 key 在途请求的 401 / 403 不暂停新 key。出站箱旧批照常用当前 key 发。
- **强制换段有节流**：fatal 10 s 窗口内只有第一条立即换段、排作业，其余并入 error 去抖封段；已有等待中的 flush 且之后没有新义务行时，新的 flush 挂到同一个等待上。
- 前后台状态从进程一开始就对：init provider 里注册进程级 tracker（按 Activity 身份集合计数，没见过 onStart 的 Activity 的 onStop 不参与）；
  provider 加 `android:initOrder="1000"`。`configure` 在 `attachBaseContext` 里传 base context 时，provider 稍后补注册生命周期。
- JobScheduler 作业认归属：同 id 是别人的作业不跳过、不取消、不替换；`setPersisted(true)` 因缺 `RECEIVE_BOOT_COMPLETED` 失败时退回非持久作业。
- 读失败 ≠ 没有内容：物化时段文件读不出不推进游标（定时重试），文件确已不存在才记墓碑后推进；目录锁拿不到时不执行临界区。
  会话目录 / root 在运行中被删：重新建会话并写合成 warn `rtv.root_vanished`。
- 行的级别 / ctx / synthetic / 是否已合成 `rtv.unclean_exit` 按解析位置判断，attrs 里的同名键不再影响物化优先级、413 切分与恢复判重。
- `setUser` 清洗后为空（`""`、纯空白、纯控制字符）= null。SDK 自己取消的请求（purge、后台作业被停）不计入毒批失败次数。

### 新增
- 合成行 `rtv.pre_init_dropped`、`rtv.root_vanished`、`rtv.reconfigure_ignored`。
- `Options.setUploadLevel(LogLevel?)` / `setLocalLevel(LogLevel?)` / `setSdkVersion(String?)`（Java 侧 setter 接受 null；Kotlin 照常用属性赋值）。
- 示例 app 场景 `preconfigure`、`preconfigure_kill`、`late_configure`（两步：先记一次性标记退出进程，再冷启动跑）。

### 盘上格式（全部是新文件 / 可选键；降级到 0.2.x 时 pre 文件留在盘上不被收编，升回来再收编）
- 新目录 `pre/`（`<uuid>.jsonl`）；`meta.json` 可选键 `pre`；`config.json` 加 `from_host`、`key_fp`、`base_url`；`backoff.json` / `mapping.json` 加 `key_fp`、`base_url`。
- 旧文件没有这些键：`from_host` 缺失 = 空集（同 0.2.x）；指纹缺失 = 视为当前 key（升级不清退避、不重发映射、不丢配置缓存），读到后补写当前值。

## [0.2.0] - 2026-10-01

遗留修复批（ADR 0019「本地状态自带真实归属」、ADR 0020「宿主线程不等待 SDK；`setEnabled` 落盘」），与 iOS `retriever-ios` 0.2.0 同口径。
公开 API 只增；0.1.x 留在盘上的全部状态照读（新键都是可选键），原地升级无需迁移。

### 默认行为变化（宿主请看）
- **`log(FATAL)`（含 `RetrieverLog.wtf`、Timber ASSERT）不再阻塞调用线程**：调用线程上只写行、换段并直接排 JobScheduler 作业（key 非空、已启用、
  远程 `upload_enabled` 时），封段物化在 SDK 后台线程上做；进程随后死亡由下次启动的恢复物化出同一个批。原先无超时地等后台线程，引擎忙时可致 ANR。
- **`purgeLocal()` 不再阻塞**：立即返回，清空在后台完成——返回时 `installId` 还是旧值，新增 `purgeLocal(callback)` 在完成时回调。
  换进程名的 `configure`（旧实例 `shutdown`）同样不再等待。
- **`setEnabled(false)` 跨重启持久**：落盘为 `noBackupFilesDir/retriever.disabled` 标记，直到 `setEnabled(true)`；禁用期间不写、不传、
  **不拉配置、不排后台作业，并取消已排的作业**（在途的那一个请求不打断）。把它当「临时暂停、重启恢复」用的宿主现在会一直禁用。
  `configure` 之前的调用会暂存并在建实例时生效。
- 超过 ±(2^53 − 1) 的整数 attrs 改为十进制字符串（原先转 Double，值被静默改写）；范围内的整数输出逐字节不变。

### 新增
- `Retriever.isEnabled`（只读）、`Retriever.purgeLocal(callback: Runnable)`。
- 示例 app `strictmode` 场景（`ThreadPolicy` / `VmPolicy` 都 `detectAll().penaltyDeath()` 后跑 error 场景）。
- README：Google Play Data safety 申报指引；宿主 compileSdk ≥ 36；同意与清空的新语义与多进程限制；fatal 不能在 signal handler 里调。

### 修复
- 会话终态只为有义务行（`last_oseq > 0`）的会话写，恢复时零行的会话目录直接删除。原先每个后台拉起的空进程都写一条终态，
  超过 20 条时有数据的真实会话终态被挤掉并从文件删除（服务端判 unknown，真实缺口不再被发现）。
- 终态 / 墓碑每批按文件顺序带最旧的未在途 20 / 100 条，携带不改写文件、不合并、不计数，带不完留给下一批；信封不再出现 `closed_sessions_dropped`。
  `drops.jsonl` / `sessions.jsonl` 各自上限 1000 条：墓碑先无损合并（同会话同原因、区间相接或重叠，n = 并集长度），仍超出删最旧的未在途条目。
  原先墓碑超限时按 (会话, 原因) 并成 [min, max] 甚至跨会话合并并改写文件，盖住真实缺口。
- `install.json` 损坏不再换 install_id：`meta.json` 新增可选键 `install_id`（身份冗余副本），损坏时从最近会话的副本修复、计数续上，
  留合成 warn `rtv.install_repaired`；有 meta 却读不了则本次失败稍后重试。找不到任何副本（只可能在升级后第一次启动恰逢损坏）才清空 root 新建，
  留 `rtv.install_reset`（attrs 带作废的批数与会话数）。原先换新 id 而旧会话、终态、墓碑、出站箱照用，旧批会被服务端隔离后确认删除。
- 清空（`purgeLocal` 与上一条）= root 先整个改名为同级 `retriever.purge-<uuid>` 再删，启动时清残留；中途被杀不再留下半个 root 与新 install 混用。
- 每个批以自身信封里的 install_id 作请求头 `X-Rtv-Install`；映射只在响应 `status = stored` 且批属于当前 install 时记为已确认
  （被隔离的批不再让映射推迟 24 h）。
- 413 切分改为「先写新、后删旧」：半批 batch_id = UUIDv5(`<install>:<session>:primary:<oseq_from>:<oseq_to>`)，全部半批写成才删原批，
  任一半写失败则删掉已写的半批、原批保留并按普通失败退避。原先前一半原地覆盖原批，后一半写失败即静默丢失。
- `setUser` 的值一变（与是否封段无关）就按新身份重拉配置，缓存里上一个身份的放大型配置立即回落；请求时身份与当前不符的响应丢弃并重拉。
- 恢复时段文件读不出：不推进 cursor、不删会话目录、不写终态，下次启动重试（原先整个会话目录被删，未物化的义务行无墓碑消失）。
- jsonl 追加写到一半失败时截回追加前的长度（原先半行与下一条粘连，两条一起丢）。
- attrs 的值先按预算判再转义（超长值不再整串转义、按数倍分配）。
- StrictMode：宿主线程上的写入另放行 unbuffered IO（API 26+，`detectAll()` 包含它），上传 / 拉配置请求打 `TrafficStats` 标签。
- 写失败路径不再在调用线程上 fsync，失败后关闭写句柄、1 s 后重开（接着原段追加）。
- 系统停止后台作业的停止标志只对当次作业的排空生效（原先常驻，同进程的排空被挡到下一次前后台切换）。
- `RetrieverLog` 先写 Retriever、再写 logcat（`Log.wtf` 可能直接终止进程）。
- 禁用状态下恢复旧会话不再合成 `rtv.unclean_exit`（合成行也是写入）；禁用期间启动的零行会话重新启用后不补报（目录直接删）。
- 413 切分写不出时按普通失败退避（`http_413`，不计毒批）；信封身份推导不出半批 id 的批交给服务端隔离。

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
