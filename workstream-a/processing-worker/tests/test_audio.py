import pytest

from worker.audio import DecodeError, assess, decode


@pytest.mark.parametrize("variant,status,flag", [
    ("voice", "PASS", None),
    ("noisy", "DEGRADED", "HIGH_NOISE"),
    ("drowned", "FAIL", "HIGH_NOISE"),
    ("clipped", "DEGRADED", "CLIPPING"),
    ("silent", "FAIL", "SILENCE"),
    ("short", "FAIL", "SHORT_DURATION"),
])
def test_qc_classifies_synthetic_variants(synth, variant, status, flag):
    q = assess(decode(synth(variant), "WAV_PCM_S16LE"))
    assert q.status == status, q
    if flag:
        assert flag in q.flags
    if status == "DEGRADED":
        assert q.degraded_reason and "Automatic QC flags" in q.degraded_reason
    if status == "PASS":
        assert q.flags == [] and q.degraded_reason is None


def test_loud_noise_is_never_reported_as_silence(synth):
    q = assess(decode(synth("drowned"), "WAV_PCM_S16LE"))
    assert "SILENCE" not in q.flags and q.peak_dbfs > -10


def test_qc_is_deterministic(synth):
    a = assess(decode(synth("noisy"), "WAV_PCM_S16LE"))
    b = assess(decode(synth("noisy"), "WAV_PCM_S16LE"))
    assert a == b


def test_decoded_properties(synth):
    d = decode(synth("voice"), "WAV_PCM_S16LE")
    assert (d.sampling_rate_hz, d.channels, d.duration_seconds) == (16000, 1, 6.0)


@pytest.mark.parametrize("content,fmt", [
    (b"not audio at all, just text bytes", "WAV_PCM_S16LE"),
    (b"RIFF\x00\x00\x00\x00WAVEgarbage", "WAV_PCM_S16LE"),
    (b"RIFF\x00\x00\x00\x00WAVE", "FLAC"),
])
def test_bad_audio_is_a_decode_error(content, fmt):
    with pytest.raises(DecodeError):
        decode(content, fmt)
