#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Does every javadoc block read English-first, Chinese-after — one block each?
#
# The house rule for anything written down is: English in a block, then Chinese in a block, and never a
# Chinese paragraph followed by an English one. That last shape is the one this checks for, because
# until now the rule was enforced by whoever happened to be reviewing — and a reviewer who knows the
# rule can still miss it. The interleaved version shipped twice in one session before anyone noticed.
#
#   bash scripts/check-bilingual-comments.sh            # report, always exit 0
#   bash scripts/check-bilingual-comments.sh --strict   # exit 1 when something is interleaved
#   bash scripts/check-bilingual-comments.sh --self-test
#
# Report-only by default, and that is deliberate: a comment block already in the tree is not this
# script's business to fail on — the rule does not rewrite what it did not touch. Turn on `--strict`
# when the existing offenders are cleaned up, or wire that flag into CI at that point.
#
# What it does not check: whether a block has both languages at all. Plenty of older blocks are
# English-only and are meant to stay that way, so flagging them would be noise that trains people to
# ignore the output.
#
# 每一条 javadoc 是否都是「英文整块在前、中文整块在后」？
#
# 本仓对一切写下来的文字有一条规矩：**英文成一块、中文成一块**，而且**绝不允许中文段后面再跟英文段**。最后那个形状
# 读起来像逐条对照翻译，也正是本脚本要查的东西——因为在此之前，这条规矩靠**恰好审到的人**把守，而一个知道规矩的
# 审查者**照样会漏**：那个交错的版本在同一个会话里先后发出去两次，都没人当场看出来。
#
#   bash scripts/check-bilingual-comments.sh            # 只报告，永远退出 0
#   bash scripts/check-bilingual-comments.sh --strict   # 有交错时退出 1
#   bash scripts/check-bilingual-comments.sh --self-test
#
# 默认只报告，这是有意的：**已经在树里的注释块不是这个脚本该判失败的**——规矩不回溯改写它没碰过的东西。等存量的
# 交错清干净了，再打开 `--strict`，或者到那时把该开关接进 CI。
#
# 不查的一件事：一个块**有没有**两种语言。大量较老的块只有英文，而且按规定就该保持原样，把它们列出来只会是噪声，
# 而噪声会训练人忽略输出。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# GNU awk where it exists, whatever `awk` is otherwise. The classification depends on byte-wise
# string comparison, which both implementations do under LC_ALL=C — and the self-test is what says
# so on a given machine, which is why the gate runs it before it runs the check.
AWK="$(command -v gawk || command -v awk)"

# Findings for the given files, one line each: path:line: what is wrong. The classification is done on
# bytes rather than characters on purpose — LC_ALL=C makes awk count bytes and compare single bytes, so
# no Unicode range and no locale are involved, which are the two things that make this kind of check
# behave differently on two machines.
#
# A CJK character starts with a lead byte in 0xE4..0xE9 (U+4E00..U+9FFF), its punctuation U+3000..303F
# with 0xE3, and the full-width forms U+FF00.. with 0xEF. All three count, and leaving the last two out
# is not a small mistake: a Chinese paragraph wrapped so one line ends on （`SomeClass`）。 carries no
# ideograph at all, reads as English, and makes the whole block look interleaved. That is how the first
# version of this script managed to accuse a file it had just been used to fix.
scan() {
  for f in "$@"; do
    LC_ALL=C "$AWK" -v file="$f" '
      function hasCJK(s,   i, c) {
        for (i = 1; i <= length(s); i++) {
          c = substr(s, i, 1)
          if ((c >= "\343" && c <= "\351") || c == "\357") return 1
        }
        return 0
      }
      # A paragraph ends at a blank comment line, and it is the paragraph that is English or Chinese —
      # not the line. Classifying line by line was the third mistake this script made, and the worst: an English
      # paragraph that quotes one Chinese sample ("当前客户「Acme」（ID 1）。…") flipped kind in the middle
      # of the paragraph and came out interleaved, so half the report was English prose quoting Chinese.
      # A paragraph counts as Chinese when most of its lines are, which survives both a quoted sample and
      # a Chinese line that carries nothing but full-width punctuation.
      function closeParagraph() {
        if (plines > 0) {
          kind = (pcjk * 2 > plines) ? "ZH" : "EN"
          sequence = sequence kind " "
          plines = 0; pcjk = 0
        }
      }
      function report(   n, parts, i, seenChinese, bad) {
        closeParagraph()
        if (sequence == "") return
        n = split(sequence, parts, " ")
        seenChinese = 0; bad = 0
        for (i = 1; i <= n; i++) {
          if (parts[i] == "ZH") seenChinese = 1
          else if (seenChinese) bad = 1
        }
        if (bad) printf "%s:%d: a Chinese paragraph is followed by an English one\n", file, start
      }
      /^[[:space:]]*\/\*\*/ { inside = 1; start = NR; sequence = ""; plines = 0; pcjk = 0; next }
      inside && /\*\/[[:space:]]*$/ { report(); inside = 0; next }
      inside {
        line = $0
        sub(/^[[:space:]]*\*[[:space:]]?/, "", line)
        if (line ~ /^[[:space:]]*$/) { closeParagraph(); next }
        plines++
        if (hasCJK(line)) pcjk++
        next
      }
    ' "$f"
  done
}

