# Retriever Android 传输层（`sdk/android`）

Kotlin，minSdk 24 / compileSdk 36，JVM 17；**宿主 compileSdk ≥ 36**（AAR 元数据 `minCompileSdk=36`，AGP 9 的默认行为，低于它宿主编译失败）。
规格：`docs/plan/system-design.md` §3；不变式：`docs/architecture.md` §2。
行为、字段、顺序、数字与 iOS 实现（`sdk/ios`，已真机验收）逐字一致；只在平台层不同（见文末「与 iOS 的平台差异」）。

| 模块 | 坐标 | 依赖 | 用途 |
|---|---|---|---|
| `retriever` | `org.revdog:retriever:0.3.0` | **只有 kotlin-stdlib**（无 AndroidX、无 coroutines、无 okhttp） | 传输层本体 + `RetrieverLog`（`android.util.Log` 替身） |
| `retriever-timber` | `org.revdog:retriever-timber:0.3.0` | `:retriever` + `com.jakewharton.timber:timber:5.0.1` | `RetrieverTree` |

产物 Kotlin 语言版本 2.0（POM 里的 kotlin-stdlib = 2.0.21），宿主 Kotlin ≥ 2.0 即可。发布渠道：自托管 Maven 仓库 `https://maven.revdog.org/releases`（ADR 0006，与 revenue-dog 共用）+ 公开只读源码镜像 `githubYiheng/retriever-android`；门禁与发布脚本见文末「发布」。

## 接入

```kotlin
// Application.onCreate() 第一行（在任何日志之前）
Retriever.configure(this, key = "lk_live_<app>_…", options = Options().apply { uploadLevel = LogLevel.WARN })
Retriever.setUser(currentUserId)                        // 登录 / 登出时再调；null = 未登录
Retriever.log(LogLevel.ERROR, "purchase failed", tag = "billing", attrs = mapOf("code" to 7), error = e)
```

Java：`Retriever.configure(ctx, key)`、`Retriever.log(LogLevel.ERROR, "msg", "tag", attrs, e)`、`Retriever.flush(r -> …)`（`@JvmStatic` / `@JvmOverloads`）。
公开入口绝不向宿主抛异常（0.3.0 起参数都可空）：key 为 null = 空串（只写本地）、baseUrl / options 为 null = 默认、log 的 level 为 null =
丢弃并计数（见下面 `rtv.pre_init_dropped`）、回调为 null = 不回调；`Options` 的引用类型 setter 传 null = 默认值。

- 「上报问题」：`Retriever.flush { r -> … }`（回调在 SDK 后台线程上，**必回**，回调抛什么都被吞掉）。向当前段追加合成行（`error`、`tag: "rtv.flush"`、
  `synthetic: true`，无视上传级别一定上传），立即封段并排空；该批 15 s 内被服务端确认 → `FlushResult.Stored`，否则
  `Pending("offline" | "backoff" | "paused" | "timeout" | "disabled")`（未 configure = `paused`；`setEnabled(false)` 或等待中被禁用 = `disabled`）。
  `flush(includeContext = false)` 时该批不带上下文。已有一个 flush 在等待、且它之后没有新的义务行时，新调用挂到同一个等待上（不追加标记行、
  不换段，各自的回调拿同一个结果）——**不要在 flush 回调里自旋重试**（Pending 就再 flush 只会白占资源）。
- 用户撤回同意：`Retriever.setEnabled(false)`；清空本地：`Retriever.purgeLocal()` / `Retriever.purgeLocal { … }`；当前状态：`Retriever.isEnabled`；
  客服短码：`Retriever.supportCode`。两者的语义见下面「同意与清空」。
