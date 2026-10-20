// ASHA Care Clinic — local test build (ADR-016). Synthetic data only.
// For doctors (CLINICIAN), counsellors (COUNSELLOR) and health staff (HEALTH_STAFF) at a facility.
// Pseudonymous by default: identifiers appear only after an audited, reason-coded lookup and are
// cleared when another patient is opened. No framework or third-party code; all output via
// textContent. The token lives in memory; sessionStorage holds only the PKCE verifier.
"use strict";

const KEYCLOAK = "http://localhost:8081/realms/wellness-local/protocol/openid-connect";
const CLIENT_ID = "wellness-clinic-web";
const REDIRECT_URI = location.origin + "/clinic/";
const CONSENT_FORM = { form_id: "clinic-care-consent", form_version: "0.1.0", language_tag: "en-IN" };

const FREQUENCY = [["NOT_AT_ALL", "Not at all"], ["SEVERAL_DAYS", "Several days"],
  ["MORE_THAN_HALF_THE_DAYS", "More than half the days"], ["NEARLY_EVERY_DAY", "Nearly every day"],
  ["DECLINED", "Declined to answer"]];
// Standard English wording (PHQ-9 and GAD-7 are free to use). Other languages need validated translations.
const INSTRUMENTS = {
  "phq-9": { version: "1.0.0", title: "PHQ-9", items: [
    ["PHQ9_1", "Little interest or pleasure in doing things"],
    ["PHQ9_2", "Feeling down, depressed, or hopeless"],
    ["PHQ9_3", "Trouble falling or staying asleep, or sleeping too much"],
    ["PHQ9_4", "Feeling tired or having little energy"],
    ["PHQ9_5", "Poor appetite or overeating"],
    ["PHQ9_6", "Feeling bad about yourself, or that you are a failure or have let yourself or your family down"],
    ["PHQ9_7", "Trouble concentrating on things, such as reading the newspaper or watching television"],
    ["PHQ9_8", "Moving or speaking so slowly that other people could have noticed, or being so fidgety or restless that you have been moving around a lot more than usual"],
    ["PHQ9_9", "Thoughts that you would be better off dead, or of hurting yourself in some way"]] },
  "gad-7": { version: "1.0.0", title: "GAD-7", items: [
    ["GAD7_1", "Feeling nervous, anxious, or on edge"],
    ["GAD7_2", "Not being able to stop or control worrying"],
    ["GAD7_3", "Worrying too much about different things"],
    ["GAD7_4", "Trouble relaxing"],
    ["GAD7_5", "Being so restless that it is hard to sit still"],
    ["GAD7_6", "Becoming easily annoyed or irritable"],
    ["GAD7_7", "Feeling afraid, as if something awful might happen"]] },
};
const TYPES = [["IN_PERSON", "In person"], ["TELE_PHONE", "Tele-consultation (phone)"], ["TELE_VIDEO", "Tele-consultation (video)"],
  ["COUNSELLING_SESSION", "Counselling session"]];
const REFERRALS = [["NONE", "No referral"], ["COUNSELLING", "Counselling"], ["PSYCHIATRY_OPD", "Psychiatry OPD"],
  ["DISTRICT_MENTAL_HEALTH_PROGRAMME", "District Mental Health Programme"], ["TELE_MANAS", "Tele-MANAS"],
  ["EMERGENCY_SERVICES", "Emergency services"]];
const TASKS = [["HOME_VISIT", "ASHA home visit"], ["CHECK_IN_CALL", "ASHA check-in call"],
  ["ACCOMPANY_TO_FACILITY", "ASHA accompanies to facility"], ["TREATMENT_ADHERENCE_CHECK", "ASHA treatment adherence check"]];

const auth = { token: null, refresh: null, claims: null };
const ui = { memberships: [], facility: null, patient: null, consentEvent: null, details: null, timeline: null,
  encounter: null, message: null, results: [], contacts: [], registering: false };
const $ = (id) => document.getElementById(id);

// --- helpers -------------------------------------------------------------------------

