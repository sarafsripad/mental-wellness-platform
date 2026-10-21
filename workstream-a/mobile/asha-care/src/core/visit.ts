// A household visit and the ordered API calls it becomes (ADR-008, ADR-017). Same steps, IDs and
// rules as the web app (resources/asha/app.js): consent, case, identity, observations,
// questionnaire, completion, then, only with consent to record, the voice steps.

export const CONSENT_FORM = {form_id: 'asha-wellness-consent', form_version: '0.3.0'};
export const INSTRUMENT = {instrument_id: 'illustrative-wellness', instrument_version: '0.1.0'};
export const CATALOGUE = {catalogue_id: 'asha-observations', catalogue_version: '0.1.0'};
export const PROMPT_SET = {prompt_set_id: 'baseline-voice-tasks', prompt_set_version: '0.1.0'};
export const PIPELINE_PROFILE = 'EGEMAPS_V02_FUNCTIONALS_V1';
export const APP_VERSION = '0.3.0';
export const URGENT = ['SOMETIMES', 'OFTEN'];

export type Ids = {randomId(prefix: string): string; uuid(): string};

export type RecordingRef = {
  recordingId: string;
  seconds: number;
  sha256: string;
  size: number;
  recordedAt: string;
  origin: 'SYNTHETIC' | 'AUTHORIZED_TEST_AUDIO';
};

export type Visit = {
  ids: {participant: string; consent: string; case: string; obs: string; qr: string; session?: string};
  page?: string;
  name: string;
  village: string;
  age: string;
  gender: string;
  consent: '' | 'yes' | 'no';
  method: string;
  readAloud: boolean;
  understood: boolean;
  witness: boolean;
  adult: boolean;
  sharing: '' | 'yes' | 'no';
  audio: '' | 'yes' | 'no';
  recordings: Record<string, RecordingRef>;
  consentAt: string | null;
  health: string[];
  anxiety: string;
  sadness: string;
  interest: string;
  tobacco: string;
  alcohol: string;
  sleep: string;
  lifestyleAt: string | null;
  safety: string;
  safetyAt: string | null;
  followup: boolean;
};

export type Op = {
  what: string;
  operationId: string;
  method: 'POST' | 'PUT';
  path: string;
  key?: string;
  body: unknown;
  /** Identity: dropped from storage as soon as it is stored. */
  pii?: boolean;
  /** Voice steps: a refusal deletes the audio but keeps the visit. */
  optional?: boolean;
  /** Binary upload of this recording ID. */
  audio?: string;
  sha256?: string;
};

export function newVisit(ids: Ids): Visit {
  return {
    ids: {participant: ids.randomId('ppt'), consent: ids.randomId('ce'), case: ids.randomId('case'), obs: ids.randomId('obs'),
      qr: ids.randomId('qr')},
    name: '', village: '', age: '', gender: 'NOT_STATED',
    consent: '', method: 'VERBAL_WITNESSED', readAloud: false, understood: false, witness: false, adult: false,
    sharing: '', audio: '', recordings: {}, consentAt: null,
    health: [], anxiety: '', sadness: '', interest: '', tobacco: 'NOT_REPORTED', alcohol: 'NOT_REPORTED', sleep: '',
    lifestyleAt: null, safety: '', safetyAt: null, followup: true,
  };
}

