# Retriever Android 传输层（`sdk/android`）

Kotlin，minSdk 24 / compileSdk 36，JVM 17。规格：`docs/plan/system-design.md` §3；不变式：`docs/architecture.md` §2。
行为、字段、顺序、数字与 iOS 实现（`sdk/ios`，已真机验收）逐字一致；只在平台层不同（见文末「与 iOS 的平台差异」）。

| 模块 | 坐标 | 依赖 | 用途 |
|---|---|---|---|
| `retriever` | `org.revdog:retriever:0.1.0` | **只有 kotlin-stdlib**（无 AndroidX、无 coroutines、无 okhttp） | 传输层本体 + `RetrieverLog`（`android.util.Log` 替身） |
| `retriever-timber` | `org.revdog:retriever-timber:0.1.0` | `:retriever` + `com.jakewharton.timber:timber:5.0.1` | `RetrieverTree` |

产物 Kotlin 语言版本 2.0（POM 里的 kotlin-stdlib = 2.0.21），宿主 Kotlin ≥ 2.0 即可。发布渠道：自托管 Maven 仓库 `https://maven.revdog.org/releases`（ADR 0006，与 revenue-dog 共用）+ 公开只读源码镜像 `githubYiheng/retriever-android`；门禁与发布脚本见文末「发布」。

## 接入

```kotlin
// Application.onCreate() 第一行（在任何日志之前）
Retriever.configure(this, key = "lk_live_<app>_…", options = Options().apply { uploadLevel = LogLevel.WARN })
Retriever.setUser(currentUserId)                        // 登录 / 登出时再调；null = 未登录
Retriever.log(LogLevel.ERROR, "purchase failed", tag = "billing", attrs = mapOf("code" to 7), error = e)
```

Java：`Retriever.configure(ctx, key)`、`Retriever.log(LogLevel.ERROR, "msg", "tag", attrs, e)`、`Retriever.flush(r -> …)`（`@JvmStatic` / `@JvmOverloads`）。

- 「上报问题」：`Retriever.flush { r -> … }`（回调在 SDK 后台线程上）。向当前段追加合成行（`error`、`tag: "rtv.flush"`、`synthetic: true`，
  无视上传级别一定上传），立即封段并排空；该批 15 s 内被服务端确认 → `FlushResult.Stored`，否则 `Pending("offline" | "backoff" | "paused" | "timeout")`
  （`setEnabled(false)` 时 `Pending("disabled")`）。`flush(includeContext = false)` 时该批不带上下文。
- 用户撤回同意：`Retriever.setEnabled(false)`（不写不传）；清空本地：`Retriever.purgeLocal()`；客服短码：`Retriever.supportCode`。
- 生效级别（远程配置钳制后）：`Retriever.uploadLevel` / `Retriever.localLevel`，适配器用来早过滤。
- `attrs` 值只认 String / Number / Boolean / null（其它 `toString()`）；≤ 32 键、≤ 4 KB，超出由 SDK 截断并标 `truncated`。

## 宿主必须知道的纪律

- **目录**：`context.noBackupFilesDir/retriever/`（默认不进 Auto Backup，不用改宿主的备份规则）。不要自行清理；SDK 按容量（默认 20 MB，远程可调 2–100 MB）与 7 天驱逐。
- **`log()` 落盘即返回**：每行一次 `FileOutputStream.write`（常开、`append = true`、不套 Buffered），进程被杀 / 崩溃不丢（真杀进程测试守着）。
  可从任意线程同步调用；主线程写入处 SDK 自己包了 `StrictMode.allowThreadDiskWrites()`，开着 StrictMode 也不炸。
  `redact` 钩子在落盘前同步执行，钩子里调 `log()` 会被忽略；钩子抛异常则该行丢弃（宁丢不漏 PII）。
- **`configure` 之前的日志**：默认进程里库自带的 `RetrieverInitProvider` 在 `Application.onCreate` 之前记下 Application context（不做 I/O），
  所以 `configure` 之前的 `log()` 也落盘。不想要这个 provider：宿主 manifest 里 `tools:node="remove"`，并保证 `configure` 在第一条日志之前。
- **多进程**：每个进程各自的会话目录 `proc-<name>/`（`Options.processName = null` 时自动：主进程 `main`，其它取进程名 `:` 后缀）；
  `install.json` 与出站箱共享，只有拿到 `upload.lock`（`FileChannel.tryLock`）的进程上传。非默认进程没有 init provider，请在该进程
  `Application.onCreate` 第一行 `configure`。
- **后台兜底**：进后台时封段（段内有义务行）并就地排空；出站箱还有待传批就排一个一次性 **JobScheduler** 作业
  （`NETWORK_TYPE_ANY`、`setPersisted(true)`、id = `Options.jobId`，默认 `0x5254`，与宿主作业冲突时改它）；`fatal` 同样排作业。
  作业在系统给的时机拉起进程（Doze 期间按维护窗口），里面排空出站箱后 `jobFinished`。库 manifest 合并进
  `INTERNET`、`ACCESS_NETWORK_STATE`、`RECEIVE_BOOT_COMPLETED`（`setPersisted` 的前提）与 `RetrieverUploadJobService`（`BIND_JOB_SERVICE`）。
- **不挂崩溃处理器**：未捕获异常由下次启动的恢复流程发现（前台死亡 → 合成 `rtv.unclean_exit` 带上下文补传）。
  宿主自有处理器可在其中调 `Retriever.log(LogLevel.FATAL, …, error = t)`（同步物化 + 排作业）。