# The checker is worth exactly as much as its own correctness, and reading a list of findings cannot
# tell you whether they are right — which is how a version of this script came to accuse fifteen files,
# including one it had just been used to fix. So it carries the two samples that would have caught
# that: a correct block whose Chinese paragraph wraps onto a line of nothing but full-width punctuation,
# and a block with the shape it is looking for. A checker with no test is a checker nobody should trust.
self_test() {
  local tmp good bad failures=0
  tmp="$(mktemp -d)"
  cat > "$tmp/Good.java" <<'JAVA'
/**
 * An English paragraph, and one that is only English.
 *
 * 一个中文段落，它换行之后落在了只有全角标点的一行上（`SomeClass`）。
 */
JAVA
  cat > "$tmp/Bad.java" <<'JAVA'
/**
 * An English paragraph.
 *
 * 一个中文段落。
 *
 * And English again, which is the shape this looks for.
 */
JAVA
# An English paragraph quoting a Chinese sample is English. This one is here because the script did not
# know that: it classified line by line, so the quoted sample flipped the paragraph and the report was
# half full of English prose quoting Chinese.
  cat > "$tmp/Quoted.java" <<'JAVA'
/**
 * The console names the customer it is looking at inside the message ("当前客户「Acme」（ID 1）。…"),
 * which is what a model would read, and the runtime resolves the reference either way.
 */
JAVA
  good="$(scan "$tmp/Good.java" | grep -c . || true)"
  quoted="$(scan "$tmp/Quoted.java" | grep -c . || true)"
  bad="$(scan "$tmp/Bad.java" | grep -c . || true)"
  rm -rf "$tmp"

  if [ "$bad" = "1" ]; then
    echo "  ok    an interleaved block is reported"
  else
    echo "  FAIL  an interleaved block was not reported (got $bad)"; failures=$((failures + 1))
  fi
  if [ "$good" = "0" ]; then
    echo "  ok    a correct block is not reported, full-width punctuation and all"
  else
    echo "  FAIL  a correct block was reported ($good finding(s))"; failures=$((failures + 1))
  fi
  if [ "$quoted" = "0" ]; then
    echo "  ok    an English paragraph quoting Chinese is not reported"
  else
    echo "  FAIL  English prose quoting a Chinese sample was reported ($quoted finding(s))"; failures=$((failures + 1))
  fi
  return "$failures"
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
esac

STRICT=0
for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=1 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

FILES="$(git ls-files '*.java')"
FILE_COUNT="$(printf '%s\n' "$FILES" | grep -c .)"

REPORT="$(scan $FILES)"
COUNT="$(printf '%s\n' "$REPORT" | grep -c . || true)"
[ -z "$REPORT" ] && COUNT=0

if [ "$COUNT" -gt 0 ]; then
  printf '%s\n' "$REPORT"
  echo
fi
echo "bilingual comment shape: $COUNT interleaved block(s) across $FILE_COUNT Java file(s)"

if [ "$STRICT" -eq 1 ] && [ "$COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
