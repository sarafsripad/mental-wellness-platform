"""Decoding and server-side quality control (QC) of leased audio, entirely in memory.

QC decides whether a recording is usable: FAIL produces no feature record (the job is reported as
failed so the ASHA worker can re-record); DEGRADED produces a record with flags and a reason.
"""
import io
from dataclasses import dataclass, field

import numpy as np
import soundfile as sf

MIN_SECONDS = 3.0
MAX_SECONDS = 180.0
FRAME_SECONDS = 0.025
HOP_SECONDS = 0.010
SILENCE_DBFS = -50.0
LOW_LEVEL_DBFS = -30.0
CLIP_LEVEL = 0.999
CLIP_DEGRADED_RATIO = 0.001
SPEECH_ABOVE_FLOOR_DB = 10.0
MIN_SPEECH_RATIO_FAIL = 0.05
LOW_SPEECH_RATIO = 0.30
HIGH_NOISE_SNR_DB = 15.0


class DecodeError(Exception):
    """The audio could not be decoded or does not match its declared format."""


@dataclass
class Decoded:
    signal: np.ndarray  # mono float32 in [-1, 1]
    sampling_rate_hz: int
    channels: int
    duration_seconds: float


@dataclass
class Quality:
    status: str  # PASS | DEGRADED | FAIL
    flags: list = field(default_factory=list)
    snr_db: float | None = None
    speech_ratio: float | None = None
    clipping_ratio: float | None = None
    peak_dbfs: float | None = None
    degraded_reason: str | None = None


def decode(content: bytes, declared_format: str) -> Decoded:
    signature_ok = (content[:4] == b"RIFF" and content[8:12] == b"WAVE") if declared_format == "WAV_PCM_S16LE" \
        else content[:4] == b"fLaC" if declared_format == "FLAC" else False
    if not signature_ok:
        raise DecodeError("FORMAT_MISMATCH")
    try:
        data, rate = sf.read(io.BytesIO(content), dtype="float32", always_2d=True)
    except Exception as ex:  # soundfile raises several types
        raise DecodeError("UNREADABLE") from ex
    channels = data.shape[1]
    signal = data.mean(axis=1) if channels > 1 else data[:, 0]
    return Decoded(signal=np.ascontiguousarray(signal, dtype=np.float32), sampling_rate_hz=int(rate),
                   channels=int(channels), duration_seconds=round(len(signal) / rate, 3))


def assess(d: Decoded) -> Quality:
    """Deterministic QC on the decoded signal."""
    if d.duration_seconds < MIN_SECONDS:
        return Quality("FAIL", ["SHORT_DURATION"])
    if d.duration_seconds > MAX_SECONDS:
        return Quality("FAIL", ["LONG_DURATION"])
    x = d.signal
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    if peak <= 0.0:
        return Quality("FAIL", ["SILENCE"])
    peak_dbfs = round(min(0.0, 20 * np.log10(peak)), 2)
    clipping_ratio = round(float(np.mean(np.abs(x) >= CLIP_LEVEL)), 6)

    frame, hop = int(FRAME_SECONDS * d.sampling_rate_hz), int(HOP_SECONDS * d.sampling_rate_hz)
    n = 1 + max(0, (len(x) - frame) // hop)
    idx = np.arange(frame)[None, :] + hop * np.arange(n)[:, None]
    rms = np.sqrt(np.mean(x[idx] ** 2, axis=1)) + 1e-12
    rms_db = 20 * np.log10(rms)
    floor_db = float(np.percentile(rms_db, 10))
    speech = rms_db > max(floor_db + SPEECH_ABOVE_FLOOR_DB, SILENCE_DBFS)
    speech_ratio = round(float(np.mean(speech)), 4)
    snr_db = round(float(np.mean(rms_db[speech]) - floor_db), 2) if speech.any() else None

    if peak_dbfs < SILENCE_DBFS:
        return Quality("FAIL", ["SILENCE"], snr_db, speech_ratio, clipping_ratio, peak_dbfs)
    if speech_ratio < MIN_SPEECH_RATIO_FAIL:
        # Loud, but nothing stands out from the noise: the advice is "find a quieter place",
        # not "speak up", so this must not be reported as silence.
        return Quality("FAIL", ["HIGH_NOISE", "LOW_SPEECH_RATIO"], snr_db, speech_ratio, clipping_ratio, peak_dbfs)
    flags = []
    if clipping_ratio > CLIP_DEGRADED_RATIO:
        flags.append("CLIPPING")
    if peak_dbfs < LOW_LEVEL_DBFS:
        flags.append("LOW_LEVEL")
    if snr_db is not None and snr_db < HIGH_NOISE_SNR_DB:
        flags.append("HIGH_NOISE")
    if speech_ratio < LOW_SPEECH_RATIO:
        flags.append("LOW_SPEECH_RATIO")
    if flags:
        # Fixed technical text only: never participant information.
        return Quality("DEGRADED", flags, snr_db, speech_ratio, clipping_ratio, peak_dbfs,
                       "Automatic QC flags: " + ", ".join(flags))
    return Quality("PASS", [], snr_db, speech_ratio, clipping_ratio, peak_dbfs)
