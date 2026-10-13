#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# JV-32 B — 「能试点」: start the *generated* application on PostgreSQL, and read what that proves.
#
# The acceptance line's B tier asks whether this artifact can run in a customer's environment, and the
# three things it names are a PostgreSQL database, a schema that arrives by migration, and rows that go
# in and come back out. This script is that tier's instrument — and until now the tier had none: the
# 2026-10-12 reading was taken by hand, so nothing re-ran it and the reading could only age. What it
# asserts is the same list, each item with a reading or the exit code.
#
# It is deliberately **not** the flagship demo (`demo-flagship-generated.sh`): that one is about the
# container, the seeded data and the console on one origin. This one is about one thing only — the
# database being PostgreSQL rather than the file-backed H2 the generated application ships with — so it
# starts nothing but the database and the application.
#
#   bash scripts/demo-generated-app-pg.sh                     # database on 15432, application on 18088
#   PG_PORT=… APP_PORT=… GEN_DIR=… bash scripts/demo-generated-app-pg.sh
#
# Needs Docker (the database runs in it) and a JDK. The `ddl-auto=validate` assertion is the load-bearing
# one: Hibernate checks every mapped column against what Flyway built, and the application refuses to
# start when they disagree — which is what makes this a real database reading rather than a checkbox.
#
# JV-32 B —— 「能试点」：把**生成物**起在 PostgreSQL 上，并读出这件事证明了什么。
#
# 验收线的 B 档问的是这个产物**能不能在客户环境里跑**，它点名的三件事是：一个 PostgreSQL 库、一份靠**迁移**到达的
# schema、以及行**写得进也读得回**。本脚本就是这一档的仪器 —— 而这一档此前**没有仪器**：2026-10-12 那份读数是**手工**
# 取的，于是没有任何东西会重跑它，那份读数只会变旧。它断言的就是同一张清单，每一项都有读数或退出码可判。
#
# 它**刻意不是**旗舰演示（`demo-flagship-generated.sh`）：那个讲的是容器、种子数据与同源上的控制台；这个只讲一件事
# —— 库是 **PostgreSQL** 而不是生成物自带的文件版 H2 —— 所以它只起数据库与那一个应用。
#
#   bash scripts/demo-generated-app-pg.sh                     # 数据库在 15432，应用在 18088
#   PG_PORT=… APP_PORT=… GEN_DIR=… bash scripts/demo-generated-app-pg.sh
#
# 需要 Docker（数据库跑在里面）与一个 JDK。**承载性**的那条断言是 `ddl-auto=validate`：Hibernate 把每个映射列
# 拿去对 Flyway 建出来的东西，不一致就**拒绝启动** —— 正是它让这里是一份**真数据库的读数**、而不是一个勾选框。

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

GEN_DIR="${GEN_DIR:-$ROOT/target/gen-pg-demo}"
PG_PORT="${PG_PORT:-15432}"
APP_PORT="${APP_PORT:-18088}"
PG_CONTAINER="${PG_CONTAINER:-keelbase4j-pg-demo}"
PG_DB="${PG_DB:-keelbase_b}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
# The generated application's login is closed unless this is set (JV-43): with it unset it refuses
# every login rather than shipping a passphrase of its own.
DEMO_PASSWORD="${KEELBASE_DEMO_PASSWORD:-$(openssl rand -hex 6 2>/dev/null || echo keelbase-demo)}"
BASE="http://localhost:$APP_PORT/api/v1"

APP_PID=""
cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1
}
trap cleanup EXIT

fail=0
check() { # name expected actual — literal match: the expected strings contain SQL/JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi
}
psql_() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1"; }
sign_in() { # username — the generated application's own login, with this run's passphrase
  curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$DEMO_PASSWORD\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4
}

echo "== 1/5 the database =="
docker rm -f "$PG_CONTAINER" >/dev/null 2>&1
docker run -d --name "$PG_CONTAINER" -e POSTGRES_PASSWORD="$PG_PASSWORD" -e POSTGRES_DB="$PG_DB" \
  -p "$PG_PORT:5432" postgres:17 >/dev/null
ready=0
for _ in $(seq 1 60); do
  if docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || {
  echo "  FAIL PostgreSQL never became ready on port $PG_PORT (is something else holding it?)"
  docker logs "$PG_CONTAINER" 2>&1 | tail -10
  exit 1
}
check "it is PostgreSQL 17" "PostgreSQL 17" "$(psql_ 'select version()')"

echo "== 2/5 generate and build the application =="
rm -rf "$GEN_DIR"
mvn -q -B -DskipTests -pl keelbase4j-generator compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR" > "$GEN_DIR.gen.log" 2>&1 \
  || { echo "  FAIL the generator produced nothing -- see $GEN_DIR.gen.log"; exit 1; }
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests > "$GEN_DIR.build.log" 2>&1 \
  || { echo "  FAIL the generated project did not build -- see $GEN_DIR.build.log"; tail -20 "$GEN_DIR.build.log"; exit 1; }
