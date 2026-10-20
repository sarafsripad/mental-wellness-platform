// Clinician review dashboard — local test build (ADR-014). Synthetic data only.
// Pseudonymous: the API never returns names or villages to this page. Decisions are coded and
// chosen by the clinician; nothing here suggests, ranks by risk, or scores anything.
// No framework or third-party code. All output goes through textContent. The token lives in
// memory only; sessionStorage holds just the PKCE verifier across the login redirect.
"use strict";

const KEYCLOAK = "http://localhost:8081/realms/wellness-local/protocol/openid-connect";
const CLIENT_ID = "wellness-review-web";
const REDIRECT_URI = location.origin + "/review/";

// English labels for the coded values (wording from the ASHA Care prototype).
const LABELS = {
  AGE_18_29: "18–29", AGE_30_44: "30–44", AGE_45_59: "45–59", AGE_60_PLUS: "60+", AGE_UNDER_18: "Under 18",
  NOT_STATED: "Not stated", FEMALE: "Female", MALE: "Male", OTHER: "Other",
};
const ITEMS = {
  ANXIETY: "Felt nervous, anxious or worried (last two weeks)",
  LOW_MOOD: "Felt down, sad or hopeless (last two weeks)",
  INTEREST: "Enjoyed usual activities",
  SELF_HARM_THOUGHTS: "Recent thoughts of self-harm, or that life is not worth living",
};
const ANSWERS = {
  NOT_AT_ALL: "Not at all", SEVERAL_DAYS: "Several days", MORE_THAN_HALF_THE_DAYS: "More than half the days",
  NEARLY_EVERY_DAY: "Nearly every day", DECLINED: "Prefer not to answer", AS_USUAL: "Yes, as usual",
  LESS_THAN_BEFORE: "Less than before", NO: "No", SOMETIMES: "Yes, sometimes", OFTEN: "Yes, often",
};
const OBSERVATIONS = { HEALTH_CONDITION: "Health condition", TOBACCO_USE: "Tobacco use", ALCOHOL_USE: "Alcohol use",
  SLEEP_QUALITY: "Sleep" };
const OBSERVATION_VALUES = {
  HYPERTENSION: "Hypertension", DIABETES: "Diabetes", HEART_DISEASE: "Heart disease",
  BREATHING_PROBLEM: "Asthma or breathing problems", OTHER_LONG_TERM_ILLNESS: "Other long-term illness",
  NONE_REPORTED: "None reported", DECLINED: "Prefer not to answer", NOT_REPORTED: "Not reported",
  REPORTED: "Reported", GOOD: "Good", SOME_DIFFICULTY: "Some difficulty", VERY_POOR: "Very poor",
};
const DECISIONS = [
  ["NO_FURTHER_ACTION", "No further action"], ["MONITOR", "Monitor"], ["REFER", "Refer"], ["URGENT_REFER", "Urgent referral"],
];
// Must match the API rule (wellness-api.v1.yaml, submitClinicianReview).
const TIMEFRAMES = {
  NO_FURTHER_ACTION: [["NONE", "No follow-up"]],
  MONITOR: [["WITHIN_7_DAYS", "Within 7 days"], ["WITHIN_30_DAYS", "Within 30 days"]],
  REFER: [["WITHIN_24_HOURS", "Within 24 hours"], ["WITHIN_7_DAYS", "Within 7 days"], ["WITHIN_30_DAYS", "Within 30 days"]],
  URGENT_REFER: [["WITHIN_24_HOURS", "Within 24 hours"]],
};
const WITHIN = { NONE: "No follow-up", WITHIN_24_HOURS: "Within 24 hours", WITHIN_7_DAYS: "Within 7 days",
  WITHIN_30_DAYS: "Within 30 days" };
const DECISION = Object.fromEntries(DECISIONS);

const auth = { token: null, refresh: null, claims: null };
const ui = { filter: "AWAITING_REVIEW", items: [], selected: null, detail: null, message: null, referrals: [] };
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
function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined && text !== null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function when(iso) {
  return iso ? new Date(iso).toLocaleString() : "—";
}
function ago(iso) {
  const hours = Math.round((Date.now() - new Date(iso).getTime()) / 3600000);
  return hours < 1 ? "under an hour ago" : hours < 48 ? `${hours} h ago` : `${Math.round(hours / 24)} days ago`;
}

// --- login (authorization code + PKCE) ---------------------------------------------------

async function login() {
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(48)));
  const challenge = base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  const stateParam = randomId("st");
  sessionStorage.setItem("review-pkce", JSON.stringify({ verifier, state: stateParam }));
  const params = new URLSearchParams({ client_id: CLIENT_ID, redirect_uri: REDIRECT_URI, response_type: "code",
    scope: "openid", code_challenge: challenge, code_challenge_method: "S256", state: stateParam });
  location.assign(`${KEYCLOAK}/auth?${params}`);
}

