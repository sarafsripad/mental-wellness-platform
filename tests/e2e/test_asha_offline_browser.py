"""ASHA app offline mode in a real browser (ADR-017). Synthetic data only.

Drives the installed Google Chrome with Playwright (requirements-browser.txt). Skips when Playwright
isn't installed. Needs the local stack and run-local.sh, like the rest of the e2e suite.
"""
import re
import secrets
from datetime import datetime, timezone

import pytest

from conftest import pytestmark_skip

playwright_api = pytest.importorskip("playwright.sync_api")

pytestmark = pytestmark_skip

APP = "http://localhost:8080/asha/"
PIN = "482913"

# Reads every row the app's store has written, with the binary fields as Latin-1 text, so a test can
# look for a name or village in what is actually on disk.
DUMP_STORE = """async () => {
  const db = await new Promise((ok, fail) => { const r = indexedDB.open("asha-offline", 1); r.onsuccess = () => ok(r.result); r.onerror = () => fail(r.error); });
  const all = (name) => new Promise((ok) => { const r = db.transaction(name).objectStore(name).getAll(); r.onsuccess = () => ok(r.result); });
  const text = (v) => v instanceof Uint8Array ? new TextDecoder("latin1").decode(v) : JSON.stringify(v);
  const rows = [...await all("meta"), ...await all("records")];
  db.close();
  return { count: rows.length, text: rows.map((row) => Object.values(row).map(text).join("|")).join("\\n") };
}"""


@pytest.fixture(scope="module")
def browser():
    with playwright_api.sync_playwright() as p:
        b = p.chromium.launch(channel="chrome", headless=True)
        yield b
        b.close()


@pytest.fixture
def page(browser):
    context = browser.new_context()
    page = context.new_page()
    yield page
    context.close()


def click(page, text):
    page.get_by_role("button", name=text, exact=True).click()


def login(page, secrets_env, username="asha.test1"):
    page.goto(APP)
    click(page, "Continue →")
    if page.get_by_role("button", name="Log in instead").is_visible():  # offline data on this device
        click(page, "Log in instead")
    click(page, "Enter demo app →")
    page.fill("#username", username)
    page.fill("#password", secrets_env["KEYCLOAK_TEST_USER_PASSWORD"])
    page.click("#kc-login")
    page.wait_for_url(re.compile(r"/asha/$"))


def turn_on_offline_mode(page):
    click(page, "Turn on offline mode")
    page.get_by_placeholder("PIN", exact=True).fill(PIN)
    page.get_by_placeholder("PIN again").fill(PIN)
    click(page, "Turn on offline mode")
    page.get_by_text("Offline mode is on").wait_for()
    # The app must be loadable with no connection before the test goes offline.
    page.evaluate("navigator.serviceWorker.ready.then(() => true)")


def unlock(page, pin=PIN):
    page.get_by_placeholder("PIN", exact=True).fill(pin)
    click(page, "Unlock")


def record_visit(page, name, village, voice=False):
    if page.get_by_role("button", name="Back to dashboard").is_visible():
        click(page, "Back to dashboard")
    page.get_by_role("button", name=re.compile("New assessment")).click()
    page.get_by_placeholder("Enter name or local ID").fill(name)
    page.get_by_placeholder("Enter village name").fill(village)
    page.locator("select").first.select_option("AGE_30_44")
    click(page, "Continue →")
    page.check("input[name=consent][value=yes]")
    for label in ["The consent wording was read aloud", "The person confirmed they understood", "A witness was present",
                  "The person confirmed they are 18 or older"]:
        page.get_by_text(label, exact=True).click()
    page.check("input[name=sharing][value=no]")
    page.check(f"input[name=audio][value={'yes' if voice else 'no'}]")
    click(page, "Continue")
    page.get_by_text("None reported", exact=True).click()
    click(page, "Continue →")
    page.check("input[name=anxiety][value=NOT_AT_ALL]")
    page.check("input[name=sadness][value=NOT_AT_ALL]")
    page.check("input[name=interest][value=AS_USUAL]")
    click(page, "Continue →")
    page.check("input[name=sleep][value=GOOD]")
    click(page, "Continue to safety check →")
    page.check("input[name=safety][value=NO]")
    click(page, "Review assessment →")
    if voice:
        page.locator("#screenTitle", has_text="Voice recording").wait_for()
        for _ in range(2):
            page.get_by_role("button", name="Record (synthetic test voice)").first.click()
        page.get_by_text("✓ Recorded").nth(1).wait_for()
        click(page, "Review assessment →")
    else:
        # The consent gate: without consent to record, the voice step is skipped entirely.
        page.locator("#screenTitle", has_text="Review and next steps").wait_for()
        page.get_by_text("Not consented").wait_for()
    click(page, "Save assessment")
    page.locator("#screenTitle", has_text="Assessment saved").wait_for()


def case_count(asha):
    status, body = asha.call("GET", "/v1/cases?limit=50")
    assert status == 200
    return {c["case_id"] for c in body["cases"]}


