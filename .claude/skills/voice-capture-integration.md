---
name: voice-capture-integration
description: Integrate voice recording, audio quality validation, and secure handoff from the mobile capture layer to the feature extraction pipeline.
---

# Voice Capture Integration Skill

## Purpose

Implement the audio capture pipeline: record audio on device, validate quality gates, encrypt and store locally, and hand off to the feature extraction pipeline via the ingestion service.

## Prerequisites

- Read `contracts/voice-feature-schema.json` — the audio quality block defines required quality fields.
- Understand the consent gate: recording must not start until a consent event ID is stored locally.
- Know the target audio format and quality requirements (sample rate, bit depth, channel count, minimum SNR).

## Workflow

1. **Implement the capture layer.** Use a platform audio API (e.g., react-native-audio-recorder-player or ExpoAV). Configure sample rate (16 kHz recommended for speech), mono channel, PCM or WAV output.
2. **Enforce the consent gate.** Before starting recording, verify that a valid consent event ID exists in local storage. If missing, block recording with a clear user message — do not bypass.
3. **Implement real-time level monitoring.** Display an audio level meter during recording. Warn if the level is too low (possible microphone blockage) or too high (clipping).
4. **Validate duration.** Enforce minimum and maximum recording duration. Reject recordings outside the configured range before encryption.
5. **Run on-device quality pre-screening.** Compute basic quality indicators (peak level, estimated noise floor, clipping flag). Store in the `audio_quality` block of the feature record.
6. **Encrypt the audio.** Encrypt the audio file with a device-derived key before writing to the local queue. The encryption key must never be transmitted to the server.
7. **Prepare the handoff.** Package the audio file reference, consent event ID, pseudonymous session ID, language tag, and device class. Submit to the ingestion service for server-side feature extraction.
8. **Handle extraction feedback.** On feature extraction completion, receive the `audio_quality.status` from the server and display to the ASHA worker if `DEGRADED` or `FAIL`.

## Constraints

- Audio files must not be uploaded without the encrypted wrapping — validate before submission.
- Never log or transmit raw audio to analytics or error-tracking services.
- Do not use audio files as training data without verifying `MODEL_TRAINING` is in the consent's `permitted_purposes`.
- Quality pre-screening results are informational — they do not replace server-side validation.

## Expected Outputs

- Audio capture component with consent gate, level monitor, duration enforcement.
- On-device quality pre-screener.
- Encrypted local audio file writer.
- Handoff payload builder conforming to `contracts/voice-feature-schema.json` audio quality fields.
- Tests: capture blocked without consent, duration validation, quality pre-screen output, encryption round-trip.

## Acceptance Criteria

- [ ] Recording blocked when no consent event is present.
- [ ] Audio encrypted before writing to disk.
- [ ] Quality indicators populated in feature record.
- [ ] Handoff payload validates against `contracts/voice-feature-schema.json`.
- [ ] No audio data in application logs.
- [ ] Tests pass for all capture gate conditions.