function hex(bytes) { return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join(""); }
function randomId(prefix) { return `${prefix}_${hex(crypto.getRandomValues(new Uint8Array(16)))}`; }
function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function isoNow(offsetSeconds = 0) {
  return new Date(Date.now() + offsetSeconds * 1000).toISOString().replace(/\.\d{3}Z$/, "Z");
}
function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined && text !== null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function button(label, onClick, cls = "btn") {
  const b = el("button", label, cls);
  b.type = "button";
  b.addEventListener("click", onClick);
  return b;
}
function select(options, value) {
  const s = document.createElement("select");
  for (const [v, label] of options) {
    const o = new Option(label, v);
    o.selected = v === value;
    s.append(o);
  }
  return s;
}
function field(label, control) {
  const d = el("div");
  d.append(el("label", label, "small"), control);
  return d;
}
function when(iso) { return iso ? new Date(iso).toLocaleString() : "—"; }
function problem(r) { return r.data && r.data.code ? r.data.code : `HTTP ${r.status}`; }

/** The caller's role at the selected facility, which must also be in the token. */
function role() {
  const m = ui.memberships.find((x) => x.facility_code === ui.facility);
  return m ? m.care_role : null;
}
function clinical() { return ["CLINICIAN", "COUNSELLOR"].includes(role()); }

// --- login (authorization code + PKCE) ---------------------------------------------------

async function login() {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(48)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  const stateParam = randomId("st");
  sessionStorage.setItem("clinic-pkce", JSON.stringify({ verifier, state: stateParam }));
  const params = new URLSearchParams({ client_id: CLIENT_ID, redirect_uri: REDIRECT_URI, response_type: "code",
    scope: "openid", code_challenge: challenge, code_challenge_method: "S256", state: stateParam });
  location.assign(`${KEYCLOAK}/auth?${params}`);
}

async function completeLogin() {
  const params = new URLSearchParams(location.search);
  const code = params.get("code");
  if (!code) return false;
  const saved = JSON.parse(sessionStorage.getItem("clinic-pkce") || "null");
  sessionStorage.removeItem("clinic-pkce");
  history.replaceState(null, "", REDIRECT_URI);
  if (!saved || saved.state !== params.get("state")) throw new Error("state mismatch");
  await tokenRequest({ grant_type: "authorization_code", code, redirect_uri: REDIRECT_URI, code_verifier: saved.verifier });
  return true;
}

async function tokenRequest(fields) {
  const r = await fetch(`${KEYCLOAK}/token`, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, ...fields }) });
  if (!r.ok) throw new Error(`login failed (${r.status})`);
  const body = await r.json();
  auth.token = body.access_token;
  auth.refresh = body.refresh_token || null;
  const payload = body.access_token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
  auth.claims = JSON.parse(atob(payload + "=".repeat((4 - (payload.length % 4)) % 4)));
}

function logout() {
  ui.details = null;
  auth.token = auth.refresh = auth.claims = null;
  location.assign(`${KEYCLOAK}/logout?client_id=${CLIENT_ID}&post_logout_redirect_uri=${encodeURIComponent(REDIRECT_URI)}`);
}

async function api(method, path, body, idempotent = method === "POST") {
  if (auth.claims && auth.claims.exp * 1000 - Date.now() < 30000 && auth.refresh) {
    await tokenRequest({ grant_type: "refresh_token", refresh_token: auth.refresh });
  }
  const headers = { Authorization: `Bearer ${auth.token}`, "X-Correlation-ID": crypto.randomUUID() };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (idempotent) headers["Idempotency-Key"] = crypto.randomUUID();
  const r = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  return { status: r.status, data };
}

// --- facility and patient search -----------------------------------------------------

async function loadMemberships() {
  const r = await api("GET", "/v1/me/care-memberships");
  ui.memberships = r.status === 200 ? r.data.memberships : [];
  const s = $("facility");
  s.replaceChildren(...ui.memberships.map((m) => new Option(`${m.facility_label} · ${m.care_role}`, m.facility_code)));
  s.hidden = !ui.memberships.length;
  ui.facility = ui.memberships.length ? ui.memberships[0].facility_code : null;
  const c = await api("GET", "/v1/programme/support-contacts");
  ui.contacts = c.status === 200 ? c.data.contacts : [];
  render();
}

