#!/usr/bin/env bash
# Enterprise deployment health verification — runs ON the VPS after the service restart.
# Goes beyond a single liveness ping: liveness, readiness (Actuator overall status reflects
# DB + Redis + disk health indicators), frontend availability, and host resources.
#
# Usage: verify-deployment.sh <liveness_url> [<actuator_health_url>] [<frontend_url>]
# Env:   HEALTH_JSON (output path, default /tmp/hms-health.json)
#        DISK_WARN_PCT (default 90), MEM_WARN_PCT (default 92)
# Exit:  0 if all CRITICAL checks pass (liveness + readiness, and frontend when a URL is given);
#        1 otherwise. Resource checks are warnings unless the disk is full.
set -uo pipefail

LIVENESS_URL="${1:?liveness url required}"
ACTUATOR_URL="${2:-}"
FRONTEND_URL="${3:-}"
OUT="${HEALTH_JSON:-/tmp/hms-health.json}"
DISK_WARN_PCT="${DISK_WARN_PCT:-90}"
MEM_WARN_PCT="${MEM_WARN_PCT:-92}"

TS() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
crit_fail=0
declare -A R  # results

http_code() { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" 2>/dev/null); echo "${c:-000}"; }

echo "===== Deployment health verification @ $(TS) ====="

# ── CRITICAL: liveness ─────────────────────────────────────────────────────
code=$(http_code "$LIVENESS_URL")
if [ "$code" = "200" ]; then
  R[liveness]="pass"; echo "[PASS] liveness ($LIVENESS_URL) → 200"
else
  R[liveness]="fail"; crit_fail=1; echo "[FAIL] liveness ($LIVENESS_URL) → $code"
fi

# ── CRITICAL: readiness (Actuator overall status covers DB/Redis/disk indicators) ──
if [ -n "$ACTUATOR_URL" ]; then
  body=$(curl -s --max-time 10 "$ACTUATOR_URL" 2>/dev/null || echo '')
  if printf '%s' "$body" | grep -q '"status":"UP"'; then
    R[readiness]="pass"; echo "[PASS] readiness ($ACTUATOR_URL) → UP"
  else
    R[readiness]="fail"; crit_fail=1
    echo "[FAIL] readiness ($ACTUATOR_URL) → ${body:-<no response>}"
  fi
else
  R[readiness]="skipped"; echo "[SKIP] readiness (no actuator url)"
fi

# ── CRITICAL (if URL given): frontend availability / static assets ─────────
if [ -n "$FRONTEND_URL" ]; then
  code=$(http_code "$FRONTEND_URL")
  if [ "$code" = "200" ]; then
    R[frontend]="pass"; echo "[PASS] frontend ($FRONTEND_URL) → 200"
  else
    R[frontend]="fail"; crit_fail=1; echo "[FAIL] frontend ($FRONTEND_URL) → $code"
  fi
else
  R[frontend]="skipped"; echo "[SKIP] frontend (no url)"
fi

# ── Resource checks (warnings; a full disk is critical) ────────────────────
disk_pct=$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')
R[disk_used_pct]="${disk_pct:-unknown}"
if [ -n "${disk_pct:-}" ] && [ "$disk_pct" -ge 98 ]; then
  crit_fail=1; echo "[FAIL] disk ${disk_pct}% used (>=98% — critical)"
elif [ -n "${disk_pct:-}" ] && [ "$disk_pct" -ge "$DISK_WARN_PCT" ]; then
  echo "[WARN] disk ${disk_pct}% used (>=${DISK_WARN_PCT}%)"
else
  echo "[PASS] disk ${disk_pct:-?}% used"
fi

mem_pct=$(free 2>/dev/null | awk '/Mem:/ {printf "%d", ($2>0)?($3*100/$2):0}')
R[mem_used_pct]="${mem_pct:-unknown}"
if [ -n "${mem_pct:-}" ] && [ "$mem_pct" -ge "$MEM_WARN_PCT" ]; then
  echo "[WARN] memory ${mem_pct}% used (>=${MEM_WARN_PCT}%)"
else
  echo "[PASS] memory ${mem_pct:-?}% used"
fi

load=$(awk '{print $1}' /proc/loadavg 2>/dev/null || echo unknown)
cores=$(nproc 2>/dev/null || echo 1)
R[load_1m]="${load}"; R[cpu_cores]="${cores}"
echo "[INFO] load(1m)=${load} cores=${cores}"

# ── Emit JSON ──────────────────────────────────────────────────────────────
overall="pass"; [ "$crit_fail" -eq 0 ] || overall="fail"
{
  printf '{'
  printf '"verifiedAt":"%s",' "$(TS)"
  printf '"overall":"%s",' "$overall"
  printf '"liveness":"%s",' "${R[liveness]}"
  printf '"readiness":"%s",' "${R[readiness]}"
  printf '"frontend":"%s",' "${R[frontend]}"
  printf '"diskUsedPct":"%s",' "${R[disk_used_pct]}"
  printf '"memUsedPct":"%s",' "${R[mem_used_pct]}"
  printf '"load1m":"%s",' "${R[load_1m]}"
  printf '"cpuCores":"%s"' "${R[cpu_cores]}"
  printf '}\n'
} > "$OUT"

echo "===== Verification $overall (JSON: $OUT) ====="
[ "$crit_fail" -eq 0 ]