- 生效级别（远程配置钳制后）：`Retriever.uploadLevel` / `Retriever.localLevel`，适配器用来早过滤。
- `attrs` 值只认 String / Number / Boolean / null；数组、Collection、Map 渲染成紧凑 JSON 文本作字符串值（`[1,2,3]`，有上限）；其它 `toString()`。
  ≤ 32 键（取 map 迭代顺序的前 32 个、再按键排序）、≤ 4 KB，超出由 SDK 截断并标 `truncated`。单个值求值出错（`toString` / `toDouble` 抛异常、
  循环引用、遍历时被并发修改）只影响那个值：写占位串 `"<unprintable>"` 并标 `truncated`，行照常落盘；异常的 `getMessage()` 抛异常同理。
  整数型（Byte / Short / Int / Long / AtomicInteger / AtomicLong / BigInteger / 无小数位的 BigDecimal）绝对值 ≤ 2^53 − 1 输出 JSON 数字，
  超出的（雪花 id、订单号）输出十进制字符串——查看台与 `rtv` 用 `JSON.parse`，更大的数字会被改写（ADR 0020）；其余 Number 按 Double。

## configure 之前的日志（0.3.0 起，ADR 0023）

- **不丢、按本次 configure 判定**：`configure` 之前 SDK 没有实例，`log()` 只把行（不含 seq / oseq）一次 write 追加到
  `noBackupFilesDir/retriever/pre/<uuid>.jsonl`（本进程一个文件，持 flock）。`configure` 时同步建实例，引擎线程上把这些行按文件顺序
  **收编**进本次会话：按这次 configure 的上传级别 / 本地级别（叠加远程明确下发的覆盖）判义务、分 seq / oseq，error 去抖、fatal 换段、
  用户边界都照常生效；行的 `ts` 保持写入时刻。不论 `configure` 早晚，判定结果一样——宿主不必为了它调整初始化顺序。
- **会过 redact**：configure 之前的行在收编时经过 `Options.redact`（返回 null / 抛异常 = 丢弃该行）。所以 **redact 可能在 SDK 线程上被调，
  必须线程安全、要快**；它改了 `ts` 也会被取回原值。SDK 自己的合成行（`rtv.*`）不经 redact。
- **configure 之前其它入口**：`setUser` 记下来并在 pre 文件里留用户切换记录（收编时用户边界正确）；`setEnabled` 立即落盘 / 删除标记；
  `flush` 回 `Pending("paused")`；`purgeLocal` 删 pre 文件并清 root；`installId` / `supportCode` = null，`uploadLevel` / `localLevel` = warn / debug
  （适配器早过滤按全收）；fatal 只写行（不排后台作业）。configure 之前不驱逐、不联网、不排作业、不恢复旧会话。
- **上限 1 MB**：本进程 pre 文件写到 1 MB（1048576 字节）后不再写；超出的、写失败的、拿不到 context 的进程里的行**计数**，
  有会话之后以合成 warn `rtv.pre_init_dropped` 上报（见下）。计数只在内存：进程在那之前死掉，计数随之丢失。
- **configure 之前进程就死**（例如 DI 构造期崩溃循环）：pre 文件留在本地，等之后某次启动走到 `configure` 时作为独立会话收编并上传
  （没有前后台记录，不合成 `rtv.unclean_exit`），不看年龄；只有收编不了的孤儿（别的进程名下的会话认领了它、读不出）满 7 天才在驱逐时删除。
  pre 文件计入本地总量上限。**从不 configure 的宿主**：每个进程建自己的 pre 文件之前，已死进程留下的 pre 文件总量超过 4 MB 或超过 8 个时
  从最旧的删起（这些行从未判定过上传义务，不计数）。
  SDK 在 `configure` 之前不知道 key，任何设计都传不出去——所以 **`configure` 仍然越早越好**，只是不再影响正确性。
- 默认进程里库自带的 `RetrieverInitProvider`（`initOrder = 1000`）在 `Application.onCreate` 之前记下 context、注册进程级前后台 tracker（都不做 I/O）。
  非默认进程、或宿主用 `tools:node="remove"` 去掉了 provider 时，configure 之前没有 context 可写：行只计数——这类进程请在 `Application.onCreate`
  第一行 `configure`。
- **实例身份只认首次 `configure`**：`Options.processName` 之后再改被忽略（其余参数照常生效），并留合成 warn `rtv.reconfigure_ignored`
  （`attrs.field = "process_name"`）；参数完全相同的重复 `configure` 只更新 redact、不发请求。再次 `configure` 改的级别在调用线程上同步生效。

## 合成行（SDK 自己写的 warn，`synthetic: true`，不经 redact）

