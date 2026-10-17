---
name: workstream-b
description: Plan or implement Workstream B audio and ML capabilities — openSMILE feature extraction, dataset engineering, model experimentation, evaluation, and MLOps.
---

# /workstream-b — Research Workstream

## What this skill does

Guides planning and implementation of Workstream B: the voice AI research pipeline, from raw audio to evaluated models.

## Usage

Invoke with `/workstream-b` followed by your request. Examples:

- `/workstream-b configure openSMILE for Hindi speech feature extraction`
- `/workstream-b build the dataset for the first training run`
- `/workstream-b run evaluation on candidate model v0.2`

## Workflow

When this skill is invoked:

1. **Classify the request.** Is it extraction config (→ `opensmile-processing`; note extraction runs in the application-side worker per ADR-006, so changes there are Workstream A releases), dataset work (→ `dataset-engineering`), model training (→ `ml-experimentation`), evaluation (→ `model-evaluation`), or deployment (→ `mlops-release`)?
2. **Confirm consent coverage.** Before any data operation, verify that the sessions in scope have appropriate consent for the intended purpose.
3. **Check existing pipeline code.** Read relevant `workstream-b/` source before modifying.
4. **Propose a plan.** For any dataset or model change, outline the approach and affected artefacts. Wait for confirmation before executing.
5. **Implement and validate.** Validate consumed and produced records against `contracts/voice-features/feature-record.schema.json`, and run `scripts/validate-contracts.sh` if contracts changed. Log experiment results to the experiment tracker.
6. **Do not auto-deploy.** Experiments and evaluations do not trigger production deployment. The `mlops-release` skill requires human approval at deployment.

## Safety Gates

- Do not use the held-out set for training or hyperparameter selection at any point.
- Do not run extraction on audio with `FAIL` quality status.
- Do not include sessions with withdrawn consent in any dataset.
- Do not present experiment results as clinical validation — that requires the `model-evaluation` skill on the held-out set.
- Do not retrain the production model automatically.

## Relevant Skills

- `/dataset-engineering` — for building or updating datasets
- `/opensmile-processing` — for extraction configuration
- `/ml-experimentation` — for running experiments
- `/model-evaluation` — for formal evaluation
- `/mlops-release` — for release management (human approval required)
