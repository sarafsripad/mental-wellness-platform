import {consentProblem, operations} from '../src/core/visit';
import {completedVisit, fakeIds} from '../test-support/fixtures';

describe('a visit becomes ordered API calls', () => {
  test('the same steps and order as the web app, identity after consent', () => {
    const ops = operations(completedVisit(fakeIds(), 'no'), '2026-10-20T08:10:00Z', 'kn-IN', fakeIds());
    expect(ops.map(o => o.operationId)).toEqual(['createConsentEvent', 'createCase', 'putParticipantIdentity',
      'submitObservations', 'submitQuestionnaireResponse', 'completeCase']);
    expect(ops.find(o => o.operationId === 'putParticipantIdentity')!.pii).toBe(true);
  });

  test('consent form 0.3.0 asks about voice recording separately', () => {
    const [consent] = operations(completedVisit(fakeIds(), 'no'), '2026-10-20T08:10:00Z', 'kn-IN', fakeIds());
    expect((consent.body as any).consent_form.form_version).toBe('0.3.0');
    expect((consent.body as any).scope_decisions).toContainEqual({scope: 'AUDIO_RECORDING', granted: false});
  });

  test('the consent gate: no voice steps unless the person agreed, even if recordings exist', () => {
    const v = completedVisit(fakeIds(), 'yes');
    expect(operations(v, '2026-10-20T08:10:00Z', 'kn-IN', fakeIds()).filter(o => o.optional)).toHaveLength(7);
    v.audio = 'no';
    expect(operations(v, '2026-10-20T08:10:00Z', 'kn-IN', fakeIds()).filter(o => o.optional)).toHaveLength(0);
  });

  test('voice steps come after the visit and are optional; uploads carry the checksum', () => {
    const ops = operations(completedVisit(fakeIds(), 'yes'), '2026-10-20T08:10:00Z', 'kn-IN', fakeIds());
    const first = ops.findIndex(o => o.optional);
    expect(ops[first - 1].operationId).toBe('completeCase');
    expect(ops.slice(first).every(o => o.optional)).toBe(true);
    const uploads = ops.filter(o => o.audio);
    expect(uploads.map(o => o.sha256)).toEqual(['a'.repeat(64), 'b'.repeat(64)]);
    expect((ops[first].body as any).data_origin).toBe('SYNTHETIC');
  });

  test('a session with any microphone recording is never labelled SYNTHETIC', () => {
    const v = completedVisit(fakeIds(), 'yes');
    v.recordings['counting-one-to-ten'].origin = 'AUTHORIZED_TEST_AUDIO';
    const session = operations(v, '2026-10-20T08:10:00Z', 'kn-IN', fakeIds()).find(o => o.operationId === 'createVoiceSession')!;
    expect((session.body as any).data_origin).toBe('AUTHORIZED_TEST_AUDIO');
  });

  test('every retry-able step has its own idempotency key, fixed at save time', () => {
    const ops = operations(completedVisit(fakeIds(), 'yes'), '2026-10-20T08:10:00Z', 'kn-IN', fakeIds());
    const keys = ops.filter(o => o.method === 'POST').map(o => o.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  test('consent checks match the web app', () => {
    const v = completedVisit(fakeIds(), 'yes');
    expect(consentProblem(v)).toBeNull();
    expect(consentProblem({...v, audio: ''})).toMatch(/voice recording/);
    expect(consentProblem({...v, witness: false})).toMatch(/witness/);
    expect(consentProblem({...v, adult: false})).toMatch(/adults/);
    expect(consentProblem({...v, consent: 'no'})).toBeNull();
  });
});
