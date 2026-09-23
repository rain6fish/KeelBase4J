#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# L3, second slot — the same frontend spec, pointed at a *generated* application.
#
# `demo-golden-path.sh` answers "can the frontend talk to this runtime". JV-13's own row names the other
# half of the question: a generated application serving that same frontend is the complete shape of "one
# frontend, a Java application". This script produces that verdict — it is expected to be **red**, and
# the red is the deliverable: it says where, and the two axes are probed separately so that one gap does
# not hide the others.
#
#   identity — the frontend authenticates with a delegation token; does a generated app accept one?
#   shape    — with identity supplied the way this app expects it, do the frontend's payloads hold?
#
# Exit code is the spec's, i.e. this is a verdict, not a gate. Run it when the question is asked.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/../KeelBase/Web-Admin-Vue}"
if [ ! -f "$FRONTEND_DIR/src/api/golden-path.e2e.spec.ts" ]; then
  echo "cannot find the golden-path spec under $FRONTEND_DIR" >&2
  echo "set FRONTEND_DIR to the Web-Admin-Vue checkout" >&2
  exit 2
fi

GEN_DIR="target/gen-verdict"
PORT="${PORT:-18097}"
BASE="http://127.0.0.1:$PORT/api/v1"
SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"

echo "== 1/4 build the modules =="
mvn -q -B -DskipTests install

echo "== 2/4 generate an application =="
rm -rf "$GEN_DIR"
mvn -q -B -DskipTests -pl keelbase4j-generator compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR"

echo "== 3/4 build and start it =="
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests
JAR="$(ls "$ROOT/$GEN_DIR"/target/*.jar | head -1)"
# Run from inside the generated project, as the other demos do: its database is file-backed and belongs
# to the project, not to whatever directory the script happened to be started from.
( cd "$ROOT/$GEN_DIR" && exec java -jar "$JAR" --server.port="$PORT" ) > "$ROOT/$GEN_DIR/verdict.log" 2>&1 &
APP_PID=$!

# Git Bash's `kill` cannot signal a native Windows process, so ask Windows which pid owns the port.
stop_app() {
  kill "${APP_PID:-}" 2>/dev/null || true
  local owner=""
  owner="$(netstat -ano 2>/dev/null | awk -v p=":$PORT " 'index($0,p) && /LISTENING/ {print $NF; exit}')" || owner=""
  if [ -n "$owner" ] && command -v taskkill >/dev/null 2>&1; then
    taskkill //PID "$owner" //F >/dev/null 2>&1 || true
  fi
  wait "${APP_PID:-}" 2>/dev/null || true
}
trap stop_app EXIT

for _ in $(seq 1 60); do
  sleep 1
  curl -s -o /dev/null "$BASE/app/capabilities" && break
done

echo "== 4/4 the two axes =="

echo
echo "-- axis 1: identity — the frontend sends a delegation token"
TOKEN="$(mvn -q -B -pl keelbase4j-demo exec:java \
  -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
  -Dexec.args="alice $SECRET" 2>/dev/null | tail -1)"
[ -n "$TOKEN" ] || { echo "  FAIL could not mint a token"; exit 1; }
BEARER=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/me/permissions" \
  -H "Authorization: Bearer $TOKEN")
echo "  bearer token on /auth/me/permissions -> $BEARER"
HEADERS=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/me/permissions" \
  -H 'X-User-Id: alice')
echo "  self-declared headers (X-User-Id)     -> $HEADERS"
if [ "$BEARER" = "200" ] && [ "$HEADERS" != "200" ]; then
  echo "  => the frontend's identity is accepted, and a self-declared one is not"
elif [ "$BEARER" = "200" ]; then
  echo "  => the frontend's identity is accepted (but so is a self-declared one — check the adapter)"
else
  echo "  => GAP: the frontend's identity is not accepted (bearer token rejected)"
fi

echo
echo "-- axis 2: shape — the frontend's payloads, over the identity it accepts"
CHAT=$(curl -s -X POST "$BASE/ai/chat" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{"message":"给客户建一条跟进记录","customerId":1}')
echo "  POST /ai/chat {message, customerId} -> ${CHAT:0:120}"
PERMS=$(curl -s "$BASE/auth/me/permissions" -H "Authorization: Bearer $TOKEN")
echo "  GET  /auth/me/permissions -> ${PERMS:0:120}"
CAPS=$(curl -s "$BASE/app/capabilities")
echo "  GET  /app/capabilities -> ${CAPS:0:120}"
CHATSTREAM=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/ai/chat/stream" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"message":"hi"}')
echo "  POST /ai/chat/stream -> $CHATSTREAM"

echo
echo "-- axis 3: the frontend's own spec, pointed at this app"
cd "$FRONTEND_DIR"
set +e
VITE_API_BASE="$BASE" KEELBASE_GOLDEN_PATH_TOKEN="$TOKEN" \
  npx vitest run src/api/golden-path.e2e.spec.ts
SPEC=$?
set -e
echo
echo "spec exit: $SPEC (non-zero is the expected verdict here — it says where the frontend breaks)"
exit "$SPEC"
