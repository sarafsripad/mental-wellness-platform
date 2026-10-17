# ADR-001: Workstream A and B Are Independently Deployable with a Defined Contract Boundary

**Status:** ACCEPTED
**Date:** 2026-10-17
**Workstream:** BOTH
**Author:** solution-architect

---

## Context

The platform combines a production application (Workstream A) that captures voice data from real participants with a research pipeline (Workstream B) that trains and evaluates models on that data. Without a clear boundary:

- Research code could inadvertently write to or read from live participant data paths.
- A research dependency failure could take down the production application.
- Regulatory and consent requirements differ: participant data collected under `CLINICAL_SUPPORT` consent cannot be used for model training.
- The research pipeline may require infrastructure that is unsuitable for a production application (GPUs, large storage, long-running batch jobs).

The principal risk is that the two workstreams become coupled in a way that violates consent boundaries or creates a data path from field recordings directly to model retraining without human review.

## Decision

Workstream A (application) and Workstream B (research) are independently deployable systems. They communicate only through a versioned, schema-validated contract boundary defined in `contracts/`. Workstream B has no write path to any Workstream A data store. Workstream A has no dependency on Workstream B for its runtime operation.

## Consequences

### Positive
- Consent purposes can be enforced at the boundary — only sessions with appropriate purposes reach the research pipeline.
- Workstream B failures, deployments, or breaking changes cannot affect the production application.
- Each workstream can evolve its internal technology choices independently.
- The contract boundary is auditable — all cross-stream data passes through `contracts/voice-feature-schema.json`.

### Negative
- Cross-stream features (e.g., serving model inference inside the application) require a well-defined API and cannot be a direct function call.
- Contract evolution requires coordination and versioned migration rather than a simple refactor.
- Duplicated infrastructure (CI/CD, logging, monitoring) per workstream.

## Alternatives Considered

| Option | Why Not Chosen |
|--------|----------------|
| Monorepo with shared runtime | Couples research and production deployments. A research dependency update can break production. |
| Shared database between workstreams | Consent-boundary enforcement becomes the responsibility of every query rather than being enforced at ingestion. High risk of consent violation. |
| Research pipeline directly reads production DB | No consent filtering layer. Research code has write adjacency to production data. Regulatory risk. |

## Implementation Notes

- The feature routing service in Workstream A is responsible for applying consent filtering before forwarding feature records to Workstream B.
- The boundary is the ingestion endpoint in Workstream B — it accepts only `voice-feature-schema.json`-compliant records.
- Workstream B must never be given database credentials, network access, or service accounts with write access to Workstream A stores.

## Amendment (2026-10-17)

The feature contract moved to `contracts/voice-features/feature-record.schema.json` v2.0.0; v1 (`contracts/voice-feature-schema.json`) is deprecated (ADR-009). Feature extraction runs in the application-side audio-processing worker (ADR-006), and Workstream B receives records only through the consent-filtered research export, not a Workstream B ingestion endpoint that Workstream A pushes to. The "feature routing service" above is now the `research-export` module of the wellness-api modular monolith (ADR-004). The decision itself is unchanged. See [architecture-decisions.md](architecture-decisions.md).

## Review Triggers

- If a new feature requires real-time model inference within the application (may need an inference API layer).
- If the consent model changes to allow purpose re-use.
- If regulatory requirements change the required data residency or separation rules.
