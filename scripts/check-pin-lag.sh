#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Report when this repository's contract pin trails the contract repository's newest release tag.
#
# The pin is read the same way the release gate reads it: the first `ref:` under
# `repository: rain6fish/keelbase-contract` in `.github/workflows/ci.yml` and `.github/workflows/release.yml`.
# The newest tag is either asked of the contract remote (`git ls-remote --tags`) or read from a local
# clone (`--contract DIR`, offline). This script only reads and reports — it never edits a file, never
# bumps the pin, and is not a push gate.
#
# Threshold. CT-4 says pins move only at three moments (contract tag cut, consumer release prep,
# cross-repo gate red), in the order contract push → consumer bump → consumer push, same day. A pin
# one released tag behind is the normal state between those moments; a pin two or more releases
# behind means a release window went by without the pin moving. `--lag N` is how many released tags
# the pin may trail; the reminder fires when it trails by MORE than N. Default N = 1, i.e. one
# release window (one released contract tag) is tolerated.
#
#   bash scripts/check-pin-lag.sh                                   # report only; always exit 0
#   bash scripts/check-pin-lag.sh --strict                          # exit 1 when the reminder fires
#   bash scripts/check-pin-lag.sh --contract ../keelbase-contract   # offline: tags from that clone
#   bash scripts/check-pin-lag.sh --lag 2                           # tolerate two releases behind
#   bash scripts/check-pin-lag.sh --repo https://gitee.com/...      # override the remote URL
#   KEELBASE_CONTRACT_REMOTE=https://... bash scripts/check-pin-lag.sh
#
# Suggested weekly reminder (this repository has no scheduled workflow yet, so nothing is wired in):
#   crontab:  0 3 * * 1  cd /path/to/KeelBase4J && bash scripts/check-pin-lag.sh --strict
# or add the same command as a `schedule:` job in .github/workflows/. It deliberately stays out of the
# push gate: a lagging pin is a reminder to run `sync-vectors.sh --bump <tag>`, not a reason to block
# a push — CT-4 keeps the pin edit manual on purpose.
#
# Exit codes: 0 report-only (whether or not there are findings); 1 --strict and the reminder fires
# (or the two workflow pins disagree, or the pin is not a tag the source knows); 2 usage/environment
# (no readable pin, no tags, unreachable remote).
#
# ── 中文 ──
# 当本仓的契约 pin 落后契约仓的最新发布 tag 时给出报告。
#
# pin 的读法与发布门禁一致：`.github/workflows/ci.yml` 与 `.github/workflows/release.yml` 里
# `repository: rain6fish/keelbase-contract` 之下第一个 `ref:`。最新 tag 要么向契约远端要
# （`git ls-remote --tags`），要么从本地克隆离线读（`--contract DIR`）。本脚本只读、只报告——
# 绝不改文件、绝不自动 bump，也不是推送门禁。
#
# 阈值。CT-4 规定 pin 只在三个时刻移动（契约打 tag、消费方发布准备、跨仓门禁红），顺序为
# 契约推送 → 消费方 bump → 消费方推送，同日完成。落后一个已发布 tag 是这些时刻之间的常态；
# 落后两个及以上发布，说明有一个发版窗口过去而 pin 没动。`--lag N` 表示允许 pin 落后多少个
# 已发布 tag；落后**超过** N 时提醒。默认 N = 1，即容忍一个发版窗口（一个已发布契约 tag）。
#
#   bash scripts/check-pin-lag.sh                                   # 只报告；永远退出 0
#   bash scripts/check-pin-lag.sh --strict                          # 提醒条件成立时退出 1
#   bash scripts/check-pin-lag.sh --contract ../keelbase-contract   # 离线：从该克隆读 tag
#   bash scripts/check-pin-lag.sh --lag 2                           # 容忍落后两个发布
#   bash scripts/check-pin-lag.sh --repo https://gitee.com/...      # 覆盖远端 URL
#   KEELBASE_CONTRACT_REMOTE=https://... bash scripts/check-pin-lag.sh
#
# 建议的每周提醒（本仓尚无定时工作流，故未接线）：
#   crontab:  0 3 * * 1  cd /path/to/KeelBase4J && bash scripts/check-pin-lag.sh --strict
# 或把同一命令加成 `.github/workflows/` 里的 `schedule:` job。它**有意**不进推送门禁：pin 落后是
# 「该跑 `sync-vectors.sh --bump <tag>` 了」的提醒，不是拦推送的理由——CT-4 有意让改 pin 这一步
# 保持手动。
#
# 退出码：0 只报告（不论有无发现）；1 `--strict` 且提醒条件成立（或两处 pin 不一致、或 pin 不是
# 来源认识的 tag）；2 用法/环境问题（读不到 pin、没有 tag、远端不可达）。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

usage() {
  sed -n '19,24p' "$0" | sed 's/^# \{0,1\}//'
}

