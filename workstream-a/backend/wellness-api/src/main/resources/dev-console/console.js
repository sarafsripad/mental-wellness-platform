// Wellness Test Console — local development only. Synthetic data only.
// No framework, no third-party code. All output goes through textContent (never innerHTML).
// The access token lives in memory only; the PKCE verifier sits in sessionStorage just across the
// login redirect and is removed straight after the code exchange.
"use strict";

const KEYCLOAK = "http://localhost:8081/realms/wellness-local/protocol/openid-connect";
const CLIENT_ID = "wellness-dev-console";
const REDIRECT_URI = location.origin + "/dev-console/";
const PROFILE = "EGEMAPS_V02_FUNCTIONALS_V1";

const SCOPES = [
  ["SCREENING_PARTICIPATION", "Screening participation"],
  ["AUDIO_RECORDING", "Audio recording (needs screening)"],
  ["AUDIO_RETENTION", "Audio retention (needs recording)"],
  ["RESEARCH_FEATURE_REUSE", "Research feature reuse (needs recording)"],
  ["MODEL_TRAINING", "Model training (needs research reuse)"],
  ["QUALITY_REVIEW", "Quality review (needs recording)"],
];

// Same signal model as scripts/generate-synthetic-audio.py: [seconds, peak, noise, clip, bursts, expected]
const VARIANTS = {
  voice: [6.0, 0.45, 0.002, false, true, "Expected QC: PASS → job SUCCEEDED"],
  noisy: [6.0, 0.30, 0.03, false, true, "Expected QC: DEGRADED (HIGH_NOISE) → job SUCCEEDED with flags"],
  drowned: [6.0, 0.30, 0.08, false, true, "Expected QC: FAIL (HIGH_NOISE) → job FAILED, no feature record"],
  clipped: [6.0, 3.0, 0.002, true, true, "Expected QC: DEGRADED (CLIPPING) → job SUCCEEDED with flags"],
  silent: [6.0, 0.0, 0.0, false, false, "Expected QC: FAIL (SILENCE) → job FAILED, no feature record"],
  short: [1.5, 0.45, 0.002, false, true, "1.5 s: the API rejects it at registration (minimum 3 s)"],
};

const state = { token: null, claims: null, participant: null, consentEvent: null, audio: null };
const $ = (id) => document.getElementById(id);

// --- small helpers -----------------------------------------------------------------

function hex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
function randomId(prefix) {
  return `${prefix}_${hex(crypto.getRandomValues(new Uint8Array(16)))}`;
}
function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
async function sha256(bytes) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)));
}
function isoNow(offsetSeconds = 0) {
  return new Date(Date.now() + offsetSeconds * 1000).toISOString().replace(/\.\d{3}Z$/, "Z");
}
function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function setFacts(dl, pairs) {
  dl.replaceChildren();
  for (const [k, v, cls] of pairs) {
    dl.append(el("dt", k));
    dl.append(el("dd", v === null || v === undefined ? "—" : String(v), cls));
  }
}
function step(text, cls) {
  const li = el("li", text, cls);
  $("steps").append(li);
  return li;
}

// --- login (authorization code + PKCE) -----------------------------------------------

async function login() {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(48)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  const stateParam = randomId("st");
  sessionStorage.setItem("pkce", JSON.stringify({ verifier, state: stateParam }));
  const params = new URLSearchParams({ client_id: CLIENT_ID, redirect_uri: REDIRECT_URI, response_type: "code",
    scope: "openid", code_challenge: challenge, code_challenge_method: "S256", state: stateParam });
  location.assign(`${KEYCLOAK}/auth?${params}`);
}

async function completeLogin() {
  const params = new URLSearchParams(location.search);
  const code = params.get("code");
  if (!code) return;
  const saved = JSON.parse(sessionStorage.getItem("pkce") || "null");
  sessionStorage.removeItem("pkce");
  history.replaceState(null, "", REDIRECT_URI); // drop code and state from the URL and history
  if (!saved || saved.state !== params.get("state")) {
    $("who").textContent = "Login failed: state mismatch";
    return;
  }
  const r = await fetch(`${KEYCLOAK}/token`, { method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", client_id: CLIENT_ID, code,
      redirect_uri: REDIRECT_URI, code_verifier: saved.verifier }) });
  if (!r.ok) {
    $("who").textContent = `Login failed (${r.status})`;
    return;
  }
  const body = await r.json();
  state.token = body.access_token;
  const payload = body.access_token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
  state.claims = JSON.parse(atob(payload + "=".repeat((4 - (payload.length % 4)) % 4)));
  renderAuth();
}

