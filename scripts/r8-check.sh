#!/usr/bin/env bash
# consumer ProGuard / R8 门禁。结构照 revenue-dog `sdk/android/scripts/r8-check.sh`，改为本 SDK 口径。
#
#   scripts/r8-check.sh                # 跑 :example:assembleRelease 再断言
#   SKIP_BUILD=1 scripts/r8-check.sh   # 直接断言上一次的产物
#
# 为什么需要它：aar 带出去的 `retriever/consumer-rules.pro`、`retriever-timber/consumer-rules.pro` 是
# **宿主 release 构建**才生效的东西，单测一条都覆盖不到。规则漏了的症状是「debug 一切正常，
# 商店版本里 JobScheduler / init provider 按类名实例化时 ClassNotFoundException，或宿主调公开 API 时 NoSuchMethodError」。
# 这个脚本把「规则有没有生效」变成四份可 diff 的产物上的断言：
#
#   configuration.txt —— R8 实际吃进去的全部规则（必须含两个 aar 带来的每一条 -keep*）
#   seeds.txt         —— 被 keep 命中的符号（公开入口、manifest 组件的无参构造）
#   usage.txt         —— 被删掉的符号（consumer 规则点名的公开类一个都不许整类出现）
#   mapping.txt       —— 改名表（consumer 规则点名的公开类必须原名 → 原名）
#
# 判定对象只有 consumer 规则**点名**的类（脚本从两个 .pro 里解析，不手抄）。
# 内部实现 `org.revdog.retriever.core.*` / `org.revdog.retriever.android.*` 允许被删、被改名（设计如此），不断言。
#
# `:example` 的 `proguard-rules.pro` 里**没有任何 keep**（下面第 1 步先断言这一点），
# 所以过关只可能是 SDK 自己的规则在起作用。
set -u

cd "$(dirname "$0")/.." || exit 1

MAPPING_DIR="example/build/outputs/mapping/release"
APK="example/build/outputs/apk/release/example-release-unsigned.apk"
RULE_FILES=(retriever/consumer-rules.pro retriever-timber/consumer-rules.pro)
EXAMPLE_RULES="example/proguard-rules.pro"
exit_code=0

fail() { echo -e "❌ $*" >&2; exit_code=1; }
ok() { echo "✅ $*"; }

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    echo "==> ./gradlew :example:assembleRelease（isMinifyEnabled = true）"
    ./gradlew :example:assembleRelease --console=plain -q || { echo "❌ assembleRelease 失败" >&2; exit 1; }
fi

for f in configuration.txt seeds.txt usage.txt mapping.txt; do
    [[ -s "$MAPPING_DIR/$f" ]] || fail "缺少 R8 产物 $MAPPING_DIR/${f}（example/proguard-rules.pro 的 -print* 指令没生效？）"
done
[[ -s "$APK" ]] || fail "缺少 $APK"
for f in "${RULE_FILES[@]}"; do
    [[ -s "$f" ]] || fail "缺少 consumer 规则文件 $f"
done
[[ $exit_code -eq 0 ]] || exit $exit_code

# 规则文件里的一行：去掉 CR 与行尾空白。
rules_of() { sed -e 's/\r$//' -e 's/[[:space:]]*$//' "$1"; }

# ProGuard 类名通配 → 锚定的 ERE：`**` 任意（含包分隔符）、`*` 不跨 `.`、`?` 单个非 `.` 字符。
glob_to_ere() {
    local s="$1"
    s="${s//./\\.}"
    s="${s//\$/\\\$}"
    s="${s//\*\*/@@}"
    s="${s//\*/[^.]*}"
    s="${s//@@/.*}"
    s="${s//\?/[^.]}"
    printf '^%s$' "$s"
}

echo
echo "==> 1/4 两个 aar 的 consumer 规则进了 R8 配置"
if grep -qE '^[[:space:]]*-keep' "$EXAMPLE_RULES"; then
    fail "$EXAMPLE_RULES 里出现了 -keep —— 本门禁的前提是示例 app 零 keep，否则过关的可能是示例的规则而不是 SDK 的"
else
    ok "$EXAMPLE_RULES 零 -keep（前提成立）"
fi
rule_count=0
for rf in "${RULE_FILES[@]}"; do
    while IFS= read -r rule; do
        rule_count=$((rule_count + 1))
        if grep -qF -- "$rule" "$MAPPING_DIR/configuration.txt"; then
            ok "规则已生效（${rf}）：$rule"
        else
            fail "consumer 规则没进 R8 配置（${rf}）：$rule"
        fi
    done < <(rules_of "$rf" | grep -E '^-keep')
done
[[ $rule_count -gt 0 ]] || fail "两个 consumer-rules.pro 里一条 -keep 都没读到"

echo
echo "==> 2/4 consumer 规则点名的公开类没被删、没被改名（堆栈要能直接读，manifest 要能按类名实例化）"
# 只取 `-keep`（可带非 allow* 的修饰）的类名；-keepclassmembers / -keepnames / -keepattributes 不代表「整类保住」。
PUBLIC_CLASSES=()
for rf in "${RULE_FILES[@]}"; do
    while IFS= read -r cls; do PUBLIC_CLASSES+=("$cls"); done < <(
        rules_of "$rf" \
            | grep -E '^-keep(,[a-z,]+)?[[:space:]]' | grep -vE '^-keep,[a-z,]*allow' \
            | sed -nE 's/^-keep[^[:space:]]*[[:space:]]+(.*[[:space:]])?(class|interface|enum)[[:space:]]+([^[:space:]{]+).*/\3/p'
    )
