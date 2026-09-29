#!/usr/bin/env bash
# 重新生成公开 API 基线（metalava）。照 revenue-dog `sdk/android/scripts/api-dump.sh`。
#
# 改了公开面之后跑它，然后 **review diff 再提交** —— 基线文件是「我们承诺了什么」的唯一记录：
#   retriever/api/retriever.api、retriever-timber/api/retriever-timber.api
set -u

cd "$(dirname "$0")/.." || exit 1

exit_code=0
./gradlew :retriever:metalavaGenerateSignatureRelease :retriever-timber:metalavaGenerateSignatureRelease \
    --console=plain -q || exit_code=$?
exit $exit_code
