#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Demo: the runtime with a *real* model on the seam.
#
# The other three demos prove the runtime and its generated artifacts without any model, on purpose.
# This one is the opposite: it puts DeepSeek behind the ToolCallPlanner seam and asks the model to
# route a request that the runtime's own rule-based planner cannot route at all — so a 200 here is
# not "the rules matched", it is "the model decided". It then checks that what the model proposed
# still had to pass the gate.
#
# Requires DEEPSEEK_API_KEY. Nothing here ships to a deployment; it is a demo harness.
#
#   1. build the demo deployment (runtime + Spring AI adapter + DeepSeek provider)
#   2. start it with the key and base URL in the environment
#   3. /ai/chat with a request the rule-based planner has no keyword for
#   4. assert the model routed it, and that a proposed write still waits for a human
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  cat >&2 <<'MSG'
DEEPSEEK_API_KEY is not set — this demo exists to talk to a real model, so there is nothing to fall
back to (the other three demos are the ones that run without a model).

  export DEEPSEEK_API_KEY=...            # https://platform.deepseek.com
  DEEPSEEK_BASE_URL   optional, default https://api.deepseek.com
  AI_CHAT_MODEL       optional, default deepseek-chat
MSG
  exit 1
fi

PORT="${PORT:-18084}"
BASE="http://localhost:$PORT/api/v1"
SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"
unset DELEGATION_SECRET 2>/dev/null || true

echo "== 1/4 build the demo deployment (runtime + adapter + DeepSeek) =="
mvn -q -B -DskipTests install
mvn -q -B -DskipTests -pl keelbase4j-demo package
JAR="$(ls "$ROOT"/keelbase4j-demo/target/keelbase4j-demo-*.jar | grep -v original | head -1)"
echo "   jar: $JAR"

# Stop the app *and make sure it is gone*: Git Bash's `kill` cannot signal a native Windows process,
# so the app would outlive the script holding the port.
stop_app() {
  kill "${APP_PID:-}" 2>/dev/null || true
  # No `| head -1` here on purpose: under `set -o pipefail` the SIGPIPE it causes can make the whole
  # pipeline report failure, which would exit the script in the middle of cleanup.
  local owner=""
  owner="$(netstat -ano 2>/dev/null | awk -v p=":$PORT " 'index($0,p) && /LISTENING/ {print $NF; exit}')" || owner=""
  if [ -n "$owner" ] && command -v taskkill >/dev/null 2>&1; then
    taskkill //PID "$owner" //F >/dev/null 2>&1 || true
  fi
  wait "${APP_PID:-}" 2>/dev/null || true
  local attempt
  for attempt in $(seq 1 20); do
    if netstat -ano 2>/dev/null | grep -q ":$PORT .*LISTENING"; then sleep 1; else break; fi
  done
}
stop_app
trap stop_app EXIT

echo "== 2/4 start it with a model configured =="
# Spring Boot binds these to spring.ai.deepseek.*  (relaxed binding). Passed through the environment
# rather than a properties file, which at the classpath root would shadow the runtime's own.
SPRING_AI_DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY" \
SPRING_AI_DEEPSEEK_BASE_URL="${DEEPSEEK_BASE_URL:-https://api.deepseek.com}" \
SPRING_AI_DEEPSEEK_CHAT_MODEL="${AI_CHAT_MODEL:-deepseek-chat}" \
  java -jar "$JAR" --server.port="$PORT" > "$ROOT/keelbase4j-demo/target/demo.log" 2>&1 &
APP_PID=$!

READY=0
for _ in $(seq 1 90); do
  sleep 1
  if curl -s -o /dev/null "$BASE/app/capabilities"; then READY=1; break; fi
done
if [ "$READY" -ne 1 ]; then
  echo "  FAIL the demo app did not start; last lines of its log:"
  tail -20 "$ROOT/keelbase4j-demo/target/demo.log" | sed 's/^/    /'
  exit 1
fi

echo "== 3/4 mint a delegation token (the only way in) =="
TOKEN="$(mvn -q -B -pl keelbase4j-demo exec:java \
  -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
  -Dexec.args="alice $SECRET" 2>/dev/null | tail -1)"
[ -n "$TOKEN" ] || { echo "  FAIL could not mint a token"; exit 1; }
echo "   token minted for subject local:alice"

fail=0
check() { # name expected actual — literal match: the expected strings contain JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi
}

echo "== 4/4 ask something the *rules* cannot route =="
# The rule-based planner matches keywords (风险 / 分析 → read; 跟进 / 创建 → write). This request
# contains none of them, so it is a 400 there and a decision here. That difference is the whole demo.
MESSAGE="把今天和这个客户的沟通记一笔"
echo "   request: $MESSAGE"

# The body goes through a file, not through argv. Git Bash hands command-line arguments to native
# Windows programs in the ANSI code page, so a Chinese message passed to curl.exe as an argument
# arrives as GBK bytes and the server rejects the body as invalid UTF-8 — while printf, a shell
# builtin, writes the script's own UTF-8 through untouched.
BODY_FILE="$ROOT/keelbase4j-demo/target/chat-request.json"
printf '{"message":"%s","customerId":1}' "$MESSAGE" > "$BODY_FILE"

# A real model is not deterministic. It may answer {"tool": null} on one call and route on the next,
# and one decline says nothing about whether the model's decision reaches the runtime — which is what
# this demo is actually about. Retrying keeps the demo from being flaky without weakening the claim:
# it still requires an answer that came from the model.
RES=""
BODY=""
for attempt in 1 2 3; do
  RES=$(curl -s -o /tmp/springai-demo.json -w '%{http_code}' -X POST "$BASE/ai/chat" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    --data-binary "@$BODY_FILE")
  BODY="$(cat /tmp/springai-demo.json)"
  [ "$RES" = "200" ] && break
  echo "   attempt $attempt: the model proposed no tool this time; asking again"
done

check "the model routed what the rules could not (HTTP 200)" "200" "$RES"

STATUS="$(printf '%s' "$BODY" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
echo "   the model proposed: status=$STATUS"
case "$STATUS" in
  executed|pending_confirmation) echo "  ok   the outcome is one the engine produces" ;;
  *) echo "  FAIL unexpected outcome: $BODY"; fail=1 ;;
esac

if [ "$STATUS" = "pending_confirmation" ]; then
  check "a proposed write still waits for a human (token)" '"token":"' "$BODY"
else
  echo "  --   the model proposed a read, so there is no gate to observe this time"
  echo "       (run again with a clearer 'record something' request to see it)"
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS — a real model decided what to call, and the runtime still decided whether it may run"
else
  echo "FAIL"
fi
exit "$fail"
