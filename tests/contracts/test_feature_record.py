"""Tests for contracts/voice-features/feature-record.schema.json (v2.0.0).

All data here is synthetic. Records are built from the committed example.
"""
import copy
import json

import pytest
from jsonschema import Draft202012Validator

from conftest import FEATURE_DIR

VALID_DIR = FEATURE_DIR / "examples" / "valid"
INVALID_DIR = FEATURE_DIR / "examples" / "invalid"

# Every invalid example and the JSON Schema keyword expected to reject it.
# A new invalid example must be added here, so its intent is stated.
EXPECTED_REJECTIONS = {
    "clinical-feature-name.json": "not",
    "clinical-feature-name-uppercase.json": "not",
    "degraded-without-reason.json": "type",
    "direct-identifier-field.json": "additionalProperties",
    "fail-quality-record.json": "enum",
    "free-text-warning.json": "pattern",
    "malformed-session-id.json": "pattern",
    "missing-consent-reference.json": "required",
    "model-prediction-embedded.json": "additionalProperties",
    "no-audio-recording-consent.json": "contains",
    "raw-audio-embedded.json": "additionalProperties",
    "valid-feature-with-null-value.json": "type",
}


def _errors(validator, record):
    return list(validator.iter_errors(record))


# --- schema metadata -----------------------------------------------------------

def test_schema_is_valid_draft_2020_12(feature_schema):
    Draft202012Validator.check_schema(feature_schema)


def test_version_is_consistent(feature_schema):
    version = feature_schema["version"]
    assert feature_schema["$id"].endswith(f"/v{version}")
    assert feature_schema["properties"]["schema_version"]["const"] == version
    assert feature_schema["x-changelog"][-1]["version"] == version


def test_governance_flags_are_set(feature_schema):
    gov = feature_schema["x-governance"]
    for flag in ("direct_identifiers_prohibited", "clinical_labels_prohibited_in_features",
                 "consent_reference_required", "raw_audio_prohibited",
                 "transcript_content_prohibited", "model_predictions_prohibited"):
        assert gov.get(flag) is True, flag


def test_record_type_marks_engineering_record(feature_schema):
    assert feature_schema["properties"]["record_type"]["const"] == "ENGINEERING_FEATURE_RECORD"


# --- committed examples --------------------------------------------------------

@pytest.mark.parametrize("path", sorted(VALID_DIR.glob("*.json")), ids=lambda p: p.name)
def test_valid_examples_pass(feature_validator, path):
    errors = _errors(feature_validator, json.loads(path.read_text()))
    assert not errors, errors[0].message


@pytest.mark.parametrize("path", sorted(INVALID_DIR.glob("*.json")), ids=lambda p: p.name)
def test_invalid_examples_fail_for_expected_reason(feature_validator, path):
    assert path.name in EXPECTED_REJECTIONS, f"add {path.name} to EXPECTED_REJECTIONS"
    keywords = {e.validator for e in _errors(feature_validator, json.loads(path.read_text()))}
    assert keywords, f"{path.name} unexpectedly passed"
    assert EXPECTED_REJECTIONS[path.name] in keywords, keywords


def test_every_expected_rejection_has_a_file():
    present = {p.name for p in INVALID_DIR.glob("*.json")}
    assert set(EXPECTED_REJECTIONS) == present


def test_examples_are_marked_non_participant():
    for path in VALID_DIR.glob("*.json"):
        origin = json.loads(path.read_text())["provenance"]["data_origin"]
        assert origin in ("SYNTHETIC", "AUTHORIZED_TEST_AUDIO"), path.name


# --- privacy invariants --------------------------------------------------------

IDENTIFIER_FIELDS = ["name", "participant_name", "phone", "phone_number", "address",
                     "village", "district", "location", "gps", "date_of_birth",
                     "aadhaar", "device_id", "imei", "email", "worker_name"]
PAYLOAD_FIELDS = ["transcript", "audio_base64", "audio_url", "diagnosis", "prediction",
                  "risk_score", "label"]


@pytest.mark.parametrize("field", IDENTIFIER_FIELDS + PAYLOAD_FIELDS)
@pytest.mark.parametrize("where", [None, "audio", "provenance", "consent_reference",
                                   "quality", "extraction", "processing"])
def test_extra_fields_are_rejected_everywhere(feature_validator, valid_record, field, where):
    target = valid_record if where is None else valid_record[where]
    target[field] = "synthetic-value"
    assert _errors(feature_validator, valid_record)


@pytest.mark.parametrize("field", ["participant_name", "phone", "transcript"])
def test_extra_fields_rejected_inside_feature_items(feature_validator, valid_record, field):
    valid_record["features"][0][field] = "synthetic-value"
    assert _errors(feature_validator, valid_record)


