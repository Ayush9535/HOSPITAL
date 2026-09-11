#!/usr/bin/env bash
# Operator-triggered rollback — runs ON the VPS. Restores the previous artifact set that the
# last deployment saved to ../rollback_backup, restarts the service, and re-verifies health.
# Writes a plain-text rollback report to stdout (captured by the workflow for the audit trail).
#
# Usage: rollback.sh <repo_path> <service_name> <liveness_url>
# Requires: a prior deployment created ../rollback_backup (prev_backend.jar, prev_frontend_dist,
#           prev_sha.txt). Documented limitation: does NOT roll back the database.
set -uo pipefail

REPO="${1:?repo_path required}"
SERVICE="${2:?service_name required}"
URL="${3:?liveness_url required}"
BACKUP="$(dirname "$REPO")/rollback_backup"
TS() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }

echo "===== ROLLBACK REPORT ====="
echo "started:    $(TS)"
echo "service:    $SERVICE"
echo "repo:       $REPO"
echo "backup dir: $BACKUP"

if [ ! -d "$BACKUP" ]; then
  echo "result:     FAILED — no rollback backup found. Nothing to restore."
  echo "next:       Restore from a known-good release artifact manually (see DEPLOYMENT.md)."
  exit 1
fi

PREV_SHA="$(cat "$BACKUP/prev_sha.txt" 2>/dev/null || echo unknown)"
echo "target sha: $PREV_SHA"

cd "$REPO"
if [ "$PREV_SHA" != "unknown" ]; then
  git reset --hard "$PREV_SHA" 2>&1 | sed 's/^/  git: /' || echo "  git: reset failed (continuing with artifacts)"
fi

echo "restoring previous artifacts..."
mkdir -p backend/target frontend/dist
cp -f "$BACKUP/prev_backend.jar" backend/target/hospital-management-system-1.0.0.jar 2>/dev/null && echo "  restored backend jar" || echo "  WARN: no backend jar in backup"
rm -rf frontend/dist && cp -r "$BACKUP/prev_frontend_dist" frontend/dist 2>/dev/null && echo "  restored frontend dist" || echo "  WARN: no frontend dist in backup"

echo "restarting $SERVICE..."
sudo systemctl restart "$SERVICE"

echo "verifying health..."
HEALTHY=false
for i in $(seq 1 15); do
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL" 2>/dev/null || echo 000)
  echo "  health $i/15 → HTTP $STATUS"
  if [ "$STATUS" = "200" ]; then HEALTHY=true; break; fi
  sleep 8
done

if $HEALTHY; then
  echo "result:     SUCCESS — previous version restored and healthy."
  echo "finished:   $(TS)"
  exit 0
else
  echo "result:     FAILED — service did not become healthy after rollback."
  echo "next:       Inspect 'journalctl -u $SERVICE -n 120'. Manual recovery required."
  echo "finished:   $(TS)"
  exit 1
fi
