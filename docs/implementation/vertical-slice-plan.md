# Vertical Slice 1 — Plan

**Version:** 0.1.0
**Date:** 2026-10-17
**Status:** DRAFT. Needs approval before step S4 (consent), which touches consent logic (rule 10)
**Supersedes:** [ADR-002](../architecture/ADR-002-implementation-baseline.md). Its baseline items are steps S0–S1 here.
**Related:** [solution-architecture.md](../architecture/solution-architecture.md), [architecture-decisions.md](../architecture/architecture-decisions.md), [wellness-api.v1.yaml](../../contracts/openapi/wellness-api.v1.yaml), [processing-worker.internal.v1.yaml](../../contracts/openapi/processing-worker.internal.v1.yaml), [feature-record.schema.json](../../contracts/voice-features/feature-record.schema.json)

---

## 1. Goal

Show the integration working end to end using **synthetic audio only**:

> React Native test recording → Spring Boot voice session API → Python/openSMILE feature extraction → PostgreSQL metadata and feature record → research pipeline consumes the versioned contract.

**This slice proves:**
- consent is enforced at every stage
- idempotent offline-style sync works
- audio stays inside the application side
- the worker produces schema-valid feature records
- withdrawal stops processing and excludes data from export
- Workstream B can consume an export using only the contract

**This slice does not include:**
- real participants or real voices
- any clinical prediction or model
- questionnaires, clinician review or referrals
- production hosting, or any external integration

---

## 2. Repository modules

```
contracts/                              # already present: OpenAPI + JSON Schemas + examples
workstream-a/
  backend/wellness-api/                 # Java 21, Spring Boot 3.x, Spring Modulith, Gradle wrapper
    src/main/java/.../consent/          # consent events, withdrawals, requireScopes()
    src/main/java/.../voicesession/     # sessions, recordings, upload → object storage
    src/main/java/.../processing/       # job queue, leases, internal worker API, feature intake
    src/main/java/.../researchexport/   # consent-filtered, re-keyed JSONL export
    src/main/java/.../audit/            # append-only audit events
    src/main/resources/db/migration/    # Flyway (app_db); identity_db unused in this slice
  processing-worker/                    # Python 3.11, openSMILE 3.0.x, httpx, jsonschema
    configs/egemaps_v02_functionals/1.0.0/   # immutable config + sha256
    worker/ (claim loop, QC, extract, build record, validate, submit)
  mobile/                               # React Native + TypeScript, minimal "test recording" flow
deploy/local/
  docker-compose.yml                    # postgres, storage (SeaweedFS), keycloak; wellness-api and worker added later
  keycloak/wellness-local-realm.json    # synthetic test users only
workstream-b/
  research-pipeline/                    # Python: export ingest + contract validation + summary report
tests/
  contracts/                            # pytest: schemas, examples, OpenAPI conformance
  e2e/                                  # pytest: drives the API with synthetic WAV (no mobile)
  fixtures/audio/                       # generated synthetic WAVs (script, not committed binaries)
scripts/
  generate-synthetic-audio.py           # deterministic signal-generated "voice-like" test audio
.github/workflows/ci.yml
```

| Module | Responsibility | Depends on |
|---|---|---|
| `wellness-api` | Public API v1, internal worker API, consent enforcement, storage, queue, export | `wellness-api.v1.yaml`, `processing-worker.internal.v1.yaml`, `feature-record.schema.json` |
| `processing-worker` | Claim, fetch, QC, openSMILE, emit v2.0.0 records | Internal API, feature-record schema |
| `mobile` | Synthetic participant → consent → record → outbox sync → status | `wellness-api.v1.yaml` (generated TypeScript client) |
| `research-pipeline` | Validate every exported record against the schema; reject unknown major versions; apply the withdrawal list; produce a non-clinical summary (counts, QC distribution) | `feature-record.schema.json`, export manifest |

---

## 3. Contract dependencies

| Contract | Version | Used by | State |
|---|---|---|---|
| `contracts/openapi/wellness-api.v1.yaml` | 1.0.0-draft.1 | mobile, wellness-api, e2e tests | Draft. Consent parts need approval |
| `contracts/openapi/processing-worker.internal.v1.yaml` | 1.0.0-draft.1 | wellness-api, worker | Draft |
| `contracts/voice-features/feature-record.schema.json` | 2.0.0 | worker, wellness-api, research-pipeline | Active (consent scope values draft) |
| Research export manifest | — | research-export, research-pipeline | **To define in S7** (JSON Schema under `contracts/research-export/`) |
| `contracts/inference-contract.json` | 1.0.0 | — | **Not used in this slice** (needs revision; see ADR-009) |

**Contract-first rule:** a contract change lands (and passes `scripts/validate-examples.py`) before the code that implements it.

---

## 4. Implementation sequence

