#!/usr/bin/env bash
# Verify a backup file is intact and restorable-looking (fast check — no restore).
# Usage: verify-backup.sh <backup.sql.gz>
# Checks: gzip integrity, non-trivial size, checksum matches sidecar (if present), dump footer,
#         and that it contains CREATE TABLE statements.
set -euo pipefail

F="${1:?backup file required}"
[ -f "$F" ] || { echo "ERROR: not found: $F" >&2; exit 2; }

echo "[verify] $F"
gzip -t "$F" || { echo "ERROR: gzip integrity failed" >&2; exit 3; }

SIZE=$(stat -c '%s' "$F" 2>/dev/null || wc -c < "$F")
[ "${SIZE:-0}" -ge 1024 ] || { echo "ERROR: too small (${SIZE} bytes)" >&2; exit 4; }

if [ -f "$F.meta.json" ]; then
  want=$(grep -o '"sha256":"[^"]*"' "$F.meta.json" | cut -d'"' -f4)
  got=$(sha256sum "$F" | awk '{print $1}')
  if [ -n "$want" ] && [ "$want" != "$got" ]; then
    echo "ERROR: checksum mismatch (sidecar=$want actual=$got)" >&2; exit 5
  fi
  echo "[verify] checksum matches sidecar"
fi

zgrep -qi -m1 'CREATE TABLE' "$F" || { echo "ERROR: no CREATE TABLE found — not a schema dump?" >&2; exit 6; }
gzip -dc "$F" | tail -5 | grep -qi 'Dump completed' || echo "WARNING: missing 'Dump completed' footer"

echo "[verify] OK — ${SIZE} bytes, integrity + content checks passed"
