#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end demo: generate an application from a business request, build it, run it, walk the
# KeelBase trust loop against the *generated* artifact (S3 axis A + S4 on generated output), and
# check the row-level scope its generated authorization enforces.
#
#   1. install the modules (protocol + runtime + generator)
#   2. generate the project (dev entry point)
#   3. build the generated project into a runnable jar
#   4. start it and exercise: read auto / write gated / approve / the effects list in the console's
#      own shape / revoke with local compensation / audit verify / capability list / no-identity 401 /
#      own-scope row filtering
#
# Exits non-zero if any expected outcome is missing. No network beyond Maven's own resolution.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

GEN_DIR="target/gen-demo"
PORT="${PORT:-18080}"
BASE="http://localhost:$PORT/api/v1"

SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"
mint() {
  mvn -q -B -pl keelbase4j-demo exec:java \
    -Dexec.mainClass=cn.com.keelbase.demo.DevToken \
    -Dexec.args="$1 $SECRET" 2>/dev/null | tail -1
}

echo "== 1/4 install the modules =="
mvn -q -B -DskipTests install

echo "== 2/4 generate the project =="
rm -rf "$GEN_DIR"
mvn -q -B -DskipTests -pl keelbase4j-generator compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR"

echo "== 3/4 build the generated project =="
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests
JAR="$(ls "$ROOT/$GEN_DIR"/target/*.jar | head -1)"
echo "   jar: $JAR"

# Stop the app *and make sure it is gone*. Git Bash's `kill` cannot signal a native Windows process —
# it reports "no such process" and does nothing — so the app would outlive the script, holding the port
# and the database. Ask Windows which pid owns the port and terminate that.
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
    if netstat -ano 2>/dev/null | grep -q ":$PORT .*LISTENING"; then
      sleep 1
    else
      break
    fi
  done
}

echo "== 4/4 run and exercise the trust loop =="
stop_app
# Run from inside the generated project: its database is file-backed and belongs to the project.
( cd "$ROOT/$GEN_DIR" && exec java -jar "$JAR" --server.port="$PORT" ) > "$ROOT/$GEN_DIR/app.log" 2>&1 &
APP_PID=$!
trap stop_app EXIT

for _ in $(seq 1 60); do
  sleep 1
  curl -s -o /dev/null "$BASE/ai/tools" && break
done

# This app verifies the frozen delegation token, the way the runtime does, so a caller proves who it is
# rather than declaring it. carol is a manager here because this deployment's directory says so.
echo "   minting delegation tokens for alice / bob / carol"
ALICE="$(mint alice)"
BOB="$(mint bob)"
CAROL="$(mint carol)"
if [ -z "$ALICE" ] || [ -z "$BOB" ] || [ -z "$CAROL" ]; then
  echo "  FAIL could not mint tokens" >&2
  exit 1
fi

fail=0
check() { # name expected actual — literal match: the expected strings contain JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi
}
check_absent() { # name unexpected actual — the negative half of a scope assertion
  if printf '%s' "$3" | grep -qF "$2"; then echo "  FAIL $1 -- '$2' must not appear in: $3"; fail=1; else echo "  ok   $1"; fi
}

# POST a chat message with the body supplied on stdin, not as a curl argument. Git Bash hands an
# argv-supplied payload to the native curl.exe in the machine's ANSI code page, so a UTF-8 Chinese
# message sent with -d arrives as GBK and the server rejects it as malformed JSON ("Invalid UTF-8
# start byte 0xb7") — a 400 that says nothing about the application. Through a pipe the bytes are
# carried verbatim, whatever the console's code page is.
post_chat() { # token body
  printf '%s' "$2" | curl -s -X POST "$BASE/ai/chat" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $1" --data-binary @-
}

TOOLS=$(curl -s "$BASE/ai/tools")
check "tools exposed (R1/R3)" '"riskLevel":"R3"' "$TOOLS"

# The chat entry takes a *message* and answers the conversation shape the runtime answers — the same
# fields, so one frontend reads either. The reply is not from a model and says so, and conversationId
# names a stored turn rather than an id made up on the spot.
READ=$(post_chat "$ALICE" '{"message":"分析客户风险"}')
check "a message routes to the read tool and it auto-executes" '"status":"executed"' "$READ"
check "the answer names its conversation" '"conversationId"' "$READ"
check "and reports the tool it used" '"toolCalls":["analyze_customer_risk"]' "$READ"
check "the reply is honestly attributed to no model" '"provider":"deterministic","model":"none"' "$READ"

