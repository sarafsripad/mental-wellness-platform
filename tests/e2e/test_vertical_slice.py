"""S10 acceptance criteria (docs/implementation/vertical-slice-plan.md section 7), end to end."""
import json
import subprocess

from jsonschema import Draft202012Validator, FormatChecker

from conftest import ROOT, psql, pytestmark_skip, storage_has

pytestmark = pytestmark_skip


def test_voice_like_audio_becomes_a_stored_valid_feature_record(journey, secrets_env, synth):
    _, _, session, recording = journey.upload(synth("voice"))
    status, job = journey.submit(recording)
    assert status == 202, job
    done = journey.wait(job["job_id"])
    assert done["status"] == "SUCCEEDED", done
    assert done["quality_outcome"] == {"status": "PASS", "flags": []}
    assert "features" not in json.dumps(done), "the status API never exposes feature values"
    status, s = journey.http.call("GET", f"/v1/voice-sessions/{session}")
    assert s["status"] == "PROCESSED"
    record = json.loads(psql(secrets_env, f"SELECT record::text FROM processing.feature_record WHERE job_id = '{done['job_id']}'"))
    assert record["provenance"]["data_origin"] == "SYNTHETIC"
    assert record["consent_reference"]["consent_event_id"] == journey.consent_event
    assert record["extraction"]["tool"] == "openSMILE" and record["extraction"]["tool_version"] == "3.0.1"
    assert len(record["features"]) == 88


def test_noisy_audio_is_processed_as_degraded(journey, synth):
    _, _, _, recording = journey.upload(synth("noisy"))
    done = journey.wait(journey.submit(recording)[1]["job_id"])
    assert done["status"] == "SUCCEEDED"
    assert done["quality_outcome"]["status"] == "DEGRADED" and "HIGH_NOISE" in done["quality_outcome"]["flags"]


def test_silent_audio_fails_quality_and_produces_no_record(journey, secrets_env, synth):
    _, _, session, recording = journey.upload(synth("silent"))
    done = journey.wait(journey.submit(recording)[1]["job_id"])
    assert done["status"] == "FAILED"
    assert done["failure"] == {"code": "AUDIO_QUALITY_FAILED", "retryable": False}
    assert done["quality_outcome"]["status"] == "FAIL" and done["feature_record_id"] is None
    assert psql(secrets_env, f"SELECT count(*) FROM processing.feature_record WHERE job_id = '{done['job_id']}'") == "0"
    assert journey.http.call("GET", f"/v1/voice-sessions/{session}")[1]["status"] == "PROCESSING_FAILED"


def test_without_audio_consent_nothing_can_be_processed(journey, synth):
    _, _, _, recording = journey.upload(synth("voice"))
    status, w = journey.withdraw(["AUDIO_RECORDING"])
    assert status == 202
    status, problem = journey.submit(recording)
    assert status in (404, 422), problem  # audio deleted with the withdrawal
    status, problem = journey.upload(synth("voice"))[:2]
    assert status == 422 and problem["code"] == "CONSENT_NOT_ACTIVE"


def test_withdrawal_after_processing_deletes_audio_and_flags_the_record(journey, secrets_env, synth):
    _, _, _, recording = journey.upload(synth("voice"))
    done = journey.wait(journey.submit(recording)[1]["job_id"])
    assert done["status"] == "SUCCEEDED"
    assert storage_has(secrets_env, f"audio/{recording}")
    status, w = journey.withdraw(["AUDIO_RECORDING"])
    actions = {a["action"]: a["status"] for a in w["downstream_actions"]}
    assert actions["DELETE_RETAINED_AUDIO"] == "SCHEDULED" and actions["FLAG_DERIVED_FEATURES"] == "COMPLETED"
    assert not storage_has(secrets_env, f"audio/{recording}")
    assert psql(secrets_env, f"SELECT flagged_at IS NOT NULL FROM processing.feature_record WHERE job_id = '{done['job_id']}'") == "t"


def test_participant_data_is_refused_in_this_environment(journey, synth):
    status, problem, _, _ = journey.upload(synth("voice"), origin="PARTICIPANT")
    assert status == 422 and problem["code"] == "DATA_ORIGIN_NOT_PERMITTED"


def test_every_stored_feature_record_validates_against_the_contract(secrets_env):
    schema = json.loads((ROOT / "contracts/voice-features/feature-record.schema.json").read_text())
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    rows = psql(secrets_env, "SELECT record::text FROM processing.feature_record").splitlines()
    assert rows, "no feature records stored"
    for row in rows:
        errors = list(validator.iter_errors(json.loads(row)))
        assert not errors, errors[0].message


def test_logs_contain_no_feature_values_or_audio():
    api_log = (ROOT / "workstream-a/backend/wellness-api/build/bootrun.log").read_text(errors="replace")
    worker_log = (ROOT / "workstream-a/processing-worker/.worker.log").read_text(errors="replace")
    for text in (api_log, worker_log):
        assert "F0semitone" not in text and "RIFF" not in text and "lease_token" not in text
    assert "outcome=SUCCEEDED" in worker_log


def test_worker_container_holds_no_database_or_storage_credentials():
    out = subprocess.run(["docker", "inspect", "wellness-worker", "--format", "{{json .Config.Env}}"],
                         capture_output=True, text=True, check=True, stdin=subprocess.DEVNULL).stdout
    names = {e.split("=", 1)[0] for e in json.loads(out)}
    assert not any(k for k in names if any(t in k for t in ("DB", "POSTGRES", "STORAGE", "AWS", "S3"))), names
