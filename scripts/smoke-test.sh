#!/usr/bin/env bash
# End-to-end check of a running Portway server, through its public API only.
#
#   scripts/smoke-test.sh                      # against http://localhost:8080
#   AI=true scripts/smoke-test.sh              # also exercise AI translation
#   BASE=http://host:port BUILD=true scripts/smoke-test.sh
#
# AI=true needs the server started with PORTWAY_AI_ENABLED=true and a provider.
# BUILD=true also runs `mvn package` on the downloaded project.
# Needs curl, jq, unzip and python3. Every job it creates is deleted at the end.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
AI="${AI:-false}"
BUILD="${BUILD:-false}"
TIMEOUT="${TIMEOUT:-900}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
JOBS=()
FAILED=0

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=$((FAILED + 1)); }
warn() { printf '  \033[33mWARN\033[0m %s\n' "$1"; }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected $3, got $2)"; fi; }

cleanup() {
  for id in "${JOBS[@]:-}"; do
    [ -n "$id" ] && curl -s -o /dev/null -X DELETE "$BASE/api/jobs/$id" || true
  done
  rm -rf "$WORK"
}
trap cleanup EXIT

wait_for() {
  local id=$1 start=$SECONDS status
  while :; do
    status=$(curl -sf "$BASE/api/jobs/$id" | jq -r .job.status)
    case "$status" in COMPLETED|FAILED) echo "$status"; return ;; esac
    if (( SECONDS - start > TIMEOUT )); then echo "TIMEOUT"; return; fi
    sleep 2
  done
}

echo "Portway smoke test against $BASE (ai=$AI)"

echo "Health"
check "actuator health is UP" "$(curl -sf "$BASE/actuator/health" | jq -r .status)" "UP"
check "OpenAPI docs served" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")" "200"

echo "Sample project"
SAMPLE=$(curl -sf -X POST "$BASE/api/jobs/sample?ai=$AI" | jq -r .id)
JOBS+=("$SAMPLE")
curl -sN --max-time "$TIMEOUT" "$BASE/api/jobs/$SAMPLE/events" > "$WORK/events.txt" 2>/dev/null &
SSE=$!
check "sample job completes" "$(wait_for "$SAMPLE")" "COMPLETED"
sleep 1; kill "$SSE" 2>/dev/null || true
DETAIL=$(curl -sf "$BASE/api/jobs/$SAMPLE")
check "generated project compiles" "$(jq -r .job.compileStatus <<<"$DETAIL")" "PASS"
check "17 files generated" "$(jq -r .job.files <<<"$DETAIL")" "17"
echo "    methods by strategy: $(jq -c .job.methodsByStrategy <<<"$DETAIL")"
echo "    stages: $(jq -r '[.stages[] | "\(.stage) \(.millis)ms"] | join(", ")' <<<"$DETAIL")"
if grep -q '^event:\|^data:' "$WORK/events.txt"; then pass "progress events streamed over SSE"; else fail "no SSE events received"; fi
if [ "$AI" = "true" ]; then
  AI_DONE=$(jq '[.job.methodsByStrategy | to_entries[] | select(.key | startswith("AI_")) | .value] | add // 0' <<<"$DETAIL")
  echo "    AI usage: $(jq -c '.stats.ai // .stats' <<<"$DETAIL")"
  if [ "$AI_DONE" -gt 0 ]; then pass "$AI_DONE method(s) translated by the model and compiled"
  else fail "AI was requested but no model translation survived compilation"; fi
fi

echo "Upload"
(cd "$ROOT/sample-dotnet" && git ls-files BookstoreApi | zip -q "$WORK/upload.zip" -@)
UPLOAD=$(curl -sf -F "file=@$WORK/upload.zip" -F basePackage=com.example.shop "$BASE/api/jobs" | jq -r .id)
JOBS+=("$UPLOAD")
check "uploaded zip migrates" "$(wait_for "$UPLOAD")" "COMPLETED"
FILES=$(curl -sf "$BASE/api/jobs/$UPLOAD/files")
check "base package option applied" \
  "$(jq -r '[.[] | select(.path | contains("com/example/shop"))] | length > 0' <<<"$FILES")" "true"

python3 -I - "$WORK" <<'PY'
import sys, zipfile
work = sys.argv[1]
with zipfile.ZipFile(f"{work}/slip.zip", "w") as z:
    z.writestr("../../escape.cs", "class Escape {}")
