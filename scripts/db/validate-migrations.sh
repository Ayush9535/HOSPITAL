#!/usr/bin/env bash
# Static validation + safety gate for Flyway migrations. Runs in CI (no database needed) and
# stops a deployment before risky schema changes reach an environment.
#
# Checks:
#   - Flyway naming convention (V<n>__desc.sql / R__desc.sql)
#   - Duplicate version numbers
#   - Empty migration files
#   - Destructive / dangerous SQL — flagged unless the file carries an explicit ack line:
#       -- flyway:safety-ack: <reason>
#
# Usage: validate-migrations.sh [migrations_dir] [baseline_version]
#   migrations_dir   default: backend/src/main/resources/db/migration
#   baseline_version default: 11 (V6-V11 are inert pre-Flyway history; only V12+ are gated)
# Exit:  0 = ok; 1 = validation failed (naming/dupes/unacked destructive SQL above baseline).
set -uo pipefail

DIR="${1:-backend/src/main/resources/db/migration}"
BASELINE="${2:-11}"
fail=0

if [ ! -d "$DIR" ]; then
  echo "No migration directory at '$DIR' — nothing to validate."
  exit 0
fi

echo "== Validating Flyway migrations in $DIR =="

# Dangerous patterns (case-insensitive). Type changes via MODIFY/CHANGE are risky on populated tables.
DANGER='DROP[[:space:]]+TABLE|DROP[[:space:]]+DATABASE|DROP[[:space:]]+SCHEMA|DROP[[:space:]]+COLUMN|TRUNCATE|ALTER[[:space:]]+TABLE[[:space:]].*[[:space:]]DROP[[:space:]]|RENAME[[:space:]]+TABLE|[[:space:]]MODIFY[[:space:]]|[[:space:]]CHANGE[[:space:]]'

# Version ledger for duplicate detection, as a plain file rather than an associative array.
#
# `declare -A` is bash 4. macOS ships bash 3.2, where it errors and — because this script does
# not use `set -e` — execution continued into a check that could no longer work. For plain
# integer versions bash 3.2 happened to give the right answer anyway, treating seen_versions as
# an ordinary indexed array. For a dotted version (V12.1, which Flyway supports) the subscript
# was evaluated as arithmetic, raised "invalid arithmetic operator", and the duplicate check was
# skipped for that file — leaving the script to report success on a genuine duplicate. A
# validator that passes when it could not run is worse than no validator.
VERSION_LEDGER="$(mktemp "${TMPDIR:-/tmp}/flyway-versions.XXXXXX")"
trap 'rm -f "$VERSION_LEDGER"' EXIT
versioned_count=0

shopt -s nullglob
files=("$DIR"/*.sql)

if [ "${#files[@]}" -eq 0 ]; then
  echo "No .sql migrations found (baseline-only). OK."
  exit 0
fi

for f in "${files[@]}"; do
  base="$(basename "$f")"

  # README and non-migration files are skipped by extension already (*.sql only).
  # Naming convention.
  if [[ "$base" =~ ^V([0-9]+(\.[0-9]+)*)__.+\.sql$ ]]; then
    ver="${BASH_REMATCH[1]}"
    # Recorded as an exact string, never a number: V12 and V120 are different versions, and so
    # are V12 and V12.0. Compared after the loop by sort/uniq.
    printf '%s\t%s\n' "$ver" "$base" >> "$VERSION_LEDGER"
    versioned_count=$((versioned_count + 1))
  elif [[ "$base" =~ ^R__.+\.sql$ ]]; then
    : # repeatable migration — allowed
  else
    echo "  [FAIL] bad name '$base' — expected V<n>__desc.sql or R__desc.sql"
    fail=1
    continue
  fi

  # Non-empty (ignoring comments/blank).
  if ! grep -qvE '^[[:space:]]*(--.*)?$' "$f"; then
    echo "  [FAIL] $base contains no SQL statements"
    fail=1
  fi

  # Destructive SQL → require an explicit ack line. Only gate migrations ABOVE the baseline;
  # V1..baseline are inert pre-Flyway history that Flyway never executes.
  is_new=1
  if [[ "$base" =~ ^V([0-9]+) ]] && [ "${BASH_REMATCH[1]}" -le "$BASELINE" ]; then
    is_new=0
  fi
  if grep -qiE "$DANGER" "$f"; then
    if [ "$is_new" -eq 0 ]; then
      echo "  [INFO] $base has destructive SQL but is at/below baseline V$BASELINE (never executed) — skipped"
    elif grep -qiE '^[[:space:]]*--[[:space:]]*flyway:safety-ack:' "$f"; then
      echo "  [WARN] $base has destructive SQL — ACKED (explicit review recorded in file)"
    else
      echo "  [FAIL] $base has destructive/dangerous SQL without an ack."
      echo "         Review carefully; if intended, add: -- flyway:safety-ack: <reason>"
      grep -inE "$DANGER" "$f" | sed 's/^/           /'
      fail=1
    fi
  fi
done

# Duplicate versions. Portable: cut the version column, sort, and ask uniq for repeats.
ledger_lines=$(wc -l < "$VERSION_LEDGER" | tr -d '[:space:]')
if [ "$ledger_lines" != "$versioned_count" ]; then
  # Fail closed. If the ledger and the loop disagree, the check did not see every migration and
  # its silence means nothing.
  echo "  [FAIL] duplicate-version check did not run over every migration"
  echo "         (recorded $ledger_lines of $versioned_count versioned files)"
  fail=1
else
  dupes=$(cut -f1 "$VERSION_LEDGER" | sort | uniq -d)
  if [ -n "$dupes" ]; then
    # One block per duplicated version, naming every file that claims it.
    echo "$dupes" | while IFS= read -r dupe_ver; do
      [ -n "$dupe_ver" ] || continue
      echo "  [FAIL] duplicate version V$dupe_ver claimed by:"
      awk -F'\t' -v v="$dupe_ver" '$1 == v { print "           " $2 }' "$VERSION_LEDGER" | sort
    done
    fail=1
  fi
fi

echo "Validated ${#files[@]} migration file(s)."
if [ "$fail" -ne 0 ]; then
  echo "== Migration validation FAILED =="
  exit 1
fi
echo "== Migration validation passed =="
