---
name: mobile-development
description: Build and maintain the React Native mobile application for ASHA worker workflows, including offline-first UI, multilingual support, and voice capture flows.
---

# Mobile Development Skill

## Purpose

Implement the React Native mobile client for Workstream A: ASHA worker onboarding, multilingual participant consent capture, offline-first voice recording, and synchronisation status feedback.

## Prerequisites

- Read any existing `workstream-a/mobile/` source before modifying.
- Confirm the target API version from `contracts/` before implementing a new endpoint call.
- The app must function without network connectivity. Design UI states for: online, offline-queued, and sync-in-progress.

## Workflow

1. **Define the screen flow.** Map the user journey (ASHA worker login → select participant → capture consent → record voice sample → review and submit → sync queue).
2. **Implement offline-first data layer.** Use a local embedded store (e.g., SQLite via Watermelon or Realm) as the source of truth. Sync to backend asynchronously.
3. **Implement consent capture UI.** Display consent form in participant's selected language. Require explicit affirmative action (checkbox + confirm). Record consent event locally before enabling recording.
4. **Implement voice capture.** Call the audio capture library. Enforce minimum/maximum duration. Show real-time audio-level feedback. Store encrypted audio locally.
5. **Implement sync queue.** On connectivity, upload pending sessions in order. Show sync status per session. Handle partial failures gracefully — retry without duplication.
6. **Internationalisation.** All user-facing strings must be in i18n resource files. No hard-coded English strings. Support right-to-left layouts where required.

## Constraints

- The app must not transmit audio without a valid local consent event.
- Do not store API tokens in cleartext on device. Use secure keychain/keystore.
- Minimum Android API level and iOS version must be documented in `workstream-a/mobile/README.md`.
- Do not use deprecated React Native APIs or community packages with no active maintenance.
- UI must meet WCAG 2.1 AA contrast and tap-target size requirements.

## Expected Outputs

- React Native component and screen files.
- i18n resource file updates.
- Local data model definition.
- Unit tests for consent-gate logic and sync-queue conflict resolution.

## Acceptance Criteria

- [ ] App records and queues a session end-to-end in airplane mode.
- [ ] Consent gate prevents recording without a recorded consent event.
- [ ] Sync uploads queued sessions and reports status on reconnection.
- [ ] UI strings are externalised — no hard-coded visible text.
- [ ] Secure storage used for credentials.
- [ ] Tests pass for consent gate and offline queue.