async function search(body) {
  const r = await api("POST", "/v1/identity/search", body, false);
  ui.results = r.status === 200 ? r.data.participant_refs : [];
  ui.message = r.status === 200 ? (ui.results.length ? null : { text: "No patient found on your care teams.", cls: "notice" })
    : { text: `Search failed (${problem(r)}).`, cls: "alert" };
  renderResults();
  render();
}

function renderResults() {
  $("results").replaceChildren(...ui.results.map((ref) => {
    const li = el("li");
    const b = button(ref, () => openPatient(ref), ref === ui.patient ? "selected" : "");
    li.append(b);
    return li;
  }));
}

async function openPatient(ref) {
  ui.registering = false;
  ui.patient = ref;
  ui.details = null; // identifiers never carry over between patients
  ui.encounter = null;
  ui.consentEvent = null;
  ui.message = null;
  renderResults();
  await loadTimeline();
  const consent = await api("GET", `/v1/participants/${ref}/consent-status`, undefined, false);
  if (consent.status === 200 && consent.data.effective_scopes.includes("CLINICAL_CARE")) ui.consentEvent = consent.data.active_consent_event_id;
  render();
}

async function loadTimeline() {
  const r = await api("GET", `/v1/participants/${ui.patient}/timeline`, undefined, false);
  ui.timeline = r.status === 200 ? r.data.items : [];
}

async function showDetails(reason) {
  const r = await api("POST", `/v1/participants/${ui.patient}/identity/lookup`, { reason_code: reason }, false);
  if (r.status === 200) ui.details = r.data;
  else ui.message = { text: `Details not shown (${problem(r)}).`, cls: "alert" };
  render();
}

// --- registration --------------------------------------------------------------------

function registrationForm() {
  const name = el("input"); name.maxLength = 100;
  const village = el("input"); village.maxLength = 100;
  const phone = el("input"); phone.maxLength = 13; phone.placeholder = "Optional, 10–13 digits";
  const method = select([["VERBAL_WITNESSED", "Verbal, with a witness"], ["SIGNED_ON_DEVICE", "Signed on device"],
    ["THUMBPRINT_ON_DEVICE", "Thumbprint on device"]], "VERBAL_WITNESSED");
  const checks = el("div", null, "checks");
  const box = (label) => { const l = el("label"); const i = el("input"); i.type = "checkbox"; l.append(i, el("span", label)); checks.append(l); return i; };
  const readAloud = box("The clinic consent form was read aloud");
  const understood = box("The person confirmed they understood");
  const witness = box("A witness was present");
  const adult = box("The person confirmed they are 18 or older");
  const card = el("div", null, "card");
  card.append(el("h2", "Register a new patient"),
    el("p", "Use invented details only: this test build accepts synthetic data.", "small"),
    el("div", null, "grid2"));
  card.lastChild.append(field("Name or approved identifier", name), field("Village", village), field("Phone", phone),
    field("Consent method", method));
  card.append(el("h3", "Clinic care consent (CLINICAL_CARE)"), checks);
  card.append(button("Record consent and register", async () => {
    if (!name.value.trim() || !village.value.trim()) return note("Enter a name and a village.", "alert");
    if (!understood.checked || !adult.checked) return note("Consent needs confirmed understanding and an adult participant.", "alert");
    if (method.value === "VERBAL_WITNESSED" && !witness.checked) return note("Verbal consent needs a witness.", "alert");
    const ppt = randomId("ppt"); const ce = randomId("ce");
    const c = await api("POST", "/v1/consent-events", { consent_event_id: ce, participant_ref: ppt, consent_form: CONSENT_FORM,
      scope_decisions: [{ scope: "CLINICAL_CARE", granted: true }],
      capture: { captured_at_utc: isoNow(), method: method.value, read_aloud: readAloud.checked, comprehension_confirmed: true,
        witness_present: witness.checked }, safeguards: { adult_confirmed: true }, facility_code: ui.facility });
    if (c.status !== 201) return note(`Consent not recorded (${problem(c)}).`, "alert");
    const identity = { consent_event_id: ce, data_origin: "SYNTHETIC", display_name: name.value.trim(), village_name: village.value.trim() };
    if (phone.value.trim()) identity.contact_phone = phone.value.trim();
    const i = await api("PUT", `/v1/participants/${ppt}/identity`, identity, false);
    name.value = village.value = phone.value = ""; // the page keeps no copy
    if (i.status !== 200) return note(`Registered, but identity not stored (${problem(i)}).`, "alert");
    ui.results = [ppt];
    await openPatient(ppt);
    note(`Registered. Patient card code ${i.data.patient_card_code}: give it to the person for future visits.`, "ok");
  }));
  return card;
}

