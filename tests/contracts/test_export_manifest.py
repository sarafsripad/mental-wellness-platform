"""Tests for contracts/research-export/export-manifest.schema.json (v1.0.0)."""
import copy
import json

import pytest
from jsonschema import Draft202012Validator

from conftest import CONTRACTS

SCHEMA = json.loads((CONTRACTS / "research-export/export-manifest.schema.json").read_text())
EXAMPLE = json.loads((CONTRACTS / "research-export/examples/manifest-valid.json").read_text())
V = Draft202012Validator(SCHEMA)


def test_schema_and_example_are_valid():
    Draft202012Validator.check_schema(SCHEMA)
    assert V.is_valid(EXAMPLE)


def test_governance_flags():
    gov = SCHEMA["x-governance"]
    assert gov["direct_identifiers_prohibited"] and gov["application_ids_prohibited"] and gov["consent_filtered_at_export_time"]


@pytest.mark.parametrize("field,value", [
    ("participant_ref", "ppt_" + "0" * 32),
    ("hmac_key", "secret"),
    ("participants", ["x"]),
])
def test_no_identifiers_or_keys_can_be_added(field, value):
    m = copy.deepcopy(EXAMPLE)
    m[field] = value
    assert not V.is_valid(m)


def test_split_groups_map_sessions_to_groups_only():
    m = copy.deepcopy(EXAMPLE)
    m["split_groups"] = {"sess_" + "1" * 32: "grp_" + "2" * 32}
    assert V.is_valid(m)
    m["split_groups"] = {"ppt_" + "1" * 32: "grp_" + "2" * 32}
    assert not V.is_valid(m), "keys must be re-keyed session IDs, never participant references"


def test_exclusions_are_counts_not_ids():
    m = copy.deepcopy(EXAMPLE)
    m["exclusions"]["no_research_consent"] = ["frec_" + "0" * 32]
    assert not V.is_valid(m)
