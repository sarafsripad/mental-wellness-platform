---
name: opensmile-processing
description: Configure and run openSMILE feature extraction, validate output schemas, and document feature-set configurations for Workstream B.
---

# openSMILE Processing Skill

## Purpose

Configure openSMILE feature extraction runs, validate outputs against `contracts/voice-feature-schema.json`, and maintain reproducible feature-set configurations in `workstream-b/configs/`.

## Prerequisites

- Read `contracts/voice-feature-schema.json` to understand the required output structure.
- Know the openSMILE version in use — document in `workstream-b/configs/README.md`.
- Confirm that the audio input has a valid consent event with `MODEL_TRAINING` or `RESEARCH_ONLY` in `permitted_purposes`.

## Workflow

1. **Select or define a feature configuration.** openSMILE configs are `.conf` files in `workstream-b/configs/`. Each config must be versioned (filename includes version tag, e.g., `emobase_v2.conf`). Do not modify a config that has already been used in a dataset — create a new versioned config.
2. **Validate audio input.** Before extraction, confirm: file exists, format is supported (WAV/PCM preferred), duration meets minimum threshold, `audio_quality.status` is `PASS` or `DEGRADED` (not `FAIL`).
3. **Run extraction.** Invoke openSMILE with the selected config. Capture stdout and stderr. Non-zero exit must propagate as a pipeline failure — do not silently continue.
4. **Parse and map output.** Map openSMILE output to the `features` object in `voice-feature-schema.json`. Feature names must be the raw openSMILE functional names — do not rename to clinical labels.
5. **Populate metadata.** Fill `extraction_version`, `feature_set_id` (config filename + version), `audio_quality` (from pre-screen + any openSMILE quality indicators), and `provenance`.
6. **Validate the output record.** Run `scripts/validate-contracts.sh` on the output JSON. A record that fails schema validation must not be written to the dataset.
7. **Write to the feature store.** Append validated records to the versioned dataset partition.

## Constraints

- openSMILE configs are immutable once used in a dataset. Version-pin — never modify retroactively.
- Feature names in the output are acoustic descriptors only. Never map them to clinical label names.
- Extraction must be deterministic: the same audio + same config must always produce identical output.
- Do not run extraction on audio files with `FAIL` quality status.
- Document any config change in `docs/research/` with rationale and affected dataset versions.

## Expected Outputs

- Versioned openSMILE config file.
- Feature extraction script or pipeline step.
- Validated feature records conforming to `voice-feature-schema.json`.
- Extraction run log with audio file count, success/failure counts, and quality distribution.

## Acceptance Criteria

- [ ] Extraction is reproducible — same inputs produce identical outputs.
- [ ] All output records pass `validate-contracts.sh`.
- [ ] Feature names are acoustic (not clinical).
- [ ] Config version is recorded in every output record's `feature_set_id`.
- [ ] Failed-quality audio files skipped and logged.
- [ ] No direct identifiers in output records.
