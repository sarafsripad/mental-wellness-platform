---
name: ml-experimentation
description: Design, run, and track ML experiments for Workstream B — feature selection, model architecture, training, and hyperparameter search.
---

# ML Experimentation Skill

## Purpose

Run reproducible, tracked experiments on voice feature datasets to develop models for the Mental Wellness Platform. All experiments must be recorded, reproducible, and kept strictly separate from production inference.

## Prerequisites

- Read `docs/research/model-validation-spec.md` for the approved task definitions and performance thresholds.
- Confirm the dataset version and manifest before loading data.
- Experiments must use only the training split — never the held-out set.

## Workflow

1. **Define the experiment.** Document the hypothesis: what are you testing and why? Record in the experiment log (`docs/research/experiment-log.md`) before running.
2. **Set up reproducible environment.** Pin all library versions. Set a fixed random seed. Record the environment in `workstream-b/experiments/<exp-id>/requirements.txt` and `environment.yml`.
3. **Load the training split only.** Load data using the dataset manifest. Assert that the held-out session IDs are not present in the training split before fitting any model.
4. **Define the feature set.** Document which openSMILE features are included/excluded and why. Do not include features derived from clinical labels.
5. **Train and evaluate.** Evaluate on the validation split (not held-out). Record: model architecture, hyperparameters, training curves, validation metrics.
6. **Log the experiment.** Use an experiment-tracking tool (e.g., MLflow). Log: dataset version, feature set, model config, all metrics, artefact paths.
7. **Interpret results honestly.** Report limitations, failure modes, and out-of-distribution behaviour. Do not cherry-pick metrics. If the model does not meet the threshold in `model-validation-spec.md`, document why.
8. **Propose next steps.** Based on results, propose one or two well-reasoned next experiments. Do not propose deploying a model that has not passed held-out evaluation.

## Constraints

- Experiments must not touch production data, production inference paths, or the held-out evaluation set.
- Model artefacts from experiments are not production models. They require separate evaluation (see `model-evaluation` skill) before any deployment consideration.
- Do not train on sessions with withdrawn consent — verify before every run.
- Experiment results must not be presented as validation evidence. Validation requires the held-out set and the `model-evaluation` skill.
- Never fabricate metrics or results. If a run failed, record the failure.

## Expected Outputs

- Experiment log entry in `docs/research/experiment-log.md`.
- Training script in `workstream-b/experiments/<exp-id>/`.
- MLflow (or equivalent) run record with all metrics and artefacts.
- Honest summary of findings and limitations.

## Acceptance Criteria

- [ ] Experiment log entry exists before results are reported.
- [ ] Held-out session IDs absent from training data (assertion in code).
- [ ] All library versions pinned and recorded.
- [ ] Metrics logged to experiment tracker.
- [ ] Limitations and failure modes documented — not omitted.
- [ ] No production paths touched.
