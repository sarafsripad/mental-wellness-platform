#!/usr/bin/env bash
# Calls a running API (run-local.sh) with real tokens from the local Keycloak realm.
# Exit 0 = all checks passed.
set -uo pipefail
cd "$(dirname "$0")"
set -a; source ../../../deploy/local/.env; set +a

API=http://localhost:8080
TOKEN_URL=http://localhost:8081/realms/wellness-local/protocol/openid-connect/token
PASS=0; FAIL=0
expect() {  # expect <label> <expected-status> <expected-code-or-empty> <curl args...>
  local label="$1" want="$2" code="$3"; shift 3
  local out status body
  out=$(curl -s -o /tmp/smoke-body.$$ -w '%{http_code}' "$@"); status="$out"; body="$(cat /tmp/smoke-body.$$)"; rm -f /tmp/smoke-body.$$
  if [[ "$status" == "$want" && ( -z "$code" || "$body" == *"\"code\":\"$code\""* ) ]]; then
    echo "  PASS $label ($status${code:+ $code})"; PASS=$((PASS + 1))
  else
    echo "  FAIL $label (got $status: ${body:0:160})"; FAIL=$((FAIL + 1))
  fi
}
token() { curl -sf "$@" "$TOKEN_URL" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'; }

WORKER=$(token -d grant_type=client_credentials -d client_id=processing-worker -d client_secret="$PROCESSING_WORKER_CLIENT_SECRET")
ASHA=$(token -d grant_type=password -d client_id=wellness-e2e -d client_secret="$E2E_CLIENT_SECRET" \
  -d username=asha.test1 -d password="$KEYCLOAK_TEST_USER_PASSWORD")
[[ -n "$WORKER" && -n "$ASHA" ]] || { echo "smoke: could not get tokens from Keycloak" >&2; exit 1; }

echo "=== wellness-api smoke test against $API"
expect "health is anonymous"                      200 ""                "$API/actuator/health"
expect "no token is rejected"                     401 UNAUTHENTICATED   "$API/v1/consent-events/ce_00000000000000000000000000000001"
expect "garbage token is rejected"                401 UNAUTHENTICATED   -H "Authorization: Bearer not.a.jwt" "$API/v1/voice-sessions"
expect "real ASHA token is accepted (no route yet)" 404 NOT_FOUND       -H "Authorization: Bearer $ASHA" "$API/v1/consent-events/ce_00000000000000000000000000000001"
expect "real worker token is accepted (no route yet)" 404 NOT_FOUND     -H "Authorization: Bearer $WORKER" "$API/internal/v1/processing-jobs/claim"
expect "actuator env is not exposed"              404 NOT_FOUND         -H "Authorization: Bearer $ASHA" "$API/actuator/env"
# Tampering must change real bytes: the last base64url character of an RS256 signature carries
# only 2 data bits, so swapping it can leave the decoded signature unchanged.
read -r TAMPERED FORGED < <(python3 - "$ASHA" <<'PY'
import sys, json, base64
h, p, s = sys.argv[1].split(".")
mid = len(s) // 2
tampered = f"{h}.{p}.{s[:mid]}{'A' if s[mid] != 'A' else 'B'}{s[mid + 1:]}"
claims = json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))
claims["realm_access"] = {"roles": ["ADMIN"]}
forged_payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
print(tampered, f"{h}.{forged_payload}.{s}")
PY
)
expect "tampered signature is rejected"           401 UNAUTHENTICATED   -H "Authorization: Bearer $TAMPERED" "$API/v1/voice-sessions"
expect "payload forged to ADMIN is rejected"      401 UNAUTHENTICATED   -H "Authorization: Bearer $FORGED" "$API/v1/voice-sessions"

echo; echo "smoke: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
