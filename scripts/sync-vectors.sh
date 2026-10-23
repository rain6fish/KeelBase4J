#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Refresh, verify, or move the pin of the vendored contract snapshot.
#
# conformance/vectors/ is a READ-ONLY snapshot of
#   <contract-repo>/                              → conformance/vectors/
#   <main-repo>/Server-NestJS/specs/scenarios/    → conformance/vectors/scenarios/
# The protocol half comes from the contract repository — that is where the contract lives, and it is
# what this repository is a consumer of. The scenario half comes from the main repository: scenario
# packs are not part of the contract, so they are still taken from where they live.
# Never hand-edit the vendored copies — run this instead.
#
#   scripts/sync-vectors.sh [--check] [--contract DIR] [--main DIR]     # refresh (copy)
#   scripts/sync-vectors.sh --check [--contract DIR] [--main DIR]       # verify only; exit 1 on drift
#   scripts/sync-vectors.sh --bump <tag> [--contract DIR] [--main DIR]  # move the pin, in one step
#   CONTRACT_DIR=/path/to/keelbase-contract MAIN_REPO_DIR=/path/to/KeelBase scripts/sync-vectors.sh
#
# --bump <tag> changes four things, all inside this repository:
#   1. the contract `ref:` in `.github/workflows/ci.yml`;
#   2. the contract `ref:` in `.github/workflows/release.yml` — the release gate refuses to run when
#      the two disagree, so they always move together;
#   3. the pin statement in `conformance/vectors/README.md`;
#   4. the vendored snapshot, re-copied from <tag> — exported from the contract clone with
#      `git archive`, so the clone's checkout and working tree are left alone, and the snapshot comes
#      from the tagged commit even if the clone sits on something newer.
# It then runs `--check` over the result: a bump that does not verify exits 1 instead of reporting
# success. It does not commit, push, or create tags, and it never modifies the contract repository.
# Bumping to the tag already pinned is not an error: the snapshot is still re-synced and re-verified,
# and the script says it was already pinned.
# <tag> must look like a contract release tag (vX.Y.Z) and must exist as a tag in the contract clone.
#
# The vendored set is the contract the Java tests read, not just the vectors:
#   *-vector.json                    the language-neutral conformance vectors
#   wire-schema-registry*.json       the wire-object registry family (id → schema file); the Java
#                                    tests read v2 (ADR-0007 §3 Q10), and the family is picked up
#                                    whole so a future vN needs no change here either
#   schemas/**/*.json                the schemas the registry points at
#   scenarios/*.json                 the behaviour-level scenario packs (their `replay` is the
#                                    Extended-layer neutral-replay corpus the runtime replays)
# Every file is picked up dynamically (a new vector, schema or scenario pack in the main repo needs
# no change here); anything present here but absent upstream is reported as "extra". Content is
# compared and written normalised to LF, so the snapshot stays byte-stable. CI runs this with
# --check to gate drift.
#
# ── 中文 ──
# 刷新、校验，或一步移动 vendored 契约快照的 pin。
#
# conformance/vectors/ 是下列来源的**只读**快照：
#   <契约仓>/                                     → conformance/vectors/
#   <主仓>/Server-NestJS/specs/scenarios/         → conformance/vectors/scenarios/
# 协议那一半来自契约仓——契约就在那里，而本仓是它的消费方。场景那一半来自主仓：场景包不属于契约，
# 所以仍从它们所在的地方取。**永远不要手改** vendored 副本——跑这个脚本。
#
#   scripts/sync-vectors.sh [--check] [--contract DIR] [--main DIR]     # 刷新（复制）
#   scripts/sync-vectors.sh --check [--contract DIR] [--main DIR]       # 只校验；漂移时退出 1
#   scripts/sync-vectors.sh --bump <tag> [--contract DIR] [--main DIR]  # 一步移动 pin
#   CONTRACT_DIR=/path/to/keelbase-contract MAIN_REPO_DIR=/path/to/KeelBase scripts/sync-vectors.sh
#
# `--bump <tag>` 改四样东西，全在本仓内：
#   1. `.github/workflows/ci.yml` 里的契约 `ref:`；
#   2. `.github/workflows/release.yml` 里同一个 `ref:`——发布门禁拒绝两者不一致，所以必须一起动；
#   3. `conformance/vectors/README.md` 里陈述 pin 的那句话；
#   4. vendored 快照本身，从 <tag> 重新复制——用 `git archive` 从契约克隆导出，故克隆的检出与
#      工作树都不会被碰到；即使克隆停在更新的提交上，快照也仍取自该 tag 的提交。
# 随后对结果跑 `--check`：校验不通过的一次 bump 会退出 1，而不是报成功。它不提交、不推送、不打
# tag，也从不修改契约仓。bump 到已经钉着的 tag 不算错：快照照样重新同步并重新校验，脚本会说明
# 「pin 本来就在该 tag 上」。
# `<tag>` 必须形如契约发布 tag（vX.Y.Z），且在契约克隆里确实存在。
#
# vendored 集合是 Java 测试所读的那份契约，不只是向量：
#   *-vector.json                    语言无关的一致性向量
#   wire-schema-registry*.json       wire 对象注册表家族（id → schema 文件）；Java 测试读 v2
#                                    （ADR-0007 §3 Q10），整个家族一并收进来，将来的 vN 也无需改这里
#   schemas/**/*.json                注册表指向的 schema
#   scenarios/*.json                 行为级场景包（其 `replay` 就是运行时回放的 Extended 层中性重放语料）
# 每个文件都动态收录（主仓新增向量、schema 或场景包，这里无需改动）；本仓有而上游没有的报为
# "extra"。内容按 LF 归一化后比较与写入，快照因此保持字节稳定。CI 以 --check 跑它来拦漂移。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
DEST="$ROOT/conformance/vectors"
CI_WF="$ROOT/.github/workflows/ci.yml"
REL_WF="$ROOT/.github/workflows/release.yml"
VECTORS_README="$DEST/README.md"

