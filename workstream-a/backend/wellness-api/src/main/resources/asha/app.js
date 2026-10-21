// ASHA Care — field-worker web app (local test build, ADR-013). Synthetic data only.
// Same flow and wording as the ASHA Care prototype, but every answer is saved through the
// wellness API: consent event → case → identity (write-only) → observations → questionnaire →
// completion. No framework or third-party code. All output goes through textContent.
// Offline mode is off by default: the visit in progress and the outbox then live in memory only
// (they include names), so a reload discards them. With offline mode on (ADR-017), store.js keeps
// them encrypted on the device under the worker's PIN. Tokens are never stored either way.
"use strict";

const KEYCLOAK = "http://localhost:8081/realms/wellness-local/protocol/openid-connect";
const CLIENT_ID = "wellness-asha-web";
const REDIRECT_URI = location.origin + "/asha/";

// 0.2.0 also asks, separately, about sharing a referral with a partner programme (ADR-015);
// 0.3.0 also asks, separately, about recording the person's voice (ADR-017).
const CONSENT_FORM = { form_id: "asha-wellness-consent", form_version: "0.3.0" };
const PROMPT_SET = { prompt_set_id: "baseline-voice-tasks", prompt_set_version: "0.1.0" };
const PIPELINE_PROFILE = "EGEMAPS_V02_FUNCTIONALS_V1";
const PROMPTS = [["sustained-vowel-a", "Ask the person to say “aaah” for as long as is comfortable."],
  ["counting-one-to-ten", "Ask the person to count slowly from one to ten."]];
const SYNTHETIC_SECONDS = 6;
const INSTRUMENT = { instrument_id: "illustrative-wellness", instrument_version: "0.1.0" };
const CATALOGUE = { catalogue_id: "asha-observations", catalogue_version: "0.1.0" };

const STEPS = ["language", "login", "home", "registration", "consent", "health", "wellness", "lifestyle", "safety",
  "voice", "summary", "complete"];

// Answer options: [API code, prototype wording]. Codes match wellness-api.v1.yaml 1.1.
const AGE = [["", "Select age group"], ["AGE_UNDER_18", "Under 18 — use an approved child pathway"], ["AGE_18_29", "18–29"],
  ["AGE_30_44", "30–44"], ["AGE_45_59", "45–59"], ["AGE_60_PLUS", "60+"]];
const GENDER = [["NOT_STATED", "Prefer not to say"], ["FEMALE", "Female"], ["MALE", "Male"], ["OTHER", "Other"]];
const HEALTH = [["HYPERTENSION", "Hypertension"], ["DIABETES", "Diabetes"], ["HEART_DISEASE", "Heart disease"],
  ["BREATHING_PROBLEM", "Asthma or breathing problems"], ["OTHER_LONG_TERM_ILLNESS", "Other long-term illness"],
  ["NONE_REPORTED", "None reported"], ["DECLINED", "Prefer not to answer"]];
const EXCLUSIVE_HEALTH = ["NONE_REPORTED", "DECLINED"];
const FREQUENCY = [["NOT_AT_ALL", "Not at all"], ["SEVERAL_DAYS", "Several days"],
  ["MORE_THAN_HALF_THE_DAYS", "More than half the days"], ["NEARLY_EVERY_DAY", "Nearly every day"],
  ["DECLINED", "Prefer not to answer"]];
const INTEREST = [["AS_USUAL", "Yes, as usual"], ["LESS_THAN_BEFORE", "Less than before"], ["NOT_AT_ALL", "Not at all"],
  ["DECLINED", "Prefer not to answer"]];
const TOBACCO = [["NOT_REPORTED", "Not reported"], ["REPORTED", "Person reports tobacco use"], ["DECLINED", "Prefer not to answer"]];
const ALCOHOL = [["NOT_REPORTED", "Not reported"], ["REPORTED", "Person reports alcohol use"], ["DECLINED", "Prefer not to answer"]];
const SLEEP = [["GOOD", "Good"], ["SOME_DIFFICULTY", "Some difficulty"], ["VERY_POOR", "Very poor"], ["DECLINED", "Prefer not to answer"]];
const SAFETY = [["NO", "No"], ["SOMETIMES", "Yes, sometimes"], ["OFTEN", "Yes, often"], ["DECLINED", "Prefer not to answer"]];
const URGENT = ["SOMETIMES", "OFTEN"];
const CONSENT_METHOD = [["VERBAL_WITNESSED", "Verbal, with a witness"], ["SIGNED_ON_DEVICE", "Signed on this device"],
  ["THUMBPRINT_ON_DEVICE", "Thumbprint on this device"]];

const TITLES = { language: "Choose your language", login: "ASHA worker login", home: "Namaste, ASHA worker",
  registration: "Beneficiary details", consent: "Consent to participate", declined: "Participation declined",
  health: "General health background", wellness: "Mental wellness questions", lifestyle: "Lifestyle and support",
  safety: "Important safety check", voice: "Voice recording", summary: "Review and next steps", complete: "Assessment saved",
  households: "My households", followups: "Follow-ups", unlock: "Unlock offline visits",
  offlineSetup: "Offline mode" };
const SUBS = { language: "Choose the language you are most comfortable using.",
  login: "Use your assigned worker account in the production app.", home: "Your community wellness visits",
  registration: "Collect only information required for the assessment.",
  consent: "Explain the purpose before asking any questions.", health: "Select conditions the person reports.",
  wellness: "Sample questions about the last two weeks.", lifestyle: "Ask sensitively and respect the person's privacy.",
  safety: "Ask privately, following approved training.",
  voice: "Optional. Only with the person's consent to record their voice.", summary: "Check the information and decide the next step.",
  complete: "Thank you. The demonstration flow is complete.",
  declined: "Respect the person's decision. No screening questions will be asked.",
  households: "View assigned visits", followups: "Review pending care",
  unlock: "Visits saved on this device are encrypted. Enter your PIN.",
  offlineSetup: "Keep visits on this device, encrypted, until they sync." };

const auth = { token: null, refresh: null, claims: null, workerRef: null, owner: null };
const ui = { lang: "en", page: "language", online: navigator.onLine, forcedOffline: false, message: null, lastEntry: null,
  listing: null };
// Offline mode (ADR-017): the unlocked store, whose worker it belongs to, and the stores on this device.
const offline = { session: null, stores: [], idleTimer: null, retryTimer: null, failures: 0, quota: null };
const IDLE_LOCK_MS = 5 * 60 * 1000;
const STALE_MS = 3 * 24 * 3600 * 1000;
const KEEP_FINISHED_MS = 7 * 24 * 3600 * 1000;
let visit = null; // the assessment in progress (memory only)
const audio = new Map(); // recording ID → WAV bytes, kept apart from the visit's JSON
const settings = { microphone: false }; // from /asha/settings.json; the microphone needs privacy-lead approval
const outbox = []; // saved assessments waiting for, or finished with, the API (memory only)
let untranslated = new Set();
const $ = (id) => document.getElementById(id);

// --- helpers -------------------------------------------------------------------------

function hex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
function randomId(prefix) {
  return `${prefix}_${hex(crypto.getRandomValues(new Uint8Array(16)))}`;
}
function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function isoNow() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
}
function language() {
  return LANGUAGES.find(([code]) => code === ui.lang);
}

