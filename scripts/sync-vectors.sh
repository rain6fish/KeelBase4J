#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Refresh the vendored protocol-vector snapshot from the authoritative main repo.
#
# conformance/vectors/ is a READ-ONLY snapshot of
#   <main-repo>/Server-NestJS/specs/protocol/*-vector.json
# The source of truth stays in the main repo (its CI keeps the gold samples evergreen).
# Never hand-edit the vendored copies — run this instead.
#
#   scripts/sync-vectors.sh [MAIN_REPO_DIR]
#   MAIN_REPO_DIR=/path/to/KeelBase scripts/sync-vectors.sh
#
# All *-vector.json files are picked up dynamically (a new vector in the main repo is synced
# without touching this script). Content is normalised to LF so the snapshot stays byte-stable.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/conformance/vectors"

MAIN_REPO_DIR="${1:-${MAIN_REPO_DIR:-}}"
if [ -z "$MAIN_REPO_DIR" ]; then
  MAIN_REPO_DIR="$(cd "$ROOT/../KeelBase" 2>/dev/null && pwd || true)"
fi
SRC="$MAIN_REPO_DIR/Server-NestJS/specs/protocol"
if [ -z "$MAIN_REPO_DIR" ] || [ ! -d "$SRC" ]; then
  echo "cannot locate the main repo's vector directory." >&2
  echo "pass it as arg 1 or set MAIN_REPO_DIR; expected: <main-repo>/Server-NestJS/specs/protocol" >&2
  exit 2
fi
if [ "$SRC" = "$DEST" ]; then
  echo "refusing to sync a directory onto itself: $SRC" >&2
  exit 2
fi

echo "source: $SRC"
if git -C "$MAIN_REPO_DIR" rev-parse --short HEAD >/dev/null 2>&1; then
  echo "        @ $(git -C "$MAIN_REPO_DIR" rev-parse --short HEAD) ($(git -C "$MAIN_REPO_DIR" log -1 --format=%ad --date=format:'%Y-%m-%d %H:%M'))"
fi
echo "dest:   $DEST"
mkdir -p "$DEST"

shopt -s nullglob
added=0 changed=0 same=0
for f in "$SRC"/*-vector.json; do
  base="$(basename "$f")"
  tmp="$(mktemp)"
  tr -d '\r' < "$f" > "$tmp" # normalise to LF
  if [ ! -e "$DEST/$base" ]; then
    mv "$tmp" "$DEST/$base"; added=$((added + 1)); echo "  added    $base"
  elif cmp -s "$tmp" "$DEST/$base"; then
    rm -f "$tmp"; same=$((same + 1)); echo "  same     $base"
  else
    mv "$tmp" "$DEST/$base"; changed=$((changed + 1)); echo "  changed  $base"
  fi
done

if [ "$added" -eq 0 ] && [ "$changed" -eq 0 ]; then
  echo "snapshot already up to date ($same unchanged)."
  exit 0
fi
echo "synced: $added added, $changed changed, $same unchanged."
