#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Report when this repository's contract pin trails the contract repository's newest release tag.
#
# The pin is read the same way the release gate reads it: the first `ref:` under
# `repository: rain6fish/keelbase-contract` in `.github/workflows/ci.yml` and `.github/workflows/release.yml`.
# The newest tag is either asked of the contract remote (`git ls-remote --tags`) or read from a local
# clone (`--contract DIR`, offline). This script only reads and reports — it never edits a file and
# never bumps the pin, and lag is never a gate (see below); the one thing CI gates on is that the two
# workflow pins agree.
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
#   bash scripts/check-pin-lag.sh --pins-only                       # only: the two pins agree, and both are tags
#   bash scripts/check-pin-lag.sh --contract ../keelbase-contract   # offline: tags from that clone
#   bash scripts/check-pin-lag.sh --lag 2                           # tolerate two releases behind
#   bash scripts/check-pin-lag.sh --repo https://gitee.com/...      # override the remote URL
#   KEELBASE_CONTRACT_REMOTE=https://... bash scripts/check-pin-lag.sh
#
# Where this runs. The `contract-pin` job in `.github/workflows/ci.yml` runs `--pins-only --strict` on
# every push to main and every pull request, then runs the lag report without `--strict` and turns its
# reminders into workflow warnings. The split is the point. The two-pin check reads only this
# repository — no sibling checkout, no network — so it cannot go red because a remote had a bad
# minute, and it judges exactly one thing: both workflows answer for the same released contract
# version. Lag is a warning and never a red, because CT-4 makes one release window behind the normal
# state between the moments a pin is allowed to move, and a gate that is red in the normal state is a
# gate people learn to ignore. There is no `schedule:` trigger, deliberately: it would cover every job
# in ci.yml (the Maven legs included), and a tree nobody is pushing to has nobody to act on the
# reminder. The cron below remains the way to get a weekly look at a quiet tree.
#   crontab:  0 3 * * 1  cd /path/to/KeelBase4J && bash scripts/check-pin-lag.sh --strict
#
# Exit codes: 0 report-only (whether or not there are findings); 1 --strict and the reminder fires
# (or the two workflow pins disagree, or a pin is not a released version tag, or the pin is not a tag
# the source knows); 2 usage/environment (no readable pin, no tags, unreachable remote).
#
# ── 中文 ──
# 当本仓的契约 pin 落后契约仓的最新发布 tag 时给出报告。
#
# pin 的读法与发布门禁一致：`.github/workflows/ci.yml` 与 `.github/workflows/release.yml` 里
# `repository: rain6fish/keelbase-contract` 之下第一个 `ref:`。最新 tag 要么向契约远端要
# （`git ls-remote --tags`），要么从本地克隆离线读（`--contract DIR`）。本脚本只读、只报告——
# 绝不改文件、绝不自动 bump；落后**从不**是一道闸（见下），CI 唯一判红的是两处 workflow 的 pin 一致。
#
# 阈值。CT-4 规定 pin 只在三个时刻移动（契约打 tag、消费方发布准备、跨仓门禁红），顺序为
# 契约推送 → 消费方 bump → 消费方推送，同日完成。落后一个已发布 tag 是这些时刻之间的常态；
# 落后两个及以上发布，说明有一个发版窗口过去而 pin 没动。`--lag N` 表示允许 pin 落后多少个
# 已发布 tag；落后**超过** N 时提醒。默认 N = 1，即容忍一个发版窗口（一个已发布契约 tag）。
#
#   bash scripts/check-pin-lag.sh                                   # 只报告；永远退出 0
#   bash scripts/check-pin-lag.sh --strict                          # 提醒条件成立时退出 1
#   bash scripts/check-pin-lag.sh --pins-only                       # 只查：两处 pin 一致，且都是发布 tag
#   bash scripts/check-pin-lag.sh --contract ../keelbase-contract   # 离线：从该克隆读 tag
#   bash scripts/check-pin-lag.sh --lag 2                           # 容忍落后两个发布
#   bash scripts/check-pin-lag.sh --repo https://gitee.com/...      # 覆盖远端 URL
#   KEELBASE_CONTRACT_REMOTE=https://... bash scripts/check-pin-lag.sh
#
# 它在哪里跑。`.github/workflows/ci.yml` 的 `contract-pin` job 在每次推送 main 与每个 PR 上先跑
# `--pins-only --strict`，再不带上 `--strict` 跑落后报告，并把其中的提醒转成 workflow 警告。这个分工
# 就是要点。两处 pin 的检查只读本仓库——不检出兄弟仓、不联网——所以它不会因为远端抖了一下就变红，且
# 只判一件事：两个工作流回答的是同一个已发布的契约版本。落后只作警告、永不变红，因为 CT-4 把「落后
# 一个发版窗口」定成 pin 允许移动的那些时刻之间的常态，而在常态里变红的闸，是人们学会去无视的闸。
# **有意**不加 `schedule:` 触发：它会覆盖 ci.yml 里的每一个 job（含几条 Maven 腿），而一棵没人推送的
# 树，也没人去处理那条提醒。下面这条 cron 仍是「安静时每周看一眼」的办法。
#   crontab:  0 3 * * 1  cd /path/to/KeelBase4J && bash scripts/check-pin-lag.sh --strict
#
# 退出码：0 只报告（不论有无发现）；1 `--strict` 且提醒条件成立（或两处 pin 不一致、或某处 pin 不是
# 发布 tag、或 pin 不是来源认识的 tag）；2 用法/环境问题（读不到 pin、没有 tag、远端不可达）。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

