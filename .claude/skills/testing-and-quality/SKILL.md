---
name: testing-and-quality
description: Write, run, and review tests and quality checks across both workstreams of the Mental Wellness Platform.
---

# Testing and Quality Skill

## Purpose

Define test strategies, write tests, run automated checks, and review coverage for Workstream A (application) and Workstream B (research pipeline).

## Prerequisites

- Identify the test framework in use for the component (pytest, JUnit, Jest, etc.).
- Read the component under test before writing tests.
- Do not mock infrastructure that behaves differently from production (e.g., consent service, schema validator).

## Workflow

### Application Tests (Workstream A)

1. **Unit tests** — individual service methods, consent validation logic, offline-sync conflict resolution.
2. **Integration tests** — REST API endpoints against a real (test) database. Do not mock the DB for integration tests.
3. **Contract tests** — validate that request/response bodies conform to `contracts/` schemas.
4. **Consent-path tests** — every API that touches voice or feature data must have a test case for: granted, missing, withdrawn and expired consent, for each required scope.
5. **Offline-sync tests** — simulate connectivity loss and verify queue behaviour, deduplication, and conflict resolution.

### Research Pipeline Tests (Workstream B)

1. **Feature extraction determinism** — given the same audio input and config, openSMILE must produce identical feature values.
2. **Schema conformance** — extracted feature records must validate against `contracts/voice-features/feature-record.schema.json` (see `scripts/validate-examples.py`).
3. **Dataset integrity** — verify row counts, consent coverage, and absence of direct identifiers in training datasets.
4. **Model evaluation regression** — after any model change, re-run the held-out evaluation set and verify metrics do not fall below documented thresholds.
5. **No-training-on-production gate** — assert that production inference paths cannot write back to training data stores.

## Constraints

- Tests must not make network calls to external services. Stub at the network boundary.
- Do not commit test fixtures that contain real audio, real participant data, or real identifiers.
- Model evaluation tests must use a frozen, versioned held-out set — never the full dataset.
- Test files may not contain hard-coded secrets.

## Expected Outputs

- Test files co-located with the component they test, or in a designated `tests/` directory.
- Coverage report if coverage tooling is configured.
- CI configuration update if new test commands are added.

## Acceptance Criteria

- [ ] All new public functions/endpoints have at least one happy-path and one error-path test.
- [ ] Consent-path tests cover all four states: granted, missing, withdrawn, expired.
- [ ] `scripts/validate-contracts.sh` passes.
- [ ] No test fixture contains real participant data.
- [ ] Test suite passes locally before declaring work complete.