/** The prototype translation of an English string, or the English string (marked) if there is none. */
function t(s) {
  if (ui.lang === "en") return { text: s, missing: false };
  const map = TRANSLATIONS[ui.lang] || {};
  if (map[s]) return { text: map[s], missing: false };
  untranslated.add(s);
  return { text: s, missing: true };
}
/** Element with translated text. */
function el(tag, s, cls) {
  const e = document.createElement(tag);
  if (s !== undefined && s !== null) {
    const r = t(s);
    e.textContent = r.text;
    if (r.missing) e.classList.add("untr");
  }
  if (cls) e.className += (e.className ? " " : "") + cls;
  return e;
}
/** Element with literal text (IDs, codes, the person's own entries); never translated. */
function raw(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined && text !== null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function field(label, ...children) {
  const d = raw("div", null, "field");
  d.append(el("label", label), ...children);
  return d;
}
function radios(name, options, value, onChange) {
  const box = raw("div", null, "options");
  for (const [code, label] of options) {
    const l = raw("label", null, "choice");
    const input = document.createElement("input");
    input.type = "radio";
    input.name = name;
    input.value = code;
    input.checked = value === code;
    input.addEventListener("change", () => onChange(code));
    l.append(input, el("span", label));
    box.append(l);
  }
  return box;
}
function checks(name, options, values, onChange) {
  const box = raw("div", null, "options");
  for (const [code, label] of options) {
    const l = raw("label", null, "choice");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.name = name;
    input.value = code;
    input.checked = values.includes(code);
    input.addEventListener("change", () => onChange(code, input.checked));
    l.append(input, el("span", label));
    box.append(l);
  }
  return box;
}
function checkbox(label, value, onChange) {
  const l = raw("label", null, "choice");
  const input = document.createElement("input");
  input.type = "checkbox";
  input.checked = value;
  input.addEventListener("change", () => onChange(input.checked));
  l.append(input, el("span", label));
  return l;
}
function select(options, value, onChange) {
  const s = document.createElement("select");
  for (const [code, label] of options) {
    const o = document.createElement("option");
    o.value = code;
    o.textContent = t(label).text;
    o.selected = code === value;
    s.append(o);
  }
  s.addEventListener("change", () => onChange(s.value));
  return s;
}
function textInput(value, placeholder, onInput) {
  const i = document.createElement("input");
  i.value = value;
  i.maxLength = 100;
  i.autocomplete = "off";
  i.spellcheck = false;
  i.setAttribute("autocorrect", "off"); // keyboards must not learn beneficiary names (review W3)
  i.placeholder = t(placeholder).text;
  i.addEventListener("input", () => onInput(i.value));
  return i;
}
function button(label, onClick, cls = "") {
  const b = el("button", label, `btn ${cls}`.trim());
  b.type = "button";
  b.addEventListener("click", onClick);
  return b;
}
function actions(...buttons) {
  const d = raw("div", null, "actions");
  d.append(...buttons);
  return d;
}
function labelOf(options, code) {
  const found = options.find(([c]) => c === code);
  return found ? found[1] : "Not answered";
}
function go(page, message = null) {
  if (page !== "voice") cancelMicrophone(); // a recording never outlives its screen (review B1)
  ui.page = page;
  if (visit && STEPS.includes(page)) visit.page = page; // a restored visit reopens where it was left
  persistVisit();
  ui.message = message;
  render();
}
function warn(text) {
  ui.message = text;
  render();
}

// --- login (authorization code + PKCE, as the dev console) -----------------------------

async function login() {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(48)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  const stateParam = randomId("st");
  // Only the PKCE verifier and the chosen UI language cross the redirect; nothing about any person.
  sessionStorage.setItem("asha-pkce", JSON.stringify({ verifier, state: stateParam, lang: ui.lang }));
  const params = new URLSearchParams({ client_id: CLIENT_ID, redirect_uri: REDIRECT_URI, response_type: "code",
    scope: "openid", code_challenge: challenge, code_challenge_method: "S256", state: stateParam });
  location.assign(`${KEYCLOAK}/auth?${params}`);
}

async function completeLogin() {
  const params = new URLSearchParams(location.search);
  const code = params.get("code");
  const saved = JSON.parse(sessionStorage.getItem("asha-pkce") || "null");
  if (saved && saved.lang) ui.lang = saved.lang;
  if (!code) return false;
  sessionStorage.removeItem("asha-pkce");
  history.replaceState(null, "", REDIRECT_URI); // drop code and state from the URL and history
  if (!saved || saved.state !== params.get("state")) throw new Error("state mismatch");
  await tokenRequest({ grant_type: "authorization_code", code, redirect_uri: REDIRECT_URI, code_verifier: saved.verifier });
  return true;
}

async function tokenRequest(fields) {
  const r = await fetch(`${KEYCLOAK}/token`, { method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, ...fields }) });
  if (!r.ok) throw new Error(`login failed (${r.status})`);
  const body = await r.json();
  auth.token = body.access_token;
  auth.refresh = body.refresh_token || null;
  const payload = body.access_token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
  auth.claims = JSON.parse(atob(payload + "=".repeat((4 - (payload.length % 4)) % 4)));
  auth.owner = await OfflineStore.ownerOf(auth.claims.sub);
}

/** Keeps the session alive without a redirect, so the in-memory outbox survives. */
async function freshToken() {
  if (auth.claims && auth.claims.exp * 1000 - Date.now() > 30000) return;
  if (!auth.refresh) throw new SessionError("session expired");
  try {
    await tokenRequest({ grant_type: "refresh_token", refresh_token: auth.refresh });
  } catch (e) {
    if (e instanceof TypeError) throw new NetworkError("network unavailable");
    throw new SessionError("session expired");
  }
}

async function logout() {
  if (offline.session) {
    await persistVisit();
    alert("Your offline data stays on this device, encrypted and locked. Your PIN unlocks it again.");
  } else if (pendingCount() > 0 || visit) {
    if (!confirm("Unsynced assessments are kept only in this page and will be lost. Log out anyway?")) return;
  }
  cancelMicrophone();
  revokePlayback();
  offline.session = null;
  outbox.length = 0;
  visit = null;
  audio.clear();
  auth.token = auth.refresh = auth.claims = null;
  location.assign(`${KEYCLOAK}/logout?client_id=${CLIENT_ID}&post_logout_redirect_uri=${encodeURIComponent(REDIRECT_URI)}`);
}

function isAshaWorker() {
  if (!auth.token && offline.session) return true; // the store was created while logged in as an ASHA worker
  return Boolean(auth.claims && auth.claims.realm_access && (auth.claims.realm_access.roles || []).includes("ASHA_WORKER"));
}

// --- API -------------------------------------------------------------------------------

class NetworkError extends Error {}
class SessionError extends Error {}

/** One API call. Request bodies are never logged: they can contain a name and a village. */
async function api(method, path, body, idempotencyKey, extraHeaders) {
  await freshToken();
  const headers = { Authorization: `Bearer ${auth.token}`, "X-Correlation-ID": crypto.randomUUID(), ...extraHeaders };
  const binary = body instanceof Uint8Array;
  if (body !== undefined && !binary) headers["Content-Type"] = "application/json";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  let r;
  try {
    r = await fetch(path, { method, headers, body: body === undefined ? undefined : binary ? body : JSON.stringify(body) });
  } catch (e) {
    throw new NetworkError("network unavailable");
  }
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  return { status: r.status, data };
}

// --- turning a visit into API operations -----------------------------------------------

function newVisit() {
  return {
    ids: { participant: randomId("ppt"), consent: randomId("ce"), case: randomId("case"), obs: randomId("obs"),
      qr: randomId("qr") },
    name: "", village: "", age: "", gender: "NOT_STATED",
    consent: "", method: "VERBAL_WITNESSED", readAloud: false, understood: false, witness: false, adult: false,
    sharing: "", audio: "", recordings: {}, consentAt: null,
    health: [], anxiety: "", sadness: "", interest: "", tobacco: "NOT_REPORTED", alcohol: "NOT_REPORTED", sleep: "",
    lifestyleAt: null, safety: "", safetyAt: null, followup: true,
  };
}

