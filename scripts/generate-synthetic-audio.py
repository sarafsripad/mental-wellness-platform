#!/usr/bin/env python3
"""Generates deterministic, signal-generated "voice-like" WAV files for software testing.

These are NOT recordings of any person and NOT synthesised from anyone's voice. They are
harmonic tones shaped into syllable-like bursts with pitch movement, small jitter and pauses over a
low noise floor, so that audio QC and feature extraction behave as they would on speech. Every
record produced from them carries data_origin SYNTHETIC. They can validate software only, never
clinical performance (docs/research/model-validation-plan.md, ground rule 2).

Standard library only (runs on Python 3.9+). Usage:

    python3 scripts/generate-synthetic-audio.py --out DIR [--variant all|voice|noisy|drowned|clipped|silent|short]
"""
import argparse
import math
import random
import struct
import wave
from pathlib import Path

RATE = 16000

VARIANTS = {
    # name: (seconds, peak amplitude, noise amplitude, clip, bursts)
    "voice": (6.0, 0.45, 0.002, False, True),     # expected QC: PASS
    "noisy": (6.0, 0.30, 0.03, False, True),      # expected QC: DEGRADED (HIGH_NOISE), SNR about 13 dB
    "drowned": (6.0, 0.30, 0.08, False, True),    # expected QC: FAIL (HIGH_NOISE): loud, but no usable speech
    "clipped": (6.0, 3.0, 0.002, True, True),     # expected QC: DEGRADED (CLIPPING)
    "silent": (6.0, 0.0, 0.0, False, False),      # expected QC: FAIL (SILENCE)
    "short": (1.5, 0.45, 0.002, False, True),     # expected QC: FAIL (SHORT_DURATION)
}


def samples(variant: str, seed: int = 7):
    seconds, peak, noise, clip, bursts = VARIANTS[variant]
    rng = random.Random(f"{variant}-{seed}")
    n = int(RATE * seconds)
    out = []
    phase = 0.0
    syllable, gap = 0.22, 0.12  # seconds
    period = syllable + gap
    for i in range(n):
        t = i / RATE
        within = t % period
        envelope = 0.0
        if bursts and within < syllable:
            envelope = math.sin(math.pi * within / syllable) ** 2  # smooth syllable shape
        f0 = 140 + 25 * math.sin(2 * math.pi * 0.35 * t) + rng.uniform(-1.5, 1.5)  # contour + jitter
        phase += 2 * math.pi * f0 / RATE
        voiced = sum(math.sin(k * phase) / k for k in range(1, 6))  # harmonics
        x = peak * envelope * voiced / 1.6 + rng.gauss(0.0, noise) if (peak or noise) else 0.0
        if clip:
            x = max(-1.0, min(1.0, x))
        out.append(max(-1.0, min(1.0, x)))
    return out


def write_wav(path: Path, xs) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(round(x * 32767))) for x in xs))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--variant", default="all", choices=["all", *VARIANTS])
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for name in (VARIANTS if args.variant == "all" else [args.variant]):
        path = args.out / f"synthetic-{name}.wav"
        write_wav(path, samples(name, args.seed))
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
