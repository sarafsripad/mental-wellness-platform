---
name: opensmile-processing
description: Configure and run openSMILE feature extraction in the application-side audio-processing worker, validate output records against the v2 feature-record schema, and document versioned pipeline profiles.
---

# openSMILE Processing Skill

## Purpose

Configure and maintain openSMILE extraction in the **application-side audio-processing worker** (`workstream-a/processing-worker/`). Per ADR-006 the worker is the only component that turns participant audio into features. Workstream B receives feature records only and never receives raw audio. Workstream B may run openSMILE only on separately authorised research audio datasets that are documented in `contracts/` (rule 3).

## Prerequisites

- Read `contracts/voice-features/feature-record.schema.json` (v2.0.0) and `contracts/voice-features/README.md`.
- Read `contracts/openapi/processing-worker.internal.v1.yaml`. It is the worker's only interface: claim, fetch audio, submit result, report failure.
- Know the openSMILE version in use and record it in `workstream-a/processing-worker/configs/README.md`.
- Confirm the openSMILE licence position is documented before any non-research use (see `docs/implementation/vertical-slice-plan.md`, risk 1).
- Consent is checked by the backend at claim and at result intake. The worker copies the lease's `consent_reference` into the record unchanged and never invents or defaults one.

## Workflow

1. **Select or define a pipeline profile.** Each profile (for example `EGEMAPS_V02_FUNCTIONALS_V1`) maps to an immutable config at `workstream-a/processing-worker/configs/<config_id>/<config_version>/` with a recorded SHA-256. Never modify a config that has been used; create a new version and a new profile, and add the profile to the OpenAPI enums.
2. **Validate audio input.** Verify the SHA-256 against the lease, decode it, and check the format, sampling rate and duration.
3. **Run server-side QC.** Duration, level, clipping, silence, an SNR estimate and the speech ratio. A `FAIL` result goes to `POST …/failure` (`AUDIO_QUALITY_FAILED`) and never produces a feature record.
4. **Run extraction.** Invoke openSMILE with the pinned config. A non-zero exit or a parse error is reported as a failure (`EXTRACTION_ERROR`) and never silently skipped.
5. **Build the record.** Map the output to `features[]` as `{name, value, unit, status, reason}`, using native openSMILE names (never clinical terms). Non-finite or uncomputable values get `status` `INVALID` or `MISSING` and a `null` value. Fill `extraction`, `audio`, `processing`, `quality` and `provenance` (including `data_origin` from the lease).
6. **Validate the record locally** against the schema before submitting. A record that fails is a worker bug, so report `INTERNAL_ERROR`.
7. **Submit** with `POST …/result`. The backend validates again and stores it. The worker keeps nothing: audio and output are deleted from scratch space when the job ends.

## Constraints

- Configs are immutable once used. Pin versions and never modify retroactively.
- Feature names are acoustic descriptors only. The schema rejects clinical or prediction-like terms.
- Extraction must be deterministic: the same audio and config give identical feature values.
- No extraction from `FAIL`-quality audio. `DEGRADED` requires a `degraded_reason`.
- No logging of audio, feature values or file paths. Log job IDs, durations and error codes only.
- The worker holds no database or storage credentials.
- Document any config change in `workstream-a/processing-worker/configs/README.md`, with the rationale and the profile it affects.

## Expected Outputs

- Versioned openSMILE config and pipeline profile.
- Worker code for the step being changed.
- Feature records that validate against `feature-record.schema.json`.
- A determinism test and QC tests that use synthetic audio only.

## Acceptance Criteria

- [ ] Extraction is reproducible: same inputs give identical outputs (test).
- [ ] All output records validate against `contracts/voice-features/feature-record.schema.json`.
- [ ] Feature names are acoustic, not clinical.
- [ ] `extraction.config_id`, `config_version` and `config_sha256` are set in every record.
- [ ] FAIL-quality audio is reported as a failure with no record.
- [ ] No direct identifiers, audio or transcript in output records.