/** The ordered calls for one visit. IDs and idempotency keys are fixed now, so every retry is safe. */
function operations(v, savedAt) {
  const tag = language()[1];
  const observations = [
    ...v.health.map((code) => ({ code: "HEALTH_CONDITION", value_code: code })),
    { code: "TOBACCO_USE", value_code: v.tobacco },
    { code: "ALCOHOL_USE", value_code: v.alcohol },
    ...(v.sleep ? [{ code: "SLEEP_QUALITY", value_code: v.sleep }] : []),
  ];
  const items = [["ANXIETY", v.anxiety], ["LOW_MOOD", v.sadness], ["INTEREST", v.interest], ["SELF_HARM_THOUGHTS", v.safety]]
    .filter(([, answer]) => answer)
    .map(([item_id, answer_code]) => ({ item_id, answer_code }));
  const caseId = v.ids.case;
  return [
    { what: "consent", method: "POST", path: "/v1/consent-events", key: crypto.randomUUID(), body: {
      consent_event_id: v.ids.consent, participant_ref: v.ids.participant,
      consent_form: { ...CONSENT_FORM, language_tag: tag },
      scope_decisions: [{ scope: "SCREENING_PARTICIPATION", granted: true },
        { scope: "EXTERNAL_REFERRAL_SHARING", granted: v.sharing === "yes" },
        { scope: "AUDIO_RECORDING", granted: v.audio === "yes" }],
      capture: { captured_at_utc: v.consentAt, method: v.method, read_aloud: v.readAloud, comprehension_confirmed: true,
        witness_present: v.witness },
      safeguards: { adult_confirmed: v.adult } } },
    { what: "case", method: "POST", path: "/v1/cases", key: crypto.randomUUID(), body: {
      case_id: caseId, participant_ref: v.ids.participant, consent_event_id: v.ids.consent, language_tag: tag,
      opened_at_utc: v.consentAt, data_origin: "SYNTHETIC", age_band: v.age, gender: v.gender } },
    // Identity goes last of the enrolment steps, only once consent is on record (ADR-013).
    { what: "identity", method: "PUT", path: `/v1/participants/${v.ids.participant}/identity`, pii: true, body: {
      consent_event_id: v.ids.consent, data_origin: "SYNTHETIC", display_name: v.name.trim(), village_name: v.village.trim() } },
    { what: "observations", method: "POST", path: `/v1/cases/${caseId}/observations`, key: crypto.randomUUID(), body: {
      observation_set_id: v.ids.obs, catalogue: CATALOGUE, observed_at_utc: v.lifestyleAt, observations } },
    { what: "questionnaire", method: "POST", path: `/v1/cases/${caseId}/questionnaire-responses`, key: crypto.randomUUID(),
      body: { response_id: v.ids.qr, instrument: INSTRUMENT, language_tag: tag, answered_at_utc: v.safetyAt, items } },
    { what: "completion", method: "POST", path: `/v1/cases/${caseId}/completion`, key: crypto.randomUUID(), body: {
      completed_at_utc: savedAt, follow_up_requested: v.followup } },
    ...voiceOperations(v, tag),
  ];
}

/**
 * Voice steps, only when the person agreed to recording (the consent gate). They come last and are
 * optional: if the server refuses one (for example consent was withdrawn meanwhile), the audio is
 * deleted and the rest of the visit still counts (ADR-008: withdrawal always wins).
 */
function voiceOperations(v, tag) {
  const recs = Object.entries(v.recordings);
  if (v.audio !== "yes" || !recs.length) return [];
  const sessionId = v.ids.session || randomId("sess");
  // A real voice is never labelled SYNTHETIC: any microphone recording makes the session test audio.
  const origin = recs.some(([, r]) => r.origin !== "SYNTHETIC") ? "AUTHORIZED_TEST_AUDIO" : "SYNTHETIC";
  const os = /iPhone|iPad/.test(navigator.userAgent) ? "IOS" : "ANDROID";
  const ops = [{ what: "voice session", optional: true, method: "POST", path: "/v1/voice-sessions", key: crypto.randomUUID(),
    body: { session_id: sessionId, participant_ref: v.ids.participant, consent_event_id: v.ids.consent, language_tag: tag,
      prompt_set: PROMPT_SET, captured_at_utc: recs.map(([, r]) => r.recordedAt).sort()[0],
      device: { device_class: "UNKNOWN", app_version: "0.3.0", os_family: os }, data_origin: origin } }];
  for (const [promptId, r] of recs) {
    ops.push({ what: "recording", optional: true, method: "POST", path: `/v1/voice-sessions/${sessionId}/recordings`,
      key: crypto.randomUUID(), body: { recording_id: r.recordingId, prompt_id: promptId, format: "WAV_PCM_S16LE",
        sampling_rate_hz: Voice.RATE, channels: 1, duration_seconds: r.seconds, size_bytes: r.size, sha256: r.sha256,
        recorded_at_utc: r.recordedAt } },
    { what: "audio upload", optional: true, method: "PUT", audio: r.recordingId, sha256: r.sha256,
      path: `/v1/voice-sessions/${sessionId}/recordings/${r.recordingId}/content`, body: null },
    { what: "processing", optional: true, method: "POST", path: "/v1/processing-jobs", key: crypto.randomUUID(),
      body: { recording_id: r.recordingId, pipeline_profile: PIPELINE_PROFILE } });
  }
  return ops;
}

/** Drops an unsaved visit and any recordings it made. */
function discardVisit() {
  cancelMicrophone();
  revokePlayback();
  if (visit && visit.recordings) dropAudio(Object.values(visit.recordings).map((r) => r.recordingId));
  visit = null;
}

function audioIds(entry) {
  return entry.ops.filter((o) => o.audio).map((o) => o.audio);
}

async function audioBytes(id) {
  return audio.get(id) || (offline.session ? offline.session.get("audio:" + id) : undefined);
}

/** Deletes recordings from memory and the device: after a confirmed upload, a refusal, or a discard. */
async function dropAudio(ids) {
  for (const id of ids) {
    audio.delete(id);
    if (offline.session) await offline.session.delete("audio:" + id).catch(storageFailed);
  }
}

function pendingCount() {
  return outbox.filter((e) => e.status === "pending").length;
}

let flushing = null;
/** Sends pending assessments in order. Stops at the first network failure and keeps the rest queued. */
function flush() {
  if (!flushing) {
    flushing = doFlush()
      .then(() => { offline.failures = 0; })
      .catch((e) => {
        if (e instanceof NetworkError) offline.failures += 1;
        else ui.message = `Sync stopped: ${e.message}`;
      })
      .finally(() => { flushing = null; scheduleRetry(); render(); });
  }
  return flushing;
}

/** Retries pending visits while the page is open: 30 s, doubling to at most 15 minutes. */
function scheduleRetry() {
  clearTimeout(offline.retryTimer);
  if (!pendingCount() || !auth.token || !ui.online) return;
  const delay = offline.failures ? Math.min(15 * 60000, 30000 * 2 ** (offline.failures - 1)) : 60000;
  offline.retryTimer = setTimeout(flush, delay);
}

async function doFlush() {
  if (!auth.token) {
    ui.message = "Log in to sync. Your visits stay on this device until then.";
    return;
  }
  // Only the worker who saved a visit may send it (ADR-017): the store is theirs, and so are the IDs.
  if (offline.session && offline.session.owner !== auth.owner) {
    ui.message = "These offline visits belong to another worker's login. Log in as that worker to sync them.";
    return;
  }
  for (const entry of outbox) {
    if (entry.status !== "pending") continue;
    while (entry.next < entry.ops.length) {
      if (!ui.online) return;
      const op = entry.ops[entry.next];
      let r;
      try {
        if (op.audio) {
          const bytes = await audioBytes(op.audio);
          // A lost recording is a voice refusal: it must never hold back later visits (review W2).
          r = bytes ? await api(op.method, op.path, bytes, null, { "Content-Type": "audio/wav", "X-Content-SHA256": op.sha256 })
            : { status: 0, data: { code: "RECORDING_MISSING_ON_DEVICE" } };
        } else {
          r = await api(op.method, op.path, op.body, op.key);
        }
      } catch (e) {
        if (e instanceof NetworkError) throw e; // stay pending; retried with back-off
        if (e instanceof SessionError) {
          ui.message = offline.session ? "Your session has expired. Log in again to sync; your visits stay on this device."
            : "Your session has expired. Unsynced assessments stay pending in this page; logging in again "
            + "reloads the page and discards them.";
          return;
        }
        throw e;
      }
      if (r.status >= 200 && r.status < 300) {
        if (op.pii) op.body = null; // the name and village leave memory as soon as they are stored
        if (r.data && r.data.case_id) entry.result = r.data;
        if (r.data && r.data.patient_card_code) entry.card = r.data.patient_card_code;
        if (r.data && r.data.opened_by_worker_ref) auth.workerRef = r.data.opened_by_worker_ref;
        // The audio leaves the device only once the server has stored exactly these bytes.
        if (op.audio && r.data && r.data.upload_status === "STORED" && r.data.sha256 === op.sha256) await dropAudio([op.audio]);
        entry.next += 1;
        await persistEntry(entry); // a reload resumes after the last confirmed step
      } else if (op.optional) {
        // The visit itself is saved; only the voice part was refused. Its audio is deleted, not retried.
        entry.voiceProblem = r.data && r.data.code ? `${op.what}: ${r.data.code}` : `${op.what}: HTTP ${r.status}`;
        await dropAudio(audioIds(entry));
        for (const o of entry.ops.slice(entry.next)) o.body = null;
        entry.next = entry.ops.length;
        await persistEntry(entry);
      } else {
        entry.status = "failed";
        entry.problem = r.data && r.data.code ? `${op.what}: ${r.data.code}` : `${op.what}: HTTP ${r.status}`;
        for (const o of entry.ops) o.body = null; // a refused visit is not retried, so drop its answers
        await dropAudio(audioIds(entry));
        await persistEntry(entry);
        break;
      }
    }
    if (entry.status === "pending") {
      entry.status = "done";
      for (const op of entry.ops) op.body = null;
      await dropAudio(audioIds(entry));
      await persistEntry(entry);
    }
  }
}

