// ASHA Care — field-worker web app (local test build, ADR-013). Synthetic data only.
// Same flow and wording as the ASHA Care prototype, but every answer is saved through the
// wellness API: consent event → case → identity (write-only) → observations → questionnaire →
// completion. No framework or third-party code. All output goes through textContent.
// Nothing is written to persistent browser storage: tokens, the visit in progress and the
// offline outbox live in memory only (they include names), so a reload discards them.
"use strict";

const KEYCLOAK = "http://localhost:8081/realms/wellness-local/protocol/openid-connect";
const CLIENT_ID = "wellness-asha-web";
const REDIRECT_URI = location.origin + "/asha/";

// 0.2.0 also asks, separately, about sharing a referral with a partner programme (ADR-015).
const CONSENT_FORM = { form_id: "asha-wellness-consent", form_version: "0.2.0" };
const INSTRUMENT = { instrument_id: "illustrative-wellness", instrument_version: "0.1.0" };
const CATALOGUE = { catalogue_id: "asha-observations", catalogue_version: "0.1.0" };

const STEPS = ["language", "login", "home", "registration", "consent", "health", "wellness", "lifestyle", "safety",
  "summary", "complete"];

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
  safety: "Important safety check", summary: "Review and next steps", complete: "Assessment saved",
  households: "My households", followups: "Follow-ups" };
const SUBS = { language: "Choose the language you are most comfortable using.",
  login: "Use your assigned worker account in the production app.", home: "Your community wellness visits",
  registration: "Collect only information required for the assessment.",
  consent: "Explain the purpose before asking any questions.", health: "Select conditions the person reports.",
  wellness: "Sample questions about the last two weeks.", lifestyle: "Ask sensitively and respect the person's privacy.",
  safety: "Ask privately, following approved training.", summary: "Check the information and decide the next step.",
  complete: "Thank you. The demonstration flow is complete.",
  declined: "Respect the person's decision. No screening questions will be asked.",
  households: "View assigned visits", followups: "Review pending care" };

const auth = { token: null, refresh: null, claims: null, workerRef: null };
const ui = { lang: "en", page: "language", online: true, message: null, lastEntry: null, listing: null };
let visit = null; // the assessment in progress (memory only)
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
function go(page) {
  ui.page = page;
  ui.message = null;
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

function logout() {
  if (pendingCount() > 0 || visit) {
    if (!confirm("Unsynced assessments are kept only in this page and will be lost. Log out anyway?")) return;
  }
  outbox.length = 0;
  visit = null;
  auth.token = auth.refresh = auth.claims = null;
  location.assign(`${KEYCLOAK}/logout?client_id=${CLIENT_ID}&post_logout_redirect_uri=${encodeURIComponent(REDIRECT_URI)}`);
}

function isAshaWorker() {
  return Boolean(auth.claims && auth.claims.realm_access && (auth.claims.realm_access.roles || []).includes("ASHA_WORKER"));
}

// --- API -------------------------------------------------------------------------------

class NetworkError extends Error {}
class SessionError extends Error {}

/** One API call. Request bodies are never logged: they can contain a name and a village. */
async function api(method, path, body, idempotencyKey) {
  await freshToken();
  const headers = { Authorization: `Bearer ${auth.token}`, "X-Correlation-ID": crypto.randomUUID() };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  let r;
  try {
    r = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
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
    sharing: "", consentAt: null,
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
        { scope: "EXTERNAL_REFERRAL_SHARING", granted: v.sharing === "yes" }],
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
  ];
}

function pendingCount() {
  return outbox.filter((e) => e.status === "pending").length;
}

let flushing = null;
/** Sends pending assessments in order. Stops at the first network failure and keeps the rest queued. */
function flush() {
  if (!flushing) {
    flushing = doFlush()
      .catch((e) => { ui.message = `Sync stopped: ${e.message}`; })
      .finally(() => { flushing = null; render(); });
  }
  return flushing;
}

