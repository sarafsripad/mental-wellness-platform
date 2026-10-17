---
name: mlops-release
description: Manage the model release pipeline — packaging, staging, integration testing, human approval gates, and production deployment — with strict governance controls.
---

# MLOps Release Skill

## Purpose

Package evaluated models, manage staging and production environments, enforce human approval gates, and document the release provenance. No model may be deployed to production without explicit human sign-off.

## Prerequisites

- The candidate model must have a completed evaluation report with `READY_FOR_HUMAN_REVIEW` status (see `model-evaluation` skill).
- Read the current production model version and its evaluation report before proposing a replacement.
- Human approval must be obtained before executing any production deployment step.

## Workflow

1. **Package the model.** Create a versioned model package: model artefact, feature schema version it expects, inference contract version, evaluation report reference, and a `model-card.md` documenting task, performance, limitations, and intended use.
2. **Deploy to staging.** Deploy the packaged model to the staging inference service. Run integration tests: send sample feature records, verify response conforms to `contracts/inference-contract.json`, verify uncertainty outputs are present.
3. **Run canary validation.** On staging, run the held-out evaluation again using the deployed endpoint (not the local model file) to confirm the serving wrapper does not alter outputs.
4. **Request human approval.** Produce an approval request document containing: model version, evaluation report link, staging test results, performance delta vs. current production model, known limitations, intended use scope. **Pause and wait for explicit human sign-off.** Do not proceed to production without it.
5. **Deploy to production (after approval only).** Execute the production deployment. Record the deployer identity, timestamp, and approval reference in the release log.
6. **Post-deployment monitoring.** Configure: inference latency alert, input distribution drift alert, output distribution drift alert. Document alert thresholds in `docs/research/`.
7. **Document the release.** Write a release note in `docs/research/releases/` with: model version, deployment date, evaluation report reference, approval reference, monitoring configuration.

## Constraints

- **No automatic deployment.** Production deployment requires explicit, documented human approval. This skill must pause at step 4 and not continue without confirmation.
- Do not deploy a model that has not completed the `model-evaluation` skill with `READY_FOR_HUMAN_REVIEW`.
- Do not retrain the production model automatically from new field recordings. Retraining requires a new experiment cycle.
- The production inference service must never have write access to training data stores.
- A rollback procedure must be documented and tested before a new model is promoted to production.

## Expected Outputs

- Versioned model package with model card.
- Staging integration test report.
- Approval request document.
- Production release note in `docs/research/releases/`.
- Monitoring alert configuration.

## Acceptance Criteria

- [ ] Model card present and complete.
- [ ] Staging integration tests pass.
- [ ] Canary validation matches evaluation report metrics (within tolerance).
- [ ] Human approval documented before production deployment.
- [ ] Release note written with evaluation and approval references.
- [ ] Monitoring alerts configured.
- [ ] Rollback procedure documented.