| tag | 何时 | attrs |
|---|---|---|
| `rtv.pre_init_dropped` | 有没落盘的行（configure 之前超 1 MB / 写失败 / 没有 context、没有会话时的 log、level 为 null、内部异常），一旦有可写会话就写一条并清零 | `count`、`error_count`（error 及以上）、`first_ts`、`last_ts` |
| `rtv.root_vanished` | 运行中会话目录 / root 被删（宿主自行清目录、别的进程清空），重新建会话后写一条；期间的行计入上一条 | — |
| `rtv.reconfigure_ignored` | 首次之后的 `configure` 改了 `processName` | `field`: `process_name` |
| `rtv.install_repaired` / `rtv.install_reset` | `install.json` 损坏（0.2.0 起） | 见 CHANGELOG 0.2.0 |

## 同意与清空（0.2.0 起的语义，ADR 0019 / 0020；0.3.0 补充）

- **`setEnabled(false)` 跨重启有效**：落盘为 root 同级的空标记文件 `noBackupFilesDir/retriever.disabled`，直到 `setEnabled(true)`。
  禁用 = 不写（不占 seq、不计到达率）、不传、不拉配置、不排后台作业，并取消已排的 JobScheduler 作业；调用时正在途的那一个请求不打断
  （数据是在同意期内采集的）。调用立即返回：写入立即停，标记在 SDK 后台线程上写；标记写失败（磁盘满）时内存照样禁用、调度器定时重试——
  在写成之前进程就死，下次启动会是启用的，所以**已撤回同意的宿主请在每次启动时、`configure` 之前调一次 `setEnabled(false)`**（幂等）。
  `configure` 之前的调用当场落盘 / 删除标记，并作为建实例时的初值。只把它当「临时暂停、重启自动恢复」用的宿主：现在会一直禁用。
  标记**判定不了**（目录列不出、I/O 错误、首次解锁前）时按禁用处理（不写、不传），之后每次建会话成功、每次定时唤醒重判（ADR 0024 决定 6）。
- **撤回同意的完整组合** = `setEnabled(false)` + `purgeLocal()` + `setUser(null)`（`purgeLocal` 不清用户）。
- **`setEnabled(true)`**：删标记，然后排空出站箱、拉配置；标记删不掉则保持禁用（宁可不传）。
- **`purgeLocal()` 不阻塞**：调用线程上只取消在途请求，删除与重建在 SDK 后台线程上做——返回时清空尚未完成，`installId` 还是旧值；
  要在完成时做事用 `purgeLocal(callback: Runnable)`（回调在 SDK 后台线程上，此时 `installId` 已是新值）。回调之前写的行可能随清空一起删除。
  清空 = root 先整个改名为同级的 `retriever.purge-<uuid>` 再删，中途被杀也不会留下半个 root；启动时清掉残留。清空不改变启用状态
  （标记在 root 外面，「撤回 = `setEnabled(false)` + `purgeLocal()`」不会被自己撤销），禁用状态下清空之后不拉配置。
- **多进程**：标记在所有进程之间共享——别的进程的上传 / 拉配置 / 排作业在它下一次决策时停，但它的**写入要到它自己调用 `setEnabled(false)`
  或重启才停**。`purgeLocal` 只保证调用进程：别的进程内存里的 install 与已打开的段文件不变（写进已被删除的旧文件里的行随之丢失），
  它们之后物化的批以各自信封里的 install_id 上报、不会被服务端隔离；它们下次启动才换到新 install。需要所有进程一起清空时，
  在每个进程里各调一次，或清空后重启这些进程。

## 宿主必须知道的纪律

- **目录**：`context.noBackupFilesDir/retriever/`（默认不进 Auto Backup，不用改宿主的备份规则）。不要自行清理；SDK 按容量（默认 20 MB，远程可调 2–100 MB）与 7 天驱逐。
- **`log()` 落盘即返回**：每行一次 `FileOutputStream.write`（常开、`append = true`、不套 Buffered），进程被杀 / 崩溃不丢（真杀进程测试守着）。
  可从任意线程同步调用，SDK 的任何入口都不在宿主线程上等 SDK 自己的后台线程。写失败（磁盘满等）时该行记 `write_failed` 墓碑、
  关掉写句柄 1 s 后再试，期间的行只计数不碰磁盘。
  `redact` 钩子在落盘前同步执行，钩子里调 `log()` 会被忽略；钩子抛异常则该行丢弃（宁丢不漏 PII）。
