# 示例 app 自己的 R8 规则：故意没有任何 keep —— SDK 需要的 keep 必须全部来自 aar 带出的 consumer-rules.pro。
-printmapping build/outputs/mapping/release/mapping.txt
-printseeds build/outputs/mapping/release/seeds.txt
-printusage build/outputs/mapping/release/usage.txt
