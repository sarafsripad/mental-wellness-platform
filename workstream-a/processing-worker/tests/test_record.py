import json

import pytest

from conftest import lease
from worker.audio import assess, decode
from worker.record import RecordBuilder, RecordInvalid


@pytest.fixture(scope="module")
def builder(schema_path, extractor):
    return RecordBuilder(schema_path, extractor.config_sha256)


@pytest.mark.parametrize("variant", ["voice", "noisy", "clipped"])
def test_built_records_validate_against_the_contract(builder, extractor, synth, variant):
    d = decode(synth(variant), "WAV_PCM_S16LE")
    q = assess(d)
    record = builder.build(lease(), d, q, extractor.extract(d.signal, d.sampling_rate_hz), 1234)
    assert record["record_type"] == "ENGINEERING_FEATURE_RECORD"
    assert record["quality"]["status"] == q.status
    assert record["extraction"]["tool_version"] == "3.0.1"
    assert record["processing_job_id"] == lease()["job_id"]


def test_record_contains_no_audio_or_identifiers(builder, extractor, synth):
    d = decode(synth("voice"), "WAV_PCM_S16LE")
    text = json.dumps(builder.build(lease(), d, assess(d), extractor.extract(d.signal, d.sampling_rate_hz), 1))
    assert "RIFF" not in text and "lease_token" not in text and "t" * 64 not in text


def test_invalid_records_are_never_built(builder, extractor, synth):
    d = decode(synth("voice"), "WAV_PCM_S16LE")
    features = extractor.extract(d.signal, d.sampling_rate_hz)
    with pytest.raises(RecordInvalid):
        builder.build(lease(session_id="not-a-session"), d, assess(d), features, 1)
    features[0].name = "depression_score"
    with pytest.raises(RecordInvalid):
        builder.build(lease(), d, assess(d), features, 1)