STRICT=0
LAG=1
CONTRACT_DIR=""
REPO_URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --strict) STRICT=1 ;;
    --lag)
      [ $# -ge 2 ] || { echo "--lag needs a number" >&2; exit 2; }
      LAG="$2"; shift ;;
    --contract)
      [ $# -ge 2 ] || { echo "--contract needs a directory" >&2; exit 2; }
      CONTRACT_DIR="$2"; shift ;;
    --repo)
      [ $# -ge 2 ] || { echo "--repo needs a URL" >&2; exit 2; }
      REPO_URL="$2"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done
case "$LAG" in
  '' | *[!0-9]*) echo "--lag must be a non-negative integer: $LAG" >&2; exit 2 ;;
esac

pin_of() {
  awk '/^[[:space:]]*repository:[[:space:]]*rain6fish\/keelbase-contract[[:space:]]*$/ { f = 1 }
       f && /^[[:space:]]*ref:[[:space:]]*/ { sub(/^[[:space:]]*ref:[[:space:]]*/, ""); print; exit }' "$1"
}

PIN_CI=$(pin_of .github/workflows/ci.yml)
PIN_REL=$(pin_of .github/workflows/release.yml)
if [ -z "$PIN_CI" ] || [ -z "$PIN_REL" ]; then
  echo "cannot read the contract pin from .github/workflows/ci.yml / release.yml" >&2
  exit 2
fi

if [ -n "$CONTRACT_DIR" ]; then
  if ! git -C "$CONTRACT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    echo "--contract is not a git clone: $CONTRACT_DIR" >&2
    exit 2
  fi
  TAGS=$(git -C "$CONTRACT_DIR" tag --list | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true)
  WHERE="local clone $CONTRACT_DIR (offline; fetch --tags there for a fresh answer)"
else
  URL="$REPO_URL"
  [ -n "$URL" ] || URL="${KEELBASE_CONTRACT_REMOTE:-}"
  if [ -z "$URL" ]; then
    DEFAULT_CLONE="$ROOT/../keelbase-contract"
    if git -C "$DEFAULT_CLONE" rev-parse --git-dir >/dev/null 2>&1; then
      URL=$(git -C "$DEFAULT_CLONE" remote get-url origin 2>/dev/null || true)
    fi
  fi
  [ -n "$URL" ] || URL="https://github.com/rain6fish/keelbase-contract.git"
  if command -v timeout >/dev/null 2>&1; then
    RAW=$(timeout 30 env GIT_TERMINAL_PROMPT=0 git ls-remote --tags "$URL" 2>&1)
  else
    RAW=$(GIT_TERMINAL_PROMPT=0 git ls-remote --tags "$URL" 2>&1)
  fi || {
    echo "cannot read tags from $URL (offline?). Re-run with --contract DIR to use a local clone." >&2
    exit 2
  }
  TAGS=$(printf '%s\n' "$RAW" | sed -n 's|^.*refs/tags/||p' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true)
  WHERE="remote $URL"
fi

if [ -z "$TAGS" ]; then
  echo "no release tags (vX.Y.Z) found at $WHERE" >&2
  exit 2
fi

SORTED=$(printf '%s\n' "$TAGS" | sort -uV)
LATEST=$(printf '%s\n' "$SORTED" | tail -n 1)
idx_of() { printf '%s\n' "$SORTED" | grep -nxF -- "$1" | head -n 1 | cut -d: -f1; }
IDX_PIN=$(idx_of "$PIN_CI")
IDX_LATEST=$(idx_of "$LATEST")

FINDINGS=0
finding() { FINDINGS=$((FINDINGS + 1)); printf 'REMINDER: %s\n' "$1"; }

echo "contract pin: ci.yml=$PIN_CI release.yml=$PIN_REL"
echo "newest tag:   $LATEST (from $WHERE)"
if [ "$PIN_CI" != "$PIN_REL" ]; then
  finding "the two workflow pins disagree: ci.yml=$PIN_CI, release.yml=$PIN_REL — they must move together"
fi
if [ -z "$IDX_PIN" ]; then
  finding "pin $PIN_CI is not among the tags known at $WHERE (stale offline clone, or a pin that was never tagged?)"
else
  BEHIND=$((IDX_LATEST - IDX_PIN))
  [ "$BEHIND" -lt 0 ] && BEHIND=0
  if [ "$BEHIND" -gt "$LAG" ]; then
    finding "pin $PIN_CI trails the newest tag $LATEST by $BEHIND released tag(s); allowed is $LAG (one release window = one released tag)"
  else
    echo "pin is within the allowed window: $BEHIND released tag(s) behind (allowed $LAG)."
  fi
fi
if [ "$FINDINGS" -gt 0 ]; then
  echo "next step: after the contract tag is pushed, bump deliberately with 'scripts/sync-vectors.sh --bump <tag>' (CT-4: contract push -> consumer bump -> consumer push, same day). This script does not bump."
fi
if [ "$STRICT" -eq 1 ] && [ "$FINDINGS" -gt 0 ]; then
  exit 1
fi
exit 0
