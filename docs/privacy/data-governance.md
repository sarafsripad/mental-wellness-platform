# Data Governance and Privacy Requirements

**Version:** 1.0.0
**Date:** 2026-10-17
**Status:** ACTIVE

---

> **Update 2026-10-17:** a granular consent-scope model and full data lifecycle are proposed in [consent-and-data-lifecycle.md](consent-and-data-lifecycle.md) (DRAFT, pending named approval). Until it is approved, the purposes below remain the recorded baseline; no participant data has been collected under either.

## Purpose

This document defines the data governance requirements for the Mental Wellness Platform. It applies to all data collection, storage, processing, and transfer operations in both workstreams. All engineers, agents, and contractors working on this platform must read and comply with these requirements.

---

## Data Categories

### Category 1 — Direct Identifiers (Highest Sensitivity)

Fields that directly identify a participant:
- Name, alias, ASHA worker name
- Phone number
- Village, district, address
- Date of birth
- Any government-issued ID reference

**Rules:**
- Must never appear in `contracts/voice-features/feature-record.schema.json` records.
- Must never appear in feature stores, training datasets, or inference logs.
- Stored only in the identity service with access logging.
- Transmitted only over encrypted channels with mutual authentication.

### Category 2 — Pseudonymous Identifiers

- `session_id` (format: `sess_<32 hex chars>`)
- `consent_event_id` (format: `ce_<32 hex chars>`)

**Rules:**
- May appear in feature records and inference contracts.
- The mapping from `session_id` to Category 1 identifiers is held only in the identity service.
- Access to the identity service mapping requires authorised role and is audit-logged.
- Pseudonymous IDs must be generated with a cryptographically random component — not derived from any direct identifier.

### Category 3 — Voice Recordings (High Sensitivity)

Raw audio files captured from participants.

**Rules:**
- Encrypted at rest on device using device-derived keys before any other processing.
- Transmitted only over TLS 1.2+.
- Stored server-side in encrypted object storage.
- Retention period must be defined per consent purpose before collection begins.
- Must not be used for model training unless `MODEL_TRAINING` is in the consent's `permitted_purposes`.
- Must not be accessible to Workstream B directly — only extracted features are transferred.

### Category 4 — Derived Features

Acoustic feature vectors extracted from voice recordings.

**Rules:**
- Must conform to `contracts/voice-features/feature-record.schema.json`.
- Must include a valid `consent_reference`.
- Feature names must be acoustic descriptors — never clinical labels.
- May be used for purposes listed in `consent_reference.permitted_purposes` only.

### Category 5 — Inference Results

Model outputs from the inference service.

**Rules:**
- Must conform to `contracts/inference-contract.json`.
- `result_is_not_a_diagnosis` must always be `true`.
- Must include a non-empty `limitations` array.
- Must not be stored alongside Category 1 identifiers without explicit linkage policy.

---

## Consent Requirements

### Consent Event

A consent event must be recorded before any voice capture. A consent event contains:
- Participant pseudonymous ID (generated at consent time)
- Timestamp of consent
- Language in which consent was given
- Consent form version
- Purposes granted (from the permitted purposes enum)
- ASHA worker pseudonymous ID (the capturing worker)

### Permitted Purposes

| Purpose | Description | Requires Separate Consent |
|---------|-------------|--------------------------|
| `RESEARCH_ONLY` | Features used for academic research, not patient care | No |
| `CLINICAL_SUPPORT` | Features used to assist clinician review | No |
| `MODEL_TRAINING` | Features used to train ML models | Yes — explicit opt-in |
| `QUALITY_REVIEW` | Recording reviewed for capture quality improvement | No |

### Consent Withdrawal

Participants may withdraw consent at any time. On withdrawal:
1. The consent event is flagged as withdrawn in the consent service.
2. The ingestion service must not accept new feature records referencing a withdrawn consent event.
3. Existing features in the research pipeline are flagged for removal from future training datasets.
4. Removal from current training sets is triggered on next dataset rebuild — not automatically mid-run.

---

## Data Residency

- All Category 1 and Category 3 data must remain in the defined geographic boundary (to be specified by legal/compliance review).
- Pseudonymous feature records (Category 4) may be processed outside the geographic boundary only if the identity mapping is not transferred.

---

## Retention Periods

Retention periods are to be defined by the product and legal team for each data category before production launch. Until defined, default retention must be the shortest practically feasible period. Engineers must not implement indefinite retention.

---

## Access Control Requirements

| Data Category | Access Requirement |
|---------------|-------------------|
| Category 1 — Direct Identifiers | Authorised role, audit log, MFA |
| Category 2 — Pseudonymous IDs | Standard service authentication |
| Category 3 — Voice Recordings | Authorised role, encrypted, audit log |
| Category 4 — Derived Features | Service authentication, consent check |
| Category 5 — Inference Results | Clinician role for clinical use |

---

## Audit Logging

- All access to Category 1 data must be logged with: accessor identity, timestamp, reason code, record accessed.
- Logs must not contain the Category 1 data itself.
- Logs must be retained for a minimum of 12 months (subject to legal review).

---

## Open Items (Requiring Legal/Clinical Review)

- [ ] Retention periods per data category.
- [ ] Geographic boundary definition.
- [ ] Guardian consent requirements for minor participants.
- [ ] Process for regulatory reporting if a data breach occurs.
- [ ] Clinical validation requirements before `CLINICAL_SUPPORT` purpose is activated.
