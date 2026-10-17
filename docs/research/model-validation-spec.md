# Model Validation Specification

**Version:** 1.0.0
**Date:** 2026-10-17
**Status:** DRAFT — Requires clinical and regulatory review before production use

---

## Purpose

This document defines the validation requirements that a model must meet before it may be considered for any clinical use on the Mental Wellness Platform. It is referenced by the `model-evaluation` skill and the `mlops-release` skill.

No model may be deployed for clinical support use without satisfying all requirements in this specification.

---

## Fundamental Constraints

These constraints apply before any quantitative threshold is evaluated:

1. **No autonomous diagnosis.** Models produce decision-support indicators only. A qualified clinician must review and interpret all model outputs before any clinical action.
2. **Prospective validation required for clinical use.** Internal held-out evaluation is necessary but not sufficient for clinical use. A prospective clinical validation study with appropriate ethics approval is required before clinical deployment.
3. **Subgroup analysis is mandatory.** Performance must be reported and acceptable across all supported languages and capture device classes.
4. **Honest limitations.** Limitations, failure modes, and out-of-distribution behaviour must be documented. A model with undocumented limitations cannot be approved.

---

## Supported Tasks

### WELLNESS_SCREENING

An acoustic indicator task that provides a decision-support signal for clinician review of potential wellness concerns. Not a diagnostic tool.

**Input:** Voice feature record conforming to `contracts/voice-features/feature-record.schema.json` (v2.0.0) with `quality.status` of `PASS` or `DEGRADED` (with justification). The research workflow that produces evaluation evidence is in [model-validation-plan.md](model-validation-plan.md).

**Minimum performance thresholds (held-out set):**

| Metric | Minimum Threshold | Notes |
|--------|-------------------|-------|
| Balanced accuracy | ≥ 0.70 | Averaged across classes |
| Sensitivity (recall for concern class) | ≥ 0.75 | Must not miss too many at-risk participants |
| Specificity | ≥ 0.65 | Must not over-refer |
| AUC-ROC | ≥ 0.75 | Overall discriminative ability |
| Calibration ECE | ≤ 0.15 | Required if confidence scores are surfaced |

**Subgroup requirements:** Thresholds above must be met independently for each supported language tag. A model that meets overall thresholds but fails for a specific language is NOT approved for that language.

**Note:** These thresholds are initial engineering targets. Final thresholds must be reviewed and confirmed by a qualified clinical advisor before production deployment.

### SPEECH_QUALITY_ASSESSMENT

Assesses recording quality and flags recordings unsuitable for further analysis.

**Minimum performance thresholds:**

| Metric | Minimum Threshold |
|--------|-------------------|
| Quality classification accuracy | ≥ 0.85 |
| False negative rate (passing poor recordings) | ≤ 0.10 |

### LANGUAGE_IDENTIFICATION

Identifies the spoken language from audio features to support routing and feature-set selection.

**Minimum performance thresholds:**

| Metric | Minimum Threshold |
|--------|-------------------|
| Top-1 accuracy | ≥ 0.90 |

---

## Evaluation Dataset Requirements

- Must use the frozen held-out split created by the `dataset-engineering` skill.
- The held-out set must not have been used for any training, validation, or hyperparameter tuning.
- The held-out set manifest hash must be verified before evaluation begins.
- Minimum held-out set size: 200 sessions per language per class (subject to revision).

---

## Evaluation Report Requirements

Every evaluation report must include:

1. Model version and artefact hash.
2. Dataset version, manifest hash, and held-out set session count.
3. All metrics in this specification (not a subset).
4. Subgroup breakdown by language and capture device class.
5. Confusion matrix.
6. Calibration curve (if confidence scores are produced).
7. Known failure modes and out-of-distribution behaviour.
8. A clearly stated limitations section.
9. A `result_is_not_a_diagnosis: true` attestation.
10. A readiness recommendation: `READY_FOR_HUMAN_REVIEW` or `NOT_READY` with reasons.

---

## Approval Gate

A model reaching `READY_FOR_HUMAN_REVIEW` must be reviewed and signed off by:

- [ ] A named responsible engineer (confirming technical evaluation is complete).
- [ ] A named clinical advisor (confirming clinical suitability for the stated purpose).

For production deployment, approval must be documented in the release note in `docs/research/releases/`.

---

## Open Items

- [ ] Clinical advisor appointment and sign-off process to be established.
- [ ] Ethics review requirement for prospective validation study.
- [ ] Regulatory pathway (if any) to be assessed.
- [ ] Threshold review with clinical evidence once initial data is available.
- [ ] Bias and fairness assessment criteria to be added once demographic data policy is established.
