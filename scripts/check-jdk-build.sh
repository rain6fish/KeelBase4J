#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Fail when the JDK on PATH is below a given vendor build.
#
# The assertion is made on the vendor build number, not on the version string, because the version
# string does not distinguish the two. CVE-2026-91403 and CVE-2026-114820 are fixed at Temurin
# 17.0.21+9 / 21.0.13+9 / 25.0.5+8, and Oracle's risk matrix lists "17.0.21" among the affected
# versions in the same notation — so `java -version | grep 17.0.21` answers "affected" for a build
# that is fixed, and `grep 17.0.20` answers "fine" for one that is not. The build number after the
# `+` is the only part of the line that separates them, so that is the part read here, off the
# `... Temurin-<version>+<build> ...` line itself.
#
# "At or above", not "equal to". A newer build of the same major is fixed too, and a gate that goes
# red the day a newer fixed build ships is a red for a reason it does not name — the kind that gets
# silenced by editing the number down rather than by looking. The build it passes on is printed
# either way, so the log says which one ran.
#
#   bash scripts/check-jdk-build.sh 17.0.21+9   # exit 0 at or above 17.0.21+9, 1 below it
#   bash scripts/check-jdk-build.sh --tolerate-below 25.0.5+8
#   bash scripts/check-jdk-build.sh --self-test # the comparisons, through a stand-in `java`
#
# Where this runs. `.github/workflows/ci.yml` asserts it in the conformance and release-rehearsal
# jobs (both matrix legs), and `.github/workflows/release.yml` in the release job — the release one
# matters most, because a tag cannot be taken back and the artifact it publishes runs on the JDK
# chosen there.
#
# The gate also prints one machine-readable line, so a run can name the state instead of leaving a
# reader to infer it from an exit code:
#
#   jdk-build-gate: {"major":25,"running":"25.0.4.1+1","fix_line":"25.0.5+8","state":"below","jdk_below_fix_line":true,"verdict":"below_fix_line"}
#
# `verdict` is one of `pass`, `below_fix_line`, `unverified`, `unreadable` and `major_mismatch`;
# `jdk_below_fix_line` is `true`, `false`, or `null` when no comparison could be made. The prefix is
# on stdout and nowhere else, so the line can be grepped without reading the prose.
#
# `--tolerate-below` records a below-fix-line build as `"verdict":"unverified"` and exits 0. It is for
# one situation only: a leg whose *environment* is known to be below the line, where a red step is
# read as a code failure and the state is lost. Today that is the JDK 25 leg on GitHub's runner,
# until its image ships 25.0.5+8 (CTO ruling, 2026-11-28: keep the fix line, print the reading, do
# not count the leg as code-green, re-verify after the runner is patched). It does not move the fix
# line, and every other failure — no `Temurin-` line, a different major — still exits non-zero.
#
# Exit codes:
#   0  at or above the fix line, or below it under `--tolerate-below` (verdict `unverified`)
#   1  below the fix line, no `Temurin-<version>+<build>` line, or a different major
#   2  usage
#
# ── 中文 ──
#
# 当 PATH 上的 JDK 低于给定的厂商构建号时失败。
#
# 断言落在厂商构建号上，而不是版本串上，因为版本串分不出这两者。CVE-2026-91403 与
# CVE-2026-114820 的修复线是 Temurin 17.0.21+9 / 21.0.13+9 / 25.0.5+8，而 Oracle 的风险矩阵用
# **同样的写法**（「17.0.21」）把 17.0.21 列在**受影响**版本里 —— 所以 `java -version | grep 17.0.21`
# 会对一个已修的构建答「受影响」，而 `grep 17.0.20` 会对一个没修的答「没事」。`+` 之后的构建号是这一行里
# 唯一能分开两者的部分，所以这里读的就是它，而且是从 `... Temurin-<版本>+<构建号> ...` 那一行本身读。
#
# 是「不低于」，不是「等于」。同一大版本更新的构建同样是已修的，而一道在更新的已修构建出现的当天变红的闸，
# 是一道红得说不出自己名字的闸 —— 那种红最后是被把数字改小消掉的，而不是被看出来的。无论哪种它都会打印
# 通过的构建号，日志因此始终记着跑的是哪一个。
#
#   bash scripts/check-jdk-build.sh 17.0.21+9   # 不低于 17.0.21+9 退出 0，低于则退出 1
#   bash scripts/check-jdk-build.sh --tolerate-below 25.0.5+8
#   bash scripts/check-jdk-build.sh --self-test # 借一个替身 `java` 跑那几种比较
#
# 它在哪里跑。`.github/workflows/ci.yml` 的 conformance 与 release-rehearsal 两个 job（两条 matrix 腿都跑），
# 以及 `.github/workflows/release.yml` 的发布 job —— 发布那个最要紧，因为 tag 收不回来，而它发布的产物
# 就跑在那里选定的 JDK 上。
#
# 本闸除散文外还打印**一行机器可读读数**，好让一次运行**点名**状态，而不是留给读者从退出码去猜：
#
#   jdk-build-gate: {"major":25,"running":"25.0.4.1+1","fix_line":"25.0.5+8","state":"below","jdk_below_fix_line":true,"verdict":"below_fix_line"}
#
# `verdict` 取 `pass` / `below_fix_line` / `unverified` / `unreadable` / `major_mismatch` 之一；
# `jdk_below_fix_line` 为 `true`、`false`，无法比较时为 `null`。该前缀只出现在 stdout 的这一行，
# 故不必读散文即可 grep。
#
# `--tolerate-below` 把低于修复线的构建记为 `"verdict":"unverified"` 并退出 0。它只为一件事存在：
# 某条腿的**环境**已知低于修复线，此时红色步骤会被读成代码失败、状态反而丢失。当下就是 GitHub runner 上的
# JDK 25 腿，直到其镜像带上 25.0.5+8（CTO 裁决，2026-11-28：保留修复线、输出读数、不计入代码绿、
# runner 更新后复验）。它不移动修复线；其它失败（没有 `Temurin-` 行、大版本不同）在该腿上照样非零退出。
#
# 退出码：
#   0  不低于修复线，或低于修复线但带 `--tolerate-below`（verdict 为 `unverified`）
#   1  低于修复线、没有 `Temurin-<版本>+<构建号>` 行，或大版本不同
#   2  用法错误
set -euo pipefail