async function doFlush() {
  for (const entry of outbox) {
    if (entry.status !== "pending") continue;
    while (entry.next < entry.ops.length) {
      if (!ui.online) return;
      const op = entry.ops[entry.next];
      let r;
      try {
        r = await api(op.method, op.path, op.body, op.key);
      } catch (e) {
        if (e instanceof NetworkError) return; // stay pending; try again on the next sync
        if (e instanceof SessionError) {
          ui.message = "Your session has expired. Unsynced assessments stay pending in this page; logging in again "
            + "reloads the page and discards them.";
          return;
        }
        throw e;
      }
      if (r.status >= 200 && r.status < 300) {
        if (op.pii) op.body = null; // the name and village leave memory as soon as they are stored
        if (r.data && r.data.case_id) entry.result = r.data;
        if (r.data && r.data.opened_by_worker_ref) auth.workerRef = r.data.opened_by_worker_ref;
        entry.next += 1;
      } else {
        entry.status = "failed";
        entry.problem = r.data && r.data.code ? `${op.what}: ${r.data.code}` : `${op.what}: HTTP ${r.status}`;
        for (const o of entry.ops) o.body = null; // a refused visit is not retried, so drop its answers
        break;
      }
    }
    if (entry.status === "pending") {
      entry.status = "done";
      for (const op of entry.ops) op.body = null;
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
    actions(button("Continue →", () => go(auth.token ? "home" : "login"), "full"))];
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
    const syncTile = tile("⇅", "Sync data", null, sync);
    syncTile.querySelector("small").textContent = `${t("Pending records:").text} ${pending}`;
    cards.append(
      tile("＋", "New assessment", "Start a household visit", () => { visit = newVisit(); go("registration"); }),
      tile("⌂", "My households", "View assigned visits", () => openList("households")),
      tile("▦", "Follow-ups", "Review pending care", () => openList("followups")),
      syncTile);
    const nodes = [cards, field("Connectivity simulation", radios("online",
      [["no", "Offline — save for later"], ["yes", "Internet available"]], ui.online ? "yes" : "no",
      (v) => { ui.online = v === "yes"; render(); if (ui.online && pendingCount()) flush(); }))];
    nodes.push(raw("p", auth.workerRef ? `Worker reference: ${auth.workerRef}` : "Signed in as an ASHA worker.", "small"));
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
      go("summary");
    }, "full")));
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
        ["Follow-up requested", c.follow_up_requested ? "Yes" : "No"]];
      for (const [k, v] of facts) {
        const d = raw("div", null, "summaryrow");
        d.append(raw("span", k), raw("code", String(v)));
        nodes.push(d);
      }
    } else if (entry && entry.status === "failed") {
      box.append(raw("div", "!", "mark bad"), raw("h2", "Not saved"), raw("p", `The server refused it (${entry.problem}).`));
      if (entry.urgent) {
        nodes.push(el("div", "This visit had a self-harm answer of 'sometimes' or 'often' and it was NOT saved, so no "
          + "urgent-review flag was raised. Contact your supervisor now.", "alert"));
      }
    } else {
      box.append(raw("div", "⇅", "mark wait"), raw("h2", "Saved for sync"),
        raw("p", "Kept in this page's memory until you sync. Reloading or closing the page discards it."));
    }
    nodes.push(actions(button("Start another assessment", () => { visit = newVisit(); go("registration"); }, "full"),
      button("Back to dashboard", () => go("home"), "secondary")));
    return nodes;
  },
};

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
    if (r.status === 200) contacts = r.data.contacts;
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

async function openList(page) {
  const followUpsOnly = page === "followups";
  ui.listing = null;
  go(page);
  if (!ui.online) return;
  try {
    const r = await api("GET", `/v1/cases?limit=50${followUpsOnly ? "&follow_up=true" : ""}`);
    ui.listing = r.status === 200 ? { followUpsOnly, cases: r.data.cases }
      : { followUpsOnly, cases: [], error: `Could not load visits (${r.data && r.data.code || r.status}).` };
  } catch (e) {
    ui.listing = { followUpsOnly, cases: [], error: "Could not reach the server." };
  }
  if (ui.page === page) render();
}

async function save() {
  const entry = { ops: operations(visit, isoNow()), next: 0, status: "pending", problem: null, result: null,
    urgent: URGENT.includes(visit.safety) }; // kept even when the answers are dropped
  outbox.push(entry);
  ui.lastEntry = entry;
  visit = null; // the outbox entry now holds the only copy
  go("complete");
  if (ui.online) await flush();
}

async function sync() {
  if (!ui.online) return warn("No connection simulated. Record remains pending sync.");
  if (!pendingCount()) return warn("Nothing to sync.");
  await flush();
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
  conn.textContent = "● " + t(ui.online ? "Internet available" : "Offline — save for later").text;
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
    if (visit || pendingCount()) e.preventDefault(); // unsynced data lives only in this page
  });
  completeLogin()
    .then((loggedIn) => { ui.page = loggedIn ? "home" : "language"; if (loggedIn) loadContacts(); })
    .catch((e) => { ui.page = "login"; ui.message = `Login failed: ${e.message}`; })
    .finally(render);
});
