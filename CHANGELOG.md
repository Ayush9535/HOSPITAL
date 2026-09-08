# Changelog

The authoritative, per-version changelog for HMS is **[GitHub Releases](../../releases)** — release
notes are **auto-generated** from merged pull-request labels (categorised by
[`.github/release.yml`](.github/release.yml)) plus build metadata, produced by the release
workflow. See [docs/release/RELEASE_ENGINEERING.md](docs/release/RELEASE_ENGINEERING.md).

This file is intentionally **not** hand-maintained per commit. To find what changed in a version,
open its GitHub Release; to influence how a change is categorised, label its PR
(`type: feature`, `type: bug`, `type: security`, `breaking change`, …).

Versioning follows [Semantic Versioning 2.0.0](https://semver.org).

## Releases

- Browse all versions and their notes at **Releases** (each has assets, checksums, and an SBOM).
- Pre-releases (`-alpha`/`-beta`/`-rc`) are flagged as such; hotfixes ship as stable patch versions.

<!--
  Automation note: release notes are generated at tag time by .github/workflows/release.yml via the
  GitHub "generate-notes" API. Do not maintain a manual entry list here.
-->
