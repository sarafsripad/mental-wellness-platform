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
expect "real worker token accepted; claim is POST-only" 405 METHOD_NOT_ALLOWED -H "Authorization: Bearer $WORKER" "$API/internal/v1/processing-jobs/claim"
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

# --- voice session and upload (S5) ---------------------------------------------
TMPD="$(mktemp -d)"; trap 'rm -rf "$TMPD"' EXIT
python3 - "$TMPD/probe.wav" <<'PY'
import math, struct, sys
rate, n = 16000, 16000 * 5
data = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 150 * i / rate))) for i in range(n))
hdr = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
open(sys.argv[1], "wb").write(hdr + b"data" + struct.pack("<I", len(data)) + data)
PY
SHA=$(shasum -a 256 "$TMPD/probe.wav" | cut -d' ' -f1); SIZE=$(wc -c < "$TMPD/probe.wav" | tr -d ' ')
SESS="sess_$(hex)"; REC="rec_$(hex)"; LATER="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
CE2="ce_$(hex)"; PPT2="ppt_$(hex)"
CONSENT2="${CONSENT//$CE/$CE2}"; CONSENT2="${CONSENT2//$PPT/$PPT2}"
expect "record consent for voice participant"    201 ""   -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" "${J[@]}" -d "$CONSENT2" "$API/v1/consent-events"
SESSION="{\"session_id\":\"$SESS\",\"participant_ref\":\"$PPT2\",\"consent_event_id\":\"$CE2\",\"language_tag\":\"kn-IN\",\"prompt_set\":{\"prompt_set_id\":\"baseline-voice-tasks\",\"prompt_set_version\":\"0.1.0\"},\"captured_at_utc\":\"$LATER\",\"device\":{\"device_class\":\"ANDROID_PHONE\",\"app_version\":\"0.1.0\",\"os_family\":\"ANDROID\"},\"data_origin\":\"SYNTHETIC\"}"
expect "create voice session"                     201 ""   -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" "${J[@]}" -d "$SESSION" "$API/v1/voice-sessions"
expect "participant data is refused"              422 DATA_ORIGIN_NOT_PERMITTED -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" "${J[@]}" -d "$(echo "$SESSION" | sed "s/$SESS/sess_$(hex)/; s/SYNTHETIC/PARTICIPANT/")" "$API/v1/voice-sessions"
RECORDING="{\"recording_id\":\"$REC\",\"prompt_id\":\"sustained-vowel-a\",\"format\":\"WAV_PCM_S16LE\",\"sampling_rate_hz\":16000,\"channels\":1,\"duration_seconds\":5.0,\"size_bytes\":$SIZE,\"sha256\":\"$SHA\",\"recorded_at_utc\":\"$LATER\"}"
expect "register recording"                       201 ""   -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" "${J[@]}" -d "$RECORDING" "$API/v1/voice-sessions/$SESS/recordings"
expect "wrong checksum header is rejected"        422 CHECKSUM_MISMATCH -X PUT -H "Authorization: Bearer $ASHA" -H "Content-Type: audio/wav" -H "X-Content-SHA256: $(printf '0%.0s' {1..64})" --data-binary "@$TMPD/probe.wav" "$API/v1/voice-sessions/$SESS/recordings/$REC/content"
expect "upload synthetic audio"                   200 ""   -X PUT -H "Authorization: Bearer $ASHA" -H "Content-Type: audio/wav" -H "X-Content-SHA256: $SHA" --data-binary "@$TMPD/probe.wav" "$API/v1/voice-sessions/$SESS/recordings/$REC/content"
S3=(docker run --rm --network "${COMPOSE_PROJECT_NAME}_data" -e AWS_ACCESS_KEY_ID="$STORAGE_ROOT_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$STORAGE_ROOT_SECRET_KEY" -e AWS_DEFAULT_REGION=us-east-1 amazon/aws-cli:2.37.4 --endpoint-url http://storage:8333)
HEAD=$("${S3[@]}" s3api head-object --bucket wellness-audio --key "audio/$REC" 2>&1)
if [[ "$HEAD" == *'"ServerSideEncryption": "AES256"'* && "$HEAD" == *"\"ContentLength\": $SIZE"* ]]; then
  echo "  PASS audio is in storage, encrypted (AES256), full size"; PASS=$((PASS + 1))
else
  echo "  FAIL storage object: ${HEAD:0:200}"; FAIL=$((FAIL + 1))
fi
WD2="{\"withdrawal_id\":\"cw_$(hex)\",\"scopes\":[\"AUDIO_RECORDING\"],\"withdrawn_at_utc\":\"$LATER\",\"channel\":\"IN_PERSON_ASHA\"}"
expect "withdraw audio recording"                 202 ""   -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(python3 -c 'import uuid;print(uuid.uuid4())')" "${J[@]}" -d "$WD2" "$API/v1/consent-events/$CE2/withdrawals"
if "${S3[@]}" s3api head-object --bucket wellness-audio --key "audio/$REC" >/dev/null 2>&1; then
  echo "  FAIL audio still in storage after withdrawal"; FAIL=$((FAIL + 1))
else
  echo "  PASS audio deleted from storage after withdrawal"; PASS=$((PASS + 1))
fi
SESSION_NOW=$(curl -s -H "Authorization: Bearer $ASHA" "$API/v1/voice-sessions/$SESS")
[[ "$SESSION_NOW" == *'"status":"CANCELLED"'* ]] && { echo "  PASS session is CANCELLED"; PASS=$((PASS + 1)); } || { echo "  FAIL session: ${SESSION_NOW:0:200}"; FAIL=$((FAIL + 1)); }

# --- processing queue and worker API (S6) ---------------------------------------
uuid() { python3 -c 'import uuid;print(uuid.uuid4())'; }
CE3="ce_$(hex)"; PPT3="ppt_$(hex)"; SESS3="sess_$(hex)"; REC3="rec_$(hex)"; WID="wrk_$(openssl rand -hex 8)"
CONSENT3="${CONSENT//$CE/$CE3}"; CONSENT3="${CONSENT3//$PPT/$PPT3}"
curl -s -o /dev/null -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(uuid)" "${J[@]}" -d "$CONSENT3" "$API/v1/consent-events"
SESSION3="${SESSION//$SESS/$SESS3}"; SESSION3="${SESSION3//$PPT2/$PPT3}"; SESSION3="${SESSION3//$CE2/$CE3}"
curl -s -o /dev/null -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(uuid)" "${J[@]}" -d "$SESSION3" "$API/v1/voice-sessions"
curl -s -o /dev/null -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(uuid)" "${J[@]}" -d "${RECORDING//$REC/$REC3}" "$API/v1/voice-sessions/$SESS3/recordings"
curl -s -o /dev/null -X PUT -H "Authorization: Bearer $ASHA" -H "Content-Type: audio/wav" -H "X-Content-SHA256: $SHA" --data-binary "@$TMPD/probe.wav" "$API/v1/voice-sessions/$SESS3/recordings/$REC3/content"
JOB=$(curl -s -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(uuid)" "${J[@]}" -d "{\"recording_id\":\"$REC3\",\"pipeline_profile\":\"EGEMAPS_V02_FUNCTIONALS_V1\"}" "$API/v1/processing-jobs")
JOB_ID=$(echo "$JOB" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("job_id",""))')
[[ "$JOB" == *'"status":"QUEUED"'* ]] && { echo "  PASS processing job queued"; PASS=$((PASS + 1)); } || { echo "  FAIL submit job: ${JOB:0:200}"; FAIL=$((FAIL + 1)); }
expect "ASHA token cannot claim jobs"             403 FORBIDDEN -H "Authorization: Bearer $ASHA" -H "X-Worker-Id: $WID" "${J[@]}" -d '{"supported_profiles":["EGEMAPS_V02_FUNCTIONALS_V1"],"worker_version":"0.1.0"}' "$API/internal/v1/processing-jobs/claim"
LEASE=$(curl -s -H "Authorization: Bearer $WORKER" -H "X-Worker-Id: $WID" "${J[@]}" -d '{"supported_profiles":["EGEMAPS_V02_FUNCTIONALS_V1"],"worker_version":"0.1.0"}' "$API/internal/v1/processing-jobs/claim")
TOKEN_L=$(echo "$LEASE" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("lease_token",""))' 2>/dev/null)
LEASED_JOB=$(echo "$LEASE" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("job_id",""))' 2>/dev/null)
[[ -n "$TOKEN_L" && "$LEASED_JOB" == "$JOB_ID" ]] && { echo "  PASS real worker token claims the job"; PASS=$((PASS + 1)); } || { echo "  FAIL claim: ${LEASE:0:200}"; FAIL=$((FAIL + 1)); }
LH=(-H "Authorization: Bearer $WORKER" -H "X-Lease-Token: $TOKEN_L" -H "X-Worker-Id: $WID" -H "X-Correlation-ID: $(uuid)")
curl -s -o "$TMPD/fetched.wav" "${LH[@]}" "$API/internal/v1/processing-jobs/$JOB_ID/audio"
cmp -s "$TMPD/probe.wav" "$TMPD/fetched.wav" && { echo "  PASS leased audio is byte-identical to the upload"; PASS=$((PASS + 1)); } || { echo "  FAIL fetched audio differs"; FAIL=$((FAIL + 1)); }
expect "wrong lease token is refused"             409 LEASE_INVALID -H "Authorization: Bearer $WORKER" -H "X-Lease-Token: $(openssl rand -hex 32)" -H "X-Worker-Id: $WID" -H "X-Correlation-ID: $(uuid)" "$API/internal/v1/processing-jobs/$JOB_ID/audio"
mkrecord() { python3 - "$1" "$JOB_ID" "$REC3" "$SESS3" "$2" <<'PY'
import json, sys, secrets
rec = json.load(open(sys.argv[1]))
rec.update(record_id="frec_" + secrets.token_hex(16), processing_job_id=sys.argv[2], recording_id=sys.argv[3], session_id=sys.argv[4])
if sys.argv[5] == "clinical":
    rec["features"][0]["name"] = "depression_score"
print(json.dumps(rec))
PY
}
EXAMPLE=../../../contracts/voice-features/examples/valid/complete-pass.json
expect "clinical feature name is refused"         400 SCHEMA_INVALID "${LH[@]}" "${J[@]}" -d "$(mkrecord $EXAMPLE clinical)" "$API/internal/v1/processing-jobs/$JOB_ID/result"
expect "valid feature record is accepted"         201 ""            "${LH[@]}" "${J[@]}" -d "$(mkrecord $EXAMPLE valid)" "$API/internal/v1/processing-jobs/$JOB_ID/result"
DONE=$(curl -s -H "Authorization: Bearer $ASHA" "$API/v1/processing-jobs/$JOB_ID")
[[ "$DONE" == *'"status":"SUCCEEDED"'* && "$DONE" == *'"feature_record_id":"frec_'* && "$DONE" != *'"features"'* ]] \
  && { echo "  PASS job SUCCEEDED; status shows no feature values"; PASS=$((PASS + 1)); } || { echo "  FAIL job: ${DONE:0:200}"; FAIL=$((FAIL + 1)); }
SESSION_DONE=$(curl -s -H "Authorization: Bearer $ASHA" "$API/v1/voice-sessions/$SESS3")
[[ "$SESSION_DONE" == *'"status":"PROCESSED"'* ]] && { echo "  PASS session is PROCESSED"; PASS=$((PASS + 1)); } || { echo "  FAIL session: ${SESSION_DONE:0:200}"; FAIL=$((FAIL + 1)); }

echo; echo "smoke: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