// --- screens ---------------------------------------------------------------------------

const screens = {
  language() {
    const box = raw("div", null, "options");
    for (const [code, , label] of LANGUAGES) {
      const l = raw("label", null, "choice");
      const input = document.createElement("input");
      input.type = "radio";
      input.name = "lang";
      input.value = code;
      input.checked = ui.lang === code;
      input.addEventListener("change", () => { ui.lang = code; render(); });
      l.append(input, raw("span", label));
      box.append(l);
    }
    return [raw("p", "Select an app language. Translations other than English are unreviewed prototype translations; "
      + "strings marked EN have no translation and are shown in English."), box,
    actions(button("Continue →", () => go(auth.token || offline.session ? "home" : offline.stores.length ? "unlock" : "login"),
      "full"))];
  },

  login() {
    return [
      raw("div", "Log in with your synthetic test worker account (for example asha.test1). Accounts and roles come from "
        + "the local identity provider; the app never sees your password.", "notice"),
      actions(button("Enter demo app →", () => login().catch((e) => warn(`Login failed: ${e.message}`)), "full")),
    ];
  },

  home() {
    if (!isAshaWorker()) {
      return [raw("div", "This account does not have the ASHA_WORKER role, so it cannot record assessments.", "alert")];
    }
    const tile = (icon, title, sub, onClick) => {
      const b = raw("button", null, "tile");
      b.type = "button";
      b.append(raw("span", icon), el("strong", title), el("small", sub));
      b.addEventListener("click", onClick);
      return b;
    };
    const cards = raw("div", null, "cards");
    const pending = pendingCount();
    const syncTile = tile("⇅", "Sync data", null, () => (auth.token ? sync() : loginToSync()));
    syncTile.querySelector("small").textContent = `${t("Pending records:").text} ${pending}`;
    cards.append(
      tile("＋", "New assessment", "Start a household visit", () => { discardVisit(); visit = newVisit(); go("registration"); }),
      tile("⌂", "My households", "View assigned visits", () => openList("households")),
      tile("▦", "Follow-ups", "Review pending care", () => openList("followups")),
      syncTile);
    const nodes = [cards, checkbox("Work offline (test: treat the connection as unavailable)", ui.forcedOffline, (v) => {
      ui.forcedOffline = v;
      connectivityChanged();
    })];
    nodes.push(offlineModeBox());
    nodes.push(raw("p", !auth.token ? "Working offline without a login. Log in to sync."
      : auth.workerRef ? `Worker reference: ${auth.workerRef}` : "Signed in as an ASHA worker.", "small"));
    const stale = outbox.filter((e) => e.status === "pending" && Date.now() - Date.parse(e.savedAt) > STALE_MS).length;
    if (stale) {
      nodes.push(raw("div", `${stale} visit(s) have waited more than 3 days to sync. Connect and sync as soon as you can; `
        + "if the device is lost or reset before then, they are lost.", "alert"));
    }
    const failed = outbox.filter((e) => e.status === "failed");
    if (failed.length) {
      nodes.push(raw("div", `${failed.length} assessment(s) were refused by the server: `
        + failed.map((e) => e.problem).join("; "), "alert"));
    }
    const lostUrgent = outbox.filter((e) => e.urgent && e.status !== "done").length;
    if (lostUrgent) {
      nodes.push(el("div", "A visit with a self-harm answer of 'sometimes' or 'often' has not reached the server, so no "
        + "urgent-review flag exists for it yet. Make sure the person has been connected to trained human support and "
        + "tell your supervisor now.", "alert"));
    }
    return nodes;
  },

  unlock() {
    const mine = auth.token ? offline.stores.filter((m) => m.owner === auth.owner) : offline.stores;
    if (!mine.length) {
      return [raw("div", "There are no offline visits for this account on this device.", "notice"),
        actions(button(auth.token ? "Continue →" : "Log in", () => (auth.token ? go("home") : go("login")), "full"))];
    }
    let chosen = mine[0].owner;
    let pin = "";
    const nodes = [];
    if (mine.length > 1) {
      nodes.push(field("Whose visits?", select(mine.map((m) => [m.owner,
        `Saved data ${m.owner.slice(0, 6)} · since ${new Date(m.createdAt).toLocaleDateString()}`]), chosen,
      (v) => { chosen = v; })));
    }
    const input = textInput("", "PIN", (v) => { pin = v; });
    input.type = "password";
    input.inputMode = "numeric";
    input.autocomplete = "off";
    nodes.push(field("PIN", input),
      raw("p", `After ${OfflineStore.MAX_ATTEMPTS} wrong PINs this worker's offline data is deleted.`, "small"),
      actions(button("Unlock", () => unlockWith(chosen, pin), "full")));
    if (!auth.token) nodes.push(actions(button("Log in instead", () => go("login"), "secondary full")));
    nodes.push(actions(button("Forgotten PIN: delete this data", async () => {
      if (!confirm("This deletes every unsynced visit stored for this worker on this device. Continue?")) return;
      await OfflineStore.remove(chosen);
      offline.stores = await OfflineStore.list();
      if (!offline.stores.length) await unregisterShell();
      go(offline.stores.length ? "unlock" : auth.token ? "home" : "login");
    }, "secondary full")));
    return nodes;
  },

  offlineSetup() {
    let pin = "";
    let again = "";
    const box = (placeholder, set) => {
      const i = textInput("", placeholder, set);
      i.type = "password";
      i.inputMode = "numeric";
      i.autocomplete = "new-password";
      return i;
    };
    return [
      raw("div", "Visits you save are kept on this device, encrypted with a key that only your PIN unlocks, until "
        + "they reach the server. The app locks after 5 minutes without use. Choose a PIN nobody else knows: "
        + `after ${OfflineStore.MAX_ATTEMPTS} wrong attempts the stored visits are deleted.`, "notice"),
      field("New PIN (6 to 12 digits)", box("PIN", (v) => { pin = v; })),
      field("Repeat the PIN", box("PIN again", (v) => { again = v; })),
      raw("p", "Test build: use synthetic visits only. A shared device keeps each worker's visits separately.", "small"),
      actions(button("Turn on offline mode", () => enableOffline(pin, again), "full"),
        button("Back", () => go("home"), "secondary")),
    ];
  },

  households() { return listScreen(false); },
  followups() { return listScreen(true); },

  registration() {
    const nodes = [
      field("Beneficiary name or approved identifier", textInput(visit.name, "Enter name or local ID", (v) => { visit.name = v; })),
      field("Village", textInput(visit.village, "Enter village name", (v) => { visit.village = v; })),
      field("Age group", select(AGE, visit.age, (v) => { visit.age = v; render(); })),
      field("Gender (optional)", select(GENDER, visit.gender, (v) => { visit.gender = v; })),
      raw("p", "Use invented names and villages only: this is a test build for synthetic data.", "small"),
    ];
    if (visit.age === "AGE_UNDER_18") {
      nodes.push(el("div", "People under 18 need an approved child pathway. This app cannot assess them; "
        + "the server refuses the visit.", "alert"));
    }
    nodes.push(actions(button("Continue →", () => {
      if (!visit.name.trim() || !visit.village.trim()) return warn("Please enter beneficiary name/identifier and village.");
      if (!visit.age) return warn("Please select the person's age group.");
      if (visit.age === "AGE_UNDER_18") return warn("Use an approved child pathway for people aged 18 and under.");
      go("consent");
    }, "full")));
    return nodes;
  },

  consent() {
    const nodes = [
      el("div", "We would like to ask a few questions about wellbeing, sleep and feelings. Participation is voluntary. "
        + "The person may skip a question or stop at any time. Explain how their information will be used and who may access it.",
      "notice"),
      field("Does the person agree to participate?", radios("consent",
        [["yes", "Yes, informed consent obtained"], ["no", "No, the person declines"]], visit.consent,
        (v) => { visit.consent = v; render(); })),
    ];
    if (visit.consent === "yes") {
      const box = raw("div", null, "options");
      box.append(
        checkbox("The consent wording was read aloud", visit.readAloud, (v) => { visit.readAloud = v; }),
        checkbox("The person confirmed they understood", visit.understood, (v) => { visit.understood = v; }),
        checkbox("A witness was present", visit.witness, (v) => { visit.witness = v; }),
        checkbox("The person confirmed they are 18 or older", visit.adult, (v) => { visit.adult = v; }));
      nodes.push(field("How was consent recorded?", select(CONSENT_METHOD, visit.method, (v) => { visit.method = v; })), box);
      nodes.push(field("If a clinician recommends it, may a referral be shared with a national support programme "
        + "(for example Tele-MANAS or eSanjeevani)? The referral carries no name or phone number; you would connect the "
        + "person yourself, quoting a short code.", radios("sharing", [["yes", "Yes, the person agrees"],
        ["no", "No, do not share"]], visit.sharing, (v) => { visit.sharing = v; })));
      nodes.push(field("Optional: may we record the person's voice for a few seconds? The recording is used to study voice "
        + "and wellbeing. It is not a test and gives no result. The person can say no and still take part.",
      radios("audio", [["yes", "Yes, the person agrees to a voice recording"], ["no", "No voice recording"]], visit.audio,
        (v) => { visit.audio = v; })));
    }
    nodes.push(actions(button("Continue", () => {
      if (!visit.consent) return warn("Please record the person's choice.");
      if (visit.consent === "no") {
        visit = null; // nothing about this person is kept or sent
        return go("declined");
      }
      if (!visit.understood) return warn("Consent needs the person's confirmed understanding.");
      if (!visit.adult) return warn("Only adults can be assessed. Use an approved child pathway for people under 18.");
      if (!visit.sharing) return warn("Please record the person's choice about sharing a referral.");
      if (!visit.audio) return warn("Please record the person's choice about a voice recording.");
      if (visit.method === "VERBAL_WITNESSED" && !visit.witness) return warn("Verbal consent needs a witness.");
      visit.consentAt = isoNow();
      go("health");
    }, "full")));
    return nodes;
  },

  declined() {
    return [el("div", "Thank the person and respect their decision. Do not continue with the assessment.", "notice"),
      raw("p", "Nothing about this person was saved or sent.", "small"),
      actions(button("Return home", () => go("home"), "full"))];
  },

  health() {
    return [el("p", "Does the person report any of the following?"),
      checks("health", HEALTH, visit.health, (code, on) => {
        if (on) {
          visit.health = EXCLUSIVE_HEALTH.includes(code) ? [code]
            : [...visit.health.filter((c) => !EXCLUSIVE_HEALTH.includes(c)), code];
        } else {
          visit.health = visit.health.filter((c) => c !== code);
        }
        render();
      }),
      actions(button("Continue →", () => go("wellness"), "full"))];
  },

  wellness() {
    return [
      field("1. Have you felt nervous, anxious or worried?", radios("anxiety", FREQUENCY, visit.anxiety, (v) => { visit.anxiety = v; })),
      field("2. Have you felt down, sad or hopeless?", radios("sadness", FREQUENCY, visit.sadness, (v) => { visit.sadness = v; })),
      field("3. Have you enjoyed your usual activities?", radios("interest", INTEREST, visit.interest, (v) => { visit.interest = v; })),
      el("p", "Illustrative questions only; do not use these answers to diagnose or calculate an unvalidated score.", "small"),
      actions(button("Continue →", () => go("lifestyle"), "full")),
    ];
  },

  lifestyle() {
    return [
      field("Use of tobacco", radios("tobacco", TOBACCO, visit.tobacco, (v) => { visit.tobacco = v; })),
      field("Use of alcohol", radios("alcohol", ALCOHOL, visit.alcohol, (v) => { visit.alcohol = v; })),
      field("How has the person been sleeping?", radios("sleep", SLEEP, visit.sleep, (v) => { visit.sleep = v; })),
      field("Optional relevant notes", raw("div", "Free-text notes are disabled until a policy on sensitive free text is "
        + "agreed (consent-and-data-lifecycle.md §5). Nothing typed here would be saved.", "notice")),
      actions(button("Continue to safety check →", () => { visit.lifestyleAt = isoNow(); go("safety"); }, "full")),
    ];
  },

  safety() {
    const nodes = [
      el("div", "Important: ask sensitively and in private. This is not a complete clinical risk assessment.", "alert"),
      field("Have you recently had thoughts of harming yourself, or felt that life is not worth living?",
        radios("safety", SAFETY, visit.safety, (v) => { visit.safety = v; render(); })),
    ];
    if (URGENT.includes(visit.safety)) {
      nodes.push(el("div", "Prompt human support: follow the approved safety protocol and promptly connect the person with a "
        + "trained health professional. If there is immediate danger, seek urgent local emergency help. Do not wait for sync.",
      "alert"));
      nodes.push(el("div", "Test build: no approved local safety protocol exists yet. Contact your supervisor now. The "
        + "case will carry an urgent-review flag for human follow-up; this is not a risk score.", "alert"));
      nodes.push(supportContacts());
    }
    nodes.push(actions(button("Review assessment →", () => {
      if (!visit.safety) return warn("Please record a response or prefer-not-to-answer.");
      visit.safetyAt = isoNow();
      go(visit.audio === "yes" ? "voice" : "summary");
    }, "full")));
    return nodes;
  },

  voice() {
    revokePlayback(); // the previous render's players are replaced
    // The consent gate: no recording control exists unless the person agreed to recording.
    if (visit.audio !== "yes") {
      return [el("div", "The person did not agree to a voice recording, so nothing can be recorded.", "notice"),
        actions(button("Review assessment →", () => go("summary"), "full"))];
    }
    const nodes = [el("div", settings.microphone ? "Record in a quiet place, with the phone about 20 cm from the person."
      : "Test build: the microphone is off, so each recording is a generated synthetic voice, not the person.", "notice")];
    for (const [promptId, instruction] of PROMPTS) {
      const done = visit.recordings[promptId];
      const box = raw("div", null, "sidebox");
      box.append(el("p", instruction));
      if (done) {
        box.append(raw("p", `✓ Recorded ${done.seconds.toFixed(1)} s`, "small"));
        const bytes = audio.get(done.recordingId);
        if (bytes) {
          const player = document.createElement("audio");
          player.controls = true;
          player.src = URL.createObjectURL(new Blob([bytes], { type: "audio/wav" }));
          playbackUrls.push(player.src);
          box.append(player);
        }
      }
      box.append(actions(recordButton(promptId, done)));
      nodes.push(box);
    }
    nodes.push(el("p", "No result is shown, now or later: a voice recording is not a diagnosis.", "small"),
      actions(button("Review assessment →", () => go("summary"), "full")));
    return nodes;
  },

  summary() {
    const row = (label, value, literal) => {
      const d = raw("div", null, "summaryrow");
      d.append(el("span", label), literal ? raw("strong", value) : el("strong", value));
      return d;
    };
    const nodes = [
      row("Beneficiary", visit.name, true),
      row("Village", visit.village, true),
      row("Age group", labelOf(AGE, visit.age)),
      row("Consent", "Obtained"),
      row("Safety response", labelOf(SAFETY, visit.safety)),
      row("Voice recordings", visit.audio === "yes" ? String(Object.keys(visit.recordings).length) : "Not consented"),
      field("Follow-up", radios("followup", [["yes", "Flag for trained health professional follow-up"],
        ["no", "No follow-up requested in this demo"]], visit.followup ? "yes" : "no", (v) => { visit.followup = v === "yes"; })),
    ];
    if (URGENT.includes(visit.safety)) {
      nodes.push(el("div", "The safety answer raises an urgent-review flag on this case, whatever you choose above.", "alert"));
    }
    nodes.push(el("p", "No diagnosis or clinical severity score is generated. Confirm referral criteria with a qualified "
      + "mental health professional before deployment.", "small"));
    nodes.push(actions(button("Save assessment", save, "full"), button("Back to safety check", () => go("safety"), "secondary")));
    return nodes;
  },

  complete() {
    const entry = ui.lastEntry;
    const box = raw("div", null, "done");
    const nodes = [box];
    if (entry && entry.status === "done") {
      box.append(raw("div", "✓", "mark"), el("h2", "Assessment saved"), raw("p", "Saved to the wellness API."));
      const c = entry.result || {};
      const facts = [["Case", c.case_id], ["Status", c.status], ["Urgent-review flag", c.urgent_review_flag ? "Raised" : "Not raised"],
        ["Follow-up requested", c.follow_up_requested ? "Yes" : "No"],
        ["Patient card code (give to the person for clinic visits)", entry.card || "—"]];
      if (entry.ops.some((o) => o.audio)) facts.push(["Voice recording", entry.voiceProblem ? "Not uploaded" : "Uploaded"]);
      for (const [k, v] of facts) {
        const d = raw("div", null, "summaryrow");
        d.append(raw("span", k), raw("code", String(v)));
        nodes.push(d);
      }
      if (entry.voiceProblem) {
      nodes.push(raw("div", `The voice recording was not uploaded (${entry.voiceProblem}) and was deleted from this device. `
        + "The rest of the visit is saved.", "notice"));
    }
  } else if (entry && entry.status === "failed") {
      box.append(raw("div", "!", "mark bad"), raw("h2", "Not saved"), raw("p", `The server refused it (${entry.problem}).`));
      if (entry.urgent) {
        nodes.push(el("div", "This visit had a self-harm answer of 'sometimes' or 'often' and it was NOT saved, so no "
          + "urgent-review flag was raised. Contact your supervisor now.", "alert"));
      }
    } else {
      box.append(raw("div", "⇅", "mark wait"), raw("h2", "Saved for sync"), raw("p", offline.session
        ? "Stored encrypted on this device until it syncs. It stays if the page is closed or the phone restarts."
        : "Kept in this page's memory until you sync. Reloading or closing the page discards it."));
    }
    nodes.push(actions(button("Start another assessment", () => { visit = newVisit(); go("registration"); }, "full"),
      button("Back to dashboard", () => go("home"), "secondary")));
    return nodes;
  },
};

