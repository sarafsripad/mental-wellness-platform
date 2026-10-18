# Voice Feature Record Contract

**Schema:** [feature-record.schema.json](feature-record.schema.json)
**Version:** 2.0.0, JSON Schema draft 2020-12
**Producer:** audio-processing worker (application side, Workstream A)
**Consumers:** wellness API (stores records), research export, Workstream B research pipeline (reads only)
**Status:** ACTIVE. The `consentScope` values were approved for implementation with synthetic data on 2026-10-18; privacy/legal review of [consent-and-data-lifecycle.md](../../docs/privacy/consent-and-data-lifecycle.md) is still pending

---

## What a feature record is, and is not

A feature record holds the acoustic and prosodic **measurements** that openSMILE extracted from one consented recording, plus enough metadata to reproduce the extraction.

**It is not a clinical result.** It holds no prediction, score, risk level, label or diagnosis, and the schema rejects records that try to add one. `record_type` is fixed to `ENGINEERING_FEATURE_RECORD` so consumers can't confuse it with model output. Any future model output uses a separate contract (`contracts/inference-contract.json`) and still has to pass the human-review and approval gates in [model-validation-plan.md](../../docs/research/model-validation-plan.md).

**What the schema prevents:**

| Invariant | How it is enforced |
|---|---|
| No direct identifiers (name, phone, address, location, device ID) | `additionalProperties: false` at every level; IDs are random, prefixed pseudonyms (`sess_`, `rec_`, `job_`, `frec_`, `ce_` + 32 hex) |
| No raw audio or transcript | No field can hold them; `additionalProperties: false` rejects extras such as `content_base64` |
| No clinical terms in feature names | `features[].name` must match openSMILE-style characters and must not contain, in any letter case, terms such as *depress*, *bipolar*, *dementia*, *mood*, *phq*, *diagnos*, *risk*, *score*, *label* or *predict*. This is a guard, not a complete list; new feature sets still need review. |
| No free text that could carry participant data | `processing.warnings[]` and `features[].reason` are upper-case codes only. `quality.degraded_reason` is short technical text and is dropped from research exports |
| Consent recorded, not assumed | `consent_reference.consent_event_id` is required, and `scopes_verified` must contain `AUDIO_RECORDING` |
| No record from failed audio | `quality.status` allows only `PASS` or `DEGRADED`; failed jobs are reported through the processing-job status instead |
| `DEGRADED` audio needs a reason | `degraded_reason` is required and must not be empty |
| Missing values are explicit | Each feature carries a `status`; `VALID` requires a number, any other status requires `null` |
| Synthetic data is labelled | `provenance.data_origin` is required (`SYNTHETIC`, `AUTHORIZED_TEST_AUDIO` or `PARTICIPANT`) |

**What the schema can't check.** These must be enforced by code and tests:
- whether a `consent_event_id` really exists and is still active
- whether an ID was generated randomly
- whether a feature value is physically plausible

The `consent_reference` in a record is a snapshot taken when extraction ran. It doesn't authorise later use. Consumers rely on the consent-filtered research export, which re-checks current consent.

---

## Versioning and compatibility

The schema uses semantic versioning. The version appears in both `$id` and `version`, and `schema_version` in each record must equal it.

| Change | Version bump | Example |
|---|---|---|
| New optional field, new enum value consumers can ignore | Minor (2.**1**.0) | Adding `quality.reverb_estimate` |
| Description or documentation only | Patch (2.0.**1**) | Clarifying a unit |
| Removed or renamed field, new required field, narrowed type or enum | Major (**3**.0.0) | Making `unit` required |

Rules:
- A released schema version is never edited in place. Every change adds an `x-changelog` entry.
- Producers write exactly one schema version at a time. Consumers must reject records with an unknown **major** version. They may accept higher **minor** versions by ignoring unknown optional fields, but this needs a schema built to allow it. v2.0.0 uses `additionalProperties: false`, so it doesn't, and consumers should pin the exact version until a 2.1 policy is agreed.
- Stored records are never rewritten to a new version. A version change means re-extracting under a new `extraction.config_version`, or running a documented, reviewed migration job.

### Migration from `contracts/voice-feature-schema.json` v1.0.0

v1.0.0 is **deprecated**. No producer or consumer was ever built against it, so no stored data needs migrating. If any tooling is written against v1, these are the mappings:

| v1.0.0 | v2.0.0 |
|---|---|
| `features` (object of name → value) | `features[]` of `{name, value, unit, status, reason}` |
| `feature_set_id` | `extraction.config_id` + `extraction.config_version` + `extraction.feature_set` |
| `extraction_version` | `extraction.pipeline_version` |
| `consent_reference.permitted_purposes` | `consent_reference.scopes_verified` (new scope list; see the mapping in the consent document) |
| `audio_quality` | `quality` (`FAIL` removed) plus `audio` (technical properties) |
| `provenance.opensmile_version` | `extraction.tool_version` |
| `provenance.pipeline_run_id` | `processing_job_id` |
| (none) | `record_type`, `record_id`, `recording_id`, `provenance.data_origin` |

---

## Examples

All examples use obviously synthetic IDs (`…0001`) and made-up values. They aren't real recordings or real measurements.

| File | Expected | Why |
|---|---|---|
| [valid/complete-pass.json](examples/valid/complete-pass.json) | valid | Complete Kannada record, `PASS` quality |
| [valid/degraded-with-missing-feature.json](examples/valid/degraded-with-missing-feature.json) | valid | `DEGRADED` with reason, one `MISSING` feature with `null` value |
| [invalid/missing-consent-reference.json](examples/invalid/missing-consent-reference.json) | invalid | No `consent_reference` |
| [invalid/no-audio-recording-consent.json](examples/invalid/no-audio-recording-consent.json) | invalid | `scopes_verified` lacks `AUDIO_RECORDING` |
| [invalid/direct-identifier-field.json](examples/invalid/direct-identifier-field.json) | invalid | Extra `participant_name` field |
| [invalid/clinical-feature-name.json](examples/invalid/clinical-feature-name.json) | invalid | Feature named `depression_score` |
| [invalid/clinical-feature-name-uppercase.json](examples/invalid/clinical-feature-name-uppercase.json) | invalid | Feature named `DEPRESSION_index` (case-insensitive rule) |
| [invalid/free-text-warning.json](examples/invalid/free-text-warning.json) | invalid | Free-text warning instead of a code |
| [invalid/model-prediction-embedded.json](examples/invalid/model-prediction-embedded.json) | invalid | Extra `prediction` block |
| [invalid/raw-audio-embedded.json](examples/invalid/raw-audio-embedded.json) | invalid | Base64 audio in `audio.content_base64` |
| [invalid/degraded-without-reason.json](examples/invalid/degraded-without-reason.json) | invalid | `DEGRADED` with `degraded_reason: null` |
| [invalid/valid-feature-with-null-value.json](examples/invalid/valid-feature-with-null-value.json) | invalid | `status: VALID` but `value: null` |
| [invalid/malformed-session-id.json](examples/invalid/malformed-session-id.json) | invalid | `session_id` not `sess_` + 32 hex |
| [invalid/fail-quality-record.json](examples/invalid/fail-quality-record.json) | invalid | `quality.status: FAIL` |

## Validation

```bash
python3 -m pip install -r requirements-dev.txt
python3 scripts/validate-examples.py
```

The script checks that every `valid/` example passes and every `invalid/` example fails. It also validates the OpenAPI documents and their embedded examples.
