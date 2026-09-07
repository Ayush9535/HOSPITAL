#!/usr/bin/env bash
# Validate that required deployment configuration is present BEFORE touching the server.
# Checks each named environment variable is non-empty. NEVER prints values — only names.
#
# Usage: validate-config.sh VAR_NAME_1 VAR_NAME_2 ...
# Exit:  0 = all present; 1 = one or more missing (deployment must stop).
set -uo pipefail

missing=()
for name in "$@"; do
  val="${!name:-}"
  if [ -z "$val" ]; then
    missing+=("$name")
  fi
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "::error::Deployment blocked — missing required configuration: ${missing[*]}"
  echo "Set these as GitHub Environment Variables/Secrets. (Values are never printed by this check.)"
  exit 1
fi

echo "Configuration check passed: all $# required value(s) present."
