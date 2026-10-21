"""Validates the request bodies the React Native ASHA app sends (ADR-017).

The mobile Jest suite writes workstream-a/mobile/asha-care/__contract__/requests.json from the app's
own operation builder and fails if the file is stale. Each body is checked here against its
operation's requestBody schema, in whichever contract file declares the operation.
"""
import json

import pytest
import yaml
from jsonschema import Draft202012Validator, FormatChecker

from conftest import OPENAPI_DIR, ROOT
from test_backend_samples import _find_operation, _get, _pointer

SAMPLES = ROOT / "workstream-a/mobile/asha-care/__contract__/requests.json"
CONTRACTS = sorted(OPENAPI_DIR.glob("*.v1.yaml"))


def _locate(operation_id):
    for path in CONTRACTS:
        doc = yaml.safe_load(path.read_text())
        try:
            return path, doc, *_find_operation(doc, operation_id)
        except AssertionError:
            continue
    raise AssertionError(f"operationId {operation_id} is in no contract")


@pytest.mark.parametrize("sample", json.loads(SAMPLES.read_text()), ids=lambda s: s["operation_id"])
def test_mobile_request_matches_contract(sample, registry):
    path, doc, api_path, method, op = _locate(sample["operation_id"])
    assert method.upper() == sample["method"]
    pattern = api_path.replace("{", "<").replace("}", ">")
    assert len(pattern.split("/")) == len(sample["path"].split("/")), f"{sample['path']} does not fit {api_path}"
    body = op["requestBody"]
    body_ptr = _pointer("paths", api_path, method, "requestBody")
    if "$ref" in body:
        body_ptr = body["$ref"][1:]
        body = _get(doc, body["$ref"])
    validator = Draft202012Validator(
        {"$ref": f"{path.as_uri()}#{body_ptr}{_pointer('content', 'application/json', 'schema')}"},
        registry=registry, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(sample["request"]), key=lambda e: list(e.absolute_path))
    assert not errors, f"{'/'.join(map(str, errors[0].absolute_path)) or '<root>'}: {errors[0].message}"
