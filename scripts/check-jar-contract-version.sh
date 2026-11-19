#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Read the built jars back and assert they say which contract version they were built from.
#
# The root pom writes `KeelBase-Contract-Version` into META-INF/MANIFEST.MF when the build is given
# `-Dkeelbase.contract.version`. That is a build *input*; nothing until now checked that the input
# arrived. A workflow that forgot the property would keep its green build and ship jars that say
# nothing — the exact silence the property was added to end. This is the assertion that turns that
# into a red step, before Central sees the bytes.
#
#   bash scripts/check-jar-contract-version.sh keelbase4j-core   # expected = this repo's pin
#   bash scripts/check-jar-contract-version.sh --expect v1.22.0 keelbase4j-core keelbase4j-protocol keelbase4j-springai
#   bash scripts/check-jar-contract-version.sh --self-test       # the 0/1/2 branches, no Maven needed
#
# Each argument is a jar or a module directory. A module directory resolves to its `target/`; inside,
# the main jar is every `*.jar` except the `-sources`, `-javadoc` and `-tests` classifiers. A module
# with no jar is an error: it means the build this step is supposed to check did not happen.
#
# `--expect` is optional and defaults to `bash scripts/check-pin-lag.sh --print-pin`, the one reader
# of the contract pin the workflows build from. Leaving it out is the safe form: the expected value
# is then the repository's pin, not a second copy typed beside it.
#
# Exit codes, the same split `scripts/check-artifact-contract.mjs` uses in the main repository:
#   0  every jar declares the expected version
#   1  a jar declares a different version (or `--expect` was given a value nobody could have built)
#   2  a jar declares nothing, a module has no jar, or usage/environment is wrong
#
# ── 中文 ──
# 把构建出的 jar 读回来，断言它们说清了自己是按哪一版契约构建的。
#
# 根 pom 在收到 `-Dkeelbase.contract.version` 时把 `KeelBase-Contract-Version` 写进
# META-INF/MANIFEST.MF。那是一个构建**输入**；在此之前没有任何东西检查这个输入是否真的到了。漏传属性的
# 工作流会照旧全绿，并发出什么也不说的 jar——正是加入该属性要终结的那种沉默。这道检查把它变成一个红色的
# 步骤，且在 Central 看到这些字节之前。
#
#   bash scripts/check-jar-contract-version.sh keelbase4j-core   # 期望值 = 本仓 pin
#   bash scripts/check-jar-contract-version.sh --expect v1.22.0 keelbase4j-core keelbase4j-protocol keelbase4j-springai
#   bash scripts/check-jar-contract-version.sh --self-test       # 0/1/2 三支，不需要 Maven
#
# 每个参数是一个 jar 或一个模块目录。模块目录解析到它的 `target/`；其中主 jar 是所有 `*.jar` 里除
# `-sources`、`-javadoc`、`-tests` 分类器之外的。一个模块没有 jar 是错误：它意味着这一步要检查的那次
# 构建根本没发生。
#
# `--expect` 可省，默认取 `bash scripts/check-pin-lag.sh --print-pin`——工作流据以构建的那条契约 pin
# 的唯一读法。省掉它才是稳妥形态：期望值就是本仓的 pin，而不是在它旁边再抄一份。
#
# 退出码，与主仓 `scripts/check-artifact-contract.mjs` 同一套分工：
#   0  每个 jar 都声明了期望版本
#   1  某个 jar 声明了不同的版本（或 `--expect` 收到一个没人可能据以构建的值）
#   2  某个 jar 什么也没声明、某个模块没有 jar，或用法/环境有误
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  awk '/^#   bash scripts\/check-jar-contract-version\.sh/ { on = 1 }
       on { sub(/^# ?/, ""); print; if (/^[[:space:]]*bash scripts\/check-jar-contract-version\.sh --self-test/) exit }' "$0"
}

# Print the value of one manifest attribute, joining continuation lines (RFC 822: a line that starts
# with a single space continues the previous one). Case-insensitive on the attribute name, because a
# manifest attribute is. Anything unexpected prints nothing and the caller treats it as undeclared.
#
# 打印清单里某个属性的值，并把续行拼起来（RFC 822：以单个空格开头的行是上一行的延续）。属性名不区分
# 大小写，因为清单属性本就不区分。任何异常都打印空，调用方按「未声明」处理。
manifest_attr() {
  local jar="$1" want="$2" text
  if command -v unzip >/dev/null 2>&1; then
    text="$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null)"
  else
    local tmp
    tmp="$(mktemp -d)"
    (cd "$tmp" && jar --extract --file "$jar" META-INF/MANIFEST.MF >/dev/null 2>&1)
    [ -f "$tmp/META-INF/MANIFEST.MF" ] && text="$(cat "$tmp/META-INF/MANIFEST.MF")"
    rm -rf "$tmp"
  fi
  [ -n "${text:-}" ] || return 0
  printf '%s\n' "$text" | awk -v want="$want" '
    function emit(line,   i, name, value) {
      if (found || line == "") return
      i = index(line, ":")
      if (i == 0) return
      name = substr(line, 1, i - 1)
      value = substr(line, i + 1)
      sub(/^[ \t]+/, "", value); sub(/[ \t]+$/, "", value)
      if (tolower(name) == tolower(want)) { print value; found = 1 }
    }
    {
      sub(/\r$/, "")
      if ($0 ~ /^[ \t]/) { buf = buf substr($0, 2); next }
      emit(buf); buf = $0
    }
    END { emit(buf) }
  '
}

# Run one case of the self-test: given a manifest body and an expectation, assert the exit code.
self_test() {
  local failures=0 tmp jar
  tmp="$(mktemp -d)"
  trap "rm -rf '$tmp'" EXIT

  make_jar() { # name, manifest-content
    local dir="$tmp/$1"
    mkdir -p "$dir/content"
    printf '%b' "$2" > "$dir/manifest.mf"
    (cd "$dir/content" && jar --create --file "$dir/out.jar" --manifest "$dir/manifest.mf" -C . .) >/dev/null 2>&1
    printf '%s\n' "$dir/out.jar"
  }

  expect_rc() { # label, expected-rc, actual-rc
    if [ "$3" = "$2" ]; then
      echo "  ok   $1 (exit $3)"
    else
      echo "  FAIL $1 (exit $3, wanted $2)" >&2
      failures=$((failures + 1))
    fi
  }

  echo "self-test: the three branches plus the shapes that used to hide"
  jar="$(make_jar declared 'Manifest-Version: 1.0\r\nKeelBase-Contract-Version: v1.22.0\r\n\r\n')"
  bash "$ROOT/scripts/check-jar-contract-version.sh" --expect v1.22.0 "$jar" >/dev/null 2>&1; expect_rc "declared and matching" 0 $?

  jar="$(make_jar mismatch 'Manifest-Version: 1.0\r\nKeelBase-Contract-Version: v1.21.0\r\n\r\n')"
  bash "$ROOT/scripts/check-jar-contract-version.sh" --expect v1.22.0 "$jar" >/dev/null 2>&1; expect_rc "declared and different" 1 $?

  jar="$(make_jar undeclared 'Manifest-Version: 1.0\r\nImplementation-Version: 0.1.20\r\n\r\n')"
  bash "$ROOT/scripts/check-jar-contract-version.sh" --expect v1.22.0 "$jar" >/dev/null 2>&1; expect_rc "undeclared" 2 $?

  # The attribute after a wrapped line: a parser that reads only physical lines sees the tail of the
  # previous attribute and misses this one.
  #
  # 位于被折行的属性之后的那一行：只读物理行的解析器会读到上一条属性的尾巴，从而漏掉这一条。
  jar="$(make_jar wrapped 'Manifest-Version: 1.0\r\nImplementation-Description: a long value that Maven wraps at seventy-two bytes and continues on the next line\r\n with a leading space\r\nKeelBase-Contract-Version: v1.22.0\r\n\r\n')"
  bash "$ROOT/scripts/check-jar-contract-version.sh" --expect v1.22.0 "$jar" >/dev/null 2>&1; expect_rc "attribute after a continuation line" 0 $?

  jar="$(make_jar lowercase 'manifest-version: 1.0\r\nkeelbase-contract-version: v1.22.0\r\n\r\n')"
  bash "$ROOT/scripts/check-jar-contract-version.sh" --expect v1.22.0 "$jar" >/dev/null 2>&1; expect_rc "attribute name in another case" 0 $?

  mkdir -p "$tmp/empty-module/target"
  bash "$ROOT/scripts/check-jar-contract-version.sh" --expect v1.22.0 "$tmp/empty-module" >/dev/null 2>&1; expect_rc "module with no jar" 2 $?

  bash "$ROOT/scripts/check-jar-contract-version.sh" --expect '${keelbase.contract.version}' "$tmp/declared/out.jar" >/dev/null 2>&1
  expect_rc "an unexpanded placeholder as the expectation" 2 $?

  if [ "$failures" -gt 0 ]; then
    echo "self-test: $failures case(s) failed" >&2
    return 1
  fi
  echo "self-test: all cases passed"
}

EXPECT=""
EXPECT_GIVEN=0
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --expect)
      [ $# -ge 2 ] || { echo "--expect needs a version" >&2; exit 2; }
      EXPECT="$2"; EXPECT_GIVEN=1; shift ;;
    --expect=*) EXPECT="${1#--expect=}"; EXPECT_GIVEN=1 ;;
    --self-test) self_test; exit $? ;;
    -h | --help) usage; exit 0 ;;
    -*) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
    *) ARGS+=("$1") ;;
  esac
  shift
