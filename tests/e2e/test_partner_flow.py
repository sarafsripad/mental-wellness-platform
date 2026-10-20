"""Partner referrals end to end (ADR-015), against the SIMULATED partner only.

The flow: an urgent visit with sharing consent, then a clinician URGENT_REFER, then a referral to
the simulated Tele-MANAS. The simulator verifies the signature, acks and posts status back, and
the ASHA worker sees CONTACTED with the handoff code. Withdrawal then revokes the referral at
the simulator, and the HMIS report holds counts only. No real partner is ever contacted.
"""
import json
import os
import subprocess
import time
import urllib.request
from datetime import datetime, timedelta, timezone

import pytest

from conftest import ROOT, hexid, iso, pytestmark_skip

SIMULATOR = "http://127.0.0.1:8090"


def simulator_up():
    try:
        urllib.request.urlopen(SIMULATOR + "/_simulator/health", timeout=2)
        return True
    except Exception:
        return False


if os.environ.get("E2E_REQUIRED") == "1" and not simulator_up():
    raise RuntimeError("E2E_REQUIRED=1 but the partner simulator is not reachable")
pytestmark = [pytestmark_skip, pytest.mark.skipif(not simulator_up(), reason="partner simulator not running")]


def simulator_messages():
    with urllib.request.urlopen(SIMULATOR + "/_simulator/messages", timeout=5) as r:
        return json.loads(r.read())["messages"]


def wait_for(check, timeout=60, interval=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = check()
        if value:
            return value
        time.sleep(interval)
    raise AssertionError("condition not met in time")


def urgent_referable_visit(asha, clinician, sharing=True):
    ppt, ce, case = hexid("ppt"), hexid("ce"), hexid("case")
    consented = iso(datetime.now(timezone.utc) - timedelta(minutes=10))
    status, _ = asha.call("POST", "/v1/consent-events", {
        "consent_event_id": ce, "participant_ref": ppt,
        "consent_form": {"form_id": "asha-wellness-consent", "form_version": "0.2.0", "language_tag": "ta-IN"},
        "scope_decisions": [{"scope": "SCREENING_PARTICIPATION", "granted": True},
                            {"scope": "EXTERNAL_REFERRAL_SHARING", "granted": sharing}],
        "capture": {"captured_at_utc": consented, "method": "VERBAL_WITNESSED", "read_aloud": True,
                    "comprehension_confirmed": True, "witness_present": True},
        "safeguards": {"adult_confirmed": True}})
    assert status == 201
    status, _ = asha.call("POST", "/v1/cases", {"case_id": case, "participant_ref": ppt, "consent_event_id": ce,
                                                "language_tag": "ta-IN", "opened_at_utc": consented,
                                                "data_origin": "SYNTHETIC", "age_band": "AGE_18_29"})
    assert status == 201
    status, _ = asha.call("POST", f"/v1/cases/{case}/questionnaire-responses", {
        "response_id": hexid("qr"), "instrument": {"instrument_id": "illustrative-wellness", "instrument_version": "0.1.0"},
        "language_tag": "ta-IN", "answered_at_utc": iso(datetime.now(timezone.utc) - timedelta(minutes=5)),
        "items": [{"item_id": "SELF_HARM_THOUGHTS", "answer_code": "OFTEN"}]})
    assert status == 201
    review_id = hexid("rvw")
    status, _ = clinician.call("POST", f"/v1/review-queue/{case}/reviews", {
        "review_id": review_id, "decision": "URGENT_REFER", "follow_up_within": "WITHIN_24_HOURS",
        "reviewed_at_utc": iso(datetime.now(timezone.utc))})
    assert status == 201
    return ppt, ce, case, review_id


def test_referral_reaches_the_simulated_partner_and_status_comes_back(asha, clinician):
    status, partners = clinician.call("GET", "/v1/integration/partners")
    assert status == 200 and {p["mode"] for p in partners["partners"]} == {"SIMULATED"}

    _, ce, case, review_id = urgent_referable_visit(asha, clinician)
    referral_id = hexid("ref")
    status, referral = clinician.call("POST", f"/v1/review-queue/{case}/referrals",
                                      {"referral_id": referral_id, "partner_id": "sim-tele-manas", "review_id": review_id})
    assert status == 201 and referral["status"] == "QUEUED"

    received = wait_for(lambda: [m for m in simulator_messages() if m.get("referral_id") == referral_id])
    assert received[0]["kind"] == "REFERRAL" and received[0]["priority"] == "urgent"
    assert received[0]["handoff_code"] == referral["handoff_code"]

    def contacted():
        s, body = asha.call("GET", f"/v1/cases/{case}/referrals")
        return s == 200 and body["referrals"][0]["status"] == "CONTACTED"
    wait_for(contacted)

    status, withdrawal = asha.call("POST", f"/v1/consent-events/{ce}/withdrawals", {
        "withdrawal_id": hexid("cw"), "scopes": ["EXTERNAL_REFERRAL_SHARING"],
        "withdrawn_at_utc": iso(datetime.now(timezone.utc)), "channel": "IN_PERSON_ASHA"})
    assert status == 202
    assert {"action": "REVOKE_EXTERNAL_REFERRAL", "status": "SCHEDULED"} in withdrawal["downstream_actions"]
    wait_for(lambda: [m for m in simulator_messages() if m.get("referral_id") == referral_id and m["kind"] == "REVOCATION"])


def test_no_sharing_consent_no_referral(asha, clinician):
    _, _, case, review_id = urgent_referable_visit(asha, clinician, sharing=False)
    status, problem = clinician.call("POST", f"/v1/review-queue/{case}/referrals",
                                     {"referral_id": hexid("ref"), "partner_id": "sim-tele-manas", "review_id": review_id})
    assert (status, problem["code"]) == (422, "CONSENT_NOT_ACTIVE")


def test_hmis_report_is_aggregate_only():
    month = datetime.now(timezone.utc).strftime("%Y-%m")
    r = subprocess.run([str(ROOT / "workstream-a/backend/wellness-api/run-hmis-report.sh"), month], capture_output=True,
                       text=True, stdin=subprocess.DEVNULL, timeout=900)
    assert r.returncode == 0, r.stdout[-2000:] + r.stderr[-2000:]
    report_id = next(w for line in r.stdout.splitlines() if "HMIS report hmis_" in line
                     for w in (json.loads(line)["message"] if line.startswith("{") else line).split() if w.startswith("hmis_"))
    report = json.loads((ROOT / "deploy/local/hmis-dropzone" / f"{report_id}.json").read_text())
    assert report["data_origin"] == "SYNTHETIC" and report["period"]["month"] == month
    text = json.dumps(report)
    for prefix in ("ppt_", "case_", "ce_", "ref_", "wkr_"):
        assert prefix not in text
    for group in report["visits_by_group"]:
        for key in ("visits", "urgent_review_flags", "follow_up_requests", "clinician_reviewed"):
            assert group[key] == "<5" or group[key] == 0 or group[key] >= 5
    assert any(m.get("report_id") == report_id for m in simulator_messages()), "posted to the simulated HMIS"
