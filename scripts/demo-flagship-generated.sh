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
# Three things about this artifact are worth knowing before reading the assertions, and all three were
# measured rather than assumed: the generated application ships **no login** (its auth controller serves
# the permissions read only, so a caller proves itself with a delegation token); its
# `analyze_customer_risk` is a **stub** that computes nothing, so the seed deliberately plants no
# "customer at risk" because there is no such signal to plant; and for the same reason the console, once
# served against it, **renders but stops at its own login page** — it restores a session through
# `GET /auth/me` and signs in through `POST /auth/login`, neither of which this application serves.
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
UI=0
for arg in "$@"; do
  case "$arg" in
    --container) MODE="container" ;;
    --ui) MODE="container"; UI=1 ;;   # the clickable demo needs the container's shared origin
  esac
done
UI_AS="${UI_AS:-alice}"
FRONTEND_DIR="${FRONTEND_DIR:-$ROOT/../KeelBase/Web-Admin-Vue}"

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
  echo "  PASS — the generated application runs as a container, and its database lives on the mount"

  if [ "$UI" = "1" ]; then
    echo "== 6/6 build the front end, and serve it on the app's own origin =="
    if [ ! -d "$FRONTEND_DIR" ]; then
      echo "  FAIL no front end at $FRONTEND_DIR — set FRONTEND_DIR to the Web-Admin-Vue checkout" >&2
      exit 1
    fi
    # The base is **relative** on purpose: nginx serves this build and proxies /api/ to the app, so the
    # browser talks to one origin and there is no CORS to arrange.
    #
    # 基址**相对**是有意的：nginx 伺服这份构建、并把 /api/ 反代给应用，于是浏览器只跟一个源打交道，
    # 也就没有 CORS 要安排。
    ( cd "$FRONTEND_DIR" && VITE_API_BASE=/api/v1 npm run build ) > "$ROOT/$GEN_DIR/web-build.log" 2>&1
    rm -rf "$ROOT/$GEN_DIR/web"
    cp -r "$FRONTEND_DIR/dist" "$ROOT/$GEN_DIR/web"
    # The console is an **entry**, not the root of the build: `Web-Admin-Vue` emits `dist/admin/index.html`
    # (the deployed convention), so the page to inject into and the URL to open both carry its name.
    #
    # 控制台是一个**入口**、不是构建的根：`Web-Admin-Vue` 产出的是 `dist/admin/index.html`（部署约定），
    # 故要注入的页面与要打开的地址都带着它的名字。
    ENTRY="${UI_ENTRY:-admin}"
    IDX="$ROOT/$GEN_DIR/web/$ENTRY/index.html"
    if [ ! -f "$IDX" ]; then
      echo "  FAIL the front end build has no $ENTRY/index.html — see $ROOT/$GEN_DIR/web-build.log" >&2
      exit 1
    fi
    # The token the console would keep after signing in. It goes where the console looks for it —
    # `admin_access_token`, raw string, measured — but **it does not sign you in**: this console restores
    # a session only through `GET /auth/me`, and signs in only through `POST /auth/login`, and the
    # generated application serves neither (its auth controller answers **`/auth/me/permissions` only**,
    # because it authenticates delegated callers rather than people). Measured in a real browser: the
    # console loads, renders, and stops at its login page having called no API at all. Closing that gap
    # means adding a login surface to the generated application — a change to the product surface, which
    # is why it is not done here.
    #
    # 控制台登录后会保存的那枚令牌。它被放到控制台会去找的地方——`admin_access_token`、原样字符串、实测
    # ——但**它并不能让你登录**：这个控制台只通过 `GET /auth/me` 恢复会话、只通过 `POST /auth/login` 登录，
    # 而生成物**两个都不提供**（它的认证控制器**只回答 `/auth/me/permissions`**，因为它认证的是被委托的
    # 调用方、而不是人）。真浏览器实测：控制台加载、渲染、停在它自己的登录页上，**一条 API 都没调**。要
    # 补上这个差，得给生成物加一层登录面——那是对**产品面**的改动，所以不在这里做。
    TOK="$(mint "$UI_AS")"
    if [ -z "$TOK" ]; then echo "  FAIL could not mint a token for $UI_AS" >&2; exit 1; fi
    awk -v tok="$TOK" 'BEGIN{done=0} { if (!done && index($0, "</head>")) {
        printf "<script>localStorage.setItem(\"admin_access_token\",\"%s\")</script>\n", tok; done=1 } print }' \
      "$IDX" > "$IDX.tmp" && mv "$IDX.tmp" "$IDX"
    grep -qF 'admin_access_token' "$IDX" || { echo "  FAIL the token stand-in was not injected into $IDX" >&2; exit 1; }

    export FLAGSHIP_WEB_PORT="${FLAGSHIP_WEB_PORT:-18087}"
    WEB="http://localhost:$FLAGSHIP_WEB_PORT"
    docker compose -f "$COMPOSE" up -d
    wait_ready "$WEB/api/v1"
    check "the console is served"                         '200' "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/$ENTRY/")"
    # The short URL *redirects* to the console — that is what nginx.conf configures, so 302 is the
    # assertion, and the check above proves where it lands.
    #
    # 短地址**重定向**到控制台——这是 nginx.conf 配的行为，故断言就是 302；而上面那条断言证明它落到哪。
    check "the demo's short URL redirects to the console" '302' "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/")"
    check "and the page carries the token stand-in"       'admin_access_token' "$(cat "$IDX")"
    # The path the browser will actually walk: the front end's own origin, the injected token, the API
    # through the proxy — so this asserts the demo's wiring, not just that two ports answer.
    check "and the same origin serves the API for $UI_AS" '晨光科技' "$(curl -s -H "Authorization: Bearer $TOK" "$WEB/api/v1/customers")"
    [ "$fail" -eq 0 ] || { echo "  FAIL — see: docker compose -f docker-compose.flagship.yml logs web" >&2; exit 1; }

    echo ""
    echo "  The front end is served against the generated application — open this in a browser:"
    echo "    $WEB/$ENTRY/            (it renders; it lands on its own login page — see the note above)"
    echo "  The console cannot be signed into on this artifact: it wants /auth/me and /auth/login, which"
    echo "  the generated application does not serve. Its pages are still reachable once a session exists;"
    echo "  to look as $UI_AS by hand, paste this in the browser console and reload:"
    echo "    localStorage.setItem('admin_access_token','$TOK'); location.reload()"
    echo "  ($UI_AS's token; swap in another identity's the same way — the app answers for it.)"
    echo "  Ctrl-C to stop."
    if [ "${NO_WAIT:-0}" = "1" ]; then
      echo "   NO_WAIT=1 — automated run: not waiting, and the stack is taken down below"
    else
      while true; do sleep 3600; done
    fi
  fi
  compose_down
else
  echo "== 5/5 boot and assert that each identity sees its own rows =="
  boot
  if ! scope_assertions; then echo "  FAIL — see $ROOT/$GEN_DIR/app.log" >&2; exit 1; fi
  echo "  PASS — the generated application serves seeded data, and the row gate holds per identity"
fi