# A message that names no customer still routes — the write is gated on a human either way.
GATED=$(post_chat "$ALICE" '{"message":"给客户建一条跟进记录"}')
check "a message naming no customer still reaches the write tool" '"status":"pending_confirmation"' "$GATED"

WRITE=$(post_chat "$ALICE" '{"message":"给客户建一条跟进记录","customerId":1}')
check "write tool is gated" '"status":"pending_confirmation"' "$WRITE"
TOKEN=$(printf '%s' "$WRITE" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

APPROVED=$(curl -s -X POST "$BASE/ai/confirmations/$TOKEN" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE" -d '{"decision":"approve"}')
check "approve executes and records an effect" '"effectId"' "$APPROVED"

VERIFY=$(curl -s "$BASE/audit/verify")
check "audit chain verifies" '"valid":true' "$VERIFY"

# The identity seam and the contract-derived decision, on the generated artifact.
PERMS=$(curl -s "$BASE/auth/me/permissions" -H "Authorization: Bearer $ALICE")
check "capability list served in the frozen shape" '"subject":"Customer","scope":"own"' "$PERMS"
check "role is the contract's vocabulary" '"role":"user"' "$PERMS"
ANON=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/me/permissions")
check "no token => 401 (nothing runs anonymously)" "401" "$ANON"

# ── the capability surface, as this app actually serves it ───────────────────────────────────────
# F5's own rule is that the shell reads this *before* it holds a token, so it is fetched without one.
# Nothing else checks the served payload: the unit test pins the file that produces it, not the JSON.
CAPS=$(curl -s "$BASE/app/capabilities")
check "capabilities is served without a token (the shell reads it first)" '"preset":"full"' "$CAPS"
check "the module block is the contract's three keys, labelled as the module" \
  '{"id":"crm","label":"客户管理","description":""}' "$CAPS"

# ── the effects list, in the console's own shape ───────────────────────────────────────────────────
# The console pages through this list and renders a row from it: an envelope, and items carrying the
# fields its own model requires. A bare array was the shape here before — it reads fine by eye and is
# unusable in the console, which is why the envelope and the fields are what gets checked.
EFFECTS=$(curl -s "$BASE/ai/tool-effects" -H "Authorization: Bearer $ALICE")
check "the effects list is paginated" '"items":[' "$EFFECTS"
check "and reports the total it paged over" '"total":' "$EFFECTS"
check "and echoes the page it was asked for" '"page":1' "$EFFECTS"
check "and the limit" '"limit":20' "$EFFECTS"
for field in id toolName conversationId resultType resultId argsHash createdAt targetExists \
             targetSoftDeleted targetTitle; do
  check "a row carries '$field' for the console" "\"$field\":" "$EFFECTS"
done
CAPPED=$(curl -s "$BASE/ai/tool-effects?page=1&limit=100000" -H "Authorization: Bearer $ALICE")
# The trailing comma is load-bearing: `"limit":100` is a substring of `"limit":1000`, so without it a
# cap raised to a thousand would still pass. The envelope's key order puts `items` next.
check "a caller cannot ask for the whole table" '"limit":100,' "$CAPPED"

# Revoking compensates. The row the write created is soft-deleted, which is what makes
# `local_compensate` a class this application honours rather than one it merely claims.
EFF=$(printf '%s' "$EFFECTS" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
check "a live effect reports a target that is not deleted" '"targetSoftDeleted":false' "$EFFECTS"
REVOKED=$(curl -s -X DELETE "$BASE/ai/tool-effects/$EFF" -H "Authorization: Bearer $ALICE")
check "revoke marks the effect revoked" '"revokeStatus":"revoked"' "$REVOKED"
AFTER_REVOKE=$(curl -s "$BASE/ai/tool-effects" -H "Authorization: Bearer $ALICE")
check "and soft-deletes the row it created" '"targetSoftDeleted":true' "$AFTER_REVOKE"
check "which is still there — deleted, not gone" '"targetExists":true' "$AFTER_REVOKE"

# ── own scope, on its own ───────────────────────────────────────────────────────────────────────
# The base spec carries no policy, so a plain user holds `update` — whatever produces the 403 below,
# it cannot be a missing action. That leaves the row check, which is the branch under test.
ALICE_CUST=$(curl -s -X POST "$BASE/customers" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE" -d '{"name":"alice-co","level":"low"}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
BOB_CUST=$(curl -s -X POST "$BASE/customers" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BOB" -d '{"name":"bob-co","level":"low"}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)

NOT_MINE=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$BASE/customers/$ALICE_CUST" \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $BOB" -d '{"level":"high"}')
check "a non-owner is denied by the row check (403)" "403" "$NOT_MINE"

MINE=$(curl -s -X PATCH "$BASE/customers/$BOB_CUST" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BOB" -d '{"level":"high"}')
check "an owner may update their own row" '"level":"high"' "$MINE"

BOB_LIST=$(curl -s "$BASE/customers" -H "Authorization: Bearer $BOB")
check "own scope narrows the list to the caller's rows" '"name":"bob-co"' "$BOB_LIST"
check_absent "own scope keeps other owners' rows out" '"name":"alice-co"' "$BOB_LIST"

MGR_LIST=$(curl -s "$BASE/customers" -H "Authorization: Bearer $CAROL")
check "the unrestricted scope sees every row" '"name":"alice-co"' "$MGR_LIST"

# ── the second entity has a surface of its own ────────────────────────────────────────────────────
# It always had a table, a repository and a rule in the authorization source; what it did not have was
# a REST surface, so everything past the first entity was reachable only by hand. These checks fail if
# that comes back — and they also pin that a second entity is governed the same way, not less.
ALICE_FU=$(curl -s -X POST "$BASE/follow_ups" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ALICE" -d "{\"customerId\":$ALICE_CUST,\"note\":\"alice-fu\"}")
check "the second entity accepts a write of its own" '"note":"alice-fu"' "$ALICE_FU"
check "and stamps the caller as its owner" '"ownerUserId":"alice"' "$ALICE_FU"

curl -s -o /dev/null -X POST "$BASE/follow_ups" -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BOB" -d "{\"customerId\":$BOB_CUST,\"note\":\"bob-fu\"}"
BOB_FU_LIST=$(curl -s "$BASE/follow_ups" -H "Authorization: Bearer $BOB")
check "own scope narrows the second entity just the same" '"note":"bob-fu"' "$BOB_FU_LIST"
check_absent "and keeps the other owner's rows out of it" '"note":"alice-fu"' "$BOB_FU_LIST"

# ── one token, two deciders at once ───────────────────────────────────────────────────────────────
# A token is one-shot (failure-semantics FP-2: "token 一次性，不二次执行"). Two approvals fired
# together are the case that gives "one-shot" meaning: if the app reads the token, runs the tool and
# only then forgets it, both calls pass the check and the write lands twice.
# Eight at once rather than two: bash has no barrier, so overlap is a matter of timing, and a wider
# field makes "at least two were in flight together" the likely case rather than the lucky one.
RACE_EXECUTED_TWICE=0
for _ in $(seq 1 3); do
  RACE=$(post_chat "$ALICE" '{"message":"给客户建一条跟进记录","customerId":1}')
  RTOKEN=$(printf '%s' "$RACE" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  [ -n "$RTOKEN" ] || continue
  BEFORE=$(curl -s "$BASE/ai/tool-effects" -H "Authorization: Bearer $ALICE" | grep -o '"id":' | wc -l)
  # Bare `wait` would also wait for the demo app itself — started with `&` further up and never
  # exiting — so the script would hang there forever. Wait for these eight by pid, nobody else.
  RACE_PIDS=""
  for _ in $(seq 1 8); do
    curl -s -o /dev/null -X POST "$BASE/ai/confirmations/$RTOKEN" \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $ALICE" -d '{"decision":"approve"}' &
    RACE_PIDS="$RACE_PIDS $!"
  done
  wait $RACE_PIDS || true
  AFTER=$(curl -s "$BASE/ai/tool-effects" -H "Authorization: Bearer $ALICE" | grep -o '"id":' | wc -l)
  # Exactly one, whatever the field size: the token is one-shot.
  [ "$AFTER" -eq "$((BEFORE + 1))" ] || RACE_EXECUTED_TWICE=$((RACE_EXECUTED_TWICE + 1))
done
check "a token approved by eight at once writes exactly once" "0" "$RACE_EXECUTED_TWICE"

if grep -qE "Exception" "$GEN_DIR/app.log"; then echo "  FAIL runtime exception in app.log"; fail=1; fi

echo
if [ "$fail" -eq 0 ]; then echo "PASS — generated app runs, the trust loop holds, and row scope is enforced"; else echo "FAIL"; fi
exit "$fail"
