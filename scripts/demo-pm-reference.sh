#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# The PM reference project's one-command demo: build, start, mint tokens, walk the whole governance
# loop, and stop.
#
# It drives the same runtime the CRM demo drives — there is no second application and no second
# seam. What changes is the domain: projects and tasks instead of customers and follow-ups, with the
# same gate, the same audit chain and the same local compensation.
#
# The assertions themselves live in `scripts/pm-reference-e2e.mjs`, because they are readings and
# belong with the code that prints them. This script owns only what a shell is better at: building the
# jar, starting it on a free port, minting a delegation token, and making sure the process is gone
# afterwards.
#
#   bash scripts/demo-pm-reference.sh
#
# Every expected outcome is asserted. Exit 0 only when nothing failed.
#
# —— 中文 ——
#
# PM 参考项目的一条命令演示：构建、启动、签发令牌、走完整条治理闭环、收工。
#
# 它驱动的运行时与 CRM 演示是**同一个** —— 没有第二个应用、也没有第二条接缝。变化的只是域：项目与任务，
# 而不是客户与跟进；闸、审计链、本地补偿都相同。
#
# 断言本身在 `scripts/pm-reference-e2e.mjs` 里，因为它们是**读数**、应与打印它们的代码住在一起。本脚本只
# 负责 shell 更擅长的事：构建 jar、在空闲端口起它、签发委托令牌、并确保事后进程真的没了。
#
#   bash scripts/demo-pm-reference.sh
#
# 每个预期结局都被断言。只有全不失败才退 0。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

PORT="${PORT:-18107}"
SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"

echo "== 1/4 build the runtime and the demo module =="
mvn -q -B -DskipTests install -pl keelbase4j-demo -am

# The version moves with every release, so the artifact is found rather than named here. A name
# written into this line goes stale at the next bump and takes the whole demo with it.
#
# 版本随每次发版走，故产物是**找出来**的、不是在这里**点名**的。写死在这一行的名字会在下一次抬版本时过期、
# 并把整条 demo 一起带走。
JAR="$(ls "$ROOT"/keelbase4j-runtime/target/keelbase4j-runtime-*-exec.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  echo "FAIL cannot find the runtime's executable jar under keelbase4j-runtime/target" >&2
  exit 2
fi

# Stop the app *and make sure it is gone*: Git Bash's `kill` cannot signal a native Windows process,
# so the app would outlive the script holding the port and the database file.
#
# 停掉应用、**并确认它真的没了**：Git Bash 的 `kill` 发不到原生 Windows 进程，否则应用会活过脚本、占着端口。
APP_PID=""
stop_app() {
  kill "${APP_PID:-}" 2>/dev/null || true
  wait "${APP_PID:-}" 2>/dev/null || true
  local owner
  owner="$(netstat -ano 2>/dev/null | awk -v p=":$PORT " 'index($0,p) && /LISTENING/ {print $NF; exit}')" || owner=""
  if [ -n "$owner" ] && command -v taskkill >/dev/null 2>&1; then
    taskkill //PID "$owner" //F >/dev/null 2>&1 || true
  fi
  local attempt
  for attempt in $(seq 1 20); do
    if netstat -ano 2>/dev/null | grep -q ":$PORT .*LISTENING"; then sleep 1; else break; fi
  done
}
trap stop_app EXIT

echo "== 2/4 start the runtime on port $PORT =="
java -jar "$JAR" --server.port="$PORT" \
  > "$ROOT/keelbase4j-runtime/target/pm-reference.log" 2>&1 &
APP_PID=$!

wait_ready() {
  local attempt
  for attempt in $(seq 1 90); do
    sleep 1
    # The self-description, not the tool catalogue: the catalogue is an admin surface now, and a
    # readiness probe should ask a question any caller may ask.
    #
    # 探自述面、不是工具名录：名录现在是管理面，而就绪探针该问任何调用者都能问的问题。
    if curl -s -o /dev/null "http://127.0.0.1:$PORT/api/v1/app/capabilities"; then return 0; fi
  done
  echo "FAIL the application did not answer within 90s" >&2
  return 1
}
wait_ready

echo "== 3/4 mint delegation tokens =="
mint() {
  mvn -q -B -pl keelbase4j-demo exec:java \
    -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
    -Dexec.args="$1 $SECRET" 2>/dev/null | tail -1
}
ALICE="$(mint alice)"
CAROL="$(mint carol)"
BOB="$(mint bob)"
[ -n "$ALICE" ] && [ -n "$CAROL" ] && [ -n "$BOB" ] || { echo "FAIL could not mint a token" >&2; exit 1; }
echo "   tokens minted for local:alice / local:carol / local:bob"

echo "== 4/4 walk the PM governance loop =="
node "$ROOT/scripts/pm-reference-e2e.mjs" "http://127.0.0.1:$PORT/api/v1" "$ALICE" "$CAROL" "$BOB"
