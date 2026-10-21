// The outbox (ADR-008, ADR-017): sends saved visits in order, step by step, and saves progress after
// every confirmed step, so a restart resumes exactly where it stopped. Retries reuse the same IDs and
// idempotency keys. A refusal of a required step marks the visit refused; a refusal of a voice step
// deletes the audio and keeps the visit (withdrawal always wins).
import type {Op} from './visit';

export type Entry = {
  id: string;
  savedAt: string;
  ops: Op[];
  next: number;
  status: 'pending' | 'done' | 'failed';
  problem: string | null;
  voiceProblem?: string;
  result: Record<string, unknown> | null;
  card?: string;
  urgent: boolean;
};

export class NetworkError extends Error {}
export class SessionError extends Error {}

export type Response = {status: number; data: any};

export type Deps = {
  /** Sends one step: JSON for ordinary steps, the recording's bytes for an upload. */
  send(op: Op, audioBase64?: string): Promise<Response>;
  audio(id: string): Promise<string | undefined>;
  dropAudio(ids: string[]): Promise<void>;
  persist(entry: Entry): Promise<void>;
  online(): boolean;
};

export const audioIds = (entry: Entry) => entry.ops.filter(o => o.audio).map(o => o.audio as string);

const problemOf = (op: Op, r: Response) => (r.data && r.data.code ? `${op.what}: ${r.data.code}` : `${op.what}: HTTP ${r.status}`);

/** Sends pending entries. Throws NetworkError (stay pending, retry later) or SessionError (log in again). */
export async function flush(outbox: Entry[], deps: Deps): Promise<void> {
  for (const entry of outbox) {
    if (entry.status !== 'pending') continue;
    while (entry.next < entry.ops.length) {
      if (!deps.online()) return;
      const op = entry.ops[entry.next];
      let r: Response;
      if (op.audio) {
        const bytes = await deps.audio(op.audio);
        if (!bytes) {
          r = {status: 0, data: {code: 'RECORDING_MISSING_ON_DEVICE'}};
        } else {
          r = await deps.send(op, bytes);
        }
      } else {
        r = await deps.send(op);
      }
      if (r.status >= 200 && r.status < 300) {
        if (op.pii) op.body = null; // the name and village leave storage as soon as they are stored
        if (r.data && r.data.case_id) entry.result = r.data;
        if (r.data && r.data.patient_card_code) entry.card = r.data.patient_card_code;
        // The audio leaves the device only once the server has stored exactly these bytes.
        if (op.audio && r.data && r.data.upload_status === 'STORED' && r.data.sha256 === op.sha256) {
          await deps.dropAudio([op.audio]);
        }
        entry.next += 1;
        await deps.persist(entry);
      } else if (op.optional) {
        entry.voiceProblem = problemOf(op, r);
        await deps.dropAudio(audioIds(entry));
        for (const o of entry.ops.slice(entry.next)) o.body = null;
        entry.next = entry.ops.length;
        await deps.persist(entry);
      } else {
        entry.status = 'failed';
        entry.problem = problemOf(op, r);
        for (const o of entry.ops) o.body = null; // a refused visit is not retried, so drop its answers
        await deps.dropAudio(audioIds(entry));
        await deps.persist(entry);
        break;
      }
    }
    if (entry.status === 'pending') {
      entry.status = 'done';
      for (const op of entry.ops) op.body = null;
      await deps.dropAudio(audioIds(entry));
      await deps.persist(entry);
    }
  }
}

/** Retry delay after `failures` consecutive network failures: 30 s doubling to 15 minutes. */
export function retryDelay(failures: number): number {
  return failures ? Math.min(15 * 60000, 30000 * 2 ** (failures - 1)) : 60000;
}
