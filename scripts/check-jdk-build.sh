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
#   bash scripts/check-jdk-build.sh --self-test # the comparisons, through a stand-in `java`
#
# Where this runs. `.github/workflows/ci.yml` asserts it in the conformance and release-rehearsal
# jobs (both matrix legs), and `.github/workflows/release.yml` in the release job — the release one
# matters most, because a tag cannot be taken back and the artifact it publishes runs on the JDK
# chosen there.
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
#   bash scripts/check-jdk-build.sh --self-test # 借一个替身 `java` 跑那几种比较
#
# 它在哪里跑。`.github/workflows/ci.yml` 的 conformance 与 release-rehearsal 两个 job（两条 matrix 腿都跑），
# 以及 `.github/workflows/release.yml` 的发布 job —— 发布那个最要紧，因为 tag 收不回来，而它发布的产物
# 就跑在那里选定的 JDK 上。
set -euo pipefail

usage() {
  echo "usage: bash scripts/check-jdk-build.sh <major>.<minor>.<patch>[.<update>]+<build>  e.g. 17.0.21+9" >&2
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

  run_case() { # label, wanted-rc, fix line, version lines
    local bin rc=0
    bin="$(shim "$1" "$4")"
    PATH="$bin:$PATH" bash "$0" "$3" >/dev/null 2>&1 || rc=$?
    expect_rc "$1" "$2" "$rc"
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
  run_case 'another vendor reports no Temurin build' 1 25.0.5+8 \
    'OpenJDK 64-Bit Server VM Zulu25.30+17 (build 25.0.5.1+2-LTS, mixed mode, sharing)'
  run_case 'major other than the fix line' 1 25.0.5+8 \
    'OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)'

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

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi

# Accept the fix line as one word or as two (`17.0.21` `+9`), because a shell that is not bash can
# take the bare `+9` for an operator instead of part of the word. Concatenating covers both.
require=${1:-}${2:+${2}}
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
  exit 1
fi

cur_fields=$(sed -nE 's/.*Temurin-([0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\+[0-9]+).*/\1/p' <<<"$build_line" | head -n1)
cur_fields=$(split_build "$cur_fields")
[[ -n $cur_fields ]] || {
  echo "check-jdk-build: could not split '$build_line' into a version and a build number." >&2
  echo "check-jdk-build: 无法从 '$build_line' 里拆出版本号与构建号。" >&2
  exit 1
}
read -r cur_major cur_minor cur_patch cur_update cur_build <<<"$cur_fields"

if [[ $cur_major != "$req_major" ]]; then
  echo "check-jdk-build: running JDK is major $cur_major, but the fix line given is for major $req_major." >&2
  echo "check-jdk-build: 当前 JDK 是大版本 $cur_major，而给出的修复线属于大版本 $req_major。" >&2
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

running=$(sed -nE 's/.*(Temurin-[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\+[0-9]+).*/\1/p' <<<"$build_line" | head -n1)

if [[ $state == below ]]; then
  echo "check-jdk-build: $running is BELOW the $require fix line (CVE-2026-91403 / CVE-2026-114820)." >&2
  echo "check-jdk-build: $running 低于 $require 修复线（CVE-2026-91403 / CVE-2026-114820）。" >&2
  exit 1
fi

echo "check-jdk-build: $running is at or above the $require fix line."
