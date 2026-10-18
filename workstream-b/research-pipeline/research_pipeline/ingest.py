"""Ingests the newest export in a drop zone into a local SQLite research store.

Every export is a full snapshot of the records currently eligible, so after a successful ingest the
store holds exactly that snapshot: anything withdrawn or no longer eligible is gone. An export is
accepted only if all of the following hold:

- the manifest validates against contracts/research-export/export-manifest.schema.json
- the records file exists and its size and SHA-256 match the manifest
- every record validates against contracts/voice-features/feature-record.schema.json (major version 2)
- every record's data_origin is allowed (participant data needs separate ethics approval first)
- the export is newer than the last one ingested

Otherwise nothing changes (all-or-nothing).
"""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[3]
FEATURE_SCHEMA = ROOT / "contracts/voice-features/feature-record.schema.json"
MANIFEST_SCHEMA = ROOT / "contracts/research-export/export-manifest.schema.json"
SUPPORTED_MANIFEST_MAJOR = 1
SUPPORTED_FEATURE_MAJOR = 2
DEFAULT_ALLOWED_ORIGINS = "SYNTHETIC,AUTHORIZED_TEST_AUDIO"


class ExportRejected(Exception):
    """The export was not ingested. The message is a short reason code plus a location, never data."""


@dataclass
class Ingested:
    export_id: str
    records: int
    withdrawn_applied: int
    training_excluded: int


def _validator(path: Path) -> Draft202012Validator:
    return Draft202012Validator(json.loads(path.read_text()), format_checker=FormatChecker())


def _major(version: str) -> int:
    return int(version.split(".")[0])


def open_store(store_dir: Path) -> sqlite3.Connection:
    store_dir.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(str(store_dir / "research-store.sqlite"))
    db.executescript("""
        CREATE TABLE IF NOT EXISTS export (
            export_id TEXT PRIMARY KEY, created_at_utc TEXT NOT NULL, record_count INTEGER NOT NULL,
            key_id TEXT NOT NULL, ingested_at_utc TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')));
        CREATE TABLE IF NOT EXISTS record (
            record_id TEXT PRIMARY KEY, export_id TEXT NOT NULL, split_group TEXT NOT NULL,
            training_permitted INTEGER NOT NULL, record_json TEXT NOT NULL);
    """)
    return db


def newest_manifest(dropzone: Path) -> Optional[Path]:
    manifests = sorted(dropzone.glob("exp_*.manifest.json"),
                       key=lambda p: json.loads(p.read_text()).get("created_at_utc", ""))
    return manifests[-1] if manifests else None


def ingest(manifest_path: Path, store_dir: Path, allowed_origins: Optional[set] = None) -> Ingested:
    allowed = allowed_origins or set(os.environ.get("RESEARCH_ALLOWED_ORIGINS", DEFAULT_ALLOWED_ORIGINS).split(","))
    manifest = json.loads(manifest_path.read_text())

    errors = list(_validator(MANIFEST_SCHEMA).iter_errors(manifest))
    if errors:
        raise ExportRejected("MANIFEST_INVALID at /" + "/".join(map(str, errors[0].absolute_path)))
    if _major(manifest["manifest_version"]) != SUPPORTED_MANIFEST_MAJOR:
        raise ExportRejected("UNSUPPORTED_MANIFEST_VERSION")
    if _major(manifest["feature_schema_version"]) != SUPPORTED_FEATURE_MAJOR:
        raise ExportRejected("UNSUPPORTED_FEATURE_SCHEMA_VERSION")

    records_path = manifest_path.parent / manifest["records_file"]["name"]
    if not records_path.is_file():
        raise ExportRejected("RECORDS_FILE_MISSING")
    body = records_path.read_bytes()
    if len(body) != manifest["records_file"]["bytes"] or hashlib.sha256(body).hexdigest() != manifest["records_file"]["sha256"]:
        raise ExportRejected("RECORDS_FILE_INTEGRITY")

    record_validator = _validator(FEATURE_SCHEMA)
    records = []
    for line_no, line in enumerate(body.decode("utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        record = json.loads(line)
        errs = list(record_validator.iter_errors(record))
        if errs:
            raise ExportRejected(f"RECORD_INVALID line {line_no} at /" + "/".join(map(str, errs[0].absolute_path)))
        if _major(record["schema_version"]) != SUPPORTED_FEATURE_MAJOR:
            raise ExportRejected(f"UNSUPPORTED_RECORD_VERSION line {line_no}")
        if record["provenance"]["data_origin"] not in allowed:
            raise ExportRejected(f"DATA_ORIGIN_NOT_ALLOWED line {line_no}")
        if record["session_id"] not in manifest["split_groups"]:
            raise ExportRejected(f"SPLIT_GROUP_MISSING line {line_no}")
        records.append(record)
    if len(records) != manifest["record_count"]:
        raise ExportRejected("RECORD_COUNT_MISMATCH")

    db = open_store(store_dir)
    try:
        last = db.execute("SELECT max(created_at_utc) FROM export").fetchone()[0]
        if last is not None and manifest["created_at_utc"] <= last:
            raise ExportRejected("NOT_NEWER_THAN_LAST_INGESTED")
        excluded = set(manifest["model_training_excluded"])
        with db:  # one transaction: all or nothing
            withdrawn = manifest["withdrawn_record_ids"]
            applied = db.execute(
                f"DELETE FROM record WHERE record_id IN ({','.join('?' * len(withdrawn))})", withdrawn
            ).rowcount if withdrawn else 0
            db.execute("DELETE FROM record")  # full snapshot replaces the previous one
            db.executemany(
                "INSERT INTO record (record_id, export_id, split_group, training_permitted, record_json) VALUES (?, ?, ?, ?, ?)",
                [(r["record_id"], manifest["export_id"], manifest["split_groups"][r["session_id"]],
                  0 if r["record_id"] in excluded else 1, json.dumps(r, sort_keys=True)) for r in records])
            db.execute("INSERT INTO export (export_id, created_at_utc, record_count, key_id) VALUES (?, ?, ?, ?)",
                       (manifest["export_id"], manifest["created_at_utc"], len(records), manifest["rekey"]["key_id"]))
        return Ingested(manifest["export_id"], len(records), applied, len(excluded))
    finally:
        db.close()