done
[[ ${#PUBLIC_CLASSES[@]} -gt 0 ]] || fail "没能从 consumer 规则里解析出任何类名"
# mapping.txt 的类行：`原名 -> 新名:`（成员行以空格缩进）。
mapping_classes="$(grep -E '^[^ #].* -> .*:$' "$MAPPING_DIR/mapping.txt" | sed 's/:$//')"
# usage.txt 的约定：`类名:` = 只删了它下面列出的成员；`类名`（无冒号、无缩进）= 整个类被删。
usage_whole="$(grep -E '^[^ ]' "$MAPPING_DIR/usage.txt" | grep -v ':$' || true)"
for cls in "${PUBLIC_CLASSES[@]}"; do
    re="$(glob_to_ere "$cls")"
    removed="$(grep -E "$re" <<<"$usage_whole" || true)"
    if [[ -n "$removed" ]]; then
        fail "整类被 R8 删掉（规则 ${cls}）：\n$removed"
        continue
    fi
    # 正则经环境变量传给 awk：`-v` 会先处理反斜杠转义，把 `\$` 变成行尾锚点。
    kept="$(RE="$re" awk -F' -> ' '$1 ~ ENVIRON["RE"]' <<<"$mapping_classes")"
    if [[ -z "$kept" ]]; then
        fail "mapping.txt 里没有 ${cls}（被删了，或者规则里的类名写错了）"
        continue
    fi
    renamed="$(awk -F' -> ' '$1 != $2' <<<"$kept")"
    if [[ -n "$renamed" ]]; then
        fail "以下公开类被改名（规则 ${cls}）：\n$renamed"
    else
        ok "原名保留：$(awk -F' -> ' '{print $1}' <<<"$kept" | paste -sd ' ' -)"
    fi
done

echo
echo "==> 3/4 关键入口在 seeds 里（真被 keep 住，不是恰好没被删）"
# 签名取自 seeds.txt 实际输出（R8 格式：`类: 返回类型 方法(参数,…)`，构造器 `类: 简单名()`）。
# configure / log / flush 是 @JvmOverloads，Java 宿主会调短重载，所以每个重载都断言。
for seed in \
    'org.revdog.retriever.Retriever: void configure(android.content.Context,java.lang.String)' \
    'org.revdog.retriever.Retriever: void configure(android.content.Context,java.lang.String,java.lang.String)' \
    'org.revdog.retriever.Retriever: void configure(android.content.Context,java.lang.String,java.lang.String,org.revdog.retriever.Options)' \
    'org.revdog.retriever.Retriever: void log(org.revdog.retriever.LogLevel,java.lang.String)' \
    'org.revdog.retriever.Retriever: void log(org.revdog.retriever.LogLevel,java.lang.String,java.lang.String)' \
    'org.revdog.retriever.Retriever: void log(org.revdog.retriever.LogLevel,java.lang.String,java.lang.String,java.util.Map)' \
    'org.revdog.retriever.Retriever: void log(org.revdog.retriever.LogLevel,java.lang.String,java.lang.String,java.util.Map,java.lang.Throwable)' \
    'org.revdog.retriever.Retriever: void flush(org.revdog.retriever.FlushCallback)' \
    'org.revdog.retriever.Retriever: void flush(boolean,org.revdog.retriever.FlushCallback)' \
    'org.revdog.retriever.Retriever: void setUser(java.lang.String)' \
    'org.revdog.retriever.RetrieverUploadJobService: RetrieverUploadJobService()' \
    'org.revdog.retriever.RetrieverInitProvider: RetrieverInitProvider()' \
    'org.revdog.retriever.timber.RetrieverTree' \
    'org.revdog.retriever.timber.RetrieverTree: RetrieverTree()'
do
    grep -qxF -- "$seed" "$MAPPING_DIR/seeds.txt" && ok "keep 命中：$seed" || fail "公开入口没被 keep：$seed"
done

echo
echo "==> 4/4 apk 的 dex 里真的有这些类（产物级复核，不只看 R8 的自述）"
APKANALYZER="${APKANALYZER:-$(command -v apkanalyzer || true)}"
if [[ -z "$APKANALYZER" && -n "${ANDROID_HOME:-}" ]]; then
    APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
fi
if [[ -x "$APKANALYZER" ]]; then
    dex_classes="$("$APKANALYZER" dex packages --defined-only "$APK" 2>/dev/null | awk '$1=="C"{print $NF}')"
    for cls in \
        org.revdog.retriever.Retriever \
        org.revdog.retriever.RetrieverLog \
        org.revdog.retriever.RetrieverUploadJobService \
        org.revdog.retriever.RetrieverInitProvider \
        org.revdog.retriever.timber.RetrieverTree
    do
        grep -qxF "$cls" <<<"$dex_classes" && ok "dex 内含 $cls" || fail "dex 里找不到 $cls"
    done
    echo "   apk：${APK}，$(du -h "$APK" | cut -f1)"
else
    echo "   ⚠️ 找不到 apkanalyzer（设 APKANALYZER 或 ANDROID_HOME），跳过 dex 复核 —— 跳过 ≠ 通过"
fi

echo
if [[ $exit_code -eq 0 ]]; then
    echo "R8 / consumer ProGuard 门禁全过。"
else
    echo "R8 / consumer ProGuard 门禁失败：缺的规则要加进对应模块的 consumer-rules.pro" >&2
    echo "（retriever/consumer-rules.pro 或 retriever-timber/consumer-rules.pro），" >&2
    echo "**不要**加到 example/proguard-rules.pro（那等于把 SDK 的缺陷藏起来，宿主照样会炸）。" >&2
fi
exit $exit_code
