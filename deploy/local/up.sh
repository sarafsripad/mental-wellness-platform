#!/usr/bin/env bash
# Starts the local environment after checking that every variable listed in
# .env.example has a non-empty value in .env. Usage: ./up.sh
set -euo pipefail
cd "$(dirname "$0")"

[[ -f .env ]] || ./init-env.sh

missing=()
while IFS= read -r line; do
  [[ "$line" =~ ^([A-Z0-9_]+)= ]] || continue
  key="${BASH_REMATCH[1]}"
  value="$(grep -E "^${key}=" .env | head -1 | cut -d= -f2-)"
  [[ -n "$value" ]] || missing+=("$key")
done < .env.example

if (( ${#missing[@]} )); then
  echo "up: .env is missing values for: ${missing[*]}" >&2
  echo "up: delete .env and run ./init-env.sh to regenerate it (local data volumes may need 'docker compose down -v')." >&2
  exit 1
fi

docker compose up -d "$@"
echo "up: started. Run ./verify.sh to check the environment."
