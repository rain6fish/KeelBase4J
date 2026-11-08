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
#
# 它在哪里跑。`.github/workflows/ci.yml` 的 conformance 与 release-rehearsal 两个 job（两条 matrix 腿都跑），
# 以及 `.github/workflows/release.yml` 的发布 job —— 发布那个最要紧，因为 tag 收不回来，而它发布的产物
# 就跑在那里选定的 JDK 上。
set -euo pipefail

usage() {
  echo "usage: bash scripts/check-jdk-build.sh <major>.<minor>.<patch>[.<update>]+<build>  e.g. 17.0.21+9" >&2
  exit 2
}

# Accept the fix line as one word or as two (`17.0.21` `+9`), because a shell that is not bash can
# take the bare `+9` for an operator instead of part of the word. Concatenating covers both.
require=${1:-}${2:+${2}}
[[ $require == *+* ]] || usage

# Split a `MAJOR.MINOR.PATCH[.UPDATE]+BUILD` word into its five numeric fields, empty `UPDATE` -> 0.
# Prints nothing when the word does not have that shape, which the callers turn into a usage error.
split_build() {
  sed -nE 's/^([0-9]+)\.([0-9]+)\.([0-9]+)(\.([0-9]+))?\+([0-9]+)$/\1 \2 \3 \5 \6/p' <<<"$1" |
    awk '{ if ($4 == "") $4 = 0; print $0 }'
}

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
