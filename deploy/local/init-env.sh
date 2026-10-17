#!/usr/bin/env bash
# Generates deploy/local/.env with random local-only secrets.
# Never overwrites an existing .env. The file is gitignored; never commit it.
set -euo pipefail

cd "$(dirname "$0")"
ENV_FILE=".env"

if [[ -f "$ENV_FILE" ]]; then
  echo "init-env: $ENV_FILE already exists; leaving it unchanged."
  exit 0
fi

rand() { openssl rand -hex "${1:-24}"; }

umask 077
cat > "$ENV_FILE" <<EOF
# Local development secrets, generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by init-env.sh.
# Local use only. Never commit this file or reuse these values elsewhere.
COMPOSE_PROJECT_NAME=wellness-local
POSTGRES_SUPERUSER_PASSWORD=$(rand)
APP_DB_PASSWORD=$(rand)
IDENTITY_DB_PASSWORD=$(rand)
STORAGE_ROOT_ACCESS_KEY=storageadmin$(rand 6)
STORAGE_ROOT_SECRET_KEY=$(rand)
STORAGE_APP_ACCESS_KEY=wellnessapi$(rand 6)
STORAGE_APP_SECRET_KEY=$(rand)
STORAGE_SSE_KEY=$(rand 32)
STORAGE_SSE_KEK_PASSPHRASE=$(rand)
STORAGE_JWT_SIGNING_KEY=$(rand)
KEYCLOAK_ADMIN_PASSWORD=$(rand)
KEYCLOAK_TEST_USER_PASSWORD=$(rand 12)
PROCESSING_WORKER_CLIENT_SECRET=$(rand)
E2E_CLIENT_SECRET=$(rand)
EOF
echo "init-env: wrote $(pwd)/$ENV_FILE (mode 600)."
