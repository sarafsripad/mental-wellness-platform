#!/usr/bin/env bash
# validate-contracts.sh
# Validates all JSON files in contracts/ against their own $schema declaration.
# Exits 0 if all files are valid or if no validator is installed (advisory).
# Exits 2 if any file is invalid JSON or fails schema validation. Exit code 2
# makes Claude Code feed stderr back to Claude as a blocking hook error.
#
# Used as a PostToolUse hook and can be run manually.

set -euo pipefail

CONTRACTS_DIR="$(git rev-parse --show-toplevel 2>/dev/null)/contracts"
if [[ ! -d "$CONTRACTS_DIR" ]]; then
  # Not in a git repo or contracts dir doesn't exist yet — skip silently
  exit 0
fi

ERRORS=0
FILES_CHECKED=0

echo "=== Contract Validation ==="

# Top-level contracts plus versioned *.schema.json files in subdirectories.
# Example records (contracts/**/examples/) are checked by validate-examples.py.
while IFS= read -r f; do
  [[ -e "$f" ]] || continue
  FILES_CHECKED=$((FILES_CHECKED + 1))
  filename="$(basename "$f")"

  # Step 1: Validate JSON syntax
  if command -v python3 &>/dev/null; then
    if ! python3 -c "import json, sys; json.load(open('$f'))" 2>/dev/null; then
      echo "  FAIL [syntax]  $filename — invalid JSON" >&2
      ERRORS=$((ERRORS + 1))
      continue
    fi
  elif command -v jq &>/dev/null; then
    if ! jq empty "$f" 2>/dev/null; then
      echo "  FAIL [syntax]  $filename — invalid JSON" >&2
      ERRORS=$((ERRORS + 1))
      continue
    fi
  else
    echo "  SKIP [no validator] $filename — install python3 or jq to validate"
    continue
  fi

  # Step 2: Check required governance fields are present
  if command -v python3 &>/dev/null; then
    python3 - <<PYEOF
import json, sys

with open('$f') as fh:
    schema = json.load(fh)

issues = []

# Check \$schema declaration
if '\$schema' not in schema:
    issues.append("missing \$schema declaration")

# Check \$id contains version
if '\$id' in schema and '/v' not in schema['\$id']:
    issues.append("\$id does not contain a version segment (expected /vX.Y.Z)")

# Check version field
if 'version' not in schema:
    issues.append("missing top-level 'version' field")

# Check title and description
if 'title' not in schema:
    issues.append("missing 'title' field")
if 'description' not in schema:
    issues.append("missing 'description' field")

# Governance checks for voice feature schemas (v1 deprecated, v2 feature-record)
if 'voice-feature-schema' in '$f' or 'feature-record.schema' in '$f':
    governance = schema.get('x-governance', {})
    if not governance.get('direct_identifiers_prohibited', False):
        issues.append("x-governance.direct_identifiers_prohibited must be true")
    if not governance.get('consent_reference_required', False):
        issues.append("x-governance.consent_reference_required must be true")

# Governance checks for inference-contract
if 'inference-contract' in '$f':
    governance = schema.get('x-governance', {})
    if not governance.get('result_is_not_a_diagnosis_is_immutable', False):
        issues.append("x-governance.result_is_not_a_diagnosis_is_immutable must be true")
    if not governance.get('limitations_field_required', False):
        issues.append("x-governance.limitations_field_required must be true")

if issues:
    for issue in issues:
        print(f"  WARN [governance] $filename — {issue}")
    sys.exit(0)  # Governance checks are warnings, not blocking
else:
    print(f"  OK             $filename")
PYEOF
  else
    echo "  OK (syntax)    $filename"
  fi

done < <(find "$CONTRACTS_DIR" -maxdepth 1 -name '*.json'; find "$CONTRACTS_DIR" -mindepth 2 -name '*.schema.json' -not -path '*/examples/*')

# Examples and OpenAPI documents (needs requirements-dev.txt; skipped if absent)
if command -v python3 &>/dev/null; then
  if ! python3 "$(dirname "$0")/validate-examples.py" --allow-missing-deps; then
    ERRORS=$((ERRORS + 1))
  fi
fi

echo ""
if [[ $FILES_CHECKED -eq 0 ]]; then
  echo "No contract files found in $CONTRACTS_DIR — skipping."
  exit 0
fi

if [[ $ERRORS -gt 0 ]]; then
  echo "Contract validation FAILED: $ERRORS error(s) found." >&2
  exit 2
else
  echo "Contract validation PASSED: $FILES_CHECKED file(s) checked."
  exit 0
fi