@pytest.mark.parametrize("bad_name", [
    "depression_score", "DEPRESSION_index", "dEpReSsIoN", "bipolar_flag", "Dementia_x",
    "anxiety_level", "phq9_total", "GAD7", "mood_estimate", "suicide_risk", "RiskLevel",
    "predicted_class", "diagnosis_code", "label_1", "score",
])
def test_clinical_feature_names_rejected(feature_validator, valid_record, bad_name):
    valid_record["features"][0]["name"] = bad_name
    assert _errors(feature_validator, valid_record)


@pytest.mark.parametrize("good_name", [
    "F0semitoneFrom27.5Hz_sma3nz_amean", "jitterLocal_sma3nz_amean",
    "shimmerLocaldB_sma3nz_amean", "HNRdBACF_sma3nz_amean", "logRelF0-H1-H2_sma3nz_amean",
    "alphaRatioV_sma3nz_amean", "hammarbergIndexV_sma3nz_amean", "loudnessPeaksPerSec",
    "MeanVoicedSegmentLengthSec", "equivalentSoundLevel_dBp", "mfcc_sma[1]_amean",
    "pcm_fftMag_psySharpness_sma_amean",
])
def test_real_opensmile_names_accepted(feature_validator, valid_record, good_name):
    valid_record["features"][0]["name"] = good_name
    assert not _errors(feature_validator, valid_record)


@pytest.mark.parametrize("field,bad_value", [
    ("session_id", "sess_0000"),
    ("session_id", "sess_" + "A" * 32),
    ("session_id", "ses_" + "0" * 32),
    ("recording_id", "rec_" + "0" * 31),
    ("processing_job_id", "job_" + "g" * 32),
    ("record_id", "frec_" + "0" * 33),
])
def test_pseudonymous_ids_must_match_pattern(feature_validator, valid_record, field, bad_value):
    valid_record[field] = bad_value
    assert _errors(feature_validator, valid_record)


def test_consent_event_id_must_match_pattern(feature_validator, valid_record):
    valid_record["consent_reference"]["consent_event_id"] = "consent-placeholder"
    assert _errors(feature_validator, valid_record)


# --- consent reference ---------------------------------------------------------

def test_consent_reference_requires_audio_recording_scope(feature_validator, valid_record):
    valid_record["consent_reference"]["scopes_verified"] = ["SCREENING_PARTICIPATION",
                                                           "RESEARCH_FEATURE_REUSE"]
    assert _errors(feature_validator, valid_record)


def test_consent_reference_rejects_unknown_scope(feature_validator, valid_record):
    valid_record["consent_reference"]["scopes_verified"].append("CLINICAL_SUPPORT")
    assert _errors(feature_validator, valid_record)


@pytest.mark.parametrize("missing", ["consent_event_id", "scopes_verified", "verified_at_utc"])
def test_consent_reference_fields_required(feature_validator, valid_record, missing):
    del valid_record["consent_reference"][missing]
    assert _errors(feature_validator, valid_record)


# --- quality, processing and feature values ------------------------------------

def test_fail_quality_never_produces_record(feature_validator, valid_record):
    valid_record["quality"]["status"] = "FAIL"
    assert _errors(feature_validator, valid_record)


def test_degraded_requires_non_empty_reason(feature_validator, valid_record):
    valid_record["quality"].update(status="DEGRADED", degraded_reason="")
    assert _errors(feature_validator, valid_record)
    valid_record["quality"]["degraded_reason"] = "High background noise; pipeline test only"
    assert not _errors(feature_validator, valid_record)


def test_completed_with_warnings_requires_a_warning_code(feature_validator, valid_record):
    valid_record["processing"].update(status="COMPLETED_WITH_WARNINGS", warnings=[])
    assert _errors(feature_validator, valid_record)
    valid_record["processing"]["warnings"] = ["RESAMPLED_INPUT"]
    assert not _errors(feature_validator, valid_record)


@pytest.mark.parametrize("status", ["MISSING", "INVALID", "NOT_APPLICABLE"])
def test_non_valid_feature_must_have_null_value(feature_validator, valid_record, status):
    feature = valid_record["features"][0]
    feature.update(status=status, value=1.5, reason="NON_FINITE_VALUE")
    assert _errors(feature_validator, valid_record)
    feature["value"] = None
    assert not _errors(feature_validator, valid_record)


def test_feature_reason_must_be_a_code(feature_validator, valid_record):
    valid_record["features"][0].update(status="MISSING", value=None,
                                       reason="no voiced frames found")
    assert _errors(feature_validator, valid_record)


def test_at_least_one_feature_required(feature_validator, valid_record):
    valid_record["features"] = []
    assert _errors(feature_validator, valid_record)


@pytest.mark.parametrize("missing", ["data_origin", "capture_device_class", "recorded_at_utc"])
def test_provenance_fields_required(feature_validator, valid_record, missing):
    del valid_record["provenance"][missing]
    assert _errors(feature_validator, valid_record)


def test_schema_version_must_match(feature_validator, valid_record):
    for version in ("1.0.0", "2.1.0", "3.0.0"):
        record = copy.deepcopy(valid_record)
        record["schema_version"] = version
        assert _errors(feature_validator, record), version