/** Clinic care consent for a patient already on this facility's care team (for example an ASHA referral). */
function consentForm(ppt) {
  const card = el("div", null, "card");
  const method = select([["VERBAL_WITNESSED", "Verbal, with a witness"], ["SIGNED_ON_DEVICE", "Signed on device"],
    ["THUMBPRINT_ON_DEVICE", "Thumbprint on device"]], "VERBAL_WITNESSED");
  const checks = el("div", null, "checks");
  const box = (label) => { const l = el("label"); const i = el("input"); i.type = "checkbox"; l.append(i, el("span", label)); checks.append(l); return i; };
  const readAloud = box("The clinic consent form was read aloud");
  const understood = box("The person confirmed they understood");
  const witness = box("A witness was present");
  const adult = box("The person confirmed they are 18 or older");
  card.append(el("h3", "Record clinic care consent (CLINICAL_CARE)"), field("Consent method", method), checks,
    button("Record consent", async () => {
      if (!understood.checked || !adult.checked) return note("Consent needs confirmed understanding and an adult participant.", "alert");
      if (method.value === "VERBAL_WITNESSED" && !witness.checked) return note("Verbal consent needs a witness.", "alert");
      const ce = randomId("ce");
      const r = await api("POST", "/v1/consent-events", { consent_event_id: ce, participant_ref: ppt, consent_form: CONSENT_FORM,
        scope_decisions: [{ scope: "CLINICAL_CARE", granted: true }],
        capture: { captured_at_utc: isoNow(), method: method.value, read_aloud: readAloud.checked, comprehension_confirmed: true,
          witness_present: witness.checked }, safeguards: { adult_confirmed: true }, facility_code: ui.facility });
      if (r.status !== 201) return note(`Consent not recorded (${problem(r)}).`, "alert");
      ui.consentEvent = ce;
      note("Clinic care consent recorded.", "ok");
    }, "btn secondary"));
  return card;
}

function note(text, cls) {
  ui.message = { text, cls };
  render();
}

// --- encounters ----------------------------------------------------------------------

async function openEncounter(type) {
  if (!ui.consentEvent) return note("No clinic care consent on record: record it before the encounter.", "alert");
  const id = randomId("enc");
  const r = await api("POST", "/v1/encounters", { encounter_id: id, participant_ref: ui.patient, consent_event_id: ui.consentEvent,
    facility_code: ui.facility, encounter_type: type, started_at_utc: isoNow(-5), data_origin: "SYNTHETIC" });
  if (r.status !== 201 && r.status !== 200) return note(`Encounter not opened (${problem(r)}).`, "alert");
  ui.encounter = r.data;
  await loadTimeline();
  render();
}

async function reloadEncounter(message) {
  const r = await api("GET", `/v1/encounters/${ui.encounter.encounter_id}`, undefined, false);
  if (r.status === 200) ui.encounter = r.data;
  await loadTimeline();
  ui.message = message || null;
  render();
}