- **网络**：SDK 用自己的 `HttpURLConnection`（不经宿主 OkHttp / 拦截器），不缓存、超时 30 s、不跟随重定向；
  不做可达性预检（`registerDefaultNetworkCallback` 只用来提前唤醒）；full_dump 的 backfill 默认只走非计量网络。
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
异常进 `exc`（type = 类名、stack = `Log.getStackTraceString`）；只有异常没有消息时 msg = `t.toString()`。

## 示例 app（`example/`）

```bash
cd sdk/android
./example/gen-local-properties.sh      # 从仓库根 .env 的 EXAMPLE_KEY_STAGING 生成 retriever.local.properties（gitignored，不打印 key）
./gradlew :example:installDebug        # 装到 adb 连着的设备
adb shell am start -n org.revdog.retriever.example/.MainActivity --es scenario error   # error | user | bulk | crash | flush
```

没有 `retriever.local.properties` 时 key 为空：只写本地不上传。baseURL 默认 `https://logs-staging.revdog.org`（staging 只收 `lk_test_` key）。
界面显示 installId / supportCode / 出站箱待传数 / 生效级别；按钮演示 `Retriever.log`、Timber、`RetrieverLog` 三种写法，以及 flush、setUser、
崩溃恢复（`throw RuntimeException`）、5000 行压测。场景与 iOS 示例 `ScenarioRunner` 逐字对应，末尾记 warn `scenario <name> done`。
`./gradlew :example:assembleRelease` 走 R8（本 app 没有任何 keep），顺带验证 consumer 规则。

## 协议备注（与服务端 / 另两端对齐）

- `batch_id`：primary = UUIDv5(ns, `<install>:<session>:primary:<oseq_from>`)；backfill 每段一批 = `…:backfill:<seg_no>`；
  单段超 768 KB 按 seq 切多批时 = UUIDv5(ns, `<install>:<session>:backfill:<seg_no>:<seq_from>`)。
- backfill 回传 RETAINED 段里的全部非义务行，可能与已作为 ctx 上传的行重复，读侧按 `(session_id, seq)` 去重。
- 本地状态文件比方案 §3.2 多三处（同 iOS）：根目录 `config.json`、`cursor.json.closed_ms`、`backoff.json.last_ack_ms`。
- 远程配置请求头另带 `X-Rtv-Local-Cap-Bytes`（宿主 `localCapBytes`）。`device.sdk` / `X-Rtv-Sdk` = `retriever-android/<ver>`。

## 与 iOS 的平台差异

| | iOS | Android |
|---|---|---|
| 目录 / 备份 | Application Support + isExcludedFromBackup + 保护类别 | `noBackupFilesDir`（默认不备份） |
| 进后台 | `beginBackgroundTask` 包住排空（过期必 end） | 就地排空 25 s 预算 + 一次性 JobScheduler 作业兜底；系统停作业 → 取消在途、不删批 |
| fatal | 同步物化，只落盘 | 同左 + 排作业 |
| 会话目录锁 | 目录 fd 上 flock | `meta.json` 上的 `FileChannel` 锁 |
| 根级读改写锁 | 根目录 fd 上 flock | `upload.lock` 的第 2 个字节区间（上传锁是第 1 个字节区间，同一个常开 channel） |
| 单调时钟 | `CLOCK_MONOTONIC` | `SystemClock.elapsedRealtime()`（含深睡眠） |
| configure 前的日志 | 惰性实例写默认目录 | 默认进程靠 init provider 拿 context；其它进程需先 configure |

## 开发

```bash
./gradlew :retriever:testDebugUnitTest :retriever-timber:testDebugUnitTest   # JVM 单测（需仓库根 npm install：跨语言信封校验跑 npx tsx）
./gradlew :retriever:assembleRelease :retriever-timber:assembleRelease :example:assembleDebug
./gradlew lint
./gradlew :retriever:publishReleasePublicationToStagingRepository            # 发到 build/maven-staging（不联网）
```

单测覆盖：golden 向量（ids / client_day / config clamp）、`validate-envelope.ts` 跨语言校验（原始信封字节）、gzip（`GZIPInputStream` + `gunzip -t`）、
队列状态机、段与物化、驱逐、配置、生命周期与 JobScheduler 调度、多进程、写入纪律、UTF-8 截断、**真杀进程**
（子 JVM 写 5000 行后 `Runtime.halt(137)`，父进程恢复；另测残行）、`log()` 1 万次 p99。

## 发布

monorepo 是唯一开发源；公开仓库 `githubYiheng/retriever-android` 只读（`git subtree split --prefix=sdk/android` 推送，改动一律回 monorepo）。
两个脚本都在仓库根、默认 dry-run，`--apply` 才真推 / 真传。**顺序固定：先 `sdk-android-release.sh --apply`，再 `sdk-android-maven-publish.sh --apply`**
（后者的门禁 3 要求公开仓库已有 tag 且树 == `HEAD:sdk/android`，保证 Maven 上的制品与 GitHub 上的源码同源）。

```bash
scripts/sdk-android-release.sh 0.1.0                # 八道门禁 + subtree split + git push --dry-run
scripts/sdk-android-release.sh 0.1.0 --apply        # 推 retriever-android main + tag v0.1.0
scripts/sdk-android-maven-publish.sh 0.1.0          # 六道门禁 + Gradle 发到 staging + 列出将上传的对象
scripts/sdk-android-maven-publish.sh 0.1.0 --apply  # wrangler 传 R2 `revdog-maven` + 回读校验
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
implementation("org.revdog:retriever:0.1.0")
implementation("org.revdog:retriever-timber:0.1.0")   // 可选，宿主已用 Timber 时
```

升级规则：修订号 = 只修 bug；次版本 = 公开 API 只增；主版本 = 公开 API 有减或改，看 CHANGELOG 迁移说明。