async function completeLogin() {
  const params = new URLSearchParams(location.search);
  const code = params.get("code");
  if (!code) return false;
  const saved = JSON.parse(sessionStorage.getItem("review-pkce") || "null");
  sessionStorage.removeItem("review-pkce");
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

function logout() {
  auth.token = auth.refresh = auth.claims = null;
  location.assign(`${KEYCLOAK}/logout?client_id=${CLIENT_ID}&post_logout_redirect_uri=${encodeURIComponent(REDIRECT_URI)}`);
}

function isClinician() {
  return Boolean(auth.claims && auth.claims.realm_access && (auth.claims.realm_access.roles || []).includes("CLINICIAN"));
}

async function api(method, path, body) {
  if (auth.claims && auth.claims.exp * 1000 - Date.now() < 30000 && auth.refresh) {
    await tokenRequest({ grant_type: "refresh_token", refresh_token: auth.refresh });
  }
  const headers = { Authorization: `Bearer ${auth.token}`, "X-Correlation-ID": crypto.randomUUID() };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    headers["Idempotency-Key"] = body.__key;
  }
  const payload = body === undefined ? undefined : JSON.stringify({ ...body, __key: undefined });
  const r = await fetch(path, { method, headers, body: payload });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  return { status: r.status, data };
}

// --- queue ---------------------------------------------------------------------------

async function loadQueue() {
  if (!auth.token) return;
  const r = await api("GET", `/v1/review-queue?status=${ui.filter}&limit=200`);
  if (r.status !== 200) {
    $("queueNote").textContent = `Could not load the queue (${r.data && r.data.code || r.status}).`;
    ui.items = [];
  } else {
    ui.items = r.data.items;
    const urgent = ui.items.filter((i) => i.priority === "URGENT").length;
    $("queueNote").textContent = `${ui.items.length} case(s)` + (urgent ? ` · ${urgent} urgent` : "")
      + " · urgent first, then oldest";
  }
  renderQueue();
}

function renderQueue() {
  const ul = $("items");
  ul.replaceChildren();
  for (const item of ui.items) {
    const c = item.case;
    const li = el("li");
    const b = el("button", null, c.case_id === ui.selected ? "selected" : "");
    b.type = "button";
    const head = el("div");
    if (item.priority === "URGENT") head.append(el("span", "Urgent review", "badge urgent"));
    if (c.follow_up_requested) head.append(el("span", "Follow-up requested", "badge"));
    if (c.review.status === "REVIEWED") head.append(el("span", DECISION[c.review.decision], "badge done"));
    b.append(head, el("div", c.case_id),
      el("div", `${LABELS[c.age_band]} · ${LABELS[c.gender]} · ${c.language_tag} · visit ${ago(c.opened_at_utc)}`, "meta"));
    b.addEventListener("click", () => select(c.case_id));
    li.append(b);
    ul.append(li);
  }
}

// --- case detail ---------------------------------------------------------------------

async function select(caseId, message = null) {
  ui.selected = caseId;
  ui.message = message;
  renderQueue();
  const r = await api("GET", `/v1/review-queue/${caseId}`);
  if (r.status !== 200) {
    ui.detail = null;
    $("detail").replaceChildren(el("div", r.status === 404
      ? "This case is no longer in the queue (for example, consent was withdrawn)." : `Could not load the case (${r.status}).`,
    "empty"));
    return;
  }
  ui.detail = r.data;
  const refs = await api("GET", `/v1/cases/${caseId}/referrals`);
  ui.referrals = refs.status === 200 ? refs.data.referrals : [];
  renderDetail();
}

