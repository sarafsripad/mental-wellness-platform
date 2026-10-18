"""Validates real API traffic recorded by the backend integration tests.

The Java tests write build/contract-samples/*.json (see ContractSamples.java). Each sample's
request body is checked against the operation's requestBody schema, and its response body
against the schema for the actual status code. Skipped when no samples exist, e.g. when the
backend tests have not been run.
"""
import json
import os
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator, FormatChecker

from conftest import OPENAPI_DIR, ROOT

SAMPLES = Path(os.environ.get(
    "CONTRACT_SAMPLES_DIR", ROOT / "workstream-a/backend/wellness-api/build/contract-samples"))
SAMPLE_FILES = sorted(SAMPLES.glob("*.json")) if SAMPLES.is_dir() else []


def _find_operation(doc, operation_id):
    for path, item in doc["paths"].items():
        for method, op in item.items():
            if isinstance(op, dict) and op.get("operationId") == operation_id:
                return path, method, op
    raise AssertionError(f"operationId {operation_id} not in contract")



def _get(doc, ref):
    node = doc
    for part in ref[2:].split("/"):
        node = node[part.replace("~1", "/").replace("~0", "~")]
    return node


def _pointer(*parts):
    return "/" + "/".join(str(p).replace("~", "~0").replace("/", "~1") for p in parts)


@pytest.mark.skipif(not SAMPLE_FILES, reason="no backend contract samples (run ./gradlew test first)")
@pytest.mark.parametrize("sample_file", SAMPLE_FILES, ids=lambda p: p.name)
def test_recorded_traffic_matches_contract(sample_file, registry):
    sample = json.loads(sample_file.read_text())
    path = OPENAPI_DIR / sample["file"]
    doc = yaml.safe_load(path.read_text())
    api_path, method, op = _find_operation(doc, sample["operation_id"])
    op_ptr = _pointer("paths", api_path, method)
    uri = path.as_uri()

    def validate(schema_ptr, instance, what):
        validator = Draft202012Validator({"$ref": f"{uri}#{schema_ptr}"}, registry=registry,
                                         format_checker=FormatChecker())
        errors = sorted(validator.iter_errors(instance), key=lambda e: list(e.absolute_path))
        assert not errors, f"{what}: {'/'.join(map(str, errors[0].absolute_path)) or '<root>'}: {errors[0].message}"

    if sample["request"] is not None and int(sample["status"]) < 400:
        body = op["requestBody"]
        body_ptr = op_ptr + "/requestBody"
        if "$ref" in body:
            body_ptr = body["$ref"][1:]
            body = _get(doc, body["$ref"])
        assert "application/json" in body["content"]
        validate(body_ptr + _pointer("content", "application/json", "schema"), sample["request"], "request")

    status = sample["status"]
    assert status in op["responses"], f"status {status} is not documented for {sample['operation_id']}"
    resp = op["responses"][status]
    resp_ptr = op_ptr + _pointer("responses", status)
    if "$ref" in resp:
        resp_ptr = resp["$ref"][1:]
        resp = _get(doc, resp["$ref"])
    if sample["response"] is None:
        assert "content" not in resp, "contract expects a body"
        return
    media = next(m for m in resp["content"] if sample["content_type"].startswith(m.split(";")[0]))
    validate(resp_ptr + _pointer("content", media, "schema"), sample["response"], "response")
