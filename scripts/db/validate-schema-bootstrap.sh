#!/usr/bin/env bash
#
# Fences setup/schema-full.sql against constructs that make it unloadable on MySQL 8, or that
# quietly build the schema somewhere other than where the caller asked.
#
# Both failures this guards against were silent. CREATE INDEX IF NOT EXISTS is accepted by H2 and
# Postgres and rejected by MySQL, so the file aborted partway through with a syntax error nobody
# read. A hardcoded USE <database> was worse: mysql reported success while creating every table in
# a database the caller never named, leaving the intended target empty.
#
# Deliberately POSIX-ish: no associative arrays, no mapfile, no ${x^^}. macOS ships bash 3.2, and a
# validator that silently no-ops on a developer's machine is not a validator.

set -uo pipefail

SCHEMA_FILE="${1:-setup/schema-full.sql}"
FAILED=0

fail() {
    echo "  [FAIL] $1"
    FAILED=1
}

echo "== Validating schema bootstrap: $SCHEMA_FILE =="

if [ ! -f "$SCHEMA_FILE" ]; then
    echo "  [FAIL] $SCHEMA_FILE not found"
    exit 1
fi

# 1. CREATE INDEX ... IF NOT EXISTS — not valid MySQL 8 at any version.
hits=$(grep -niE 'CREATE[[:space:]]+(UNIQUE[[:space:]]+)?INDEX[[:space:]]+IF[[:space:]]+NOT[[:space:]]+EXISTS' "$SCHEMA_FILE" | grep -v '^[0-9]*:--' || true)
if [ -n "$hits" ]; then
    fail "CREATE INDEX ... IF NOT EXISTS is not supported by MySQL 8:"
    echo "$hits" | sed 's/^/         /'
fi

# 2. A bootstrap script must not choose the database for the caller.
hits=$(grep -niE '^[[:space:]]*(USE[[:space:]]+|CREATE[[:space:]]+DATABASE)' "$SCHEMA_FILE" | grep -v '^[0-9]*:--' || true)
if [ -n "$hits" ]; then
    fail "schema-full.sql must not select or create a database; the caller decides:"
    echo "$hits" | sed 's/^/         /'
fi

# 3. Schema-qualified table names re-introduce the same coupling by another route.
hits=$(grep -nE '(CREATE|ALTER|INSERT INTO|REFERENCES)[[:space:]]+TABLE?[[:space:]]*`[A-Za-z0-9_]+`\.`' "$SCHEMA_FILE" | grep -v '^[0-9]*:--' || true)
if [ -n "$hits" ]; then
    fail "schema-qualified names tie this file to one database:"
    echo "$hits" | sed 's/^/         /'
fi

if [ "$FAILED" -eq 0 ]; then
    echo "== Schema bootstrap validation passed =="
    exit 0
fi

echo "== Schema bootstrap validation FAILED =="
exit 1
