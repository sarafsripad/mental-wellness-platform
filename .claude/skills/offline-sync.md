---
name: offline-sync
description: Design and implement offline-first data capture, queuing, deduplication, and synchronisation between the mobile client and the backend ingestion service.
---

# Offline Sync Skill

## Purpose

Implement reliable offline-first data capture and synchronisation for ASHA worker field sessions. Sessions captured without connectivity must be queued locally and synced without data loss or duplication when connectivity is restored.

## Prerequisites

- Read the mobile data model and sync queue implementation in `workstream-a/mobile/`.
- Read the ingestion service API contract in `contracts/` and `workstream-a/services/ingestion/`.
- Understand the consent model — consent events must be captured and stored locally before any audio recording begins.

## Workflow

1. **Define the local queue schema.** Each queued item must store: local ID, session data, consent event ID, creation timestamp, sync status (`PENDING`, `IN_FLIGHT`, `SYNCED`, `FAILED`), retry count, and last error.
2. **Implement idempotent submission.** Each session must have a client-generated idempotency key (UUID). The ingestion service must reject duplicates and return the existing record. The mobile client must treat a duplicate response as a success.
3. **Implement the sync worker.** Run as a background task when connectivity is detected. Process the queue in FIFO order. Mark items `IN_FLIGHT` while uploading. On success, mark `SYNCED`. On failure, increment retry count and apply exponential back-off.
4. **Implement conflict resolution.** If the same participant appears in two queued sessions with overlapping timestamps, surface a conflict in the clinician review interface rather than silently merging.
5. **Implement sync status UI.** Show per-session sync status in the mobile app. Allow manual retry of failed sessions. Prevent deletion of unsynced sessions.
6. **Implement encryption.** Audio and feature data must be encrypted at rest in the local queue using device-derived keys.

## Constraints

- The queue must survive app restarts and device reboots.
- Do not delete local queue items until the server confirms receipt (not just HTTP 200 — verify with an idempotency key round-trip).
- Maximum retry count must be configurable. After max retries, surface a manual-action alert, do not silently discard.
- No participant audio may be transmitted without a locally-stored consent event.

## Expected Outputs

- Local queue schema definition.
- Sync worker implementation.
- Idempotency key generation and validation logic.
- Tests: offline queue persistence, sync success, sync retry, duplicate rejection, conflict surfacing.

## Acceptance Criteria

- [ ] Session queued offline is successfully synced on reconnection.
- [ ] Duplicate submission returns existing record — no duplicate in the database.
- [ ] Queue survives app restart (data persisted to disk).
- [ ] Encrypted local store — plaintext audio not accessible on device without decryption key.
- [ ] Failed sessions surface an alert after max retries rather than being silently dropped.
- [ ] Tests pass for all sync states.