done

if [ "$EXPECT_GIVEN" = 0 ]; then
  EXPECT="$(cd "$ROOT" && bash scripts/check-pin-lag.sh --print-pin)"
  if [ -z "$EXPECT" ]; then
    echo "cannot read the contract pin (bash scripts/check-pin-lag.sh --print-pin)" >&2
    exit 2
  fi
fi
if [ -z "$EXPECT" ]; then
  echo "the expected contract version is empty" >&2
  exit 2
fi
case "$EXPECT" in
  *'$('* | *'${'* | *'}'*)
    echo "the expected value looks like an unexpanded placeholder: $EXPECT" >&2
    echo "pass the value itself, or leave --expect out to read this repository's pin" >&2
    exit 2 ;;
esac
if [ "${#ARGS[@]}" -eq 0 ]; then
  echo "nothing to check: pass at least one jar or module directory" >&2
  usage >&2
  exit 2
fi

JARS=()
for path in "${ARGS[@]}"; do
  if [ -f "$path" ]; then
    JARS+=("$path")
    continue
  fi
  if [ -d "$path" ]; then
    dir="$path"
    [ -d "$dir/target" ] && dir="$dir/target"
    found=0
    for candidate in "$dir"/*.jar; do
      [ -e "$candidate" ] || continue
      case "$(basename "$candidate")" in
        *-sources.jar | *-javadoc.jar | *-tests.jar) continue ;;
      esac
      JARS+=("$candidate"); found=1
    done
    if [ "$found" = 0 ]; then
      echo "no jar found for $path — the build this check reads has not run" >&2
      exit 2
    fi
    continue
  fi
  echo "not a jar or a module directory: $path" >&2
  exit 2
done

declared=0
mismatched=0
missing=0
for jar in "${JARS[@]}"; do
  value="$(manifest_attr "$jar" KeelBase-Contract-Version)"
  if [ -z "$value" ]; then
    echo "✗ $(basename "$jar"): no KeelBase-Contract-Version in META-INF/MANIFEST.MF (UNDECLARED)"
    missing=$((missing + 1))
  elif [ "$value" != "$EXPECT" ]; then
    echo "✗ $(basename "$jar"): carries $value, expected $EXPECT"
    mismatched=$((mismatched + 1))
  else
    echo "✓ $(basename "$jar"): KeelBase-Contract-Version: $value"
    declared=$((declared + 1))
  fi
done

if [ "$mismatched" -gt 0 ]; then
  echo "$mismatched of ${#JARS[@]} jar(s) name a contract this build was not given" >&2
  exit 1
fi
if [ "$missing" -gt 0 ]; then
  echo "$missing of ${#JARS[@]} jar(s) do not say which contract they carry" >&2
  echo "the build was not given -Dkeelbase.contract.version, or maven-jar-plugin did not receive it" >&2
  exit 2
fi
echo "all ${#JARS[@]} jar(s) declare KeelBase-Contract-Version: $EXPECT"
exit 0
