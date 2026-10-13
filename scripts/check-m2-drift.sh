#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Report local ~/.m2 coordinates of this runtime whose content is not what their version names.
#
# Why this exists. This repository's version only advances inside a release commit, so after tagging
# v<X> the working tree keeps building under <X>. A local `mvn install` then writes a jar under the
# released number, and every consumer pinning <X> silently gets that newer content on this machine
# while a clean machine or CI resolves the published artifact. The version stops meaning one thing,
# and nothing says so. This finds those coordinates before someone trusts them.
#
# How it decides — local only, no network. For each release tag v<X>, look at
# ~/.m2/repository/cn/com/keelbase/<module>/<X>/. A jar that `_remote.repositories` records as coming
# from a repository (`name>central=`) is the published artifact and is left alone. A jar recorded as a
# local install (`name>=`, empty repository id) that was written more than SLACK seconds after the
# tag's own commit is reported: it was built from a tree the tag does not describe.
#
#   scripts/check-m2-drift.sh              # report; always exit 0
#   scripts/check-m2-drift.sh --strict     # exit 1 when any coordinate has drifted
#   M2_REPO=/path/to/repository scripts/check-m2-drift.sh
#   KEELBASE_M2_SLACK=3600 scripts/check-m2-drift.sh   # grace in seconds after the tag (default 3600)
#
# 报告本机 ~/.m2 里「内容与版本号所述不符」的运行时坐标。
#
# 为什么有它。本仓的版本号只在发版提交里前进，故打了 v<X> 之后工作树仍以 <X> 构建。本机一次
# `mvn install` 就往那个已发布号下写了一份 jar，于是钉 <X> 的消费方在本机静默拿到更新的内容、
# 而干净机或 CI 解析到的却是发布物。版本号不再只对应一件事，且没有任何东西会说出来。本脚本在
# 有人信它之前把这些坐标找出来。
#
# 判法 —— 只用本地、不联网。对每个发版 tag v<X>，看 ~/.m2/repository/cn/com/keelbase/<module>/<X>/。
# 若 `_remote.repositories` 记的 jar 来自某个仓库（`name>central=`），那是发布物，不动它。若记的是
# 本地安装（`name>=`，仓库名为空），且写入时间比该 tag 自己的提交晚超过 SLACK 秒，就报出来：它是
# 从 tag 描述不了的那棵树构建的。
#
#   scripts/check-m2-drift.sh              # 只报告；永远退出 0
#   scripts/check-m2-drift.sh --strict     # 有任一坐标漂移时退出 1
#   M2_REPO=/path/to/repository scripts/check-m2-drift.sh
#   KEELBASE_M2_SLACK=3600 scripts/check-m2-drift.sh   # tag 之后的宽限秒数（默认 3600）

set -u

usage() {
  sed -n '5,18p' "$0" | sed 's/^# \{0,1\}//'
}

strict=0
for arg in "$@"; do
  case "$arg" in
    --strict) strict=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$ROOT" ]; then
  echo "not inside a git repository — this reads the release tags from the repository it runs in" >&2
  exit 2
fi
cd "$ROOT"

M2="${M2_REPO:-$HOME/.m2/repository}"
GROUP="$M2/cn/com/keelbase"
SLACK="${KEELBASE_M2_SLACK:-3600}"

if [ ! -d "$GROUP" ]; then
  echo "no local artifacts for cn.com.keelbase under $GROUP — nothing to check"
  exit 0
fi

printf '%-8s %-24s %-9s %-19s %-19s %s\n' version module kind jar-written tag-commit verdict

drift=0
checked=0

for tag in $(git tag --list 'v[0-9]*' --sort=v:refname); do
  version="${tag#v}"
  tag_epoch="$(git log -1 --format=%ct "$tag" 2>/dev/null || true)"
  [ -n "$tag_epoch" ] || continue
  tag_date="$(date -d "@$tag_epoch" '+%Y-%m-%d %H:%M:%S')"

  for dir in "$GROUP"/*/"$version"; do
    [ -d "$dir" ] || continue
    module="$(basename "$(dirname "$dir")")"
    jar="$dir/$module-$version.jar"
    [ -f "$jar" ] || continue
    checked=$((checked + 1))

    kind="download"
    if [ ! -f "$dir/_remote.repositories" ] || grep -qF "$(basename "$jar")>=" "$dir/_remote.repositories"; then
      kind="install"
    fi

    jar_epoch="$(stat -c %Y "$jar" 2>/dev/null || echo 0)"
    jar_date="$(date -d "@$jar_epoch" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '?')"

    verdict="ok"
    if [ "$kind" = "install" ]; then
      delta=$((jar_epoch - tag_epoch))
      if [ "$delta" -gt "$SLACK" ]; then
        verdict="DRIFT (+$((delta / 3600))h)"
        drift=$((drift + 1))
      fi
    fi

    printf '%-8s %-24s %-9s %-19s %-19s %s\n' "$version" "$module" "$kind" "$jar_date" "$tag_date" "$verdict"
  done
done

echo
if [ "$checked" -eq 0 ]; then
  echo "no local coordinates matched a release tag — nothing to check"
elif [ "$drift" -eq 0 ]; then
  echo "OK: $checked local coordinate(s) checked, none drifted from its release"
else
  echo "DRIFT: $drift of $checked local coordinate(s) were built from a tree their tag does not describe"
  echo "       Delete the drifted version directory to let Maven re-fetch the published artifact,"
  echo "       or keep it knowingly — the header says why this happens."
fi

if [ "$strict" -eq 1 ] && [ "$drift" -gt 0 ]; then
  exit 1
fi
exit 0