function logout() {
  state.token = null;
  location.assign(`${KEYCLOAK}/logout?client_id=${CLIENT_ID}&post_logout_redirect_uri=${encodeURIComponent(REDIRECT_URI)}`);
}

function renderAuth() {
  const loggedIn = Boolean(state.token);
  $("login").hidden = loggedIn;
  $("logout").hidden = !loggedIn;
  for (const id of ["record-consent", "consent-status", "withdraw", "process"]) $(id).disabled = !loggedIn;
  if (!loggedIn) {
    $("who").textContent = "Not logged in";
    return;
  }
  const roles = (state.claims.realm_access && state.claims.realm_access.roles || [])
    .filter((r) => ["ASHA_WORKER", "CLINICIAN", "ADMIN"].includes(r));
  const minutes = Math.max(0, Math.round((state.claims.exp * 1000 - Date.now()) / 60000));
  $("who").textContent = `Logged in · roles: ${roles.join(", ") || "none"} · subject ${state.claims.sub.slice(0, 8)}… · token expires in ${minutes} min`;
  $("process").disabled = !loggedIn || !state.consentEvent;
}

// --- API calls -------------------------------------------------------------------------

async function api(method, path, { json, bytes, headers = {} } = {}) {
  if (!state.token) throw new Error("Log in first");
  const h = { Authorization: `Bearer ${state.token}`, "X-Correlation-ID": crypto.randomUUID(), ...headers };
  let body;
  if (json !== undefined) {
    h["Content-Type"] = "application/json";
    body = JSON.stringify(json);
  } else if (bytes !== undefined) {
    body = bytes;
  }
  if (method === "POST" && !h["Idempotency-Key"]) h["Idempotency-Key"] = crypto.randomUUID();
  const r = await fetch(path, { method, headers: h, body });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  logCall(method, path, r.status, r.headers.get("X-Correlation-ID"), json, data);
  return { status: r.status, data };
}

function logCall(method, path, status, correlation, request, response) {
  const li = el("li");
  const details = el("details");
  const cls = status < 300 ? "ok" : status < 500 ? "warn" : "bad";
  const summary = el("summary");
  summary.append(el("span", `${status} `, cls), el("span", `${method} ${path}`),
    el("span", correlation ? `  · correlation ${correlation}` : "", "sub"));
  details.append(summary);
  if (request !== undefined) details.append(el("pre", "Request: " + JSON.stringify(request, null, 2)));
  details.append(el("pre", "Response: " + (typeof response === "string" ? response : JSON.stringify(response, null, 2))));
  li.append(details);
  $("log").prepend(li);
}

function problemText(data) {
  return data && data.code ? `${data.code}: ${data.detail || data.title}` : JSON.stringify(data);
}

// --- consent ---------------------------------------------------------------------------

function buildScopeBoxes() {
  for (const [name, label] of SCOPES) {
    const l = el("label");
    const box = el("input");
    box.type = "checkbox"; box.value = name; box.checked = true;
    l.append(box, el("span", label));
    $("scopes").append(l);
    const w = el("label");
    const wbox = el("input");
    wbox.type = "checkbox"; wbox.value = name;
    w.append(wbox, el("span", name));
    $("withdraw-scopes").append(w);
  }
}

function checked(fieldset) {
  return Array.from(fieldset.querySelectorAll("input:checked"), (i) => i.value);
}

async function recordConsent() {
  const participant = randomId("ppt");
  const consentEvent = randomId("ce");
  const granted = new Set(checked($("scopes")));
  const { status, data } = await api("POST", "/v1/consent-events", { json: {
    consent_event_id: consentEvent, participant_ref: participant,
    consent_form: { form_id: "screening-consent", form_version: "0.1.0", language_tag: $("language").value },
    scope_decisions: SCOPES.map(([s]) => ({ scope: s, granted: granted.has(s) })),
    capture: { captured_at_utc: isoNow(-60), method: "SIGNED_ON_DEVICE", read_aloud: true, comprehension_confirmed: true },
    safeguards: { adult_confirmed: $("adult").checked } } });
  if (status === 201) {
    state.participant = participant;
    state.consentEvent = consentEvent;
    setFacts($("participant"), [["Participant", participant], ["Consent event", consentEvent],
      ["Status", data.status, "ok"], ["Effective scopes", data.effective_scopes.join(", ") || "none"]]);
  } else {
    setFacts($("participant"), [["Refused", `${status} ${problemText(data)}`, "bad"]]);
  }
  renderAuth();
}

