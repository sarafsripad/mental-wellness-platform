---
name: workstream-a
description: Plan or implement Workstream A application features — ASHA worker mobile app, consent flows, Spring Boot microservices, and clinician review dashboard.
---

# /workstream-a — Application Workstream

## What this skill does

Guides planning and implementation of Workstream A: the multilingual mental wellness application serving ASHA workers and clinicians.

## Usage

Invoke with `/workstream-a` followed by your request. Examples:

- `/workstream-a design the consent capture flow for Tamil-speaking participants`
- `/workstream-a implement the session ingestion API endpoint`
- `/workstream-a add offline queue retry logic to the mobile app`

## Workflow

When this skill is invoked:

1. **Classify the request.** Is it design (→ use `architecture-design` or `api-contract-design`), mobile (→ use `mobile-development` or `offline-sync`), backend (→ use `springboot-microservices`), consent/privacy (→ use `privacy-consent`), or cross-cutting?
2. **Check contracts.** Read `contracts/` to understand current API boundaries before proposing any new data shape.
3. **Check existing source.** Read the relevant `workstream-a/` source files before modifying anything.
4. **Propose a plan.** Before writing code, outline the approach, affected files, and any contract or schema changes required. Wait for confirmation on non-trivial changes.
5. **Implement incrementally.** Make small, testable changes. Write tests alongside implementation.
6. **Validate.** Run `scripts/validate-contracts.sh` if contracts changed. Run available tests.
7. **Do not deploy.** This skill does not trigger deployment pipelines. Infrastructure changes require separate human approval.

## Safety Gates

- Any change to consent logic requires the `privacy-consent` skill and explicit review.
- Any new API endpoint that handles participant data requires a consent gate — do not omit it.
- Do not modify the production database schema without a reviewed Flyway migration script.
- Do not commit secrets, real participant data, or real audio to the repository.

## Relevant Skills

- `/architecture-design` — for structural decisions
- `/api-contract-design` — for schema changes
- `/privacy-consent` — for consent and data-governance features
- `/testing-and-quality` — for test strategy
