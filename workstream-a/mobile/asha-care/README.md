# ASHA Care: Android app (local test build)

**Synthetic data only.** This is the React Native version of the ASHA worker app (ADR-003, ADR-017). It follows the same visit flow, wording and API calls as the web app at `/asha/`.

## What it does

- **Offline-first.**
  - Every visit is saved on the phone before anything is sent.
  - The store is an SQLCipher database for each worker. Its key is wrapped twice: by the worker's PIN (PBKDF2-SHA-256) and by an Android Keystore key.
  - After 5 wrong PINs, that worker's data is deleted.
  - The app locks after 5 minutes idle, and whenever it goes to the background.
- **No stored tokens.**
  - Login is PKCE against Keycloak (client `wellness-mobile`), and tokens stay in memory.
  - The PIN unlocks offline work. A fresh login is needed only to sync, and only the worker who saved a visit can send it.
- **Outbox** (`src/core/outbox.ts`):
  - Progress is saved after every confirmed step, so a restart resumes where it stopped.
  - Retries reuse the same IDs and idempotency keys.
  - If a voice step is refused (for example, because consent was withdrawn), the recording is deleted and the rest of the visit is kept.
- **Voice**:
  - Recording happens only when the person agreed to the separate audio question on consent form 0.3.0.
  - Recordings are 16 kHz mono WAV, kept in the encrypted store.
  - A recording is deleted once the server confirms it stored exactly those bytes.
  - The worker never sees a result.
- **The microphone is off by default.** Builds then record a generated synthetic voice. Only a build made with `-PashaMicrophoneEnabled=true` uses the microphone, which needs privacy-lead approval. Those recordings are labelled `AUTHORIZED_TEST_AUDIO`, never `SYNTHETIC`.
- **Screen privacy:** screenshots are blocked and the recent-apps preview is blank (`FLAG_SECURE`). Nothing is backed up.

## Layout

| Path | What |
|---|---|
| `App.tsx` | Screens and app state |
| `src/core/visit.ts` | Visit model and the API calls it becomes (the consent gate is here) |
| `src/core/outbox.ts` | Sync rules (ADR-008) |
| `src/core/store.ts` | Encrypted store (op-sqlite with SQLCipher) |
| `src/core/auth.ts` | PKCE login (react-native-app-auth) |
| `src/native/NativeAshaSecure.ts` | Spec for the app's own native module |
| `android/.../secure/AshaSecureModule.kt` | Key wrapping, random IDs, recorder, upload |
| `src/i18n/strings.json` | Generated from the web app's `i18n.js` (`npm run strings`) |
| `__contract__/requests.json` | The app's request bodies, checked by `tests/contracts/test_mobile_requests.py` |

## Third-party runtime libraries

Both were reviewed and approved on 2026-10-20 and are pinned to exact versions:
- `@op-engineering/op-sqlite` 18.2.5, for SQLCipher
- `react-native-app-auth` 8.5.0, for PKCE login

Adding anything else needs the same review (see `.claude/agents/mobile-engineer.md`).

## Toolchain

Android only for now. iOS needs a newer Xcode than macOS 13 can run.

- Node 22 LTS
- JDK 17
- Android SDK platform 37.0, build-tools 37.0.0, NDK 27.1.12297006 and an emulator image

On the development Mac, Node and the JDK are in `~/.local/toolchains/`:

```bash
export PATH=~/.local/toolchains/node-v22.23.3-darwin-x64/bin:$PATH
export JAVA_HOME=$(ls -d ~/.local/toolchains/jdk-17*/Contents/Home)
export ANDROID_HOME=~/Library/Android/sdk
```

## Test

```bash
npm ci
npm run typecheck && npm run lint && npx jest
UPDATE_CONTRACT_SAMPLES=1 npx jest   # after changing a request body; then run the contract tests
```

## Run on the emulator against the local stack

1. Start the stack and the API as described in `deploy/local/README.md`.
2. Start an emulator, then make the host's ports reachable as `localhost` on the device, so token issuers match:

   ```bash
   adb reverse tcp:8080 tcp:8080
   adb reverse tcp:8081 tcp:8081
   ```

3. Run Metro on port 8088, because Keycloak already uses Metro's default 8081:

   ```bash
   adb reverse tcp:8088 tcp:8088
   npx react-native start --port 8088
   ```

4. In a second terminal, build and launch the app:

   ```bash
   npx react-native run-android --port 8088 --no-packager
   ```

   If the app looks for Metro on 8081, open the dev menu (⌘M in the emulator), choose **Settings → Debug server host** and enter `localhost:8088`.

5. Log in as `asha.test1` and set a PIN.

The emulator needs about 5 GB of memory. On a Mac with less than 24 GB, close other heavy apps first, or use a real phone over USB, which needs the same `adb reverse` commands.

Debug builds allow plain HTTP to the local stack. Release builds don't.
