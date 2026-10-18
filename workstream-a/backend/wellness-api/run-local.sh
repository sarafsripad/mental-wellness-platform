#!/usr/bin/env bash
# Runs the API against the local Docker Compose stack (deploy/local). Start that first with
# deploy/local/up.sh. Reads the database password from deploy/local/.env; nothing is hard-coded.
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=../../../deploy/local/.env
[[ -f "$ENV_FILE" ]] || { echo "run-local: $ENV_FILE not found; run deploy/local/init-env.sh and up.sh first." >&2; exit 1; }
APP_DB_PASSWORD="$(grep -E '^APP_DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$APP_DB_PASSWORD" ]] || { echo "run-local: APP_DB_PASSWORD missing in $ENV_FILE" >&2; exit 1; }

# Pseudonym key for wkr_ references: generated once into .env if an older .env lacks it.
if ! grep -qE '^WELLNESS_PSEUDONYM_KEY=.{32,}' "$ENV_FILE"; then
  echo "WELLNESS_PSEUDONYM_KEY=$(openssl rand -hex 32)" >> "$ENV_FILE"
  echo "run-local: added WELLNESS_PSEUDONYM_KEY to $ENV_FILE"
fi
WELLNESS_PSEUDONYM_KEY="$(grep -E '^WELLNESS_PSEUDONYM_KEY=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"

# Identity field-encryption key (AES-256), generated once into .env like the pseudonym key.
if ! grep -qE '^WELLNESS_IDENTITY_KEY=[0-9a-f]{64}$' "$ENV_FILE"; then
  echo "WELLNESS_IDENTITY_KEY=$(openssl rand -hex 32)" >> "$ENV_FILE"
  echo "run-local: added WELLNESS_IDENTITY_KEY to $ENV_FILE"
fi
WELLNESS_IDENTITY_KEY="$(grep -E '^WELLNESS_IDENTITY_KEY=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
IDENTITY_DB_PASSWORD="$(grep -E '^IDENTITY_DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$IDENTITY_DB_PASSWORD" ]] || { echo "run-local: IDENTITY_DB_PASSWORD missing in $ENV_FILE" >&2; exit 1; }

export WELLNESS_DB_URL="jdbc:postgresql://localhost:5433/app_db"
export WELLNESS_DB_USER="app_user"
export WELLNESS_DB_PASSWORD="$APP_DB_PASSWORD"
export WELLNESS_PSEUDONYM_KEY
export WELLNESS_IDENTITY_DB_URL="jdbc:postgresql://localhost:5433/identity_db"
export WELLNESS_IDENTITY_DB_USER="identity_user"
export WELLNESS_IDENTITY_DB_PASSWORD="$IDENTITY_DB_PASSWORD"
export WELLNESS_IDENTITY_KEY
export WELLNESS_STORAGE_ACCESS_KEY="$(grep -E '^STORAGE_APP_ACCESS_KEY=' "$ENV_FILE" | cut -d= -f2-)"
export WELLNESS_STORAGE_SECRET_KEY="$(grep -E '^STORAGE_APP_SECRET_KEY=' "$ENV_FILE" | cut -d= -f2-)"
# Local only: also load the synthetic consent form IDs.
export SPRING_FLYWAY_LOCATIONS="classpath:db/migration,classpath:db/local-seed"
# Local only: serve the browser test console at http://localhost:8080/dev-console/
export WELLNESS_DEV_CONSOLE_ENABLED=true
# Local only: serve the ASHA Care web app at http://localhost:8080/asha/
export WELLNESS_ASHA_APP_ENABLED=true
echo "run-local: test console at http://localhost:8080/dev-console/ (once started)"
echo "run-local: ASHA Care app at http://localhost:8080/asha/ (once started)"
exec ./gradlew bootRun "$@"