let stopMicrophone = null;
let playbackUrls = [];

/** Stops a running recording and throws its samples away. */
function cancelMicrophone() {
  if (!stopMicrophone) return;
  const stop = stopMicrophone;
  stopMicrophone = null;
  stop().catch(() => {});
}

function revokePlayback() {
  for (const u of playbackUrls) URL.revokeObjectURL(u);
  playbackUrls = [];
}
function recordButton(promptId, done) {
  if (!settings.microphone) {
    return button(done ? "Record again (synthetic)" : "Record (synthetic test voice)",
      () => keepRecording(promptId, Voice.synthetic(SYNTHETIC_SECONDS), "SYNTHETIC"), "secondary");
  }
  if (stopMicrophone && stopMicrophone.promptId === promptId && stopMicrophone.participant === visit.ids.participant) {
    return button("Stop recording", async () => {
      const stop = stopMicrophone;
      stopMicrophone = null;
      const pcm = await stop();
      if (!visit || visit.ids.participant !== stop.participant || visit.audio !== "yes") return; // never another visit's
      await keepRecording(promptId, pcm, "AUTHORIZED_TEST_AUDIO");
    });
  }
  return button(done ? "Record again" : "Start recording", async () => {
    if (stopMicrophone) return warn("Stop the other recording first.");
    try {
      stopMicrophone = await Voice.microphone();
      stopMicrophone.promptId = promptId;
      stopMicrophone.participant = visit.ids.participant; // the recording belongs to this visit only
      render();
    } catch (e) {
      warn(`The microphone could not be used (${e.message}).`);
    }
  }, "secondary");
}

