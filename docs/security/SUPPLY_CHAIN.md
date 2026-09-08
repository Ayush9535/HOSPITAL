# Software Supply Chain Security

Everything HMS ships is built from third-party code. This document covers how we inventory it
(SBOM), scan it (Trivy), keep it license-clean, and how build integrity / signing will extend
into the deployment phase. All supply-chain controls run **in observe mode today** — they
produce artifacts, SARIF, and summaries but do not block — so they add visibility without
destabilizing the required security gate or deployment.

---

## SBOM (Software Bill of Materials)

**Format:** CycloneDX (the OWASP standard; tool-friendly for vuln correlation).

| Stack | Tool | Output | Trigger |
|---|---|---|---|
| Backend (Maven) | `cyclonedx-maven-plugin` (bound to `package`) | `target/bom.json` + `bom.xml` (CycloneDX 1.5) | **every** `mvn package` |
| Frontend (npm) | `@cyclonedx/cyclonedx-npm` | `sbom-frontend.json` (CycloneDX) | CI supply-chain job |

- **Generation:** the backend plugin means every build — local or CI — emits an SBOM as a side
  effect of packaging. CI additionally aggregates both stacks in the `supply-chain` job.
- **Storage:** uploaded as the `sbom-<sha>` build artifact.
- **Retention:** 90 days (CI artifact). For releases, the SBOM should be attached to the release
  and retained for the support lifetime of that version (wired in the release phase).
- **Usage:** feeds vulnerability correlation (which builds contain an affected component?),
  license inventory, and audit/compliance evidence. Regenerate and diff on dependency changes.

---

## Dependency integrity

- **Lockfiles are the integrity anchor.** `package-lock.json` pins exact versions + hashes; CI
  uses `npm ci` (fails on drift). Maven resolves through the Spring Boot BOM with pinned plugin
  versions.
- **Dependency Review** (PRs) blocks newly-introduced high-severity vulns and denied licenses
  before they enter the tree.
- **Dependabot** keeps the tree current so integrity-relevant patches are small, reviewable bumps
  (see [DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md)).

---

## Trivy — filesystem, dependency & Dockerfile scanning

Job: `trivy-fs` in `_supply-chain.yml`.

- **Scanners:** `vuln` (dependencies + OS packages), `secret`, `misconfig` (includes
  `backend/Dockerfile`).
- **Severity:** CRITICAL, HIGH. `ignore-unfixed: true` to cut noise from vulns with no available
  fix. Documented exceptions go in [`.trivyignore`](../../.trivyignore).
- **Output:** SARIF → **Security → Code scanning** tab (category `trivy-fs`); a human-readable
  table → the job's step summary; both retained as the `trivy-fs-report-<sha>` artifact.
- **Mode:** observe (`exit-code: 0`). See §Promoting to blocking.

### Container image scanning
`backend/Dockerfile` is scanned for **misconfigurations** today. Full **image** scanning
(`trivy image` against the built container) activates in the deployment/release phase, when
images are actually built and published — it belongs next to the build-and-push step, which is
out of scope for Phase 6.

---

## Build provenance & artifact signing (prepared)

Not enabled in Phase 6 (there is no artifact publish step yet), but the repository is prepared so
it switches on cleanly in the release phase:

- **Signing — Cosign (keyless / Sigstore OIDC):** `_supply-chain.yml` contains a Cosign install +
  `sign-blob` step for the SBOMs, **gated off** behind repo variable `ENABLE_COSIGN=true`. Keyless
  signing uses the workflow's OIDC identity — **no long-lived keys to manage** (the job already
  requests `id-token: write`). Signatures/certs upload alongside the SBOM artifact.
- **Build provenance (SLSA):** the release phase should attach provenance to published artifacts
  via `actions/attest-build-provenance` (needs `id-token: write` + `attestations: write`). The
  build jobs already produce content-addressed artifacts (`backend-jar-<sha>`, `frontend-dist-<sha>`)
  that provenance can attest.
- **Verification:** consumers/deploy verify signatures with `cosign verify-blob` /
  `cosign verify-attestation` before trusting an artifact — this hook lives in the deployment
  phase.

### Manual prerequisites to activate signing later
1. Set repo variable **`ENABLE_COSIGN=true`**.
2. Ensure the signing job has **`id-token: write`** (already declared).
3. Keyless needs no secrets. For key-based signing instead, add `COSIGN_PRIVATE_KEY` +
   `COSIGN_PASSWORD` as GitHub Secrets (keyless is preferred — nothing to rotate).

---

## Promoting a control from observe → blocking

Observe mode lets us measure real signal before turning a control into a merge/deploy gate
(the enterprise "monitor first, enforce second" rollout). To promote a supply-chain control:

1. Watch its summaries/SARIF for a few weeks; drive findings to zero or documented exceptions.
2. In `_supply-chain.yml`, remove `continue-on-error: true` from the job and set the tool's
   `exit-code` to `1` (Trivy) so real findings fail it.
3. To make it **gate deployment**, add the `supply-chain` job to the required-fail list in
   `ci.yml`'s `ci-summary` and/or to `deploy-*` `needs`. *(Left out in Phase 6 by design — deploy
   wiring is a later phase.)*

---

## Retention & storage summary

| Artifact | Name | Retention |
|---|---|---|
| SBOMs (both stacks) | `sbom-<sha>` | 90 days |
| Trivy SARIF + table | `trivy-fs-report-<sha>` | 30 days |
| License reports | `license-reports-<sha>` | 90 days |
| Code scanning alerts | GitHub Security tab | until resolved |
