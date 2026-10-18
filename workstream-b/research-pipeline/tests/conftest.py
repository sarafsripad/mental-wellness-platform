import copy
import hashlib
import json
import secrets
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
ROOT = Path(__file__).resolve().parents[3]
EXAMPLE = json.loads((ROOT / "contracts/voice-features/examples/valid/complete-pass.json").read_text())


def hexid(prefix):
    return f"{prefix}_{secrets.token_hex(16)}"


def record(**changes):
    r = copy.deepcopy(EXAMPLE)
    r.update(record_id=hexid("frec"), session_id=hexid("sess"), recording_id=hexid("rec"), processing_job_id=hexid("job"))
    for path, value in changes.items():
        node = r
        *parents, leaf = path.split(".")
        for p in parents:
            node = node[p]
        node[leaf] = value
    return r


@pytest.fixture
def make_export(tmp_path):
    dropzone = tmp_path / "dropzone"
    dropzone.mkdir()

    def make(records, created="2026-10-18T12:00:00Z", withdrawn=(), training_excluded=(), mutate=None):
        export_id = hexid("exp")
        body = "".join(json.dumps(r) + "\n" for r in records).encode()
        manifest = {
            "manifest_version": "1.0.0", "export_id": export_id, "created_at_utc": created,
            "feature_schema_version": "2.0.0",
            "records_file": {"name": f"{export_id}.jsonl", "sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)},
            "record_count": len(records),
            "rekey": {"method": "HMAC-SHA256-TRUNC128-V1", "key_id": "key_" + "a" * 16},
            "split_groups": {r["session_id"]: "grp_" + hashlib.sha256(r["session_id"].encode()).hexdigest()[:32] for r in records},
            "model_training_excluded": list(training_excluded), "withdrawn_record_ids": list(withdrawn),
            "exclusions": {"no_research_consent": 0, "audio_consent_withdrawn": 0},
        }
        if mutate:
            mutate(manifest, body)
        (dropzone / f"{export_id}.jsonl").write_bytes(body)
        path = dropzone / f"{export_id}.manifest.json"
        path.write_text(json.dumps(manifest))
        return path

    return make


@pytest.fixture
def store(tmp_path):
    return tmp_path / "store"