async function keepRecording(promptId, pcm, origin) {
  let rec;
  try {
    rec = await Voice.recording(pcm);
  } catch (e) {
    return warn(e.message);
  }
  const previous = visit.recordings[promptId];
  if (previous) await dropAudio([previous.recordingId]);
  // One session has one data origin, so a visit never mixes synthetic and real recordings (review B2).
  const others = Object.entries(visit.recordings).filter(([id, r]) => id !== promptId && r.origin !== origin);
  if (others.length) {
    await dropAudio(others.map(([, r]) => r.recordingId));
    for (const [id] of others) delete visit.recordings[id];
    ui.message = "The microphone setting changed, so the earlier recordings for this visit were deleted. Record them again.";
  }
  const recordingId = randomId("rec");
  visit.ids.session = visit.ids.session || randomId("sess");
  audio.set(recordingId, rec.bytes);
  if (offline.session) await offline.session.put("audio:" + recordingId, rec.bytes).catch(storageFailed);
  visit.recordings[promptId] = { recordingId, seconds: rec.seconds, sha256: rec.sha256, size: rec.bytes.length,
    recordedAt: isoNow(), origin };
  await persistVisit();
  render();
}

function listScreen(followUpsOnly) {
  const nodes = [raw("p", "Names and villages are not shown: they are stored write-only in the identity store.", "small")];
  const listing = ui.listing;
  if (!ui.online) {
    nodes.push(raw("div", "Connect to the internet to see your visits.", "notice"));
  } else if (!listing || listing.followUpsOnly !== followUpsOnly) {
    nodes.push(raw("p", "Loading…"));
  } else if (listing.error) {
    nodes.push(raw("div", listing.error, "alert"));
  } else if (listing.cases.length === 0) {
    nodes.push(raw("div", followUpsOnly ? "No visits need follow-up." : "No visits recorded yet.", "notice"));
  } else {
    const ul = raw("ul", null, "caselist");
    for (const c of listing.cases) {
      const li = raw("li");
      const head = raw("div");
      head.append(raw("code", c.case_id));
      if (c.urgent_review_flag) head.append(raw("span", "Urgent review", "badge urgent"));
      if (c.follow_up_requested) head.append(raw("span", "Follow-up", "badge"));
      const meta = raw("div", null, "meta");
      meta.textContent = `${c.status} · ${t(labelOf(AGE, c.age_band)).text} · ${c.language_tag} · ${new Date(c.created_at_utc).toLocaleString()}`;
      li.append(head, meta);
      if (c.review && c.review.status !== "NOT_REQUIRED") li.append(reviewLine(c.review));
      if (c.review && ["REFER", "URGENT_REFER"].includes(c.review.decision)) li.append(referralLines(c.case_id));
      ul.append(li);
    }
    nodes.push(ul);
  }
  if (followUpsOnly && listing && listing.tasks && listing.tasks.length) {
    nodes.push(raw("h2", "Tasks from the clinic"));
    nodes.push(raw("p", "Asked by the facility after a clinic visit. The clinic's notes and assessments are not shown here.", "small"));
    const ul = raw("ul", null, "caselist");
    for (const task of listing.tasks) ul.append(taskItem(task));
    nodes.push(ul);
  }
  nodes.push(actions(button("Back to dashboard", () => go("home"), "secondary full")));
  return nodes;
}

