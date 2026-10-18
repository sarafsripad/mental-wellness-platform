#!/usr/bin/env bash
# Builds ONE research export from the local database into deploy/local/research-dropzone and exits.
# This is the only way an export happens: a person runs it (CLAUDE.md rule 5). There is no
# endpoint and no schedule. The HMAC export key stays in deploy/local/.env.
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../../.. && pwd)"
ENV_FILE="$REPO/deploy/local/.env"
[[ -f "$ENV_FILE" ]] || { echo "run-export: $ENV_FILE not found" >&2; exit 1; }
get() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }
if ! grep -qE '^WELLNESS_EXPORT_KEY=.{32,}' "$ENV_FILE"; then
  echo "WELLNESS_EXPORT_KEY=$(openssl rand -hex 32)" >> "$ENV_FILE"
  echo "run-export: added WELLNESS_EXPORT_KEY to $ENV_FILE"
fi
if ! grep -qE '^WELLNESS_PSEUDONYM_KEY=.{32,}' "$ENV_FILE"; then
  echo "WELLNESS_PSEUDONYM_KEY=$(openssl rand -hex 32)" >> "$ENV_FILE"
fi

./gradlew -q bootJar
JAR="$(ls build/libs/wellness-api-*.jar | grep -v plain | head -1)"
export WELLNESS_DB_URL="jdbc:postgresql://localhost:5433/app_db" WELLNESS_DB_USER="app_user"
export WELLNESS_DB_PASSWORD="$(get APP_DB_PASSWORD)" WELLNESS_PSEUDONYM_KEY="$(get WELLNESS_PSEUDONYM_KEY)"
export WELLNESS_STORAGE_ACCESS_KEY="$(get STORAGE_APP_ACCESS_KEY)" WELLNESS_STORAGE_SECRET_KEY="$(get STORAGE_APP_SECRET_KEY)"
export WELLNESS_EXPORT_KEY="$(get WELLNESS_EXPORT_KEY)" WELLNESS_EXPORT_DIR="$REPO/deploy/local/research-dropzone"
export WELLNESS_EXPORT_RUN_ONCE=true SPRING_MAIN_WEB_APPLICATION_TYPE=none
export SPRING_FLYWAY_LOCATIONS="classpath:db/migration,classpath:db/local-seed"
exec java -jar "$JAR"