CLEANUP_PATHS=()
cleanup() {
  local p
  for p in "${CLEANUP_PATHS[@]+"${CLEANUP_PATHS[@]}"}"; do rm -rf "$p"; done
}
trap cleanup EXIT

usage() {
  cat <<'EOF'
usage:
  scripts/sync-vectors.sh [--check] [--contract DIR] [--main DIR]     refresh (copy)
  scripts/sync-vectors.sh --check [--contract DIR] [--main DIR]       verify only; exit 1 on drift
  scripts/sync-vectors.sh --bump <tag> [--contract DIR] [--main DIR]  move the pin in one step

--bump updates the contract `ref:` in .github/workflows/ci.yml and .github/workflows/release.yml, the
pin statement in conformance/vectors/README.md, and the vendored snapshot (exported from <tag> with
`git archive`), then runs --check and exits 1 if the result does not verify. It does not commit, push,
tag, or modify the contract repository. Bumping to the already-pinned tag re-syncs and re-verifies.

--bump 会改 ci.yml 与 release.yml 的契约 ref、conformance/vectors/README.md 的 pin 陈述，并从 <tag>
重新导出快照；随后跑 --check，校验不过即退出 1。不提交、不推送、不打 tag、不改契约仓；
bump 到已钉的 tag 也会重新同步并校验。
EOF
}

CHECK=0
BUMP_TAG=""
CONTRACT_DIR="${CONTRACT_DIR:-}"
MAIN_REPO_DIR="${MAIN_REPO_DIR:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK=1 ;;
    --contract)
      [ $# -ge 2 ] || { echo "--contract needs a directory" >&2; exit 2; }
      CONTRACT_DIR="$2"; shift ;;
    --main)
      [ $# -ge 2 ] || { echo "--main needs a directory" >&2; exit 2; }
      MAIN_REPO_DIR="$2"; shift ;;
    --bump)
      [ $# -ge 2 ] || { echo "--bump needs a tag such as v1.14.0" >&2; exit 2; }
      BUMP_TAG="$2"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
if [ "$CHECK" -eq 1 ] && [ -n "$BUMP_TAG" ]; then
  echo "--check only reads; --bump writes and then verifies. Run one at a time." >&2
  exit 2
fi
[ -n "$CONTRACT_DIR" ] || CONTRACT_DIR="$(cd "$ROOT/../keelbase-contract" 2>/dev/null && pwd || true)"
[ -n "$MAIN_REPO_DIR" ] || MAIN_REPO_DIR="$(cd "$ROOT/../KeelBase" 2>/dev/null && pwd || true)"

# Where each vendored set comes from. `protocol` is the contract repository itself — it is the
# authority this repository consumes. `scenarios` are behaviour-level replay packs which are NOT part
# of the contract, so they still come from the main repository; whether they belong in the contract is
# an open question, and this script deliberately does not answer it by pretending they do.
src_dir_for() { # $1 = spec subdir
  case "$1" in
    protocol) printf '%s' "$CONTRACT_DIR" ;;
    *) printf '%s/%s' "$MAIN_REPO_DIR/Server-NestJS/specs" "$1" ;;
  esac
}

