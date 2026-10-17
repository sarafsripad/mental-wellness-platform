#!/usr/bin/env bash
# Smoke-checks the running local environment, including its security properties.
# Read-only except for synthetic probe objects that are written and removed.
# Exit 0 = all checks passed, 1 = at least one failed.
set -uo pipefail

cd "$(dirname "$0")"
[[ -f .env ]] || { echo "No .env; run ./init-env.sh and docker compose up -d first." >&2; exit 1; }
set -a; source .env; set +a

DC=(docker compose)
NET="${COMPOSE_PROJECT_NAME}_data"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
ok()     { echo "  PASS $1"; PASS=$((PASS + 1)); }
bad()    { echo "  FAIL $1"; FAIL=$((FAIL + 1)); }
check()  { local label="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$label"; else bad "$label"; fi; }
refuse() { local label="$1"; shift; if "$@" >/dev/null 2>&1; then bad "$label"; else ok "$label"; fi; }
has()    { grep -qE -- "$2" <<<"$1"; }   # has <text> <regex>

# `docker compose exec -T` silently does nothing in some Compose versions, so
# commands run through plain `docker exec` on the service's container ID.
cid() { "${DC[@]}" ps -q "$1"; }
in_svc() { local svc="$1"; shift; docker exec -i "$(cid "$svc")" "$@"; }
exec_works() { [[ "$(in_svc postgres echo ok)" == ok ]] && ! in_svc postgres false; }

# --- PostgreSQL helpers --------------------------------------------------------
# Connects over TCP from a separate client container on the data network, so
# the server's password rules apply (connections inside the server container
# may be trusted by the image's default pg_hba.conf).
psql_as() {  # psql_as <user> <password> <db> <sql>
  docker run --rm --network "$NET" -e PGPASSWORD="$2" postgres:16.4 \
    psql -h postgres -U "$1" -d "$3" -v ON_ERROR_STOP=1 -tAc "$4"
}
password_auth_enforced() {  # no pg_hba rule (socket or network) may use trust
  local rules; rules="$(in_svc postgres sh -c 'grep -vE "^[[:space:]]*(#|$)" "$PGDATA/pg_hba.conf"')" || return 1
  [[ -n "$rules" ]] && ! grep -qw trust <<<"$rules"
}

# --- Object storage helpers (AWS CLI container on the data network) -----------
s3_as() {  # s3_as <access> <secret> <aws args...>
  local k="$1" s="$2"; shift 2
  docker run --rm --network "$NET" -v "$TMP:/w" \
    -e AWS_ACCESS_KEY_ID="$k" -e AWS_SECRET_ACCESS_KEY="$s" -e AWS_DEFAULT_REGION=us-east-1 \
    amazon/aws-cli:2.37.4 --endpoint-url http://storage:8333 "$@"
}
s3_root() { s3_as "$STORAGE_ROOT_ACCESS_KEY" "$STORAGE_ROOT_SECRET_KEY" "$@"; }
s3_app()  { s3_as "$STORAGE_APP_ACCESS_KEY" "$STORAGE_APP_SECRET_KEY" "$@"; }

enc_default() { s3_root s3api get-bucket-encryption --bucket wellness-audio | grep -q '"AES256"'; }
expiry_rule() { s3_root s3api get-bucket-lifecycle-configuration --bucket wellness-audio | grep -q '"Days": 30'; }
app_put_encrypted() {
  s3_app s3api put-object --bucket wellness-audio --key verify/probe.bin --body /w/probe.bin \
    | grep -q '"ServerSideEncryption": "AES256"'
}
app_roundtrip() {
  s3_app s3 cp s3://wellness-audio/verify/probe.bin /w/back.bin && cmp -s "$TMP/probe.bin" "$TMP/back.bin"
}
no_plaintext_on_disk() {  # copy volume files out; host grep (the container's busybox grep can't search binaries)
  mkdir -p "$TMP/dump" && "${DC[@]}" cp storage:/data/. "$TMP/dump/" || return 1
  # The copy must actually contain the uploaded object, or "no match" proves nothing.
  find "$TMP/dump" -name '*.dat' -size +128k | grep -q . || return 1
  ! LC_ALL=C grep -arq "$MARKER" "$TMP/dump"
}
other_bucket_denied() {
  s3_root s3api create-bucket --bucket verify-other >/dev/null 2>&1
  local rc=0; s3_app s3api put-object --bucket verify-other --key x --body /w/probe.bin >/dev/null 2>&1 && rc=1
  s3_root s3api delete-bucket --bucket verify-other >/dev/null 2>&1
  return $rc
}

# --- Keycloak helpers ----------------------------------------------------------
TOKEN_URL=http://localhost:8081/realms/wellness-local/protocol/openid-connect/token
jwt_claims() {
  python3 -c 'import sys,json,base64
t=json.load(sys.stdin)["access_token"].split(".")[1]; t+="="*(-len(t)%4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(t))))'
}
localhost_only() { ! "${DC[@]}" ps | grep -q '0\.0\.0\.0:'; }

