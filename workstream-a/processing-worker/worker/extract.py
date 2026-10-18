"""openSMILE eGeMAPS v02 functionals for the EGEMAPS_V02_FUNCTIONALS_V1 pipeline profile.

The config is pinned by SHA-256: if the installed openSMILE ships a different config, the worker
refuses to start (configs are immutable once used; a change needs a new profile).
"""
import hashlib
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import opensmile

PROFILE = "EGEMAPS_V02_FUNCTIONALS_V1"
CONFIG_ID = "egemaps_v02_functionals"
CONFIG_VERSION = "1.0.0"
PINNED_CONFIG_SHA256 = "ef451953badced2ed112ba4ffb997f9d2a7e7c444a09e8092665eadd9f24109e"
OPENSMILE_CORE_VERSION = "3.0.1"  # verified against the bundled libSMILEapi in tests


class ConfigMismatch(RuntimeError):
    pass


@dataclass
class Feature:
    name: str
    value: float | None
    unit: str | None
    status: str
    reason: str | None


def unit_for(name: str) -> str | None:
    """Units where the eGeMAPS name makes them unambiguous; otherwise null (schema allows it)."""
    if name.startswith("F0semitone"):
        return "semitone"
    if "frequency" in name or "bandwidth" in name:
        return "Hz"
    if any(k in name for k in ("dB", "HNR", "alphaRatio", "hammarbergIndex", "logRelF0", "AmplitudeLogRelF0",
                               "amplitudeLogRelF0")):
        return "dB"
    if name.endswith("Sec") and "PerSec" not in name:
        return "s"
    if name.endswith("PerSec"):
        return "1/s"
    if name.startswith("jitter"):
        return "ratio"
    return None


class Extractor:
    def __init__(self):
        self.smile = opensmile.Smile(feature_set=opensmile.FeatureSet.eGeMAPSv02,
                                     feature_level=opensmile.FeatureLevel.Functionals)
        self.config_sha256 = hashlib.sha256(Path(self.smile.config_path).read_bytes()).hexdigest()
        if self.config_sha256 != PINNED_CONFIG_SHA256:
            raise ConfigMismatch("installed eGeMAPSv02 config differs from the pinned version")

    def extract(self, signal: np.ndarray, sampling_rate_hz: int) -> list[Feature]:
        frame = self.smile.process_signal(signal, sampling_rate_hz)
        features = []
        for name in self.smile.feature_names:
            value = float(frame[name].iloc[0])
            if math.isfinite(value):
                features.append(Feature(name, value, unit_for(name), "VALID", None))
            else:
                features.append(Feature(name, None, unit_for(name), "INVALID", "NON_FINITE_VALUE"))
        return features
