#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# JV-24 — the flagship demo, built on a *generated* application.
#
# What this adds over `demo-generated-app.sh` (which proves the trust loop on the same artifact): **data
# the demo can be about**, seeded **out of band** and idempotently, and the **container** it runs in. The
# governance beat itself is the sibling's subject; this script asserts only what the seed makes newly
# observable: **each identity sees its own rows and not the other's**, on an application nobody hand-wrote.
#
# Two things about this artifact are worth knowing before reading the assertions, and both were measured
# rather than assumed: the generated application ships **no login** (its auth controller serves the
# permissions read only, so a caller proves itself with a delegation token), and its
# `analyze_customer_risk` is a **stub** that computes nothing — so the seed deliberately plants no
# "customer at risk", because there is no such signal to plant.
#
#   bash scripts/demo-flagship-generated.sh                # run it on the host, as a jar
#   bash scripts/demo-flagship-generated.sh --container     # run it as the container, and prove the mount
#
# JV-24 —— 旗舰演示，建在一个**生成物**上。
#
# 它比 `demo-generated-app.sh`（在同一个产物上证明信任闭环）多出来的，是**演示能有据可讲的数据**：
# **带外**、**幂等**地灌入；以及它所跑的那个**容器**。治理那一拍是兄弟脚本的题目；本脚本只断言**种子新
# 让它可观测**的那件事：**每个身份只看得到自己的行、看不到别人的**——而且是在一个没人手写的应用上。
#
# 这个产物有两件事值得在断言之前知道，且两件都是**实测**不是假定：生成物**没有登录**（认证控制器只提供
# 权限读取，故调用方靠委托令牌自证），而它的 `analyze_customer_risk` 是**桩**、什么都不算——所以种子
# **刻意不造「有风险的客户」**，因为**没有那个信号可种**。
#
#   bash scripts/demo-flagship-generated.sh                # 在宿主机上以 jar 跑
#   bash scripts/demo-flagship-generated.sh --container     # 以容器跑，并证明挂载
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

MODE="host"
[ "${1:-}" = "--container" ] && MODE="container"

GEN_DIR="target/gen-flagship"
PORT="${PORT:-18085}"
COMPOSE="$ROOT/docker-compose.flagship.yml"
CONTAINER_PORT="${FLAGSHIP_PORT:-18086}"
SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"
SEED="$ROOT/scripts/seed/flagship-crm.sql"
if [ "$MODE" = container ]; then BASE="http://localhost:$CONTAINER_PORT/api/v1"; else BASE="http://localhost:$PORT/api/v1"; fi

mint() {
  mvn -q -B -pl keelbase4j-demo exec:java \
    -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
    -Dexec.args="$1 $SECRET" 2>/dev/null | tail -1
}

# Stop the app *and make sure it is gone*: Git Bash's `kill` cannot signal a native Windows process,
# so the app would outlive the script holding the port and the database file.
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

compose_down() { docker compose -f "$COMPOSE" down >/dev/null 2>&1 || true; }
cleanup() { stop_app; compose_down; }
trap cleanup EXIT

boot() { # start the app from inside its own directory (the database is file-backed and relative)
  ( cd "$ROOT/$GEN_DIR" && exec java -jar "$JAR" --server.port="$PORT" ) > "$ROOT/$GEN_DIR/app.log" 2>&1 &
  APP_PID=$!
  # Wait on the **host** port, whatever mode we are in: this boot is the one that creates the schema,
  # and in container mode `$BASE` points at the container's mapped port instead.
  #
  # 等的是**宿主**端口，与模式无关：这一次启动只是为了让 schema 建出来；而容器模式下 `$BASE` 指容器那个
  # 映射端口。
  wait_ready "http://localhost:$PORT/api/v1"
}

# Fails **loudly** when the app never answers. Returning the loop's status instead would exit the script
# with whatever the last `curl` returned — measured: an unreachable port makes that 7, and the script dies
# with a bare `7` and no message, which says nothing about the application.
#
# 等不到就**大声失败**。若直接返回循环的状态，脚本会以**最后一条 `curl` 的退出码**退出——实测：端口连不上
# 时那是 `7`，于是脚本带着一个光秃秃的 `7` 死掉、一句提示都没有，什么也说明不了。
wait_ready() { # base — the app needs a moment to apply migrations and bind the port
  local base="$1" attempt
  for attempt in $(seq 1 90); do
    sleep 1
    if curl -s -o /dev/null "$base/ai/tools"; then return 0; fi
  done
  echo "  FAIL the application did not answer at $base within 90s" >&2
  return 1
}

