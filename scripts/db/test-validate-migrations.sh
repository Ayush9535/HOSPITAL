#!/usr/bin/env bash
#
# Tests for validate-migrations.sh. Fixtures only — never reads the real migration directory
# except for the final case, and never writes to it.
#
# This exists because the duplicate-version check was silently unreliable for a long time: it
# used `declare -A`, which macOS bash 3.2 does not support. For plain integer versions bash 3.2
# gave the right answer by accident; for a dotted version (V12.1, which Flyway allows) the
# subscript was evaluated as arithmetic, the check was skipped, and the script reported success
# on a real duplicate. Every case below is therefore run in whatever shell the developer or CI
# actually has, rather than assumed to behave the same everywhere.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VALIDATOR="$HERE/validate-migrations.sh"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/validator-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0

# expect <expected_exit> <name> <dir>
expect() {
    expected="$1"; name="$2"; dir="$3"
    bash "$VALIDATOR" "$dir" 11 > "$WORK/out.txt" 2>&1
    actual=$?
    if [ "$actual" = "$expected" ]; then
        echo "  ok    $name"
        passed=$((passed + 1))
    else
        echo "  FAIL  $name (expected exit $expected, got $actual)"
        sed 's/^/          /' "$WORK/out.txt"
        failed=$((failed + 1))
    fi
}

# Builds a fixture directory from a list of filenames.
fixture() {
    name="$1"; shift
    dir="$WORK/$name"
    mkdir -p "$dir"
    for f in "$@"; do
        printf 'CREATE TABLE t_%s (id bigint);\n' "$RANDOM" > "$dir/$f"
    done
    echo "$dir"
}

echo "== validate-migrations.sh self-test ($(bash --version | head -1)) =="

expect 1 "duplicate integer version is rejected" \
    "$(fixture dup_int V12__alpha.sql V12__bravo.sql)"

# The case that used to pass on bash 3.2.
expect 1 "duplicate dotted version is rejected" \
    "$(fixture dup_dot V12.1__alpha.sql V12.1__bravo.sql)"

expect 1 "duplicate below the baseline is still rejected" \
    "$(fixture dup_old V7__alpha.sql V7__bravo.sql)"

expect 0 "a valid set passes" \
    "$(fixture valid V12__alpha.sql V13__bravo.sql R__repeatable.sql)"

# Version comparison must be textual: as numbers these are unrelated, but a sloppy prefix
# match would call them the same.
expect 0 "V12 and V120 are different versions" \
    "$(fixture v12_v120 V12__alpha.sql V120__bravo.sql)"

expect 0 "dotted versions that differ are not duplicates" \
    "$(fixture dot_distinct V12.1__alpha.sql V12.2__bravo.sql)"

expect 1 "a filename with no version is rejected" \
    "$(fixture no_version V__alpha.sql)"

expect 1 "a non-numeric version is rejected" \
    "$(fixture bad_version Vxx__alpha.sql)"

expect 1 "a file that is not a migration is rejected" \
    "$(fixture not_migration notamigration.sql)"

expect 0 "an empty directory passes" "$(fixture empty_dir)"

# Fail closed: if the ledger cannot be written the check cannot have run, and the script must
# refuse rather than report success.
mkdir -p "$WORK/closed"
printf 'CREATE TABLE t (id bigint);\n' > "$WORK/closed/V12__alpha.sql"
TMPDIR=/nonexistent-dir-for-validator-test \
    bash "$VALIDATOR" "$WORK/closed" 11 > "$WORK/out.txt" 2>&1
if [ $? -ne 0 ]; then
    echo "  ok    fails closed when the version ledger cannot be written"
    passed=$((passed + 1))
else
    echo "  FAIL  reported success when the version ledger could not be written"
    failed=$((failed + 1))
fi

# The real thing must pass.
expect 0 "the repository's own migrations pass" \
    "$REPO_ROOT/backend/src/main/resources/db/migration"

echo "== $passed passed, $failed failed =="
[ "$failed" -eq 0 ] || exit 1
