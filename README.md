# Mental Wellness Platform

A privacy-first, multilingual platform for community mental wellness. It supports ASHA (Accredited Social Health Activist) workers in the field with voice-based wellness check-ins, and runs a separate research pipeline that studies acoustic voice features as candidate biomarkers.

> **Status:** Early stage. This repository holds the shared contracts, governance documents, architecture decisions and Claude Code development harness. Application and research source code (`workstream-a/`, `workstream-b/`) has not been written yet.

> **Clinical safety notice:** Acoustic voice features are biomarker *candidates*, not diagnoses. Any model output is a decision-support indicator that a qualified clinician must interpret. No model may be used clinically without documented validation and named human sign-off (see [docs/research/model-validation-spec.md](docs/research/model-validation-spec.md)).

---

## Overview

The platform has two workstreams that are deployed independently and connected only through versioned contracts ([ADR-001](docs/architecture/ADR-001-workstream-separation.md)). The full design is in [solution-architecture.md](docs/architecture/solution-architecture.md).

| | Workstream A: Application | Workstream B: Research |
|---|---|---|
| **Purpose** | Field screening support, consent, voice capture, clinician review | Voice AI research and model development |
| **Components** | React Native + TypeScript app (offline-first, English and Kannada); `wellness-api` Spring Boot modular monolith (Java 21); Python/openSMILE audio-processing worker; PostgreSQL; encrypted object storage | Export ingest, dataset engineering, experiments (scikit-learn, MLflow), evaluation |
| **Users** | ASHA workers, clinicians, programme admins | Researchers, ML engineers |
| **Data access** | Participant data, under consent checks | Consent-filtered, re-keyed feature records only, **never raw audio or identity** |

```
 Mobile app ──HTTPS/OIDC──▶ wellness-api (consent · voice-session · processing · research-export · audit)
                                 │  ▲                                   │
                     audio ──▶ object storage     internal API          │ consent-filtered export
                                 │  │ claim / audio / result            ▼
                                 ▼  └──── audio-processing-worker   research environment (Workstream B)
                             PostgreSQL         (Python + openSMILE)    datasets → experiments → evaluation
```

Feature extraction runs on the application side ([ADR-006](docs/architecture/architecture-decisions.md#adr-006-feature-extraction-runs-on-the-application-side)). Workstream B has no write path into Workstream A, and Workstream A doesn't depend on Workstream B at runtime.

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
  skills/<name>/SKILL.md # Domain and workflow skills (/skill-name)
  agents/                # Specialized subagents with bounded tools
contracts/
  openapi/               # OpenAPI 3.1: wellness API v1, internal worker API v1, provisional APIs v0
  voice-features/        # Feature-record schema v2.0.0, valid/invalid examples, versioning notes
  inference-contract.json, voice-feature-schema.json (v1, deprecated)
docs/
  architecture/          # Solution architecture, ADR log, ADR files
  implementation/        # Vertical-slice plan
  privacy/               # Data governance, consent and data lifecycle
  research/              # Model validation plan and spec, experiment log
scripts/                 # Deterministic, non-destructive validation scripts
requirements-dev.txt     # Pinned tooling for contract validation (not used at runtime)
workstream-a/            # Application source (planned)
workstream-b/            # Research source (planned)
```

---

## Shared Contracts

| Contract | Version | Purpose |
|---|---|---|
| [wellness-api.v1.yaml](contracts/openapi/wellness-api.v1.yaml) | 1.0.0-draft.1 | First-slice public API: consent, voice sessions, recordings, processing jobs |
| [processing-worker.internal.v1.yaml](contracts/openapi/processing-worker.internal.v1.yaml) | 1.0.0-draft.1 | Internal API between the backend and the openSMILE worker |
| [wellness-api-provisional.v0.yaml](contracts/openapi/wellness-api-provisional.v0.yaml) | 0.1.0-provisional | Outline only: cases, questionnaires, review, referrals, integration (blocked) |
| [feature-record.schema.json](contracts/voice-features/feature-record.schema.json) | 2.0.0 | Pseudonymous engineering feature record; no identifiers, audio, transcript or prediction ([details](contracts/voice-features/README.md)) |
| [inference-contract.json](contracts/inference-contract.json) | 1.0.0 | Future decision-support inference; needs revision before use |
| [voice-feature-schema.json](contracts/voice-feature-schema.json) | 1.0.0 | **Deprecated**, superseded by feature-record v2.0.0 |

A breaking schema change needs a version bump (in both `$id` and `version`) and a migration note. Use the `/api-contract-design` skill when changing a contract.

---

## Documentation

- [Solution architecture](docs/architecture/solution-architecture.md): context, components, data flows, deployment and cross-cutting concerns
- [Architecture decisions](docs/architecture/architecture-decisions.md): ADR-003 to ADR-012 and the ADR index
- [ADR-001: Workstream separation](docs/architecture/ADR-001-workstream-separation.md)
- [Consent and data lifecycle](docs/privacy/consent-and-data-lifecycle.md): consent scopes, withdrawal, retention and approvals needed (**draft, needs named approval**)
- [Data governance](docs/privacy/data-governance.md): data categories, identifier handling, access and audit rules
- [Model validation plan](docs/research/model-validation-plan.md) and [spec](docs/research/model-validation-spec.md): the research workflow and the thresholds a model must meet before any clinical use
- [Vertical-slice plan](docs/implementation/vertical-slice-plan.md): the first end-to-end engineering demonstration, using synthetic audio only

---

## Getting Started

### Prerequisites

- Git
- Python 3 (used by the validation scripts; `jq` works as a fallback for JSON syntax checks)
- For full contract validation: `python3 -m pip install -r requirements-dev.txt`
- Optional: `shellcheck`, and the linters for each workstream once code exists (ruff/black/flake8, ESLint, Checkstyle)
- [Claude Code](https://claude.com/claude-code), if you want to use the development harness

### Validation scripts

Run these from the repository root. They do not modify any files.

```bash
bash scripts/validate-contracts.sh   # Schema syntax, versioning and governance fields; runs the example checks when the dev requirements are installed
python3 scripts/validate-examples.py # Feature-record examples, OpenAPI structure, embedded API examples, policy checks
bash scripts/check-secrets.sh        # Scans uncommitted changes and untracked files for secret patterns
bash scripts/lint-check.sh           # Runs whichever linters are configured (advisory)
```

Run `validate-contracts.sh` and `validate-examples.py` before merging any change to `contracts/`.

---

## Claude Code Harness

The repository is set up for AI-assisted development with [Claude Code](https://claude.com/claude-code).

### Hooks

After every file write or edit, [.claude/settings.json](.claude/settings.json) runs `check-secrets.sh`, `validate-contracts.sh` and `lint-check.sh`. A secret or an invalid contract exits with code 2, which stops Claude Code and shows it the error. The same file blocks destructive commands such as force-push, `--no-verify` commits, `DROP TABLE` and namespace deletion.

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
