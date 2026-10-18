# wellness-api

The Mental Wellness Platform backend: a Spring Boot modular monolith ([ADR-004](../../../docs/architecture/architecture-decisions.md#adr-004-service-decomposition--modular-monolith-plus-worker)). Slice steps **S3** (skeleton: security, errors, correlation IDs, audit, module boundaries), **S4** (consent), **S5** (voice sessions and audio upload) and **S6** (processing queue and internal worker API) are implemented. The backend half of the vertical slice is complete; the Python worker (S7) is next.

| | |
|---|---|
| Runtime | Java 21, Spring Boot 4.1.1, Spring Security 7, Spring Modulith 2.1.1 |
| Build | Gradle 9.8.1 wrapper (checksum-pinned; no local Gradle install needed) |
| Database | PostgreSQL 16 (`app_db`), Flyway migrations |
| Auth | OIDC bearer tokens from Keycloak (local realm `wellness-local`) |
| Contract | [contracts/openapi/wellness-api.v1.yaml](../../../contracts/openapi/wellness-api.v1.yaml) |

## Modules

Each top-level package is a module. Spring Modulith tests enforce that modules have no cycles and don't use each other's internal types.

| Package | Status | Purpose |
|---|---|---|
| `shared` | ✅ S3 | Correlation IDs, RFC 9457 problem responses, OIDC security. No domain logic. |
| `audit` | ✅ S3 | Append-only audit events (`AuditLog`). The database rejects updates, deletes and truncation. |
| `consent` | ✅ S4 | Consent events, withdrawals (cascading), status, and the `ConsentGate` other modules call. Approved for synthetic data on 2026-10-18 (privacy/legal review pending). |
| `voicesession` | ✅ S5 | Sessions, recording metadata, audio upload to encrypted S3-compatible storage; deletes audio when `AUDIO_RECORDING` is withdrawn |
| `processing` | ✅ S6 | PostgreSQL job queue (`SKIP LOCKED`), worker leases, feature-record intake validated against the contract schema; cancels jobs and flags records on withdrawal |
| `researchexport` | ✅ S8 | Consent-filtered, re-keyed, date-truncated exports with a contract-validated manifest. Run only by a person (`run-export.sh`). |
| `identity` | later | Direct identifiers in `identity_db`. No other module may depend on it (ArchUnit). |

## Run locally

```bash
deploy/local/up.sh                                   # Postgres, storage, Keycloak
workstream-a/backend/wellness-api/run-local.sh       # API on http://localhost:8080
workstream-a/backend/wellness-api/smoke-local.sh     # 34 live checks: tokens, consent, upload, storage, deletion, worker flow
```

`run-local.sh` reads the database password from `deploy/local/.env`. The API never has a default password. On first start, Flyway creates the module schemas and the audit table in `app_db`.

## Test

```bash
cd workstream-a/backend/wellness-api
./gradlew test        # needs Docker: integration tests start postgres:16.4 via Testcontainers
```

The suite has 156 tests (Testcontainers: PostgreSQL 16.4 and SeaweedFS 4.47). Integration tests also record every real request and response to `build/contract-samples/`; run `python3 -m pytest tests/contracts/test_backend_samples.py` from the repo root to validate them against the OpenAPI schemas (CI does this automatically). Integration tests use the full application context and a real PostgreSQL, with no database mocks. They also use the production token validators: only the signing key is swapped, for a test key.

| Area | What is checked |
|---|---|
| Authentication | No token, garbage, expired, wrong issuer, wrong audience, unknown signing key, HS256 and `alg: none` tokens are all rejected with a 401 problem and a `Bearer` challenge |
| Authorization | A wrong role gets a 403 problem and an `ACCESS_DENIED` audit row; Keycloak's own realm roles grant nothing |
| Health and actuator | Health and probes are anonymous and status-only. Every other actuator endpoint, and the discovery page, is not exposed. |
| Errors | Every failure returns a body matching the contract's `Problem` schema, read from the real OpenAPI file. Nothing is echoed: no submitted values and no exception messages, in responses *or logs*. |
| Correlation IDs | A valid client UUID is echoed in the header and body; anything else is replaced, never reflected |
| Audit | Append-only (UPDATE, DELETE and TRUNCATE rejected by trigger); check constraints refuse free text such as names |
| Boundaries | Modulith verification; ArchUnit rules (no `System.out`, no `java.util.logging`, no field injection, identity isolation) |
| Contract | `ProblemCode` matches the contract's `Problem.code` list exactly |
| Consent (S4) | Create, replay, conflicts, concurrent duplicates (one event), incomplete or inconsistent decisions, minors, unknown forms, future times, unknown fields; assignment isolation; ADMIN reason codes; cascading withdrawal; no-op withdrawal; the gate refuses another participant's event, missing scopes, data captured before consent, and a withdrawn event revived by a newer one; append-only history |

Mutation check: removing the audience validator makes exactly the two audience tests fail.

## Consent endpoints (S4)

| Operation | Who | Notes |
|---|---|---|
| `POST /v1/consent-events` | ASHA worker (`consent:write`) | Idempotent. The first consent event assigns the participant to the worker. |
| `GET /v1/consent-events/{id}` | Assigned ASHA worker; ADMIN with `X-Access-Reason` | Others get 404. Every read is audited. |
| `POST /v1/consent-events/{id}/withdrawals` | Assigned ASHA worker; ADMIN with reason | Cascades to dependent scopes; 202, or 200 for a no-op or replay |
| `GET /v1/participants/{ref}/consent-status` | As for reads | Latest event's status and effective scopes |

### Voice sessions (S5)

| Operation | Who | Notes |
|---|---|---|
| `POST /v1/voice-sessions` | Assigned ASHA worker (`voice:write`) | Needs `SCREENING_PARTICIPATION` + `AUDIO_RECORDING` on the referenced consent event; capture time not before consent; `PARTICIPANT` data refused in every current environment |
| `GET /v1/voice-sessions/{id}` | Assigned ASHA worker; ADMIN with reason | Status only, never audio |
| `POST /v1/voice-sessions/{id}/recordings` | Assigned ASHA worker | Registers size and SHA-256 before upload |
| `PUT .../recordings/{id}/content` | Assigned ASHA worker | `audio/wav` or `audio/flac`, ≤ 25 MB. The server hashes the body itself, checks the file signature, re-checks consent, and stores with SSE-S3 |

Withdrawing `AUDIO_RECORDING` cancels the event's sessions and deletes stored audio after the withdrawal commits (retried every minute until done).

### Research export (S8)

Run `run-export.sh`. It builds one export and exits, with no web server, endpoint or schedule (rule 5). Each export is a full snapshot of the records eligible **at export time**: not flagged, and `RESEARCH_FEATURE_REUSE` in effect.

- **Re-keying:** every ID becomes `prefix_` + 128 bits of HMAC-SHA-256 with `WELLNESS_EXPORT_KEY`. The result is stable across exports, irreversible, and the key never leaves the application.
- **Minimisation:** timestamps are truncated to the date, and `degraded_reason` is replaced with fixed text.
- **Manifest:** carries `split_groups` (re-keyed session → `grp_` group, for participant-level splits), `model_training_excluded`, and `withdrawn_record_ids`. Each withdrawal is announced once.
- **Validation:** every exported record and the manifest are validated against the contracts before anything is written. Files are written atomically, records first.

### Processing (S6)

| Operation | Who | Notes |
|---|---|---|
| `POST /v1/processing-jobs` | Assigned ASHA worker; ADMIN with reason | Recording must be stored (`RECORDING_NOT_UPLOADED` otherwise). One job per recording and profile, so a repeat returns it (200). |
| `GET /v1/processing-jobs/{id}` | Assigned ASHA worker; ADMIN with reason | Status and audio-quality outcome only, never feature values; `Retry-After: 5` while queued or running |
| `POST /internal/v1/processing-jobs/claim` | `processing-worker` service account only | Oldest claimable job, leased for 300 s; jobs whose consent is gone are cancelled and skipped |
| `GET .../{id}/audio` | Lease holder | Consent re-checked; integrity-verified bytes with `X-Content-SHA256` |
| `POST .../{id}/result` | Lease holder | Validated against `feature-record.schema.json` (packaged from `contracts/`). Consent, language and provenance are overwritten from server records; IDs and audio properties must match the job. |
| `POST .../{id}/failure` | Lease holder | Retryable failures are re-queued, up to 3 attempts |

- Lease tokens are random, bound to the worker ID, and stored only as a SHA-256 hash.
- Expired leases are re-queued, or fail with `TIMEOUT` after the last attempt.
- Withdrawing `AUDIO_RECORDING` cancels queued and running jobs and flags any derived feature records.
- In deployed environments `/internal/**` must not be reachable from the public ingress. Locally it is on `localhost:8080`, protected by the worker role and scope.

Clinicians get 404 until clinician assignment exists (case module). Downstream withdrawal actions report `NOT_APPLICABLE` until the modules that hold audio and jobs (S5, S6) register handlers.

## Security behaviour

- **Every request needs a valid bearer token**, except `/actuator/health` (and its probes) and `/.well-known/oauth-protected-resource`.
- **Accepted tokens:** RS256-signed only, by the issuer's keys; issuer, audience `wellness-api`, expiry and not-before are all checked.
- **Authorities:** `ROLE_*` comes from `realm_access.roles`, limited to `ASHA_WORKER`, `CLINICIAN`, `ADMIN` and `PROCESSING_WORKER`. `SCOPE_*` comes from `scope`.
- **Stateless:** no session, cookies, CSRF, form login or basic auth.
- **Logs** are structured JSON (ECS) and include `correlation_id`. Exception messages are redacted, because they can contain request data.

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `WELLNESS_DB_URL` | `jdbc:postgresql://localhost:5433/app_db` | |
| `WELLNESS_DB_USER` | `app_user` | |
| `WELLNESS_DB_PASSWORD` | **none (required)** | |
| `WELLNESS_STORAGE_ENDPOINT` | `http://localhost:8333` | S3-compatible endpoint |
| `WELLNESS_STORAGE_ACCESS_KEY` / `_SECRET_KEY` | **none (required)** | Bucket-scoped key; `run-local.sh` reads `STORAGE_APP_*` from `deploy/local/.env` |
| `WELLNESS_PSEUDONYM_KEY` | **none (required)** | HMAC key for `wkr_` worker references (≥ 32 chars). `run-local.sh` adds one to `deploy/local/.env` if missing. |
| `WELLNESS_JWT_ISSUER` | `http://localhost:8081/realms/wellness-local` | Must match the token's `iss` |
| `WELLNESS_JWT_JWK_SET_URI` | local Keycloak certs URL | Can point at an internal hostname inside Docker |
