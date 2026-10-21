// Spec for the app's own native module (android/.../secure/AshaSecureModule.kt), ADR-017.
// Everything security-sensitive happens natively: random IDs, the PIN- and Keystore-wrapped
// database key, and audio capture. Nothing here returns a token, and no token is ever stored.
import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export type Recording = {
  /** WAV_PCM_S16LE, 16 kHz, mono, base64-encoded. */
  base64: string;
  seconds: number;
  sha256: string;
  size: number;
};

export type StoreInfo = {owner: string; createdAt: string; attemptsLeft: number};

export interface Spec extends TurboModule {
  getConstants(): {microphoneEnabled: boolean; storeDir: string};
  /** A stable, non-reversible label for a worker's store: SHA-256 of their subject. */
  ownerOf(subject: string): Promise<string>;
  randomHex(bytes: number): string;
  uuid(): string;
  listStores(): Promise<Array<StoreInfo>>;
  /** Creates a worker's store and returns its database key (hex). Rejects if one exists. */
  createStore(owner: string, pin: string): Promise<string>;
  /** Returns the database key, or rejects with WRONG_PIN (message: attempts left) or WIPED. */
  unlockStore(owner: string, pin: string): Promise<string>;
  removeStore(owner: string): Promise<void>;
  syntheticRecording(seconds: number): Promise<Recording>;
  startRecording(): Promise<void>;
  stopRecording(): Promise<Recording>;
  /** PUTs a recording's bytes with its checksum header; resolves with the HTTP status and body text. */
  uploadRecording(url: string, bearer: string, base64: string, sha256: string, correlationId: string): Promise<{
    status: number;
    body: string;
  }>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('AshaSecure');