def test_a_visit_saved_offline_survives_a_reload_and_syncs_exactly_once(page, secrets_env, asha):
    name, village = f"Synthetic Offline {secrets.token_hex(3)}", f"Testgrama-{secrets.token_hex(2)}"
    before = case_count(asha)
    login(page, secrets_env)
    turn_on_offline_mode(page)

    page.context.set_offline(True)
    page.get_by_text("Offline — save for later").first.wait_for()
    record_visit(page, name, village)
    page.get_by_text("Stored encrypted on this device").wait_for()

    stored = page.evaluate(DUMP_STORE)
    assert stored["count"] >= 2
    assert name not in stored["text"] and village not in stored["text"], "names must never be on disk in plaintext"
    assert "SCREENING_PARTICIPATION" not in stored["text"], "answers must never be on disk in plaintext"

    # No connection: the service worker serves the app, and the PIN unlocks the visit.
    page.reload()
    click(page, "Continue →")
    unlock(page)
    page.get_by_text("Pending records: 1").wait_for()
    assert case_count(asha) == before

    page.context.set_offline(False)
    page.get_by_role("button", name=re.compile("Sync data")).click()  # logs in again (SSO), then unlocks
    page.wait_for_url(re.compile(r"/asha/$"))
    unlock(page)
    page.get_by_text("Pending records: 0").wait_for(timeout=30000)
    after = case_count(asha)
    assert len(after - before) == 1

    # A second sync and a second reload send nothing again.
    page.reload()
    click(page, "Continue →")
    unlock(page)
    page.get_by_text("Pending records: 0").wait_for()
    assert case_count(asha) == after


def test_five_wrong_pins_delete_the_workers_offline_data(page, secrets_env):
    login(page, secrets_env)
    turn_on_offline_mode(page)
    page.context.set_offline(True)
    record_visit(page, f"Synthetic Wipe {secrets.token_hex(3)}", "Testgrama-77")
    page.reload()
    click(page, "Continue →")
    for left in [4, 3, 2, 1]:
        unlock(page, "000000")
        page.get_by_text(f"{left} attempt(s) left").wait_for()
    unlock(page, "000000")
    page.get_by_text("too many wrong PINs").wait_for()
    assert page.evaluate(DUMP_STORE)["count"] == 0


def test_offline_mode_off_keeps_nothing_on_the_device(page, secrets_env):
    login(page, secrets_env)
    page.context.set_offline(True)
    page.get_by_role("checkbox", name=re.compile("Work offline")).check()
    record_visit(page, f"Synthetic Memory {secrets.token_hex(3)}", "Testgrama-78")
    page.get_by_text("Kept in this page's memory").wait_for()
    assert page.evaluate(DUMP_STORE)["count"] == 0


def test_a_shared_device_keeps_each_workers_visits_apart(page, secrets_env, asha):
    before = case_count(asha)
    login(page, secrets_env)
    turn_on_offline_mode(page)
    page.context.set_offline(True)
    record_visit(page, f"Synthetic Shared {secrets.token_hex(3)}", "Testgrama-79")
    page.context.set_offline(False)

    # Worker 2 logs in on the same device (the first worker's login session is gone).
    page.context.clear_cookies()
    login(page, secrets_env, "asha.test2")
    page.get_by_text("Pending records: 0").wait_for()
    assert not page.get_by_role("button", name="Unlock").is_visible(), "another worker's visits must not be offered"
    turn_on_offline_mode(page)
    owners = page.evaluate("""() => new Promise((ok) => { const r = indexedDB.open("asha-offline", 1);
      r.onsuccess = () => { const q = r.result.transaction("meta").objectStore("meta").getAll();
        q.onsuccess = () => { ok(q.result.map((m) => m.owner)); r.result.close(); }; }; })""")
    assert len(set(owners)) == 2, "each worker has their own key and records"
    assert case_count(asha) == before, "worker 1's offline visit was not sent under worker 2's login"


def test_voice_is_recorded_only_with_consent_and_leaves_the_device_once_stored(page, secrets_env, asha):
    login(page, secrets_env)
    turn_on_offline_mode(page)

    # Consent refused: there is no voice step at all.
    record_visit(page, f"Synthetic NoVoice {secrets.token_hex(3)}", "Testgrama-80", voice=False)

    sessions = []
    page.on("request", lambda r: sessions.append(r.post_data_json["session_id"])
            if r.method == "POST" and r.url.endswith("/v1/voice-sessions") else None)
    page.context.set_offline(True)
    record_visit(page, f"Synthetic Voice {secrets.token_hex(3)}", "Testgrama-81", voice=True)
    assert page.evaluate(DUMP_STORE)["text"].count("audio:") == 2, "both recordings are kept on the device, encrypted"
    assert "RIFF" not in page.evaluate(DUMP_STORE)["text"], "audio must never be on disk in plaintext"

    page.context.set_offline(False)
    page.get_by_role("button", name=re.compile("Back to dashboard")).click()
    page.get_by_role("button", name=re.compile("Sync data")).click()
    page.get_by_text("Pending records: 0").wait_for(timeout=60000)
    assert len(sessions) == 1
    status, session = asha.call("GET", f"/v1/voice-sessions/{sessions[0]}")
    assert status == 200
    assert [r["upload_status"] for r in session["recordings"]] == ["STORED", "STORED"]
    assert page.evaluate(DUMP_STORE)["text"].count("audio:") == 0, "audio is deleted once the server stored it"


