"""Structural, example and policy tests for contracts/openapi/*.yaml."""
import pytest
from openapi_spec_validator import validate as validate_openapi

from conftest import OPENAPI_DIR

PUBLIC_V1 = "wellness-api.v1.yaml"
WORKER_V1 = "processing-worker.internal.v1.yaml"
PROVISIONAL = "wellness-api-provisional.v0.yaml"
IDENTITY_V1 = "identity-api.v1.yaml"
HTTP_METHODS = ("get", "put", "post", "delete", "patch")


def operations(doc):
    for path, item in doc.get("paths", {}).items():
        for method in HTTP_METHODS:
            if method in item:
                yield path, method, item[method]


def param_refs(op):
    return {p.get("$ref", "").rsplit("/", 1)[-1] for p in op.get("parameters", [])} | \
           {p.get("name") for p in op.get("parameters", []) if "name" in p}


@pytest.mark.parametrize("name", [PUBLIC_V1, WORKER_V1, IDENTITY_V1, PROVISIONAL])
def test_openapi_documents_are_structurally_valid(openapi_docs, name):
    validate_openapi(openapi_docs[name], base_uri=(OPENAPI_DIR / name).as_uri())


def test_all_openapi_files_are_covered(openapi_docs):
    assert set(openapi_docs) == {PUBLIC_V1, WORKER_V1, IDENTITY_V1, PROVISIONAL}


@pytest.mark.parametrize("name", [PUBLIC_V1, WORKER_V1, IDENTITY_V1, PROVISIONAL])
def test_embedded_examples_match_schemas(validate_examples, openapi_docs, registry, name):
    validate_examples.failures = []
    validate_examples.check_openapi(OPENAPI_DIR / name, openapi_docs[name], registry)
    assert validate_examples.failures == []


def test_example_checker_detects_bad_examples(validate_examples, openapi_docs, registry):
    """Guard against the checker silently validating nothing."""
    import copy
    doc = copy.deepcopy(openapi_docs[PUBLIC_V1])
    doc["components"]["examples"]["ProcessingJobSucceededExample"]["value"]["status"] = "DONE"
    path = OPENAPI_DIR / PUBLIC_V1
    from referencing import Resource
    from referencing.jsonschema import DRAFT202012
    reg = registry.with_resource(path.as_uri(),
                                 Resource.from_contents(doc, default_specification=DRAFT202012))
    validate_examples.failures = []
    validate_examples.check_openapi(path, doc, reg)
    assert any("DONE" in f for f in validate_examples.failures)


# --- authentication and authorization policy -----------------------------------

@pytest.mark.parametrize("name", [PUBLIC_V1, WORKER_V1, IDENTITY_V1, PROVISIONAL])
def test_no_anonymous_operations(openapi_docs, name):
    doc = openapi_docs[name]
    assert doc.get("security"), "global security requirement missing"
    for path, method, op in operations(doc):
        if "security" in op:
            assert op["security"] and {} not in op["security"], f"{method} {path}"


@pytest.mark.parametrize("name", [PUBLIC_V1, WORKER_V1, IDENTITY_V1, PROVISIONAL])
def test_researcher_has_no_application_access(openapi_docs, name):
    for path, method, op in operations(openapi_docs[name]):
        assert "RESEARCHER" not in op.get("x-roles", []), f"{method} {path}"


@pytest.mark.parametrize("name", [PUBLIC_V1, WORKER_V1, IDENTITY_V1])
def test_v1_operations_declare_roles_and_audit(openapi_docs, name):
    for path, method, op in operations(openapi_docs[name]):
        assert op.get("x-roles"), f"{method} {path}: x-roles"
        assert "x-audit-events" in op, f"{method} {path}: x-audit-events"


@pytest.mark.parametrize("name", [PUBLIC_V1, IDENTITY_V1])
def test_public_operations_declare_consent_prerequisite(openapi_docs, name):
    for path, method, op in operations(openapi_docs[name]):
        assert op.get("x-consent-prerequisite"), f"{method} {path}"


def test_worker_operations_are_worker_only(openapi_docs):
    for path, method, op in operations(openapi_docs[WORKER_V1]):
        assert op["x-roles"] == ["PROCESSING_WORKER"], f"{method} {path}"
        assert path.startswith("/internal/"), path


def test_admin_participant_access_accepts_reason_code(openapi_docs):
    for path, method, op in operations(openapi_docs[PUBLIC_V1]):
        if "ADMIN" in op.get("x-roles", []):
            assert "AccessReason" in param_refs(op), f"{method} {path}"


@pytest.mark.parametrize("name", [PUBLIC_V1, IDENTITY_V1])
def test_public_operations_carry_correlation_id(openapi_docs, name):
    for path, method, op in operations(openapi_docs[name]):
        assert "CorrelationId" in param_refs(op), f"{method} {path}"


def test_public_creates_require_idempotency_key(openapi_docs):
    for path, method, op in operations(openapi_docs[PUBLIC_V1]):
        if method == "post":
            assert "IdempotencyKey" in param_refs(op), f"{method} {path}"