function instrumentForm(id) {
  const spec = INSTRUMENTS[id];
  const card = el("div", null, "card");
  card.append(el("h3", `${spec.title}: over the last 2 weeks, how often have you been bothered by…`));
  const selects = spec.items.map(([itemId, text]) => {
    const row = el("div", null, "item");
    const s = select([["", "Choose…"], ...FREQUENCY], "");
    row.append(el("span", text), s);
    card.append(row);
    return [itemId, s];
  });
  card.append(button(`Save ${spec.title}`, async () => {
    const items = selects.filter(([, s]) => s.value).map(([item_id, s]) => ({ item_id, answer_code: s.value }));
    if (!items.length) return note("Answer at least one item.", "alert");
    const r = await api("POST", `/v1/encounters/${ui.encounter.encounter_id}/instrument-responses`, { response_id: randomId("ir"),
      instrument: { instrument_id: id, instrument_version: spec.version }, language_tag: "en-IN", answered_at_utc: isoNow(), items });
    if (r.status !== 201) return note(`${spec.title} not saved (${problem(r)}).`, "alert");
    await reloadEncounter(r.data.urgent_review_flag
      ? { text: "Self-harm item answered above 'not at all': follow the safety protocol now and make sure the person has support.", cls: "alert" }
      : { text: `${spec.title} saved.`, cls: "ok" });
  }));
  return card;
}