- **StrictMode**：宿主线程上碰磁盘处 SDK 自己放行磁盘读写，API 26+ 另放行 unbuffered IO（`ThreadPolicy.detectAll()` 在 targetSdk ≥ 26 时
  包含它，逐行一次小 write 恰好命中；只包 `allowThreadDiskWrites()` 不够）；SDK 自己的上传 / 拉配置请求打 `TrafficStats` 线程标签
  （`VmPolicy.detectAll()` 同条件下包含 untagged sockets）。`ThreadPolicy` / `VmPolicy` 都 `detectAll().penaltyDeath()` 的验收在示例 app 的
  `strictmode` 场景（模拟器）。
- **`configure` 之前的日志**：见上面「configure 之前的日志」。不想要 init provider：宿主 manifest 里 `tools:node="remove"`，并保证 `configure` 在第一条日志之前。
- **单元测试**：JVM / Robolectric 单测的宿主进程会跑宿主的 `Application.onCreate`（含 `configure`）——测试里不要注入真 key，否则单测会往线上传日志。
- **开发机切换环境**（staging ↔ 生产、换成另一个 app 的 key）：切换前先 `purgeLocal()` 或卸载重装。换 key 时 SDK 会清掉旧 key 的鉴权暂停 / 退避、
  重发映射、按新身份重拉配置，但出站箱里已有的批照常用当前 key 发（同 app 轮换 key 的正路）。
- **多进程**：每个进程各自的会话目录 `proc-<name>/`（`Options.processName = null` 时自动：主进程 `main`，其它取进程名 `:` 后缀）；
  `install.json` 与出站箱共享，只有拿到 `upload.lock`（`FileChannel.tryLock`）的进程上传。非默认进程没有 init provider，请在该进程
  `Application.onCreate` 第一行 `configure`。`processName` 只认本进程首次 `configure` 的值。
- **后台兜底**：进后台时封段（段内有义务行）并就地排空；出站箱还有待传批就排一个一次性 **JobScheduler** 作业
  （`NETWORK_TYPE_ANY`、`setPersisted(true)`——宿主移除了 `RECEIVE_BOOT_COMPLETED` 时退回非持久作业；id = `Options.jobId`，默认 `0x5254`）；`fatal` 同样排作业。
  SDK 只认自己的作业：同 id 已有宿主（或 WorkManager）的作业时不替换、`setEnabled(false)` 也不取消它（只是排不上兜底作业）——用 WorkManager 的宿主请把
  `jobId` 挪出它的 id 区间。
  作业在系统给的时机拉起进程（Doze 期间按维护窗口），里面排空出站箱后 `jobFinished`；系统停止作业只挡当次作业的排空。禁用时不排作业。
  库 manifest 合并进 `INTERNET`、`ACCESS_NETWORK_STATE`、`RECEIVE_BOOT_COMPLETED`（`setPersisted` 的前提）与 `RetrieverUploadJobService`（`BIND_JOB_SERVICE`）。
- **不挂崩溃处理器**：未捕获异常由下次启动的恢复流程发现（前台死亡 → 合成 `rtv.unclean_exit` 带上下文补传）。
  宿主自有处理器可在其中调 `Retriever.log(LogLevel.FATAL, …, error = t)`（**任意线程都可以调**）：**不阻塞**——调用线程上只写行、换段并直接排作业，
  封段物化在 SDK 后台线程上做；进程随后死亡的话，下次启动的恢复会把这一行物化成同一个 batch_id 的批。`RetrieverLog.wtf` / Timber
  ASSERT 同样映射到 fatal（`RetrieverLog` 先写 Retriever、再调 `Log.wtf`，后者可能直接终止进程）。
  **fatal 只能在 Java / Kotlin 的异常处理器或普通代码里调，不能在 native signal handler 里调**（会分配内存、拿锁、做文件 I/O）。
  fatal 有节流：10 s 窗口内第一条立即换段并排作业，之后 10 s 内的 fatal 照常逐行落盘、并入 error 的去抖封段（把 `RetrieverLog.wtf` / Timber
  ASSERT 当普通断言高频调用也不会每条一个批）。
