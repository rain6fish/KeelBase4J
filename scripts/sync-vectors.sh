#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Refresh — or verify — the vendored contract snapshot against its two sources.
#
# conformance/vectors/ is a READ-ONLY snapshot of
#   <contract-repo>/                              → conformance/vectors/
#   <main-repo>/Server-NestJS/specs/scenarios/    → conformance/vectors/scenarios/
# The protocol half comes from the contract repository — that is where the contract lives, and it is
# what this repository is a consumer of. The scenario half comes from the main repository: scenario
# packs are not part of the contract, so they are still taken from where they live.
# Never hand-edit the vendored copies — run this instead.
#
#   scripts/sync-vectors.sh [--check] [--contract DIR] [--main DIR]   # refresh (copy)
#   scripts/sync-vectors.sh --check [--contract DIR] [--main DIR]     # verify only; exit 1 on drift
#   CONTRACT_DIR=/path/to/keelbase-contract MAIN_REPO_DIR=/path/to/KeelBase scripts/sync-vectors.sh
#
# The vendored set is the contract the Java tests read, not just the vectors:
#   *-vector.json                    the language-neutral conformance vectors
#   wire-schema-registry.json        the wire-object registry (id → schema file)
#   schemas/**/*.json                the schemas the registry points at
#   scenarios/*.json                 the behaviour-level scenario packs (their `replay` is the
#                                    Extended-layer neutral-replay corpus the runtime replays)
# Every file is picked up dynamically (a new vector, schema or scenario pack in the main repo needs
# no change here); anything present here but absent upstream is reported as "extra". Content is
# compared and written normalised to LF, so the snapshot stays byte-stable. CI runs this with
# --check to gate drift.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/conformance/vectors"

CHECK=0
CONTRACT_DIR="${CONTRACT_DIR:-}"
MAIN_REPO_DIR="${MAIN_REPO_DIR:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK=1 ;;
    --contract) CONTRACT_DIR="$2"; shift ;;
    --main) MAIN_REPO_DIR="$2"; shift ;;
    -h | --help)
      sed -n '3,22p' "$0"
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
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
  echo "source: contract $CONTRACT_DIR"
  echo "        scenarios $MAIN_REPO_DIR/Server-NestJS/specs"
  if git -C "$MAIN_REPO_DIR" rev-parse --short HEAD >/dev/null 2>&1; then
    echo "        contract @ $(git -C "$CONTRACT_DIR" rev-parse --short HEAD 2>/dev/null || echo '?')"
    echo "        main     @ $(git -C "$MAIN_REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo '?')"
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
        find . -type f \( -name '*-vector.json' -o -name 'wire-schema-registry.json' \) -print
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
        find . -maxdepth 1 -type f \( -name '*-vector.json' -o -name 'wire-schema-registry.json' \) -print
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
fi
