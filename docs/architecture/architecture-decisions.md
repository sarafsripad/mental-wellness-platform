# Architecture Decision Log

**Date:** 2026-10-17
**Context document:** [solution-architecture.md](solution-architecture.md)

This log holds ADR-003 to ADR-014. ADR-001 and ADR-002 are separate files, and future ADRs may be added here or as separate files using [ADR-000-template.md](ADR-000-template.md).

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-workstream-separation.md) | Application and research workstreams independently deployable | ACCEPTED |
| [002](ADR-002-implementation-baseline.md) | Implementation baseline v0.1 | SUPERSEDED by [vertical-slice-plan.md](../implementation/vertical-slice-plan.md) (never approved) |
| [003](#adr-003-mobile-and-backend-technology) | Mobile and backend technology | PROPOSED |
| [004](#adr-004-service-decomposition--modular-monolith-plus-worker) | Service decomposition: modular monolith plus worker | PROPOSED |
| [005](#adr-005-raw-audio-in-object-storage-metadata-in-postgresql) | Raw audio in object storage, metadata in PostgreSQL | PROPOSED |
| [006](#adr-006-feature-extraction-runs-on-the-application-side) | Feature extraction runs on the application side | ACCEPTED (decided by the repository owner on 2026-10-17) |
| [007](#adr-007-asynchronous-audio-processing-with-a-postgresql-job-queue) | Asynchronous audio processing with a PostgreSQL job queue | PROPOSED |
| [008](#adr-008-offline-synchronisation-and-conflict-handling) | Offline synchronisation and conflict handling | PROPOSED |
| [009](#adr-009-versioned-feature-contracts) | Versioned feature contracts | ACCEPTED (v2 supersedes v1, decided on 2026-10-17); consent scopes approved 2026-10-18 |
| [010](#adr-010-separation-of-participant-identity-from-research-data) | Separation of participant identity from research data | PROPOSED |
| [011](#adr-011-authentication-authorisation-and-consent-enforcement) | Authentication, authorisation and consent enforcement | ACCEPTED for implementation with synthetic data (responsible engineer, 2026-10-18); privacy/legal review pending |
| [012](#adr-012-experimental-model-deployment-and-release-approval) | Experimental model deployment and release approval | PROPOSED |
| [013](#adr-013-asha-assessment-cases-and-write-only-identity-intake) | ASHA assessment cases and write-only identity intake | ACCEPTED for synthetic data (repository owner, 2026-10-18); privacy lead review pending |
| [014](#adr-014-clinician-review-of-flagged-cases) | Clinician review of flagged cases | ACCEPTED for synthetic data (repository owner, 2026-10-19); clinical lead review pending |

Status meanings: *PROPOSED* means written and awaiting review. *ACCEPTED* means a decision the repository owner explicitly made in this session. No ADR here is approved for clinical use.

---

## ADR-003: Mobile and backend technology

**Context.** ASHA workers use mixed Android devices (and some iOS) with intermittent connectivity. The team needs one mobile codebase, a mature backend ecosystem for security and data work, and Python for audio processing because openSMILE and the research tooling are Python-first.

**Options.**
1. React Native + TypeScript; Java 21 + Spring Boot; Python worker.
2. Native Kotlin and Swift apps; same backend.
3. Flutter; Node.js/TypeScript backend.
4. React Native; Python (FastAPI) for the whole backend.

**Decision.** Option 1, the stack in the brief.

**Rationale.**
- React Native gives one TypeScript codebase for Android and iOS, mature encrypted SQLite (SQLCipher) and keystore libraries, and good i18n support (i18next, Kannada fonts).
- Spring Boot gives strong OIDC resource-server support, Spring Modulith for module boundaries, Flyway, Testcontainers and long-term support on Java 21.
- Keeping Python to the worker isolates the native openSMILE dependency.

**Consequences.**
- *Positive:* one mobile codebase; a well-understood security stack; Python only where it's needed.
- *Negative:* three languages (TypeScript, Java, Python), which raises CI and hiring costs. Audio recording and background sync in React Native rely on native modules that need careful choice and maintenance. Kannada text rendering and input must be tested on low-end Android devices.

**Unresolved.**
- Which React Native audio and encrypted-storage libraries to use (needs a maintenance and security review per the mobile-engineer rules).
- Expo vs bare workflow.
- Minimum Android API level.
- The local machine has Java 8 only, so Java 21 must be installed to build.

---

## ADR-004: Service decomposition — modular monolith plus worker

**Context.** The brief lists eight or more capabilities. Consent is checked in nearly every operation. The team is small and load is low. The harness skills previously assumed separate consent, ingestion, routing and review microservices.

**Options.**
1. One microservice per capability (8–10 services).
2. A Spring Boot modular monolith (`wellness-api`), a separate Python worker, and a separate identity database.
3. A single monolith including audio processing (Java calling openSMILE).

**Decision.** Option 2.

**Rationale.**
- Consent checks become in-process calls rather than network calls with their own failure modes.
- One deployment pipeline.
- Module boundaries are enforced by Spring Modulith and ArchUnit tests, so a module can be split out later without rewriting it.
- The worker is separate because its runtime and resource profile differ, and so it can run with no data-store credentials.
- Identity gets its own database, not just its own module, because that separation is a privacy control, not a convenience.

**Consequences.**
- *Positive:* far less infrastructure; consistent transactions; simpler local development.
- *Negative:* all modules scale and deploy together. A bug in one module can affect the others. Module discipline depends on architecture tests. The `springboot-microservices` skill's "services must not share databases" rule becomes "modules must not share schemas or tables" (the skill needs updating).

**When to split a module out:** it has a different scaling or availability need, a separate team owns it, it has a different compliance boundary (identity is the first candidate), or its release cadence starts blocking others.

**Unresolved.** Whether `identity` should be its own deployable from day one in production. It is recommended before any real participant data is collected.

---

## ADR-005: Raw audio in object storage, metadata in PostgreSQL

**Context.** Recordings are 0.5–10 MB each. They are sensitive (Category 3 in [data-governance.md](../privacy/data-governance.md)) and need their own retention and deletion policy.

**Options.**
1. Audio as `bytea` in PostgreSQL.
2. S3-compatible object storage for audio, with metadata, checksum and object key in PostgreSQL.
3. Files on the API server's filesystem.

**Decision.** Option 2: SeaweedFS locally, and a managed S3-compatible service in production. (*Amended 2026-10-17:* the draft named MinIO for local use, but MinIO no longer publishes freely pullable container images. SeaweedFS 4.47 was checked for S3 API compatibility, default SSE-S3 encryption with no plaintext on disk, lifecycle expiry, and per-key bucket restrictions. The application only depends on the S3 API, so the local choice can change without code changes.)

**Rationale.**
- Keeps the database small and backups fast.
- Server-side encryption per object, bucket policies and lifecycle rules support the retention policy.
- Deletion on withdrawal is a single object delete, recorded in the audit log.

**Consequences.**
- *Positive:* scales independently; retention rules are native; the database never holds audio.
- *Negative:* the database and the bucket can drift apart, so a reconciliation job is needed to find orphan objects or missing content. A second store has to be secured and backed up (or deliberately not backed up, depending on the retention decision).
- **Object keys are random** (`audio/<rec_id>`). They contain no participant information.

**Unresolved.** Raw-audio retention after successful processing, when `AUDIO_RETENTION` is not granted. The proposal is deletion within 30 days, which needs privacy and legal sign-off. Whether audio backups are kept at all.

---

## ADR-006: Feature extraction runs on the application side

**Context.** The harness contradicted itself. The `opensmile-processing` skill and the `audio-engineer` agent placed extraction in Workstream B, while [data-governance.md](../privacy/data-governance.md) and ADR-001 say Workstream B never receives raw audio.

**Options.**
1. The extraction worker is an application component; Workstream B receives feature records only.
2. Workstream B reads raw audio and extracts features itself.

**Decision.** Option 1. The repository owner confirmed this on 2026-10-17.

**Rationale.**
- Keeps raw audio inside the application's consent and retention controls.
- Gives the research side a stable, versioned, consent-filtered input.
- The same extraction serves both the operational QC feedback and research.

**Consequences.**
- *Positive:* one audio access path, which is easy to audit; withdrawal is handled in one place.
- *Negative:* research can't change extraction configs for application data on its own. A new feature set needs a new worker pipeline profile, released by the application team. Workstream B can still extract from separately authorised research audio datasets.
- The skills and agents have been updated to match.

**Unresolved.** A governance process for research teams to request new pipeline profiles.

---

## ADR-007: Asynchronous audio processing with a PostgreSQL job queue

**Context.** Extraction takes seconds and uses CPU. Uploads arrive in bursts when devices reconnect. The mobile app is offline-first anyway, so it doesn't need a synchronous result.

**Options.**
1. Synchronous: the upload request waits for extraction.
2. Asynchronous, with a PostgreSQL job table and worker pull (`SELECT … FOR UPDATE SKIP LOCKED`, leases).
3. Asynchronous, with a message broker (RabbitMQ or Kafka).

**Decision.** Option 2.

**Rationale.**
- No new infrastructure.
- Job state is changed in the same transaction as consent changes, so a withdrawal can cancel queued jobs atomically.
- Pulling over an internal API means the worker needs no database credentials.
- This approach handles far more jobs than the expected load.

**Consequences.**
- *Positive:* simple; transactional; easy to inspect.
- *Negative:* polling latency of a few seconds; queue load on the primary database; a lease-expiry sweeper is needed. A future fan-out need (several consumers per recording) would want events.

**Revisit when:** sustained throughput goes above a few jobs per second, more than one independent consumer needs recording events, or database load from the queue becomes measurable.

**Unresolved.** Whether the API should submit processing jobs automatically when an upload completes, as well as through the explicit `POST /v1/processing-jobs`. Explicit submission is kept for the first slice.

---

## ADR-008: Offline synchronisation and conflict handling

**Context.** Workers capture data for days without connectivity, sometimes on shared devices. Consent may be withdrawn on one channel while another device still has queued sessions.

**Options.**
1. Server-generated IDs and online-only creation.
2. Client-generated random IDs, an idempotent outbox, append-only consent, immutable submissions, and optimistic concurrency for mutable records.
3. A full multi-master sync with CRDTs.

**Decision.** Option 2. See [solution-architecture.md §6](solution-architecture.md#6-offline-capture-and-synchronisation).

**Rationale.**
- Most entities are naturally immutable events (consent, sessions, recordings), so conflicts are rare and can be resolved by fixed rules.
- **Withdrawal always wins.** The server applies the current consent state when it processes a request, whatever the device time.

**Consequences.**
- *Positive:* safe retries; predictable conflicts; no data loss on restart.
- *Negative:* the server must keep idempotency keys for at least 30 days. A session captured offline after a withdrawal on another channel is rejected and its audio discarded, so the worker loses effort. That is correct, and the app must explain it. Mutable-record conflicts need a resolution screen.

**Unresolved.**
- Maximum offline period before re-authentication.
- How shared devices separate worker data.
- Whether consent forms can be updated while devices are offline (forms are pinned by version).

---

## ADR-009: Versioned feature contracts

**Context.** Feature records cross the application/research boundary and must stay reproducible for years. v1.0.0 (`contracts/voice-feature-schema.json`) had a map of features with no units, no missing-value status, no job or recording lineage, and the old consent purposes.

**Options.**
1. Edit v1 in place.
2. Publish v2.0.0 at `contracts/voice-features/feature-record.schema.json`, deprecate v1, add a migration note.
3. Keep both active.

**Decision.** Option 2, confirmed by the repository owner on 2026-10-17. Details are in [contracts/voice-features/README.md](../../contracts/voice-features/README.md).

**Rationale.**
- Semantic versioning: the version is in `$id` and `schema_version`, and records are never rewritten to a new version.
- `additionalProperties: false` throughout, a fixed `record_type`, and a name rule that bans clinical terms make the privacy and clinical-safety rules machine-checkable.
- Example records, including deliberately invalid ones, are validated in CI.

**Consequences.**
- *Positive:* the producer and consumer agree on a strict contract; invalid records are caught at intake.
- *Negative:* strictness means any new field is a schema release. Consumers must pin the exact version until a minor-version policy exists.

**Unresolved.**
- The consent scope values inside the schema depend on ADR-011's consent model approval.
- `inference-contract.json` still uses the old purpose values and the draft-07 `definitions` keyword. It must be revised before any inference work starts.

---

## ADR-010: Separation of participant identity from research data

**Context.** Rule 8: direct identifiers never appear in feature records. Re-identification risk also comes from combining quasi-identifiers (exact timestamps, locality, device).

**Options.**
1. Identity columns in the same tables, with access controls.
2. An `identity` module with its own database and credentials, random `participant_ref` everywhere else, and minimal research exports.
3. A separate identity service from day one.

**Decision.** Option 2 now, moving to option 3 before collecting real participant data (see ADR-004, unresolved).

**Rationale.** A separate database is a structural barrier, which makes a mistaken join impossible.

**Research exports go further:**
- **Every identifier is re-keyed** with a keyed hash (HMAC-SHA-256, truncated to 32 hex) whose key stays on the application side: `record_id`, `session_id`, `recording_id`, `processing_job_id` and `consent_reference.consent_event_id`. Each keeps its prefix, so records still validate against the schema. Without the key, research IDs can't be joined with application IDs.
- One HMAC key per **export programme** (not per file), so Workstream B can link a participant's records across exports for participant-level splits. The participant's own `ppt_` reference is never exported.
- Timestamps truncated to the date.
- `quality.degraded_reason` dropped (free text).
- No locality, no worker reference.

**Consequences.**
- *Positive:* defence in depth; research data is close to de-identified.
- *Negative:* support tasks that need identity go through an audited lookup. Re-keying in exports means withdrawal handling needs the application side to map back (it holds the key). Feature records exported with re-keyed IDs no longer match the stored record IDs exactly, so the export manifest records the method used.

**Unresolved.**
- Whether re-keying is required or optional for the first slice (proposed: required, even for synthetic data, so the code path is exercised).
- Whether `recorded_at_utc` precision should be reduced in the stored record as well as in the export.

---

## ADR-011: Authentication, authorisation and consent enforcement

> **Rule 10:** this ADR changes the consent model. The responsible engineer approved it for implementation and testing with synthetic data on 2026-10-18. Privacy/legal review is still required before any real participant data.

**Context.** There are several user types plus service clients. Consent must be checked by the server for every operation on participant data. The brief asks for separate permissions for screening, audio recording and research reuse, which the old purpose list (`RESEARCH_ONLY`, `CLINICAL_SUPPORT`, `MODEL_TRAINING`, `QUALITY_REVIEW`) didn't provide.

**Options.**
- *Authentication:* (a) a custom username/password system; (b) standards-based OIDC.
- *Authorisation:* (a) roles only; (b) roles plus record-level assignment checks.
- *Consent:* (a) keep the four purposes; (b) six granular scopes with dependency rules.

**Decision.** OIDC (authorization code + PKCE for the app, client credentials for the worker). Roles **plus** assignment checks. Six granular consent scopes:

| Scope | Allows | Depends on |
|---|---|---|
| `SCREENING_PARTICIPATION` | Questionnaires, observations, clinician review, referral | — |
| `AUDIO_RECORDING` | Capturing voice and extracting features for this programme | `SCREENING_PARTICIPATION` |
| `AUDIO_RETENTION` | Keeping raw audio beyond the processing retention window | `AUDIO_RECORDING` |
| `RESEARCH_FEATURE_REUSE` | Pseudonymous feature records in research exports | `AUDIO_RECORDING` |
| `MODEL_TRAINING` | Use of exported features to train models (separate, explicit opt-in) | `RESEARCH_FEATURE_REUSE` |
| `QUALITY_REVIEW` | Authorised staff listening to recordings to improve capture quality | `AUDIO_RECORDING`, `AUDIO_RETENTION` (or within the processing window) |

Mapping from the old purposes: `CLINICAL_SUPPORT` → `SCREENING_PARTICIPATION` + `AUDIO_RECORDING`; `RESEARCH_ONLY` → `RESEARCH_FEATURE_REUSE`; `MODEL_TRAINING` and `QUALITY_REVIEW` are unchanged.

**Enforcement.**
- `consent.requireScopes(consentEventId, participantRef, scopes)` runs before any other logic in every operation on participant data. Scopes are checked **on the referenced consent event**, so a newer event never revives a withdrawn one. Capture times must fall after the event's capture time and before any withdrawal of a required scope.
- Scopes are re-checked at each stage boundary: session creation, recording registration, upload, job submission, job claim, **audio fetch by the worker**, result intake and export build.
- Dependencies: AUDIO_RECORDING needs SCREENING_PARTICIPATION; AUDIO_RETENTION, RESEARCH_FEATURE_REUSE and QUALITY_REVIEW need AUDIO_RECORDING; MODEL_TRAINING needs RESEARCH_FEATURE_REUSE. Withdrawal cascades transitively.
- Every scope requires an explicit decision. There are no defaults.
- Withdrawal cascades to dependent scopes.
- Only adults are accepted until a guardian policy is approved.
- `RESEARCHER` has no application API access.

**Consequences.**
- *Positive:* purpose limitation matches what participants are actually asked. Fine-grained withdrawal is possible.
- *Negative:* consent forms get longer, so comprehension must be tested with participants in Kannada and English. There are more combinations to test (four states × six scopes). Consent forms have to say which scopes each version offers.

**Unresolved (needs legal, ethics and clinical input).**
- Exact consent wording and the validated translation.
- Acceptable capture methods (signature, thumbprint, witnessed verbal).
- Whether consent can be captured fully offline.
- Expiry and re-consent rules.
- Guardian and assisted consent.

**Approval**

| Role | Name | Decision | Date |
|---|---|---|---|
| Responsible engineer | Repository owner (GitHub: `sarafsripad`) | Approved for implementation and testing with synthetic data only | 2026-10-18 |
| Privacy / legal reviewer | | **Pending.** Required before any real participant data (approvals 2–5 in §14 still apply) | |

---

## ADR-012: Experimental model deployment and release approval

**Context.** Rules 5 and 6: no automatic retraining or deployment, and clinical use needs documented evaluation and named sign-off. Research will produce candidate models long before any clinical validation exists.

**Options.**
1. Allow research models to be called from the app behind a feature flag.
2. Keep models in the research environment only. Anything moving towards clinical use needs a staged release with human approval gates and independent clinical validation.

**Decision.** Option 2. See [model-validation-plan.md](../research/model-validation-plan.md).

**Rationale.** Even a flagged model in the app risks its output being read as a diagnosis. Keeping the paths separate makes the boundary structural.

**Consequences.**
- *Positive:* no route from an experiment to clinical workflows.
- *Negative:* slower path to any clinical value. Requires an inference service, an approved inference contract and a model registry to be built later.
- Defined stages: `EXPERIMENTAL → CANDIDATE → APPROVED_FOR_SHADOW_EVALUATION → APPROVED_FOR_CLINICAL_DECISION_SUPPORT`. Each promotion needs named approvers, and rollback is always possible.

**Unresolved.**
- The named clinical advisor and their sign-off process.
- The regulatory pathway (for example, whether software as a medical device rules apply).
- Ethics approval for prospective validation.

---

## ADR-013: ASHA assessment cases and write-only identity intake

**Context.** The ASHA Care prototype (a browser-only, multilingual field-worker flow) has to save its inputs through the API. It collects a name and village, coded registration answers, a yes/no consent, health and lifestyle answers, an illustrative wellness questionnaire, a self-harm safety question and a follow-up decision. On 2026-10-18 the repository owner decided to build the identity module, accept all 12 prototype languages, treat the questions as synthetic-test only, and deliver a web app first.

**Options for the identifiers.**
1. Send identity inside `POST /v1/cases`, as the provisional contract sketched. The cases module would handle names and write to two databases in one request.
2. A separate write-only operation owned by the identity module, `PUT /v1/participants/{ref}/identity`, in its own contract file.

**Decision.** Option 2.
- **`cases` module** (app_db schema `cases`): case, observation set, questionnaire response and completion. Coded values only, with no names, places or free text. Each write checks `ConsentGate` (SCREENING_PARTICIPATION on the case's own consent event) and the assignment.
- **`identity` module**: its own connection pool, credentials and Flyway history on `identity_db`, which is not a Spring `DataSource` bean, so nothing else can inject it. Values are AES-256-GCM encrypted with the participant reference as associated data. There is no read operation. `identity-api.v1.yaml` is write-only, so `wellness-api.v1.yaml` keeps its "no direct identifiers" guarantee.
- **Order:** consent event, then case, then identity. Identity is stored only once consent is on record, and a declined consent sends nothing.
- **Clinical safety:** answers are stored as entered and never scored. A self-harm answer of SOMETIMES or OFTEN sets `urgent_review_flag`, which asks for human follow-up and is not a risk score. The instrument is `illustrative-wellness` 0.1.0, NOT VALIDATED, so case and identity writes accept `SYNTHETIC` only.
- **Languages:** consent and cases accept 12 tags. Voice sessions keep `en-IN` and `kn-IN` until the feature-record schema and research review cover more.

**Rationale.** The cases module never handles a name, the architecture rule "only identity touches identity" stays intact, and moving identity to its own service later (ADR-010 option 3) only means moving one operation.

**Consequences.**
- *Positive:* PII has one entry point that can be audited. The identity write is idempotent by nature (PUT, upsert keyed by `participant_ref`), so there is no cross-database transaction.
- *Negative:* the client makes three calls instead of one. An identity record can briefly exist without a case if the client stops between calls, but it is still covered by a consent event.

**Unresolved.**
- The data-minimisation review of the identity fields (privacy lead).
- Whether withdrawing SCREENING_PARTICIPATION should delete the identity record (privacy lead; not automatic today).
- `listMyCases` filters by the worker who opened the case, while reads and writes follow the current assignment. The two agree only while assignment never changes; switch the list to assignment before ADMIN reassignment is built.
- A validated instrument, approved translations and the urgent-risk protocol (clinical lead, consent-and-data-lifecycle.md §13–14). Each blocks any real participant.

---

## ADR-014: Clinician review of flagged cases

**Context.** ASHA visits can raise an urgent-review flag (self-harm answer) or a follow-up request. Someone trained has to look at them. The provisional contract sketched screening assessments with an instrument score, but there's no validated instrument, so there's nothing to score. On 2026-10-19 the repository owner chose:
- an audited queue that every clinician can see
- no identity access
- coded decisions only
- the decision fed back to the ASHA worker

**Decision.**
- **API (wellness-api 1.2, tag `review`):**
  - `GET /v1/review-queue`: urgent first, then the oldest, filterable by AWAITING_REVIEW, REVIEWED or ALL
  - `GET /v1/review-queue/{caseId}`: coded answers, marked `validated: false`, plus observations and the review history
  - `POST /v1/review-queue/{caseId}/reviews`
  - CLINICIAN role with `review:*` scopes only. The provisional `screening-assessments` outline is retired.
- **Eligibility:** `urgent_review_flag` or `follow_up_requested`, with SCREENING_PARTICIPATION still in effect. Anything else returns 404.
- **Decisions:** NO_FURTHER_ACTION, MONITOR, REFER or URGENT_REFER, each with a timeframe that must fit it, checked in the API and the database. They're stored in `cases.case_review`, which is append-only (enforced by a trigger). The latest decision appears on every case as `review`, so the ASHA app shows it in Follow-ups.
- **Pseudonymity:** clinicians are `clin_` HMAC pseudonyms. No names, villages or free text.
- **Dashboard:** `/review/`, off by default, with its own PKCE client `wellness-review-web`. It has the same CSP and static-only security chain as the ASHA app.
- **Placement:** review lives in the `cases` module, because it only reads and annotates cases. There's no new module dependency.

**Rationale.** Contact happens through the worker who already knows the person, so the write-only identity design (ADR-013) survives. Coded decisions are auditable and keep the free-text exclusion. An append-only history means a decision is never silently changed.

**Consequences.**
- *Positive:* flagged cases now reach a human, and the loop back to the worker is closed.
- *Negative:* every clinician sees every flagged case in the programme, which is broader than a catchment.
- *Negative:* there are no alerts, so an unreviewed urgent case waits until someone opens the queue.
- *Negative:* two clinicians can review the same case at once. Both decisions are kept, and the latest wins.

**Unresolved.**
- Catchment- or team-based visibility, and claiming a case (programme).
- Review time targets, escalation when they're missed, and real referral pathways (clinical lead).
- Whether urgent cases stay visible after consent is withdrawn (clinical lead and privacy lead).
- The queue's consent filter runs after the database limit, so a page can hold fewer than `limit` items. That's acceptable at today's volumes.

