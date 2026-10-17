---
name: api-contract-design
description: Design, version, and validate shared API and feature contracts between Workstream A and Workstream B.
---

# API Contract Design Skill

## Purpose

Define and evolve the versioned schemas and API contracts in `contracts/` that bridge the application workstream and the research workstream.

## Prerequisites

- Read all files in `contracts/` before proposing changes.
- Understand which services consume each contract (grep for the schema `$id`).
- Any change that breaks an existing consumer requires a new schema version, not an in-place edit.

## Workflow

1. **Identify the contract boundary.** Is this a new contract, an additive change, or a breaking change?
2. **Draft the schema.** Use JSON Schema draft 2020-12. All schemas must have `$schema`, `$id` (with version), `title`, `description`, and explicit `required` fields.
3. **Enforce privacy invariants:**
   - Feature records must use pseudonymous session IDs, never direct identifiers.
   - Consent reference must be a foreign key to a real consent event, never a boolean flag.
   - Clinical interpretations must not appear in feature schemas — features are numerical values only.
4. **Version the change.** Follow semver. Additive (new optional fields): minor bump. Breaking (removed/renamed fields, type changes): major bump with migration note.
5. **Validate the schema.** Run `scripts/validate-contracts.sh`.
6. **Document the change.** Add a changelog entry inside the schema file under `"x-changelog"`.

## Constraints

- Schema `$id` must embed the version (e.g., `/contracts/voice-feature-schema/v1.1.0`).
- The `features` object in `voice-feature-schema` must never contain a field named with a clinical label (e.g., `depression_score`, `anxiety_label`). Use opaque feature names.
- Inference response schemas must include a `limitations` field and a `result_is_not_a_diagnosis` constant.
- Never remove a `consent_reference` or `session_id` requirement.

## Expected Outputs

- Updated or new JSON Schema file in `contracts/`.
- Passing `scripts/validate-contracts.sh`.
- Migration note if breaking change.

## Acceptance Criteria

- [ ] Schema validates against JSON Schema draft 2020-12 meta-schema.
- [ ] All `required` fields are present and typed.
- [ ] Privacy invariants enforced (no direct identifiers, no clinical labels in features).
- [ ] Version bumped correctly relative to previous version.
- [ ] `validate-contracts.sh` exits 0.
