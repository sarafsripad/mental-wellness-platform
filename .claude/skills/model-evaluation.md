---
name: model-evaluation
description: Run formal evaluation of candidate models on the held-out set, produce evaluation reports, and determine readiness for human review before any clinical use consideration.
---

# Model Evaluation Skill

## Purpose

Conduct rigorous, reproducible evaluation of candidate models using the frozen held-out set. Produce an evaluation report that a human reviewer can use to make an informed deployment decision. No model may be considered for clinical use without passing this process.

## Prerequisites

- Read `docs/research/model-validation-spec.md` for required tasks, thresholds, and reporting format.
- Have the candidate model artefact and its experiment log entry.
- The held-out set must be unfrozen only for this skill — assert it has not been used in training.

## Workflow

1. **Confirm the evaluation plan.** Define: task (what the model predicts), input feature set, held-out dataset version, metrics to report, acceptance thresholds.
2. **Load the held-out set.** Load using the frozen manifest. Verify file hash matches the recorded manifest hash. Assert no overlap with training set session IDs.
3. **Run inference.** Run the candidate model on the held-out set without fine-tuning. Record all predictions and confidence scores.
4. **Compute metrics.** At minimum: accuracy, F1 per class, AUC if applicable, calibration curve, confusion matrix. For mental wellness tasks: sensitivity and specificity at clinically relevant operating points.
5. **Analyse failure modes.** Identify: which subgroups (language, quality level, capture device) the model underperforms on. Document all subgroup gaps.
6. **Evaluate uncertainty.** Report calibration quality. If the model is poorly calibrated, document this as a limitation — do not hide it.
7. **Write the evaluation report.** Report goes in `docs/research/evaluations/<model-id>/`. Must include: task definition, dataset version, all computed metrics, subgroup analysis, known limitations, and a clear statement that results represent decision-support indicators only — not clinical diagnoses.
8. **Produce a readiness summary.** State clearly: does the model meet the thresholds in `model-validation-spec.md`? If yes, flag for human review. If no, do not proceed.

## Constraints

- Held-out set must not be used for any training, fine-tuning, or hyperparameter selection.
- Do not present evaluation results as clinical validation — that requires a separate prospective clinical study.
- Do not pass a model for human review if it fails any threshold in `model-validation-spec.md`.
- Subgroup analysis is mandatory, not optional.
- The evaluation report must state limitations explicitly — omitting them is not acceptable.

## Expected Outputs

- Evaluation script in `workstream-b/evaluations/<model-id>/`.
- Evaluation report in `docs/research/evaluations/<model-id>/report.md`.
- Metrics artefacts (confusion matrix, calibration plot) in the same directory.
- A readiness flag: `READY_FOR_HUMAN_REVIEW` or `NOT_READY` with reasons.

## Acceptance Criteria

- [ ] Held-out set hash verified before loading.
- [ ] No overlap between held-out and training session IDs.
- [ ] All required metrics computed and reported.
- [ ] Subgroup analysis present.
- [ ] Limitations section is non-empty and honest.
- [ ] Report contains explicit statement: results are not clinical diagnoses.
- [ ] Readiness flag is present and justified.
