# audio-processing-worker

Slice step **S7**. A Python worker that turns leased, consented audio into feature records. It claims jobs from the wellness-api's internal API, runs quality control (QC) and openSMILE eGeMAPS v02 extraction, and submits a record that validates against [feature-record.schema.json](../../contracts/voice-features/feature-record.schema.json).

> **Licence:** openSMILE is used under the audEERING Research License, for **development and evaluation with synthetic or authorised test audio only**. Production use, or using extracted features in a product, needs a commercial licence. Don't publish images that contain openSMILE. See [contracts/licences/opensmile.md](../../contracts/licences/opensmile.md).

## What it does, per job

1. **Claim** a job (`POST /internal/v1/processing-jobs/claim`) using the `processing-worker` client-credentials token.
2. **Licence guard:** refuse jobs whose `data_origin` isn't in `WORKER_ALLOWED_ORIGINS` (default `SYNTHETIC,AUTHORIZED_TEST_AUDIO`).
3. **Fetch** the audio and verify its SHA-256 against the lease. Audio stays in memory and is dropped when the job ends.
4. **Decode and run QC:** check the file signature, duration (3–180 s), level, clipping, speech ratio and an SNR estimate.
   - `FAIL` (silence, too short or long, or speech drowned in noise) → reported as `AUDIO_QUALITY_FAILED` with no record, so the ASHA worker can re-record.
   - `DEGRADED` → a record with flags and a fixed technical reason.
5. **Extract** 88 eGeMAPS v02 functionals with openSMILE 3.0.1. Non-finite values become `INVALID` with value `null`.
6. **Build** the v2.0.0 record, **validate it locally** against the contract, and **submit** it. The backend validates again and overwrites server-known fields.
7. A 409 from the backend (lease lost, or consent withdrawn) means the job's audio and output are discarded.

**Logs** contain job IDs, outcome codes and durations only, never audio, feature values or file paths.

## Pinned for reproducibility

| What | Pin | Enforced by |
|---|---|---|
| eGeMAPS v02 config | SHA-256 `ef451953…f24109e` | The worker refuses to start on a mismatch; `test_config_is_the_pinned_version` |
| openSMILE core | 3.0.1 | `test_bundled_core_is_the_recorded_version` checks the bundled library |
| Python and dependencies | `python:3.12.14-slim-bookworm`, [requirements.lock](requirements.lock) | Docker build |

## Run the tests

Tests run in the container, so nothing needs installing on your machine:

```bash
docker build --target test -f workstream-a/processing-worker/Dockerfile -t wellness-worker-test .   # from the repo root
docker run --rm wellness-worker-test
```

The suite has 32 tests:
- QC on every synthetic variant
- deterministic QC and extraction
- the pinned config and core version
- every built record validating against the contract
- clinical names and bad IDs refused
- the licence guard, decode errors, lost leases and quality failures
- logs containing IDs and codes only

## Run locally

Start [the local stack](../../deploy/local/README.md) and the [API](../backend/wellness-api/README.md), then:

```bash
workstream-a/processing-worker/run-local.sh
```

Test audio comes from [scripts/generate-synthetic-audio.py](../../scripts/generate-synthetic-audio.py). It is signal-generated, not anyone's voice, and its variants are `voice`, `noisy`, `drowned`, `clipped`, `silent` and `short`.

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `WORKER_CLIENT_SECRET` | **none (required)** | `processing-worker` client secret |
| `WORKER_API_URL` | `http://localhost:8080` | |
| `WORKER_TOKEN_URL` | local Keycloak token endpoint | |
| `WORKER_ID` | random `wrk_` + 16 hex | Bound to every lease |
| `WORKER_POLL_SECONDS` | `5` | Delay when the queue is empty |
| `WORKER_ALLOWED_ORIGINS` | `SYNTHETIC,AUTHORIZED_TEST_AUDIO` | Licence and governance guard |
| `WORKER_SCHEMA_PATH` | packaged contract schema | |
