"""Checks that the feature schema and the OpenAPI contracts agree with each other."""
import re

import pytest

from conftest import FEATURE_DIR, OPENAPI_DIR

PUBLIC_V1 = "wellness-api.v1.yaml"
WORKER_V1 = "processing-worker.internal.v1.yaml"


def _walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk(value)


# Scopes that never appear in feature records: they don't govern audio or features (ADR-015).
# Feature records list only the scopes of feature-record v2.0.0 (ConsentScope.FEATURE_RECORD_SCOPES).
NON_FEATURE_SCOPES = {"EXTERNAL_REFERRAL_SHARING"}


def test_consent_scopes_identical(feature_schema, openapi_docs):
    feature_scopes = feature_schema["$defs"]["consentScope"]["enum"]
    api_scopes = openapi_docs[PUBLIC_V1]["components"]["schemas"]["ConsentScope"]["enum"]
    assert feature_scopes == [s for s in api_scopes if s not in NON_FEATURE_SCOPES]
    assert NON_FEATURE_SCOPES <= set(api_scopes)


@pytest.mark.parametrize("prefix", ["sess_", "rec_", "job_", "ce_", "frec_", "ppt_", "cw_"])
def test_id_patterns_identical_everywhere(feature_schema, openapi_docs, prefix):
    sources = [feature_schema, *openapi_docs.values()]
    patterns = {node["pattern"] for src in sources for node in _walk(src)
                if isinstance(node.get("pattern"), str) and node["pattern"].startswith(f"^{prefix}")}
    assert patterns, f"no pattern found for {prefix}"
    assert patterns == {f"^{prefix}[a-f0-9]{{32}}$"}, patterns


def test_pipeline_profiles_identical(openapi_docs):
    public = openapi_docs[PUBLIC_V1]["components"]["schemas"]["ProcessingJobCreate"]
    worker = openapi_docs[WORKER_V1]["components"]["schemas"]["JobLease"]
    assert public["properties"]["pipeline_profile"]["enum"] == \
        worker["properties"]["pipeline_profile"]["enum"]


def test_data_origin_values_identical(feature_schema, openapi_docs):
    feature = feature_schema["properties"]["provenance"]["properties"]["data_origin"]["enum"]
    public = openapi_docs[PUBLIC_V1]["components"]["schemas"]["VoiceSessionCreate"]
    worker = openapi_docs[WORKER_V1]["components"]["schemas"]["JobLease"]
    assert feature == public["properties"]["data_origin"]["enum"] == \
        worker["properties"]["data_origin"]["enum"]


def test_api_languages_allowed_by_feature_schema(feature_schema, openapi_docs):
    pattern = re.compile(feature_schema["properties"]["language_tag"]["pattern"])
    for tag in openapi_docs[PUBLIC_V1]["components"]["schemas"]["LanguageTag"]["enum"]:
        assert pattern.match(tag), tag


def test_api_device_classes_allowed_by_feature_schema(feature_schema, openapi_docs):
    allowed = set(feature_schema["properties"]["provenance"]["properties"]
                  ["capture_device_class"]["enum"])
    device = openapi_docs[PUBLIC_V1]["components"]["schemas"]["VoiceSessionCreate"] \
        ["properties"]["device"]["properties"]["device_class"]["enum"]
    assert set(device) <= allowed


def test_quality_failure_flags_identical(openapi_docs):
    public = openapi_docs[PUBLIC_V1]["components"]["schemas"]["ProcessingJob"] \
        ["properties"]["quality_outcome"]["oneOf"][1]["properties"]["flags"]["items"]["enum"]
    worker = openapi_docs[WORKER_V1]["components"]["schemas"]["FailureReport"] \
        ["properties"]["quality_outcome"]["properties"]["flags"]["items"]["enum"]
    assert public == worker


def test_worker_result_body_is_the_feature_schema(openapi_docs):
    op = openapi_docs[WORKER_V1]["paths"]["/internal/v1/processing-jobs/{jobId}/result"]["post"]
    ref = op["requestBody"]["content"]["application/json"]["schema"]["$ref"]
    assert (OPENAPI_DIR / ref).resolve() == (FEATURE_DIR / "feature-record.schema.json").resolve()


def test_failure_codes_cover_worker_reports(openapi_docs):
    public = set(openapi_docs[PUBLIC_V1]["components"]["schemas"]["ProcessingJob"]
                 ["properties"]["failure"]["oneOf"][1]["properties"]["code"]["enum"])
    worker = set(openapi_docs[WORKER_V1]["components"]["schemas"]["FailureReport"]
                 ["properties"]["code"]["enum"])
    assert worker <= public


def test_attempt_limit_matches_documented_maximum(openapi_docs):
    job = openapi_docs[PUBLIC_V1]["components"]["schemas"]["ProcessingJob"]
    assert job["properties"]["attempt"]["maximum"] == 3