JAR="$(ls "$GEN_DIR"/target/*.jar | head -1)"
check "the generated project builds into a jar" ".jar" "$JAR"

echo "== 3/5 start it, against PostgreSQL =="
# Refuse to start on a port somebody else is holding. Without this the readiness probe below cannot
# tell whose answer it got — a leftover instance answers 401 on its first request, the loop declares the
# application up, and every reading after that is about the wrong process. (Learned the hard way: a run
# whose cleanup killed the launching shell and left the JVM listening made exactly that happen.)
#
# 端口被别人占着就**不开**。没有这一条，下面的就绪探针**分不清**它拿到的是谁的应答 —— 一个遗留实例会对第一条请求
# 答 401，循环随即宣布应用已就绪，往后每一条读数说的都是**另一个进程**。（实测踩过：一次运行的清理杀掉的是**发起它的
# 那个外壳**、把 JVM 留在监听，于是上面这件事真的发生了。）
if netstat -ano 2>/dev/null | grep -q "LISTENING.*:$APP_PORT "; then
  echo "  FAIL port $APP_PORT is already being listened on — refusing to read somebody else's process:"
  netstat -ano 2>/dev/null | grep "LISTENING.*:$APP_PORT " | head -3
  exit 1
fi
( cd "$GEN_DIR" && KEELBASE_DEMO_PASSWORD="$DEMO_PASSWORD" exec java -jar "$JAR" \
    --server.port="$APP_PORT" \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$PG_PORT/$PG_DB" \
    --spring.datasource.username="$PG_USER" --spring.datasource.password="$PG_PASSWORD" ) \
  > "$GEN_DIR/app.log" 2>&1 &
APP_PID=$!
# Ready means **our** passphrase signs in — not merely that something answers. This run generated that
# passphrase, so a token proves the process is the one just started and that it has finished booting.
TOKEN=""
for _ in $(seq 1 90); do
  TOKEN="$(sign_in alice)"
  [ -n "$TOKEN" ] && break
  sleep 1
done
[ -n "$TOKEN" ] || {
  echo "  FAIL our passphrase never signed in — see $GEN_DIR/app.log (a validate mismatch fails the boot)"
  tail -25 "$GEN_DIR/app.log"
  exit 1
}
LOG="$(cat "$GEN_DIR/app.log")"
check "it is talking to PostgreSQL, not to the shipped H2" "Database: jdbc:postgresql://" "$LOG"
check "the migrations applied" "Successfully applied" "$LOG"
check "Hibernate validated the entity mapping against the migrated schema" \
  "Initialized JPA EntityManagerFactory" "$LOG"
check "it serves under the deployed context path" "context path '/api/v1'" "$LOG"
# Every migration in the history succeeded, and there is at least one — asserted from the database
# rather than from the log, so a log line cannot stand in for a row that is not there. The version
# count is deliberately not pinned: it grows, and a check that breaks when it does is a check that
# gets deleted.
check "every migration in flyway_schema_history succeeded" "t" \
  "$(psql_ "select coalesce(bool_and(success), false) from flyway_schema_history")"

echo "== 4/5 the API answers =="
check "a signed-in caller reaches their rows" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE/customers")"
check "and is answered as themselves" '"username":"alice"' \
  "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/auth/me")"
# The tool inventory is administrators-only (JV-48's posture, carried into the generated copy). Asserted
# both ways, because "403 for a user" alone would also be true of an endpoint that is simply broken.
check "the tool inventory is refused to a plain caller" "403" \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE/ai/tools")"
check "and answered to an administrator" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(sign_in carol)" "$BASE/ai/tools")"

echo "== 5/5 a row goes in through the database and comes back out through the API =="
# Written straight into PostgreSQL, read back over HTTP: one store, two doors. A row that came back
# would also come back from a cache, so writing it underneath the application is what makes this a
# reading about the database.
ROW="PG-inserted row $(date +%s)"
if psql_ "insert into customers (name, level, owner_user_id) values ('$ROW', 'low', 'alice')" >/dev/null; then
  check "a row written under the application is read back over the API" "$ROW" \
    "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/customers")"
else
  echo "  FAIL the insert into customers was refused — did the baseline change its columns?"
  fail=1
fi
# JV-37: the generated entities map `Instant`, which Hibernate wants as a zoned type. A bare TIMESTAMP
# passes `validate` and then rounds every instant through the JVM's zone on the way in and out — the
# difference no other assertion here would catch. Read from the catalog, not from the migration text.
check "an instant column is a zoned timestamp, not a bare one" "timestamp with time zone" \
  "$(psql_ "select data_type from information_schema.columns where table_name='customers' and column_name='deleted_at'")"

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS — the generated application runs on PostgreSQL: migrated, validated, answering, and round-tripping a row"
else
  echo "FAIL"
fi
exit "$fail"
