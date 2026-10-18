import json
import os
import sqlite3
from pathlib import Path

import pytest

from conftest import ROOT, record
from research_pipeline.ingest import ExportRejected, ingest, newest_manifest
from research_pipeline.summary import summarise


def stored_ids(store):
    db = sqlite3.connect(str(store / "research-store.sqlite"))
    try:
        return {row[0] for row in db.execute("SELECT record_id FROM record")}
    finally:
        db.close()


def test_valid_export_is_ingested(make_export, store):
    records = [record(), record(**{"quality.status": "DEGRADED", "quality.flags": ["HIGH_NOISE"],
                                    "quality.degraded_reason": "See quality.flags"})]
    result = ingest(make_export(records), store)
    assert result.records == 2
    assert stored_ids(store) == {r["record_id"] for r in records}


def test_each_export_replaces_the_previous_snapshot_and_applies_withdrawals(make_export, store):
    a, b = record(), record()
    ingest(make_export([a, b], created="2026-10-18T10:00:00Z"), store)
    result = ingest(make_export([a], created="2026-10-18T11:00:00Z", withdrawn=[b["record_id"]]), store)
    assert result.withdrawn_applied == 1
    assert stored_ids(store) == {a["record_id"]}


def test_training_exclusions_are_kept(make_export, store):
    a, b = record(), record()
    ingest(make_export([a, b], training_excluded=[b["record_id"]]), store)
    s = summarise(store)
    assert s["records"] == 2 and s["training_permitted_records"] == 1


@pytest.mark.parametrize("mutate,reason", [
    (lambda m, b: m.update(records_file={**m["records_file"], "sha256": "0" * 64}), "RECORDS_FILE_INTEGRITY"),
    (lambda m, b: m.update(records_file={**m["records_file"], "bytes": 1}), "RECORDS_FILE_INTEGRITY"),
    (lambda m, b: m.update(record_count=99), "RECORD_COUNT_MISMATCH"),
    (lambda m, b: m.update(participant_ref="ppt_" + "0" * 32), "MANIFEST_INVALID"),
    (lambda m, b: m.update(feature_schema_version="3.0.0"), "MANIFEST_INVALID"),
])
def test_bad_manifests_are_rejected(make_export, store, mutate, reason):
    with pytest.raises(ExportRejected, match=reason):
        ingest(make_export([record()], mutate=mutate), store)
    assert not (store / "research-store.sqlite").exists() or not stored_ids(store)


@pytest.mark.parametrize("bad,reason", [
    (record(**{"features": [{"name": "depression_score", "value": 1.0, "unit": None, "status": "VALID", "reason": None}]}),
     "RECORD_INVALID"),
    (record(**{"provenance.data_origin": "PARTICIPANT"}), "DATA_ORIGIN_NOT_ALLOWED"),
])
def test_one_bad_record_rejects_the_whole_export(make_export, store, bad, reason):
    good = record()
    with pytest.raises(ExportRejected, match=reason):
        ingest(make_export([good, bad]), store)
    assert good["record_id"] not in (stored_ids(store) if (store / "research-store.sqlite").exists() else set())


def test_older_or_replayed_exports_are_rejected(make_export, store):
    ingest(make_export([record()], created="2026-10-18T12:00:00Z"), store)
    with pytest.raises(ExportRejected, match="NOT_NEWER"):
        ingest(make_export([record()], created="2026-10-18T09:00:00Z"), store)


def test_summary_has_counts_only(make_export, store):
    ingest(make_export([record(), record()]), store)
    text = json.dumps(summarise(store))
    assert "F0semitone" not in text and "value" not in text
    assert "Not evidence of clinical performance" in text


def test_newest_manifest_is_chosen(make_export, tmp_path):
    make_export([record()], created="2026-10-18T09:00:00Z")
    newest = make_export([record()], created="2026-10-18T12:00:00Z")
    assert newest_manifest(tmp_path / "dropzone") == newest


SAMPLES = Path(os.environ.get("RESEARCH_EXPORT_SAMPLES",
                              ROOT / "workstream-a/backend/wellness-api/build/research-export-samples"))


@pytest.mark.skipif(not list(SAMPLES.glob("exp_*.manifest.json")) if SAMPLES.is_dir() else True,
                    reason="no exports from the backend tests (run ./gradlew test first)")
def test_real_exports_from_the_application_are_accepted(tmp_path):
    """Cross-language check: exports written by the Java producer pass this consumer unchanged."""
    manifests = sorted(SAMPLES.glob("exp_*.manifest.json"), key=lambda p: json.loads(p.read_text())["created_at_utc"])
    for i, manifest in enumerate(manifests):
        ingest(manifest, tmp_path / f"store-{i}")  # each into a fresh store: they share timestamps
    assert manifests
