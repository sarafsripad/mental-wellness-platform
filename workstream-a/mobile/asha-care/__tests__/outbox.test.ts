import {flush, NetworkError, retryDelay, type Deps, type Entry, type Response} from '../src/core/outbox';
import {operations} from '../src/core/visit';
import {completedVisit, fakeIds} from '../test-support/fixtures';

const ok = (data: any = {}): Response => ({status: 201, data});

function entry(audio: 'yes' | 'no'): Entry {
  return {id: 'ob_1', savedAt: '2026-10-20T08:10:00Z', ops: operations(completedVisit(fakeIds(), audio), '2026-10-20T08:10:00Z',
    'kn-IN', fakeIds()), next: 0, status: 'pending', problem: null, result: null, urgent: false};
}

function deps(answer: (op: any) => Response | Error, audioOnDevice = new Map([['rec', 'UklGRg==']])) {
  const sent: any[] = [];
  const persisted: Entry[] = [];
  const dropped: string[] = [];
  const d: Deps = {
    async send(op) {
      sent.push(op);
      const r = answer(op);
      if (r instanceof Error) throw r;
      return r;
    },
    audio: async () => audioOnDevice.get('rec'),
    dropAudio: async ids => { dropped.push(...ids); },
    persist: async e => { persisted.push(JSON.parse(JSON.stringify(e))); },
    online: () => true,
  };
  return {d, sent, persisted, dropped};
}

test('progress is saved after every confirmed step, and identity is dropped once stored', async () => {
  const e = entry('no');
  const {d, persisted} = deps(op => ok(op.operationId === 'createCase' ? {case_id: 'case_x'} : {}));
  await flush([e], d);
  expect(e.status).toBe('done');
  expect(persisted.map(p => p.next)).toEqual([1, 2, 3, 4, 5, 6, 6]);
  expect(persisted[2].ops[2].body).toBeNull(); // the name and village are gone after the identity step
  expect(e.ops.every(o => o.body === null)).toBe(true);
});

test('a network failure stops the sync and a retry resumes at the same step with the same key', async () => {
  const e = entry('no');
  let fail = true;
  const first = deps(op => (op.operationId === 'putParticipantIdentity' && fail ? new NetworkError('down') : ok()));
  await expect(flush([e], first.d)).rejects.toBeInstanceOf(NetworkError);
  expect(e.status).toBe('pending');
  expect(e.next).toBe(2);
  fail = false;
  const second = deps(() => ok());
  await flush([e], second.d);
  expect(second.sent[0].operationId).toBe('putParticipantIdentity');
  expect(e.status).toBe('done');
});

test('a refused required step marks the visit refused and drops its answers and audio', async () => {
  const e = entry('yes');
  const {d, dropped} = deps(op => (op.operationId === 'createCase' ? {status: 422, data: {code: 'CONSENT_NOT_ACTIVE'}} : ok()));
  await flush([e], d);
  expect(e.status).toBe('failed');
  expect(e.problem).toBe('case: CONSENT_NOT_ACTIVE');
  expect(e.ops.every(o => o.body === null)).toBe(true);
  expect(dropped).toHaveLength(2);
});

test('a refused voice step (withdrawal wins) deletes the audio but keeps the visit', async () => {
  const e = entry('yes');
  const {d, sent, dropped} = deps(op => (op.operationId === 'createVoiceSession' ? {status: 422, data: {code: 'CONSENT_NOT_ACTIVE'}} : ok()));
  await flush([e], d);
  expect(e.status).toBe('done');
  expect(e.voiceProblem).toBe('voice session: CONSENT_NOT_ACTIVE');
  expect(sent.some(o => o.audio)).toBe(false);
  expect(new Set(dropped).size).toBe(2);
});

test('audio leaves the device only when the server stored exactly those bytes', async () => {
  const e = entry('yes');
  const {d, dropped} = deps(op => (op.audio ? {status: 200, data: {upload_status: 'STORED',
    sha256: op.sha256 === 'a'.repeat(64) ? op.sha256 : 'c'.repeat(64)}} : ok()));
  e.status = 'pending';
  const steps = e.ops.length;
  // Stop before the final clean-up, so only the per-upload rule is observed.
  let calls = 0;
  const limited = {...d, online: () => ++calls <= steps - 1};
  await flush([e], limited);
  expect(dropped).toEqual([e.ops.find(o => o.sha256 === 'a'.repeat(64))!.audio]);
});

test('a recording missing on the device is a voice refusal, never a crash', async () => {
  const e = entry('yes');
  const {d} = deps(() => ok(), new Map());
  await flush([e], d);
  expect(e.status).toBe('done');
  expect(e.voiceProblem).toBe('audio upload: RECORDING_MISSING_ON_DEVICE');
});

test('retries back off from 30 seconds to at most 15 minutes', () => {
  expect([0, 1, 2, 3, 10].map(retryDelay)).toEqual([60000, 30000, 60000, 120000, 900000]);
});