usage() {
  echo "usage: bash scripts/check-jdk-build.sh [--tolerate-below] <major>.<minor>.<patch>[.<update>]+<build>  e.g. 17.0.21+9" >&2
  echo "       bash scripts/check-jdk-build.sh --self-test" >&2
  exit 2
}

# Split a `MAJOR.MINOR.PATCH[.UPDATE]+BUILD` word into its five numeric fields, empty `UPDATE` -> 0.
# Prints nothing when the word does not have that shape, which the callers turn into a usage error.
#
# The two shapes are matched separately so that every field printed is non-empty. One pattern with an
# optional update group leaves a hole there, and whitespace splitting closes the hole by shifting the
# build number into it: `25.0.5+8` was read as version 25.0.5.8 build 0, so Temurin 25.0.5.1+2 — newer
# than the fix line — came out BELOW it while `17.0.21+9` against itself still passed, which is the
# only reason this stayed hidden.
#
# 把 `MAJOR.MINOR.PATCH[.UPDATE]+BUILD` 拆成五个数字字段，缺省 `UPDATE` 为 0。形状不符时什么都不打印，
# 调用方据此报用法错误。
#
# 两种形状分开匹配，好让打印出的每个字段都非空。用一条带可选更新组的模式会在那里留一个空位，而按空白切分
# 会把空位合上、把构建号挪进去：`25.0.5+8` 曾被读成版本 25.0.5.8、构建号 0，于是比修复线更新的
# Temurin 25.0.5.1+2 被算成低于它；而 `17.0.21+9` 与自身比较仍然通过，这正是它一直没被发现的原因。
split_build() {
  sed -nE \
    -e 's/^([0-9]+)\.([0-9]+)\.([0-9]+)\.([0-9]+)\+([0-9]+)$/\1 \2 \3 \4 \5/p' \
    -e 's/^([0-9]+)\.([0-9]+)\.([0-9]+)\+([0-9]+)$/\1 \2 \3 0 \4/p' <<<"$1"
}

# Print the one machine-readable line. `major` and `running` take the literal `null` when no
# comparison could be made, because `false` there would claim something nobody checked.
#
# 打印那一行机器可读读数。无法比较时 `major` 与 `running` 取字面量 `null` —— 此时写 `false` 等于
# 声称了一件没人核过的事。
reading() { # major running fix_line state below verdict
  local major=$1 running=$2 fix_line=$3 state=$4 below=$5 verdict=$6 running_json
  if [ "$running" = null ]; then running_json=null; else running_json="\"$running\""; fi
  printf 'jdk-build-gate: {"major":%s,"running":%s,"fix_line":"%s","state":"%s","jdk_below_fix_line":%s,"verdict":"%s"}\n' \
    "$major" "$running_json" "$fix_line" "$state" "$below" "$verdict"
}