if [ -z "$CONTRACT_DIR" ] || [ ! -d "$CONTRACT_DIR/schemas" ]; then
  echo "cannot locate the contract repository (expected its root, containing schemas/)." >&2
  echo "pass --contract DIR or set CONTRACT_DIR" >&2
  exit 2
fi
if [ -z "$MAIN_REPO_DIR" ] || [ ! -d "$MAIN_REPO_DIR/Server-NestJS/specs/scenarios" ]; then
  echo "cannot locate the main repo's scenarios directory." >&2
  echo "pass --main DIR or set MAIN_REPO_DIR; expected: <main-repo>/Server-NestJS/specs/scenarios" >&2
  exit 2
fi
for d in "$CONTRACT_DIR" "$MAIN_REPO_DIR/Server-NestJS/specs"; do
  if [ "$d" = "$(dirname "$DEST")" ]; then
    echo "refusing to operate on the same directory: $d" >&2
    exit 2
  fi
done

# The pin, read the same way `scripts/release-gate.sh` reads it: the first `ref:` under
# `repository: rain6fish/keelbase-contract`. Everything after that line belongs to a different
# checkout (the main repo's scenario packs), so the match is scoped rather than positional.
pin_of() { # workflow-file
  awk '/^[[:space:]]*repository:[[:space:]]*rain6fish\/keelbase-contract[[:space:]]*$/ { f = 1 }
       f && /^[[:space:]]*ref:[[:space:]]*/ { sub(/^[[:space:]]*ref:[[:space:]]*/, ""); print; exit }' "$1"
}

# Rewrite that one scoped `ref:` line, keeping its indentation and every other line as-is.
set_pin() { # workflow-file, tag
  local file="$1" tag="$2" tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/keelbase-workflow.XXXXXX")"
  CLEANUP_PATHS+=("$tmp")
  awk -v tag="$tag" '
    /^[[:space:]]*repository:[[:space:]]*rain6fish\/keelbase-contract[[:space:]]*$/ { in_contract = 1 }
    in_contract && /^[[:space:]]*ref:[[:space:]]*/ {
      match($0, /^[[:space:]]*/)
      printf "%sref: %s\n", substr($0, 1, RLENGTH), tag
      in_contract = 0
      next
    }
    { print }
  ' "$file" >"$tmp"
  mv "$tmp" "$file"
}

# Replace the pin wherever it is stated in the vendored snapshot's README (two lines).
replace_pin() { # file, old, new
  local file="$1" old="$2" new="$3" tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/keelbase-readme.XXXXXX")"
  CLEANUP_PATHS+=("$tmp")
  awk -v old="$old" -v new="$new" '{
    while ((i = index($0, old)) > 0) { $0 = substr($0, 1, i - 1) new substr($0, i + length(old)) }
    print
  }' "$file" >"$tmp"
  mv "$tmp" "$file"
}

