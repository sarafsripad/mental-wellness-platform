---
name: architecture-design
description: Design system components, record Architecture Decision Records (ADRs), and validate cross-stream boundaries for the Mental Wellness Platform.
---

# Architecture Design Skill

## Purpose

Design, document, and evolve the system architecture for both workstreams. Produce ADRs in `docs/architecture/` and update shared contracts when boundaries change.

## Prerequisites

- Read existing ADRs in `docs/architecture/` before proposing changes.
- Read `contracts/` to understand current API and feature boundaries.
- Read `CLAUDE.md` rules — no design may violate the separation-of-concerns or data-privacy rules.

## Workflow

1. **Understand the change driver.** Is this a new feature, a scaling problem, a compliance requirement, or a research milestone?
2. **Identify affected components.** Map the request to existing workstream-A or workstream-B services, or flag a new component.
3. **Evaluate options.** Present 2–3 options with trade-offs: simplicity, privacy, offline capability, research independence.
4. **Select and record the decision.** Write an ADR using the template in `docs/architecture/ADR-000-template.md`. Number sequentially.
5. **Update contracts if boundaries change.** Any change to cross-workstream data shape must update `contracts/` and bump the schema version.
6. **Flag irreversible decisions.** If the design requires a data migration, infrastructure teardown, or model-breaking change, explicitly call this out and request human approval before proceeding.

## Constraints

- Workstream B (research) must never have a write path into production patient data.
- Pseudonymisation must be enforced at the collection boundary, not deferred to processing.
- Offline-first capability is required for Workstream A field use — any design that assumes connectivity must be explicitly justified.
- Do not propose designs that combine direct identifiers with derived features in a single store.

## Expected Outputs

- One numbered ADR Markdown file per decision.
- Updated `contracts/` JSON Schema files if the decision changes shared data shapes.
- A summary list of open questions requiring product or clinical input.

## Acceptance Criteria

- [ ] ADR contains context, decision, consequences, and alternatives considered.
- [ ] All affected contracts are updated and versioned.
- [ ] No non-negotiable rule in `CLAUDE.md` is violated.
- [ ] Any irreversible consequence is explicitly documented and flagged for human review.
