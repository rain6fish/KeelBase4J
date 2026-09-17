#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# S5 demo — changeability: a change request flows semantic -> code -> app, regeneration preserves
# hand-written code, and the new rule is enforced at runtime.
#
#   1. install the protocol library
#   2. generate v1, then simulate a developer hand-edit inside the user-code region
#   3. apply the change request and regenerate into the SAME directory
#   4. assert the new field is present AND the hand edit survived
#   5. build + run; assert the new rule: a regular user is denied, a manager is allowed
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then
  # Accept Windows-style JAVA_HOME (C:\x or C:/x) under Git Bash: normalize to a POSIX path.
  JH="${JAVA_HOME//\\//}"
  if [[ "$JH" =~ ^([A-Za-z]):/ ]]; then JH="/${BASH_REMATCH[1],,}${JH:2}"; fi
  PATH="$JH/bin:$PATH"; export PATH
fi

GEN_DIR="target/s5-demo"
PORT="${PORT:-18081}"
BASE="http://localhost:$PORT"
ENTITY="$GEN_DIR/src/main/java/com/example/crm/domain/Customer.java"

echo "== 1/5 install protocol library =="
mvn -q -B -DskipTests install

echo "== 2/5 generate v1 + developer hand-edit =="
rm -rf "$GEN_DIR"
mvn -q -B -DskipTests compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR"
sed -i 's#// <keelbase:user-code>#// <keelbase:user-code>\n    public String displayName() { return getName(); }#' "$ENTITY"

echo "== 3/5 apply the change request and regenerate =="
mvn -q -B -DskipTests compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR changed"

fail=0
check() { # name expected actual — literal match: the expected strings contain JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi; }

echo "== 4/5 regeneration outcome =="
check "new field 'tier' present" "tier" "$(cat "$ENTITY")"
check "hand-written code survives" "displayName" "$(cat "$ENTITY")"

echo "== 5/5 build, run, enforce the new rule =="
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests
JAR="$(ls "$GEN_DIR"/target/*.jar | head -1)"
java -jar "$JAR" --server.port="$PORT" > "$GEN_DIR/app.log" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do sleep 1; curl -s -o /dev/null "$BASE/ai/tools" && break; done

ID=$(curl -s -X POST "$BASE/customers" -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
  -d '{"name":"Acme","level":"low"}' | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
echo "   created customer id=$ID"

USER_PATCH=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$BASE/customers/$ID" \
  -H 'Content-Type: application/json' -H 'X-User-Id: bob' -H 'X-User-Role: user' \
  -d '{"tier":"gold"}')
check "regular user is denied (403)" "403" "$USER_PATCH"

MGR_PATCH=$(curl -s -X PATCH "$BASE/customers/$ID" -H 'Content-Type: application/json' \
  -H 'X-User-Id: carol' -H 'X-User-Role: manager' -d '{"tier":"gold"}')
check "manager is allowed and the field changes" '"tier":"gold"' "$MGR_PATCH"

# Why the 403 above happened is now observable rather than inferred: the spec's policy reaches the
# caller as contract data. A hardcoded role check could not produce either of these answers.
BOB_PERMS=$(curl -s "$BASE/auth/me/permissions" -H 'X-User-Id: bob' -H 'X-User-Role: user')
check "the user's Customer capability excludes update" \
  '"subject":"Customer","scope":"own","actions":["create","read","delete"]' "$BOB_PERMS"
CAROL_PERMS=$(curl -s "$BASE/auth/me/permissions" -H 'X-User-Id: carol' -H 'X-User-Role: manager')
check "the manager's capability is unrestricted" '"subject":"all","scope":"all"' "$CAROL_PERMS"

if grep -qE "Exception" "$GEN_DIR/app.log"; then echo "  FAIL runtime exception in app.log"; fail=1; fi

echo
if [ "$fail" -eq 0 ]; then echo "PASS — change applied, hand edit preserved, rule enforced"; else echo "FAIL"; fi
exit "$fail"
