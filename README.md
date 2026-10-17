# Mental Wellness Platform

A privacy-first, multilingual platform for community mental wellness. It supports ASHA (Accredited Social Health Activist) workers in the field with voice-based wellness check-ins, and runs a separate research pipeline that studies acoustic voice features as candidate biomarkers.

> **Status:** Early stage. This repository holds the shared contracts, governance documents, architecture decisions and Claude Code development harness. Application and research source code (`workstream-a/`, `workstream-b/`) has not been written yet.

> **Clinical safety notice:** Acoustic voice features are biomarker *candidates*, not diagnoses. Any model output is a decision-support indicator that a qualified clinician must interpret. No model may be used clinically without documented validation and named human sign-off (see [docs/research/model-validation-spec.md](docs/research/model-validation-spec.md)).

---

## Overview

The platform has two workstreams that are deployed independently and connected only through versioned contracts ([ADR-001](docs/architecture/ADR-001-workstream-separation.md)).

| | Workstream A: Application | Workstream B: Research |
|---|---|---|
| **Purpose** | Field data capture and clinician review | Voice AI research and model development |
| **Components** | React Native mobile app (offline-first, multilingual), Spring Boot microservices (consent, session ingestion, feature routing, clinician review API), clinician review dashboard | openSMILE feature extraction, dataset engineering, model experimentation, evaluation, MLOps |
| **Users** | ASHA workers, clinicians | Researchers, ML engineers |
| **Data access** | Live participant data | Consent-filtered, pseudonymous feature records only |

```
 Mobile app (ASHA worker)            Workstream A backend                 Workstream B
 ─────────────────────────           ────────────────────                 ────────────
 consent capture  ───────────────▶   consent service
 voice capture (offline queue) ──▶   session ingestion
                                     feature routing ── consent filter ──▶ ingestion (voice-feature-schema.json)
                                                                           datasets → experiments → evaluation
 clinician dashboard  ◀────────────  clinician review API ◀── inference-contract.json ── approved models only
```

Workstream B has no write path into any Workstream A data store, and Workstream A does not depend on Workstream B at runtime.

---

## Core Principles

These rules apply to all contributors, human or AI. The full list is in [CLAUDE.md](CLAUDE.md).

- **Features are not diagnoses.** Never present acoustic measurements as clinical outcomes.
- **No fabricated evidence.** Clinical labels, results and validation claims must come from documented evaluation runs.
- **Authorized data only.** Record provenance, license and consent basis in `contracts/` before any feature extraction.
- **Research is kept apart from production.** Training and production inference never share live patient data paths.
- **No automatic retraining or deployment.** New recordings never trigger model updates without human review.
- **Pseudonymous IDs only.** Names, phone numbers and locations never appear in feature records; use `sess_<32 hex>` session IDs.
- **Consent is recorded, not assumed.** Every feature record references a consent event ID (`ce_<32 hex>`) that exists in the consent service.
- **Sensitive changes need review.** Changes touching voice recording, consent logic or model release are never auto-merged.

---

## Repository Layout

```
.claude/
  settings.json          # Hooks and permission deny-list
  skills/                # Domain and workflow skills (/skill-name)
  agents/                # Specialized subagents with bounded tools
contracts/               # Versioned, schema-validated cross-workstream contracts
docs/
  architecture/          # Architecture Decision Records (ADRs)
  privacy/               # Data governance and consent requirements
  research/              # Model validation and dataset specifications
scripts/                 # Deterministic, non-destructive validation scripts
workstream-a/            # Application source (planned)
workstream-b/            # Research source (planned)
```

---

## Shared Contracts

All cross-workstream data must conform to the JSON Schemas (draft 2020-12) in [contracts/](contracts/).

| Contract | Version | Purpose |
|---|---|---|
| [voice-feature-schema.json](contracts/voice-feature-schema.json) | 1.0.0 | Pseudonymous acoustic feature record: session ID, BCP-47 language tag, openSMILE feature set, audio quality, consent reference and provenance |
| [inference-contract.json](contracts/inference-contract.json) | 1.0.0 | Request and response format for decision-support inference |

A breaking schema change needs a version bump (in both `$id` and `version`) and a migration note. Use the `/api-contract-design` skill when changing a contract.

---

## Documentation

- [ADR-001: Workstream separation](docs/architecture/ADR-001-workstream-separation.md): why A and B are deployed independently
- [ADR template](docs/architecture/ADR-000-template.md): use this for new decisions
- [Data governance](docs/privacy/data-governance.md): data categories, identifier handling, encryption and retention rules
- [Model validation spec](docs/research/model-validation-spec.md): the requirements a model must meet before clinical use (draft, pending clinical and regulatory review)

---

## Getting Started

### Prerequisites

- Git
- Python 3 (used by the contract validation and lint scripts; `jq` works as a fallback for JSON syntax checks)
- Optional: `shellcheck`, and the linters for each workstream once code exists (ruff/black/flake8, ESLint, Checkstyle)
- [Claude Code](https://claude.com/claude-code), if you want to use the development harness

### Validation scripts

Run these from the repository root. They do not modify any files.

```bash
bash scripts/validate-contracts.sh   # JSON syntax, versioning and governance fields in contracts/
bash scripts/check-secrets.sh        # Scans uncommitted changes for secret patterns
bash scripts/lint-check.sh           # Runs whichever linters are configured (advisory)
```

Run `validate-contracts.sh` before merging any change to `contracts/`.

---

## Claude Code Harness

The repository is set up for AI-assisted development with [Claude Code](https://claude.com/claude-code).

### Hooks

After every file write or edit, [.claude/settings.json](.claude/settings.json) runs the three validation scripts above. The same file blocks destructive commands such as force-push, `--no-verify` commits, `DROP TABLE` and namespace deletion.

### Skills

Invoke a skill with `/skill-name`.

| Area | Skills |
|---|---|
| Workflow | `/workstream-a`, `/workstream-b`, `/integrate`, `/review-safety`, `/testing-and-quality` |
| Design | `/architecture-design`, `/api-contract-design`, `/privacy-consent` |
| Application | `/mobile-development`, `/offline-sync`, `/springboot-microservices`, `/voice-capture-integration` |
| Research | `/opensmile-processing`, `/dataset-engineering`, `/ml-experimentation`, `/model-evaluation`, `/mlops-release` |

### Agents

| Agent | Responsibility |
|---|---|
| `solution-architect` | System design, ADRs, cross-stream contracts |
| `backend-engineer` | Spring Boot services, APIs, data models |
| `mobile-engineer` | React Native app, offline sync, UI |
| `audio-engineer` | Voice capture, openSMILE, signal quality |
| `ml-researcher` | Experiments, datasets, model training (cannot deploy) |
| `test-engineer` | Test coverage, CI, regression |
| `security-reviewer` | Threat review, consent audit, secret scan (read-only) |

No agent can approve a clinical model release on its own.

---

## Contributing

1. Read [CLAUDE.md](CLAUDE.md) and [docs/privacy/data-governance.md](docs/privacy/data-governance.md) before starting.
2. Create a feature branch from `main`.
3. Record significant design decisions as an ADR in `docs/architecture/`.
4. Run the validation scripts before opening a pull request.
5. Request an explicit safety review (`/review-safety`, or the `security-reviewer` agent) for any change that touches voice recording, consent logic or model release. These changes must not be auto-merged.
