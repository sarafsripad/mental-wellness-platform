#!/usr/bin/env bash
# check-secrets.sh
# Scans uncommitted changes for common secret patterns.
# Exits 0 with a warning if patterns are found (advisory, non-blocking).
# Exits 1 only for high-confidence patterns (e.g., real AWS access keys).
#
# Designed to be fast and non-destructive. Does not modify any files.

set -uo pipefail

# Determine what to scan: prefer git diff for uncommitted changes
if git rev-parse --is-inside-work-tree &>/dev/null 2>&1; then
  # Scan staged + unstaged changes against HEAD
  SCAN_CONTENT="$(git diff HEAD 2>/dev/null || git diff 2>/dev/null || true)"
  SCAN_SOURCE="git diff"
else
  SCAN_CONTENT=""
  SCAN_SOURCE="none"
fi

if [[ -z "$SCAN_CONTENT" ]]; then
  # Nothing to scan
  exit 0
fi

BLOCKING_FOUND=0
WARNINGS_FOUND=0

check_pattern() {
  local label="$1"
  local pattern="$2"
  local severity="$3"  # BLOCK or WARN

  if echo "$SCAN_CONTENT" | grep -qE "$pattern" 2>/dev/null; then
    if [[ "$severity" == "BLOCK" ]]; then
      echo "  SECRET [BLOCK] $label — found in $SCAN_SOURCE. Remove before committing."
      BLOCKING_FOUND=$((BLOCKING_FOUND + 1))
    else
      echo "  SECRET [WARN]  $label — found in $SCAN_SOURCE. Verify this is not a real secret."
      WARNINGS_FOUND=$((WARNINGS_FOUND + 1))
    fi
  fi
}

# High-confidence patterns (BLOCKING)
check_pattern "AWS Access Key"         'AKIA[0-9A-Z]{16}'                               "BLOCK"
check_pattern "AWS Secret Key"         'aws_secret_access_key\s*=\s*[A-Za-z0-9/+]{40}' "BLOCK"
check_pattern "Google API Key"         'AIza[0-9A-Za-z\-_]{35}'                         "BLOCK"
check_pattern "GitHub Personal Token"  'ghp_[0-9a-zA-Z]{36}'                            "BLOCK"
check_pattern "GitHub OAuth Token"     'gho_[0-9a-zA-Z]{36}'                            "BLOCK"
check_pattern "Slack Bot Token"        'xoxb-[0-9]{11}-[0-9]{11}-[0-9a-zA-Z]{24}'       "BLOCK"
check_pattern "Private Key Header"     '-----BEGIN (RSA|EC|DSA|OPENSSH) PRIVATE KEY-----' "BLOCK"

# Medium-confidence patterns (WARN — may be test values)
check_pattern "Generic password assignment" '(password|passwd|pwd)\s*[=:]\s*["\x27][^"\x27]{8,}["\x27]' "WARN"
check_pattern "Generic secret assignment"   '(secret|api_key|apikey|token)\s*[=:]\s*["\x27][^"\x27]{16,}["\x27]' "WARN"
check_pattern "Database connection string"  '(jdbc|mongodb|postgresql|mysql):\/\/[^:]+:[^@]+@' "WARN"
check_pattern "Bearer token in diff"        'Authorization:\s*Bearer\s+[A-Za-z0-9._~+/-]+=*' "WARN"

# Project-specific checks (WARN)
check_pattern "Direct identifier in feature" '"(name|phone|address|date_of_birth)"\s*:' "WARN"

if [[ $BLOCKING_FOUND -gt 0 ]]; then
  echo ""
  echo "Secret check: $BLOCKING_FOUND BLOCKING pattern(s) found. Remove secrets before proceeding."
  exit 1
elif [[ $WARNINGS_FOUND -gt 0 ]]; then
  echo ""
  echo "Secret check: $WARNINGS_FOUND warning(s). Review the above before committing."
  exit 0
else
  exit 0
fi