/** The ordered calls for one visit. IDs and idempotency keys are fixed now, so every retry is safe. */
export function operations(v: Visit, savedAt: string, languageTag: string, ids: Ids): Op[] {
  const observations = [
    ...v.health.map(code => ({code: 'HEALTH_CONDITION', value_code: code})),
    {code: 'TOBACCO_USE', value_code: v.tobacco},
    {code: 'ALCOHOL_USE', value_code: v.alcohol},
    ...(v.sleep ? [{code: 'SLEEP_QUALITY', value_code: v.sleep}] : []),
  ];
  const items = ([['ANXIETY', v.anxiety], ['LOW_MOOD', v.sadness], ['INTEREST', v.interest], ['SELF_HARM_THOUGHTS', v.safety]] as const)
    .filter(([, answer]) => answer)
    .map(([item_id, answer_code]) => ({item_id, answer_code}));
  const caseId = v.ids.case;
  return [
    {what: 'consent', operationId: 'createConsentEvent', method: 'POST', path: '/v1/consent-events', key: ids.uuid(), body: {
      consent_event_id: v.ids.consent, participant_ref: v.ids.participant,
      consent_form: {...CONSENT_FORM, language_tag: languageTag},
      scope_decisions: [{scope: 'SCREENING_PARTICIPATION', granted: true},
        {scope: 'EXTERNAL_REFERRAL_SHARING', granted: v.sharing === 'yes'},
        {scope: 'AUDIO_RECORDING', granted: v.audio === 'yes'}],
      capture: {captured_at_utc: v.consentAt, method: v.method, read_aloud: v.readAloud, comprehension_confirmed: true,
        witness_present: v.witness},
      safeguards: {adult_confirmed: v.adult}}},
    {what: 'case', operationId: 'createCase', method: 'POST', path: '/v1/cases', key: ids.uuid(), body: {
      case_id: caseId, participant_ref: v.ids.participant, consent_event_id: v.ids.consent, language_tag: languageTag,
      opened_at_utc: v.consentAt, data_origin: 'SYNTHETIC', age_band: v.age, gender: v.gender}},
    // Identity goes last of the enrolment steps, only once consent is on record (ADR-013).
    {what: 'identity', operationId: 'putParticipantIdentity', method: 'PUT', path: `/v1/participants/${v.ids.participant}/identity`,
      pii: true, body: {consent_event_id: v.ids.consent, data_origin: 'SYNTHETIC', display_name: v.name.trim(),
        village_name: v.village.trim()}},
    {what: 'observations', operationId: 'submitObservations', method: 'POST', path: `/v1/cases/${caseId}/observations`,
      key: ids.uuid(), body: {observation_set_id: v.ids.obs, catalogue: CATALOGUE, observed_at_utc: v.lifestyleAt, observations}},
    {what: 'questionnaire', operationId: 'submitQuestionnaireResponse', method: 'POST',
      path: `/v1/cases/${caseId}/questionnaire-responses`, key: ids.uuid(),
      body: {response_id: v.ids.qr, instrument: INSTRUMENT, language_tag: languageTag, answered_at_utc: v.safetyAt, items}},
    {what: 'completion', operationId: 'completeCase', method: 'POST', path: `/v1/cases/${caseId}/completion`, key: ids.uuid(),
      body: {completed_at_utc: savedAt, follow_up_requested: v.followup}},
    ...voiceOperations(v, languageTag, ids),
  ];
}

/** Voice steps exist only when the person agreed to recording: the consent gate. */
export function voiceOperations(v: Visit, languageTag: string, ids: Ids): Op[] {
  const recs = Object.entries(v.recordings);
  if (v.audio !== 'yes' || !recs.length) return [];
  const sessionId = v.ids.session || ids.randomId('sess');
  const ops: Op[] = [{what: 'voice session', operationId: 'createVoiceSession', optional: true, method: 'POST',
    path: '/v1/voice-sessions', key: ids.uuid(), body: {
      session_id: sessionId, participant_ref: v.ids.participant, consent_event_id: v.ids.consent, language_tag: languageTag,
      prompt_set: PROMPT_SET, captured_at_utc: recs.map(([, r]) => r.recordedAt).sort()[0],
      device: {device_class: 'ANDROID_PHONE', app_version: APP_VERSION, os_family: 'ANDROID'},
      // A real voice is never labelled SYNTHETIC: any microphone recording makes the session test audio.
      data_origin: recs.some(([, r]) => r.origin !== 'SYNTHETIC') ? 'AUTHORIZED_TEST_AUDIO' : 'SYNTHETIC'}}];
  for (const [promptId, r] of recs) {
    ops.push(
      {what: 'recording', operationId: 'submitRecordingMetadata', optional: true, method: 'POST',
        path: `/v1/voice-sessions/${sessionId}/recordings`, key: ids.uuid(), body: {
          recording_id: r.recordingId, prompt_id: promptId, format: 'WAV_PCM_S16LE', sampling_rate_hz: 16000, channels: 1,
          duration_seconds: r.seconds, size_bytes: r.size, sha256: r.sha256, recorded_at_utc: r.recordedAt}},
      {what: 'audio upload', operationId: 'uploadRecordingContent', optional: true, method: 'PUT', audio: r.recordingId,
        sha256: r.sha256, path: `/v1/voice-sessions/${sessionId}/recordings/${r.recordingId}/content`, body: null},
      {what: 'processing', operationId: 'submitProcessingJob', optional: true, method: 'POST', path: '/v1/processing-jobs',
        key: ids.uuid(), body: {recording_id: r.recordingId, pipeline_profile: PIPELINE_PROFILE}});
  }
  return ops;
}

/** What the worker must have recorded before the visit can continue past consent. Null when complete. */
export function consentProblem(v: Visit): string | null {
  if (!v.consent) return "Please record the person's choice.";
  if (v.consent === 'no') return null;
  if (!v.understood) return "Consent needs the person's confirmed understanding.";
  if (!v.adult) return 'Only adults can be assessed. Use an approved child pathway for people under 18.';
  if (!v.sharing) return "Please record the person's choice about sharing a referral.";
  if (!v.audio) return "Please record the person's choice about a voice recording.";
  if (v.method === 'VERBAL_WITNESSED' && !v.witness) return 'Verbal consent needs a witness.';
  return null;
}
