#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Does every comment block read English-first, Chinese-after — one block each?
#
# The house rule for anything written down is: English in a block, then Chinese in a block, and never a
# Chinese line followed by an English one. That last shape is the one this checks for, because until
# now the rule was enforced by whoever happened to be reviewing — and a reviewer who knows the rule can
# still miss it. The interleaved version shipped twice in one session before anyone noticed.
#
# It reads javadoc blocks, ordinary /* */ blocks, and runs of // lines. A comment line counts as
# English when it is prose: at least two Latin words, and more of them than CJK characters. Anything
# else that carries a CJK character counts as Chinese. Inline code is not prose, so backticked
# identifiers, {@code ...} spans and HTML tags are taken out before either count is made; indented
# code samples, table rows and block tags such as @param count as neither, and a line of neither kind
# neither joins nor breaks a paragraph. The comparison is what keeps both mistakes out — an English
# sentence that quotes a Chinese phrase is still English, and a Chinese sentence whose bulk is one
# long English identifier (`V6__audit_row_is_error`。) is still Chinese.
#
#   bash scripts/check-bilingual-comments.sh            # report, always exit 0
#   bash scripts/check-bilingual-comments.sh --strict   # exit 1 when English follows Chinese
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
# 每一条注释是否都是「英文整块在前、中文整块在后」？
#
# 本仓对一切写下来的文字有一条规矩：**英文成一块、中文成一块**，而且**绝不允许中文行后面再跟英文行**。最后那个
# 形状读起来像逐条对照翻译，也正是本脚本要查的东西——因为在此之前，这条规矩靠**恰好审到的人**把守，而一个知道
# 规矩的审查者**照样会漏**：那个交错的版本在同一个会话里先后发出去两次，都没人当场看出来。
#
# 它读 javadoc 块、普通 /* */ 块，以及连续的 // 行。一条注释行要算**英文行**，得是**散文**：至少两个拉丁词，且
# 词数**多于**汉字数；其余**含汉字/全角标点的行**算**中文行**。内联代码不是散文，故先去掉反引号标识符、`{@code …}`
# 片段与 HTML 标签再计数；缩进的代码样例、表格行、`@param` 这类块标签两者都不算，两边都不算的行既不开始、也不打断
# 一个段落。这个比较把两种误判都挡住了：**英文句子里引用一个中文短语**仍是英文，而**主体是一个长英文标识符的中文行**
# （`V6__audit_row_is_error`。）仍是中文。
#
#   bash scripts/check-bilingual-comments.sh            # 只报告，永远退出 0
#   bash scripts/check-bilingual-comments.sh --strict   # 中文之后又出现英文时退出 1
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
      # Inline code is not prose: a backticked identifier, a {@code ...} or {@link ...} span, and an
      # HTML tag are stripped before either count is taken. Without this, an English line that is
      # really one long {@code} list (`{@code executed | pending_confirmation | ...}`) reads as English
      # prose, and a Chinese line whose middle is an identifier reads the same way. A span that is not
      # closed on the line continues onto the next one, so the rest of this line is code too.
      function stripInline(s,   p) {
        gsub(/\{[@][a-zA-Z]+[^}]*\}/, " ", s)
        p = index(s, "{@"); if (p > 0) s = substr(s, 1, p - 1)
        gsub(/`[^`]*`/, " ", s)
        p = index(s, "`"); if (p > 0) s = substr(s, 1, p - 1)
        gsub(/<[^>]*>/, " ", s)
        return s
      }
      # A quotation is a citation, not the language of the line. Text between ASCII double quotes is
      # dropped before the line is weighed, and the quote state carries across lines of one block —
      # the samples in these comments are whole sentences, and one of them wraps. Chinese quotation
      # marks are left alone: they are how the Chinese prose here quotes itself.
      function dequote(s,   out, i, n, c) {
        out = ""
        n = length(s)
        for (i = 1; i <= n; i++) {
          c = substr(s, i, 1)
          if (c == "\"") { quoted = 1 - quoted; out = out " "; continue }
          if (!quoted) out = out c
        }
        return out
      }
      # CJK characters, counted at their lead byte.
      function cjkCount(s,   i, c, n) {
        n = 0
        for (i = 1; i <= length(s); i++) {
          c = substr(s, i, 1)
          if ((c >= "\343" && c <= "\351") || c == "\357") n++
        }
        return n
      }
      # Words are whitespace-separated tokens that carry a Latin letter. Splitting inside an
      # identifier would count `V6__audit_row_is_error` as five words, which is the mistake that made
      # an identifier-dominated Chinese line read as English.
      function latinWords(s,   n, i, parts, c) {
        gsub(/\t/, " ", s)
        n = split(s, parts, " ")
        c = 0
        for (i = 1; i <= n; i++) if (parts[i] ~ /[A-Za-z]/) c++
        return c
      }
      function resetBlock() { zhSeen = 0; reported = 0; pre = 0; quoted = 0 }
      function startBlock() { inBlock = 0; inLine = 0; resetBlock() }
      function endBlock() { startBlock() }
      # One content line of a comment. Lines that are neither Chinese nor English change nothing:
      # they must not start a paragraph, and they must not end the one before them either.
      #
      # Prose is English when it has two Latin words or more and more words than CJK characters. The
      # comparison is what keeps both mistakes out: an English sentence that quotes a Chinese phrase
      # ("再建一条", "服务器内部错误") is still English, while a Chinese sentence that names a long
      # English identifier is still Chinese. Everything else with a CJK character is Chinese.
      function feed(line,   h, w) {
        if (line ~ /<\/pre>/) { if (pre > 0) pre--; return }
        if (line ~ /<pre[ >]/) { pre++; return }
        if (pre > 0) return
        if (line ~ /^[[:space:]]*$/) return
        if (line ~ /^[[:space:]]/) return
        if (line ~ /^[[:space:]]*\|/) return
        if (line ~ /^[[:space:]]*@/) return
        line = dequote(stripInline(line))
        h = cjkCount(line); w = latinWords(line)
        if (w >= 2 && w > h) {
          if (zhSeen && !reported) {
            printf "%s:%d: an English line follows a Chinese one\n", file, NR
            reported = 1
          }
          return
        }
        if (h > 0) zhSeen = 1
      }
      /^[[:space:]]*\/\*\*/ {
        line = $0
        sub(/^[[:space:]]*\/\*\*[[:space:]]?/, "", line)
        startBlock()
        if (line ~ /\*\/[[:space:]]*$/) {
          sub(/\*\/[[:space:]]*$/, "", line)
          feed(line); endBlock()
        } else {
          inBlock = 1
          feed(line)
        }
        next
      }
      /^[[:space:]]*\/\*/ {
        line = $0
        sub(/^[[:space:]]*\/\*[[:space:]]?/, "", line)
        startBlock()
        if (line ~ /\*\/[[:space:]]*$/) {
          sub(/\*\/[[:space:]]*$/, "", line)
          feed(line); endBlock()
        } else {
          inBlock = 1
          feed(line)
        }
        next
      }
      inBlock && /\*\/[[:space:]]*$/ {
        line = $0
        sub(/\*\/[[:space:]]*$/, "", line)
        sub(/^[[:space:]]*\*[[:space:]]?/, "", line)
        feed(line)
        endBlock()
        next
      }
      inBlock {
        line = $0
        if (line ~ /^[[:space:]]*\*/) sub(/^[[:space:]]*\*[[:space:]]?/, "", line)
        else sub(/^[[:space:]]*/, "", line)
        feed(line)
        next
      }
      /^[[:space:]]*\/\// {
        if (!inLine) { startBlock(); inLine = 1 }
        line = $0
        sub(/^[[:space:]]*\/\/[[:space:]]?/, "", line)
        feed(line)
        next
      }
      { if (inLine) endBlock() }
      END { endBlock() }
    ' "$f"
  done
}