# --- privacy and clinical-safety policy ----------------------------------------

def _walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk(value)


def test_public_api_never_exposes_feature_values_or_predictions(openapi_docs):
    schemas = openapi_docs[PUBLIC_V1]["components"]["schemas"]
    forbidden = {"features", "prediction", "diagnosis", "risk_score", "indicator", "label"}
    for name, schema in schemas.items():
        for node in _walk(schema):
            props = set(node.get("properties", {})) if isinstance(node.get("properties"), dict) else set()
            assert not props & forbidden, f"{name}: {props & forbidden}"


def test_public_api_schemas_have_no_direct_identifiers(openapi_docs):
    schemas = openapi_docs[PUBLIC_V1]["components"]["schemas"]
    forbidden = {"name", "display_name", "phone", "contact_phone", "address", "village",
                 "date_of_birth", "aadhaar", "device_id", "imei", "email"}
    for name, schema in schemas.items():
        for node in _walk(schema):
            props = set(node.get("properties", {})) if isinstance(node.get("properties"), dict) else set()
            assert not props & forbidden, f"{name}: {props & forbidden}"


def test_identity_api_is_write_only(openapi_docs):
    """Direct identifiers go in and never come out (ADR-010): no GET, no success body."""
    for path, method, op in operations(openapi_docs[IDENTITY_V1]):
        assert method in ("put", "post"), f"{method} {path}"
        for status, response in op["responses"].items():
            if str(status).startswith("2"):
                assert "content" not in response, f"{method} {path} {status} returns a body"


def test_direct_identifiers_only_in_identity_api(openapi_docs):
    forbidden = {"display_name", "village_name", "contact_phone"}
    for name, doc in openapi_docs.items():
        if name in (IDENTITY_V1, PROVISIONAL):
            continue
        for node in _walk(doc):
            props = set(node.get("properties", {})) if isinstance(node.get("properties"), dict) else set()
            assert not props & forbidden, f"{name}: {props & forbidden}"


def test_no_score_fields_on_cases(openapi_docs):
    schemas = openapi_docs[PUBLIC_V1]["components"]["schemas"]
    for name in ("Case", "QuestionnaireResponseCreate", "ObservationSetCreate"):
        for node in _walk(schemas[name]):
            props = set(node.get("properties", {})) if isinstance(node.get("properties"), dict) else set()
            assert not {p for p in props if "score" in p or "severity" in p or "risk" in p}, name


@pytest.mark.parametrize("name", [PUBLIC_V1, WORKER_V1])
def test_problem_bodies_are_closed(openapi_docs, name):
    problem = openapi_docs[name]["components"]["schemas"]["Problem"]
    assert problem.get("additionalProperties") is False
    assert not {"stack", "trace", "exception", "stackTrace"} & set(problem["properties"])


def test_minors_rejected_in_v1(api_validator):
    validator = api_validator(PUBLIC_V1, "ConsentEventCreate")
    event = {
        "consent_event_id": "ce_" + "0" * 32,
        "participant_ref": "ppt_" + "0" * 32,
        "consent_form": {"form_id": "screening-consent", "form_version": "0.1.0",
                         "language_tag": "kn-IN"},
        "scope_decisions": [{"scope": "SCREENING_PARTICIPATION", "granted": True}],
        "capture": {"captured_at_utc": "2026-10-17T09:05:00Z", "method": "SIGNED_ON_DEVICE",
                    "read_aloud": True, "comprehension_confirmed": True},
        "safeguards": {"adult_confirmed": True},
    }
    assert validator.is_valid(event)
    event["safeguards"]["adult_confirmed"] = False
    assert not validator.is_valid(event)


def test_comprehension_must_be_confirmed(api_validator):
    validator = api_validator(PUBLIC_V1, "ConsentEventCreate")
    event = {
        "consent_event_id": "ce_" + "0" * 32,
        "participant_ref": "ppt_" + "0" * 32,
        "consent_form": {"form_id": "screening-consent", "form_version": "0.1.0",
                         "language_tag": "en-IN"},
        "scope_decisions": [{"scope": "SCREENING_PARTICIPATION", "granted": True}],
        "capture": {"captured_at_utc": "2026-10-17T09:05:00Z", "method": "VERBAL_WITNESSED",
                    "read_aloud": True, "comprehension_confirmed": False},
        "safeguards": {"adult_confirmed": True},
    }
    assert not validator.is_valid(event)


@pytest.mark.parametrize("status,field", [("SUCCEEDED", "feature_record_id"),
                                          ("FAILED", "failure"), ("CANCELLED", "failure")])
def test_processing_job_status_requires_outcome(api_validator, openapi_docs, status, field):
    validator = api_validator(PUBLIC_V1, "ProcessingJob")
    examples = openapi_docs[PUBLIC_V1]["components"]["examples"]
    source = "ProcessingJobSucceededExample" if status == "SUCCEEDED" else "ProcessingJobFailedExample"
    job = dict(examples[source]["value"], status=status)
    assert validator.is_valid(job)
    job[field] = None
    assert not validator.is_valid(job)
