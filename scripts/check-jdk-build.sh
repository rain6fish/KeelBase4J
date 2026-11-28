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
#   bash scripts/check-jdk-build.sh 17.0.21+9      # exit 0 at or above 17.0.21+9, 1 below it
#   bash scripts/check-jdk-build.sh --tolerate-below 25.0.5+8
#   bash scripts/check-jdk-build.sh --self-test
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
# `--self-test` checks the parse and the comparison against both version shapes — a three-part
# version (`25.0.5+8`) and a four-part one (`17.0.20.1+1`) — by putting a stand-in `java` on PATH.
# The parse used to hand the build number to the update slot for a three-part version, which compared
# two different numbers whenever the shapes were mixed; the self-test reddens if that returns.
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
#   bash scripts/check-jdk-build.sh 17.0.21+9      # 不低于 17.0.21+9 退出 0，低于则退出 1
#   bash scripts/check-jdk-build.sh --tolerate-below 25.0.5+8
#   bash scripts/check-jdk-build.sh --self-test
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
# `--self-test` 用 PATH 上一个替身 `java`，把解析与比较对**两种版本形状**都跑一遍 —— 三段版本
# （`25.0.5+8`）与四段版本（`17.0.20.1+1`）。此前的解析会把三段版本的构建号交给 update 槽，两种形状
# 混用时就是拿两个不同的数去比；这条回归一旦回来，自测立刻变红。
#
# 退出码：
#   0  不低于修复线，或低于修复线但带 `--tolerate-below`（verdict 为 `unverified`）
#   1  低于修复线、没有 `Temurin-<版本>+<构建号>` 行，或大版本不同
#   2  用法错误
set -euo pipefail

usage() {
  echo "usage: bash scripts/check-jdk-build.sh [--tolerate-below] <major>.<minor>.<patch>[.<update>]+<build>" >&2
  echo "       bash scripts/check-jdk-build.sh --self-test" >&2
  echo "  e.g. bash scripts/check-jdk-build.sh 17.0.21+9" >&2
  exit 2
}

# Split a `MAJOR.MINOR.PATCH[.UPDATE]+BUILD` word into `major minor patch update build`, with UPDATE
# defaulting to 0 so both version shapes compare in the same five slots. Prints nothing when the word
# does not have that shape, which the callers turn into a usage error.
#
# The field count, not a field position, is what separates the shapes: sed leaves an empty field where
# UPDATE is missing, and awk's default field splitting collapses it — so a three-part version arrives
# as four fields (build last) and a four-part one as five. The previous form defaulted the fourth
# field by position, read the collapsed line as four fields, and handed `build` to `update`.
#
# 把 `MAJOR.MINOR.PATCH[.UPDATE]+BUILD` 拆成 `major minor patch update build`，UPDATE 缺省为 0，
# 两种版本形状因此落在同样五个槽里比较。形状不对时什么都不打印，调用方把它转成用法错误。
#
# 分开两种形状的是**字段个数**，不是某个字段的位置：UPDATE 缺失时 sed 留一个空字段，而 awk 默认的
# 字段切分会把它合并掉 —— 于是三段版本到手是四个字段（build 在最后），四段版本是五个。此前的写法
# 按位置给第四个字段填缺省、又把合并后的行读成四个字段，结果把 `build` 交给了 `update`。
split_build() {
  local fields
  fields=$(sed -nE 's/^([0-9]+)\.([0-9]+)\.([0-9]+)(\.([0-9]+))?\+([0-9]+)$/\1 \2 \3 \5 \6/p' <<<"$1")
  [ -n "$fields" ] || return 0
  awk '{ if (NF == 4) print $1, $2, $3, 0, $4; else if (NF == 5) print }' <<<"$fields"
}

