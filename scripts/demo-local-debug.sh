#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Demo: local debug mode — the governance chain with no model key, on the deployment that carries a
# provider.
#
# The other demos prove the runtime without a model because no provider is on their classpath. This
# one starts the *provider-bearing* deployment (runtime + Spring AI adapter + DeepSeek) with no key and
# `keelbase.local-debug.enabled=true`, then walks the same chain: R3 waits, approval executes, the
# effect and the audit row appear, and a free-form question is refused in so many words.
#
# The last step is the reverse proof: the same deployment without the switch cannot start without a
# key, so the reading above is the switch's doing rather than something that would have worked anyway.
#
# Requires no API key. Exits non-zero if any expected outcome is missing.
#
#   bash scripts/demo-local-debug.sh
#
# —— 中文 ——
#
# 演示：本地调试模式 —— 在**带着 provider** 的部署上，不用模型 key 走完整条治理链。
#
# 其它 demo 能不带模型，是因为它们的 classpath 上根本没有 provider。这一个启动**带 provider 的**部署
# （runtime + Spring AI 适配器 + DeepSeek），不配 key、打开 `keelbase.local-debug.enabled=true`，然后走
# 同一条链：R3 等待、批准后执行、副作用行与审计行出现，自由问答被明确拒绝。
#
# 最后一步是反向证明：同一个部署去掉开关后，没有 key 就起不来 —— 所以上面的读数来自这个开关，而不是本来就行。
#
# 不需要任何 API key。任何预期结局缺失即退非零。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

# The point of the demo is that no key is present. Clear both spellings: the script's own convention
# and the one Spring Boot binds from the environment.
#
# 本演示的要点就是**没有 key**。两种写法都清掉：脚本自己的约定，以及 Spring Boot 从环境绑定的那个。
unset DEEPSEEK_API_KEY SPRING_AI_DEEPSEEK_API_KEY 2>/dev/null || true

PORT="${PORT:-18085}"
REVERSE_PORT="${REVERSE_PORT:-18086}"
SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"
unset DELEGATION_SECRET 2>/dev/null || true

stop_app() {
  kill "${APP_PID:-}" 2>/dev/null || true
  kill "${REVERSE_PID:-}" 2>/dev/null || true
  local port owner
  for port in "$PORT" "$REVERSE_PORT"; do
    owner="$(netstat -ano 2>/dev/null | awk -v p=":$port " 'index($0,p) && /LISTENING/ {print $NF; exit}')" || owner=""
    if [ -n "$owner" ] && command -v taskkill >/dev/null 2>&1; then
      taskkill //PID "$owner" //F >/dev/null 2>&1 || true
    fi
  done
  wait "${APP_PID:-}" 2>/dev/null || true
  wait "${REVERSE_PID:-}" 2>/dev/null || true
}

wait_ready() { # port log timeout-seconds
  local port="$1" log="$2" tries="$3" attempt
  for attempt in $(seq 1 "$tries"); do
    sleep 1
    if curl -s -o /dev/null "http://127.0.0.1:$port/api/v1/app/capabilities"; then return 0; fi
    if grep -qE "APPLICATION FAILED TO START|Error starting ApplicationContext" "$log" 2>/dev/null; then
      return 1
    fi
  done
  return 1
}

echo "== 1/5 build the demo deployment (runtime + adapter + DeepSeek) =="
mvn -q -B -DskipTests install -pl keelbase4j-demo -am

JAR="$(ls "$ROOT"/keelbase4j-demo/target/keelbase4j-demo-*.jar 2>/dev/null | grep -v original | head -1 || true)"
if [ -z "$JAR" ]; then
  echo "FAIL cannot find the demo jar under keelbase4j-demo/target" >&2
  exit 2
fi

stop_app
trap stop_app EXIT

echo "== 2/5 start it with local debug on and no key =="
LOCAL_LOG="$ROOT/keelbase4j-demo/target/local-debug.log"
java -jar "$JAR" --server.port="$PORT" --keelbase.local-debug.enabled=true \
  > "$LOCAL_LOG" 2>&1 &
APP_PID=$!
if ! wait_ready "$PORT" "$LOCAL_LOG" 90; then
  echo "  FAIL the deployment did not start with local debug on; last lines:"
  tail -30 "$LOCAL_LOG" | sed 's/^/    /'
  exit 1
fi
echo "   started with no API key"

echo "== 3/5 mint delegation tokens =="
mint() {
  mvn -q -B -pl keelbase4j-demo exec:java \
    -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
    -Dexec.args="$1 $SECRET" 2>/dev/null | tail -1
}
ALICE="$(mint alice)"
CAROL="$(mint carol)"
[ -n "$ALICE" ] && [ -n "$CAROL" ] || { echo "FAIL could not mint a token" >&2; exit 1; }
echo "   tokens minted for local:alice / local:carol"

echo "== 4/5 walk the chain through the deterministic seams =="
OUT="$(node "$ROOT/scripts/local-debug-e2e.mjs" "http://127.0.0.1:$PORT/api/v1" "$ALICE" "$CAROL")"
printf '%s\n' "$OUT"
if ! grep -qE '^PASS' <<<"$OUT"; then
  echo "FAIL local-debug: the end-to-end reading failed"
  exit 1
fi
N="$(sed -n 's/^PASS local-debug: \([0-9][0-9]*\) checks$/\1/p' <<<"$OUT")"
[ -n "$N" ] || N=0

echo "== 5/5 reverse: without the switch the same deployment cannot start =="
stop_app
APP_PID=""
REVERSE_LOG="$ROOT/keelbase4j-demo/target/local-debug-reverse.log"
java -jar "$JAR" --server.port="$REVERSE_PORT" > "$REVERSE_LOG" 2>&1 &
REVERSE_PID=$!

failed_for_the_right_reason=0
for _ in $(seq 1 60); do
  sleep 1
  if grep -q "DeepSeek API key must be set" "$REVERSE_LOG" 2>/dev/null; then
    failed_for_the_right_reason=1
    break
  fi
  # A deployment that came up would have defeated the premise; stop waiting and report it.
  if curl -s -o /dev/null "http://127.0.0.1:$REVERSE_PORT/api/v1/app/capabilities" 2>/dev/null; then
    break
  fi
done
if [ "$failed_for_the_right_reason" = "1" ]; then
  echo "  ok   without the switch, the provider still demands a key (DeepSeek API key must be set)"
else
  echo "  FAIL without the switch, the deployment did not fail on the missing key; last lines:"
  tail -30 "$REVERSE_LOG" | sed 's/^/    /'
fi

echo
if [ "$failed_for_the_right_reason" = "1" ]; then
  echo "PASS local-debug: $((N + 1)) checks"
  exit 0
fi
echo "FAIL local-debug: reverse proof failed"
exit 1
