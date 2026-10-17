---
name: privacy-consent
description: Implement consent flows, data-governance controls, and privacy-preserving patterns for the Mental Wellness Platform.
---

# Privacy and Consent Skill

## Purpose

Implement and review informed-consent workflows, data-minimisation controls, and privacy-by-design patterns across both workstreams. Ensure compliance with applicable data-protection requirements.

## Prerequisites

- Read `docs/privacy/data-governance.md` before any implementation.
- Understand the consent event model: every feature record requires a `consent_event_id` reference.
- Know the permitted purposes: `RESEARCH_ONLY`, `CLINICAL_SUPPORT`, `MODEL_TRAINING`, `QUALITY_REVIEW`.

## Workflow

1. **Identify the data flow.** Where is audio or biographic data collected, stored, transmitted, and processed?
2. **Map consent requirements.** Which permitted purpose(s) apply? Is the participant an ASHA worker, a community member, or a research subject? Is a guardian consent required?
3. **Design the consent event.** A consent event must record: participant pseudonym, timestamp, language, purposes granted, version of consent form shown, and the capturing ASHA worker's ID.
4. **Implement the consent service hook.** Feature-capture pipelines must call the consent service before writing any audio or feature data. Reject with a clear error if no valid consent event is found.
5. **Implement data-minimisation.** Strip or never collect fields that are not required for the stated purpose. Log what is collected, not what is stripped.
6. **Implement pseudonymisation at the boundary.** Assign pseudonymous session IDs at the point of capture. Store the mapping key only in the identity service, never in feature or inference stores.
7. **Handle withdrawal.** Implement a withdrawal path that deletes or flags audio and features tied to a withdrawn consent event. Do not propagate withdrawn data to training pipelines.

## Constraints

- Consent must be captured in the participant's preferred language with verified back-translation.
- Do not use default opt-in for research or model training purposes.
- Audio files must be encrypted at rest and in transit.
- Consent event IDs must be immutable once issued.
- The mapping between pseudonymous ID and direct identifier must require authorised key access and must be logged.

## Expected Outputs

- Consent event schema addition or validation.
- Consent service implementation or stub.
- Data-flow diagram update in `docs/privacy/`.
- Test cases covering: consent granted, consent not found, consent withdrawn.

## Acceptance Criteria

- [ ] No feature record can be created without a resolvable `consent_event_id`.
- [ ] Pseudonymous IDs do not contain or encode any direct identifier.
- [ ] Withdrawal path exists and is tested.
- [ ] Consent form version is recorded alongside each consent event.
- [ ] Tests cover all consent states: granted, missing, withdrawn, expired.
