# Model Validation Plan

**Version:** 0.1.0
**Date:** 2026-10-17
**Status:** DRAFT — research workflow for the future. **No model has been trained, and no performance results exist.**
**Companion:** [model-validation-spec.md](model-validation-spec.md) sets the minimum thresholds and evaluation report format. This plan sets out the process that produces the evidence, and the spec decides whether that evidence is enough.
**Related:** [experiment-log.md](experiment-log.md), [ADR-012](../architecture/architecture-decisions.md#adr-012-experimental-model-deployment-and-release-approval), [consent-and-data-lifecycle.md](../privacy/consent-and-data-lifecycle.md)

---

## 0. Ground rules

1. **No diagnosis.** Models built under this plan are research tools and, at most, future decision-support indicators. They never diagnose depression, bipolar disorder, dementia or any other condition on their own.
2. **Synthetic data tests software, not clinical performance.** Synthetic or test audio (`data_origin: SYNTHETIC | AUTHORIZED_TEST_AUDIO`) may be used to test pipelines, schemas and code. It can never support any claim about how well a model detects a condition, and results on it must not be reported as model performance.
3. **No fabricated evidence.** Every number in a report must come from a recorded run in the experiment log and MLflow. A run that failed is recorded as failed.
4. **No automatic retraining or deployment** (rule 5). New recordings never trigger training. Each dataset build and training run is started by a person and logged.
5. **Research stays separate from production** (ADR-001). The research environment reads only consent-filtered, re-keyed feature exports. It has no network route to application databases or audio.

---

## 1. Dataset eligibility and licensing review

Before any data is used:
- **Source:** either application exports (consent scopes `RESEARCH_FEATURE_REUSE` and, for training, `MODEL_TRAINING`), or an external dataset.
- **External datasets:** record the provenance, licence, permitted uses (including whether commercial or derived models are allowed), consent basis, demographics and recording conditions in a dataset card under `docs/research/datasets/`. The licence must allow this use, and the consent basis must cover it (rule 3).
- **Ethics:** confirm that ethics approval covers the intended analysis (consent-and-data-lifecycle.md §14, item 7).
- **Language fit:** Kannada and English coverage. Datasets in other languages or populations are labelled as such and can't be used to make claims about the target population.
- **Outcome:** each dataset card ends with ELIGIBLE or NOT_ELIGIBLE, and the reviewer's name.

## 2. Label provenance and clinical annotation

- Labels come only from a documented clinical process: a validated instrument administered by trained staff, or a structured clinical interview by a qualified clinician. The process is recorded in the dataset card.
- Each label records the instrument and version, the administration language, the annotator role, the date relative to the recording, and the blinding (the annotator mustn't see acoustic features).
- **Inter-rater agreement** is measured on a subset where possible, and labels with low agreement are reported.
- Labels are **never** generated from model outputs, acoustic thresholds or heuristics (dataset-engineering skill).
- Labels live only in the research environment. They never appear in feature records (schema-enforced).

## 3. Audio quality and reproducible feature extraction

- Features come from the application-side worker (ADR-006) under a named pipeline profile, for example `EGEMAPS_V02_FUNCTIONALS_V1`. Each record carries the tool version, config ID and version, config SHA-256 and pipeline version.
- Only records with `quality.status` `PASS` are used, or `DEGRADED` with the reason documented. The proportion of each is reported by language and device class.
- **Determinism test:** re-extracting a fixed synthetic file gives identical values (CI test in the worker).
- A dataset never mixes pipeline profiles or config versions unless it is explicitly marked as mixed and analysed by profile.

## 4. Participant-level splits

- Splits are by **participant**, not by recording or session, so no participant is in more than one split.
- Proposed ratio is 70 / 15 / 15 (train / validation / test), stratified by language and label where size allows. The seed and ratios are recorded in the dataset manifest.
- The **test (held-out) set is frozen** when it is created. Its manifest hash is recorded, and it is used once per candidate model for final evaluation only (model-evaluation skill).
- The minimum held-out size per language and class is in [model-validation-spec.md](model-validation-spec.md).

## 5. Leakage prevention, demographics and language variation

- **Leakage checks** (automated, and they fail the build):
  - no participant overlap between splits
  - no duplicate audio hashes across splits
  - feature normalisation fitted on training data only
  - no feature derived from labels
- **Recording-context confounds:** device class, recording site, ASHA worker and time of day can predict labels on their own. Report their distribution by label, and run a confound baseline (a model trained only on context variables).
- **Language:** Kannada and English are reported separately. A model is never approved for a language it wasn't evaluated on.
- **Demographics:** age band and gender are used only if consented and approved for research. They are for subgroup evaluation, not as model inputs, until a fairness policy exists (spec, open item).

## 6. Baseline experiments

The order is set to prevent over-claiming:
1. **Trivial baselines:** majority class, and the confound-only model (§5).
2. **Linear baseline:** regularised logistic regression on eGeMAPS functionals.
3. **Simple non-linear baseline:** gradient boosting or random forest (scikit-learn).
4. Only if those are documented: more complex models, with a justification.

Each run is logged in [experiment-log.md](experiment-log.md) **before** it starts, and in MLflow with the dataset manifest, feature profile, hyperparameters, seed and environment. Model selection uses the validation split only.

## 7. Metrics for screening

| Metric | Why |
|---|---|
| Sensitivity and specificity at a pre-registered operating point | A screening tool has to say how many at-risk people it misses and how many it over-refers |
| PPV and NPV at the expected prevalence | Real-world usefulness depends on prevalence. The prevalence assumption must be stated |
| AUROC and AUPRC | Performance across thresholds. AUPRC matters when classes are imbalanced |
| Calibration (reliability curve, ECE, Brier score) | Needed whenever a confidence value is shown |
| 95% confidence intervals (bootstrap, participant-level) | Small datasets give unstable estimates |
| Uncertainty: abstention or low-confidence rate | The system should be able to say "insufficient evidence" (`LOW_CONFIDENCE` in the inference contract) |

The operating point and primary metric are **pre-registered** in the experiment log before the held-out evaluation.

## 8. Subgroup evaluation and limitations

- Every metric in §7 is reported for each language, device class and quality status, and for age band and gender where approved.
- Thresholds in the spec must be met **in each subgroup** for the model to be approved for that subgroup.
- The limitations section is mandatory. It covers population and setting, languages, recording conditions, label quality, sample size, confounds that weren't ruled out, and any known failure modes.

## 9. Model registry, versioning, approval and rollback

- **Registry:** MLflow, in the research environment. Every registered model links to its dataset manifest hash, feature profile, code commit, experiment-log entry and evaluation report.
- **Stages and approvers** (ADR-012):

| Stage | Meaning | Needs |
|---|---|---|
| `EXPERIMENTAL` | Any logged research model | Experiment-log entry |
| `CANDIDATE` | Passed held-out evaluation against the spec | Evaluation report, sign-off by the responsible engineer |
| `APPROVED_FOR_SHADOW_EVALUATION` | May score live, consented data **without showing any output to users**, to measure real-world performance | Clinical advisor sign-off, ethics approval, a released inference contract, a security review |
| `APPROVED_FOR_CLINICAL_DECISION_SUPPORT` | May show an indicator, with its limitations, to clinicians | Independent clinical validation (§10), regulatory assessment, named clinical and engineering sign-off, and a release note in `docs/research/releases/` |

- No stage is reached automatically. Promotion is a documented human action, and no agent can approve one (CLAUDE.md).
- **Rollback:** the previous approved version (or "no model") must be restorable within one deployment, and the rollback is tested before each promotion.
- **Monitoring** (shadow stage onward): input drift, output drift, QC failure rate and subgroup performance on any labels that arrive later. Alerts go to people and never trigger retraining.

## 10. Independent clinical validation before any clinical deployment

- Internal held-out evaluation is **necessary but not sufficient** for clinical use.
- Clinical deployment needs:
  - A **prospective validation study** with its own ethics approval, a pre-registered protocol, and an independent reference standard (a clinical assessment blinded to the model output).
  - **Independent evaluation:** the study is analysed by people who didn't develop the model.
  - Evidence that clinicians interpret the indicator correctly, including how it is shown, its limitations, and its effect on referral decisions.
  - A **regulatory assessment** of whether the model counts as software as a medical device in the target jurisdiction, and what that requires. It is **[OPEN — regulatory]**.
- Until all of this exists, no model output is shown in any clinical or participant-facing workflow.

---

## Current status (2026-10-17)

| Item | Status |
|---|---|
| Authorised research datasets | **None documented** |
| Labels | **None** |
| Experiments run | **None** ([experiment-log.md](experiment-log.md) has no entries) |
| Models trained or registered | **None** |
| Performance claims | **None, and none may be made** |
| Clinical advisor appointed | **No** (spec open item) |