function encounterView() {
  const e = ui.encounter;
  const nodes = [el("h2", `Encounter · ${TYPES.find(([t]) => t === e.encounter_type)[1]}`)];
  const facts = el("dl", null, "facts");
  const fact = (k, v) => facts.append(el("dt", k), el("dd", v));
  fact("Facility", e.facility_label); fact("Started", when(e.started_at_utc));
  fact("Status", e.ended_at_utc ? `Closed ${when(e.ended_at_utc)}` : "Open"); fact("Recorded in role", e.care_role);
  nodes.push(facts);
  if (e.urgent_review_flag) {
    nodes.push(el("div", "Urgent-review flag raised in this encounter. Follow the approved safety protocol. (Test build: no approved protocol yet.)", "alert"));
    const c = el("div", null, "contacts");
    for (const x of ui.contacts) { const a = el("a", `${x.service_name}: ${x.dial_number}`); a.href = x.tel_uri; c.append(a); }
    nodes.push(c);
  }
  const open = !e.ended_at_utc;
  if (clinical()) {
    nodes.push(el("h3", "Assessments"));
    for (const r of e.instruments) {
      nodes.push(el("div", r.complete ? `${r.instrument_id.toUpperCase()}: score ${r.total_score}, ${r.severity_band.replace(/_/g, " ").toLowerCase()} (instrument score, not a diagnosis; translation not validated)`
        : `${r.instrument_id.toUpperCase()}: incomplete, no score`, "card"));
    }
    if (open) for (const id of Object.keys(INSTRUMENTS)) if (!e.instruments.some((r) => r.instrument_id === id)) nodes.push(instrumentForm(id));

    nodes.push(el("h3", "Clinical notes"));
    if (!e.clinical_notes_enabled) nodes.push(el("p", "Notes are disabled until a sensitive-text policy is approved.", "small"));
    else {
      for (const n of e.notes) nodes.push(el("div", `${when(n.created_at_utc)} · ${n.text}`, "card"));
      if (open) {
        const t = el("textarea"); t.maxLength = 4000;
        nodes.push(t, button("Add note", async () => {
          if (!t.value.trim()) return;
          const r = await api("POST", `/v1/encounters/${e.encounter_id}/notes`, { note_id: randomId("note"), text: t.value });
          t.value = "";
          await reloadEncounter(r.status === 201 ? { text: "Note added (encrypted).", cls: "ok" } : { text: `Note not added (${problem(r)}).`, cls: "alert" });
        }, "btn secondary"));
      }
    }
  }
  if (role() === "CLINICIAN") {
    nodes.push(el("h3", "Diagnosis (clinician-entered)"));
    for (const d of e.clinician_diagnoses) nodes.push(el("div", `${d.code_system.replace("_", "-")} ${d.code} · ${d.certainty.toLowerCase()} · ${when(d.recorded_at_utc)}`, "card"));
    if (open) {
      const system = select([["ICD_10", "ICD-10 (chapter F)"], ["ICD_11", "ICD-11 (chapter 6)"]], "ICD_10");
      const code = el("input"); code.placeholder = "e.g. F32.1"; code.maxLength = 10;
      const certainty = select([["PROVISIONAL", "Provisional"], ["CONFIRMED", "Confirmed"], ["RULED_OUT", "Ruled out"]], "PROVISIONAL");
      const g = el("div", null, "grid2");
      g.append(field("Code system", system), field("Code", code), field("Certainty", certainty));
      nodes.push(g, button("Record diagnosis", async () => {
        const r = await api("POST", `/v1/encounters/${e.encounter_id}/diagnoses`, { diagnosis_id: randomId("dx"), code_system: system.value,
          code: code.value.trim().toUpperCase(), certainty: certainty.value });
        await reloadEncounter(r.status === 201 ? { text: "Diagnosis recorded.", cls: "ok" } : { text: `Not recorded (${problem(r)}).`, cls: "alert" });
      }, "btn secondary"));
    }
  }
  nodes.push(el("h3", "Care plan"));
  if (e.care_plan) {
    const p = e.care_plan;
    nodes.push(el("div", `Referral: ${REFERRALS.find(([v]) => v === p.referral_target)[1]} · next contact ${when(p.next_contact_at_utc)}`, "card"));
    for (const t of p.tasks) nodes.push(el("div", `${TASKS.find(([v]) => v === t.task_type)[1]} · due ${when(t.due_by_utc)} · ${t.status.toLowerCase()}`, "card"));
  } else if (open) {
    const referral = select(REFERRALS, "NONE");
    const next = el("input"); next.type = "date";
    const task = select([["", "No ASHA task"], ...TASKS], "");
    const due = el("input"); due.type = "date";
    const g = el("div", null, "grid2");
    g.append(field("Referral pathway (recorded, nothing is sent)", referral), field("Next contact", next),
      field("Task for the ASHA worker", task), field("Task due", due));
    nodes.push(g, button("Save care plan", async () => {
      const tasks = task.value ? [{ task_id: randomId("task"), assignee: "ASHA_WORKER", task_type: task.value,
        due_by_utc: due.value ? `${due.value}T12:00:00Z` : isoNow(3 * 86400) }] : [];
      const r = await api("POST", `/v1/encounters/${e.encounter_id}/care-plan`, { plan_id: randomId("plan"), referral_target: referral.value,
        next_contact_at_utc: next.value ? `${next.value}T09:00:00Z` : null, next_contact_mode: next.value ? "IN_PERSON" : null, tasks });
      await reloadEncounter(r.status === 201 ? { text: "Care plan saved. ASHA tasks appear in the worker's Follow-ups.", cls: "ok" }
        : { text: r.data && r.data.errors && r.data.errors.some((x) => x.rule === "noAshaWorker")
          ? "This patient has no ASHA worker yet, so no ASHA task can be assigned." : `Not saved (${problem(r)}).`, cls: "alert" });
    }, "btn secondary"));
  }
  if (open) {
    nodes.push(button("Close encounter", async () => {
      const r = await api("POST", `/v1/encounters/${e.encounter_id}/close`, { ended_at_utc: isoNow() });
      await reloadEncounter(r.status === 201 ? { text: "Encounter closed.", cls: "ok" } : { text: `Not closed (${problem(r)}).`, cls: "alert" });
    }, "btn danger full"));
  }
  nodes.push(button("Back to patient", () => { ui.encounter = null; render(); }, "btn secondary full"));
  return nodes;
}

// --- patient view ----------------------------------------------------------------------

