#!/usr/bin/env bash
# Parse and classify a Semantic Version string.
# Usage: derive-version.sh <version|vX.Y.Z[-pre][+build]>
# Emits `key=value` lines (suitable for appending to $GITHUB_OUTPUT):
#   version, major, minor, patch, prerelease, channel, is_prerelease, tag
#
# Channels: stable | rc | beta | alpha | hotfix | dev  (see docs/release/RELEASE_ENGINEERING.md)
set -euo pipefail

raw="${1:-}"
if [ -z "$raw" ]; then
  echo "error: version argument required" >&2
  exit 2
fi

# Normalize: drop a leading 'v'.
ver="${raw#v}"

# SemVer 2.0.0 regex (major.minor.patch with optional -prerelease and +build).
semver_re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-([0-9A-Za-z.-]+))?(\+([0-9A-Za-z.-]+))?$'
if [[ ! "$ver" =~ $semver_re ]]; then
  echo "error: '$raw' is not a valid SemVer (expected X.Y.Z[-prerelease][+build])" >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
prerelease="${BASH_REMATCH[5]}"

is_prerelease=false
channel=stable
if [ -n "$prerelease" ]; then
  is_prerelease=true
  case "$prerelease" in
    rc*|*rc.*)     channel=rc ;;
    beta*)         channel=beta ;;
    alpha*)        channel=alpha ;;
    hotfix*)       channel=hotfix; is_prerelease=false ;; # hotfix ships as a stable patch
    snapshot*|SNAPSHOT*|dev*) channel=dev ;;
    *)             channel=beta ;; # unknown pre-release id → treat conservatively as pre-release
  esac
fi

cat <<EOF
version=$ver
major=$major
minor=$minor
patch=$patch
prerelease=$prerelease
channel=$channel
is_prerelease=$is_prerelease
tag=v$ver
EOF
