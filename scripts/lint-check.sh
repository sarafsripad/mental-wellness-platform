#!/usr/bin/env bash
# lint-check.sh
# Runs available linters/formatters only when their config files are present.
# Always exits 0 — this is an advisory check, not a blocking gate.
# Designed to be fast: skips checks when tooling is not configured.

set -uo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
ISSUES=0

run_if_configured() {
  local label="$1"
  local config_file="$2"
  local cmd="$3"

  if [[ -f "$ROOT/$config_file" ]]; then
    if command -v $(echo "$cmd" | awk '{print $1}') &>/dev/null; then
      echo "  LINT [$label]"
      if ! (cd "$ROOT" && eval "$cmd" 2>&1 | head -50); then
        ISSUES=$((ISSUES + 1))
      fi
    else
      echo "  SKIP [$label] — config present but tool not installed"
    fi
  fi
}

# Python
run_if_configured "ruff"    "pyproject.toml"  "ruff check --quiet workstream-b/ scripts/"
run_if_configured "ruff"    "ruff.toml"       "ruff check --quiet workstream-b/ scripts/"
run_if_configured "black"   "pyproject.toml"  "black --check --quiet workstream-b/ scripts/"
run_if_configured "flake8"  ".flake8"         "flake8 workstream-b/ scripts/"

# JavaScript / TypeScript
run_if_configured "eslint"  ".eslintrc.js"    "npx eslint --quiet workstream-a/mobile/src/"
run_if_configured "eslint"  ".eslintrc.json"  "npx eslint --quiet workstream-a/mobile/src/"
run_if_configured "eslint"  "eslint.config.js" "npx eslint --quiet workstream-a/mobile/src/"

# Java (checkstyle)
run_if_configured "checkstyle" "checkstyle.xml" "mvn checkstyle:check -q --no-transfer-progress"

# JSON / YAML (basic validation)
if command -v python3 &>/dev/null; then
  for f in "$ROOT"/contracts/*.json; do
    [[ -e "$f" ]] || continue
    if ! python3 -c "import json; json.load(open('$f'))" 2>/dev/null; then
      echo "  LINT [json-syntax] $(basename $f) — invalid JSON"
      ISSUES=$((ISSUES + 1))
    fi
  done
fi

# Shell scripts (shellcheck)
if command -v shellcheck &>/dev/null; then
  for f in "$ROOT"/scripts/*.sh; do
    [[ -e "$f" ]] || continue
    shellcheck --severity=warning "$f" 2>&1 | grep -v "^$" | head -20 || true
  done
fi

if [[ $ISSUES -gt 0 ]]; then
  echo ""
  echo "Lint check: $ISSUES issue(s) found (advisory — not blocking this operation)."
else
  echo "  Lint: nothing to report."
fi

# Always exit 0 — lint is advisory
exit 0
