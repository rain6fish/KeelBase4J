#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Refresh — or verify — the vendored contract snapshot against the authoritative main repo.
#
# conformance/vectors/ is a READ-ONLY snapshot of
#   <main-repo>/Server-NestJS/specs/protocol/
# The source of truth stays in the main repo (its CI keeps the gold samples evergreen).
# Never hand-edit the vendored copies — run this instead.
#
#   scripts/sync-vectors.sh [MAIN_REPO_DIR]            # refresh the snapshot (copy)
#   scripts/sync-vectors.sh [--check] [MAIN_REPO_DIR]  # verify only; write nothing; exit 1 on drift
#   MAIN_REPO_DIR=/path/to/KeelBase scripts/sync-vectors.sh
#
# The vendored set is the contract the Java tests read, not just the vectors:
#   *-vector.json                    the language-neutral conformance vectors
#   wire-schema-registry.json        the wire-object registry (id → schema file)
#   schemas/**/*.json                the schemas the registry points at
# Every file is picked up dynamically (a new vector or schema in the main repo needs no change here);
# anything present here but absent upstream is reported as "extra". Content is compared and written
# normalised to LF, so the snapshot stays byte-stable. CI runs this with --check to gate drift.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/conformance/vectors"

CHECK=0
MAIN_REPO_DIR=""
for arg in "$@"; do
  case "$arg" in
    --check) CHECK=1 ;;
    -h | --help)
      sed -n '3,21p' "$0"
      exit 0
      ;;
    *) MAIN_REPO_DIR="$arg" ;;
  esac
done
if [ -z "$MAIN_REPO_DIR" ]; then
  MAIN_REPO_DIR="$(cd "$ROOT/../KeelBase" 2>/dev/null && pwd || true)"
fi

SRC="$MAIN_REPO_DIR/Server-NestJS/specs/protocol"
if [ -z "$MAIN_REPO_DIR" ] || [ ! -d "$SRC" ]; then
  echo "cannot locate the main repo's protocol directory." >&2
  echo "pass it as an argument or set MAIN_REPO_DIR; expected: <main-repo>/Server-NestJS/specs/protocol" >&2
  exit 2
fi
if [ "$SRC" = "$DEST" ]; then
  echo "refusing to operate on the same directory: $SRC" >&2
  exit 2
fi

if [ "$CHECK" -eq 1 ]; then
  echo "check: vendored snapshot vs $SRC"
else
  echo "source: $SRC"
  if git -C "$MAIN_REPO_DIR" rev-parse --short HEAD >/dev/null 2>&1; then
    echo "        @ $(git -C "$MAIN_REPO_DIR" rev-parse --short HEAD) ($(git -C "$MAIN_REPO_DIR" log -1 --format=%ad --date=format:'%Y-%m-%d %H:%M'))"
  fi
  echo "dest:   $DEST"
fi
mkdir -p "$DEST"

norm() { tr -d '\r' < "$1"; }

# The vendored set, as paths relative to the protocol directory.
list_files() {
  local root="$1"
  (
    cd "$root" 2>/dev/null || return 0
    find . -type f \( -name '*-vector.json' -o -name 'wire-schema-registry.json' \) -print
    find ./schemas -type f -name '*.json' -print 2>/dev/null
  ) | sed 's|^\./||' | sort -u
}

shopt -s nullglob
same=0 changed=0 missing=0 extra=0

# 1) everything upstream must be present here, byte-identical (after LF normalisation).
while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  if [ ! -e "$DEST/$rel" ]; then
    missing=$((missing + 1))
    echo "  missing  $rel"
    if [ "$CHECK" -eq 0 ]; then
      mkdir -p "$(dirname "$DEST/$rel")"
      norm "$SRC/$rel" > "$DEST/$rel"
    fi
  elif cmp -s <(norm "$SRC/$rel") "$DEST/$rel"; then
    same=$((same + 1))
    # Top-level files are named individually (they are the ones a reader looks for); the schema
    # tree is counted only, so the log stays readable as it grows.
    case "$rel" in
      */*) ;;
      *) echo "  ok       $rel" ;;
    esac
  else
    changed=$((changed + 1))
    echo "  changed  $rel"
    if [ "$CHECK" -eq 1 ]; then
      diff -u <(norm "$SRC/$rel") "$DEST/$rel" | sed 's/^/      /' || true
    else
      norm "$SRC/$rel" > "$DEST/$rel"
    fi
  fi
done < <(list_files "$SRC")

# 2) nothing here may be absent upstream (a stale or renamed vector/schema).
while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  if [ ! -e "$SRC/$rel" ]; then
    extra=$((extra + 1))
    echo "  extra    $rel (not in the main repo)"
  fi
done < <(list_files "$DEST")

drift=$((missing + changed + extra))
if [ "$CHECK" -eq 1 ]; then
  if [ "$drift" -ne 0 ]; then
    echo "::error::vendored contract drifted from the main repo ($drift file(s)) — run scripts/sync-vectors.sh to refresh" >&2
    exit 1
  fi
  echo "vendored contract matches the main repo ($same files, no drift)."
else
  echo "synced: $same unchanged, $changed changed, $missing missing, $extra extra."
  if [ "$drift" -eq 0 ]; then echo "snapshot already up to date."; fi
fi
