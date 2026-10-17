# Experiment Log

**Version:** 1.0.0
**Date:** 2026-10-17
**Status:** ACTIVE

---

## Purpose

This log records every Workstream B training experiment. It is required by the `ml-experimentation` skill and the `ml-researcher` agent.

## Rules

1. **Log before running.** Add the entry with its hypothesis and setup *before* the training run starts.
2. **Training and validation splits only.** Experiments never use the held-out set. Held-out evaluation belongs to the `model-evaluation` skill.
3. **Record every run, including failures.** Never remove an entry or leave out unfavourable metrics.
4. **Experiment results are not validation evidence.** Nothing recorded here supports clinical use. See [model-validation-spec.md](model-validation-spec.md).
5. **No identifiers.** Refer to data by dataset version and pseudonymous session IDs only.

---

## Entry Template

Copy this block for each new experiment and add new entries at the bottom of the log.

```markdown
### EXP-NNN: <short title>

**Date started:** YYYY-MM-DD
**Owner:** <name>
**Status:** PLANNED | RUNNING | COMPLETED | FAILED | ABANDONED

**Hypothesis:** What is being tested and why.

**Setup**
- Dataset version and manifest:
- Consent basis verified (no withdrawn sessions): yes / no — date checked
- Split used: training / validation (never held-out)
- Feature set (openSMILE config and version):
- Features included or excluded, and why:
- Model and hyperparameters:
- Random seed:
- Environment: `workstream-b/experiments/<exp-id>/requirements.txt`
- Tracking run (MLflow or equivalent):

**Results** (validation split; fill in after the run)
- Metrics, including unfavourable ones:
- Subgroup results (language, device class):
- Failures or anomalies:

**Limitations:**

**Next steps:**
```

---

## Entries

_No experiments have been run yet._