- **网络**：SDK 用自己的 `HttpURLConnection`（不经宿主 OkHttp / 拦截器），不缓存、超时 30 s、不跟随重定向；
  不做可达性预检（`registerDefaultNetworkCallback` 只用来提前唤醒）；任何上传（含 full_dump 的 backfill）都不看网络类型（ADR 0009）。
- **gzip**：请求体是单成员标准 gzip（`GZIPOutputStream`），无尾随字节；文件字节即请求体，重试原样重发。
- **R8**：aar 自带 consumer 规则（公开 API、JobService、init provider），宿主不用抄规则。

## `android.util.Log` 项目怎么接

`RetrieverLog` 与 `android.util.Log` 同名同参（含 `w(tag, tr)` / `wtf(tag, tr)`，返回值同 `Log`）：把 `Log.` 换成 `RetrieverLog.`，
每条同时写 logcat 与 Retriever。级别 v / d → debug、i → info、w → warn、e → error、wtf → fatal；tag 原样；Throwable 进 `exc`。

```kotlin
import org.revdog.retriever.RetrieverLog as Log   // 或逐处替换
Log.e("billing", "purchase failed", e)
```

## Timber

```kotlin
Timber.plant(RetrieverTree())      // 已有 DebugTree 等照常并存
Timber.tag("billing").e(e, "purchase failed %s", sku)
```

级别 VERBOSE / DEBUG → debug、INFO → info、WARN → warn、ERROR → error、ASSERT → fatal；tag = Timber 的 tag；
`isLoggable` 用 `Retriever.localLevel` 早过滤（低于本地级别的行连格式化都不做）；Timber 拼在 message 尾部的栈被剥掉，
异常进 `exc`（type = 类名、stack = `printStackTrace` 文本）；只有异常没有消息时 msg = `t.toString()`。

## 示例 app（`example/`）

```bash
cd sdk/android
./example/gen-local-properties.sh      # 从仓库根 .env 的 EXAMPLE_KEY_STAGING 生成 retriever.local.properties（gitignored，不打印 key）
./gradlew :example:installDebug        # 装到 adb 连着的设备
adb shell am start -n org.revdog.retriever.example/.MainActivity --es scenario error   # error | user | bulk | crash | flush | strictmode
# configure 之前的场景（0.3.0）两步：先记一次性标记并退出进程，再冷启动跑
adb shell am start -n org.revdog.retriever.example/.MainActivity --es scenario preconfigure   # preconfigure | preconfigure_kill | preconfigure_strict | late_configure
adb shell am start -n org.revdog.retriever.example/.MainActivity
```

没有 `retriever.local.properties` 时 key 为空：只写本地不上传。baseURL 默认 `https://logs-staging.revdog.org`（staging 只收 `lk_test_` key）。
界面显示 installId / supportCode / 出站箱待传数 / 生效级别；按钮演示 `Retriever.log`、Timber、`RetrieverLog` 三种写法，以及 flush、setUser、
崩溃恢复（`throw RuntimeException`）、5000 行压测。场景与 iOS 示例 `ScenarioRunner` 逐字对应，末尾记 warn `scenario <name> done`；
`strictmode` 只有 Android 有：`ThreadPolicy`（主线程）与 `VmPolicy` 都 `detectAll().penaltyDeath()` 之后在主线程上跑 error 场景的内容，
随后的封段与上传在 SDK 线程上发生——任何违规进程即死。`preconfigure`：`Application.onCreate` 里先各级别 log、再 configure，末尾 warn
`scenario preconfigure done`；`preconfigure_kill`：先 log、configure 之前自杀，下次（正常）启动后作为独立会话上传；`preconfigure_strict`（只有 Android）：先开 StrictMode `detectAll().penaltyDeath()` 再走 configure 之前的入口与 configure，进程不死即过；`late_configure`（只有 Android）：
Activity 已在前台之后（`onResume`）才 configure，验证前后台状态从进程一开始就对。
`./gradlew :example:assembleRelease` 走 R8（本 app 没有任何 keep），顺带验证 consumer 规则。

