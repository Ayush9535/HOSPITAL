#!/usr/bin/env bash
# Emit a machine-readable deployment manifest (JSON) to stdout — the per-deployment audit record.
# Kept with deployment history on the VPS and uploaded as a workflow artifact.
#
# Inputs via env (all optional; missing → "unknown"/null):
#   ENVIRONMENT APP_VERSION GIT_SHA GIT_BRANCH BUILD_NUMBER RUN_ID RUN_URL DEPLOYER
#   PREV_SHA ROLLBACK_TARGET RESULT BACKEND_SHA256 FRONTEND_SHA256
#   APPROVAL_REQUIRED APPROVAL_ENVIRONMENT HEALTH_JSON (path to verify-deployment.sh output)
set -uo pipefail

short_sha="$(printf '%s' "${GIT_SHA:-}" | cut -c1-7)"

health='{}'
if [ -n "${HEALTH_JSON:-}" ] && [ -f "${HEALTH_JSON}" ]; then
  health="$(cat "${HEALTH_JSON}")"
fi

jstr() { # emit "value" or null
  if [ -z "${1:-}" ]; then printf 'null'; else printf '"%s"' "$1"; fi
}

cat <<EOF
{
  "schemaVersion": "1.0",
  "kind": "hms-deployment-manifest",
  "environment": $(jstr "${ENVIRONMENT:-}"),
  "version": $(jstr "${APP_VERSION:-}"),
  "gitCommit": $(jstr "${GIT_SHA:-}"),
  "gitCommitShort": $(jstr "${short_sha}"),
  "branch": $(jstr "${GIT_BRANCH:-}"),
  "buildNumber": $(jstr "${BUILD_NUMBER:-}"),
  "runId": $(jstr "${RUN_ID:-}"),
  "runUrl": $(jstr "${RUN_URL:-}"),
  "deployer": $(jstr "${DEPLOYER:-}"),
  "deployedAt": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "artifacts": {
    "backendJarSha256": $(jstr "${BACKEND_SHA256:-}"),
    "frontendSha256": $(jstr "${FRONTEND_SHA256:-}")
  },
  "previousVersion": $(jstr "${PREV_SHA:-}"),
  "rollbackTarget": $(jstr "${ROLLBACK_TARGET:-}"),
  "approval": {
    "required": ${APPROVAL_REQUIRED:-false},
    "environment": $(jstr "${APPROVAL_ENVIRONMENT:-}")
  },
  "healthChecks": ${health},
  "result": $(jstr "${RESULT:-unknown}")
}
EOF
