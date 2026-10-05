#!/usr/bin/env bash
# End-to-end checks against a running stack: authentication, enforcement, authorization
# and refresh-token rotation, using the seeded demo accounts.
#
#   GATEWAY_URL=http://localhost:8080 bash .github/scripts/smoke-test.sh
#
# Expects a fresh stack: john.doe must not have used any of his per-minute limit yet.
set -euo pipefail

G="${GATEWAY_URL:-http://localhost:8080}"
PY="$(command -v python3 || command -v python)"
JAR="$(mktemp -d)"
trap 'rm -rf "$JAR"' EXIT
failures=0

check() { # description, expected, actual
  if [ "$2" = "$3" ]; then
    echo "  ok    $1 ($3)"
  else
    echo "  FAIL  $1: expected $2, got $3"
    failures=$((failures + 1))
  fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
login() { # username, password, cookie jar
  curl -s -c "$3" -X POST "$G/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" |
    "$PY" -c 'import sys, json; print(json.load(sys.stdin)["data"]["accessToken"])'
}

echo "Smoke test against $G"

check "request without a token is rejected" 401 "$(status "$G/api/demo/ping")"
check "forged token is rejected" 401 "$(status "$G/api/demo/ping" -H 'Authorization: Bearer not.a.jwt')"

JOHN="$(login john.doe 'Test@123!' "$JAR/john")"
JANE="$(login jane.smith 'Test@123!' "$JAR/jane")"
ADMIN="$(login admin 'Admin@123!' "$JAR/admin")"

# acme-corp allows 100 requests per minute (sliding window). john makes no other
# requests here, so exactly 100 of these 106 get through.
allowed=0
blocked=0
for _ in $(seq 1 106); do
  case "$(status "$G/api/demo/ping" -H "Authorization: Bearer $JOHN")" in
    200) allowed=$((allowed + 1)) ;;
    429) blocked=$((blocked + 1)) ;;
  esac
done
check "acme-corp admits exactly its limit" 100 "$allowed"
check "requests over the limit get 429" 6 "$blocked"
check "another tenant is unaffected" 200 "$(status "$G/api/demo/ping" -H "Authorization: Bearer $JANE")"

check "regular user is refused on the admin API" 403 "$(status "$G/api/admin/rules" -H "Authorization: Bearer $JANE")"
check "admin can use the admin API" 200 "$(status "$G/api/admin/rules" -H "Authorization: Bearer $ADMIN")"

cp "$JAR/jane" "$JAR/jane-old"
check "refresh token issues a new access token" 200 \
  "$(status -b "$JAR/jane" -c "$JAR/jane" -X POST "$G/api/auth/refresh")"
check "a used refresh token is rejected" 400 "$(status -b "$JAR/jane-old" -X POST "$G/api/auth/refresh")"

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All checks passed"
