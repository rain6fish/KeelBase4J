#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Demo: the multi-step path, driven by a real model over HTTP.
#
# demo-springai.sh shows a model choosing one tool. This one shows the model driving the framework's
# own tool-calling loop: it is handed the governed tools, calls one, reads what came back, and decides
# whether another step is warranted — and every one of those steps still has to pass the engine. The
# route is POST /ai/task, which reports the run: the answer, each step with the tool it was an attempt
# at, and the token of a step still waiting on a person.
#
# It is driven over HTTP rather than from a test on purpose. The claim being checked is about a running
# deployment — which beans exist, which callbacks the framework actually invokes — and a test answers
# that question in a different scope from the one that ships. Asking the deployment removes the whole
# question of whether the test looked at the same thing the deployment would.
#
# Requires DEEPSEEK_API_KEY.
#
#   1. build the demo deployment (runtime + Spring AI adapter + DeepSeek provider)
#   2. start it with the key in the environment
#   3. POST /ai/task with one message that asks for two things
#   4. assert the loop took more than one step, that each step's outcome came from the engine, and that
#      the write it proposed waited for a human instead of running
#
# 演示：多步路径，由真模型经 HTTP 驱动。
#
# demo-springai.sh 展示的是模型**挑一个**工具。这一条展示模型**驱动框架自己的 tool-calling 循环**：受治理的
# 工具交到它手上，它调一个、读回结果、再决定要不要来第二步 —— 而这每一步仍然都得过引擎。路由是
# POST /ai/task，它汇报这次运行：答复、每一步**冲着哪个工具**去的、以及某个仍在等人的步骤的 token。
#
# 它是经 **HTTP** 驱动、而不是从测试里驱动，这是有意的。要检的主张是关于**一个跑着的部署**的 —— 哪些 bean
# 在、框架**真的**调了哪些回调 —— 而测试是在**与出厂形态不同的作用域**里回答这个问题。去问部署，就把「测试
# 看的是不是部署会看到的那个东西」这整个问题去掉了。
#
# 需要 DEEPSEEK_API_KEY。
#
#   1. 构建演示部署（运行时 + Spring AI 适配器 + DeepSeek provider）
#   2. 带上 key 启动它
#   3. 用一句要求两件事的话 POST /ai/task
#   4. 断言循环走了**不止一步**、每一步的结果都由引擎给出，且它提议的**写**是在等人而不是已经跑了
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  cat >&2 <<'MSG'
DEEPSEEK_API_KEY is not set — this demo exists to talk to a real model, so there is nothing to fall
back to (the other demos are the ones that run without a model).

  export DEEPSEEK_API_KEY=...
MSG
  exit 1
fi

PORT="${PORT:-18085}"
BASE="http://localhost:$PORT/api/v1"
SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"
unset DELEGATION_SECRET 2>/dev/null || true

echo "== 1/5 build the demo deployment (runtime + adapter + DeepSeek) =="
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

echo "== 2/5 start it with a model configured =="
SPRING_AI_DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY" \
SPRING_AI_DEEPSEEK_BASE_URL="${DEEPSEEK_BASE_URL:-https://api.deepseek.com}" \
SPRING_AI_DEEPSEEK_CHAT_MODEL="${AI_CHAT_MODEL:-deepseek-chat}" \
  java -jar "$JAR" --server.port="$PORT" > "$ROOT/keelbase4j-demo/target/demo-task.log" 2>&1 &
APP_PID=$!

READY=0
for _ in $(seq 1 90); do
  sleep 1
  if curl -s -o /dev/null "$BASE/app/capabilities"; then READY=1; break; fi
done
if [ "$READY" -ne 1 ]; then
  echo "  FAIL the demo app did not start; last lines of its log:"
  tail -20 "$ROOT/keelbase4j-demo/target/demo-task.log" | sed 's/^/    /'
  exit 1
fi

echo "== 3/5 mint a delegation token (the only way in) =="
TOKEN="$(mvn -q -B -pl keelbase4j-demo exec:java \
  -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
  -Dexec.args="alice $SECRET" 2>/dev/null | tail -1)"
[ -n "$TOKEN" ] || { echo "  FAIL could not mint a token"; exit 1; }
echo "   token minted for subject local:alice"

fail=0
check() { # name expected actual — literal match: the expected strings contain JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi
}

echo "== 4/5 ask for two things in one message =="
# The single-shot planner routes on keywords and picks one tool; this path asks the model to take
# steps. The customer is named the way the console names it, because a model reads the message — and
# a tool that needs a customer id has nobody to get it from but the words.
MESSAGE="当前客户「Acme」（ID 1）。先分析这个客户的风险，再给它建一条跟进记录，内容是：今天电话沟通了一次。"
BODY_FILE="$ROOT/keelbase4j-demo/target/task-request.json"
printf '{"message":"%s"}' "$MESSAGE" > "$BODY_FILE"