# Compare two split words field by field, most significant first. Prints `below`, `above` or `equal`;
# prints `malformed` and returns 1 when either word does not carry five fields.
#
# 逐字段比较两个拆好的词，先比高位。打印 `below`、`above` 或 `equal`；任一词不足五个字段时打印
# `malformed` 并返回 1。
state_of() { # cur_fields req_fields
  local -a cur req
  read -r -a cur <<<"$1"
  read -r -a req <<<"$2"
  if [ "${#cur[@]}" -ne 5 ] || [ "${#req[@]}" -ne 5 ]; then
    echo malformed
    return 1
  fi
  local i
  for i in 0 1 2 3 4; do
    if ((cur[i] > req[i])); then echo above; return 0; fi
    if ((cur[i] < req[i])); then echo below; return 0; fi
  done
  echo equal
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

self_test() {
  local failures=0 tmp case_rc=0 last_out=
  tmp=$(mktemp -d)
  trap "rm -rf '$tmp'" EXIT
  mkdir -p "$tmp/bin"

  # A stand-in `java` that prints whatever the case asks for, so the parse and the decision run end
  # to end without a second JDK installed.
  # 一个替身 `java`，按用例打印指定文本，于是解析与判定能端到端跑，不必装第二个 JDK。
  cat > "$tmp/bin/java" <<'STANDIN'
#!/usr/bin/env bash
printf '%s\n' "${STANDIN_JAVA_VERSION:?STANDIN_JAVA_VERSION is not set}"
STANDIN
  chmod +x "$tmp/bin/java"

  ok() { echo "  ok   $1"; }
  fail() { echo "  FAIL $1" >&2; failures=$((failures + 1)); }

  expect_split() { # word expected
    local got
    got=$(split_build "$1")
    if [ "$got" = "$2" ]; then ok "split_build $1 -> $2"; else fail "split_build $1 -> '$got', wanted '$2'"; fi
  }

  expect_rc() { # label wanted actual
    if [ "$3" = "$2" ]; then ok "$1 (exit $3)"; else fail "$1 (exit $3, wanted $2)"; fi
  }

  expect_out() { # label needle
    if grep -qF -- "$2" <<<"$last_out"; then ok "$1"; else fail "$1 (missing '$2' in output)"; fi
  }

  run_gate() { # java_version fix [extra args...]
    local text=$1 fix=$2
    shift 2
    case_rc=0
    last_out=$(PATH="$tmp/bin:$PATH" STANDIN_JAVA_VERSION="$text" bash "$0" "$@" "$fix" 2>&1) || case_rc=$?
  }

  # The shapes first. A three-part version must keep update at 0 and leave the build in the build
  # slot — the regression this self-test exists for moved the build into update.
  # 先测形状。三段版本必须让 update 保持 0、构建号留在 build 槽 —— 本自测要防的那条回归正是把构建号
  # 挪进了 update。
  expect_split "25.0.5+8" "25 0 5 0 8"
  expect_split "17.0.20.1+1" "17 0 20 1 1"
  expect_split "21.0.13+9" "21 0 13 0 9"
  expect_split "25.0.5" ""

  run_gate "OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8-LTS)" 25.0.5+8
  expect_rc "at the fix line, three-part" 0 "$case_rc"
  expect_out "at the fix line, three-part reading" '"jdk_below_fix_line":false'
  expect_out "at the fix line, three-part verdict" '"verdict":"pass"'

  run_gate "Temurin-25.0.5+7" 25.0.5+8
  expect_rc "one build below the line" 1 "$case_rc"
  expect_out "one build below the line reading" '"jdk_below_fix_line":true'
  expect_out "one build below the line verdict" '"verdict":"below_fix_line"'

  run_gate "Temurin-25.0.6+1" 25.0.5+8
  expect_rc "a later version outranks an earlier build" 0 "$case_rc"

  run_gate "Temurin-25.0.4.1+1" 25.0.5+8
  expect_rc "a four-part version below the line" 1 "$case_rc"

  run_gate "Temurin-25.0.5.1+1" 25.0.5+8
  expect_rc "a four-part version at the line (update outranks build 0)" 0 "$case_rc"

  # Mixed shapes. The old parse compared the three-part build against the four-part update and
  # answered "above" for a build that is below.
  # 混用两种形状。旧解析拿三段版本的构建号去比四段版本的 update，把一个更低的构建答成了「更高」。
  run_gate "Temurin-17.0.20+5" 17.0.20.1+1
  expect_rc "three-part version against a four-part fix line" 1 "$case_rc"

  run_gate "Temurin-25.0.4.1+1" 25.0.5+8 --tolerate-below
  expect_rc "below the line under --tolerate-below exits 0" 0 "$case_rc"
  expect_out "below the line under --tolerate-below is named unverified" '"verdict":"unverified"'
  expect_out "below the line under --tolerate-below keeps the reading" '"jdk_below_fix_line":true'

  run_gate "OpenJDK 64-Bit Server VM (build 25, mixed mode)" 25.0.5+8 --tolerate-below
  expect_rc "--tolerate-below does not tolerate an unreadable JDK" 1 "$case_rc"
  expect_out "an unreadable JDK still has a verdict" '"verdict":"unreadable"'

  run_gate "Temurin-21.0.13+9" 25.0.5+8 --tolerate-below
  expect_rc "--tolerate-below does not tolerate another major" 1 "$case_rc"
  expect_out "another major still has a verdict" '"verdict":"major_mismatch"'

  case_rc=0
  bash "$0" >/dev/null 2>&1 || case_rc=$?
  expect_rc "no fix line is a usage error" 2 "$case_rc"

  if [ "$failures" -gt 0 ]; then
    echo "self-test: $failures case(s) failed" >&2
    return 1
  fi
  echo "self-test: all cases passed"
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
#
# 修复线接受一个词或两个词（`17.0.21` `+9`），因为不是 bash 的 shell 会把裸 `+9` 当成运算符而非词的一部分。
# 拼接一下两种都覆盖。
[ "${#args[@]}" -ge 1 ] || usage
[ "${#args[@]}" -le 2 ] || usage
require="${args[0]}${args[1]:-}"
[[ $require == *+* ]] || usage

req_fields=$(split_build "$require")
[[ -n $req_fields ]] || usage
read -r req_major _ <<<"$req_fields"

java_version=$(java -version 2>&1 || true)
printf '%s\n' "$java_version"

# `Temurin-` with a three-part version and a `+` build. The vendor prefix is part of the match on
# purpose: a JDK from another vendor reports a build number in its own scheme, and reading it against
# a Temurin fix line would be comparing two different numberings.
#
# `Temurin-` 加三段版本与 `+` 构建号。厂商前缀**有意**参与匹配：别家 JDK 报的构建号是它自己的一套编号，
# 拿它去比 Temurin 修复线，比的是两套不同的编号。
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
if [[ -z $cur_fields ]]; then
  echo "check-jdk-build: could not split '$build_line' into a version and a build number." >&2
  echo "check-jdk-build: 无法从 '$build_line' 里拆出版本号与构建号。" >&2
  reading null null "$require" unknown null unreadable
  exit 1
fi

cur_major=${cur_fields%% *}
state=$(state_of "$cur_fields" "$req_fields") || state=malformed
if [[ $state == malformed ]]; then
  echo "check-jdk-build: could not compare '$running' with the fix line '$require'." >&2
  echo "check-jdk-build: 无法把 '$running' 与修复线 '$require' 放在一起比较。" >&2
  reading "$cur_major" "$running" "$require" unknown null unreadable
  exit 1
fi

if [[ $cur_major != "$req_major" ]]; then
  echo "check-jdk-build: running JDK is major $cur_major, but the fix line given is for major $req_major." >&2
  echo "check-jdk-build: 当前 JDK 是大版本 $cur_major，而给出的修复线属于大版本 $req_major。" >&2
  reading "$cur_major" "$running" "$require" unknown null major_mismatch
  exit 1
fi

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
