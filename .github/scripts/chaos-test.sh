#!/usr/bin/env bash
# Stops Redis under live traffic and checks the gateway fails safe and recovers:
#   1. baseline   every request is allowed
#   2. outage     Redis is stopped: no request may be allowed (the limiter fails
#                 closed), and the gateway must answer promptly rather than hang
#   3. recovery   Redis is back: requests are allowed again within RECOVERY_LIMIT
#
#   GATEWAY_URL=http://localhost:8080 bash .github/scripts/chaos-test.sh
#
# Uses jane.smith (beta-inc, 1,000 requests per minute), who stays far below her limit,
# so every rejection seen here is the outage, not the rate limit.
set -euo pipefail

G="${GATEWAY_URL:-http://localhost:8080}"
REDIS_CONTAINER="${REDIS_CONTAINER:-rl-redis}"
PHASE_SECONDS="${PHASE_SECONDS:-10}"
RECOVERY_LIMIT="${RECOVERY_LIMIT:-30}"
SLOW_MS="${SLOW_MS:-2000}"
PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null && "$candidate" -c 'import json' >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
[ -n "$PY" ] || { echo "Python 3 is required"; exit 2; }
failures=0

TOKEN="$(curl -s -X POST "$G/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"jane.smith","password":"Test@123!"}' |
  "$PY" -c 'import sys, json; print(json.load(sys.stdin)["data"]["accessToken"])')"

# One request: prints "<status> <milliseconds>". 10 s cap so a hang is measured, not waited out.
probe() {
  curl -s -o /dev/null -m 10 -w '%{http_code} %{time_total}\n' "$G/api/demo/ping" \
    -H "Authorization: Bearer $TOKEN" |
    awk '{ printf "%s %d\n", $1, $2 * 1000 }'
}

# Sends requests for $1 seconds; prints "allowed rejected other slowest_ms".
phase() {
  local until=$((SECONDS + $1)) allowed=0 rejected=0 other=0 slowest=0 code ms
  while [ $SECONDS -lt $until ]; do
    read -r code ms < <(probe)
    case "$code" in
      200) allowed=$((allowed + 1)) ;;
      429|503) rejected=$((rejected + 1)) ;;
      *) other=$((other + 1)) ;;
    esac
    [ "$ms" -gt "$slowest" ] && slowest=$ms
    sleep 0.2
  done
  echo "$allowed $rejected $other $slowest"
}

report() { # phase name, "allowed rejected other slowest"
  read -r a r o s <<<"$2"
  printf '  %-9s allowed %3d   rejected %3d   other %3d   slowest %5d ms\n' "$1" "$a" "$r" "$o" "$s"
}
check() {
  if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1: expected $2, got $3"; failures=$((failures + 1)); fi
}

restore_redis() { docker start "$REDIS_CONTAINER" >/dev/null 2>&1 || true; }
trap restore_redis EXIT

echo "Chaos test against $G, stopping container $REDIS_CONTAINER"

baseline="$(phase "$PHASE_SECONDS")"
report baseline "$baseline"

docker stop "$REDIS_CONTAINER" >/dev/null
outage="$(phase "$PHASE_SECONDS")"
report outage "$outage"

docker start "$REDIS_CONTAINER" >/dev/null
started=$SECONDS
recovered_after=""
while [ $((SECONDS - started)) -lt "$RECOVERY_LIMIT" ]; do
  read -r code _ < <(probe)
  if [ "$code" = 200 ]; then recovered_after=$((SECONDS - started)); break; fi
  sleep 0.5
done
after="$(phase "$PHASE_SECONDS")"
report recovered "$after"
echo "  first request allowed ${recovered_after:-never} s after Redis came back"

read -r b_allowed b_rejected b_other _ <<<"$baseline"
read -r o_allowed o_rejected o_other o_slowest <<<"$outage"
read -r a_allowed a_rejected a_other _ <<<"$after"

check "baseline: every request allowed" "0 0" "$b_rejected $b_other"
check "outage: no request let through" 0 "$o_allowed"
check "outage: every request answered with 429 or 503" 0 "$o_other"
check "outage: no answer slower than ${SLOW_MS} ms" yes "$([ "$o_slowest" -le "$SLOW_MS" ] && echo yes || echo "no (${o_slowest} ms)")"
check "recovery within ${RECOVERY_LIMIT} s" yes "$([ -n "$recovered_after" ] && echo yes || echo no)"
check "after recovery: every request allowed" "0 0" "$a_rejected $a_other"
[ "$b_allowed" -gt 0 ] && [ "$a_allowed" -gt 0 ] || { echo "  FAIL  a phase sent no requests"; failures=$((failures + 1)); }

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All checks passed"
