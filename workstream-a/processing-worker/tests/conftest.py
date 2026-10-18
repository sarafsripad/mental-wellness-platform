import importlib.util
import io
import sys
from pathlib import Path

import pytest

sys.path.insert(0, "/app")
GENERATOR = Path("/app/scripts/generate-synthetic-audio.py")


@pytest.fixture(scope="session")
def synth():
    spec = importlib.util.spec_from_file_location("synth", GENERATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    def make(variant: str) -> bytes:
        import wave, struct
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(module.RATE)
            w.writeframes(b"".join(struct.pack("<h", int(round(x * 32767))) for x in module.samples(variant)))
        return buf.getvalue()
    return make


@pytest.fixture(scope="session")
def extractor():
    from worker.extract import Extractor
    return Extractor()


@pytest.fixture(scope="session")
def schema_path():
    return Path("/app/contracts/feature-record.schema.json")


def lease(**overrides):
    base = {
        "job_id": "job_" + "1" * 32, "recording_id": "rec_" + "2" * 32, "session_id": "sess_" + "3" * 32,
        "pipeline_profile": "EGEMAPS_V02_FUNCTIONALS_V1", "lease_token": "t" * 64, "lease_seconds": 300,
        "correlation_id": "6f1c2b8e-3d4a-4f5b-9c6d-7e8f9a0b1c2d", "language_tag": "kn-IN", "data_origin": "SYNTHETIC",
        "capture_device_class": "ANDROID_PHONE", "recorded_at_utc": "2026-10-18T09:00:00Z",
        "consent_reference": {"consent_event_id": "ce_" + "4" * 32,
                              "scopes_verified": ["AUDIO_RECORDING", "SCREENING_PARTICIPATION"],
                              "verified_at_utc": "2026-10-18T09:05:00Z"},
        "audio": {"format": "WAV_PCM_S16LE", "sampling_rate_hz": 16000, "channels": 1, "duration_seconds": 6.0,
                  "sha256": "0" * 64},
    }
    base.update(overrides)
    return base
