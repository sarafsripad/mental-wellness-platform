"""Shared fixtures for contract tests.

Reuses the helpers in scripts/validate-examples.py so the test suite and the
hook/CI script can never disagree about how references are resolved.
"""
import importlib.util
import json
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
FEATURE_DIR = CONTRACTS / "voice-features"
OPENAPI_DIR = CONTRACTS / "openapi"


def _load_validate_examples():
    spec = importlib.util.spec_from_file_location(
        "validate_examples", ROOT / "scripts" / "validate-examples.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="session")
def validate_examples():
    return _load_validate_examples()


@pytest.fixture(scope="session")
def feature_schema():
    return json.loads((FEATURE_DIR / "feature-record.schema.json").read_text())


@pytest.fixture(scope="session")
def openapi_docs():
    return {p.name: yaml.safe_load(p.read_text()) for p in sorted(OPENAPI_DIR.glob("*.yaml"))}


@pytest.fixture(scope="session")
def registry(validate_examples):
    docs = {p: yaml.safe_load(p.read_text()) for p in sorted(OPENAPI_DIR.glob("*.yaml"))}
    reg, _ = validate_examples.build_registry(docs)
    return reg


@pytest.fixture(scope="session")
def feature_validator(feature_schema, registry):
    return Draft202012Validator(feature_schema, registry=registry, format_checker=FormatChecker())


@pytest.fixture()
def valid_record():
    """A fresh copy of the complete, valid synthetic example record."""
    return json.loads((FEATURE_DIR / "examples" / "valid" / "complete-pass.json").read_text())


@pytest.fixture(scope="session")
def api_validator(registry):
    """Validator factory for a component schema in an OpenAPI file."""
    def make(file_name, schema_name):
        uri = (OPENAPI_DIR / file_name).as_uri()
        return Draft202012Validator({"$ref": f"{uri}#/components/schemas/{schema_name}"},
                                    registry=registry, format_checker=FormatChecker())
    return make