# The checker is worth exactly as much as its own correctness, and reading a list of findings cannot
# tell you whether they are right — which is how a version of this script came to accuse fifteen files,
# including one it had just been used to fix. So it carries a sample of every shape it must and must
# not report: a correct javadoc block, a javadoc block in the wrong order, the same wrong order in a
# run of // lines with the Chinese line dominated by an identifier, an ordinary /* */ block, a Chinese
# line followed by an indented code sample, an English sentence that quotes Chinese, and a Chinese
# paragraph whose first line is one long {@code} list.
self_test() {
  local tmp failures=0
  tmp="$(mktemp -d)"
  cat > "$tmp/JavadocGood.java" <<'JAVA'
/**
 * An English paragraph, and one that is only English.
 *
 * 一个中文段落，它换行之后落在了只有全角标点的一行上（`SomeClass`）。
 */
JAVA
  cat > "$tmp/JavadocBad.java" <<'JAVA'
/**
 * An English paragraph.
 *
 * 一个中文段落。
 *
 * And English again, which is the shape this looks for.
 */
JAVA
  cat > "$tmp/LineBad.java" <<'JAVA'
class LineBad {
    // 本行以标识符为主：`V6__audit_row_is_error`。
    // See that row for why the column is not part of the hashed payload.
}
JAVA
  cat > "$tmp/BlockBad.java" <<'JAVA'
class BlockBad {
    /*
     * 中文在前。
     * And English after it.
     */
}
JAVA
  cat > "$tmp/SampleGood.java" <<'JAVA'
class SampleGood {
    // 用法如下：
    //     put("说明", value);
    //     trace(id);
}
JAVA
  cat > "$tmp/QuoteGood.java" <<'JAVA'
/**
 * The console names it once — in the first message ("当前客户「Acme」（ID 1）。给客户建一条
 * 跟进记录"), because no client sends a customer id as a field. A model reads that reference
 * wherever it appears.
 */
JAVA
  cat > "$tmp/CodeSpanGood.java" <<'JAVA'
/**
 * <p>The status comes from {@code status}:
 * {@code executed | pending_confirmation | requires_approval | blocked | declined |
 * invalid_arguments | error}。这一段的中文从这里开始，并接着写下去。
 */
JAVA
  local jgood jbad lbad bbad sgood qgood cgood
  jgood="$(scan "$tmp/JavadocGood.java" | grep -c . || true)"
  jbad="$(scan "$tmp/JavadocBad.java" | grep -c . || true)"
  lbad="$(scan "$tmp/LineBad.java" | grep -c . || true)"
  bbad="$(scan "$tmp/BlockBad.java" | grep -c . || true)"
  sgood="$(scan "$tmp/SampleGood.java" | grep -c . || true)"
  qgood="$(scan "$tmp/QuoteGood.java" | grep -c . || true)"
  cgood="$(scan "$tmp/CodeSpanGood.java" | grep -c . || true)"
  rm -rf "$tmp"

  if [ "$jbad" = "1" ]; then
    echo "  ok    a javadoc block with English after Chinese is reported"
  else
    echo "  FAIL  a javadoc block with English after Chinese was not reported (got $jbad)"; failures=$((failures + 1))
  fi
  if [ "$lbad" = "1" ]; then
    echo "  ok    a // block is read, and an identifier-heavy Chinese line counts as Chinese"
  else
    echo "  FAIL  a // block with English after an identifier-heavy Chinese line was not reported (got $lbad)"; failures=$((failures + 1))
  fi
  if [ "$bbad" = "1" ]; then
    echo "  ok    an ordinary /* */ block is read"
  else
    echo "  FAIL  an ordinary /* */ block was not read (got $bbad)"; failures=$((failures + 1))
  fi
  if [ "$jgood" = "0" ]; then
    echo "  ok    English before Chinese is not reported, full-width punctuation and all"
  else
    echo "  FAIL  a correct block was reported ($jgood finding(s))"; failures=$((failures + 1))
  fi
  if [ "$sgood" = "0" ]; then
    echo "  ok    an indented code sample is neither English nor Chinese"
  else
    echo "  FAIL  an indented code sample was read as prose ($sgood finding(s))"; failures=$((failures + 1))
  fi
  if [ "$qgood" = "0" ]; then
    echo "  ok    an English sentence quoting Chinese is still English"
  else
    echo "  FAIL  an English sentence quoting Chinese was reported ($qgood finding(s))"; failures=$((failures + 1))
  fi
  if [ "$cgood" = "0" ]; then
    echo "  ok    a {@code} list is not English prose"
  else
    echo "  FAIL  a {@code} list flipped a Chinese paragraph to interleaved ($cgood finding(s))"; failures=$((failures + 1))
  fi
  return "$failures"
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  -h|--help) sed -n '2,52p' "$0"; exit 0 ;;
esac