function renderDetail() {
  const d = ui.detail;
  const c = d.case;
  const box = $("detail");
  const nodes = [el("h2", c.case_id)];
  if (ui.message) nodes.push(el("div", ui.message.text, ui.message.cls));
  if (d.priority === "URGENT") {
    nodes.push(el("div", "Urgent-review flag: the safety question was answered 'sometimes' or 'often'. Follow the approved "
      + "safety protocol and make sure the person has been connected to trained human support. (Test build: no approved "
      + "urgent-risk protocol exists yet, and partner referrals go to SIMULATED partners only.)", "alert"));
    nodes.push(contactList());
  }

  const facts = el("dl", null, "facts");
  const fact = (k, v) => facts.append(el("dt", k), el("dd", v));
  fact("Age group", LABELS[c.age_band]);
  fact("Gender", LABELS[c.gender]);
  fact("Visit language", c.language_tag);
  fact("Visit opened", when(c.opened_at_utc));
  fact("Visit status", c.status === "COMPLETED" ? `Completed ${when(c.completed_at_utc)}` : "Open (not yet completed)");
  fact("Worker asked for follow-up", c.follow_up_requested === null ? "Not yet decided" : c.follow_up_requested ? "Yes" : "No");
  fact("ASHA worker", c.opened_by_worker_ref);
  fact("Data", c.data_origin);
  nodes.push(facts);

  nodes.push(el("h3", "Questionnaire answers"));
  if (!d.questionnaire) {
    nodes.push(el("p", "No questionnaire was submitted."));
  } else {
    if (!d.questionnaire.validated) {
      nodes.push(el("div", `${d.questionnaire.instrument_id} ${d.questionnaire.instrument_version} is NOT a validated `
        + "instrument. Answers are shown as given; they are not scored and must not be read as a diagnosis.", "notice"));
    }
    const table = el("table");
    const head = el("tr");
    head.append(el("th", "Question"), el("th", "Answer"), el("th", "Code"));
    table.append(head);
    for (const a of d.questionnaire.items) {
      const tr = el("tr", null, a.item_id === "SELF_HARM_THOUGHTS" && ["SOMETIMES", "OFTEN"].includes(a.answer_code) ? "flag" : "");
      tr.append(el("td", ITEMS[a.item_id] || a.item_id), el("td", ANSWERS[a.answer_code] || a.answer_code),
        el("td", `${a.item_id}=${a.answer_code}`, "code"));
      table.append(tr);
    }
    nodes.push(table);
  }

  nodes.push(el("h3", "Health and lifestyle"));
  if (!d.observations.length) {
    nodes.push(el("p", "No observations were submitted."));
  } else {
    const table = el("table");
    for (const o of d.observations) {
      const tr = el("tr");
      tr.append(el("td", OBSERVATIONS[o.code] || o.code), el("td", OBSERVATION_VALUES[o.value_code] || o.value_code));
      table.append(tr);
    }
    nodes.push(table);
  }

  nodes.push(el("h3", "Review history"));
  if (!d.reviews.length) {
    nodes.push(el("p", "Not reviewed yet."));
  } else {
    const table = el("table");
    const head = el("tr");
    head.append(el("th", "Decision"), el("th", "Follow-up"), el("th", "Reviewed"), el("th", "Clinician"));
    table.append(head);
    for (const r of d.reviews) {
      const tr = el("tr");
      tr.append(el("td", DECISION[r.decision]), el("td", WITHIN[r.follow_up_within]), el("td", when(r.reviewed_at_utc)),
        el("td", r.reviewed_by_ref, "code"));
      table.append(tr);
    }
    nodes.push(table);
    nodes.push(el("p", "A new decision supersedes the latest one; earlier decisions are kept.", "small"));
  }

  if (["REFER", "URGENT_REFER"].includes(c.review.decision)) nodes.push(...referralSection(c, d.reviews[d.reviews.length - 1]));
  nodes.push(el("h3", d.reviews.length ? "Record a new decision" : "Record your decision"), decisionForm(c.case_id));
  box.replaceChildren(...nodes);
}

// --- partner referrals (API 1.3, ADR-015) ---------------------------------------------

let partners = [];
async function loadPartners() {
  const r = await api("GET", "/v1/integration/partners");
  if (r.status === 200) partners = r.data.partners;
}

function referralSection(c, latest) {
  const nodes = [el("h3", "Referral to a partner programme")];
  nodes.push(el("p", "Sends a pseudonymous referral (no name, contact details or answers) with a handoff code. The ASHA "
    + "worker connects the person, quoting the code. Needs the person's consent to sharing.", "small"));
  if (ui.referrals.length) {
    const table = el("table");
    const head = el("tr");
    head.append(el("th", "Partner"), el("th", "Status"), el("th", "Handoff code"), el("th", "Since"));
    table.append(head);
    for (const r of ui.referrals) {
      const tr = el("tr");
      tr.append(el("td", r.partner_name), el("td", r.status.replace(/_/g, " ").toLowerCase()), el("td", r.handoff_code, "code"),
        el("td", when(r.status_at_utc)));
      table.append(tr);
    }
    nodes.push(table);
  }
  const live = new Set(ui.referrals.filter((r) => !["CLOSED", "DECLINED", "DELIVERY_FAILED", "REVOKED"].includes(r.status))
    .map((r) => r.partner_id));
  const box = el("div", null, "options");
  for (const p of partners) {
    const b = el("button", `Send to ${p.partner_label}`, "btn");
    b.type = "button";
    b.disabled = live.has(p.partner_id);
    const key = crypto.randomUUID();
    const referralId = randomId("ref");
    b.addEventListener("click", async () => {
      b.disabled = true;
      const r = await api("POST", `/v1/review-queue/${c.case_id}/referrals`, { referral_id: referralId,
        partner_id: p.partner_id, review_id: latest.review_id, __key: key });
      const msg = r.status === 201 || r.status === 200
        ? { text: `Referral queued for ${p.partner_label}. Handoff code ${r.data.handoff_code}.`, cls: "ok" }
        : { text: r.data && r.data.code === "CONSENT_NOT_ACTIVE"
          ? "Not sent: the person did not consent to sharing a referral with partner programmes."
          : `Not sent (${r.data && r.data.code || r.status}).`, cls: "alert" };
      await select(c.case_id, msg);
    });
    box.append(b);
  }
  if (!partners.length) nodes.push(el("p", "No partner programme is enabled in this environment.", "small"));
  nodes.push(box);
  return nodes;
}