// The clinician's latest decision (API 1.2): the worker acts on it, because only they know the person.
const DECISION_TEXT = { NO_FURTHER_ACTION: "No further action", MONITOR: "Monitor", REFER: "Refer",
  URGENT_REFER: "Urgent referral" };
const WITHIN_TEXT = { NONE: "", WITHIN_24_HOURS: "within 24 hours", WITHIN_7_DAYS: "within 7 days",
  WITHIN_30_DAYS: "within 30 days" };
function reviewLine(review) {
  if (review.status === "AWAITING_REVIEW") return raw("div", "Awaiting clinician review", "review pending");
  const text = `Clinician: ${DECISION_TEXT[review.decision]} ${WITHIN_TEXT[review.follow_up_within]}`.trim();
  return raw("div", `${text} · ${new Date(review.reviewed_at_utc).toLocaleDateString()}`,
    review.decision === "URGENT_REFER" ? "review urgent" : "review");
}

// Programme support contacts (API 1.3). Loaded once after login so they are at hand offline.
let contacts = [];
async function loadContacts() {
  try {
    const r = await api("GET", "/v1/programme/support-contacts");
    if (r.status === 200) {
      contacts = r.data.contacts;
      if (offline.session) await offline.session.put("contacts", contacts); // at hand after a reload offline
    }
  } catch (e) {
    contacts = []; // offline at login: the alert still tells the worker to contact their supervisor
  }
}
function supportContacts() {
  const box = raw("div", null, "contacts");
  if (!contacts.length) {
    box.append(raw("p", "No support contacts loaded (offline at login?). Contact your supervisor.", "small"));
    return box;
  }
  for (const c of contacts) {
    const a = raw("a", `${c.service_name}: ${c.dial_number}`, "contact");
    a.href = c.tel_uri;
    const row = raw("div");
    row.append(a);
    if (c.hours) row.append(raw("span", ` · ${c.hours}`, "small"));
    if (!c.verified) row.append(raw("span", "Pending clinical-lead verification", "badge"));
    box.append(row);
  }
  return box;
}

// Partner referrals (API 1.3): the worker connects the person, quoting the handoff code (warm handoff).
function referralLines(caseId) {
  const box = raw("div", null, "referrals");
  api("GET", `/v1/cases/${caseId}/referrals`).then((r) => {
    if (r.status !== 200) return;
    for (const ref of r.data.referrals) {
      const dead = ref.status === "REVOKED" || ref.status === "DELIVERY_FAILED";
      // A revoked or undelivered referral's code must not be quoted, so it isn't shown.
      box.append(raw("div", `Referred to ${ref.partner_name} · ${ref.status.replace(/_/g, " ").toLowerCase()}`
        + (dead ? "" : ` · code ${ref.handoff_code}`), dead ? "referral bad" : "referral"));
    }
    if (r.data.referrals.length) {
      box.append(raw("div", "Quote the code when you call the programme with the person.", "small"));
    }
  }).catch(() => {});
  return box;
}

const TASK_TEXT = { HOME_VISIT: "Home visit", CHECK_IN_CALL: "Check-in call", ACCOMPANY_TO_FACILITY: "Accompany to the facility",
  TREATMENT_ADHERENCE_CHECK: "Check treatment is being followed" };

/** One clinic follow-up task (API 1.5) with buttons to record the outcome. */
function taskItem(task) {
  const li = raw("li");
  const head = raw("div");
  head.append(raw("strong", TASK_TEXT[task.task_type] || task.task_type), raw("span", task.facility_label, "badge"));
  const meta = raw("div", `Due ${new Date(task.due_by_utc).toLocaleDateString()} · ${task.participant_ref}`, "meta");
  li.append(head, meta);
  const row = raw("div", null, "actions");
  for (const [outcome, label] of [["DONE", "Done"], ["NOT_REACHABLE", "Could not reach"], ["DECLINED", "Person declined"]]) {
    const b = button(label, async () => {
      const r = await api("POST", `/v1/care-tasks/${task.task_id}/completion`, { completed_at_utc: isoNow(), outcome },
        crypto.randomUUID());
      ui.message = r.status === 201 || r.status === 200 ? null : `Not recorded (${r.data && r.data.code || r.status}).`;
      await openList("followups");
    }, "secondary");
    row.append(b);
  }
  li.append(row);
  return li;
}

async function openList(page) {
  const followUpsOnly = page === "followups";
  ui.listing = null;
  go(page);
  if (!ui.online) return;
  try {
    const r = await api("GET", `/v1/cases?limit=50${followUpsOnly ? "&follow_up=true" : ""}`);
    ui.listing = r.status === 200 ? { followUpsOnly, cases: r.data.cases }
      : { followUpsOnly, cases: [], error: `Could not load visits (${r.data && r.data.code || r.status}).` };
    if (followUpsOnly && r.status === 200) {
      const t = await api("GET", "/v1/care-tasks?status=OPEN");
      ui.listing.tasks = t.status === 200 ? t.data.tasks : [];
    }
  } catch (e) {
    ui.listing = { followUpsOnly, cases: [], error: "Could not reach the server." };
  }
  if (ui.page === page) render();
}

async function save() {
  const entry = { id: randomId("ob"), savedAt: isoNow(), ops: operations(visit, isoNow()), next: 0, status: "pending",
    problem: null, result: null, urgent: URGENT.includes(visit.safety) }; // kept even when the answers are dropped
  outbox.push(entry);
  ui.lastEntry = entry;
  revokePlayback();
  visit = null; // the outbox entry now holds the only copy
  await persistEntry(entry);
  go("complete");
  if (ui.online) await flush();
}

async function sync() {
  if (!ui.online) return warn("No connection. Records remain pending sync.");
  if (!pendingCount()) return warn("Nothing to sync.");
  await flush();
}

// --- offline mode (ADR-017) -------------------------------------------------------------

/** Saves the visit in progress (or removes it) when offline mode is on. Never throws into the UI flow. */
function persistVisit() {
  if (!offline.session) return Promise.resolve();
  const s = offline.session;
  return (visit ? s.put("visit", visit) : s.delete("visit")).catch(storageFailed);
}

function persistEntry(entry) {
  if (!offline.session) return Promise.resolve();
  return offline.session.put("entry:" + entry.id, entry).catch(storageFailed);
}

function storageFailed(e) {
  ui.message = `Could not save on this device (${e.message}). Sync as soon as you can.`;
}

/** Loads the unlocked store into memory, dropping finished visits older than 7 days. */
async function loadStore(session) {
  offline.session = session;
  outbox.length = 0;
  for (const [id, entry] of await session.all("entry:")) {
    if (entry.status !== "pending" && Date.now() - Date.parse(entry.savedAt) > KEEP_FINISHED_MS) {
      await session.delete(id);
    } else {
      outbox.push(entry);
    }
  }
  outbox.sort((a, b) => a.savedAt.localeCompare(b.savedAt));
  visit = (await session.get("visit")) || null;
  contacts = (await session.get("contacts")) || contacts;
  // Audio saved just before a crash may have no visit or entry pointing at it (review I2).
  const referenced = new Set([...outbox.filter((e) => e.status === "pending").flatMap(audioIds),
    ...Object.values((visit && visit.recordings) || {}).map((r) => r.recordingId)]);
  for (const id of await session.keys("audio:")) {
    if (!referenced.has(id.slice("audio:".length))) await session.delete(id);
  }
  touch();
}

function touch() {
  if (!offline.session) return;
  clearTimeout(offline.idleTimer);
  offline.idleTimer = setTimeout(lock, IDLE_LOCK_MS);
}

