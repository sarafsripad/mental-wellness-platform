"""S10: processed records reach Workstream B only through the consent-filtered, re-keyed export."""
import hashlib
import hmac
import json
import sqlite3
import subprocess
import sys

from conftest import ROOT, pytestmark_skip

pytestmark = pytestmark_skip
DROPZONE = ROOT / "deploy/local/research-dropzone"


def run_export():
    r = subprocess.run([str(ROOT / "workstream-a/backend/wellness-api/run-export.sh")], capture_output=True, text=True,
                       stdin=subprocess.DEVNULL, timeout=900)
    assert r.returncode == 0, r.stdout[-2000:] + r.stderr[-2000:]
    for line in r.stdout.splitlines():
        if "research export exp_" in line:
            message = json.loads(line)["message"] if line.startswith("{") else line
            return next(w for w in message.split() if w.startswith("exp_"))
    raise AssertionError("export runner did not report an export ID")


def ingest(store):
    r = subprocess.run([sys.executable, "-m", "research_pipeline", "ingest", "--dropzone", str(DROPZONE), "--store", str(store)],
                       cwd=ROOT / "workstream-b/research-pipeline", capture_output=True, text=True, stdin=subprocess.DEVNULL)
    assert r.returncode == 0, r.stderr
    return json.loads(r.stdout)


def rekey(key, app_id):
    prefix = app_id.split("_", 1)[0]
    return prefix + "_" + hmac.new(key.encode(), f"{prefix}:{app_id}".encode(), hashlib.sha256).hexdigest()[:32]


def stored(store):
    db = sqlite3.connect(str(store / "research-store.sqlite"))
    try:
        return {row[0]: row[1] for row in db.execute("SELECT record_id, training_permitted FROM record")}
    finally:
        db.close()


def test_export_reaches_research_and_withdrawal_removes_it(journey, secrets_env, synth, tmp_path):
    _, _, _, recording = journey.upload(synth("voice"))
    done = journey.wait(journey.submit(recording)[1]["job_id"])
    assert done["status"] == "SUCCEEDED"

    export_id = run_export()
    manifest = json.loads((DROPZONE / f"{export_id}.manifest.json").read_text())
    body = (DROPZONE / f"{export_id}.jsonl").read_text()
    key = (ROOT / "deploy/local/.env").read_text().split("WELLNESS_EXPORT_KEY=")[-1].splitlines()[0]
    research_id = rekey(key, done["feature_record_id"])
    assert research_id in body
    for app_id in (done["feature_record_id"], journey.participant, journey.consent_event, done["job_id"], recording):
        assert app_id not in body and app_id not in json.dumps(manifest), "application IDs never leave the application"

    store = tmp_path / "store"
    assert ingest(store)["export_id"] == export_id
    assert stored(store)[research_id] == 1  # all scopes granted, so training is permitted

    status, w = journey.withdraw(["RESEARCH_FEATURE_REUSE"])
    assert {a["action"]: a["status"] for a in w["downstream_actions"]}["EXCLUDE_FROM_RESEARCH_EXPORT"] == "SCHEDULED"
    next_id = run_export()
    next_manifest = json.loads((DROPZONE / f"{next_id}.manifest.json").read_text())
    assert research_id in next_manifest["withdrawn_record_ids"]
    ingest(store)
    assert research_id not in stored(store)