# A stand-in `java` printing the version lines a chosen build would print. The self-test runs this
# script as a subprocess against these, so what is tested is the gate the workflows call — argument
# handling, the Temurin line extraction and the comparison included — not a copy of its arithmetic.
#
# 一个替身 `java`，打印指定构建会打印的那几行版本串。自检把本脚本以子进程方式对这些替身跑，于是被测的是
# 工作流真正调用的那道闸 —— 含参数处理、Temurin 行提取与比较 —— 而不是它算术的一份副本。
self_test() {
  local failures=0 tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  shim() { # name, version lines
    local dir="$tmp/$1/bin"
    mkdir -p "$dir"
    {
      printf '%s\n' '#!/usr/bin/env bash'
      printf '%s\n' "cat >&2 <<'JAVA'"
      printf '%s\n' "$2"
      printf '%s\n' 'JAVA'
    } >"$dir/java"
    chmod +x "$dir/java"
    printf '%s\n' "$dir"
  }

  expect_rc() { # label, wanted-rc, actual-rc
    if [ "$3" = "$2" ]; then
      echo "  ok   $1 (exit $3)"
    else
      echo "  FAIL $1 (exit $3, wanted $2)" >&2
      failures=$((failures + 1))
    fi
  }

  run_case() { # label, wanted-rc, fix line, version lines [, extra args...]
    local bin rc=0 label=$1 wanted=$2 fix=$3 versions=$4
    shift 4
    bin="$(shim "$label" "$versions")"
    PATH="$bin:$PATH" bash "$0" "$@" "$fix" >/dev/null 2>&1 || rc=$?
    expect_rc "$label" "$wanted" "$rc"
  }

  echo "self-test: the fix line read with and without an update component"
  run_case 'update above the fix line' 0 25.0.5+8 \
    'OpenJDK Runtime Environment Temurin-25.0.5.1+2 (build 25.0.5.1+2-LTS)'
  run_case 'exactly the fix line' 0 25.0.5+8 \
    'OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)'
  run_case 'same patch, lower build' 1 25.0.5+8 \
    'OpenJDK Runtime Environment Temurin-25.0.5+2 (build 25.0.5+2-LTS)'
  run_case 'later patch, first build' 0 25.0.5+8 \
    'OpenJDK Runtime Environment Temurin-25.0.6+1 (build 25.0.6+1-LTS)'
  run_case 'update below an update fix line' 1 17.0.21+9 \
    'OpenJDK Runtime Environment Temurin-17.0.20.1+1 (build 17.0.20.1+1-LTS)'
  # Mixed shapes: a three-part running build against a four-part fix line. The two shapes used to be
  # compared field-to-field in different slots, which answered "above" for a build that is below.
  #
  # 混用两种形状：三段的运行构建对四段的修复线。两种形状此前落在不同槽里逐字段比，把一个更低的构建
  # 答成了「更高」。
  run_case 'three-part build against a four-part fix line' 1 17.0.20.1+1 \
    'OpenJDK Runtime Environment Temurin-17.0.20+5 (build 17.0.20+5-LTS)'
  run_case 'another vendor reports no Temurin build' 1 25.0.5+8 \
    'OpenJDK 64-Bit Server VM Zulu25.30+17 (build 25.0.5.1+2-LTS, mixed mode, sharing)'
  run_case 'major other than the fix line' 1 25.0.5+8 \
    'OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)'
  run_case 'a below-line build under --tolerate-below' 0 25.0.5+8 \
    'OpenJDK Runtime Environment Temurin-25.0.5+2 (build 25.0.5+2-LTS)' --tolerate-below
  run_case '--tolerate-below still refuses an unreadable JDK' 1 25.0.5+8 \
    'OpenJDK 64-Bit Server VM (build 25, mixed mode)' --tolerate-below

  # Clear the trap here rather than letting it run at script exit: `tmp` is local to this function,
  # and under `set -u` a trap that outlives it fails the script with an unbound variable — a green
  # self-test would then report exit 1.
  #
  # 在这里撤掉 trap，而不是让它留到脚本退出时跑：`tmp` 是本函数的局部变量，而 `set -u` 下比它活得久的
  # trap 会以「未绑定变量」让脚本失败 —— 一次全过的自检反而报退出码 1。
  rm -rf "$tmp"
  trap - EXIT

  if [ "$failures" -ne 0 ]; then
    echo "self-test: $failures case(s) failed" >&2
    return 1
  fi
  echo "self-test: all cases passed"
  return 0
}

