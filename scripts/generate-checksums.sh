#!/usr/bin/env bash
# Generate SHA-256 and SHA-512 checksum manifests for every file in the current directory.
# Run from inside the directory that holds the release assets. Produces SHA256SUMS + SHA512SUMS.
#
# Verify later with:  sha256sum -c SHA256SUMS   (and sha512sum -c SHA512SUMS)
set -euo pipefail

: > SHA256SUMS
: > SHA512SUMS

shopt -s nullglob
for f in *; do
  [ -f "$f" ] || continue
  case "$f" in
    SHA256SUMS | SHA512SUMS) continue ;;
  esac
  sha256sum "$f" >> SHA256SUMS
  sha512sum "$f" >> SHA512SUMS
done

echo "Checksums written for:"
cut -c1-16,131- SHA256SUMS 2>/dev/null || cat SHA256SUMS
