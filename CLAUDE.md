# Mental Wellness Platform — Claude Code Harness

A privacy-first, multilingual platform for community mental wellness with two interoperable workstreams.

## Workstream A — Application

Multilingual mental wellness application targeting ASHA worker workflows. Capabilities: offline-first voice capture, informed-consent management, Spring Boot microservices backend, React Native mobile client, and clinician review dashboard.

## Workstream B — Research

Voice AI research pipeline. Capabilities: openSMILE acoustic feature extraction, dataset engineering, proprietary model development, evaluation, and MLOps.

---

## Non-Negotiable Engineering Rules

These rules apply in every context, to every agent, at every stage.

1. **Voice features are not diagnoses.** Acoustic features are biomarker candidates, not clinical outcomes. Never present them as diagnostic results.
2. **Never fabricate clinical labels, results, or validation evidence.** If evidence does not exist in documented evaluation runs, say so.
3. **Use only authorized datasets.** Document provenance, license, and consent basis in `contracts/` before any feature extraction.
4. **Keep research separate from production.** Training pipelines and production inference services must be independently deployable and never share live patient data paths.
5. **No automatic retraining or deployment.** New recordings must not trigger retraining or model updates without explicit human review and approval.
6. **Human approval required for clinical use.** Any model intended for clinical decision support requires documented evaluation (`docs/research/`) and sign-off from a named responsible person.
7. **Do not fabricate system access.** Never pretend to access hospital EMR, government health, or third-party systems unless explicitly integrated and documented.
8. **Direct identifiers stay separate.** PII (name, phone, location) must never appear in feature records. Use pseudonymous session IDs only. See `contracts/voice-features/feature-record.schema.json`.
9. **Consent must be recorded, not assumed.** Every feature record must reference a consent event ID that exists in the consent service.
10. **Flag sensitive changes for review.** Any edit touching voice recording, consent logic, or model-release paths must pause for explicit approval. Do not auto-merge.

---

## Repository Layout

```
.claude/
  settings.json          # Hooks, permissions
  skills/<name>/SKILL.md # Domain and workflow skills (invoke with /skill-name)
  agents/                # Specialized subagents
contracts/
  openapi/               # OpenAPI 3.1 contracts (wellness API, internal worker API, provisional APIs)
  voice-features/        # Feature-record schema v2.0.0, examples, versioning notes
  *.json                 # inference contract; deprecated v1 feature schema
docs/
  architecture/          # Solution architecture, ADR log, ADR files
  implementation/        # Vertical-slice plan
  privacy/               # Data governance, consent and data lifecycle
  research/              # Model validation plan and spec, experiment log
scripts/                 # Deterministic validation scripts (non-destructive)
workstream-a/            # Application source (created during implementation)
workstream-b/            # Research source (created during implementation)
```

---

## Skills

Invoke with `/skill-name` in the prompt. Each skill is `.claude/skills/<name>/SKILL.md` (Claude Code only loads this layout).

| Slash command         | Purpose                                              |
|-----------------------|------------------------------------------------------|
| `/workstream-a`       | Plan or implement application features               |
| `/workstream-b`       | Plan or implement audio and ML capabilities          |
| `/integrate`          | Validate contract and end-to-end flow between streams|
| `/review-safety`      | Review privacy, security, and clinical-safety impact |
| `/architecture-design`| Design system components and record ADRs             |
| `/api-contract-design`| Design or evolve shared contracts                    |
| `/privacy-consent`    | Implement consent and data-governance features       |
| `/testing-and-quality`| Write and run tests, review coverage                 |

---

## Agents

Specialized subagents are in `.claude/agents/`. Each has bounded tools and must request approval for irreversible or high-risk actions. No agent may independently approve a clinical model release.

| Agent               | Responsibility                              |
|---------------------|---------------------------------------------|
| solution-architect  | System design, ADRs, cross-stream contracts |
| backend-engineer    | Spring Boot services, APIs, data models     |
| mobile-engineer     | React Native app, offline sync, UI          |
| audio-engineer      | Voice capture, openSMILE, signal quality    |
| ml-researcher       | Experiments, datasets, model training       |
| test-engineer       | Test coverage, CI, regression               |
| security-reviewer   | Threat review, consent audit, secret scan   |

---

## Hooks

Hooks run lightweight, non-destructive checks on every edit. See `.claude/settings.json`. Scripts are in `scripts/` and exit 0 when nothing is actionable.

---

## Shared Contracts

Cross-workstream data must conform to schemas in `contracts/`. Validate with `scripts/validate-contracts.sh` and `scripts/validate-examples.py` (needs `requirements-dev.txt`) before merging. Breaking schema changes require a version bump and migration note.

Architecture decisions to respect: [solution-architecture.md](docs/architecture/solution-architecture.md) and [architecture-decisions.md](docs/architecture/architecture-decisions.md). In particular, openSMILE extraction runs in the application-side worker and Workstream B never receives raw audio (ADR-006); the backend is a modular monolith (ADR-004).
