#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Resolve a JDK 25 for one command, without touching the machine's default JDK.
#
# Why this exists. CI runs this repository's conformance leg twice — on the baseline JDK 17 and on 25
# (`.github/workflows/ci.yml`). The artifacts stay built for 17 (`--release 17`), so the 25 leg claims
# only that the build and the tests run there. Locally there was no standard way to run that leg:
# the machine default is 17, and on a checkout that several sessions share, changing JAVA_HOME or PATH
# would change what everyone else builds with. So this resolves 25 for the duration of one command and
# leaves both alone.
#
# Resolution order — the first that works wins, and nothing is installed unless none of them do:
#
#   1. $KEELBASE_JDK25             an explicit JDK 25 home
#   2. $JDK25_HOME                 a conventional name for the same thing
#   3. $KEELBASE_JDK25_DIR/jdk-25  where an earlier run downloaded it (default: ~/.keelbase/jdk25)
#   4. /d/Develop/jdk-25           where this shared machine keeps it
#   5. download + unpack           a build for this OS and CPU, from the Adoptium API
#
# Only the last option needs the network. It reads `uname` to pick the Adoptium coordinates
# (windows / mac / linux, x64 / aarch64), unpacks into the step-3 directory, and checks that the
# result reports version 25 before using it.
#
# Usage:
#
#   bash scripts/jdk25.sh java -version
#   bash scripts/jdk25.sh mvn -o -B -ntp verify
#
# 为**一条命令**解析出一个 JDK 25，**不动机器默认的 JDK**。
#
# 为什么需要它。CI 的一致性门禁在本仓跑**两遍**——基线 JDK 17 与 25（`.github/workflows/ci.yml`）。产物仍按
# 17 构建（`--release 17`），故 25 那条腿只声明「构建与测试在这边跑通」。本机此前没有标准办法跑那条腿：
# 机器默认是 17，而在**多个会话共用**的检出上改 JAVA_HOME 或 PATH 会改变**别人**构建所用的东西。故本脚本只在
# **一条命令**的期间里解析出 25，两者一律不动。
#
# 解析顺序——**先成的胜出**；以上皆不成才装：
#
#   1. `$KEELBASE_JDK25`           显式给出 JDK 25 的 home
#   2. `$JDK25_HOME`               同一件事的惯用名
#   3. `$KEELBASE_JDK25_DIR/jdk-25`  上一次运行下载到的地方（默认 `~/.keelbase/jdk25`）
#   4. `/d/Develop/jdk-25`         本机共用检出放它的地方
#   5. 下载并解包                 按本机 OS 与 CPU 取一个构建，取自 Adoptium API
#
# 只有最后一档要联网。它读 `uname` 决定 Adoptium 坐标（windows / mac / linux、x64 / aarch64），
# 解包到第 3 档的目录，并在使用前核对结果报的是 25。
#
# 用法：
#
#   bash scripts/jdk25.sh java -version
#   bash scripts/jdk25.sh mvn -o -B -ntp verify
set -euo pipefail

ADOPTIUM_BASE='https://api.adoptium.net/v3/binary/latest/25/ga'
DEFAULT_ROOT="${KEELBASE_JDK25_DIR:-${HOME:-/tmp}/.keelbase/jdk25}"

is_jdk25() { # dir
  [ -x "$1/bin/java" ] || return 1
  # The whole output, not its first line: JAVA_TOOL_OPTIONS makes the JVM print "Picked up …" first.
  #
  # 读整段输出、不只第一行：JAVA_TOOL_OPTIONS 会让 JVM 先打一行 "Picked up …"。
  "$1/bin/java" -version 2>&1 | grep -q 'version "25\.'
}

resolve() {
  local candidate
  local candidates=(
    "${KEELBASE_JDK25:-}"
    "${JDK25_HOME:-}"
    "$DEFAULT_ROOT/jdk-25"
    "/d/Develop/jdk-25"
  )
  for candidate in "${candidates[@]}"; do
    if [ -n "$candidate" ] && is_jdk25 "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

platform() { # sets os, arch, archive
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) os=windows; archive=zip ;;
    Darwin) os=mac; archive=tar.gz ;;
    Linux) os=linux; archive=tar.gz ;;
    *) echo "jdk25.sh: unsupported operating system: $(uname -s)" >&2; return 1 ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) arch=x64 ;;
    aarch64|arm64) arch=aarch64 ;;
    *) echo "jdk25.sh: unsupported architecture: $(uname -m)" >&2; return 1 ;;
  esac
}

download() {
  local root="$DEFAULT_ROOT"
  local dest="$root/jdk-25"
  local tmp file unpacked
  platform || return 1
  command -v curl >/dev/null 2>&1 \
    || { echo "jdk25.sh: curl is required to download JDK 25" >&2; return 1; }
  mkdir -p "$root"
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/keelbase-jdk25.XXXXXX")"
  trap 'rm -rf "$tmp"' RETURN
  file="$tmp/temurin25.$archive"
  echo "no JDK 25 found — downloading a $os/$arch build into $root" >&2
  curl -fsSL -o "$file" "$ADOPTIUM_BASE/$os/$arch/jdk/hotspot/normal/eclipse"
  unpacked="$tmp/unpacked"
  mkdir -p "$unpacked"
  case "$archive" in
    zip)
      if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$file" -d "$unpacked"
      elif command -v tar >/dev/null 2>&1; then
        tar -xf "$file" -C "$unpacked"
      else
        echo "jdk25.sh: unzip (or tar) is required to unpack the downloaded JDK" >&2
        return 1
      fi
      ;;
    tar.gz)
      command -v tar >/dev/null 2>&1 \
        || { echo "jdk25.sh: tar is required to unpack the downloaded JDK" >&2; return 1; }
      tar -xzf "$file" -C "$unpacked"
      ;;
  esac
  rm -rf "$dest"
  mv "$unpacked"/* "$dest"
  is_jdk25 "$dest" || { echo "downloaded JDK is not 25 — aborting" >&2; return 1; }
  printf '%s\n' "$dest"
}

JDK="$(resolve || download)"

if [ "$#" -eq 0 ]; then
  echo "JDK 25: $JDK"
  echo "JAVA_HOME (unchanged): ${JAVA_HOME:-<unset>}"
  exit 0
fi

# JAVA_HOME for one command; PATH and the machine's default are left as they were.
#
# 只为一条命令设 JAVA_HOME；PATH 与机器默认保持原样。
#
# Git Bash (MSYS) does not look for executables in a drive-letter PATH entry such as `D:/…`; it needs
# the `/d/…` form. The resolved home arrives in whichever form the caller or the download directory
# used, so the PATH entry is normalized. Without this, a `D:/…` home passes the version check (called
# directly) and then the command silently runs on the machine's default JDK.
#
# Git Bash（MSYS）不会在 `D:/…` 这样的盘符 PATH 条目里找可执行文件，要的是 `/d/…` 形式。解析到的 home
# 是哪种形式取决于调用方或下载目录，故 PATH 那一条统一规范化。没有这一步时，`D:/…` 的 home 能过版本
# 检查（直调），随后命令却**安静地**跑在机器默认的 JDK 上。
JDK_PATH="$JDK"
if command -v cygpath >/dev/null 2>&1; then
  JDK_PATH="$(cygpath -u "$JDK")"
fi
exec env JAVA_HOME="$JDK" PATH="$JDK_PATH/bin:$PATH" "$@"