async function consentStatus() {
  if (!state.participant) return setFacts($("participant"), [["Status", "Record consent first", "warn"]]);
  const { status, data } = await api("GET", `/v1/participants/${state.participant}/consent-status`);
  setFacts($("participant"), status === 200
    ? [["Participant", state.participant], ["Consent event", data.active_consent_event_id], ["Status", data.status],
       ["Effective scopes", data.effective_scopes.join(", ") || "none"]]
    : [["Refused", `${status} ${problemText(data)}`, "bad"]]);
}

async function withdraw() {
  if (!state.consentEvent) return setFacts($("participant"), [["Withdraw", "Record consent first", "warn"]]);
  const scopes = checked($("withdraw-scopes"));
  const json = { withdrawal_id: randomId("cw"), withdrawn_at_utc: isoNow(-5), channel: "IN_PERSON_ASHA" };
  if (scopes.length) json.scopes = scopes;
  const { status, data } = await api("POST", `/v1/consent-events/${state.consentEvent}/withdrawals`, { json });
  setFacts($("participant"), status < 300
    ? [["Withdrawn", data.withdrawn_scopes.join(", ") || "nothing new (no-op)"], ["Resulting status", data.resulting_status],
       ["Follow-up actions", data.downstream_actions.map((a) => `${a.action}: ${a.status}`).join("; ") || "none"]]
    : [["Refused", `${status} ${problemText(data)}`, "bad"]]);
}

// --- synthetic audio -------------------------------------------------------------------

function mulberry32(seed) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function synthesise(variant) {
  const [seconds, peak, noise, clip, bursts] = VARIANTS[variant];
  const rate = 16000;
  const n = Math.floor(rate * seconds);
  const rand = mulberry32(7);
  const gauss = () => Math.sqrt(-2 * Math.log(rand() || 1e-12)) * Math.cos(2 * Math.PI * rand());
  const pcm = new Int16Array(n);
  const syllable = 0.22, period = 0.34;
  let phase = 0;
  for (let i = 0; i < n; i++) {
    const t = i / rate;
    const within = t % period;
    const envelope = bursts && within < syllable ? Math.sin(Math.PI * within / syllable) ** 2 : 0;
    const f0 = 140 + 25 * Math.sin(2 * Math.PI * 0.35 * t) + (rand() * 3 - 1.5);
    phase += 2 * Math.PI * f0 / rate;
    let voiced = 0;
    for (let k = 1; k <= 5; k++) voiced += Math.sin(k * phase) / k;
    let x = peak || noise ? peak * envelope * voiced / 1.6 + gauss() * noise : 0;
    if (clip) x = Math.max(-1, Math.min(1, x));
    pcm[i] = Math.round(Math.max(-1, Math.min(1, x)) * 32767);
  }
  const buf = new ArrayBuffer(44 + pcm.byteLength);
  const v = new DataView(buf);
  const ascii = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  ascii(0, "RIFF"); v.setUint32(4, 36 + pcm.byteLength, true); ascii(8, "WAVE");
  ascii(12, "fmt "); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  ascii(36, "data"); v.setUint32(40, pcm.byteLength, true);
  new Int16Array(buf, 44).set(pcm);
  return { bytes: new Uint8Array(buf), seconds };
}

function generate() {
  state.audio = { variant: $("variant").value, ...synthesise($("variant").value) };
  const preview = $("preview");
  if (preview.src) URL.revokeObjectURL(preview.src);
  preview.src = URL.createObjectURL(new Blob([state.audio.bytes], { type: "audio/wav" }));
}

// --- session, upload, processing -------------------------------------------------------

