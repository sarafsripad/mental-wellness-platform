import json
import re
from pathlib import Path

import opensmile
from jsonschema import Draft202012Validator

from worker.audio import decode
from worker.extract import OPENSMILE_CORE_VERSION, PINNED_CONFIG_SHA256, unit_for


def test_config_is_the_pinned_version(extractor):
    assert extractor.config_sha256 == PINNED_CONFIG_SHA256


def test_bundled_core_is_the_recorded_version():
    """Fails if a dependency upgrade changes the openSMILE core without updating the records."""
    lib = next(Path(opensmile.__file__).parent.glob("core/bin/*/libSMILEapi.so"))
    data = lib.read_bytes()
    i = data.find(OPENSMILE_CORE_VERSION.encode())
    assert i != -1 and b"openSMILE" in data[i:i + 40]


def test_extracts_88_egemaps_functionals(extractor, synth):
    d = decode(synth("voice"), "WAV_PCM_S16LE")
    features = extractor.extract(d.signal, d.sampling_rate_hz)
    assert len(features) == 88
    assert sum(f.status == "VALID" for f in features) >= 80


def test_extraction_is_deterministic(extractor, synth):
    d = decode(synth("voice"), "WAV_PCM_S16LE")
    a = [(f.name, f.value, f.status) for f in extractor.extract(d.signal, d.sampling_rate_hz)]
    b = [(f.name, f.value, f.status) for f in extractor.extract(d.signal, d.sampling_rate_hz)]
    assert a == b


def test_feature_names_pass_the_contract_name_rule(extractor, synth, schema_path):
    rule = json.loads(schema_path.read_text())["$defs"]["feature"]["properties"]["name"]
    validator = Draft202012Validator(rule)
    d = decode(synth("voice"), "WAV_PCM_S16LE")
    for f in extractor.extract(d.signal, d.sampling_rate_hz):
        assert validator.is_valid(f.name), f.name


def test_units():
    assert unit_for("F0semitoneFrom27.5Hz_sma3nz_amean") == "semitone"
    assert unit_for("F1frequency_sma3nz_amean") == "Hz"
    assert unit_for("HNRdBACF_sma3nz_amean") == "dB"
    assert unit_for("MeanVoicedSegmentLengthSec") == "s"
    assert unit_for("VoicedSegmentsPerSec") == "1/s"
    assert unit_for("loudness_sma3_amean") is None
