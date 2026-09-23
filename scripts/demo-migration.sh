#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end demo: a change must carry the data already in the database, as one recorded migration.
#
#   1. install the modules (protocol + runtime + generator)
#   2. generate v1, build it, run it, insert a row, stop
#   3. apply the change → a NEW migration appears; the applied one is left untouched
#   4. rebuild, run again on the SAME database, and look for the row
#
# The assertions are chosen so that a schema-mutating setup cannot pass them: an applied migration
# that stays byte-identical is an artifact `ddl-auto=update` never produces, and a second run that
# rolls forward exactly one version — without re-running the baseline — is a recorded history, not
# a schema that was quietly re-derived.
#
# Exits non-zero if any expected outcome is missing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then PATH="$JAVA_HOME/bin:$PATH"; export PATH; fi

GEN_DIR="target/migration-demo"
MIGRATIONS="$GEN_DIR/src/main/resources/db/migration"
PORT="${PORT:-18082}"
BASE="http://localhost:$PORT/api/v1"

SECRET="${DELEGATION_SECRET:-cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd}"
mint() {
  mvn -q -B -pl keelbase4j-demo exec:java     -Dexec.mainClass=cn.com.keelbase.demo.DevToken     -Dexec.args="$1 $SECRET" 2>/dev/null | tail -1
}

fail=0
check() { # name expected actual — literal match: the expected strings contain JSON punctuation
  if printf '%s' "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 -- expected '$2' in: $3"; fail=1; fi
}
check_absent() { # name unexpected actual
  if printf '%s' "$3" | grep -qF "$2"; then echo "  FAIL $1 -- '$2' must not appear in: $3"; fail=1; else echo "  ok   $1"; fi
}

# Run the app from *inside* the generated project, so its file-backed database lands under that
# project's own data/ directory rather than in this repository.
start_app() { # log-file
  stop_app
  ( cd "$ROOT/$GEN_DIR" && exec java -jar "$(ls "$ROOT/$GEN_DIR"/target/*.jar | head -1)" \
      --server.port="$PORT" ) > "$1" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 60); do
    sleep 1
    curl -s -o /dev/null "$BASE/ai/tools" && return 0
  done
  echo "  FAIL the generated app did not start"
  fail=1
  return 1
}

# Stop the app *and make sure it is gone*. Git Bash's `kill` cannot signal a native Windows process —
# it reports "no such process" and does nothing — so the app would keep running, holding the database
# and the port, and the next run's readiness probe would be answered by the stale process instead of
# the new one. Ask Windows which pid owns the port and terminate that.
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

echo "== 1/4 install the modules =="
mvn -q -B -DskipTests install

echo "== 2/4 generate v1, run it, put a row in =="
rm -rf "$GEN_DIR"
mvn -q -B -DskipTests -pl keelbase4j-generator compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR"
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests
start_app "$ROOT/$GEN_DIR/run1.log" || exit 1

# This app verifies the frozen delegation token — the default identity adapter is the token one.
ALICE="$(mint alice)"
[ -n "$ALICE" ] || { echo "  FAIL could not mint a token" >&2; exit 1; }

LEGACY=$(curl -s -X POST "$BASE/customers" -H 'Content-Type: application/json' -H "Authorization: Bearer $ALICE" \
  -d '{"name":"Legacy Co","level":"low"}')
check "a row is in the database before the change" '"name":"Legacy Co"' "$LEGACY"
check_absent "and the column the change will add does not exist yet" '"tier"' "$LEGACY"
stop_app
cp "$MIGRATIONS/V1__crm_baseline.sql" "$GEN_DIR/v1.before"

echo "== 3/4 apply the change =="
mvn -q -B -DskipTests -pl keelbase4j-generator compile exec:java \
  -Dexec.mainClass=cn.com.keelbase.gen.GeneratorMain -Dexec.args="$GEN_DIR changed"

if cmp -s "$GEN_DIR/v1.before" "$MIGRATIONS/V1__crm_baseline.sql"; then
  echo "  ok   the applied migration is byte-identical after the change"
else
  echo "  FAIL the change rewrote an applied migration"; fail=1
fi

V2="$(ls "$MIGRATIONS"/V2__*.sql 2>/dev/null | head -1 || true)"
if [ -n "$V2" ]; then
  echo "  ok   the change generated a new migration ($(basename "$V2"))"
else
  echo "  FAIL the change generated no migration"; fail=1
fi
ADDED="$(cat "$V2" 2>/dev/null || true)"
check "it adds the new column" "ADD COLUMN tier" "$ADDED"
check_absent "and drops nothing" "DROP" "$ADDED"
check_absent "and re-creates nothing" "CREATE TABLE" "$ADDED"

echo "== 4/4 rebuild on the same database and look for the row =="
mvn -q -B -f "$GEN_DIR/pom.xml" clean package -DskipTests
start_app "$ROOT/$GEN_DIR/run2.log" || { stop_app; exit 1; }

AFTER=$(curl -s "$BASE/customers" -H "Authorization: Bearer $ALICE")
check "the row written before the change is still there" '"name":"Legacy Co"' "$AFTER"
check "and it now carries the added column" '"tier":null' "$AFTER"

RUN2_LOG="$(cat "$ROOT/$GEN_DIR/run2.log")"
check "the second run rolled forward one version" 'to version "2' "$RUN2_LOG"
check_absent "without re-running the baseline" '1 - crm baseline' "$RUN2_LOG"
stop_app

echo
if [ "$fail" -eq 0 ]; then echo "PASS — the change carried the data, as one recorded migration"; else echo "FAIL"; fi
exit "$fail"