if [ -n "$BUMP_TAG" ]; then
  if ! [[ "$BUMP_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "not a contract release tag: $BUMP_TAG (expected vX.Y.Z, e.g. v1.14.0)" >&2
    exit 2
  fi
  if ! git -C "$CONTRACT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    echo "--bump exports <tag> from the contract clone, so it needs a git checkout: $CONTRACT_DIR" >&2
    exit 2
  fi
  if ! git -C "$CONTRACT_DIR" rev-parse --verify --quiet "refs/tags/$BUMP_TAG^{commit}" >/dev/null; then
    echo "tag $BUMP_TAG not found in $CONTRACT_DIR — fetch tags first:" >&2
    echo "  git -C \"$CONTRACT_DIR\" fetch --tags" >&2
    exit 2
  fi
  for f in "$CI_WF" "$REL_WF"; do
    if [ ! -f "$f" ]; then echo "missing workflow file: $f" >&2; exit 2; fi
  done
  OLD_CI=$(pin_of "$CI_WF")
  OLD_REL=$(pin_of "$REL_WF")
  if [ -z "$OLD_CI" ] || [ -z "$OLD_REL" ]; then
    echo "cannot read the current contract pin from ci.yml / release.yml" >&2
    exit 2
  fi

  # Export the tagged commit rather than syncing the clone's working tree: that is what makes the
  # snapshot belong to <tag> even when the clone has moved on, and it leaves the clone untouched.
  CONTRACT_CLONE="$CONTRACT_DIR"
  EXPORT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/keelbase-contract-${BUMP_TAG}.XXXXXX")"
  CLEANUP_PATHS+=("$EXPORT_DIR")
  git -C "$CONTRACT_CLONE" archive --format=tar "$BUMP_TAG" | tar -x -C "$EXPORT_DIR"
  CONTRACT_DIR="$EXPORT_DIR"

  if [ "$OLD_CI" = "$BUMP_TAG" ] && [ "$OLD_REL" = "$BUMP_TAG" ]; then
    echo "bump: pin already at $BUMP_TAG in both workflows — re-syncing and re-verifying (no ref change)."
  else
    [ "$OLD_CI" = "$BUMP_TAG" ] || set_pin "$CI_WF" "$BUMP_TAG"
    [ "$OLD_REL" = "$BUMP_TAG" ] || set_pin "$REL_WF" "$BUMP_TAG"
    if [ -f "$VECTORS_README" ]; then
      [ "$OLD_CI" = "$BUMP_TAG" ] || replace_pin "$VECTORS_README" "$OLD_CI" "$BUMP_TAG"
      if [ "$OLD_REL" != "$OLD_CI" ] && [ "$OLD_REL" != "$BUMP_TAG" ]; then
        replace_pin "$VECTORS_README" "$OLD_REL" "$BUMP_TAG"
      fi
    fi
    echo "bump: pin $OLD_CI -> $BUMP_TAG (ci.yml, release.yml$([ -f "$VECTORS_README" ] && echo ', conformance/vectors/README.md'))"
  fi
  NEW_CI=$(pin_of "$CI_WF")
  NEW_REL=$(pin_of "$REL_WF")
  if [ "$NEW_CI" != "$BUMP_TAG" ] || [ "$NEW_REL" != "$BUMP_TAG" ]; then
    echo "bump: failed to update the workflows (ci.yml=$NEW_CI, release.yml=$NEW_REL)" >&2
    exit 1
  fi
  echo "bump: snapshot source = $BUMP_TAG, exported from $CONTRACT_CLONE"
fi

# The vendored spec subdirectories, and where each lands. `protocol` keeps the snapshot root (the
# layout readers and the schemas' relative $refs already depend on); anything else gets its own
# directory so the two sets never collide.
SUBS="protocol scenarios"
dest_for() { # $1 = spec subdir, $2 = path relative to it
  case "$1" in
    protocol) printf '%s/%s' "$DEST" "$2" ;;
    *) printf '%s/%s/%s' "$DEST" "$1" "$2" ;;
  esac
}

if [ "$CHECK" -eq 1 ]; then
  echo "check: vendored snapshot vs the contract ($CONTRACT_DIR) + scenarios ($MAIN_REPO_DIR/Server-NestJS/specs)"
else
  if [ -z "$BUMP_TAG" ]; then
    echo "source: contract $CONTRACT_DIR"
    echo "        scenarios $MAIN_REPO_DIR/Server-NestJS/specs"
    if git -C "$MAIN_REPO_DIR" rev-parse --short HEAD >/dev/null 2>&1; then
      echo "        contract @ $(git -C "$CONTRACT_DIR" rev-parse --short HEAD 2>/dev/null || echo '?')"
      echo "        main     @ $(git -C "$MAIN_REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo '?')"
    fi
  else
    echo "        scenarios $MAIN_REPO_DIR/Server-NestJS/specs"
  fi
  echo "dest:   $DEST"
