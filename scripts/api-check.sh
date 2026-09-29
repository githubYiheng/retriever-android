#!/usr/bin/env bash
# 公开 API 基线门禁。照 revenue-dog `sdk/android/scripts/api-check.sh`。
#
# 跑一次 api-dump，然后看基线文件有没有变。有变 = 公开面被改了但基线没更新，
# 门禁失败并打印 diff。破坏性变更必须升主版本（公开面只进不出）。
set -u

cd "$(dirname "$0")/.." || exit 1

exit_code=0

# 只让签名文件参与判定，免得无关的工作区脏文件把门禁搞挂。
api_paths=(':(glob)**/api/*.api')

./scripts/api-dump.sh || exit_code=$?

if ! git diff --quiet -- "${api_paths[@]}"; then
  echo "Diff:"
  git --no-pager diff -- "${api_paths[@]}"
  echo
  echo "公开 API 基线有变更：跑 scripts/api-dump.sh 重新生成，review 之后连同代码一起提交。" >&2
  echo "如果这是破坏性变更（删符号 / 改签名），按语义化版本必须升**主**版本。" >&2
  exit_code=1
fi

exit $exit_code