/** Drops the key and everything decrypted from memory. The data stays on the device, encrypted. */
async function lock() {
  if (!offline.session) return;
  if (flushing) { touch(); return; } // never lock in the middle of a sync step
  cancelMicrophone();
  revokePlayback();
  await persistVisit();
  offline.session = null;
  visit = null;
  outbox.length = 0;
  audio.clear();
  ui.lastEntry = null;
  offline.stores = await OfflineStore.list();
  go("unlock", "Locked after 5 minutes without use. Enter your PIN to continue.");
}

function connectivityChanged() {
  ui.online = navigator.onLine && !ui.forcedOffline;
  render();
  if (ui.online && pendingCount() && auth.token) flush();
}

/** Logging in reloads the page, so the visit in progress is saved first. Offline mode only. */
async function loginToSync() {
  if (!ui.online) return warn("No connection. Records remain pending sync.");
  if (!offline.session) return warn("Log in to sync.");
  await persistVisit();
  login().catch((e) => warn(`Login failed: ${e.message}`));
}

/** The service worker caches the app's own files only. It's registered only while offline mode is in use. */
async function registerShell() {
  if (!("serviceWorker" in navigator)) return;
  try {
    await navigator.serviceWorker.register("sw.js", { scope: "/asha/" });
  } catch (e) {
    ui.message = `The app could not be prepared for offline use (${e.message}).`;
  }
  if (navigator.storage && navigator.storage.persist) {
    await navigator.storage.persist().catch(() => false);
    const est = await navigator.storage.estimate().catch(() => null);
    offline.quota = est ? { used: est.usage, total: est.quota } : null;
  }
}

async function unregisterShell() {
  if (!("serviceWorker" in navigator)) return;
  for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
  for (const k of await caches.keys()) await caches.delete(k);
}

async function enableOffline(pin, again) {
  if (!auth.token) return warn("Log in first: offline mode is set up for your worker account.");
  if (!OfflineStore.PIN_PATTERN.test(pin)) return warn("The PIN must be 6 to 12 digits.");
  if (pin !== again) return warn("The two PINs do not match.");
  try {
    const session = await OfflineStore.create(auth.owner, pin);
    offline.session = session;
    for (const entry of outbox) {
      entry.id = entry.id || randomId("ob");
      entry.savedAt = entry.savedAt || isoNow();
      await persistEntry(entry);
    }
    await persistVisit();
    for (const [id, bytes] of audio) await session.put("audio:" + id, bytes);
    if (contacts.length) await session.put("contacts", contacts);
    touch();
    await registerShell();
    go("home");
  } catch (e) {
    warn(`Offline mode was not turned on: ${e.message}`);
  }
}

async function disableOffline() {
  const unsynced = pendingCount() + (visit ? 1 : 0);
  if (unsynced && !confirm(`${unsynced} visit(s) have not synced and will be deleted from this device. Turn offline mode off anyway?`)) {
    return;
  }
  await OfflineStore.remove(offline.session.owner);
  offline.session = null;
  clearTimeout(offline.idleTimer);
  if (unsynced) {
    outbox.length = 0;
    visit = null;
  }
  offline.stores = await OfflineStore.list();
  if (!offline.stores.length) await unregisterShell();
  render();
}

async function unlockWith(owner, pin) {
  try {
    await loadStore(await OfflineStore.unlock(owner, pin));
    await registerShell();
    go(visit ? visit.page || "registration" : "home");
    if (auth.token && ui.online && pendingCount()) flush();
  } catch (e) {
    if (e instanceof OfflineStore.WrongPin) {
      warn(`Wrong PIN. ${e.remaining} attempt(s) left before this worker's offline data is deleted.`);
    } else if (e instanceof OfflineStore.Wiped) {
      offline.stores = await OfflineStore.list();
      go(offline.stores.length ? "unlock" : auth.token ? "home" : "login", `${e.message}.`);
    } else {
      warn(`Could not unlock: ${e.message}`);
    }
  }
}

function offlineModeBox() {
  const box = raw("div", null, "sidebox");
  if (offline.session) {
    const used = offline.quota ? ` · ${(offline.quota.used / 1048576).toFixed(1)} MB used on this device` : "";
    box.append(raw("p", `Offline mode is on: visits are kept on this device, encrypted, until they sync${used}.`, "small"),
      actions(button("Lock now", lock, "secondary"), button("Turn offline mode off", disableOffline, "secondary")));
  } else if (auth.token) {
    box.append(raw("p", "Offline mode is off: unsynced visits are lost if the page reloads.", "small"),
      actions(button("Turn on offline mode", () => go("offlineSetup"), "secondary")));
  }
  return box;
}

async function start() {
  try {
    const r = await fetch("settings.json");
    if (r.ok) settings.microphone = (await r.json()).microphone_enabled === true;
  } catch (e) {
    settings.microphone = false; // offline and not cached: the safe default
  }
  let loggedIn = false;
  try {
    loggedIn = await completeLogin();
  } catch (e) {
    ui.page = "login";
    ui.message = `Login failed: ${e.message}`;
    return;
  }
  offline.stores = await OfflineStore.list().catch(() => []);
  if (loggedIn) {
    loadContacts();
    // A worker with offline data unlocks it first, so new and stored visits share one outbox.
    ui.page = offline.stores.some((m) => m.owner === auth.owner) ? "unlock" : "home";
  } else {
    ui.page = "language";
  }
}

// --- rendering -------------------------------------------------------------------------

function render() {
  untranslated = new Set();
  const page = ui.page;
  document.documentElement.lang = language()[1];
  document.documentElement.dir = ui.lang === "ur" ? "rtl" : "ltr";
  const setText = (id, s) => {
    const r = t(s);
    $(id).textContent = r.text;
    $(id).classList.toggle("untr", r.missing);
  };
  setText("screenTitle", TITLES[page] || "ASHA Care");
  setText("screenSub", SUBS[page] || "");
  const index = Math.max(0, STEPS.indexOf(page));
  $("stepLabel").textContent = `${index} · ${t(page === "language" ? "Welcome" : "Assessment in progress").text}`;
  $("progress").value = index;

  for (const node of document.querySelectorAll("[data-t]")) {
    const r = t(node.dataset.t);
    node.textContent = r.text;
    node.classList.toggle("untr", r.missing);
  }
  const content = $("screen");
  const nodes = screens[page]();
  if (ui.message) nodes.unshift(raw("div", ui.message, "alert"));
  content.replaceChildren(...nodes);
  updateSide();
}

function updateSide() {
  const conn = $("connection");
  conn.textContent = "● " + t(ui.online ? "Internet available" : "Offline — save for later").text
    + (offline.session ? " · offline mode" : "");
  conn.classList.toggle("online", ui.online);
  $("logout").hidden = !auth.token;

  const pending = pendingCount();
  const done = outbox.filter((e) => e.status === "done").length;
  const failed = outbox.filter((e) => e.status === "failed").length;
  $("saveStatus").textContent = visit ? t("Assessment in progress").text
    : outbox.length ? `${done} saved · ${pending} pending · ${failed} refused` : t("No assessment started").text;
  const status = $("syncStatus");
  status.textContent = pending ? `${t("Pending sync").text} (${pending})` : outbox.length ? "Synced" : t("Not synced").text;
  status.className = "status " + (failed ? "bad" : pending ? "" : outbox.length ? "ok" : "");

  const card = $("langCard");
  card.hidden = ui.lang === "en";
  if (!card.hidden) {
    $("langNote").textContent = `${language()[2]}: unreviewed prototype translation. `
      + (untranslated.size ? `${untranslated.size} text(s) on this screen have no translation and are shown in English (marked EN).`
        : "Every text on this screen has a prototype translation.");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  $("logout").addEventListener("click", logout);
  window.addEventListener("beforeunload", (e) => {
    if (!offline.session && (visit || pendingCount())) e.preventDefault(); // unsynced data lives only in this page
  });
  window.addEventListener("online", connectivityChanged);
  window.addEventListener("offline", connectivityChanged);
  for (const type of ["pointerdown", "keydown"]) document.addEventListener(type, touch, { passive: true });
  start().finally(render);
});