fi
mkdir -p "$DEST"

norm() { tr -d '\r' < "$1"; }

# The vendored set of one spec subdirectory, as paths relative to that subdirectory.
list_files() { # $1 = spec subdir
  local sub="$1"
  (
    cd "$(src_dir_for "$sub")" 2>/dev/null || return 0
    case "$sub" in
      protocol)
        find . -type f \( -name '*-vector.json' -o -name 'wire-schema-registry*.json' \) -print
        find ./schemas -type f -name '*.json' -print 2>/dev/null
        ;;
      *)
        find . -type f -name '*.json' -print
        ;;
    esac
  ) | sed 's|^\./||' | sort -u
}

shopt -s nullglob
same=0 changed=0 missing=0 extra=0

sync_one() { # $1 = spec subdir
  local sub="$1" rel src dst

  # 1) everything upstream must be present here, byte-identical (after LF normalisation).
  while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    src="$(src_dir_for "$sub")/$rel"
    dst="$(dest_for "$sub" "$rel")"
    if [ ! -e "$dst" ]; then
      missing=$((missing + 1))
      echo "  missing  $sub/$rel"
      if [ "$CHECK" -eq 0 ]; then
        mkdir -p "$(dirname "$dst")"
        norm "$src" > "$dst"
      fi
    elif cmp -s <(norm "$src") "$dst"; then
      same=$((same + 1))
      # Top-level files are named individually (they are the ones a reader looks for); the schema —
      # and scenario — trees are counted only, so the log stays readable as they grow.
      case "$rel" in
        */*) ;;
        *) echo "  ok       $sub/$rel" ;;
      esac
    else
      changed=$((changed + 1))
      echo "  changed  $sub/$rel"
      if [ "$CHECK" -eq 1 ]; then
        diff -u <(norm "$src") "$dst" | sed 's/^/      /' || true
      else
        norm "$src" > "$dst"
      fi
    fi
  done < <(list_files "$sub")

  # 2) nothing here may be absent upstream (a stale or renamed vector/schema/pack).
  while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    if [ ! -e "$(src_dir_for "$sub")/$rel" ]; then
      extra=$((extra + 1))
      echo "  extra    $sub/$rel (not in its source)"
    fi
  done < <(list_vendored "$sub")
}

# The same set, read from the snapshot instead of upstream (for the "extra" half). `protocol` sits at
# the snapshot root alongside the other subdirectories, so it is read shallow — a deep walk would
# report every other set as "extra".
list_vendored() { # $1 = spec subdir
  local sub="$1" root
  root="$(dirname "$(dest_for "$sub" 'x')")"
  (
    cd "$root" 2>/dev/null || return 0
    case "$sub" in
      protocol)
        find . -maxdepth 1 -type f \( -name '*-vector.json' -o -name 'wire-schema-registry*.json' \) -print
        find ./schemas -type f -name '*.json' -print 2>/dev/null
        ;;
      *)
        find . -type f -name '*.json' -print
        ;;
    esac
  ) | sed 's|^\./||' | sort -u
}

for sub in $SUBS; do
  sync_one "$sub"
done

drift=$((missing + changed + extra))
if [ "$CHECK" -eq 1 ]; then
  if [ "$drift" -ne 0 ]; then
    echo "::error::vendored snapshot drifted from its sources ($drift file(s)) — run scripts/sync-vectors.sh to refresh" >&2
    exit 1
  fi
  echo "vendored snapshot matches its sources ($same files, no drift)."
else
  echo "synced: $same unchanged, $changed changed, $missing missing, $extra extra."
  if [ "$drift" -eq 0 ]; then echo "snapshot already up to date."; fi
  if [ -n "$BUMP_TAG" ]; then
    echo ""
    echo "verify: pin $BUMP_TAG and the snapshot must agree — re-reading through --check"
    if ! "$SELF" --check --contract "$CONTRACT_DIR" --main "$MAIN_REPO_DIR"; then
      echo "bump failed verification: pin $BUMP_TAG is set but the snapshot does not match it; nothing was committed or pushed." >&2
      exit 1
    fi
    echo "bump complete: pin $BUMP_TAG, snapshot re-synced and verified. Nothing was committed, pushed, or tagged."
  fi
fi
