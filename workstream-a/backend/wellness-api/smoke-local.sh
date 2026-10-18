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
expect "real ASHA token accepted; unknown event not found" 404 NOT_FOUND       -H "Authorization: Bearer $ASHA" "$API/v1/consent-events/ce_00000000000000000000000000000001"
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

# --- consent flow (S4) with synthetic IDs ------------------------------------------
hex() { openssl rand -hex 16; }
CE="ce_$(hex)"; PPT="ppt_$(hex)"; KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
NOW="$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%SZ)"
CONSENT=$(cat <<JSON
{"consent_event_id":"$CE","participant_ref":"$PPT",
 "consent_form":{"form_id":"screening-consent","form_version":"0.1.0","language_tag":"kn-IN"},
 "scope_decisions":[{"scope":"SCREENING_PARTICIPATION","granted":true},{"scope":"AUDIO_RECORDING","granted":true},
  {"scope":"AUDIO_RETENTION","granted":false},{"scope":"RESEARCH_FEATURE_REUSE","granted":true},
  {"scope":"MODEL_TRAINING","granted":true},{"scope":"QUALITY_REVIEW","granted":false}],
 "capture":{"captured_at_utc":"$NOW","method":"SIGNED_ON_DEVICE","read_aloud":true,"comprehension_confirmed":true},
 "safeguards":{"adult_confirmed":true}}
JSON
)
ASHA2=$(token -d grant_type=password -d client_id=wellness-e2e -d client_secret="$E2E_CLIENT_SECRET" \
  -d username=asha.test2 -d password="$KEYCLOAK_TEST_USER_PASSWORD")
J=(-H "Content-Type: application/json")
expect "record consent"                           201 ""                -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $KEY" "${J[@]}" -d "$CONSENT" "$API/v1/consent-events"
expect "retry with same key replays"              200 ""                -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $KEY" "${J[@]}" -d "$CONSENT" "$API/v1/consent-events"
expect "assigned worker reads the event"          200 ""                -H "Authorization: Bearer $ASHA" "$API/v1/consent-events/$CE"
expect "another worker sees not found"            404 NOT_FOUND         -H "Authorization: Bearer $ASHA2" "$API/v1/consent-events/$CE"
expect "worker token cannot read consent"         403 FORBIDDEN         -H "Authorization: Bearer $WORKER" "$API/v1/consent-events/$CE"
WD="{\"withdrawal_id\":\"cw_$(hex)\",\"scopes\":[\"RESEARCH_FEATURE_REUSE\"],\"withdrawn_at_utc\":\"$NOW\",\"channel\":\"IN_PERSON_ASHA\"}"
expect "withdraw research reuse"                  202 ""                -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" "${J[@]}" -d "$WD" "$API/v1/consent-events/$CE/withdrawals"
STATUS=$(curl -s -H "Authorization: Bearer $ASHA" "$API/v1/participants/$PPT/consent-status")
if [[ "$STATUS" == *'"status":"PARTIALLY_WITHDRAWN"'* && "$STATUS" != *RESEARCH_FEATURE_REUSE* && "$STATUS" != *MODEL_TRAINING* ]]; then
  echo "  PASS status is PARTIALLY_WITHDRAWN without research or training scopes"; PASS=$((PASS + 1))
else
  echo "  FAIL consent status: ${STATUS:0:200}"; FAIL=$((FAIL + 1))
fi

echo; echo "smoke: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
