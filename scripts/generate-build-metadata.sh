#!/usr/bin/env bash
# Emit a build-metadata JSON document to stdout. Used for both the release `build-metadata.json`
# asset and the frontend `dist/version.json`. Reads GitHub Actions env when present, falling back
# to local git so it also works on a developer machine.
#
# Override version via RELEASE_VERSION; override environment label via BUILD_ENV.
set -euo pipefail

git_sha="${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
git_short="$(printf '%s' "$git_sha" | cut -c1-7)"
git_branch="${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
git_tag="$(git describe --tags --exact-match 2>/dev/null || echo '')"

# Version precedence: explicit RELEASE_VERSION → exact tag (minus v) → package.json → 0.0.0-dev
if [ -n "${RELEASE_VERSION:-}" ]; then
  version="$RELEASE_VERSION"
elif [ -n "$git_tag" ]; then
  version="${git_tag#v}"
elif [ -f frontend/package.json ]; then
  version="$(node -p "require('./frontend/package.json').version" 2>/dev/null || echo '0.0.0-dev')"
else
  version="0.0.0-dev"
fi

cat <<EOF
{
  "application": "hospital-management-system",
  "version": "$version",
  "gitCommit": "$git_sha",
  "gitCommitShort": "$git_short",
  "gitBranch": "$git_branch",
  "gitTag": "${git_tag:-null}",
  "buildNumber": "${GITHUB_RUN_NUMBER:-local}",
  "buildTimestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "ciWorkflow": "${GITHUB_WORKFLOW:-local}",
  "ciRunId": "${GITHUB_RUN_ID:-null}",
  "triggeredBy": "${GITHUB_ACTOR:-$(id -un 2>/dev/null || echo unknown)}",
  "buildEnvironment": "${BUILD_ENV:-ci}"
}
EOF
