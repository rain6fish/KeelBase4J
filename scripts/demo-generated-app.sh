#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end demo: generate an application from a business request, build it, run it, and walk
# the KeelBase trust loop against the *generated* artifact (S3 axis A + S4 on generated output).
#
#   1. install the protocol library
#   2. generate the project (dev entry point)
#   3. build the generated project into a runnable jar
#   4. start it and exercise: read auto / write gated / approve / audit verify / revoke
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
JAR="$(ls "$GEN_DIR"/target/*.jar | head -1)"
echo "   jar: $JAR"

echo "== 4/4 run and exercise the trust loop =="
java -jar "$JAR" --server.port="$PORT" > "$GEN_DIR/app.log" 2>&1 &
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

if grep -qE "Exception" "$GEN_DIR/app.log"; then echo "  FAIL runtime exception in app.log"; fail=1; fi

echo
if [ "$fail" -eq 0 ]; then echo "PASS — generated app runs and the trust loop holds"; else echo "FAIL"; fi
exit "$fail"
