#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# KeelBase4J Release Gate — one command that proves the things this repository claims about itself.
#
# The claims are the hard rules in CLAUDE.md, plus what a release has to build:
#   1. the frozen contract's vectors are still reproduced by *this* implementation (mvn verify);
#   2. the vendored snapshot still matches the two sources it is refreshed from — the contract at the
#      version this repository answers for, and the main repository's scenario packs;
#   3. the generated application is real, independent, runnable source: generate → build → run → walk the
#      trust loop against it, and the same again for a change and for a migration;
#   4. every javadoc block reads English and then Chinese;
#   5. the publish set still builds the way a release builds it — sources and javadoc included — and is
#      still exactly four modules.
#
# Two things are deliberately out of the default run, each for a reason rather than for time:
#   * `demo-golden-path.sh` needs the main repository's Web-Admin-Vue checkout. A gate that depends on a
#     neighbouring repository's working tree would fail on a machine that legitimately does not have one,
#     and a gate that fails for a reason unrelated to this repository is a gate people learn to ignore.
#   * the adapter demos (`demo-springai*.sh`) need a model key. They sit behind `LLM_ENV=1`, the way the
#     main repository's gate treats its own LLM dimension — annotated when off, executed when on.
#
# The contract pin is *read* from `.github/workflows/ci.yml` rather than written here a third time: a
# hand-copied version rots the moment the workflows move, and this repository has already paid for that
# lesson once. `release.yml` is read too, and a disagreement between the two is a failure.
#
#   bash scripts/release-gate.sh            # deterministic gate (CI-able)
#   LLM_ENV=1 bash scripts/release-gate.sh  # + the adapter demos (needs a model key)
#
# Invoked through `bash` for the reason the demo scripts are: the executable bit cannot be committed
# from this repository's development platform, so the index carries mode 100644.
#
# Output: one PASS/FAIL per dimension, then a single summary line; exit 0 only when nothing failed.
#
# —— 中文 ——
#
# KeelBase4J 的发布门禁：一条命令证明本仓对自己声称的每件事仍然成立。
#
# 被证明的是 CLAUDE.md 里那几条硬规则，外加一次发布必须构建得出来的东西：
#   1. 冻结契约的向量仍被**本实现**复现（`mvn verify`）；
#   2. vendored 快照仍与它的两个来源一致 —— 本仓背书那个版本的契约，以及主仓的场景包；
#   3. 生成物是真实、可独立运行的源码：生成 → 构建 → 独立运行 → 对它走信任闭环，变更与迁移各再来一遍；
#   4. 每个 javadoc 块英文在前、中文在后；
#   5. 发布集合仍按发布的方式构建得出来（含 sources 与 javadoc），且仍恰为四个模块。
#
# 有两件事**有意**不在默认跑，理由各有其理、不是省时间：
#   * `demo-golden-path.sh` 需要主仓的 Web-Admin-Vue 检出。门禁若依赖相邻仓库的工作树，就会在本就没有它
#     的机器上失败 —— 而一个因与本仓无关的原因变红的门禁，是会被人们学会忽略的那种门禁。
#   * 适配器 demo（`demo-springai*.sh`）需要模型 key。它们放在 `LLM_ENV=1` 之后，与主仓门禁对待自己那条
#     LLM 维度同款：关时标注、开时执行。
#
# 契约的 pin 是**读**自 `.github/workflows/ci.yml`，不在这里写第三份：手抄的版本会在工作流变动的那一刻烂掉，
# 而本仓已经为这条教训付过一次账。`release.yml` 也读，两者不一致即判失败。
#
#   bash scripts/release-gate.sh            # 确定性门禁（可进 CI）
#   LLM_ENV=1 bash scripts/release-gate.sh  # + 适配器 demo（需模型 key）
#
# 用 `bash` 调用，理由与那些 demo 脚本相同：本仓的开发平台上可执行位提交不进去，索引里是 100644。
#
# 输出：每维一行 PASS/FAIL，末尾一行汇总；只有全不失败才退 0。

set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

PASS=0; FAIL=0
gate() { # name ok detail
  local name="$1"; local ok="$2"; local detail="${3:-}"
  if [ "$ok" = "pass" ]; then echo "  ✓ $name  PASS"; PASS=$((PASS+1));
  else echo "  ✗ $name  FAIL${detail:+ — $detail}"; FAIL=$((FAIL+1)); fi
}

