#!/usr/bin/env bash
# 一键检查：核心库单元测试 + 跨端一致性检查
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1/2 核心库单元测试 =="
(cd core && PYTHONPATH=. python3 -m unittest discover -s tests -t .)

echo
echo "== 2/2 跨端一致性检查（规则表 ↔ Android 清单 ↔ 两侧枚举） =="
python3 tools/check_consistency.py

echo
echo "全部检查通过。"
