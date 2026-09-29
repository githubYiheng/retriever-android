# Retriever consumer ProGuard 规则（随 aar 分发）。
#
# 只保住公开面与 manifest 引用的组件；内部实现（org.revdog.retriever.core.*、.android.*）由 R8 自由裁剪。
# 注意 Kotlin `internal` 类在字节码里是 public，所以这里逐个点名，不用 `org.revdog.retriever.**` 通配。

# ---- 公开 API（三端同名，方案 §3.10）----
-keep class org.revdog.retriever.Retriever { public *; }
-keep class org.revdog.retriever.RetrieverLog { public *; }
-keep class org.revdog.retriever.RetrieverVersion { public *; }
-keep class org.revdog.retriever.Options { public *; }
-keep class org.revdog.retriever.LogLevel { *; }
-keep class org.revdog.retriever.LogLine { public *; }
-keep class org.revdog.retriever.LogException { public *; }
-keep class org.revdog.retriever.FlushResult { public *; }
-keep class org.revdog.retriever.FlushResult$* { public *; }
-keep interface org.revdog.retriever.FlushCallback { *; }

# ---- manifest 组件（系统按类名实例化）----
# 后台兜底作业（ADR 0003 决定 13：框架 JobScheduler，不引 WorkManager）。
-keep class org.revdog.retriever.RetrieverUploadJobService { public <init>(); public *; }
# 进程启动时拿 Application context，使 configure 之前的 log() 也能落盘。
-keep class org.revdog.retriever.RetrieverInitProvider { public <init>(); public *; }

-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions
