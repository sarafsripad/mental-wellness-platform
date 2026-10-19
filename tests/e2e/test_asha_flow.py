"""ASHA Care web app flow (ADR-013) through the running API: the same calls app.js makes, in order.

Synthetic data only. Checks where each answer ends up: coded values in app_db, the name and village
only as ciphertext in identity_db, and nothing identifying anywhere else.
"""
import json
from datetime import datetime, timedelta, timezone

from conftest import hexid, iso, psql, psql_as, pytestmark_skip

pytestmark = pytestmark_skip

NAME = "E2E Synthetic Person Kappa-5521"
VILLAGE = "E2E Testgrama-0912"


def visit(asha, language="hi-IN", safety="SOMETIMES", follow_up=True):
    """Consent → case → identity → observations → questionnaire → completion, as the web app sends them."""
    ppt, ce, case, obs, qr = hexid("ppt"), hexid("ce"), hexid("case"), hexid("obs"), hexid("qr")
    now = datetime.now(timezone.utc)
    consented = iso(now - timedelta(minutes=20))
    steps = [
        ("POST", "/v1/consent-events", {
            "consent_event_id": ce, "participant_ref": ppt,
            "consent_form": {"form_id": "asha-wellness-consent", "form_version": "0.1.0", "language_tag": language},
            "scope_decisions": [{"scope": "SCREENING_PARTICIPATION", "granted": True}],
            "capture": {"captured_at_utc": consented, "method": "VERBAL_WITNESSED", "read_aloud": True,
                        "comprehension_confirmed": True, "witness_present": True},
            "safeguards": {"adult_confirmed": True}}, 201),
        ("POST", "/v1/cases", {
            "case_id": case, "participant_ref": ppt, "consent_event_id": ce, "language_tag": language,
            "opened_at_utc": consented, "data_origin": "SYNTHETIC", "age_band": "AGE_45_59", "gender": "FEMALE"}, 201),
        ("PUT", f"/v1/participants/{ppt}/identity", {
            "consent_event_id": ce, "data_origin": "SYNTHETIC", "display_name": NAME, "village_name": VILLAGE}, 204),
        ("POST", f"/v1/cases/{case}/observations", {
            "observation_set_id": obs, "catalogue": {"catalogue_id": "asha-observations", "catalogue_version": "0.1.0"},
            "observed_at_utc": iso(now - timedelta(minutes=10)),
            "observations": [{"code": "HEALTH_CONDITION", "value_code": "DIABETES"},
                             {"code": "TOBACCO_USE", "value_code": "NOT_REPORTED"},
                             {"code": "ALCOHOL_USE", "value_code": "NOT_REPORTED"},
                             {"code": "SLEEP_QUALITY", "value_code": "VERY_POOR"}]}, 201),
        ("POST", f"/v1/cases/{case}/questionnaire-responses", {
            "response_id": qr, "instrument": {"instrument_id": "illustrative-wellness", "instrument_version": "0.1.0"},
            "language_tag": language, "answered_at_utc": iso(now - timedelta(minutes=5)),
            "items": [{"item_id": "LOW_MOOD", "answer_code": "NEARLY_EVERY_DAY"},
                      {"item_id": "SELF_HARM_THOUGHTS", "answer_code": safety}]}, 201),
        ("POST", f"/v1/cases/{case}/completion", {
            "completed_at_utc": iso(now - timedelta(minutes=1)), "follow_up_requested": follow_up}, 201),
    ]
    last = None
    for method, path, body, expected in steps:
        status, data = asha.call(method, path, body)
        assert status == expected, f"{method} {path}: {status} {data}"
        last = data if data else last
    return ppt, case, last