# A demo's verdict is its own summary line, not its exit status alone: a script that dies mid-run also
# exits non-zero, and calling that a failed assertion sends the reader after the wrong thing.
# 一个 demo 的判据是它**自己的汇总行**，不只是退出码：中途崩掉的脚本同样退非零，把那读成「断言失败」会
# 把读者引向错误的方向。
run_demo() { # name script
  local name="$1" script="$2" out attempt
  for attempt in 1 2; do
    # Through `bash`, not by path: this repository is developed on Windows, where `core.filemode` is
    # false, so the executable bit cannot be committed — `scripts/demo-*.sh` are mode 100644 in the
    # index, and a fresh Linux checkout would answer `./scripts/demo-*.sh` with "Permission denied".
    # 走 `bash` 而不按路径：本仓在 Windows 上开发，`core.filemode` 为 false ⇒ 可执行位提交不进去
    # （`scripts/demo-*.sh` 在索引里是 100644），新检出的 Linux 上 `./scripts/demo-*.sh` 会被拒。
    out=$(bash "$script" 2>&1) || true
    if grep -qE '^PASS' <<<"$out"; then gate "$name" pass; return; fi
    if grep -qE '^FAIL' <<<"$out"; then
      gate "$name" fail "$script"
      echo "── 明细 — $script 输出尾部 ──"; printf '%s\n' "$out" | tail -n 40; echo "── /明细 ──"
      return
    fi
    if [ "$attempt" = 1 ]; then echo "  ⚠ $name 未产出汇总行（脚本未跑完，非断言失败）——重试 1/1"; fi
  done
  gate "$name" fail "$script（两次都没跑到汇总行——是中断，不是断言失败）"
  echo "── 明细 — $script 输出尾部 ──"; printf '%s\n' "$out" | tail -n 40; echo "── /明细 ──"
}

# The pin the consumer answers for, read out of a workflow rather than restated here.
# 本消费者背书的那条 pin —— 从工作流里读出来，而不是在这里再述一遍。
pin_of() { # workflow-file
  awk '/repository: rain6fish\/keelbase-contract/ { f = 1 } f && /^[[:space:]]*ref:/ { sub(/^[[:space:]]*ref:[[:space:]]*/, ""); print; exit }' "$1"
}

echo "═══ KeelBase4J Release Gate ═══"
echo "模式：$([ "${LLM_ENV:-}" = "1" ] && echo 'LLM 全量（需模型 key）' || echo '确定性（可 CI）')"
echo ""

# ── Conformance：本实现仍复现冻结契约的向量 ────────────────────────────────────
echo "→ [Conformance] 冻结契约的向量仍被本实现复现（mvn verify）"
CONF_OUT=$(mvn -B -ntp verify 2>&1) || true
if grep -q "BUILD SUCCESS" <<<"$CONF_OUT"; then
  gate "Conformance(契约向量 + 生成器 + 运行时 + 适配器)" pass
else
  gate "Conformance(契约向量 + 生成器 + 运行时 + 适配器)" fail "mvn verify"
  echo "── 明细 — mvn verify 输出尾部 ──"; printf '%s\n' "$CONF_OUT" | tail -n 40; echo "── /明细 ──"
fi

# ── Drift：vendored 快照仍等于它的两个来源 ─────────────────────────────────────
echo "→ [Drift] vendored 快照 == 契约@pin + 主仓场景包"
PIN_CI=$(pin_of .github/workflows/ci.yml)
PIN_REL=$(pin_of .github/workflows/release.yml)
CONTRACT_DIR="${CONTRACT_DIR:-$ROOT/../keelbase-contract}"
MAIN_REPO_DIR="${MAIN_REPO_DIR:-$ROOT/../KeelBase}"
if [ -z "$PIN_CI" ]; then
  gate "Drift(快照与来源一致)" fail "读不出 ci.yml 里的契约 pin"
elif [ "$PIN_CI" != "$PIN_REL" ]; then
  gate "Drift(快照与来源一致)" fail "两处 pin 不一致：ci.yml=$PIN_CI，release.yml=$PIN_REL"
elif [ ! -d "$CONTRACT_DIR" ]; then
  gate "Drift(快照与来源一致)" fail "找不到契约克隆 $CONTRACT_DIR —— 设 CONTRACT_DIR 指过去"
elif [ ! -d "$MAIN_REPO_DIR/Server-NestJS/specs/scenarios" ]; then
  gate "Drift(快照与来源一致)" fail "找不到主仓场景包 $MAIN_REPO_DIR —— 设 MAIN_REPO_DIR 指过去"
else
  # The pin, not the clone's checkout: a working clone will usually sit on a newer contract version, and
  # checking against that reports this repository's snapshot as drifted when nothing here moved.
  # 核的是 **pin**，不是那个克隆当前检出：开发中的克隆通常停在更新的契约版本上，拿它去核会把本仓的快照
  # 报成漂移，而本仓什么都没动。
  PIN_TMP=$(mktemp -d)
  trap 'rm -rf "${PIN_TMP:-}"' EXIT
  if git -C "$CONTRACT_DIR" archive "$PIN_CI" 2>/dev/null | tar -x -C "$PIN_TMP" 2>/dev/null; then
    DRIFT_OUT=$(scripts/sync-vectors.sh --check --contract "$PIN_TMP" --main "$MAIN_REPO_DIR" 2>&1) || true
    if grep -q "vendored snapshot matches its sources" <<<"$DRIFT_OUT"; then
      gate "Drift(快照与来源一致 @ $PIN_CI)" pass
    else
      gate "Drift(快照与来源一致 @ $PIN_CI)" fail "sync-vectors.sh --check 报漂移"
      echo "── 明细 — sync-vectors 输出尾部 ──"; printf '%s\n' "$DRIFT_OUT" | tail -n 40; echo "── /明细 ──"
    fi
  else
    gate "Drift(快照与来源一致 @ $PIN_CI)" fail "契约克隆里取不到 $PIN_CI（先 git fetch --tags）"
  fi
