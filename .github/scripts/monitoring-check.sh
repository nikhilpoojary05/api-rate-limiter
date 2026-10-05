#!/usr/bin/env bash
# Run right after smoke-test.sh on a fresh stack: the smoke test's requests must appear in
# Prometheus with exactly the expected counts, and Zipkin must hold traces that cross from
# the gateway into an upstream service.
#
#   PROMETHEUS_URL=http://localhost:9090 ZIPKIN_URL=http://localhost:9411 \
#     bash .github/scripts/monitoring-check.sh
set -euo pipefail

P="${PROMETHEUS_URL:-http://localhost:9090}"
Z="${ZIPKIN_URL:-http://localhost:9411}"
REPLICAS="${GATEWAY_REPLICAS:-3}"
PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null && "$candidate" -c 'import json' >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
[ -n "$PY" ] || { echo "Python 3 is required"; exit 2; }
failures=0

value() {
  curl -s --get "$P/api/v1/query" --data-urlencode "query=$1" |
    "$PY" -c 'import sys, json; r = json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'
}

# Gateways are scraped every 5 s and replicas are discovered within 15 s, so retry for a
# while before calling a count wrong.
expect() { # description, PromQL, expected value
  local v=""
  for _ in $(seq 1 24); do
    v="$(value "$2")"
    if [ "$v" = "$3" ]; then
      echo "  ok    $1 ($v)"
      return
    fi
    sleep 5
  done
  echo "  FAIL  $1: expected $3, got $v"
  failures=$((failures + 1))
}

echo "Monitoring check: Prometheus at $P, Zipkin at $Z"

expect "every gateway replica is scraped" 'count(up{job="gateway-service"} == 1)' "$REPLICAS"
expect "auth, admin and demo services are scraped" 'count(up{job=~"auth-service|admin-service|demo-service"} == 1)' 3
# From smoke-test.sh: john's 106 requests give 100 allowed and 6 blocked, and admin's one
# admin-API call also belongs to acme-corp.
expect "blocked acme-corp requests counted" 'sum(gateway_requests_total{tenant="acme-corp",outcome="blocked"})' 6
expect "allowed acme-corp requests counted" 'sum(gateway_requests_total{tenant="acme-corp",outcome="allowed"})' 101
expect "every acme-corp request timed" 'sum(gateway_request_duration_seconds_count{tenant="acme-corp"})' 107

traced=""
for _ in $(seq 1 12); do
  traced="$(curl -s "$Z/api/v2/traces?serviceName=demo-service&limit=10" | "$PY" -c '
import sys, json
traces = json.load(sys.stdin)
services = [{s.get("localEndpoint", {}).get("serviceName") for s in t} for t in traces]
print(sum(1 for s in services if {"gateway-service", "demo-service"} <= s))')"
  [ "$traced" -gt 0 ] && break
  sleep 5
done
if [ "$traced" -gt 0 ]; then
  echo "  ok    traces cross from the gateway into demo-service ($traced found)"
else
  echo "  FAIL  no trace spans both gateway-service and demo-service"
  failures=$((failures + 1))
fi

services="$(curl -s "$Z/api/v2/services")"
for s in gateway-service auth-service admin-service demo-service; do
  if echo "$services" | grep -q "\"$s\""; then
    echo "  ok    $s reports to Zipkin"
  else
    echo "  FAIL  $s does not report to Zipkin"
    failures=$((failures + 1))
  fi
done

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All checks passed"