tolerate=0
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --tolerate-below) tolerate=1 ;;
    --self-test) self_test; exit $? ;;
    -h | --help) usage ;;
    -*) echo "unknown argument: $1 (try --help)" >&2; usage ;;
    *) args+=("$1") ;;
  esac
  shift
done

# Accept the fix line as one word or as two (`17.0.21` `+9`), because a shell that is not bash can
# take the bare `+9` for an operator instead of part of the word. Concatenating covers both.
[ "${#args[@]}" -ge 1 ] || usage
[ "${#args[@]}" -le 2 ] || usage
require="${args[0]}${args[1]:-}"
[[ $require == *+* ]] || usage

req_fields=$(split_build "$require")
[[ -n $req_fields ]] || usage
read -r req_major req_minor req_patch req_update req_build <<<"$req_fields"

java_version=$(java -version 2>&1 || true)
printf '%s\n' "$java_version"

# `Temurin-` with a three-part version and a `+` build. The vendor prefix is part of the match on
# purpose: a JDK from another vendor reports a build number in its own scheme, and reading it against
# a Temurin fix line would be comparing two different numberings.
build_line=$(grep -E 'Temurin-[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\+[0-9]+' <<<"$java_version" | head -n1 || true)

if [[ -z $build_line ]]; then
  echo "check-jdk-build: no 'Temurin-<version>+<build>' line in \`java -version\`." >&2
  echo "check-jdk-build: the build number is what this gate reads; a JDK that does not report one cannot pass it." >&2
  echo "check-jdk-build: no 'Temurin-<版本>+<构建号>' 行；构建号正是本闸读的东西，报不出它的 JDK 过不了这道闸。" >&2
  reading null null "$require" unknown null unreadable
  exit 1
fi

running=$(sed -nE 's/.*Temurin-([0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\+[0-9]+).*/\1/p' <<<"$build_line" | head -n1)
cur_fields=$(split_build "$running")
[[ -n $cur_fields ]] || {
  echo "check-jdk-build: could not split '$build_line' into a version and a build number." >&2
  echo "check-jdk-build: 无法从 '$build_line' 里拆出版本号与构建号。" >&2
  reading null null "$require" unknown null unreadable
  exit 1
}
read -r cur_major cur_minor cur_patch cur_update cur_build <<<"$cur_fields"

if [[ $cur_major != "$req_major" ]]; then
  echo "check-jdk-build: running JDK is major $cur_major, but the fix line given is for major $req_major." >&2
  echo "check-jdk-build: 当前 JDK 是大版本 $cur_major，而给出的修复线属于大版本 $req_major。" >&2
  reading "$cur_major" "$running" "$require" unknown null major_mismatch
  exit 1
fi

state=above # equal so far
for i in 1 2 3 4 5; do
  case $i in
    1) cur=$cur_major req=$req_major ;;
    2) cur=$cur_minor req=$req_minor ;;
    3) cur=$cur_patch req=$req_patch ;;
    4) cur=$cur_update req=$req_update ;;
    5) cur=$cur_build req=$req_build ;;
  esac
  if ((cur > req)); then
    state=above
    break
  elif ((cur < req)); then
    state=below
    break
  fi
done

if [[ $state == below ]]; then
  echo "check-jdk-build: $running is BELOW the $require fix line (CVE-2026-91403 / CVE-2026-114820)." >&2
  echo "check-jdk-build: $running 低于 $require 修复线（CVE-2026-91403 / CVE-2026-114820）。" >&2
  if [[ $tolerate == 1 ]]; then
    echo "check-jdk-build: recorded as UNVERIFIED, not as a pass: this leg's environment is below the line, and the run names that state (CTO ruling 2026-11-28)." >&2
    echo "check-jdk-build: 记为 UNVERIFIED 而非通过：这条腿的环境低于修复线，运行里点名该状态（CTO 裁决 2026-11-28）。" >&2
    reading "$cur_major" "$running" "$require" below true unverified
    exit 0
  fi
  reading "$cur_major" "$running" "$require" below true below_fix_line
  exit 1
fi

echo "check-jdk-build: $running is at or above the $require fix line."
reading "$cur_major" "$running" "$require" at_or_above false pass
