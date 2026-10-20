#!/usr/bin/env bash
# Builds ONE monthly HMIS aggregate report (counts only, 1-4 shown as "<5") into
# deploy/local/hmis-dropzone and, when simulated partners are configured, posts it to the SIMULATED
# State HMIS partner. A person runs this; there is no endpoint and no schedule (ADR-015).
# Usage: ./run-hmis-report.sh [yyyy-MM]   (default: the current UTC month)
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../../.. && pwd)"
ENV_FILE="$REPO/deploy/local/.env"
[[ -f "$ENV_FILE" ]] || { echo "run-hmis-report: $ENV_FILE not found" >&2; exit 1; }
get() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-; }

./gradlew -q bootJar
JAR="$(ls build/libs/wellness-api-*.jar | grep -v plain | head -1)"
export WELLNESS_DB_URL="jdbc:postgresql://localhost:5433/app_db" WELLNESS_DB_USER="app_user"
export WELLNESS_DB_PASSWORD="$(get APP_DB_PASSWORD)" WELLNESS_PSEUDONYM_KEY="$(get WELLNESS_PSEUDONYM_KEY)"
export WELLNESS_STORAGE_ACCESS_KEY="$(get STORAGE_APP_ACCESS_KEY)" WELLNESS_STORAGE_SECRET_KEY="$(get STORAGE_APP_SECRET_KEY)"
export WELLNESS_HMIS_RUN_ONCE=true WELLNESS_HMIS_DIR="$REPO/deploy/local/hmis-dropzone" WELLNESS_HMIS_MONTH="${1:-}"
if [[ -n "$(get PARTNER_SIMULATOR_SIGNING_SECRET)" ]]; then
  export WELLNESS_PARTNER_SIM_ENABLED=true WELLNESS_PARTNER_SIM_SIGNING_SECRET="$(get PARTNER_SIMULATOR_SIGNING_SECRET)"
  export WELLNESS_HMIS_DELIVER=true
fi
export WELLNESS_INTEGRATION_DISPATCH_ENABLED=false SPRING_MAIN_WEB_APPLICATION_TYPE=none
export SPRING_FLYWAY_LOCATIONS="classpath:db/migration,classpath:db/local-seed"
exec java -jar "$JAR"
