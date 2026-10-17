# ADR-002: Implementation Baseline v0.1 — Contract Tooling Before Participant-Data Code

**Status:** SUPERSEDED by [docs/implementation/vertical-slice-plan.md](../implementation/vertical-slice-plan.md) (2026-10-17). Never approved; its items are steps S0–S1 of the slice plan, and findings 5–7 are addressed by ADR-009 and the v2 feature contract.
**Date:** 2026-10-17
**Workstream:** BOTH
**Author:** Claude Code (drafted for human review)
**Approver:** _pending — a named responsible person must sign off below_

---

## Context

The harness (CLAUDE.md, 17 skills, 7 agents, 2 contracts, 3 hook scripts, governance docs) was reviewed on 2026-10-17 before any application or research code was written. The review confirmed that the structure is consistent with ADR-001 and the non-negotiable rules, and found the gaps below.

### Review findings

| # | Severity | Finding | Location |
|---|---|---|---|
| 1 | BLOCKING (fixed, uncommitted) | `validate-contracts.sh` crashed on unescaped `$` in a heredoc | `scripts/validate-contracts.sh` (fixed in 13aa079) |
| 2 | BLOCKING (fixed, uncommitted) | Hook scripts exited 1 on failure, which Claude Code treats as non-blocking; secret `[BLOCK]` results did not stop the agent | `scripts/check-secrets.sh`, `scripts/validate-contracts.sh` |
| 3 | WARNING (fixed, uncommitted) | Secret scanner ignored untracked files | `scripts/check-secrets.sh` |
| 4 | WARNING (fixed, uncommitted) | Required `docs/research/experiment-log.md` did not exist | `.claude/agents/ml-researcher.md:26`, `.claude/skills/ml-experimentation/SKILL.md` |
| 5 | BLOCKING | Several skills require feature *records* to "pass `validate-contracts.sh`", but that script only checks the schema files themselves. No tool validates a record against a schema, so the rule cannot currently be followed. | `opensmile-processing.md` step 6, `workstream-b.md` step 5, `testing-and-quality.md`, `audio-engineer.md` rule 3 |
| 6 | WARNING | Consent states are inconsistent: `privacy-consent` requires tests for granted, missing, withdrawn **and expired**; `test-engineer` and `testing-and-quality` list only three states. | `.claude/skills/privacy-consent/SKILL.md`, `.claude/agents/test-engineer.md`, `.claude/skills/testing-and-quality/SKILL.md` |
| 7 | WARNING | `inference-contract.json` uses the draft-07 keyword `definitions` under a draft 2020-12 `$schema` (2020-12 uses `$defs`), and does not enforce that `indicator` is present on `SUCCESS`/`LOW_CONFIDENCE` or that `error` is present on `ERROR`. | `contracts/inference-contract.json` |
| 8 | INFO | Per-agent "Allowed Bash Commands" are prose only. `settings.json` has no per-agent allow-list, so these limits rely on the agent following instructions. | `.claude/agents/*.md` |
| 9 | INFO | No CI exists, so the hook checks only run inside Claude Code sessions. | repository root |

Finding 8 is accepted as a known limitation for v0.1 and is not addressed by this ADR.

### Forces

- Rule 10 requires explicit approval for any change touching voice recording, consent logic or model release. The first slice should be useful without touching those paths.
- Every later slice (consent service, capture, extraction) depends on being able to prove a record conforms to the contracts.
- The local toolchain has Python 3.9, Java and Node, but no Maven or Gradle, so a Spring Boot service cannot be built and tested here yet.

## Decision

> We will make the first implementation baseline a contract-tooling slice: a record validator, synthetic fixtures, contract tests and CI. No consent, capture, extraction or model code is included.

### In scope (v0.1)

1. **Commit the harness fixes** for findings 1–4.
2. **Record validator** `scripts/validate-record.py`: validates one or more JSON records against a named contract using `jsonschema` (draft 2020-12). Exit 0 on success and 1 on failure, so other scripts and CI can use it. Fixes finding 5; the four skills and the audio-engineer agent are updated to reference it.
3. **Synthetic fixtures** in `tests/fixtures/`: valid and deliberately invalid records, using obviously fake IDs (for example `sess_000…`, `ce_000…`). No real audio, identifiers or participant data.
4. **Contract tests** in `tests/contracts/` (pytest). They cover at least:
   - a valid feature record and a valid inference request and response pass
   - missing `consent_reference` fails
   - a direct-identifier field (for example `name`, `phone`) is rejected
   - `DEGRADED` audio without `degraded_reason` fails
   - malformed `session_id` / `consent_event_id` fail
   - `result_is_not_a_diagnosis: false` fails
   - an empty `limitations` array fails
5. **Inference contract v1.1.0** (fixes finding 7): rename `definitions` to `$defs`, and add `if`/`then` rules so `indicator` is required for `SUCCESS`/`LOW_CONFIDENCE` and `error` is required for `ERROR`. Add an `x-changelog` entry and a migration note. There are no consumers yet, so no migration work is needed.
6. **Consent-state wording** (fixes finding 6): align `test-engineer.md` and `testing-and-quality.md` with `privacy-consent.md` on four states: granted, missing, withdrawn, expired. This is a wording change to test guidance only. No consent logic is implemented.
7. **CI** `.github/workflows/contracts.yml` (fixes finding 9): runs the three hook scripts and the pytest suite on every push and pull request.
8. **Pinned dev dependencies** in `requirements-dev.txt` (`jsonschema`, `pytest`).

### Out of scope (each needs its own approval)

- Consent service and consent gate (Workstream A). Next proposed slice; touches consent logic (rule 10).
- Mobile capture, encryption and the offline queue. Touches voice recording (rule 10).
- openSMILE extraction pipeline (Workstream B). Requires authorised datasets documented first (rule 3).
- Any model training, evaluation or release path (rules 5 and 6).
- Per-agent command enforcement (finding 8).

### Acceptance criteria

- [ ] `bash scripts/validate-contracts.sh`, `check-secrets.sh` and `lint-check.sh` exit 0 on the repo.
- [ ] `pytest tests/` passes locally and in CI.
- [ ] Every invalid fixture is rejected for the expected reason, and every valid fixture passes.
- [ ] No fixture contains anything that resembles real participant data.
- [ ] No file under `workstream-a/` or `workstream-b/` is created.

## Consequences

### Positive
- The "records must pass validation" rule becomes something an agent or CI can actually run.
- Privacy invariants (no identifiers, consent reference required, not-a-diagnosis) are guarded by tests before any service exists.
- CI applies the checks outside Claude Code sessions.
- No sensitive path is touched, so the slice can be reviewed quickly.

### Negative
- It delivers no user-facing functionality. The consent service is still the first real feature.
- It adds a Python dependency to the repository root, ahead of the Workstream B Python project.
- Inference contract v1.1.0 is a structural change. It is safe now only because nothing consumes v1.0.0.

## Alternatives Considered

| Option | Why Not Chosen |
|--------|----------------|
| Start with a consent-service skeleton (Spring Boot) | It touches consent logic, so it needs a heavier review, and Maven/Gradle are not installed to build or test it locally. Better as v0.2, on top of the validator. |
| Start with an openSMILE extraction prototype | Rule 3 requires an authorised, documented dataset before any extraction, and none is documented yet. |
| Fix only the harness scripts, with no baseline | Leaves finding 5 open, so no future slice could meet its own acceptance criteria. |

## Review Triggers

- If the consent service (v0.2) needs consent states or fields not represented in `voice-feature-schema.json`.
- If Workstream B adopts a Python project layout that should own the validator and tests.

---

## Approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Responsible engineer | | | |