def test_a_withdrawal_before_sync_deletes_the_recording_but_keeps_the_visit(page, secrets_env, asha):
    login(page, secrets_env)
    turn_on_offline_mode(page)

    def withdraw_first(route):
        body = route.request.post_data_json
        status, _ = asha.call("POST", f"/v1/consent-events/{body['consent_event_id']}/withdrawals", {
            "withdrawal_id": f"cw_{secrets.token_hex(16)}", "scopes": ["AUDIO_RECORDING"],
            "withdrawn_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "channel": "IN_PERSON_ASHA"})
        assert status == 202
        route.continue_()

    page.route("**/v1/voice-sessions", withdraw_first)
    record_visit(page, f"Synthetic Withdrawn {secrets.token_hex(3)}", "Testgrama-82", voice=True)
    page.get_by_text("The voice recording was not uploaded").wait_for(timeout=60000)
    page.get_by_text("CONSENT_NOT_ACTIVE").first.wait_for()
    page.get_by_text("Case").first.wait_for()  # the visit itself was saved
    assert page.evaluate(DUMP_STORE)["text"].count("audio:") == 0, "a refused recording is deleted, not retried"


def test_a_running_recording_never_moves_to_the_next_visit_and_is_never_labelled_synthetic(browser, secrets_env):
    # As in a build with the microphone approved. The "microphone" is a generated tone injected in place
    # of getUserMedia, so no real voice is captured and the server's microphone policy is unchanged.
    context = browser.new_context()
    page = context.new_page()
    page.route("**/asha/settings.json", lambda route: route.fulfill(json={"microphone_enabled": True}))
    page.add_init_script("""
      navigator.mediaDevices.getUserMedia = async () => {
        const ctx = new AudioContext();
        const tone = ctx.createOscillator();
        const out = ctx.createMediaStreamDestination();
        tone.connect(out);
        tone.start();
        return out.stream;
      };
    """)
    origins = []
    page.on("request", lambda r: origins.append(r.post_data_json["data_origin"])
            if r.method == "POST" and r.url.endswith("/v1/voice-sessions") else None)
    try:
        login(page, secrets_env)

        # Visit A: start a recording and walk away from the screen without stopping it.
        def start_and_leave(page):
            page.locator("#screenTitle", has_text="Voice recording").wait_for()
            page.get_by_role("button", name="Start recording").first.click()
            page.get_by_role("button", name="Stop recording").wait_for()
            page.wait_for_timeout(500)

        record_visit_with(page, "Synthetic MicA", "Testgrama-83", start_and_leave)
        # Visit B: the voice screen starts clean; nothing from visit A is waiting to be stopped.
        def check_clean_then_record(page):
            page.locator("#screenTitle", has_text="Voice recording").wait_for()
            assert page.get_by_role("button", name="Stop recording").count() == 0
            page.get_by_role("button", name="Start recording").first.click()
            page.wait_for_timeout(3500)
            click(page, "Stop recording")
            page.get_by_text("✓ Recorded").wait_for()

        record_visit_with(page, "Synthetic MicB", "Testgrama-84", check_clean_then_record)
        page.get_by_text("Pending records: 0").or_(page.get_by_text("Voice recording")).first.wait_for(timeout=60000)
        assert origins == ["AUTHORIZED_TEST_AUDIO"], "only visit B sent a session, and it is not labelled SYNTHETIC"
    finally:
        context.close()


def record_visit_with(page, name, village, on_voice):
    """record_visit with the audio question answered yes and a custom voice step."""
    if page.get_by_role("button", name="Back to dashboard").is_visible():
        click(page, "Back to dashboard")
    page.get_by_role("button", name=re.compile("New assessment")).click()
    page.get_by_placeholder("Enter name or local ID").fill(f"{name} {secrets.token_hex(2)}")
    page.get_by_placeholder("Enter village name").fill(village)
    page.locator("select").first.select_option("AGE_30_44")
    click(page, "Continue →")
    page.check("input[name=consent][value=yes]")
    for label in ["The consent wording was read aloud", "The person confirmed they understood", "A witness was present",
                  "The person confirmed they are 18 or older"]:
        page.get_by_text(label, exact=True).click()
    page.check("input[name=sharing][value=no]")
    page.check("input[name=audio][value=yes]")
    click(page, "Continue")
    page.get_by_text("None reported", exact=True).click()
    click(page, "Continue →")
    click(page, "Continue →")
    click(page, "Continue to safety check →")
    page.check("input[name=safety][value=NO]")
    click(page, "Review assessment →")
    on_voice(page)
    click(page, "Review assessment →")
    click(page, "Save assessment")
    page.locator("#screenTitle", has_text="Assessment saved").wait_for()
