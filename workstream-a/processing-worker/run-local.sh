#!/usr/bin/env bash
# Runs the worker container against the local API (workstream-a/backend/wellness-api/run-local.sh)
# and Keycloak (deploy/local). The client secret is read from deploy/local/.env; nothing hard-coded.
# Usage: ./run-local.sh            (polls forever; Ctrl+C to stop)
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../.. && pwd)"
ENV_FILE="$REPO/deploy/local/.env"
[[ -f "$ENV_FILE" ]] || { echo "run-local: $ENV_FILE not found; run deploy/local/init-env.sh and up.sh first." >&2; exit 1; }
SECRET="$(grep -E '^PROCESSING_WORKER_CLIENT_SECRET=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$SECRET" ]] || { echo "run-local: PROCESSING_WORKER_CLIENT_SECRET missing in $ENV_FILE" >&2; exit 1; }

docker build -q --target runtime -f "$REPO/workstream-a/processing-worker/Dockerfile" -t wellness-worker "$REPO" >/dev/null
# host.docker.internal reaches services on this machine from Docker Desktop. Tokens still carry the
# issuer http://localhost:8081 (Keycloak's fixed hostname), which is what the API expects.
# On Linux (e.g. CI), host.docker.internal needs --add-host, and Keycloak's 127.0.0.1-bound port isn't
# reachable from containers: set WORKER_NETWORK=wellness-local_auth and
# WORKER_TOKEN_URL=http://keycloak:8081/... to reach Keycloak on the stack network instead.
NETWORK_ARGS=()
[[ -n "${WORKER_NETWORK:-}" ]] && NETWORK_ARGS=(--network "$WORKER_NETWORK")
exec docker run --rm --name wellness-worker "${NETWORK_ARGS[@]}" \
  --add-host=host.docker.internal:host-gateway \
  -e WORKER_CLIENT_SECRET="$SECRET" \
  -e WORKER_API_URL="${WORKER_API_URL:-http://host.docker.internal:8080}" \
  -e WORKER_TOKEN_URL="${WORKER_TOKEN_URL:-http://host.docker.internal:8081/realms/wellness-local/protocol/openid-connect/token}" \
  -e WORKER_POLL_SECONDS="${WORKER_POLL_SECONDS:-2}" \
  -e WORKER_GIT_COMMIT="$(git -C "$REPO" rev-parse --short=12 HEAD 2>/dev/null || true)" \
  wellness-worker
