# research-pipeline (Workstream B)

Slice step **S8**, consumer side. This pipeline ingests the consent-filtered research exports that the application writes to its one-way drop zone, and keeps a local research store. It is the only way research receives data from the platform (ADR-001, ADR-010).

**What it never receives:**
- audio
- names or any other identity data
- application IDs: every ID is re-keyed with a key that stays in the application
- participant references: sessions carry only `grp_` split groups
- free text

**What it doesn't do:**
- train or evaluate models
- make clinical claims

Synthetic or test data can validate software, never clinical performance ([model-validation-plan.md](../../docs/research/model-validation-plan.md)).

## Ingest rules

An export is accepted only if **all** of these hold. Otherwise nothing changes.

| Check | Rejection reason |
|---|---|
| Manifest validates against [export-manifest.schema.json](../../contracts/research-export/export-manifest.schema.json) | `MANIFEST_INVALID` |
| Manifest major version 1, feature schema major version 2 | `UNSUPPORTED_*_VERSION` |
| Records file present; size and SHA-256 match | `RECORDS_FILE_MISSING`, `RECORDS_FILE_INTEGRITY` |
| Every record validates against [feature-record.schema.json](../../contracts/voice-features/feature-record.schema.json) | `RECORD_INVALID` |
| Every record's `data_origin` is allowed (default `SYNTHETIC,AUTHORIZED_TEST_AUDIO`; participant data needs separate ethics approval) | `DATA_ORIGIN_NOT_ALLOWED` |
| Every session has a split group; the record count matches | `SPLIT_GROUP_MISSING`, `RECORD_COUNT_MISMATCH` |
| The export is newer than the last one ingested | `NOT_NEWER_THAN_LAST_INGESTED` |

Each export is a **full snapshot** of what is eligible now. Ingesting one replaces the store's contents, so withdrawn or ineligible records disappear, and `withdrawn_record_ids` is applied as well.

Records listed in `model_training_excluded` are kept, but stored with `training_permitted = 0`. Any future training code must exclude them.

## Use

```bash
# 1. Application side (a person runs this; nothing exports automatically):
workstream-a/backend/wellness-api/run-export.sh          # writes deploy/local/research-dropzone/exp_*.{jsonl,manifest.json}

# 2. Research side:
cd workstream-b/research-pipeline
python3 -m research_pipeline ingest  --dropzone ../../deploy/local/research-dropzone --store ./store
python3 -m research_pipeline summary --store ./store     # counts only, no feature values
```

Requires Python 3.9+ and `jsonschema` (from the repo's `requirements-dev.txt`). The store is a SQLite file in `--store`.

## Tests

```bash
python3 -m pytest workstream-b/research-pipeline/tests -q
```

The 14 tests cover every rejection reason, snapshot replacement and withdrawals, training exclusions, a counts-only summary, and the **real exports produced by the Java integration tests**, which pass this consumer unchanged.
