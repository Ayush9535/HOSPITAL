#!/usr/bin/env bash
# Restore an HMS backup — with restore VALIDATION. Defaults to restoring into a scratch database
# so you can *prove a backup is restorable* without touching live data (the recommended drill).
#
# Usage: restore.sh <backup.sql.gz> [target_database]
#   target_database default: <db>_restore_check (a scratch DB; created/overwritten)
# Safety:
#   - Refuses to restore over the LIVE database unless FORCE_RESTORE=yes AND the target matches
#     MYSQL_DATABASE/SPRING_DATASOURCE_URL. Live restore is an emergency action — see runbook.
# Connection: same resolution as backup.sh (MYSQL_* or SPRING_DATASOURCE_*).
set -euo pipefail

SRC="${1:?backup file required}"
[ -f "$SRC" ] || { echo "ERROR: not found: $SRC" >&2; exit 2; }

# Load the app env file safely — never `.` it (unquoted `&` in the JDBC URL backgrounds the
# assignment and leaves it empty). See scripts/db/load-env.sh.
# shellcheck source=scripts/db/load-env.sh
. "$(dirname "$0")/load-env.sh"
hms_load_env || true

if [ -z "${MYSQL_HOST:-}" ] && [ -n "${SPRING_DATASOURCE_URL:-}" ]; then
  tmp="${SPRING_DATASOURCE_URL#jdbc:mysql://}"; hostport="${tmp%%/*}"
  MYSQL_HOST="${hostport%%:*}"; p="${hostport#*:}"; [ "$p" = "$hostport" ] && p=3306
  MYSQL_PORT="${MYSQL_PORT:-$p}"; rest="${tmp#*/}"; LIVE_DB="${rest%%\?*}"
fi
MYSQL_HOST="${MYSQL_HOST:-localhost}"; MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-${SPRING_DATASOURCE_USERNAME:-root}}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${SPRING_DATASOURCE_PASSWORD:-}}"
# No hardcoded default: LIVE_DB is what the "are you about to overwrite production?" guard below
# compares against. Guessing the wrong name there silently disarms that guard.
LIVE_DB="${MYSQL_DATABASE:-${LIVE_DB:-}}"
if [ -z "$LIVE_DB" ]; then
  echo "ERROR: could not determine the live database name — refusing to continue." >&2
  echo "  env file loaded: ${HMS_ENV_FILE:-<none found>}" >&2
  echo "  Without it the overwrite-protection check below cannot be trusted." >&2
  echo "  Set MYSQL_DATABASE explicitly, or point ENV_FILE at a file defining SPRING_DATASOURCE_URL." >&2
  exit 2
fi
TARGET="${2:-${LIVE_DB}_restore_check}"

if [ "$TARGET" = "$LIVE_DB" ] && [ "${FORCE_RESTORE:-no}" != "yes" ]; then
  echo "REFUSING to restore over the live database '$LIVE_DB'." >&2
  echo "This is an emergency action. Re-run with FORCE_RESTORE=yes only after a fresh backup and" >&2
  echo "with sign-off (see docs/database/BACKUP_AND_RESTORE.md §Emergency restore)." >&2
  exit 10
fi

# Verify the backup before trusting it.
DIR="$(cd "$(dirname "$0")" && pwd)"
bash "$DIR/verify-backup.sh" "$SRC"

CNF="$(mktemp)"; chmod 600 "$CNF"
cat > "$CNF" <<EOF
[client]
host=$MYSQL_HOST
port=$MYSQL_PORT
user=$MYSQL_USER
password=$MYSQL_PASSWORD
EOF
trap 'rm -f "$CNF"' EXIT

echo "[restore] $SRC → database '$TARGET' (scratch)"
mysql --defaults-extra-file="$CNF" -e "CREATE DATABASE IF NOT EXISTS \`$TARGET\`;"
zcat "$SRC" | grep -viE '^\s*(CREATE DATABASE|USE )' | mysql --defaults-extra-file="$CNF" "$TARGET"

# ── Restore validation ─────────────────────────────────────────────────────
TABLES=$(mysql --defaults-extra-file="$CNF" -N -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET';")
echo "[restore] restored $TABLES table(s) into '$TARGET'"
if [ "${TABLES:-0}" -lt 1 ]; then
  echo "ERROR: restore validation failed — no tables present." >&2
  exit 6
fi
# Spot-check a core table exists (patients or patient).
CORE=$(mysql --defaults-extra-file="$CNF" -N -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET' AND table_name IN ('patients','patient','users','user');")
[ "${CORE:-0}" -ge 1 ] && echo "[restore] core tables present ✓" || echo "WARNING: no core table found — review the dump."

echo "[restore] VALIDATION PASSED — backup is restorable into '$TARGET'."
[ "$TARGET" != "$LIVE_DB" ] && echo "Drop the scratch DB when done: DROP DATABASE \`$TARGET\`;"
