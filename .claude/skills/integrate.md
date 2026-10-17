---
name: integrate
description: Validate the contract and end-to-end data flow between Workstream A (application) and Workstream B (research pipeline). Confirm schema compatibility, pseudonymisation, and consent chain.
---

# /integrate — Cross-Workstream Integration Validation

## What this skill does

Validates the interface between Workstream A and Workstream B: schema compatibility, pseudonymisation boundary, consent chain integrity, and end-to-end data flow correctness.

## Usage

Invoke with `/integrate` followed by your scope. Examples:

- `/integrate validate voice feature handoff between ingestion and extraction`
- `/integrate check that new schema version v1.1.0 is backward compatible`
- `/integrate verify consent chain from mobile capture to training dataset`

## Workflow

When this skill is invoked:

1. **Read both contracts.** Load `contracts/voice-feature-schema.json` and `contracts/inference-contract.json`. Confirm versions are consistent with what both workstreams reference.
2. **Validate schema compatibility.** If a new schema version is proposed, check that the ingestion service (Workstream A producer) and the extraction pipeline (Workstream B consumer) both reference the same version. Incompatibilities must be resolved before merging.
3. **Trace the pseudonymisation boundary.** Confirm that the handoff point (ingestion service → feature extraction) carries only pseudonymous session IDs — never direct identifiers. Check the actual code, not just the schema.
4. **Trace the consent chain.** Follow a session from consent capture (mobile) → consent event stored (consent service) → consent reference in feature record → consent check in extraction pipeline. Identify any break in the chain.
5. **Run contract validation.** Execute `scripts/validate-contracts.sh`. All schemas must pass.
6. **Run integration tests if available.** Execute any cross-service integration tests. Report results.
7. **Produce an integration report.** Summarise: schema versions in use, pseudonymisation boundary check result, consent chain integrity result, test results, and any gaps found.

## Safety Gates

- Do not declare integration valid if any session ID in the handoff payload could be resolved to a direct identifier without authorised key access.
- Do not declare integration valid if any step in the consent chain is missing, assumed, or bypassed.
- Schema version mismatches between workstreams are blocking issues — do not paper over them.

## Expected Outputs

- Integration validation report (console or saved to `docs/architecture/integration-checks/`).
- List of any blocking issues found.
- Recommended remediation steps if issues exist.

## Acceptance Criteria

- [ ] `validate-contracts.sh` exits 0.
- [ ] Schema versions consistent across producer and consumer.
- [ ] No direct identifiers at the Workstream A → B boundary.
- [ ] Consent chain intact from capture to feature store.
- [ ] Integration tests pass (or documented as not yet implemented).
