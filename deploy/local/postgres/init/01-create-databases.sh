#!/usr/bin/env bash
# Runs once, on first start of an empty postgres volume.
# Creates app_db and identity_db, each owned by its own login role, and stops
# either role from connecting to the other's database (ADR-010).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres \
  -v app_pw="$APP_DB_PASSWORD" -v identity_pw="$IDENTITY_DB_PASSWORD" <<'SQL'
CREATE ROLE app_user LOGIN PASSWORD :'app_pw';
CREATE ROLE identity_user LOGIN PASSWORD :'identity_pw';

CREATE DATABASE app_db OWNER app_user;
CREATE DATABASE identity_db OWNER identity_user;

-- No implicit access through PUBLIC; each role may only connect to its own database.
REVOKE ALL ON DATABASE app_db FROM PUBLIC;
REVOKE ALL ON DATABASE identity_db FROM PUBLIC;
GRANT CONNECT ON DATABASE app_db TO app_user;
GRANT CONNECT ON DATABASE identity_db TO identity_user;
SQL

# Lock down the default public schema in each database (PostgreSQL 15+ already
# removes CREATE from PUBLIC; this makes the intent explicit).
for db in app_db identity_db; do
  psql -v ON_ERROR_STOP=1 --username postgres --dbname "$db" \
    -c "REVOKE ALL ON SCHEMA public FROM PUBLIC;"
done
psql -v ON_ERROR_STOP=1 --username postgres --dbname app_db \
  -c "ALTER SCHEMA public OWNER TO app_user;"
psql -v ON_ERROR_STOP=1 --username postgres --dbname identity_db \
  -c "ALTER SCHEMA public OWNER TO identity_user;"