# A real model is not deterministic: it may answer from what it already knows on one call and use the
# tools on the next, and one such answer says nothing about whether the loop works — which is what this
# demo is about. So it asks again, up to three times, and reports how many attempts that took.
#
# 真模型不确定：它可能这次凭已知直接作答、下次才用工具，而一次这样的作答**说明不了**循环行不行 —— 而循环
# 正是本演示要看的。所以最多再问两次，并报出用了几次。
# Counting has to be anchored on the step objects themselves, not on the body as a whole: a tool's
# payload can carry the very keys being counted. A call the gate blocks answers with the authorization
# reasons, and that object has a "tool" of its own — so counting raw "tool":" occurrences reported three
# steps for a run that took two. These two read only what the route emits per step.
#
# 计数必须锚在**步骤对象本身**上、而不是整个 body：工具的载荷里会带上**正在数的那些键**。被闸拒掉的那一步
# 回的是授权依据，而那个对象**自带一个 `"tool"`** —— 于是裸数 `"tool":"` 的写法，会把一次**两步**的运行报成
# 三步。下面两个只读路由**按步**吐出来的那一段。
step_statuses() { printf '%s' "$1" | grep -o '"outcome":{"status":"[^"]*"' | sed 's/.*"status":"//; s/"$//' || true; }
step_tools() { printf '%s' "$1" | grep -o '{"tool":"[^"]*","outcome"' | sed 's/^{"tool":"//; s/","outcome"$//' || true; }

ATTEMPTS=0
RES=""
BODY=""
for attempt in 1 2 3; do
  ATTEMPTS=$attempt
  RES=$(curl -s -o /tmp/springai-task.json -w '%{http_code}' -X POST "$BASE/ai/task" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
    --data-binary "@$BODY_FILE")
  BODY="$(cat /tmp/springai-task.json)"
  STEPS="$(step_statuses "$BODY" | grep -c . || true)"
  if [ "$RES" = "200" ] && [ "$STEPS" -ge 2 ] \
     && printf '%s' "$BODY" | grep -qF '"status":"pending_confirmation"'; then
    break
  fi
  echo "   attempt $attempt: the model took $STEPS step(s) this time; asking again"
done
echo "   attempts: $ATTEMPTS"

check "the deployment answered the task (HTTP 200)" "200" "$RES"
check "the model answered" '"answer":"' "$BODY"
printf '%s' "$BODY" | grep -qF '"answer":null' && { echo "  FAIL the answer is empty"; fail=1; }

TOOLS="$(step_tools "$BODY" | tr '\n' ' ')"
STATUSES="$(step_statuses "$BODY")"
STEPS="$(printf '%s' "$STATUSES" | grep -c . || true)"
echo "   steps: $STEPS  [$TOOLS]"
echo "   statuses: $(printf '%s' "$STATUSES" | tr '\n' ' ')"

if [ "$STEPS" -ge 2 ]; then
  echo "  ok   the loop took $STEPS governed steps, not one"
else
  echo "  FAIL the loop took $STEPS step(s); the framework drove nothing"
  fail=1
fi

# Every outcome has to be one the engine produces. A status from anywhere else would mean the loop
# answered from the model's own words and never consulted the gate.
for status in $STATUSES; do
  case "$status" in
    executed|pending_confirmation|requires_approval|blocked|declined|error) ;;
    *) echo "  FAIL '$status' is not an outcome the engine produces"; fail=1 ;;
  esac
done
[ "$fail" -eq 0 ] && echo "  ok   every step's outcome came from the engine"

# The counter-proof, and the reason this demo exists: the model proposed a write, and that proposal did
# not run. It is waiting on a person, which is what a gate that is still in the path looks like.
check "a proposed write did not run — it waits for a person" '"status":"pending_confirmation"' "$BODY"
PENDING="$(printf '%s' "$BODY" | sed -n 's/.*"pendingToken":"\([^"]*\)".*/\1/p')"
if [ -n "$PENDING" ]; then
  echo "  ok   the run handed back a token, and it is not among what the model saw"
else
  echo "  FAIL the waiting step has no token"; fail=1
fi

echo "== 5/5 the waiting step is on the caller's own list =="
CONFS="$(curl -s "$BASE/ai/my/confirmations" -H "Authorization: Bearer $TOKEN")"
check "the operator can see what is waiting on them" '"toolName":"' "$CONFS"
check "and it is the very token the run handed back" "$PENDING" "$CONFS"

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS — a real model drove several governed steps, and the write it proposed still waited for a human"
else
  echo "FAIL"
  echo "last body: $BODY"
fi
exit "$fail"