## 协议备注（与服务端 / 另两端对齐）

- `batch_id`：primary = UUIDv5(ns, `<install>:<session>:primary:<oseq_from>`)；backfill 每段一批 = `…:backfill:<seg_no>`；
  单段超 768 KB 按 seq 切多批时 = UUIDv5(ns, `<install>:<session>:backfill:<seg_no>:<seq_from>`)；
  413 切分出的半批 = UUIDv5(ns, `<install>:<session>:primary:<oseq_from>:<oseq_to>`)（install 取原批信封，全部半批写成才删原批）。
- 请求头 `X-Rtv-Install` 取**该批信封里**的 install_id；映射只在响应 `status = stored` 且批属于当前 install 时记为已确认。
- backfill 回传 RETAINED 段里的全部非义务行，可能与已作为 ctx 上传的行重复，读侧按 `(session_id, seq)` 去重。
- 会话终态只为有义务行（`last_oseq > 0`）的会话写；每批带最旧的未在途 20 条终态 / 100 条墓碑，不合并、不截断，带不完留给下一批；
  不再发 `closed_sessions_dropped`。`drops.jsonl` / `sessions.jsonl` 各自上限 1000 条（墓碑先无损合并，仍超出删最旧的未在途条目）。
- 本地状态文件比方案 §3.2 多三处（同 iOS）：根目录 `config.json`、`cursor.json.closed_ms`、`backoff.json.last_ack_ms`。
  0.2.0 起 `meta.json` 多一个可选键 `install_id`（install 身份的冗余副本：`install.json` 损坏时据此修复、不换 id，留 `rtv.install_repaired`；
  找不到副本才清空重建，留 `rtv.install_reset`）；root 同级多两种文件 `retriever.disabled`、`retriever.purge-*`。0.1.x 的盘上状态全部照读。
- 远程配置请求头另带 `X-Rtv-Local-Cap-Bytes`（宿主 `localCapBytes`）。`device.sdk` / `X-Rtv-Sdk` = `retriever-android/<ver>`。
  配置属于请求时的身份（install + user + 四项宿主默认 + key 指纹 + baseUrl）：任何一项变了就按新身份重拉，旧身份的放大型配置立即回落，
  旧身份的响应到得晚也不生效。0.3.0 起缓存只记远程明确给的值：响应的 `from_host` 列出的宿主型字段永远取**当前**宿主默认（ADR 0022）。
- 0.3.0 起的盘上状态（全部是可选键 / 新文件，旧版本写的状态照读）：`pre/<uuid>.jsonl`；`meta.json` 可选键 `pre`；`config.json` 加 `from_host`、
  `key_fp`、`base_url`；`backoff.json` / `mapping.json` 加 `key_fp`、`base_url`（key 指纹 = sha256(key) 前 16 位十六进制）。
  旧文件没有指纹键 = 视为当前 key（升级不清退避、不重发映射、不丢配置缓存），读到后补写。

## 与 iOS 的平台差异

| | iOS | Android |
|---|---|---|
| 目录 / 备份 | Application Support + isExcludedFromBackup + 保护类别 | `noBackupFilesDir`（默认不备份） |
| 进后台 | `beginBackgroundTask` 包住排空（过期必 end） | 就地排空 25 s 预算 + 一次性 JobScheduler 作业兜底；系统停作业 → 取消在途、不删批 |
| fatal | 只落盘，封段物化异步、不等待 | 同左 + 在调用线程上排作业 |
| 禁用标记 | root 同级 `<root>.disabled` | 同左（`noBackupFilesDir/retriever.disabled`）；另取消已排的作业 |
| 会话目录锁 | 目录 fd 上 flock | `meta.json` 上的 `FileChannel` 锁 |
| 根级读改写锁 | 根目录 fd 上 flock | `upload.lock` 的第 2 个字节区间（上传锁是第 1 个字节区间，同一个常开 channel） |
| 单调时钟 | `CLOCK_MONOTONIC` | `SystemClock.elapsedRealtime()`（含深睡眠） |
| configure 前的日志 | 写默认 root 的 pre 文件，configure 时收编 | 同左；默认进程靠 init provider 拿 context，其它进程 configure 之前只计数 |
| 前后台初值 | 首次触达时安装 tracker（非主线程触达 = unknown） | init provider 里注册进程级 tracker（按 Activity 身份计数）；没有 provider 时实例创建时注册、初值取进程重要性 |

