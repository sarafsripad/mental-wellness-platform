#!/usr/bin/env python3
"""validate-examples.py

Validates contract examples and OpenAPI documents. Read-only.

  1. Every contracts/voice-features/examples/valid/*.json passes
     feature-record.schema.json, and every examples/invalid/*.json fails it.
  2. Every contracts/openapi/*.yaml is a structurally valid OpenAPI 3.1 document.
  3. Every request/response example embedded in an OpenAPI document validates
     against its media-type schema (refs, including the external feature-record
     schema, are resolved).
  4. Policy checks: no operation is anonymous, every v1 operation declares
     x-roles and x-audit-events, and RESEARCHER has no access to the app APIs.

Exit codes: 0 = all checks passed, 1 = a check failed, 1 = dependencies missing
(0 with --allow-missing-deps, for use from editor hooks).
Dependencies: requirements-dev.txt (jsonschema, PyYAML, openapi-spec-validator).
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTRACTS = ROOT / "contracts"
FEATURE_DIR = CONTRACTS / "voice-features"
FEATURE_SCHEMA = FEATURE_DIR / "feature-record.schema.json"
OPENAPI_DIR = CONTRACTS / "openapi"
HTTP_METHODS = ("get", "put", "post", "delete", "patch")

try:
    import yaml
    from jsonschema import Draft202012Validator, FormatChecker
    from openapi_spec_validator import validate as validate_openapi
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
except ImportError as exc:
    print(f"  SKIP [validate-examples] missing dependency ({exc.name}); "
          "run: python3 -m pip install -r requirements-dev.txt")
    sys.exit(0 if "--allow-missing-deps" in sys.argv else 1)

failures = []


def fail(msg):
    failures.append(msg)
    print(f"  FAIL {msg}")


def pointer_escape(part):
    return str(part).replace("~", "~0").replace("/", "~1")


def build_registry(openapi_docs):
    feature = json.loads(FEATURE_SCHEMA.read_text())
    feature_res = Resource.from_contents(feature, default_specification=DRAFT202012)
    registry = Registry().with_resources([
        (FEATURE_SCHEMA.as_uri(), feature_res),
        (feature["$id"], feature_res),
    ])
    for path, doc in openapi_docs.items():
        registry = registry.with_resource(
            path.as_uri(), Resource.from_contents(doc, default_specification=DRAFT202012))
    return registry, feature


# 1. Feature-record examples ---------------------------------------------------
def check_feature_examples(feature, registry):
    validator = Draft202012Validator(feature, registry=registry, format_checker=FormatChecker())
    Draft202012Validator.check_schema(feature)
    for f in sorted((FEATURE_DIR / "examples" / "valid").glob("*.json")):
        errors = list(validator.iter_errors(json.loads(f.read_text())))
        if errors:
            fail(f"[feature valid]   {f.name}: {errors[0].message[:120]}")
        else:
            print(f"  OK   [feature valid]   {f.name}")
    for f in sorted((FEATURE_DIR / "examples" / "invalid").glob("*.json")):
        errors = list(validator.iter_errors(json.loads(f.read_text())))
        if not errors:
            fail(f"[feature invalid] {f.name}: unexpectedly passed validation")
        else:
            print(f"  OK   [feature invalid] {f.name} rejected ({errors[0].validator})")


# 2-4. OpenAPI documents -------------------------------------------------------
def local_ref(doc, ref):
    node = doc
    for part in ref.lstrip("#/").split("/"):
        node = node[part.replace("~1", "/").replace("~0", "~")]
    return node


def resolve_with_pointer(doc, obj, pointer):
    """Follow local non-schema $refs (responses, requestBodies, examples)."""
    while isinstance(obj, dict) and "$ref" in obj and obj["$ref"].startswith("#/"):
        pointer = obj["$ref"][1:]
        obj = local_ref(doc, obj["$ref"])
    return obj, pointer


def check_media(doc, path, registry, media, media_ptr, label):
    if "schema" not in media:
        return 0
    schema_ref = {"$ref": f"{path.as_uri()}#{media_ptr}/schema"}
    validator = Draft202012Validator(schema_ref, registry=registry, format_checker=FormatChecker())
    examples = dict(media.get("examples", {}))
    if "example" in media:
        examples["example"] = {"value": media["example"]}
    count = 0
    for name, ex in examples.items():
        ex, _ = resolve_with_pointer(doc, ex, "")
        if "value" not in ex:
            continue
        count += 1
        errors = list(validator.iter_errors(ex["value"]))
        if errors:
            e = errors[0]
            fail(f"[openapi example] {path.name} {label} '{name}': "
                 f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message[:120]}")
    return count


def check_openapi(path, doc, registry):
    try:
        validate_openapi(doc, base_uri=path.as_uri())
        print(f"  OK   [openapi struct]  {path.name}")
    except Exception as exc:  # validator raises several exception types
        fail(f"[openapi struct]  {path.name}: {str(exc).splitlines()[0][:160]}")
        return

    provisional = "provisional" in path.name
    global_security = doc.get("security")
    if not global_security:
        fail(f"[policy] {path.name}: no global security requirement")
    n_examples = 0
    for p, item in doc.get("paths", {}).items():
        for method in HTTP_METHODS:
            op = item.get(method)
            if not op:
                continue
            label = f"{method.upper()} {p}"
            op_ptr = f"/paths/{pointer_escape(p)}/{method}"
            if "security" in op and (not op["security"] or {} in op["security"]):
                fail(f"[policy] {path.name} {label}: anonymous access declared")
            roles = op.get("x-roles")
            if not provisional and (not roles or "x-audit-events" not in op):
                fail(f"[policy] {path.name} {label}: missing x-roles or x-audit-events")
            if roles and "RESEARCHER" in roles:
                fail(f"[policy] {path.name} {label}: RESEARCHER must not access application APIs")

            body, body_ptr = resolve_with_pointer(doc, op.get("requestBody", {}), f"{op_ptr}/requestBody")
            for mt, media in body.get("content", {}).items():
                n_examples += check_media(doc, path, registry, media,
                                          f"{body_ptr}/content/{pointer_escape(mt)}", f"{label} request")
            for code, resp in op.get("responses", {}).items():
                resp, resp_ptr = resolve_with_pointer(
                    doc, resp, f"{op_ptr}/responses/{pointer_escape(code)}")
                for mt, media in resp.get("content", {}).items():
                    n_examples += check_media(doc, path, registry, media,
                                              f"{resp_ptr}/content/{pointer_escape(mt)}",
                                              f"{label} {code}")
    print(f"  OK   [openapi examples] {path.name}: {n_examples} example(s) checked")


def main():
    print("=== Contract Example Validation ===")
    openapi_docs = {p: yaml.safe_load(p.read_text()) for p in sorted(OPENAPI_DIR.glob("*.yaml"))}
    registry, feature = build_registry(openapi_docs)
    check_feature_examples(feature, registry)
    for path, doc in openapi_docs.items():
        check_openapi(path, doc, registry)
    print()
    if failures:
        print(f"Example validation FAILED: {len(failures)} problem(s).")
        return 1
    print("Example validation PASSED.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