async function process() {
  $("steps").replaceChildren();
  setFacts($("job"), []);
  if (!state.consentEvent) return step("Record consent first", "warn");
  if (!state.audio || state.audio.variant !== $("variant").value) generate();
  const { bytes, seconds } = state.audio;
  const sessionId = randomId("sess");
  const recordingId = randomId("rec");

  let r = await api("POST", "/v1/voice-sessions", { json: {
    session_id: sessionId, participant_ref: state.participant, consent_event_id: state.consentEvent,
    language_tag: $("language").value, prompt_set: { prompt_set_id: "baseline-voice-tasks", prompt_set_version: "0.1.0" },
    captured_at_utc: isoNow(-10), device: { device_class: "UNKNOWN", app_version: "0.1.0", os_family: "ANDROID" },
    data_origin: "SYNTHETIC" } });
  if (r.status !== 201) return step(`Session refused: ${r.status} ${problemText(r.data)}`, "bad");
  step(`Session created (${sessionId})`, "ok");

  const sha = await sha256(bytes);
  r = await api("POST", `/v1/voice-sessions/${sessionId}/recordings`, { json: {
    recording_id: recordingId, prompt_id: "sustained-vowel-a", format: "WAV_PCM_S16LE", sampling_rate_hz: 16000,
    channels: 1, duration_seconds: seconds, size_bytes: bytes.length, sha256: sha, recorded_at_utc: isoNow(-5) } });
  if (r.status !== 201) return step(`Recording refused: ${r.status} ${problemText(r.data)}`, "bad");
  step(`Recording registered (${(bytes.length / 1024).toFixed(0)} KB, SHA-256 ${sha.slice(0, 12)}…)`, "ok");

  r = await api("PUT", `/v1/voice-sessions/${sessionId}/recordings/${recordingId}/content`,
    { bytes, headers: { "Content-Type": "audio/wav", "X-Content-SHA256": sha } });
  if (r.status !== 200) return step(`Upload refused: ${r.status} ${problemText(r.data)}`, "bad");
  step("Audio uploaded (stored encrypted)", "ok");

  r = await api("POST", "/v1/processing-jobs", { json: { recording_id: recordingId, pipeline_profile: PROFILE } });
  if (r.status !== 202 && r.status !== 200) return step(`Job refused: ${r.status} ${problemText(r.data)}`, "bad");
  const jobId = r.data.job_id;
  const waiting = step(`Job ${jobId} queued; waiting for the worker…`);

  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    const j = await api("GET", `/v1/processing-jobs/${jobId}`);
    if (j.status !== 200) return step(`Status check refused: ${j.status} ${problemText(j.data)}`, "bad");
    const job = j.data;
    const quality = job.quality_outcome ? `${job.quality_outcome.status} ${job.quality_outcome.flags.join(", ")}` : "not yet";
    setFacts($("job"), [["Job status", job.status, job.status === "SUCCEEDED" ? "ok" : job.status === "FAILED" ? "bad" : ""],
      ["Attempt", job.attempt], ["Audio quality", quality], ["Feature record", job.feature_record_id],
      ["Failure", job.failure ? `${job.failure.code} (retryable: ${job.failure.retryable})` : null],
      ["Feature values", "never shown: not meaningful to field staff, and not a screening result"]]);
    if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(job.status)) {
      waiting.textContent = `Job ${jobId} finished: ${job.status}`;
      waiting.className = job.status === "SUCCEEDED" ? "ok" : "bad";
      const s = await api("GET", `/v1/voice-sessions/${sessionId}`);
      if (s.status === 200) step(`Session status: ${s.data.status}`);
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  waiting.textContent = "Still not finished after 3 minutes. Is the worker running?";
  waiting.className = "warn";
}

// --- wiring ----------------------------------------------------------------------------

function guard(fn) {
  return () => fn().catch((e) => step(`Error: ${e.message}`, "bad"));
}

document.addEventListener("DOMContentLoaded", () => {
  buildScopeBoxes();
  for (const [name, spec] of Object.entries(VARIANTS)) $("variant").append(new Option(name, name));
  const note = () => { $("variant-note").textContent = VARIANTS[$("variant").value][5]; };
  $("variant").addEventListener("change", note);
  note();
  $("login").addEventListener("click", guard(login));
  $("logout").addEventListener("click", logout);
  $("record-consent").addEventListener("click", guard(recordConsent));
  $("consent-status").addEventListener("click", guard(consentStatus));
  $("withdraw").addEventListener("click", guard(withdraw));
  $("generate").addEventListener("click", generate);
  $("process").addEventListener("click", guard(process));
  completeLogin().catch((e) => { $("who").textContent = `Login failed: ${e.message}`; }).finally(renderAuth);
});