STRICT=0
for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=1 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# Tracked files, minus those the working tree no longer has. A deletion that has not been committed
# yet is still in the index, and the scanner was handed a path it could not open — after which it
# reported zero, which reads exactly like a clean pass. That is the one answer a gate must never give
# by accident, so the list is filtered down to what is actually there.
#
# 已在版本控制里的文件，**减去工作树已经没有的那些**。一次尚未提交的删除仍留在索引里，扫描器于是拿到一个
# 打不开的路径 —— 而它随后报了「零」，**读起来与一次干净通过一模一样**。那正是门禁**绝不能*意外*给出的**
# 那个答案，所以这份清单按「实际还在不在」过滤。
FILES="$(git ls-files '*.java' | while IFS= read -r f; do [ -f "$f" ] && printf '%s\n' "$f"; done)"
FILE_COUNT="$(printf '%s\n' "$FILES" | grep -c .)"

REPORT="$(scan $FILES)"
COUNT="$(printf '%s\n' "$REPORT" | grep -c . || true)"
[ -z "$REPORT" ] && COUNT=0

if [ "$COUNT" -gt 0 ]; then
  printf '%s\n' "$REPORT"
  echo
fi
echo "bilingual comment shape: $COUNT block(s) with English after Chinese, across $FILE_COUNT Java file(s)"

if [ "$STRICT" -eq 1 ] && [ "$COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
