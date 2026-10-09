#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# L3 — walk the golden path through the *frontend's own modules* against this runtime.
#
# The other demos drive the runtime with curl. This one drives it with the frontend: it starts the
# runtime, mints a delegation token, then runs Web-Admin-Vue's golden-path spec with `VITE_API_BASE`
# pointed here. The spec imports the app's real api modules, so what is exercised is the code the
# product ships, not a copy of it.
#
# Run the same spec against the TypeScript runtime (VITE_API_BASE=http://localhost:3000/api/v1, with
# a token from /auth/login) and compare — that comparison *is* the "one frontend, two runtimes"
# verdict. This script only does the Java half.
#
#   bash scripts/demo-golden-path.sh
#
# Exits with the spec's status: red means the frontend and this runtime still disagree, and the spec
# names where. Red is a legitimate result here — it is the gap table L3 exists to produce.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

# The frontend lives in the main repo, next to this one. Override when it is somewhere else.
FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/../KeelBase/Web-Admin-Vue}"
if [ ! -f "$FRONTEND_DIR/src/api/golden-path.e2e.spec.ts" ]; then
  echo "cannot find the golden-path spec under $FRONTEND_DIR" >&2
  echo "set FRONTEND_DIR to the Web-Admin-Vue checkout" >&2
  exit 2
fi

PORT="${PORT:-18096}"
SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"

echo "== 1/3 build and start the runtime =="
mvn -q -B -DskipTests install
# The version moves with every release, so the artifact is found rather than named here. A name
# written into this line goes stale at the next bump and takes the whole demo with it, which is
# exactly what happened: the line said `…-0.1.0-exec.jar` against a `0.1.4` build, and the script
# died at `unable to access jarfile` without ever reaching the spec it exists to run.
#
# 版本随每次发版走，故产物是**找出来**的、不是在这里**点名**的。写死在这一行的名字会在下一次抬版本时过期、
# 并把整条 demo 一起带走 —— 这一行正是这么坏的：它写着 `…-0.1.0-exec.jar` 却对着一份 `0.1.4` 的构建，
# 脚本在 `unable to access jarfile` 上就死了，**根本没走到它存在的意义那一步（跑那份 spec）**。
JAR="$(ls "$ROOT"/keelbase4j-runtime/target/keelbase4j-runtime-*-exec.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  echo "cannot find the runtime's executable jar under keelbase4j-runtime/target" >&2
  echo "the build above should have produced one; check its output" >&2
  exit 2
fi
java -jar "$JAR" --server.port="$PORT" \
  > "$ROOT/keelbase4j-runtime/target/golden-path.log" 2>&1 &
APP_PID=$!

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
  curl -s -o /dev/null "http://127.0.0.1:$PORT/api/v1/app/capabilities" && break
done

echo "== 2/3 mint a delegation token =="
# The one documented difference between the runtimes: this one verifies a delegation token the
# deployment mints, rather than issuing an access token from /auth/login.
mint() {
  mvn -q -B -pl keelbase4j-demo exec:java \
    -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
    -Dexec.args="$1 $SECRET" 2>/dev/null | tail -1
}
TOKEN="$(mint alice)"
[ -n "$TOKEN" ] || { echo "  FAIL could not mint a token"; exit 1; }
echo "   token minted for subject local:alice"
# carol is this deployment's administrator (LocalIdentities). The console sends an administrator to
# /admin/ai/chat/stream and everyone else to /ai/chat/stream, so the streaming leg needs her token.
ADMIN_TOKEN="$(mint carol)"
[ -n "$ADMIN_TOKEN" ] || { echo "  FAIL could not mint the administrator's token"; exit 1; }
echo "   token minted for subject local:carol"

echo "== 3/3 walk the golden path with the frontend's own modules =="
cd "$FRONTEND_DIR"
VITE_API_BASE="http://127.0.0.1:$PORT/api/v1" \
KEELBASE_GOLDEN_PATH_TOKEN="$TOKEN" \
KEELBASE_GOLDEN_PATH_ADMIN_TOKEN="$ADMIN_TOKEN" \
  npx vitest run src/api/golden-path.e2e.spec.ts
