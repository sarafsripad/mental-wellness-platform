"""Clinic app flow end to end (ADR-016) against the running stack: one shared record.

An ASHA visit leads to a referral to PHC Testpur (local seed), where doctor.test1 works. There the
doctor records clinic consent, runs an audited lookup, opens an encounter (PHQ-9) and sets a care
plan with a home-visit task. The ASHA worker sees and completes the task, and withdrawal of
CLINICAL_CARE hides it. Synthetic data only.
"""
import json
from datetime import datetime, timedelta, timezone

from conftest import hexid, iso, psql, pytestmark_skip

pytestmark = pytestmark_skip

FACILITY = "phc-testpur"
NAME = "E2E Clinic Person Tau-8810"
VILLAGE = "E2E Testgrama-4420"


def test_shared_record_from_asha_visit_to_clinic_and_back(asha, doctor, secrets_env):
    now = datetime.now(timezone.utc)
    ppt, ce = hexid("ppt"), hexid("ce")
    status, _ = asha.call("POST", "/v1/consent-events", {
        "consent_event_id": ce, "participant_ref": ppt,
        "consent_form": {"form_id": "asha-wellness-consent", "form_version": "0.1.0", "language_tag": "hi-IN"},
        "scope_decisions": [{"scope": "SCREENING_PARTICIPATION", "granted": True}],
        "capture": {"captured_at_utc": iso(now - timedelta(minutes=30)), "method": "VERBAL_WITNESSED", "read_aloud": True,
                    "comprehension_confirmed": True, "witness_present": True},
        "safeguards": {"adult_confirmed": True}})
    assert status == 201
    status, stored = asha.call("PUT", f"/v1/participants/{ppt}/identity", {
        "consent_event_id": ce, "data_origin": "SYNTHETIC", "display_name": NAME, "village_name": VILLAGE})
    assert status == 200
    card = stored["patient_card_code"]
    status, team = asha.call("PUT", f"/v1/participants/{ppt}/care-team/{FACILITY}",
                             {"reason_code": "ASHA_REFERRAL", "consent_event_id": ce})
    assert status == 200 and FACILITY in json.dumps(team)

    # At the clinic: find by card, record clinic consent, audited lookup.
    status, found = doctor.call("POST", "/v1/identity/search", {"patient_card_code": card})
    assert status == 200 and found["participant_refs"] == [ppt]
    clinic_ce = hexid("ce")
    status, _ = doctor.call("POST", "/v1/consent-events", {
        "consent_event_id": clinic_ce, "participant_ref": ppt,
        "consent_form": {"form_id": "clinic-care-consent", "form_version": "0.1.0", "language_tag": "en-IN"},
        "scope_decisions": [{"scope": "CLINICAL_CARE", "granted": True}],
        "capture": {"captured_at_utc": iso(now - timedelta(minutes=10)), "method": "SIGNED_ON_DEVICE", "read_aloud": True,
                    "comprehension_confirmed": True}, "safeguards": {"adult_confirmed": True}, "facility_code": FACILITY})
    assert status == 201
    status, details = doctor.call("POST", f"/v1/participants/{ppt}/identity/lookup", {"reason_code": "DIRECT_CARE"})
    assert status == 200 and details["display_name"] == NAME
    assert asha.call("POST", f"/v1/participants/{ppt}/identity/lookup", {"reason_code": "DIRECT_CARE"})[0] == 403

    # Encounter: PHQ-9 scored by its published rule, then a care plan with an ASHA task.
    enc = hexid("enc")
    status, _ = doctor.call("POST", "/v1/encounters", {"encounter_id": enc, "participant_ref": ppt, "consent_event_id": clinic_ce,
                                                       "facility_code": FACILITY, "encounter_type": "IN_PERSON",
                                                       "started_at_utc": iso(now - timedelta(minutes=5)), "data_origin": "SYNTHETIC"})
    assert status == 201
    items = [{"item_id": f"PHQ9_{i}", "answer_code": "SEVERAL_DAYS"} for i in range(1, 10)]
    items[-1] = {"item_id": "PHQ9_9", "answer_code": "NOT_AT_ALL"}
    status, result = doctor.call("POST", f"/v1/encounters/{enc}/instrument-responses", {
        "response_id": hexid("ir"), "instrument": {"instrument_id": "phq-9", "instrument_version": "1.0.0"},
        "language_tag": "en-IN", "answered_at_utc": iso(now), "items": items})
    assert status == 201 and result["total_score"] == 8 and result["severity_band"] == "MILD"
    task = hexid("task")
    status, plan = doctor.call("POST", f"/v1/encounters/{enc}/care-plan", {
        "plan_id": hexid("plan"), "referral_target": "COUNSELLING", "next_contact_at_utc": iso(now + timedelta(days=14)),
        "next_contact_mode": "IN_PERSON", "tasks": [{"task_id": task, "assignee": "ASHA_WORKER", "task_type": "HOME_VISIT",
                                                     "due_by_utc": iso(now + timedelta(days=3))}]})
    assert status == 201

    # Back in the household: the ASHA worker sees the task, never the score.
    status, tasks = asha.call("GET", "/v1/care-tasks")
    assert status == 200 and task in [t["task_id"] for t in tasks["tasks"]]
    assert "MILD" not in json.dumps(tasks) and "PHQ" not in json.dumps(tasks)
    status, done = asha.call("POST", f"/v1/care-tasks/{task}/completion", {"completed_at_utc": iso(now), "outcome": "DONE"})
    assert status == 201 and done["status"] == "DONE"

    # Identity stays ciphertext; the timeline shows both the ASHA visit side and the encounter.
    status, timeline = doctor.call("GET", f"/v1/participants/{ppt}/timeline")
    assert status == 200 and any(i["kind"] == "ENCOUNTER" for i in timeline["items"])
    assert "Tau-8810" not in psql(secrets_env, f"SELECT string_agg(action, ',') FROM audit.audit_event WHERE resource_id = '{ppt}'")
    assert "IDENTITY_LOOKUP_DIRECT_CARE" in psql(secrets_env, f"SELECT string_agg(action, ',') FROM audit.audit_event WHERE resource_id = '{ppt}'")