Each step is one reviewable pull request. Steps marked 🔒 touch voice recording, consent or the model-release path. They need explicit approval and must not be auto-merged (rule 10).

| Step | Deliverable | Done when |
|---|---|---|
| **S0** Toolchain and CI ✅ CI done 2026-10-17 (`.github/workflows/ci.yml`); Java 21 (21.0.12) installed with JAVA_HOME set | Install Java 21 locally. `requirements-dev.txt`. CI runs `check-secrets.sh`, `validate-contracts.sh` and `validate-examples.py` | CI green on `main` |
| **S1** Contract tests ✅ done 2026-10-17 (`tests/contracts/`, 284 tests) | `tests/contracts/` pytest that wraps the example validation, plus tests for consent scope dependency rules expressed as fixtures | `pytest tests/contracts` green locally and in CI |
| **S2** Local environment ✅ done 2026-10-17 (`deploy/local/`, `verify.sh` 32 checks) | `deploy/local/docker-compose.yml` (postgres with `app_db` + `identity_db`, S3-compatible storage with encryption, keycloak realm with synthetic users and the worker client) | `docker compose up` healthy; README in `deploy/local/` |
| **S3** API skeleton ✅ done 2026-10-17 (`workstream-a/backend/wellness-api`, 69 tests, live smoke 8/8) | Spring Boot app, module structure, Flyway baseline, OIDC resource server, correlation-ID filter, problem+json handler, audit module, ArchUnit/Modulith boundary tests | Health endpoint secured; boundary tests pass |
| **S4** 🔒 Consent module ✅ done 2026-10-18 on branch `s4-consent` (approved for synthetic data; review before merge) | `POST /v1/consent-events`, withdrawals, consent status, `requireScopes()`, dependency and cascade rules, adult-only and `data_origin` guards | Consent-path tests: granted / missing / withdrawn / expired-placeholder × each scope; approval recorded |
| **S5** 🔒 Voice session and upload ✅ done 2026-10-18 on branch `s5-voice` (review before merge) | Sessions, recording metadata, content upload to object storage with checksum, consent re-check, idempotency store | Testcontainers integration tests (Postgres + S3-compatible storage); audio never appears in logs (log-capture test) |
| **S6** Processing queue and internal API | `POST/GET /v1/processing-jobs`; claim/lease (`SKIP LOCKED`), audio fetch, result intake with schema validation, failure, lease sweeper, cancellation on withdrawal | Concurrency test (two claimers, one job); rejects an invalid record; withdrawal cancels a queued job |
| **S7** 🔒 Processing worker | Python worker: QC (duration, level, clipping, silence, SNR estimate), openSMILE eGeMAPSv02 functionals with the pinned config, record builder, local schema validation | Determinism test (same WAV → identical record minus timestamps); QC FAIL → failure call; records validate |
| **S8** Research export and consumer | `research-export` job (JSONL + manifest, re-keyed IDs, consent-filtered), export manifest schema, `workstream-b/research-pipeline` ingest + validation + summary | Withdrawn participant absent from export; consumer rejects a tampered or unknown-version record |
| **S9** 🔒 Mobile test-recording flow | Synthetic participant, consent screen (en/kn strings via i18n), recorder (16 kHz mono WAV), encrypted local store, outbox, status screen | Runs on an Android emulator against local compose; offline → online sync demo; recorder blocked without consent |
| **S10** End-to-end demonstration | `tests/e2e` (API-driven, synthetic WAV) in CI; a manual mobile run recorded in the slice report | §7 acceptance criteria all met |

S4 and S5 can't start until the consent model ([consent-and-data-lifecycle.md](../privacy/consent-and-data-lifecycle.md)) has named approval. S0–S3 don't depend on it.

---

## 5. Test strategy

| Level | Scope | Tools |
|---|---|---|
| Contract | Schemas, examples (valid and invalid), OpenAPI structure, embedded examples, policy checks | `scripts/validate-examples.py`, pytest |
| Unit | Consent scope maths, idempotency, QC functions, record builder | JUnit 5, pytest, Jest |
| Integration | API against real Postgres and S3-compatible storage containers; migrations from blank | Testcontainers (no database mocks) |
| API conformance | Running API against the OpenAPI document (status codes, schemas, problem bodies) | Schemathesis in CI against the compose stack |
| Consent path | Every participant-data endpoint × {granted, missing, withdrawn, expired} | JUnit parameterised tests |
| Security | Authorisation matrix (role × endpoint × assigned/unassigned); secrets scan; dependency and image scan; no PII or audio in logs | JUnit, `check-secrets.sh`, OWASP Dependency-Check or Trivy |
| Determinism | Worker output stable for fixed input and config | pytest |
| End to end | Synthetic WAV through API → worker → record → export → consumer | pytest + Docker Compose |
| Mobile | Consent gate, outbox state machine, i18n coverage (no hard-coded strings) | Jest + React Native Testing Library; Detox later |