function patientView() {
  const nodes = [el("h2", ui.patient)];
  if (ui.details) {
    const facts = el("dl", null, "facts");
    const fact = (k, v) => facts.append(el("dt", k), el("dd", v || "—"));
    fact("Name", ui.details.display_name); fact("Village", ui.details.village_name); fact("Phone", ui.details.contact_phone);
    fact("Card code", ui.details.patient_card_code);
    nodes.push(facts, el("p", "Shown after an audited lookup. Cleared when you open another patient.", "small"));
  } else {
    const reason = select([["DIRECT_CARE", "Direct care (in person)"], ["TELE_CONSULTATION", "Tele-consultation"],
      ["FOLLOW_UP_CONTACT", "Follow-up contact"]], "DIRECT_CARE");
    nodes.push(field("Reason for showing details (audited)", reason), button("Show details", () => showDetails(reason.value), "btn secondary"));
  }
  nodes.push(el("h3", "Timeline"));
  if (!ui.timeline || !ui.timeline.length) nodes.push(el("p", "No visits or encounters yet.", "small"));
  else {
    const table = el("table");
    const head = el("tr"); head.append(el("th", "When"), el("th", "What"), el("th", "Where"), el("th", "")); table.append(head);
    for (const t of ui.timeline) {
      const tr = el("tr");
      tr.append(el("td", when(t.at)), el("td", `${t.kind === "ASHA_VISIT" ? "ASHA visit" : "Encounter"} · ${t.summary_code}`),
        el("td", t.facility_label || "Household"), el("td", t.urgent_review_flag ? "Urgent" : "", t.urgent_review_flag ? "badge urgent" : ""));
      table.append(tr);
    }
    nodes.push(table);
  }
  nodes.push(el("h3", "New encounter"));
  if (!ui.consentEvent) nodes.push(el("div", "No clinic care consent in effect for this patient yet.", "notice"), consentForm(ui.patient));
  else {
    const allowed = role() === "HEALTH_STAFF" ? TYPES.filter(([t]) => t === "IN_PERSON")
      : role() === "COUNSELLOR" ? TYPES : TYPES.filter(([t]) => t !== "COUNSELLING_SESSION");
    const type = select(allowed, allowed[0][0]);
    nodes.push(field("Type", type), button("Open encounter", () => openEncounter(type.value)));
  }
  return nodes;
}

// --- rendering -------------------------------------------------------------------------

function render() {
  const loggedIn = Boolean(auth.token);
  $("login").hidden = loggedIn;
  $("logout").hidden = !loggedIn;
  $("who").textContent = !loggedIn ? "Not logged in" : role() ? `Logged in · ${role()}` : "No facility membership";
  const panel = $("panel");
  const nodes = [];
  if (ui.message) nodes.push(el("div", ui.message.text, ui.message.cls));
  if (!loggedIn || !ui.facility) nodes.push(el("div", loggedIn ? "Your account has no facility membership. Ask an administrator." : "Log in to start.", "empty"));
  else if (ui.registering) nodes.push(registrationForm());
  else if (ui.encounter) nodes.push(...encounterView());
  else if (ui.patient) nodes.push(...patientView());
  else nodes.push(el("div", "Find or register a patient.", "empty"));
  panel.replaceChildren(...nodes);
}

document.addEventListener("DOMContentLoaded", () => {
  $("login").addEventListener("click", () => login().catch((e) => { $("who").textContent = `Login failed: ${e.message}`; }));
  $("logout").addEventListener("click", logout);
  $("facility").addEventListener("change", (ev) => { ui.facility = ev.target.value; ui.patient = null; ui.details = null; ui.encounter = null; render(); });
  $("byCard").addEventListener("click", () => { const v = $("card").value.trim().toUpperCase(); if (v) search({ patient_card_code: v }); });
  $("byName").addEventListener("click", () => {
    const n = $("sname").value.trim(); const v = $("svillage").value.trim();
    if (n && v) search({ display_name: n, village_name: v });
    $("sname").value = $("svillage").value = "";
  });
  $("newPatient").addEventListener("click", () => { ui.registering = true; ui.patient = null; ui.details = null; ui.encounter = null; ui.message = null; render(); });
  window.addEventListener("beforeunload", () => { ui.details = null; });
  completeLogin()
    .catch((e) => { $("who").textContent = `Login failed: ${e.message}`; return false; })
    .then(() => (auth.token ? loadMemberships() : render()));
});