with zipfile.ZipFile(f"{work}/bomb.zip", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("Big.cs", "0" * (6 * 1024 * 1024))
open(f"{work}/notzip.zip", "w").write("this is not a zip")
PY
check "zip-slip entry rejected" "$(curl -s -o /dev/null -w '%{http_code}' -F "file=@$WORK/slip.zip" "$BASE/api/jobs")" "400"
check "oversized entry rejected" "$(curl -s -o /dev/null -w '%{http_code}' -F "file=@$WORK/bomb.zip" "$BASE/api/jobs")" "400"
check "non-zip rejected" "$(curl -s -o /dev/null -w '%{http_code}' -F "file=@$WORK/notzip.zip" "$BASE/api/jobs")" "400"

echo "Review"
FILES=$(curl -sf "$BASE/api/jobs/$SAMPLE/files")
FILE=$(jq -r '[.[] | select(.path | endswith("Controller.java"))][0].id' <<<"$FILES")
CONTENT=$(curl -sf "$BASE/api/jobs/$SAMPLE/files/$FILE")
if jq -e '.content and .sourceContent' <<<"$CONTENT" >/dev/null 2>&1 || jq -e '.content' <<<"$CONTENT" >/dev/null; then
  pass "file detail has generated content"; else fail "file detail missing content"; fi
check "accept a file" "$(curl -sf -X POST -H 'Content-Type: application/json' -d '{"status":"ACCEPTED"}' \
  "$BASE/api/jobs/$SAMPLE/files/$FILE/review" | jq -r .reviewStatus)" "ACCEPTED"
EDITED=$(jq -r .content <<<"$CONTENT" | sed '1s|^|// reviewed by smoke test\n|' | jq -Rs '{status: "ACCEPTED", editedContent: .}')
check "save an edit" "$(curl -sf -X POST -H 'Content-Type: application/json' -d "$EDITED" \
  "$BASE/api/jobs/$SAMPLE/files/$FILE/review" | jq -r .edited)" "true"
check "invalid review rejected" "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{}' "$BASE/api/jobs/$SAMPLE/files/$FILE/review")" "400"
check "re-verify accepted" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/jobs/$SAMPLE/verify")" "202"
check "re-verified job completes" "$(wait_for "$SAMPLE")" "COMPLETED"
check "edited project still compiles" "$(curl -sf "$BASE/api/jobs/$SAMPLE" | jq -r .job.compileStatus)" "PASS"

echo "Report and download"
REPORT=$(curl -sf "$BASE/api/jobs/$SAMPLE/report")
check "report has findings" "$(jq -r '(.findings // .findingGroups // []) | length > 0' <<<"$REPORT")" "true"
curl -sf -o "$WORK/out.zip" "$BASE/api/jobs/$SAMPLE/download"
check "download contains pom.xml" "$(unzip -l "$WORK/out.zip" | grep -c ' pom.xml$' || true)" "1"
check "download contains MIGRATION-NOTES.md" "$(unzip -l "$WORK/out.zip" | grep -c 'MIGRATION-NOTES.md$' || true)" "1"
if [ "$BUILD" = "true" ]; then
  mkdir -p "$WORK/out" && unzip -q "$WORK/out.zip" -d "$WORK/out"
  POM=$(find "$WORK/out" -name pom.xml -maxdepth 3 | head -1)
  # The generated project targets Java 21; Maven may be running on something older.
  # java_home -v 21 can answer with an older JDK, so each candidate's version is checked.
  for jdk in "${JAVA21_HOME:-}" "${JAVA_HOME:-}" "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
      /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-21-openjdk*; do
    if [ -n "$jdk" ] && [ -x "$jdk/bin/javac" ] && "$jdk/bin/javac" -version 2>&1 | grep -qE 'javac (2[1-9]|[3-9][0-9])'; then
      export JAVA_HOME="$jdk"; break
    fi
  done
  if mvn -B -q -f "$POM" package -DskipTests >"$WORK/build.log" 2>&1; then pass "downloaded project builds with mvn package"
  else fail "downloaded project does not build (see below)"; tail -20 "$WORK/build.log"; fi
fi

echo "Errors and deletion"
check "unknown job is 404" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/jobs/00000000-0000-0000-0000-000000000000")" "404"
check "delete job" "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/jobs/$UPLOAD")" "204"
check "deleted job is gone" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/jobs/$UPLOAD")" "404"

echo
if [ "$FAILED" -eq 0 ]; then echo "All checks passed."; else echo "$FAILED check(s) failed."; exit 1; fi
