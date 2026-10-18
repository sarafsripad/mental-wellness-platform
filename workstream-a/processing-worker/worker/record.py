"""Builds v2.0.0 feature records and validates them locally against the contract schema.

The backend overwrites server-known fields (consent reference, language, provenance) and validates
again, but a record that fails here is a worker bug and is never submitted.
"""
import json
import secrets
from datetime import datetime, timezone
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

from . import __version__
from .audio import Decoded, Quality
from .extract import CONFIG_ID, CONFIG_VERSION, OPENSMILE_CORE_VERSION, Feature

RECORD_FIELDS_FROM_QC = ("snr_db", "speech_ratio", "clipping_ratio", "peak_dbfs")


class RecordInvalid(Exception):
    def __init__(self, pointers):
        super().__init__(", ".join(pointers))
        self.pointers = pointers


class RecordBuilder:
    def __init__(self, schema_path: Path, config_sha256: str, pipeline_commit: str | None = None):
        schema = json.loads(Path(schema_path).read_text())
        self.validator = Draft202012Validator(schema, format_checker=FormatChecker())
        self.config_sha256 = config_sha256
        self.pipeline_commit = pipeline_commit

    def build(self, lease: dict, decoded: Decoded, quality: Quality, features: list[Feature],
              processing_ms: int) -> dict:
        not_computed = [f for f in features if f.status != "VALID"]
        record = {
            "schema_version": "2.0.0",
            "record_type": "ENGINEERING_FEATURE_RECORD",
            "record_id": f"frec_{secrets.token_hex(16)}",
            "session_id": lease["session_id"],
            "recording_id": lease["recording_id"],
            "processing_job_id": lease["job_id"],
            "consent_reference": lease["consent_reference"],
            "language_tag": lease["language_tag"],
            "extraction": {
                "tool": "openSMILE",
                "tool_version": OPENSMILE_CORE_VERSION,
                "config_id": CONFIG_ID,
                "config_version": CONFIG_VERSION,
                "config_sha256": self.config_sha256,
                "feature_set": "eGeMAPSv02",
                "feature_level": "FUNCTIONALS",
                "pipeline_version": __version__,
                "pipeline_commit": self.pipeline_commit,
                "container_image_digest": None,
            },
            "audio": {
                "sampling_rate_hz": decoded.sampling_rate_hz,
                "channels": decoded.channels,
                "duration_seconds": decoded.duration_seconds,
                "input_format": lease["audio"]["format"],
                "resampled_to_hz": None,
            },
            "processing": {
                "status": "COMPLETED_WITH_WARNINGS" if not_computed else "COMPLETED",
                "processed_at_utc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "processing_time_ms": processing_ms,
                "warnings": ["FEATURES_NOT_COMPUTED"] if not_computed else [],
            },
            "quality": {
                "status": quality.status,
                "flags": [f for f in quality.flags],
                **{k: getattr(quality, k) for k in RECORD_FIELDS_FROM_QC},
                "degraded_reason": quality.degraded_reason,
            },
            "features": [
                {"name": f.name, "value": f.value, "unit": f.unit, "status": f.status, "reason": f.reason}
                for f in features
            ],
            "provenance": {
                "data_origin": lease["data_origin"],
                "capture_device_class": lease["capture_device_class"],
                "recorded_at_utc": lease["recorded_at_utc"],
                "app_version": None,
            },
        }
        self.validate(record)
        return record

    def validate(self, record: dict) -> None:
        errors = sorted(self.validator.iter_errors(record), key=lambda e: list(e.absolute_path))
        if errors:
            raise RecordInvalid(["/" + "/".join(map(str, e.absolute_path)) for e in errors])
