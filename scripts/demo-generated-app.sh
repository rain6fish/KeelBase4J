#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end demo: generate an application from a business request, build it, run it, walk the
# KeelBase trust loop against the *generated* artifact (S3 axis A + S4 on generated output), and
# check the row-level scope its generated authorization enforces.
#
#   1. install the protocol library
#   2. generate the project (dev entry point)
#   3. build the generated project into a runnable jar
#   4. start it and exercise: read auto / write gated / approve / audit verify / revoke /
#      capability list / no-identity 401 / own-scope row filtering
#
# Exits non-zero if any expected outcome is missing. No network beyond Maven's own resolution.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

GEN_DIR="target/gen-demo"
PORT="${PORT:-18080}"
BASE="http://localhost:$PORT"

echo "== 1/4 install protocol library =="
mvn -q -B -DskipTests install

echo "== 2/4 generate the project =="
rm -rf "$GEN_DIR"
mvn -q -B -DskipTests compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR"

echo "== 3/4 build the generated project =="
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests
JAR="$(ls "$ROOT/$GEN_DIR"/target/*.jar | head -1)"
echo "   jar: $JAR"

echo "== 4/4 run and exercise the trust loop =="
# Run from inside the generated project: its database is file-backed and belongs to the project.
( cd "$ROOT/$GEN_DIR" && exec java -jar "$JAR" --server.port="$PORT" ) > "$ROOT/$GEN_DIR/app.log" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  sleep 1
  curl -s -o /dev/null "$BASE/ai/tools" && break
done

fail=0
check() { # name expected actual — literal match: the expected strings contain JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi
}
check_absent() { # name unexpected actual — the negative half of a scope assertion
  if printf '%s' "$3" | grep -qF "$2"; then echo "  FAIL $1 -- '$2' must not appear in: $3"; fail=1; else echo "  ok   $1"; fi
}

TOOLS=$(curl -s "$BASE/ai/tools")
check "tools exposed (R1/R3)" '"riskLevel":"R3"' "$TOOLS"

READ=$(curl -s -X POST "$BASE/ai/chat" -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
  -d '{"tool":"analyze_customer_risk"}')
check "read tool auto-executes" '"status":"executed"' "$READ"

WRITE=$(curl -s -X POST "$BASE/ai/chat" -H 'Content-Type: application/json' -H 'X-User-Id: alice' \
  -d '{"tool":"create_followup","customerId":1,"note":"renewal reminder"}')
check "write tool is gated" '"status":"pending_confirmation"' "$WRITE"
TOKEN=$(printf '%s' "$WRITE" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

APPROVED=$(curl -s -X POST "$BASE/ai/confirmations/$TOKEN" -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -d '{"decision":"approve"}')
check "approve executes and records an effect" '"effectId"' "$APPROVED"

VERIFY=$(curl -s "$BASE/audit/verify")
check "audit chain verifies" '"valid":true' "$VERIFY"

# The identity seam and the contract-derived decision, on the generated artifact.
PERMS=$(curl -s "$BASE/auth/me/permissions" -H 'X-User-Id: alice')
check "capability list served in the frozen shape" '"subject":"Customer","scope":"own"' "$PERMS"
check "role is the contract's vocabulary" '"role":"user"' "$PERMS"
ANON=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/me/permissions")
check "no identity => 401 (nothing runs anonymously)" "401" "$ANON"

EFF=$(curl -s "$BASE/ai/tool-effects" -H 'X-User-Id: alice' | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
REVOKED=$(curl -s -X DELETE "$BASE/ai/tool-effects/$EFF" -H 'X-User-Id: alice')
check "revoke marks the effect revoked" '"revokeStatus":"revoked"' "$REVOKED"

# ── own scope, on its own ───────────────────────────────────────────────────────────────────────
# The base spec carries no policy, so a plain user holds `update` — whatever produces the 403 below,
# it cannot be a missing action. That leaves the row check, which is the branch under test.
ALICE_CUST=$(curl -s -X POST "$BASE/customers" -H 'Content-Type: application/json' \
  -H 'X-User-Id: alice' -d '{"name":"alice-co","level":"low"}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
BOB_CUST=$(curl -s -X POST "$BASE/customers" -H 'Content-Type: application/json' \
  -H 'X-User-Id: bob' -d '{"name":"bob-co","level":"low"}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)

NOT_MINE=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$BASE/customers/$ALICE_CUST" \
  -H 'Content-Type: application/json' -H 'X-User-Id: bob' -d '{"level":"high"}')
check "a non-owner is denied by the row check (403)" "403" "$NOT_MINE"

MINE=$(curl -s -X PATCH "$BASE/customers/$BOB_CUST" -H 'Content-Type: application/json' \
  -H 'X-User-Id: bob' -d '{"level":"high"}')
check "an owner may update their own row" '"level":"high"' "$MINE"

BOB_LIST=$(curl -s "$BASE/customers" -H 'X-User-Id: bob')
check "own scope narrows the list to the caller's rows" '"name":"bob-co"' "$BOB_LIST"
check_absent "own scope keeps other owners' rows out" '"name":"alice-co"' "$BOB_LIST"

MGR_LIST=$(curl -s "$BASE/customers" -H 'X-User-Id: carol' -H 'X-User-Role: manager')
check "the unrestricted scope sees every row" '"name":"alice-co"' "$MGR_LIST"

if grep -qE "Exception" "$GEN_DIR/app.log"; then echo "  FAIL runtime exception in app.log"; fail=1; fi

echo
if [ "$fail" -eq 0 ]; then echo "PASS — generated app runs, the trust loop holds, and row scope is enforced"; else echo "FAIL"; fi
exit "$fail"
