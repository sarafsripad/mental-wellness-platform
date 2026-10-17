---
name: dataset-engineering
description: Build, version, and validate training and evaluation datasets for Workstream B, enforcing consent coverage and identity separation.
---

# Dataset Engineering Skill

## Purpose

Construct, version, and validate datasets for model training and evaluation. Ensure every record has authorised consent, no direct identifiers are present, and train/eval splits are reproducible and frozen.

## Prerequisites

- Read `docs/research/model-validation-spec.md` for dataset requirements.
- Confirm that every audio session in scope has a resolvable consent event with `MODEL_TRAINING` in `permitted_purposes`.
- Never include sessions with withdrawn consent in any dataset, including historical ones.

## Workflow

1. **Define the dataset spec.** Document in `docs/research/`: dataset name, version, purpose (training / evaluation / held-out), inclusion criteria, exclusion criteria, expected size, and consent basis.
2. **Query the feature store.** Select records by feature extraction version, language, quality status (`PASS` or `DEGRADED` with documented justification), and consent status.
3. **Enforce identity separation.** Strip all fields not required for training. The dataset must contain only: `session_id`, `language_tag`, `feature_set_id`, `extraction_version`, `audio_quality.status`, `features`, and optionally an opaque label provided by a clinician.
4. **Apply train/eval/held-out split.** Split by `session_id` hash to prevent leakage. Record the random seed and split ratios. Freeze the held-out set — it must never be used for training or hyperparameter tuning.
5. **Validate the dataset.** Run dataset integrity checks: row count, no null `session_id`, no direct identifiers, consent coverage 100%, class balance report.
6. **Version and publish.** Write the dataset to a versioned, immutable location. Record the manifest (file hash, row count, split sizes, extraction version, date) in `docs/research/`.
7. **Document dataset card.** Create a dataset card in `docs/research/` covering: purpose, collection context, language distribution, quality distribution, known limitations, and consent basis.

## Constraints

- Held-out evaluation set is frozen after first creation. Do not add, remove, or relabel records.
- Do not merge datasets with different consent bases without review.
- Do not include sessions from the production inference path in training data.
- Label columns (if any) must trace to a documented clinical review process — never auto-generated.
- Dataset must be reproducible from the manifest without access to the original audio.

## Expected Outputs

- Dataset spec document in `docs/research/`.
- Validated, versioned feature dataset (Parquet or CSV with manifest).
- Dataset card in `docs/research/`.
- Integrity check report.

## Acceptance Criteria

- [ ] Every record has a resolvable consent event with `MODEL_TRAINING` purpose.
- [ ] No direct identifiers present (automated check passes).
- [ ] Held-out split is frozen and documented.
- [ ] Integrity check report shows 0 consent violations, 0 null session IDs.
- [ ] Dataset card is complete with known limitations stated.
- [ ] Manifest hash matches the actual dataset file.
