# wellness-api

The Mental Wellness Platform backend: a Spring Boot modular monolith ([ADR-004](../../../docs/architecture/architecture-decisions.md#adr-004-service-decomposition--modular-monolith-plus-worker)). This is slice step **S3**, the skeleton. It handles security, errors, correlation IDs, audit and module boundaries, and has **no business endpoints yet**.

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
| `consent` | S4 🔒 | Consent events and scopes. Needs named approval of the consent model (rule 10). |
| `voicesession` | S5 🔒 | Sessions, recordings, audio upload |
| `processing` | S6 | Job queue, worker leases, feature-record intake |
| `researchexport` | S8 | Consent-filtered, re-keyed exports |
| `identity` | later | Direct identifiers in `identity_db`. No other module may depend on it (ArchUnit). |

## Run locally

```bash
deploy/local/up.sh                                   # Postgres, storage, Keycloak
workstream-a/backend/wellness-api/run-local.sh       # API on http://localhost:8080
workstream-a/backend/wellness-api/smoke-local.sh     # 8 checks with real Keycloak tokens
```

`run-local.sh` reads the database password from `deploy/local/.env`. The API never has a default password. On first start, Flyway creates the module schemas and the audit table in `app_db`.

## Test

```bash
cd workstream-a/backend/wellness-api
./gradlew test        # needs Docker: integration tests start postgres:16.4 via Testcontainers
```

The suite has 69 tests. Integration tests use the full application context and a real PostgreSQL, with no database mocks. They also use the production token validators: only the signing key is swapped, for a test key.

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

Mutation check: removing the audience validator makes exactly the two audience tests fail.

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
| `WELLNESS_JWT_ISSUER` | `http://localhost:8081/realms/wellness-local` | Must match the token's `iss` |
| `WELLNESS_JWT_JWK_SET_URI` | local Keycloak certs URL | Can point at an internal hostname inside Docker |
