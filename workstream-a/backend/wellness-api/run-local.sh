#!/usr/bin/env bash
# Runs the API against the local Docker Compose stack (deploy/local). Start that first with
# deploy/local/up.sh. Reads the database password from deploy/local/.env; nothing is hard-coded.
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=../../../deploy/local/.env
[[ -f "$ENV_FILE" ]] || { echo "run-local: $ENV_FILE not found; run deploy/local/init-env.sh and up.sh first." >&2; exit 1; }
APP_DB_PASSWORD="$(grep -E '^APP_DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
[[ -n "$APP_DB_PASSWORD" ]] || { echo "run-local: APP_DB_PASSWORD missing in $ENV_FILE" >&2; exit 1; }

export WELLNESS_DB_URL="jdbc:postgresql://localhost:5433/app_db"
export WELLNESS_DB_USER="app_user"
export WELLNESS_DB_PASSWORD="$APP_DB_PASSWORD"
exec ./gradlew bootRun "$@"
