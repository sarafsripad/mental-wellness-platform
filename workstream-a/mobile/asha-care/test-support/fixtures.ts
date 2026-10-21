import {newVisit, type Ids, type Visit} from '../src/core/visit';

/** Deterministic IDs, so generated requests are stable and comparable. */
export function fakeIds(): Ids {
  let n = 0;
  const next = () => (++n).toString(16).padStart(32, '0');
  return {
    randomId: (prefix: string) => `${prefix}_${next()}`,
    uuid: () => {
      const h = next();
      return `${h.slice(0, 8)}-${h.slice(8, 12)}-4${h.slice(13, 16)}-8${h.slice(17, 20)}-${h.slice(20, 32)}`;
    },
  };
}

export function completedVisit(ids: Ids, audio: 'yes' | 'no'): Visit {
  const v = newVisit(ids);
  Object.assign(v, {
    name: 'Synthetic Mobile Alpha', village: 'Testgrama-90', age: 'AGE_30_44', gender: 'FEMALE',
    consent: 'yes', readAloud: true, understood: true, witness: true, adult: true, sharing: 'no', audio,
    consentAt: '2026-10-20T08:00:00Z', health: ['NONE_REPORTED'], anxiety: 'NOT_AT_ALL', sadness: 'SEVERAL_DAYS',
    interest: 'AS_USUAL', sleep: 'GOOD', lifestyleAt: '2026-10-20T08:05:00Z', safety: 'NO', safetyAt: '2026-10-20T08:06:00Z',
  });
  if (audio === 'yes') {
    v.ids.session = ids.randomId('sess');
    v.recordings = {
      'sustained-vowel-a': {recordingId: ids.randomId('rec'), seconds: 6, sha256: 'a'.repeat(64), size: 192044,
        recordedAt: '2026-10-20T08:07:00Z', origin: 'SYNTHETIC'},
      'counting-one-to-ten': {recordingId: ids.randomId('rec'), seconds: 6, sha256: 'b'.repeat(64), size: 192044,
        recordedAt: '2026-10-20T08:08:00Z', origin: 'SYNTHETIC'},
    };
  }
  return v;
}