def test_full_visit_is_saved_where_it_belongs(asha, secrets_env):
    ppt, case, done = visit(asha)
    assert done["status"] == "COMPLETED"
    assert done["urgent_review_flag"] is True and done["follow_up_requested"] is True
    assert done["language_tag"] == "hi-IN"

    status, read = asha.call("GET", f"/v1/cases/{case}")
    assert status == 200 and read == done
    status, followups = asha.call("GET", "/v1/cases?follow_up=true")
    assert status == 200 and case in [c["case_id"] for c in followups["cases"]]

    assert psql(secrets_env, f"SELECT age_band || ',' || gender FROM cases.assessment_case WHERE case_id = '{case}'") \
        == "AGE_45_59,FEMALE"
    assert psql(secrets_env, "SELECT string_agg(item_id || '=' || answer_code, ',' ORDER BY item_id) FROM "
                f"cases.questionnaire_answer a JOIN cases.questionnaire_response r USING (response_id) WHERE r.case_id = '{case}'") \
        == "LOW_MOOD=NEARLY_EVERY_DAY,SELF_HARM_THOUGHTS=SOMETIMES"
    assert "URGENT_REVIEW_FLAG_RAISED" in psql(secrets_env, f"SELECT string_agg(action, ',') FROM audit.audit_event WHERE resource_id = '{case}'")

    # identity_db: one row of ciphertext, readable only by identity_user.
    row = psql_as(secrets_env, "identity_user", "IDENTITY_DB_PASSWORD", "identity_db",
                  f"SELECT data_origin, encode(display_name_enc, 'escape') FROM identity.person WHERE participant_ref = '{ppt}'")
    assert row.startswith("SYNTHETIC|") and "Kappa" not in row
    refused = psql_as(secrets_env, "app_user", "APP_DB_PASSWORD", "identity_db", "SELECT 1", check=False)
    assert refused.returncode != 0, "app_user must not reach identity_db"

    # app_db holds the participant reference but never the name or village.
    tables = psql(secrets_env, "SELECT string_agg(DISTINCT table_schema || '.' || table_name, ' ') FROM information_schema.columns "
                  "WHERE table_schema IN ('consent','voice','processing','research_export','audit','cases','idempotency')").split()
    for table in tables:
        hits = psql(secrets_env, f"SELECT count(*) FROM {table} t WHERE t::text LIKE '%Kappa-5521%' OR t::text LIKE '%Testgrama-0912%'")
        assert hits == "0", table


def test_minors_and_real_data_are_refused(asha):
    ppt, ce = hexid("ppt"), hexid("ce")
    consented = iso(datetime.now(timezone.utc) - timedelta(minutes=5))
    status, _ = asha.call("POST", "/v1/consent-events", {
        "consent_event_id": ce, "participant_ref": ppt,
        "consent_form": {"form_id": "asha-wellness-consent", "form_version": "0.1.0", "language_tag": "ur-IN"},
        "scope_decisions": [{"scope": "SCREENING_PARTICIPATION", "granted": True}],
        "capture": {"captured_at_utc": consented, "method": "SIGNED_ON_DEVICE", "read_aloud": True,
                    "comprehension_confirmed": True},
        "safeguards": {"adult_confirmed": True}})
    assert status == 201
    base = {"participant_ref": ppt, "consent_event_id": ce, "language_tag": "ur-IN", "opened_at_utc": consented,
            "data_origin": "SYNTHETIC", "age_band": "AGE_UNDER_18"}
    status, problem = asha.call("POST", "/v1/cases", {**base, "case_id": hexid("case")})
    assert (status, problem["code"]) == (422, "ADULT_CONFIRMATION_REQUIRED")
    status, problem = asha.call("POST", "/v1/cases", {**base, "case_id": hexid("case"), "age_band": "AGE_18_29",
                                                       "data_origin": "PARTICIPANT"})
    assert (status, problem["code"]) == (422, "DATA_ORIGIN_NOT_PERMITTED")


def test_clinician_reviews_a_flagged_visit_and_the_worker_sees_it(asha, clinician, secrets_env):
    _, case, _ = visit(asha, language="kn-IN", safety="OFTEN", follow_up=False)

    status, queue = clinician.call("GET", "/v1/review-queue?limit=200")
    assert status == 200
    item = next(i for i in queue["items"] if i["case"]["case_id"] == case)
    assert item["priority"] == "URGENT" and item["case"]["review"]["status"] == "AWAITING_REVIEW"

    status, detail = clinician.call("GET", f"/v1/review-queue/{case}")
    assert status == 200 and detail["questionnaire"]["validated"] is False
    assert NAME not in json.dumps(detail) and VILLAGE not in json.dumps(detail)

    review_id = hexid("rvw")
    status, recorded = clinician.call("POST", f"/v1/review-queue/{case}/reviews", {
        "review_id": review_id, "decision": "URGENT_REFER", "follow_up_within": "WITHIN_24_HOURS",
        "reviewed_at_utc": iso(datetime.now(timezone.utc))})
    assert status == 201 and recorded["reviewed_by_ref"].startswith("clin_")

    status, seen = asha.call("GET", f"/v1/cases/{case}")
    assert status == 200 and seen["review"]["status"] == "REVIEWED" and seen["review"]["decision"] == "URGENT_REFER"
    assert asha.call("GET", "/v1/review-queue")[0] == 403, "workers cannot open the clinician queue"
    assert "CLINICIAN_REVIEW_RECORDED" in psql(secrets_env, f"SELECT action FROM audit.audit_event WHERE resource_id = '{review_id}'")