check() { # name expected actual — literal match: the expected strings contain JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi
}
check_absent() { # name unexpected actual — the negative half of a scope assertion
  if printf '%s' "$3" | grep -qF "$2"; then echo "  FAIL $1 -- '$2' must not appear in: $3"; fail=1; else echo "  ok   $1"; fi
}
get() { curl -s -H "Authorization: Bearer $1" "$BASE/$2"; }

scope_assertions() {
  fail=0
  A_CUSTOMERS="$(get "$ALICE" customers)"
  B_CUSTOMERS="$(get "$BOB" customers)"
  check        "alice sees her own seeded customer"        '晨光科技'   "$A_CUSTOMERS"
  check        "and the second one"                        '远山医疗'   "$A_CUSTOMERS"
  check_absent "and not bob's"                             '海州物流'   "$A_CUSTOMERS"
  check        "bob sees his own seeded customer"          '海州物流'   "$B_CUSTOMERS"
  check_absent "and not alice's"                           '晨光科技'   "$B_CUSTOMERS"
  check        "alice sees the seeded follow-up"           '首访：已确认续约意向' "$(get "$ALICE" follow_ups)"
  return "$fail"
}

echo "== 1/5 install the modules =="
mvn -q -B -DskipTests install
echo "== 2/5 generate the project =="
rm -rf "$GEN_DIR"
mvn -q -B -DskipTests -pl keelbase4j-generator compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR"
echo "== 3/5 build the generated project =="
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests
JAR="$(ls "$ROOT/$GEN_DIR"/target/*.jar | head -1)"
echo "   jar: $JAR"

echo "== 4/5 boot once so the schema exists, then seed out of band =="
# The database is an H2 **file** opened by one process at a time (no AUTO_SERVER), so the seed cannot run
# beside the app: boot once to let Flyway create the schema, stop, seed, and boot again for the demo.
stop_app
boot
stop_app
H2_CP="$ROOT/$GEN_DIR/h2-classpath.txt"
mvn -q -B -f "$GEN_DIR/pom.xml" dependency:build-classpath -Dmdep.outputFile="$H2_CP"
H2_JAR="$(tr ';' '\n' < "$H2_CP" | grep -i "h2-[0-9]" | head -1)"
if [ -z "$H2_JAR" ]; then echo "  FAIL could not locate the H2 jar" >&2; exit 1; fi
# No `-user`: the generated application configures its datasource **by URL only**, so H2 creates the
# database with the **empty** user as its owner. Passing `sa` (the usual guess) fails with
# `Wrong user name or password [28000-240]` — measured, not assumed.
#
# 不带 `-user`：生成物**只配了 URL**，所以 H2 是用**空用户名**建的库；传 `sa`（通常的猜法）会得到
# `Wrong user name or password [28000-240]` —— 实测如此。
( cd "$ROOT/$GEN_DIR" && java -cp "$H2_JAR" org.h2.tools.RunScript \
    -url "jdbc:h2:file:./data/crm;WRITE_DELAY=0" -script "$SEED" )
echo "   seeded: $(basename "$SEED")"

echo "   minting delegation tokens for alice / bob"
ALICE="$(mint alice)"
BOB="$(mint bob)"
if [ -z "$ALICE" ] || [ -z "$BOB" ]; then echo "  FAIL could not mint tokens" >&2; exit 1; fi

if [ "$MODE" = container ]; then
  echo "== 5/5 run it as the container, and prove the mount =="
  cp "$ROOT/scripts/flagship/Dockerfile" "$ROOT/$GEN_DIR/Dockerfile"
  docker compose -f "$COMPOSE" up -d --build
  wait_ready "$BASE"
  scope_assertions || { echo "  FAIL — see: docker compose -f docker-compose.flagship.yml logs app" >&2; exit 1; }
  # The assertion the mount exists: if `/app/data` were not mounted, the app would create it inside the
  # container, and a restart would come back to an empty database. Restart and look for the same rows.
  echo "   restarting the container — the rows must survive, or the database is in the writable layer"
  docker compose -f "$COMPOSE" restart app >/dev/null
  wait_ready "$BASE"
  check "the seeded rows survive a container restart" '晨光科技' "$(get "$ALICE" customers)"
  check_absent "and the other identity still cannot see them" '晨光科技' "$(get "$BOB" customers)"
  [ "$fail" -eq 0 ] || { echo "  FAIL — the data did not survive the restart" >&2; exit 1; }
  compose_down
  echo "  PASS — the generated application runs as a container, and its database lives on the mount"
else
  echo "== 5/5 boot and assert that each identity sees its own rows =="
  boot
  if ! scope_assertions; then echo "  FAIL — see $ROOT/$GEN_DIR/app.log" >&2; exit 1; fi
  echo "  PASS — the generated application serves seeded data, and the row gate holds per identity"
fi