# ==============================================================================
echo "=== Harness"
check  "docker exec runs commands and reports failures" exec_works

echo "=== PostgreSQL"
check  "every connection requires a password (no trust rules)" password_auth_enforced
check  "app_user can create and drop a table in app_db" \
       psql_as app_user "$APP_DB_PASSWORD" app_db "CREATE TABLE verify_probe(x int); DROP TABLE verify_probe;"
check  "identity_user can query identity_db"       psql_as identity_user "$IDENTITY_DB_PASSWORD" identity_db "SELECT 1"
refuse "app_user cannot connect to identity_db"    psql_as app_user "$APP_DB_PASSWORD" identity_db "SELECT 1"
refuse "identity_user cannot connect to app_db"    psql_as identity_user "$IDENTITY_DB_PASSWORD" app_db "SELECT 1"
refuse "wrong password is rejected"                psql_as app_user "not-the-password" app_db "SELECT 1"

echo "=== Object storage (SeaweedFS)"
# Incompressible probe with a marker in the middle: the storage layer compresses
# compressible data, which would hide plaintext and make the disk check meaningless.
MARKER="SYNTHETIC-PLAINTEXT-MARKER-$(openssl rand -hex 4)"
{ head -c 65536 /dev/urandom; printf '%s' "$MARKER"; head -c 65536 /dev/urandom; } > "$TMP/probe.bin"
check  "bucket wellness-audio exists"              s3_root s3api head-bucket --bucket wellness-audio
check  "default encryption is SSE-S3 (AES256)"     enc_default
check  "lifecycle expiry rule (30 days) present"   expiry_rule
check  "app key upload is stored encrypted"        app_put_encrypted
check  "app key download is byte-identical"        app_roundtrip
check  "no plaintext of the upload on disk"        no_plaintext_on_disk
s3_app s3api delete-object --bucket wellness-audio --key verify/probe.bin >/dev/null 2>&1
refuse "app key cannot create buckets"             s3_app s3api create-bucket --bucket verify-denied
check  "app key cannot write to other buckets"     other_bucket_denied
refuse "anonymous read is denied"                  curl -sf "http://127.0.0.1:8333/wellness-audio/"
refuse "wrong secret is rejected"                  s3_as "$STORAGE_APP_ACCESS_KEY" wrong-secret s3api head-bucket --bucket wellness-audio

echo "=== Keycloak (OIDC)"
DISCOVERY=$(curl -sf http://localhost:8081/realms/wellness-local/.well-known/openid-configuration)
check  "discovery document served"                 test -n "$DISCOVERY"
check  "issuer is http://localhost:8081/realms/wellness-local" \
       has "$DISCOVERY" '"issuer":"http://localhost:8081/realms/wellness-local"'

WORKER=$(curl -sf -d grant_type=client_credentials -d client_id=processing-worker \
  -d client_secret="$PROCESSING_WORKER_CLIENT_SECRET" "$TOKEN_URL" | jwt_claims 2>/dev/null)
check  "worker gets a client-credentials token"    test -n "$WORKER"
check  "worker token has role PROCESSING_WORKER"   has "$WORKER" PROCESSING_WORKER
check  "worker token has scope processing:worker"  has "$WORKER" 'processing:worker'
check  "worker token audience is wellness-api"     has "$WORKER" '"aud": "wellness-api"'
refuse "worker token has no user scopes"           has "$WORKER" 'consent:|voice:'
refuse "wrong worker secret is rejected" \
       curl -sf -d grant_type=client_credentials -d client_id=processing-worker -d client_secret=wrong "$TOKEN_URL"

ASHA=$(curl -sf -d grant_type=password -d client_id=wellness-e2e -d client_secret="$E2E_CLIENT_SECRET" \
  -d username=asha.test1 -d password="$KEYCLOAK_TEST_USER_PASSWORD" "$TOKEN_URL" | jwt_claims 2>/dev/null)
check  "synthetic ASHA user can log in (e2e client)" test -n "$ASHA"
check  "ASHA token has role ASHA_WORKER"           has "$ASHA" ASHA_WORKER
check  "ASHA token has scope consent:write"        has "$ASHA" 'consent:write'
refuse "ASHA token has no processing:worker scope" has "$ASHA" 'processing:worker'
refuse "ASHA token carries no name or email"       has "$ASHA" '"(name|given_name|family_name|email|preferred_username)"'
refuse "mobile client refuses the password grant" \
       curl -sf -d grant_type=password -d client_id=wellness-mobile \
       -d username=asha.test1 -d password="$KEYCLOAK_TEST_USER_PASSWORD" "$TOKEN_URL"

echo "=== Exposure"
check  "published ports bind to 127.0.0.1 only"   localhost_only

echo
echo "verify: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