**Test data:** all fixtures are synthetic. Audio is generated by `scripts/generate-synthetic-audio.py` (harmonic tones with pitch and amplitude variation, plus noise, silence and clipping variants). It is not recorded or synthesised from a real person's voice, and every record carries `data_origin: SYNTHETIC`. `AUTHORIZED_TEST_AUDIO` (for example, team members recording themselves) needs a documented consent and provenance entry under `contracts/` first (rule 3), and isn't needed for this slice.

---

## 6. Local development setup (target)

```bash
# prerequisites: Docker, Java 21, Node 20 LTS, Python 3.11, Android Studio emulator
python3 -m pip install -r requirements-dev.txt
bash scripts/validate-contracts.sh && python3 scripts/validate-examples.py

docker compose -f deploy/local/docker-compose.yml up -d     # S2+
./workstream-a/backend/wellness-api/gradlew -p workstream-a/backend/wellness-api test   # S3+
python3 scripts/generate-synthetic-audio.py --out tests/fixtures/audio                 # S7+
pytest tests/e2e                                                                     # S10
```

The commands from S2 onward refer to files that don't exist yet. They are the target, not current instructions.

---

## 7. Acceptance criteria for the slice

- [ ] A synthetic recording made in the mobile app on an emulator ends up as a stored v2.0.0 feature record, and the app shows `SUCCEEDED` with the audio-quality outcome and no feature values.
- [ ] The same flow runs headless in CI (`tests/e2e`) from a generated WAV.
- [ ] Without `AUDIO_RECORDING`: the recorder is blocked in the app; the API returns 422 `CONSENT_NOT_ACTIVE` for session, upload and job; the worker never receives audio.
- [ ] Withdrawing `AUDIO_RECORDING` while a job is queued: the job ends `CANCELLED` with `CONSENT_WITHDRAWN`, the audio is deleted, and no record is produced.
- [ ] Withdrawing `RESEARCH_FEATURE_REUSE`: the next export excludes the participant, and the export manifest lists the exclusion count.
- [ ] Resubmitting any create with the same `Idempotency-Key` returns the original result, and there are no duplicate rows.
- [ ] A silent or clipped synthetic WAV gives `FAILED` / `AUDIO_QUALITY_FAILED` and no feature record.
- [ ] Every stored and exported record validates against `feature-record.schema.json`. The research consumer rejects a record with a modified `schema_version` major.
- [ ] Logs from the API and worker for the whole run contain no audio bytes, no feature values and no request bodies (automated log scan).
- [ ] The worker container has no database or storage credentials (checked in the compose config).
- [ ] The API rejects `data_origin: PARTICIPANT` in the local environment.
- [ ] All CI checks pass, and the 🔒 steps have recorded approvals.

---

## 8. Risks and prerequisites

| # | Risk / prerequisite | Impact | Mitigation |
|---|---|---|---|
| 1 | **openSMILE licence.** Our understanding is that openSMILE 3.x is licensed by audEERING free for private, research and educational use only, with commercial use needing a separate licence. **This is unverified and must be confirmed by legal.** | May block any commercial or production use | Confirm the licence position with legal **before S7**. Record it in `contracts/` (rule 3) |
| 2 | **Consent model approval** (rule 10) | Blocks S4, S5, S7 and S9 | Get named approval of consent-and-data-lifecycle.md early; S0–S3 can proceed in parallel |
| 3 | The local machine has **Java 8**; Java 21 and a Gradle wrapper are needed | Blocks S3 | Install a Java 21 JDK; use the Gradle wrapper (no global Gradle) |
| 4 | React Native audio and encrypted-storage library choice | S9 delay or security gaps | Short spike in S9 with a maintenance and security review (mobile-engineer rule) |
| 5 | Keycloak setup complexity for a small team | S2/S3 delay | A minimal realm export committed to the repo; synthetic users only |
| 6 | Kannada rendering and input on low-end Android | UX defects | Include a Kannada string set in S9; test on a low-spec emulator profile |
| 7 | Synthetic audio doesn't exercise every real QC edge case | False confidence in QC | Clearly limited to software validation; QC thresholds are revisited with authorised test audio later |
| 8 | `inference-contract.json` out of date (old purposes, `definitions` keyword) | Confusion if used | Not used in this slice; revise before any inference work (ADR-009) |
| 9 | Unresolved retention periods | Wrong deletion behaviour | Implement configurable retention with conservative defaults (30-day processing window) |

---

## 9. After the slice

Questionnaire and observation modules (once instruments are approved), clinician web app and review queue, referral workflow, identity module enabled with an audited lookup, and production environment design. Inference stays blocked until [model-validation-plan.md](../research/model-validation-plan.md) stages are met.
