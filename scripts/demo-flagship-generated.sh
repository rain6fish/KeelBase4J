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
# rather than assumed: its `analyze_customer_risk` is a **stub** that computes nothing, so the seed
# deliberately plants no "customer at risk" because there is no such signal to plant; and — since JV-43
# — the generated application **has a login**, so the console served against it signs in as one of the
# demo identities by name and passphrase rather than being handed a token. The passphrase is generated
# per run and injected below, and printed with the login instructions.
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
# 这个产物有两件事值得在断言之前知道，且两件都是**实测**不是假定：它的 `analyze_customer_risk` 是**桩**、
# 什么都不算——所以种子**刻意不造「有风险的客户」**，因为**没有那个信号可种**；以及——自 JV-43 起——生成物
# **有了登录面**，故对着它伺服的这场演示里，控制台是用**名字加口令**登进某个演示身份的，而不是被塞一枚令牌。
# 口令每次运行现生成、在下面注入，并与登录说明一起打印。
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
# The passphrase that turns on the generated application's login (JV-43). Generated per run and exported,
# so it reaches both the host-mode `java -jar` and the container: the application reads
# KEELBASE_DEMO_PASSWORD, and with it unset it refuses every login rather than shipping one of its own.
#
# 打开生成物登录面（JV-43）的口令。每次运行现生成并导出，故宿主模式的 `java -jar` 与容器都能收到：应用
# 读 KEELBASE_DEMO_PASSWORD，而未设时它拒绝每一次登录，而不是自带一个。
DEMO_PASSWORD="${KEELBASE_DEMO_PASSWORD:-$(openssl rand -hex 6 2>/dev/null || echo keelbase-demo)}"
export KEELBASE_DEMO_PASSWORD="$DEMO_PASSWORD"
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
    # `MSYS_NO_PATHCONV=1` is load-bearing, and measured rather than assumed: Git Bash rewrites an
    # assignment whose value looks like an absolute path, so `/api/v1` reached npm as
    # `D:/Develop/Git/api/v1` and Vite baked *that* into the bundle. The console then called a file
    # path, so the browser issued **no request at all** — a failure that looks like an application
    # error and leaves nginx with nothing to log. (Plain prefix and `export` both still get rewritten;
    # only this variable stops it, which is why the fix is here rather than a different shape.)
    #
    # 基址**相对**是有意的：nginx 伺服这份构建、并把 /api/ 反代给应用，于是浏览器只跟一个源打交道，
    # 也就没有 CORS 要安排。
    #
    # `MSYS_NO_PATHCONV=1` 是**关键**，且是实测不是假定：Git Bash 会改写「值看起来像绝对路径」的赋值，
    # 于是 `/api/v1` 到 npm 手上已是 `D:/Develop/Git/api/v1`，Vite 把它烧进了产物。控制台随后去调一个
    # 文件路径，浏览器**一条请求都不发**——这个故障看起来像应用出错，还让 nginx 无记录可查。（普通前缀与
    # `export` 都会被改写，只有这个变量能挡住，所以修在这里、而不是换个写法。）
    ( cd "$FRONTEND_DIR" && MSYS_NO_PATHCONV=1 VITE_API_BASE=/api/v1 npm run build ) > "$ROOT/$GEN_DIR/web-build.log" 2>&1
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
    # The bundle must carry the **relative** base, and this assertion is the one that can see it: the
    # curl checks below bypass the bundle entirely, so a wrong base leaves every one of them green
    # while the browser issues no request at all. It is not hypothetical — with the base rewritten to
    # a Windows path the console called a file path and the demo was broken with the harness passing.
    # The quote is what discriminates: `"D:/.../api/v1"` contains `/api/v1"` but not `"/api/v1"`.
    #
    # 产物必须带**相对**基址，而这条断言是唯一能看见它的：下面的 curl 断言根本不经过产物，所以基址错了
    # 它们照样全绿，而浏览器**一条请求都不发**。这不是假想——基址被改写成 Windows 路径时，控制台去调
    # 文件路径，演示是坏的、而 harness 通过。**引号**才是判别位：`"D:/.../api/v1"` 含 `/api/v1"`、
    # 但不含 `"/api/v1"`。
    if ! grep -rq '"/api/v1"' "$ROOT/$GEN_DIR/web/$ENTRY/assets" 2>/dev/null; then
      echo "  FAIL the console bundle does not carry the relative API base" >&2
      echo "       found instead: $(grep -rho '"[^"]*api/v1"' "$ROOT/$GEN_DIR/web/$ENTRY/assets" | sort -u | head -3 | tr '\n' ' ')" >&2
      echo "       it was rewritten on the way to npm — see the MSYS note on the build line" >&2
      exit 1
    fi
    echo "  ok   the console bundle carries the relative API base"
    # The console signs in for real (JV-43): the generated application serves `POST /auth/login` and
    # `GET /auth/me`, so nothing is injected into the page any more. What is asserted is the path the
    # browser walks — sign in as a demo identity with this run's passphrase, then read the identity and
    # the data back through the same origin that proxies the API.
    #
    # 控制台现在**真登录**（JV-43）：生成物提供 `POST /auth/login` 与 `GET /auth/me`，故不再往页面里注入
    # 任何东西。断言的是浏览器要走的那条路——用本次运行的口令以某个演示身份登录，再经由那个反代接口的
    # 同源，把身份与数据读回来。
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

    # `login()` — the endpoint the console itself calls, with the passphrase the script injected into the
    # container. `json_field` reads a value out of the response rather than matching punctuation, so a
    # change in key order does not turn a working login into a red assertion.
    #
    # `login()` —— 控制台自己调的那个端点，用脚本注入容器的口令。`json_field` 从响应里**取值**、而不是
    # 去匹配标点，于是键序变化不会把一个能用的登录变成一条红的断言。
    login_as() { # userId
      curl -s -X POST -H 'Content-Type: application/json' \
        -d "{\"username\":\"$1\",\"password\":\"$DEMO_PASSWORD\"}" "$WEB/api/v1/auth/login"
    }
    json_field() { # field json
      printf '%s' "$2" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"
    }
    LOGIN_ALICE="$(login_as alice)"
    UI_TOKEN="$(json_field accessToken "$LOGIN_ALICE")"
    if [ -z "$UI_TOKEN" ]; then echo "  FAIL login returned no accessToken: $LOGIN_ALICE" >&2; exit 1; fi
    check "the console's login answers for alice"       'alice' "$(json_field username "$LOGIN_ALICE")"
    check "and reports the role the shell routes on"    'user'  "$(json_field role "$LOGIN_ALICE")"
    check "while the admin identity reports admin"      'admin' "$(json_field role "$(login_as carol)")"
    # The session the login minted is the token the rest of the surface already verifies: `/auth/me` reads
    # it back, and the row gate honours it — so this is one chain, not a login-shaped side door.
    #
    # 登录铸出的会话，就是这个面其余部分已经在验的那枚令牌：`/auth/me` 把它读回来，行闸也认它——所以这是
    # **一条**链，不是一扇做成登录样子的侧门。
    check "and /auth/me answers the signed-in identity"   "$UI_AS" \
      "$(json_field username "$(curl -s -H "Authorization: Bearer $UI_TOKEN" "$WEB/api/v1/auth/me")")"
    check "and the same origin serves the API for $UI_AS" '晨光科技' \
      "$(curl -s -H "Authorization: Bearer $UI_TOKEN" "$WEB/api/v1/customers")"
    # Fail-closed both ways: a wrong passphrase and an unknown user are refused the same way, so the
    # login says nothing about which identities this deployment knows.
    #
    # 两个方向都关得死：口令不对与用户未知被**同样**拒绝，于是登录不会泄露本部署认识哪些身份。
    check "a wrong passphrase is refused" '401' "$(curl -s -o /dev/null -w '%{http_code}' \
      -X POST -H 'Content-Type: application/json' \
      -d '{"username":"alice","password":"not-the-passphrase"}' "$WEB/api/v1/auth/login")"
    check "and an unknown user is refused the same way" '401' "$(curl -s -o /dev/null -w '%{http_code}' \
      -X POST -H 'Content-Type: application/json' \
      -d "{\"username\":\"nobody\",\"password\":\"$DEMO_PASSWORD\"}" "$WEB/api/v1/auth/login")"
    [ "$fail" -eq 0 ] || { echo "  FAIL — see: docker compose -f docker-compose.flagship.yml logs web" >&2; exit 1; }

    echo ""
    echo "  The front end is served against the generated application — open this in a browser:"
    echo "    $WEB/$ENTRY/"
    echo "  Sign in with a demo identity. This run's passphrase was generated, and is:"
    echo "    $DEMO_PASSWORD"
    echo "    alice / bob   salespeople — land on the workbench and see only their own rows"
    echo "    carol         the admin — lands in the console"
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
