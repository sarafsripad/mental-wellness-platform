# Local Development Environment

Slice step S2 of the [vertical-slice plan](../../docs/implementation/vertical-slice-plan.md). It runs the infrastructure the backend and worker will use, on your machine only.

> **Synthetic and authorised test data only.** Never put real participant data, real recordings or real names into this environment. The Keycloak users are synthetic, and their emails use the reserved `example.invalid` domain.

## What runs

| Service | Image | Host address | Purpose |
|---|---|---|---|
| `postgres` | postgres:16.4 | `127.0.0.1:5433` | `app_db` (owner `app_user`) and `identity_db` (owner `identity_user`). Neither role can connect to the other's database, and every connection needs a password. |
| `storage` | chrislusf/seaweedfs:4.47 | `127.0.0.1:8333` (S3 API) | Bucket `wellness-audio`: SSE-S3 encryption by default, 30-day expiry rule, no anonymous access, and an app key limited to this bucket |
| `storage-init` | amazon/aws-cli:2.37.4 | — | One-shot setup of the bucket, encryption and lifecycle rule (exits 0 when done) |
| `keycloak` | quay.io/keycloak/keycloak:26.0.5 | `127.0.0.1:8081` | Realm `wellness-local`: roles, API scopes, clients and synthetic users matching the OpenAPI contracts |

Everything binds to `127.0.0.1`, so nothing is reachable from your network.

The `wellness-api` (S3) and `audio-processing-worker` (S7) services will be added later. The worker will only join the `worker` network, with no route to Postgres or storage.

## Quick start

```bash
cd deploy/local
./init-env.sh     # once: writes .env with random local secrets (gitignored, mode 600)
./up.sh           # checks .env is complete, then docker compose up -d
./verify.sh       # 32 checks; exits 0 when all pass
```

First start takes a few minutes while images download, and Keycloak takes about 30 seconds to become healthy.

**Stop / reset:**
```bash
docker compose stop          # stop, keep data
docker compose down          # remove containers, keep data volumes
docker compose down -v       # remove containers AND data volumes (full reset)
```

If you delete and regenerate `.env`, also run `docker compose down -v`. Postgres only sets role passwords when it first creates its data volume.

## Credentials

All secrets are in `deploy/local/.env`, created by `init-env.sh`. Nothing secret is committed. Read values from `.env` when you need them, for example `grep APP_DB_PASSWORD .env`.

| What | User / client | Secret in `.env` |
|---|---|---|
| App database | `app_user` @ `app_db` | `APP_DB_PASSWORD` |
| Identity database | `identity_user` @ `identity_db` | `IDENTITY_DB_PASSWORD` |
| Storage, wellness-api key | `STORAGE_APP_ACCESS_KEY` | `STORAGE_APP_SECRET_KEY` |
| Storage, admin (setup only) | `STORAGE_ROOT_ACCESS_KEY` | `STORAGE_ROOT_SECRET_KEY` |
| Keycloak admin console (http://localhost:8081/admin) | `admin` | `KEYCLOAK_ADMIN_PASSWORD` |
| Synthetic users `asha.test1`, `asha.test2`, `clinician.test1`, `admin.test1` | — | `KEYCLOAK_TEST_USER_PASSWORD` |
| Worker client (client credentials) | `processing-worker` | `PROCESSING_WORKER_CLIENT_SECRET` |
| E2E test client (password grant, **local only**) | `wellness-e2e` | `E2E_CLIENT_SECRET` |

Example connection: `PGPASSWORD=<APP_DB_PASSWORD> psql -h 127.0.0.1 -p 5433 -U app_user -d app_db`

## Keycloak realm

| Client | Flow | Default scopes |
|---|---|---|
| `wellness-mobile` | Authorization code + PKCE (S256). Password grant disabled. | `consent:*`, `voice:*`, `processing:read/write` |
| `processing-worker` | Client credentials only | `processing:worker` |
| `wellness-e2e` | Password grant. **Exists only here**, for automated tests; never create it elsewhere. | Same as mobile |
| `wellness-dev-console` | Authorization code + PKCE for the browser test console at `http://localhost:8080/dev-console/`. **Exists only here.** | Same as mobile |

**Tokens:**
- Issuer: `http://localhost:8081/realms/wellness-local`, including when Keycloak is reached from containers.
- Audience: `wellness-api`.
- Roles appear in `realm_access.roles`.
- Tokens carry no name, username or email. The API identifies users by `sub` only.

Realm roles are `ASHA_WORKER`, `CLINICIAN`, `ADMIN` and `PROCESSING_WORKER`. The scopes give coarse access only. The API enforces roles and participant assignment itself.

## What `verify.sh` checks

- **Harness:** the script's own way of running commands in containers actually works.
- **PostgreSQL:**
  - no `trust` rules
  - each role works in its own database and is refused in the other
  - wrong passwords are rejected
- **Storage:**
  - the bucket exists, with default AES256 encryption and the 30-day rule
  - an upload with the app key is stored encrypted and downloads byte-identical
  - the uploaded bytes don't appear in plaintext in the volume files (checked with an incompressible probe)
  - the app key can't create buckets or write elsewhere
  - anonymous and wrong-secret requests are refused
- **Keycloak:**
  - discovery document and issuer
  - the worker gets only `processing:worker`, with the right role and audience
  - a synthetic ASHA user gets `ASHA_WORKER`, has no worker scope, and the token has no personal data
  - the mobile client refuses the password grant
- **Exposure:** ports bind to `127.0.0.1` only.

## When the realm file changes

Keycloak imports `keycloak/wellness-local-realm.json` only when the realm is first created. After pulling a change to it (for example the `wellness-dev-console` client), recreate Keycloak. It keeps nothing except the imported realm, and the synthetic users and secrets come back from `.env`:

```bash
docker compose up -d --force-recreate keycloak
```

## Notes and known limitations

- **Compose compatibility.** These files avoid `${VAR:?message}` and `docker compose exec`, because older Compose releases (for example 2.0.0-beta) mis-parse the first and silently skip the second. `up.sh` replaces the missing-variable check. Upgrading Docker Desktop is recommended anyway.
- **MinIO → SeaweedFS.** ADR-005 originally named MinIO, but MinIO no longer publishes freely pullable images. The app depends only on the S3 API.
- **Local key material.** The storage encryption keys and Keycloak run in dev mode (`start-dev`, HTTP). This is for local use only. Every other environment needs TLS and a managed KMS.
- **The 30-day expiry** is the local default for the proposed processing window. The real retention period is pending privacy and legal review.