## 开发

```bash
./gradlew :retriever:testDebugUnitTest :retriever-timber:testDebugUnitTest   # JVM 单测（需仓库根 npm install：跨语言信封校验跑 npx tsx）
./gradlew :retriever:assembleRelease :retriever-timber:assembleRelease :example:assembleDebug
./gradlew lint
./gradlew :retriever:publishReleasePublicationToStagingRepository            # 发到 build/maven-staging（不联网）
```

单测覆盖：configure 之前的 pre 文件与收编（静态入口层面；含真杀进程：configure 之前被杀、收编中途被杀、别的活进程的 pre 文件）、
`from_host`（回显模式假服务端、golden 回放）、不抛不崩、没落盘的行计数、旧版本原地升级（0.2.0 / 0.1.x 布局）、换 key、fatal / flush 节流、
golden 向量（ids 含 413 半批 / client_day / config clamp / from_host / 整数 attrs）、`validate-envelope.ts` 跨语言校验（原始信封字节）、
gzip（`GZIPInputStream` + `gunzip -t`）、队列状态机、段与物化、驱逐与 jsonl 上限、配置（含身份变化重拉）、生命周期与 JobScheduler 调度、
多进程（含共享禁用标记、清空后的请求头）、写入纪律、UTF-8 截断、install 身份修复 / 清空、宿主线程不等待（fatal / purge / shutdown）、
启用状态落盘、**真杀进程**（子 JVM 写 5000 行后 `Runtime.halt(137)`，父进程恢复；另测残行、fatal 后立即杀）、`log()` 1 万次 p99。

## 发布

monorepo 是唯一开发源；公开仓库 `githubYiheng/retriever-android` 只读（`git subtree split --prefix=sdk/android` 推送，改动一律回 monorepo）。
两个脚本都在仓库根、默认 dry-run，`--apply` 才真推 / 真传。**顺序固定：先 `sdk-android-release.sh --apply`，再 `sdk-android-maven-publish.sh --apply`**
（后者的门禁 3 要求公开仓库已有 tag 且树 == `HEAD:sdk/android`，保证 Maven 上的制品与 GitHub 上的源码同源）。

```bash
scripts/sdk-android-release.sh 0.3.0                # 八道门禁 + subtree split + git push --dry-run
scripts/sdk-android-release.sh 0.3.0 --apply        # 推 retriever-android main + tag v0.3.0
scripts/sdk-android-maven-publish.sh 0.3.0          # 六道门禁 + Gradle 发到 staging + 列出将上传的对象
scripts/sdk-android-maven-publish.sh 0.3.0 --apply  # wrangler 传 R2 `revdog-maven` + 回读校验
```

- 源码发布八道门禁：CHANGELOG 有 `## [X.Y.Z]`；工作区干净；两个模块 JVM 单测；`scripts/api-check.sh`（metalava 基线）；
  `RetrieverVersion.CURRENT` == `VERSION_NAME` == 参数（且 `Options.sdkVersion` 默认引用它）；tag 不存在；远端 main fast-forward；
  `scripts/r8-check.sh`（`SKIP_R8=1` 可跳，跳过 ≠ 通过）。另查 split 树无 `build/`、`.gradle/`、`local.properties` / `*.local.properties`、keystore、真 key。
- 制品发布六道门禁：版本 == `VERSION_NAME`；工作区干净；tag 树 == `HEAD:sdk/android`；两个 artifact 的裸 pom 远端都 404（版本不可变，不覆盖）；
  分别拉回两份 `maven-metadata.xml`；staging（`build/maven-staging`）两个 artifact 的 aar / sources / pom / module 与四种校验和齐全，
  且 `retriever-timber` 的 pom 依赖同版本 `org.revdog:retriever`。上传顺序：`retriever` 版本目录（裸 pom 最后）→ 其 metadata → `retriever-timber` 同理。
  凭据只从仓库根 `.env` 读（Cloudflare token），Gradle 侧零凭据。

