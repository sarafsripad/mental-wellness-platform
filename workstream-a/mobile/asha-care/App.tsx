// ASHA Care — Android app (local test build, ADR-017). Synthetic data only.
// Offline-first: every visit is saved to the worker's encrypted store (SQLCipher, key unwrapped by
// the PIN and the Android Keystore) and sent by the outbox when a login and a connection exist.
// Same flow, wording, IDs and API calls as the web app. Tokens live in memory only.
import React, {createContext, useCallback, useContext, useEffect, useRef, useState} from 'react';
import {
  Alert,
  AppState,
  PermissionsAndroid,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import {fresh, login, type Auth} from './src/core/auth';
import {API} from './src/core/config';
import {ids, native, settings} from './src/core/native';
import {audioIds, flush, NetworkError, retryDelay, SessionError, type Entry, type Response} from './src/core/outbox';
import {createStore, listStores, MAX_ATTEMPTS, PIN_PATTERN, removeStore, unlockStore, WrongPin, Wiped,
  type Session} from './src/core/store';
import {consentProblem, newVisit, operations, URGENT, type Op, type RecordingRef, type Visit} from './src/core/visit';
import {languageTag, LANGUAGES, t} from './src/i18n';

type Page = 'language' | 'login' | 'unlock' | 'setPin' | 'home' | 'list' | 'registration' | 'consent' | 'declined' |
  'health' | 'wellness' | 'lifestyle' | 'safety' | 'voice' | 'summary' | 'complete';
type StoreInfo = {owner: string; createdAt: string; attemptsLeft: number};

const VISIT_PAGES: Page[] = ['registration', 'consent', 'health', 'wellness', 'lifestyle', 'safety', 'voice', 'summary'];
const IDLE_LOCK_MS = 5 * 60 * 1000;
const STALE_MS = 3 * 24 * 3600 * 1000;
const SYNTHETIC_SECONDS = 6;

const AGE = [['AGE_UNDER_18', 'Under 18 — use an approved child pathway'], ['AGE_18_29', '18–29'], ['AGE_30_44', '30–44'],
  ['AGE_45_59', '45–59'], ['AGE_60_PLUS', '60+']];
const GENDER = [['NOT_STATED', 'Prefer not to say'], ['FEMALE', 'Female'], ['MALE', 'Male'], ['OTHER', 'Other']];
const HEALTH = [['HYPERTENSION', 'Hypertension'], ['DIABETES', 'Diabetes'], ['HEART_DISEASE', 'Heart disease'],
  ['BREATHING_PROBLEM', 'Asthma or breathing problems'], ['OTHER_LONG_TERM_ILLNESS', 'Other long-term illness'],
  ['NONE_REPORTED', 'None reported'], ['DECLINED', 'Prefer not to answer']];
const EXCLUSIVE_HEALTH = ['NONE_REPORTED', 'DECLINED'];
const FREQUENCY = [['NOT_AT_ALL', 'Not at all'], ['SEVERAL_DAYS', 'Several days'],
  ['MORE_THAN_HALF_THE_DAYS', 'More than half the days'], ['NEARLY_EVERY_DAY', 'Nearly every day'],
  ['DECLINED', 'Prefer not to answer']];
const INTEREST = [['AS_USUAL', 'Yes, as usual'], ['LESS_THAN_BEFORE', 'Less than before'], ['NOT_AT_ALL', 'Not at all'],
  ['DECLINED', 'Prefer not to answer']];
const TOBACCO = [['NOT_REPORTED', 'Not reported'], ['REPORTED', 'Person reports tobacco use'], ['DECLINED', 'Prefer not to answer']];
const ALCOHOL = [['NOT_REPORTED', 'Not reported'], ['REPORTED', 'Person reports alcohol use'], ['DECLINED', 'Prefer not to answer']];
const SLEEP = [['GOOD', 'Good'], ['SOME_DIFFICULTY', 'Some difficulty'], ['VERY_POOR', 'Very poor'], ['DECLINED', 'Prefer not to answer']];
const SAFETY = [['NO', 'No'], ['SOMETIMES', 'Yes, sometimes'], ['OFTEN', 'Yes, often'], ['DECLINED', 'Prefer not to answer']];
const CONSENT_METHOD = [['VERBAL_WITNESSED', 'Verbal, with a witness'], ['SIGNED_ON_DEVICE', 'Signed on this device'],
  ['THUMBPRINT_ON_DEVICE', 'Thumbprint on this device']];
const PROMPTS = [['sustained-vowel-a', 'Ask the person to say “aaah” for as long as is comfortable.'],
  ['counting-one-to-ten', 'Ask the person to count slowly from one to ten.']];
const TASK_TEXT: Record<string, string> = {HOME_VISIT: 'Home visit', CHECK_IN_CALL: 'Check-in call',
  ACCOMPANY_TO_FACILITY: 'Accompany to the facility', TREATMENT_ADHERENCE_CHECK: 'Check treatment is being followed'};

// Keyboards must not learn or suggest beneficiary names (review W3).
const NO_LEARNING = {autoComplete: 'off', autoCorrect: false, spellCheck: false, importantForAutofill: 'no'} as const;

const isoNow = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');

export default function App() {
  const [lang, setLang] = useState('en');
  const [page, setPageState] = useState<Page>('language');
  const [message, setMessage] = useState<string | null>(null);
  const [auth, setAuth] = useState<Auth | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [stores, setStores] = useState<StoreInfo[]>([]);
  const [visit, setVisitState] = useState<Visit | null>(null);
  const [outbox, setOutbox] = useState<Entry[]>([]);
  const [forcedOffline, setForcedOffline] = useState(false);
  const [listing, setListing] = useState<{followUps: boolean; cases: any[]; tasks: any[]; error?: string} | null>(null);
  // The prompt being recorded and the visit it belongs to; a recording never outlives its screen (review B1).
  const [recording, setRecordingState] = useState<{promptId: string; participant: string} | null>(null);

  // Refs so the outbox, timers and lock always see the latest state.
  const s = useRef({auth, session, visit, outbox, forcedOffline, flushing: false, failures: 0,
    recording: null as {promptId: string; participant: string} | null, lockAfterSync: null as string | null});
  s.current.auth = auth;
  s.current.session = session;
  s.current.visit = visit;
  s.current.outbox = outbox;
  s.current.forcedOffline = forcedOffline;
  const idle = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retry = useRef<ReturnType<typeof setTimeout> | null>(null);

  const tr = (text: string) => t(lang, text);

  const setRecording = (r: {promptId: string; participant: string} | null) => {
    s.current.recording = r;
    setRecordingState(r);
  };
  /** Stops a running recording and throws its samples away. */
  const cancelRecording = async () => {
    if (!s.current.recording) return;
    setRecording(null);
    await native.stopRecording().catch(() => undefined);
  };

  const persistVisit = useCallback(async (v: Visit | null) => {
    const sess = s.current.session;
    if (!sess) return;
    try {
      if (v) await sess.put('visit', v);
      else await sess.delete('visit');
    } catch (e: any) {
      setMessage(`Could not save on this device (${e.message}).`);
    }
  }, []);

  const setVisit = (v: Visit | null) => {
    setVisitState(v);
    s.current.visit = v;
    persistVisit(v);
  };
  const update = (patch: Partial<Visit>) => setVisit({...(s.current.visit as Visit), ...patch});

  const go = (p: Page, msg: string | null = null) => {
    if (p !== 'voice') cancelRecording();
    const v = s.current.visit;
    if (v && VISIT_PAGES.includes(p)) setVisit({...v, page: p}); // a restored visit reopens where it was left
    setPageState(p);
    setMessage(msg);
  };
  const warn = (text: string) => setMessage(text);

  // --- API ---------------------------------------------------------------------------------------

  const currentAuth = async (): Promise<Auth> => {
    const a = s.current.auth;
    if (!a) throw new SessionError('not logged in');
    const next = await fresh(a);
    if (next !== a) {
      s.current.auth = next;
      setAuth(next);
    }
    return next;
  };

  const call = async (method: string, path: string, body?: unknown, key?: string): Promise<Response> => {
    const a = await currentAuth();
    const headers: Record<string, string> = {Authorization: `Bearer ${a.token}`, 'X-Correlation-ID': ids.uuid()};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    let r;
    try {
      r = await fetch(API + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
    } catch {
      throw new NetworkError('network unavailable');
    }
    const text = await r.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = null;
    }
    return {status: r.status, data};
  };

  const send = async (op: Op, audioBase64?: string): Promise<Response> => {
    if (!audioBase64) return call(op.method, op.path, op.body, op.key);
    const a = await currentAuth();
    try {
      const r = await native.uploadRecording(API + op.path, a.token, audioBase64, op.sha256 as string, ids.uuid());
      let data = null;
      try {
        data = r.body ? JSON.parse(r.body) : null;
      } catch {
        data = null;
      }
      return {status: r.status, data};
    } catch {
      throw new NetworkError('network unavailable');
    }
  };

  const dropAudio = async (recordingIds: string[]) => {
    const sess = s.current.session;
    if (!sess) return;
    for (const id of recordingIds) await sess.deleteAudio(id).catch(() => undefined);
  };

  const pending = outbox.filter(e => e.status === 'pending').length;

  const sync = useCallback(async () => {
    const {session: sess, auth: a, forcedOffline: off} = s.current;
    if (s.current.flushing || !sess) return;
    if (!a) return warn('Log in to sync. Your visits stay on this device until then.');
    // Only the worker who saved a visit may send it: the store is theirs, and so are the IDs.
    if (a.owner !== sess.owner) return warn('These offline visits belong to another worker. Log in as that worker to sync them.');
    if (off) return warn('No connection. Records remain pending sync.');
    s.current.flushing = true;
    try {
      await flush(s.current.outbox, {
        send,
        audio: id => sess.getAudio(id),
        dropAudio,
        persist: entry => sess.put('entry:' + entry.id, entry),
        online: () => !s.current.forcedOffline && !s.current.lockAfterSync,
      });
      s.current.failures = 0;
    } catch (e: any) {
      if (e instanceof NetworkError) s.current.failures += 1;
      else if (e instanceof SessionError) warn('Your session has expired. Log in again to sync; your visits stay on this device.');
      else warn(`Sync stopped: ${e.message}`);
    } finally {
      s.current.flushing = false;
      setOutbox([...s.current.outbox]);
      if (s.current.lockAfterSync) {
        const why = s.current.lockAfterSync;
        s.current.lockAfterSync = null;
        lock(why);
        return;
      }
      if (retry.current) clearTimeout(retry.current);
      if (s.current.outbox.some(e => e.status === 'pending') && s.current.auth) {
        retry.current = setTimeout(sync, retryDelay(s.current.failures));
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // --- store: unlock, lock, create ---------------------------------------------------------------

  const loadStore = async (sess: Session) => {
    s.current.session = sess;
    setSession(sess);
    const entries = (await sess.all<Entry>('entry:')).map(([, e]) => e);
    const keep: Entry[] = [];
    for (const e of entries) {
      if (e.status !== 'pending' && Date.now() - Date.parse(e.savedAt) > 7 * 24 * 3600 * 1000) await sess.delete('entry:' + e.id);
      else keep.push(e);
    }
    keep.sort((a, b) => a.savedAt.localeCompare(b.savedAt));
    s.current.outbox = keep;
    setOutbox(keep);
    const v = (await sess.get<Visit>('visit')) || null;
    s.current.visit = v;
    setVisitState(v);
    // Audio saved just before a crash may have no visit or entry pointing at it (review I2).
    const referenced = new Set([...keep.filter(e => e.status === 'pending').flatMap(audioIds),
      ...Object.values((v && v.recordings) || {}).map(r => r.recordingId)]);
    for (const id of await sess.audioIds()) if (!referenced.has(id)) await sess.deleteAudio(id);
    touch();
    setPageState(v ? ((v.page as Page) || 'registration') : 'home');
    setMessage(null);
    if (s.current.auth && keep.some(e => e.status === 'pending')) sync();
  };

  const lock = useCallback(async (why: string) => {
    const sess = s.current.session;
    if (!sess) return;
    if (s.current.flushing) {
      s.current.lockAfterSync = why; // locks as soon as the current sync step is confirmed (review I7)
      return;
    }
    await cancelRecording();
    await persistVisit(s.current.visit);
    sess.close();
    s.current.session = null;
    setSession(null);
    setVisitState(null);
    s.current.visit = null;
    setOutbox([]);
    s.current.outbox = [];
    setStores(await listStores());
    setPageState('unlock');
    setMessage(why);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function touch() {
    if (idle.current) clearTimeout(idle.current);
    if (s.current.session) idle.current = setTimeout(() => lock('Locked after 5 minutes without use. Enter your PIN to continue.'), IDLE_LOCK_MS);
  }

  useEffect(() => {
    listStores().then(setStores).catch(() => setStores([]));
    // Leaving the app locks it at once: names and answers are never left open in the background.
    const sub = AppState.addEventListener('change', state => {
      if (state !== 'active') lock('Locked while the app was in the background. Enter your PIN to continue.');
    });
    return () => sub.remove();
  }, [lock]);

  const doLogin = async () => {
    if (s.current.session) await persistVisit(s.current.visit);
    try {
      const a = await login();
      if (!a.roles.includes('ASHA_WORKER')) return warn('This account does not have the ASHA_WORKER role, so it cannot record assessments.');
      s.current.auth = a;
      setAuth(a);
      const all = await listStores();
      setStores(all);
      if (s.current.session && s.current.session.owner === a.owner) {
        setPageState('home');
        setMessage(null);
        sync();
      } else if (s.current.session) {
        await lock('Another worker logged in. Each worker unlocks only their own visits.');
      } else {
        go(all.some(m => m.owner === a.owner) ? 'unlock' : 'setPin');
      }
    } catch (e: any) {
      warn(`Login failed: ${e.message}`);
    }
  };

  // --- visits ------------------------------------------------------------------------------------

  const save = async () => {
    await cancelRecording();
    const v = s.current.visit as Visit;
    const sess = s.current.session as Session;
    const savedAt = isoNow();
    const entry: Entry = {id: ids.randomId('ob'), savedAt, ops: operations(v, savedAt, languageTag(lang), ids), next: 0,
      status: 'pending', problem: null, result: null, urgent: URGENT.includes(v.safety)};
    await sess.put('entry:' + entry.id, entry);
    const next = [...s.current.outbox, entry];
    s.current.outbox = next;
    setOutbox(next);
    setVisit(null);
    setLastEntry(entry.id);
    go('complete');
    if (s.current.auth && !s.current.forcedOffline) sync();
  };
  const [lastEntry, setLastEntry] = useState<string | null>(null);

  const discardVisit = async () => {
    await cancelRecording();
    const v = s.current.visit;
    if (v) await dropAudio(Object.values(v.recordings).map(r => r.recordingId));
    setVisit(null);
  };

  const record = async (promptId: string) => {
    const v = s.current.visit as Visit;
    const sess = s.current.session as Session;
    let rec;
    try {
      if (!settings.microphoneEnabled) {
        rec = await native.syntheticRecording(SYNTHETIC_SECONDS);
      } else if (recording && recording.promptId === promptId && recording.participant === v.ids.participant) {
        rec = await native.stopRecording();
        setRecording(null);
        if (!s.current.visit || s.current.visit.ids.participant !== v.ids.participant || v.audio !== 'yes') return;
      } else {
        if (recording) return warn('Stop the other recording first.');
        const granted = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO);
        if (granted !== PermissionsAndroid.RESULTS.GRANTED) return warn('The microphone permission was refused.');
        await native.startRecording();
        setRecording({promptId, participant: v.ids.participant});
        return;
      }
    } catch (e: any) {
      return warn(`The recording failed (${e.message}).`);
    }
    if (rec.seconds < 3 || rec.seconds > 180) return warn('The recording must be 3 to 180 seconds long.');
    const previous = v.recordings[promptId];
    if (previous) await sess.deleteAudio(previous.recordingId);
    const origin: RecordingRef['origin'] = settings.microphoneEnabled ? 'AUTHORIZED_TEST_AUDIO' : 'SYNTHETIC';
    // One session has one data origin, so a visit never mixes synthetic and real recordings (review B2).
    const kept = Object.fromEntries(Object.entries(v.recordings).filter(([id, r]) => id !== promptId && r.origin === origin));
    for (const [id, r] of Object.entries(v.recordings)) {
      if (id !== promptId && r.origin !== origin) await sess.deleteAudio(r.recordingId);
    }
    if (Object.keys(kept).length !== Object.keys(v.recordings).filter(id => id !== promptId).length) {
      warn('The microphone setting changed, so the earlier recordings for this visit were deleted. Record them again.');
    }
    const recordingId = ids.randomId('rec');
    await sess.putAudio(recordingId, rec.base64);
    const next = {...v, ids: {...v.ids, session: v.ids.session || ids.randomId('sess')}, recordings: {...kept, [promptId]: {
      recordingId, seconds: rec.seconds, sha256: rec.sha256, size: rec.size, recordedAt: isoNow(), origin}}};
    setVisitState(next);
    s.current.visit = next;
    await persistVisit(next); // awaited, so the visit points at its audio before anything else happens (review I2)
  };

  const openList = async (followUps: boolean) => {
    setListing(null);
    go('list');
    try {
      const r = await call('GET', `/v1/cases?limit=50${followUps ? '&follow_up=true' : ''}`);
      const tasks = followUps ? await call('GET', '/v1/care-tasks?status=OPEN') : null;
      setListing({followUps, cases: r.status === 200 ? r.data.cases : [], tasks: tasks && tasks.status === 200 ? tasks.data.tasks : [],
        error: r.status === 200 ? undefined : `Could not load visits (${(r.data && r.data.code) || r.status}).`});
    } catch (e: any) {
      setListing({followUps, cases: [], tasks: [], error: e instanceof SessionError ? 'Log in to see your visits.'
        : 'Connect to the internet to see your visits.'});
    }
  };

  // --- rendering ---------------------------------------------------------------------------------

  function screen(): React.ReactNode {
    const v = visit as Visit;
    switch (page) {
      case 'language':
        return (
          <>
            <Choice options={LANGUAGES.map(([code, , label]) => [code, label])} value={lang} onChange={setLang} />
            <P small>Translations other than English are unreviewed prototype translations.</P>
            <Button label="Continue →" onPress={() => go(session ? 'home' : stores.length ? 'unlock' : 'login')} />
          </>
        );
      case 'login':
        return (
          <>
            <P tone="notice">Log in with your synthetic test worker account (for example asha.test1).</P>
            <Button label="Enter demo app →" onPress={doLogin} />
          </>
        );
      case 'setPin':
        return <SetPin warn={warn} onCreate={async pin => {
          try {
            await loadStore(await createStore((auth as Auth).owner, pin));
            setStores(await listStores());
          } catch (e: any) {
            warn(`The PIN was not set: ${e.message}`);
          }
        }} />;
      case 'unlock':
        return <Unlock mine={auth ? stores.filter(m => m.owner === auth.owner) : stores} loggedIn={!!auth}
          onLogin={() => (auth ? go('setPin') : go('login'))}
          onUnlock={async (owner, pin) => {
            try {
              await loadStore(await unlockStore(owner, pin));
            } catch (e: any) {
              if (e instanceof WrongPin) return warn(`Wrong PIN. ${e.remaining} attempt(s) left before this worker's offline data is deleted.`);
              const all = await listStores();
              setStores(all);
              if (e instanceof Wiped) return go(all.length ? 'unlock' : auth ? 'setPin' : 'login', e.message);
              warn(`Could not unlock: ${e.message}`);
            }
          }}
          onDelete={async owner => {
            await removeStore(owner);
            const all = await listStores();
            setStores(all);
            go(all.length ? 'unlock' : auth ? 'setPin' : 'login');
          }} />;
      case 'home': {
        const stale = outbox.filter(e => e.status === 'pending' && Date.now() - Date.parse(e.savedAt) > STALE_MS).length;
        const failed = outbox.filter(e => e.status === 'failed');
        const lostUrgent = outbox.filter(e => e.urgent && e.status !== 'done').length;
        return (
          <>
            <Button label="New assessment" onPress={async () => { await discardVisit(); setVisit(newVisit(ids)); go('registration'); }} />
            <Button label="My households" secondary onPress={() => openList(false)} />
            <Button label="Follow-ups" secondary onPress={() => openList(true)} />
            <Button label={`${tr('Sync data')} · ${tr('Pending records:')} ${pending}`} secondary
              onPress={() => (auth ? sync() : doLogin())} />
            <Check label="Work offline (test: treat the connection as unavailable)" value={forcedOffline} onChange={setForcedOffline} />
            <P small>{auth ? 'Signed in as an ASHA worker.' : 'Working offline without a login. Log in to sync.'}</P>
            {stale > 0 && <P tone="alert">{`${stale} visit(s) have waited more than 3 days to sync. Connect and sync as soon as you can.`}</P>}
            {failed.length > 0 && <P tone="alert">{`${failed.length} assessment(s) were refused by the server: ${failed.map(e => e.problem).join('; ')}`}</P>}
            {lostUrgent > 0 && (
              <P tone="alert">A visit with a self-harm answer of 'sometimes' or 'often' has not reached the server, so no urgent-review flag exists for it yet. Make sure the person has been connected to trained human support and tell your supervisor now.</P>
            )}
            <Button label="Lock now" secondary onPress={() => lock('Locked. Enter your PIN to continue.')} />
          </>
        );
      }
      case 'list':
        return (
          <>
            <P small>Names and villages are not shown: they are stored write-only in the identity store.</P>
            {!listing ? <P>Loading…</P> : listing.error ? <P tone="alert">{listing.error}</P> : listing.cases.length === 0
              ? <P tone="notice">{listing.followUps ? 'No visits need follow-up.' : 'No visits recorded yet.'}</P>
              : listing.cases.map(c => (
                <View key={c.case_id} style={styles.card}>
                  <Text style={styles.mono}>{c.case_id}</Text>
                  <Text style={styles.small}>{`${c.status}${c.urgent_review_flag ? ' · Urgent review' : ''}${c.follow_up_requested ? ' · Follow-up' : ''}`}</Text>
                </View>
              ))}
            {listing && listing.tasks.length > 0 && <Text style={styles.label}>{tr('Tasks from the clinic')}</Text>}
            {listing && listing.tasks.map(task => (
              <View key={task.task_id} style={styles.card}>
                <Text>{`${TASK_TEXT[task.task_type] || task.task_type} · ${task.facility_label}`}</Text>
                <Text style={styles.small}>{`Due ${new Date(task.due_by_utc).toLocaleDateString()} · ${task.participant_ref}`}</Text>
                <View style={styles.row}>
                  {[['DONE', 'Done'], ['NOT_REACHABLE', 'Could not reach'], ['DECLINED', 'Person declined']].map(([outcome, label]) => (
                    <Button key={outcome} label={label} secondary onPress={async () => {
                      const r = await call('POST', `/v1/care-tasks/${task.task_id}/completion`, {completed_at_utc: isoNow(), outcome}, ids.uuid())
                        .catch(() => ({status: 0, data: null}));
                      if (r.status !== 200 && r.status !== 201) warn(`Not recorded (${(r.data && r.data.code) || r.status}).`);
                      openList(true);
                    }} />
                  ))}
                </View>
              </View>
            ))}
            <Button label="Back to dashboard" secondary onPress={() => go('home')} />
          </>
        );
      case 'registration':
        return (
          <>
            <Field label="Beneficiary name or approved identifier">
              <TextInput value={v.name} maxLength={100} placeholder={tr('Enter name or local ID')} style={styles.input}
                onChangeText={name => update({name})} {...NO_LEARNING} />
            </Field>
            <Field label="Village">
              <TextInput value={v.village} maxLength={100} placeholder={tr('Enter village name')} style={styles.input}
                onChangeText={village => update({village})} {...NO_LEARNING} />
            </Field>
            <Field label="Age group"><Choice options={AGE} value={v.age} onChange={age => update({age})} /></Field>
            <Field label="Gender (optional)"><Choice options={GENDER} value={v.gender} onChange={gender => update({gender})} /></Field>
            <P small>Use invented names and villages only: this is a test build for synthetic data.</P>
            <Button label="Continue →" onPress={() => {
              if (!v.name.trim() || !v.village.trim()) return warn('Please enter beneficiary name/identifier and village.');
              if (!v.age) return warn("Please select the person's age group.");
              if (v.age === 'AGE_UNDER_18') return warn('Use an approved child pathway for people aged 18 and under.');
              go('consent');
            }} />
          </>
        );
      case 'consent':
        return (
          <>
            <P tone="notice">We would like to ask a few questions about wellbeing, sleep and feelings. Participation is voluntary. The person may skip a question or stop at any time. Explain how their information will be used and who may access it.</P>
            <Field label="Does the person agree to participate?">
              <Choice options={[['yes', 'Yes, informed consent obtained'], ['no', 'No, the person declines']]} value={v.consent}
                onChange={c => update({consent: c as Visit['consent']})} />
            </Field>
            {v.consent === 'yes' && (
              <>
                <Field label="How was consent recorded?"><Choice options={CONSENT_METHOD} value={v.method} onChange={method => update({method})} /></Field>
                <Check label="The consent wording was read aloud" value={v.readAloud} onChange={readAloud => update({readAloud})} />
                <Check label="The person confirmed they understood" value={v.understood} onChange={understood => update({understood})} />
                <Check label="A witness was present" value={v.witness} onChange={witness => update({witness})} />
                <Check label="The person confirmed they are 18 or older" value={v.adult} onChange={adult => update({adult})} />
                <Field label="If a clinician recommends it, may a referral be shared with a national support programme (for example Tele-MANAS or eSanjeevani)? The referral carries no name or phone number; you would connect the person yourself, quoting a short code.">
                  <Choice options={[['yes', 'Yes, the person agrees'], ['no', 'No, do not share']]} value={v.sharing}
                    onChange={c => update({sharing: c as Visit['sharing']})} />
                </Field>
                <Field label="Optional: may we record the person's voice for a few seconds? The recording is used to study voice and wellbeing. It is not a test and gives no result. The person can say no and still take part.">
                  <Choice options={[['yes', 'Yes, the person agrees to a voice recording'], ['no', 'No voice recording']]} value={v.audio}
                    onChange={c => update({audio: c as Visit['audio']})} />
                </Field>
              </>
            )}
            <Button label="Continue" onPress={async () => {
              const problem = consentProblem(v);
              if (problem) return warn(problem);
              if (v.consent === 'no') {
                await discardVisit(); // nothing about this person is kept or sent
                return go('declined');
              }
              update({consentAt: isoNow()});
              go('health');
            }} />
          </>
        );
      case 'declined':
        return (
          <>
            <P tone="notice">Thank the person and respect their decision. Do not continue with the assessment.</P>
            <P small>Nothing about this person was saved or sent.</P>
            <Button label="Return home" onPress={() => go('home')} />
          </>
        );
      case 'health':
        return (
          <>
            <P>Does the person report any of the following?</P>
            <Choice options={HEALTH} value="" multi={v.health} onChange={code => {
              const on = !v.health.includes(code);
              update({health: on ? (EXCLUSIVE_HEALTH.includes(code) ? [code] : [...v.health.filter(c => !EXCLUSIVE_HEALTH.includes(c)), code])
                : v.health.filter(c => c !== code)});
            }} />
            <Button label="Continue →" onPress={() => go('wellness')} />
          </>
        );
      case 'wellness':
        return (
          <>
            <Field label="1. Have you felt nervous, anxious or worried?"><Choice options={FREQUENCY} value={v.anxiety} onChange={anxiety => update({anxiety})} /></Field>
            <Field label="2. Have you felt down, sad or hopeless?"><Choice options={FREQUENCY} value={v.sadness} onChange={sadness => update({sadness})} /></Field>
            <Field label="3. Have you enjoyed your usual activities?"><Choice options={INTEREST} value={v.interest} onChange={interest => update({interest})} /></Field>
            <P small>Illustrative questions only; do not use these answers to diagnose or calculate an unvalidated score.</P>
            <Button label="Continue →" onPress={() => go('lifestyle')} />
          </>
        );
      case 'lifestyle':
        return (
          <>
            <Field label="Use of tobacco"><Choice options={TOBACCO} value={v.tobacco} onChange={tobacco => update({tobacco})} /></Field>
            <Field label="Use of alcohol"><Choice options={ALCOHOL} value={v.alcohol} onChange={alcohol => update({alcohol})} /></Field>
            <Field label="How has the person been sleeping?"><Choice options={SLEEP} value={v.sleep} onChange={sleep => update({sleep})} /></Field>
            <Button label="Continue to safety check →" onPress={() => { update({lifestyleAt: isoNow()}); go('safety'); }} />
          </>
        );
      case 'safety':
        return (
          <>
            <P tone="alert">Important: ask sensitively and in private. This is not a complete clinical risk assessment.</P>
            <Field label="Have you recently had thoughts of harming yourself, or felt that life is not worth living?">
              <Choice options={SAFETY} value={v.safety} onChange={safety => update({safety})} />
            </Field>
            {URGENT.includes(v.safety) && (
              <>
                <P tone="alert">Prompt human support: follow the approved safety protocol and promptly connect the person with a trained health professional. If there is immediate danger, seek urgent local emergency help. Do not wait for sync.</P>
                <P tone="alert">Test build: no approved local safety protocol exists yet. Contact your supervisor now. The case will carry an urgent-review flag for human follow-up; this is not a risk score.</P>
              </>
            )}
            <Button label="Review assessment →" onPress={() => {
              if (!v.safety) return warn('Please record a response or prefer-not-to-answer.');
              update({safetyAt: isoNow()});
              go(v.audio === 'yes' ? 'voice' : 'summary');
            }} />
          </>
        );
      case 'voice':
        // The consent gate: no recording control exists unless the person agreed to recording.
        if (v.audio !== 'yes') {
          return (
            <>
              <P tone="notice">The person did not agree to a voice recording, so nothing can be recorded.</P>
              <Button label="Review assessment →" onPress={() => go('summary')} />
            </>
          );
        }
        return (
          <>
            <P tone="notice">{settings.microphoneEnabled ? 'Record in a quiet place, with the phone about 20 cm from the person.'
              : 'Test build: the microphone is off, so each recording is a generated synthetic voice, not the person.'}</P>
            {PROMPTS.map(([promptId, instruction]) => {
              const done = v.recordings[promptId];
              const label = !settings.microphoneEnabled ? (done ? 'Record again (synthetic)' : 'Record (synthetic test voice)')
                : recording && recording.promptId === promptId && recording.participant === v.ids.participant ? 'Stop recording'
                  : done ? 'Record again' : 'Start recording';
              return (
                <View key={promptId} style={styles.card}>
                  <P>{instruction}</P>
                  {done && <P small>{`✓ Recorded ${done.seconds.toFixed(1)} s`}</P>}
                  <Button label={label} secondary onPress={() => record(promptId)} />
                </View>
              );
            })}
            <P small>No result is shown, now or later: a voice recording is not a diagnosis.</P>
            <Button label="Review assessment →" onPress={() => go('summary')} />
          </>
        );
      case 'summary':
        return (
          <>
            {[['Beneficiary', v.name], ['Village', v.village], ['Age group', tr((AGE.find(([c]) => c === v.age) || ['', ''])[1])],
              ['Consent', tr('Obtained')], ['Safety response', tr((SAFETY.find(([c]) => c === v.safety) || ['', ''])[1])],
              ['Voice recordings', v.audio === 'yes' ? String(Object.keys(v.recordings).length) : tr('Not consented')]]
              .map(([k, val]) => (
                <View key={k} style={styles.summaryRow}><Text>{tr(k)}</Text><Text style={styles.strong}>{val}</Text></View>
              ))}
            <Field label="Follow-up">
              <Choice options={[['yes', 'Flag for trained health professional follow-up'], ['no', 'No follow-up requested in this demo']]}
                value={v.followup ? 'yes' : 'no'} onChange={c => update({followup: c === 'yes'})} />
            </Field>
            {URGENT.includes(v.safety) && <P tone="alert">The safety answer raises an urgent-review flag on this case, whatever you choose above.</P>}
            <P small>No diagnosis or clinical severity score is generated.</P>
            <Button label="Save assessment" onPress={save} />
            <Button label="Back to safety check" secondary onPress={() => go('safety')} />
          </>
        );
      case 'complete': {
        const entry = outbox.find(e => e.id === lastEntry);
        const c: any = (entry && entry.result) || {};
        return (
          <>
            {entry && entry.status === 'done' ? (
              <>
                <Text style={styles.h2}>{tr('Assessment saved')}</Text>
                {[['Case', c.case_id], ['Urgent-review flag', c.urgent_review_flag ? 'Raised' : 'Not raised'],
                  ['Patient card code (give to the person for clinic visits)', entry.card || '—'],
                  ...(audioIds(entry).length ? [['Voice recording', entry.voiceProblem ? 'Not uploaded' : 'Uploaded']] : [])]
                  .map(([k, val]) => (
                    <View key={k} style={styles.summaryRow}><Text style={styles.flex}>{tr(k)}</Text><Text style={styles.mono}>{String(val)}</Text></View>
                  ))}
                {entry.voiceProblem && <P tone="notice">{`The voice recording was not uploaded (${entry.voiceProblem}) and was deleted from this device. The rest of the visit is saved.`}</P>}
              </>
            ) : entry && entry.status === 'failed' ? (
              <>
                <Text style={styles.h2}>{tr('Not saved')}</Text>
                <P tone="alert">{`The server refused it (${entry.problem}).`}</P>
              </>
            ) : (
              <>
                <Text style={styles.h2}>{tr('Saved for sync')}</Text>
                <P>Stored encrypted on this device until it syncs. It stays if the app is closed or the phone restarts.</P>
              </>
            )}
            <Button label="Start another assessment" onPress={() => { setVisit(newVisit(ids)); go('registration'); }} />
            <Button label="Back to dashboard" secondary onPress={() => go('home')} />
          </>
        );
      }
    }
  }

  return (
    <Tr.Provider value={tr}>
    <View style={styles.app} onTouchStart={touch}>
      <StatusBar barStyle="light-content" />
      <View style={styles.header}>
        <Text style={styles.brand}>♡ ASHA Care</Text>
        <Text style={styles.headerSub}>{`${forcedOffline ? tr('Offline — save for later') : tr('Internet available')} · ${pending} ${tr('Pending sync')}`}</Text>
      </View>
      <Text style={styles.banner}>SYNTHETIC TEST DATA ONLY — not a validated clinical tool. Do not enter real people's information.</Text>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        {message && <P tone="alert">{message}</P>}
        {screen()}
      </ScrollView>
    </View>
    </Tr.Provider>
  );
}

const Tr = createContext<(text: string) => string>(text => text);

const P = (props: {children: React.ReactNode; small?: boolean; tone?: 'alert' | 'notice'}) => (
  <Text style={[styles.p, props.small && styles.small, props.tone && styles[props.tone]]}>{props.children}</Text>
);
const Button = (props: {label: string; onPress: () => void; secondary?: boolean; testID?: string}) => {
  const tr = useContext(Tr);
  return (
  <Pressable accessibilityRole="button" testID={props.testID} onPress={props.onPress}
    style={[styles.button, props.secondary && styles.secondary]}>
    <Text style={[styles.buttonText, props.secondary && styles.secondaryText]}>{tr(props.label)}</Text>
  </Pressable>
  );
};

const Choice = (props: {options: string[][]; value: string; onChange: (code: string) => void; multi?: string[]}) => {
  const tr = useContext(Tr);
  return (
  <View>
    {props.options.map(([code, label]) => {
      const on = props.multi ? props.multi.includes(code) : props.value === code;
      return (
        <Pressable key={code} accessibilityRole={props.multi ? 'checkbox' : 'radio'} accessibilityState={{checked: on}}
          onPress={() => props.onChange(code)} style={[styles.choice, on && styles.choiceOn]}>
          <Text>{(on ? '● ' : '○ ') + tr(label)}</Text>
        </Pressable>
      );
    })}
  </View>
  );
};

const Check = (props: {label: string; value: boolean; onChange: (v: boolean) => void}) => {
  const tr = useContext(Tr);
  return (
  <View style={styles.row}>
    <Switch value={props.value} onValueChange={props.onChange} accessibilityLabel={tr(props.label)} />
    <Text style={styles.flex}>{tr(props.label)}</Text>
  </View>
  );
};

const Field = (props: {label: string; children: React.ReactNode}) => {
  const tr = useContext(Tr);
  return (
  <View style={styles.field}>
    <Text style={styles.label}>{tr(props.label)}</Text>
    {props.children}
  </View>
  );
};

const Pin = (props: {placeholder: string; onChange: (v: string) => void}) => {
  const tr = useContext(Tr);
  return (
  <TextInput secureTextEntry keyboardType="number-pad" maxLength={12} placeholder={tr(props.placeholder)}
    onChangeText={props.onChange} style={styles.input} autoComplete="off" importantForAutofill="no" />
  );
};

function SetPin({warn, onCreate}: {warn: (m: string) => void; onCreate: (pin: string) => Promise<void>}) {
  const pins = useRef({pin: '', again: ''});
  return (
    <>
      <P tone="notice">{`Visits you save are kept on this device, encrypted with a key that only your PIN unlocks, until they reach the server. The app locks after 5 minutes without use. After ${MAX_ATTEMPTS} wrong attempts the stored visits are deleted.`}</P>
      <Field label="New PIN (6 to 12 digits)"><Pin placeholder="PIN" onChange={p => { pins.current.pin = p; }} /></Field>
      <Field label="Repeat the PIN"><Pin placeholder="PIN again" onChange={p => { pins.current.again = p; }} /></Field>
      <Button label="Set PIN" onPress={() => {
        const {pin, again} = pins.current;
        if (!PIN_PATTERN.test(pin)) return warn('The PIN must be 6 to 12 digits.');
        if (pin !== again) return warn('The two PINs do not match.');
        onCreate(pin);
      }} />
    </>
  );
}

function Unlock({mine, loggedIn, onUnlock, onLogin, onDelete}: {mine: StoreInfo[]; loggedIn: boolean;
  onUnlock: (owner: string, pin: string) => Promise<void>; onLogin: () => void; onDelete: (owner: string) => Promise<void>;}) {
  const [chosen, setChosen] = useState(mine.length ? mine[0].owner : '');
  const pin = useRef('');
  if (!mine.length) {
    return (
      <>
        <P tone="notice">There are no offline visits for this account on this device.</P>
        <Button label={loggedIn ? 'Set PIN' : 'Log in'} onPress={onLogin} />
      </>
    );
  }
  return (
    <>
      {mine.length > 1 && (
        <Field label="Whose visits?">
          <Choice options={mine.map(m => [m.owner, `Saved data ${m.owner.slice(0, 6)} · since ${new Date(m.createdAt).toLocaleDateString()}`])}
            value={chosen} onChange={setChosen} />
        </Field>
      )}
      <Field label="PIN"><Pin placeholder="PIN" onChange={p => { pin.current = p; }} /></Field>
      <P small>{`After ${MAX_ATTEMPTS} wrong PINs this worker's offline data is deleted.`}</P>
      <Button label="Unlock" onPress={() => onUnlock(chosen, pin.current)} />
      {!loggedIn && <Button label="Log in instead" secondary onPress={onLogin} />}
      <Button label="Forgotten PIN: delete this data" secondary onPress={() => Alert.alert('Delete offline visits?',
        'This deletes every unsynced visit stored for this worker on this device.', [
          {text: 'Cancel', style: 'cancel'},
          {text: 'Delete', style: 'destructive', onPress: () => onDelete(chosen)},
        ])} />
    </>
  );
}

const styles = StyleSheet.create({
  app: {flex: 1, backgroundColor: '#f3f6fa', paddingTop: StatusBar.currentHeight || 0},
  header: {backgroundColor: '#153b5b', padding: 16},
  brand: {color: '#fff', fontSize: 20, fontWeight: '800'},
  headerSub: {color: '#d8e7f6', fontSize: 13},
  banner: {backgroundColor: '#fff2d5', color: '#7a5200', padding: 8, fontSize: 12, textAlign: 'center'},
  content: {padding: 16, gap: 10, paddingBottom: 48},
  p: {fontSize: 15, color: '#182536'},
  small: {fontSize: 13, color: '#607086'},
  alert: {backgroundColor: '#fde8e8', color: '#a52a2a', padding: 10, borderRadius: 10},
  notice: {backgroundColor: '#eef7f4', color: '#205f55', padding: 10, borderRadius: 10},
  button: {backgroundColor: '#2169c9', padding: 14, borderRadius: 12, alignItems: 'center', marginTop: 6},
  buttonText: {color: '#fff', fontWeight: '700'},
  secondary: {backgroundColor: '#fff', borderWidth: 1, borderColor: '#dce4ed'},
  secondaryText: {color: '#153b5b'},
  choice: {padding: 12, borderWidth: 1, borderColor: '#dce4ed', borderRadius: 10, marginTop: 6, backgroundColor: '#fff'},
  choiceOn: {borderColor: '#2169c9', backgroundColor: '#eaf3ff'},
  row: {flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap'},
  flex: {flex: 1},
  field: {gap: 4},
  label: {fontWeight: '700', color: '#182536'},
  input: {borderWidth: 1, borderColor: '#dce4ed', borderRadius: 10, padding: 12, backgroundColor: '#fff'},
  card: {backgroundColor: '#fff', borderRadius: 12, padding: 12, borderWidth: 1, borderColor: '#dce4ed', gap: 4},
  mono: {fontFamily: 'monospace', fontSize: 12},
  summaryRow: {flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 6, borderBottomWidth: 1, borderColor: '#dce4ed'},
  strong: {fontWeight: '700'},
  h2: {fontSize: 20, fontWeight: '800', color: '#176342'},
});