function decisionForm(caseId) {
  const form = el("div");
  const state = { decision: null, within: null };
  const options = el("div", null, "options");
  const timeframe = document.createElement("select");
  const submit = el("button", "Record decision", "btn");
  submit.type = "button";
  submit.disabled = true;
  timeframe.disabled = true;
  timeframe.append(new Option("Choose a decision first", ""));

  for (const [code, label] of DECISIONS) {
    const l = el("label", null, "choice");
    const input = document.createElement("input");
    input.type = "radio";
    input.name = "decision";
    input.value = code;
    input.addEventListener("change", () => {
      state.decision = code;
      timeframe.replaceChildren(...TIMEFRAMES[code].map(([v, text]) => new Option(text, v)));
      timeframe.disabled = TIMEFRAMES[code].length === 1;
      state.within = timeframe.value;
      submit.disabled = false;
    });
    l.append(input, el("span", label));
    options.append(l);
  }
  timeframe.addEventListener("change", () => { state.within = timeframe.value; });

  const key = crypto.randomUUID(); // one key per form, so a double click or retry records once
  const reviewId = randomId("rvw");
  submit.addEventListener("click", async () => {
    submit.disabled = true;
    const r = await api("POST", `/v1/review-queue/${caseId}/reviews`, { review_id: reviewId, decision: state.decision,
      follow_up_within: state.within, reviewed_at_utc: isoNow(), __key: key });
    if (r.status === 201 || r.status === 200) {
      await loadQueue();
      await select(caseId, { text: `Recorded: ${DECISION[r.data.decision]}, ${WITHIN[r.data.follow_up_within].toLowerCase()}. `
        + "The ASHA worker now sees this in their Follow-ups list.", cls: "ok" });
    } else {
      ui.message = { text: `Not recorded (${r.data && r.data.code || r.status}).`, cls: "alert" };
      renderDetail();
    }
  });

  const label = el("div", "Follow-up timeframe", "small");
  form.append(options, label, timeframe, submit,
    el("p", "Choose using your own clinical judgement. The platform does not suggest decisions. No free text is stored.", "small"));
  return form;
}

// --- support contacts (API 1.3) --------------------------------------------------------

let contacts = [];
async function loadContacts() {
  const r = await api("GET", "/v1/programme/support-contacts");
  if (r.status === 200) contacts = r.data.contacts;
}
function contactList() {
  const box = el("div", null, "contacts");
  box.append(el("div", "Support contacts", "small"));
  for (const c of contacts) {
    const row = el("div");
    const a = el("a", `${c.service_name}: ${c.dial_number}`);
    a.href = c.tel_uri;
    row.append(a);
    if (c.hours) row.append(el("span", ` · ${c.hours}`, "small"));
    if (!c.verified) row.append(el("span", "Pending clinical-lead verification", "badge"));
    box.append(row);
  }
  return box;
}

// --- wiring --------------------------------------------------------------------------

function renderAuth() {
  $("login").hidden = Boolean(auth.token);
  $("logout").hidden = !auth.token;
  if (!auth.token) {
    $("who").textContent = "Not logged in";
    return;
  }
  $("who").textContent = isClinician() ? "Logged in as a clinician" : "This account has no CLINICIAN role";
}

document.addEventListener("DOMContentLoaded", () => {
  $("login").addEventListener("click", () => login().catch((e) => { $("who").textContent = `Login failed: ${e.message}`; }));
  $("logout").addEventListener("click", logout);
  $("refresh").addEventListener("click", () => loadQueue());
  for (const tab of document.querySelectorAll(".tab")) {
    tab.addEventListener("click", () => {
      ui.filter = tab.dataset.filter;
      for (const t of document.querySelectorAll(".tab")) t.classList.toggle("active", t === tab);
      loadQueue();
    });
  }
  completeLogin()
    .catch((e) => { $("who").textContent = `Login failed: ${e.message}`; return false; })
    .then(() => { renderAuth(); if (isClinician()) { loadQueue(); loadContacts(); loadPartners(); } });
});