fi

# ── Comments：注释的双语形状 ──────────────────────────────────────────────────
echo "→ [Comments] javadoc 块英文在前、中文在后"
# 自检先跑：它说的正是「本机的分类对不对」。门禁依赖逐字节比较，解释器行为不同的机器应当**以自身失败**，
# 而不是报一个干净的零。
if bash scripts/check-bilingual-comments.sh --self-test >/dev/null 2>&1; then
  gate "Comments(解释器自检)" pass
else
  gate "Comments(解释器自检)" fail "check-bilingual-comments --self-test"
fi
if COMMENTS_OUT=$(bash scripts/check-bilingual-comments.sh --strict 2>&1); then
  gate "Comments(双语形状)" pass
else
  gate "Comments(双语形状)" fail "check-bilingual-comments --strict"
  echo "── 明细 — 注释门禁输出尾部 ──"; printf '%s\n' "$COMMENTS_OUT" | tail -n 40; echo "── /明细 ──"
fi

# ── Generated：生成物是真源码，且真能跑 ───────────────────────────────────────
echo "→ [Generated] 生成物：生成 → 构建 → 独立运行 → 信任闭环"
run_demo "Generated(生成物可独立运行 + 信任闭环 + 行级范围)" ./scripts/demo-generated-app.sh
echo "→ [Generated] 变更：再生成 → 手改存活 → 新规则生效"
run_demo "Generated(变更后手改存活 + 新规则生效)" ./scripts/demo-changeability.sh
echo "→ [Generated] 迁移：加性变更 → 存量数据跟着走"
run_demo "Generated(迁移带着存量数据走)" ./scripts/demo-migration.sh

# ── Release：发布集合仍按发布的方式构建 ──────────────────────────────────────
echo "→ [Release] 发布集合（release profile，含 sources 与 javadoc）"
if mvn -B -ntp -DskipTests install -pl keelbase4j-runtime -am >/dev/null 2>&1; then
  gate "Release(装 runtime —— 发布集合构建时要解析它，但按名字把它排除在外)" pass
else
  gate "Release(装 runtime)" fail "mvn install -pl keelbase4j-runtime -am"
fi
REL_OUT=$(mvn -B -ntp -Prelease -DskipTests -pl 'keelbase4j-springai,!keelbase4j-runtime' -am package 2>&1) || true
if grep -q "BUILD SUCCESS" <<<"$REL_OUT"; then
  gate "Release(publish set 在 release profile 下构建，sources + javadoc)" pass
else
  gate "Release(publish set 构建)" fail "mvn -Prelease … package"
  echo "── 明细 — 输出尾部 ──"; printf '%s\n' "$REL_OUT" | tail -n 40; echo "── /明细 ──"
fi
# 与 release.yml 断言的同一个数：发布集合恰为四个模块（父 pom · protocol · core · springai）。
REACTOR=$(mvn -B -ntp -Prelease -DskipTests -pl 'keelbase4j-springai,!keelbase4j-runtime' -am validate 2>/dev/null | grep -cE "^\[INFO\] Building " || true)
if [ "$REACTOR" = "4" ]; then
  gate "Release(发布集合仍恰为四个模块)" pass
else
  gate "Release(发布集合仍恰为四个模块)" fail "reactor 里有 $REACTOR 个模块，期望 4（父 pom · protocol · core · springai）"
fi

# ── Run / Adversarial：LLM 部分（需 LLM_ENV=1）────────────────────────────────
if [ "${LLM_ENV:-}" = "1" ]; then
  echo "→ [Run/Adversarial] 适配器在真模型上（需模型 key）"
  run_demo "Run/Adversarial(真模型上接缝)" ./scripts/demo-springai.sh
  run_demo "Run/Adversarial(真模型驱动框架自己的多步循环)" ./scripts/demo-springai-task.sh
else
  echo "→ [Run/Adversarial] LLM 部分标注（LLM_ENV=1 时跑 demo-springai.sh + demo-springai-task.sh）"
fi

echo ""
echo "═══ Release Gate: $([ $FAIL -eq 0 ] && echo 'PASS' || echo 'FAIL')（${PASS} pass / ${FAIL} fail）═══"
[ $FAIL -eq 0 ]