usage() {
  # Keyed on the block's own content, not on line numbers: the usage block has grown, and a range of
  # line numbers is a second copy of the header that nothing keeps in step with it.
  #
  # 按这段内容本身取，不按行号：用法块已经变长过，而一串行号是那份头部说明的第二份副本，没有任何
  # 东西会保证两者同步。
  awk '/^#   bash scripts\/check-pin-lag\.sh/ { on = 1 }
       on { sub(/^# ?/, ""); print; if (/^[[:space:]]*KEELBASE_CONTRACT_REMOTE=/) exit }' "$0"
}

STRICT=0
PINS_ONLY=0
LAG=1
CONTRACT_DIR=""
REPO_URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --strict) STRICT=1 ;;
    --pins-only) PINS_ONLY=1 ;;
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

FINDINGS=0
finding() { FINDINGS=$((FINDINGS + 1)); printf 'REMINDER: %s\n' "$1"; }
is_release_tag() { [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; }
finish() {
  if [ "$FINDINGS" -gt 0 ]; then
    echo "next step: after the contract tag is pushed, bump deliberately with 'scripts/sync-vectors.sh --bump <tag>' (CT-4: contract push -> consumer bump -> consumer push, same day). This script does not bump."
  fi
  if [ "$STRICT" -eq 1 ] && [ "$FINDINGS" -gt 0 ]; then
    exit 1
  fi
  exit 0
}

echo "contract pin: ci.yml=$PIN_CI release.yml=$PIN_REL"
if [ "$PIN_CI" != "$PIN_REL" ]; then
  finding "the two workflow pins disagree: ci.yml=$PIN_CI, release.yml=$PIN_REL — they must move together"
fi
if ! is_release_tag "$PIN_CI"; then
  finding "the ci.yml pin '$PIN_CI' is not a release tag (vX.Y.Z) — a branch or a moving ref is not a pin"
fi
if [ "$PIN_REL" != "$PIN_CI" ] && ! is_release_tag "$PIN_REL"; then
  finding "the release.yml pin '$PIN_REL' is not a release tag (vX.Y.Z) — a branch or a moving ref is not a pin"
fi

if [ "$PINS_ONLY" -eq 1 ]; then
  echo "pins-only: compared the two workflow pins and their shape; the newest-tag lag was not read."
  finish
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

echo "newest tag:   $LATEST (from $WHERE)"
if [ -z "$IDX_PIN" ]; then
  # Only when the pin is tag-shaped: a branch name is already reported above, and reporting it twice
  # says less than reporting it once.
  #
  # 只在 pin 长得像 tag 时才报：分支名上面已经报过了，报两遍比报一遍说得更少。
  if is_release_tag "$PIN_CI"; then
    finding "pin $PIN_CI is not among the tags known at $WHERE (stale offline clone, or a pin that was never tagged?)"
  fi
else
  BEHIND=$((IDX_LATEST - IDX_PIN))
  [ "$BEHIND" -lt 0 ] && BEHIND=0
  if [ "$BEHIND" -gt "$LAG" ]; then
    finding "pin $PIN_CI trails the newest tag $LATEST by $BEHIND released tag(s); allowed is $LAG (one release window = one released tag)"
  else
    echo "pin is within the allowed window: $BEHIND released tag(s) behind (allowed $LAG)."
  fi
fi
finish