门禁脚本（在 `sdk/android` 下，也可单独跑）：

```bash
./scripts/api-dump.sh     # 改了公开面后重新生成 retriever/api/retriever.api、retriever-timber/api/retriever-timber.api，review diff 再提交
./scripts/api-check.sh    # 基线与代码不一致即失败（破坏性变更必须升主版本）
./scripts/r8-check.sh     # :example:assembleRelease 后断言：consumer 规则进了 R8 配置、点名的公开类原名保留、关键入口在 seeds、dex 内含
```

宿主依赖（Maven 发布之后可用；之前只能走源码依赖）：

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
maven { url = uri("https://maven.revdog.org/releases"); content { includeGroup("org.revdog") } }
// 模块
implementation("org.revdog:retriever:0.3.0")
implementation("org.revdog:retriever-timber:0.3.0")   // 可选，宿主已用 Timber 时
```

升级规则：修订号 = 只修 bug；次版本 = 公开 API 只增；主版本 = 公开 API 有减或改，看 CHANGELOG 迁移说明。

## Google Play Data safety 申报指引

表单由宿主开发者负责填写，下面是 Retriever 这一部分该怎么报（定义取自 Play 官方 [Data safety 说明](https://support.google.com/googleplay/android-developer/answer/10787469)）。

- 「收集」= 数据从设备传出，**包括 app 内 SDK 传出的**；「共享」= 转给第三方，交给代开发者处理数据的「服务提供方」不算共享。
  Retriever 后端由开发者自己运营，所以**共享：否**；第三方 app 接入时 Retriever 属于服务提供方，同样不算共享。

| Play 数据类型 | SDK 里的来源 | 收集 | 共享 | 用途 |
|---|---|---|---|---|
| 设备或其他 ID | `install_id`（随机 UUID，随 app 数据容器；官方示例就有 "Firebase installation ID"） | 是 | 否 | Analytics |
| 用户 ID | `setUser` 的值 | 调了才有 | 否 | Analytics（用于客服按人查询时加选 App functionality） |
| 崩溃日志 | fatal 行、`exc.stack`、`rtv.unclean_exit` | 是 | 否 | Analytics |
| 诊断 | 日志行、机型 / OS / locale / app 版本、墓碑 | 是 | 否 | Analytics |
| 其他（宿主判断） | `msg` / `attrs` 里写的内容：页面点击 → App interactions；搜索词 → In-app search history；自由文本 → Other user-generated content | 视宿主 | 否 | 同上 |

- **用途选 Analytics**：官方定义含 "to monitor app health, to diagnose and fix bugs or crashes"。
- **本地内容按「可能被收集」申报**：没上传的行不算收集，但 error 附带的上下文与远程按需全量（full_dump）能带走任何一行本地日志，所以保守申报。
- **可选 / 必需**：宿主用 `setEnabled` 做了同意开关的，可申报「可选」（用户能控制收集）；否则申报「必需」。
- **传输加密：可勾**。默认 `https://logs.revdog.org`，且 Android 9（API 28）起默认禁明文；宿主自己配 `http://` 的 baseUrl 就不能勾。
- **可请求删除：有条件地勾**。服务端 30 天自动删除，`rtv user purge` / 查看台能按 user / install 清除；宿主要对外提供申请渠道
  （例如让用户报 `supportCode`）才能勾。
- **不适用「临时处理」豁免**：数据保留 30 天。

## 脱敏（宿主建议）

日志在落盘前经过 `Options.redact: (LogLine) -> LogLine?`（返回 null = 丢弃该行；configure 之前的行在收编时经过它，可能在 SDK 线程上被调，须线程安全）。宿主自己的 URL、交易号、用户标识往往会出现在第三方 SDK 的错误文本里，建议在这里统一掩码，例如把 `/v1/subscribers/<id>` 与 `tx=<id>` 替换成 `<masked>`。Retriever 服务端不做二次脱敏，落盘的就是上传的。
