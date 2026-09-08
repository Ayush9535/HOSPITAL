# Release Engineering

How HMS turns a commit into a **reproducible, traceable, immutable, versioned, auditable**
release artifact — and how those artifacts are prepared for promotion. This phase is release
*engineering* only: it builds and packages artifacts and prepares GitHub Releases. It does **not**
deploy — deployment keys off branch refs in `ci.yml` and is unchanged.

---

## Versioning strategy — Semantic Versioning 2.0.0

Format: `MAJOR.MINOR.PATCH[-prerelease][+build]`.

| Bump | When |
|---|---|
| **MAJOR** | Breaking API/behaviour change |
| **MINOR** | Backwards-compatible feature |
| **PATCH** | Backwards-compatible fix |

Pre-release channels (ordered least→most stable): `alpha` → `beta` → `rc` → stable.

| Example tag | Channel | GitHub Release | Meaning |
|---|---|---|---|
| `v1.4.0-alpha.1` | alpha | pre-release (draft) | early, unstable preview |
| `v1.4.0-beta.2` | beta | pre-release (draft) | feature-complete, testing |
| `v1.4.0-rc.1` | rc | pre-release (draft) | release candidate |
| `v1.4.0` | stable | release (draft) | production-ready |
| `v1.4.1-hotfix.1` | hotfix | release (draft) | urgent patch (ships as a stable patch) |
| `1.4.0-SNAPSHOT` | dev | n/a | local/dev build, never released |

Classification is computed by [`scripts/derive-version.sh`](../../scripts/derive-version.sh)
(validates SemVer, emits `channel`/`is_prerelease`); the release workflow marks any pre-release
suffix as a GitHub **pre-release**.

### Version lifecycle & source of truth
- **Git tags are the source of truth for releases.** `backend/pom.xml` (`1.0.0`) and
  `frontend/package.json` hold the working **dev baseline**; the release workflow stamps the real
  version from the tag via `mvn versions:set` (ephemeral, not committed) so the built JAR's
  `build.version` matches the tag.
- Flow: develop → tag `vX.Y.Z[-pre]` → release workflow builds + prepares a **draft** Release →
  human reviews & publishes → promotion/deploy (later phase).

---

## Git tags

| Tag pattern | Purpose |
|---|---|
| `vX.Y.Z` | Stable release |
| `vX.Y.Z-rc.N` | Release candidate |
| `vX.Y.Z-beta.N` / `-alpha.N` | Beta / alpha pre-release |
| `vX.Y.Z-hotfix.N` | Hotfix (urgent stable patch) |
| `vX.Y.Z-SNAPSHOT` | Development (not released) |

Pushing a `v*` tag triggers [`release.yml`](../../.github/workflows/release.yml). Every built
artifact can identify its origin: the JAR embeds the commit SHA (`git.properties`) and version
(`build-info.properties`); `build-metadata.json` records tag, branch, SHA, run id, and actor.

```bash
git tag -a v1.4.0 -m "HMS 1.4.0"
git push origin v1.4.0        # → builds artifacts + prepares a DRAFT GitHub Release
```

---

## Build metadata strategy

Every build emits metadata so any running instance is identifiable ("which build is at this
hospital?").

| Surface | Produced by | Exposed at |
|---|---|---|
| Backend JAR | `spring-boot:build-info` → `META-INF/build-info.properties` | `/actuator/info` → `build.*` (already public) |
| Backend JAR | `git-commit-id` plugin → `git.properties` | `/actuator/info` → `git.*` (branch, commit, time) |
| Frontend bundle | `scripts/generate-build-metadata.sh` → `dist/version.json` | `GET /version.json` |
| Release | `build-metadata.json` asset | attached to the GitHub Release |

`build-metadata.json` / `version.json` fields: application, version, gitCommit(+short), gitBranch,
gitTag, buildNumber, buildTimestamp, ciWorkflow, ciRunId, triggeredBy, buildEnvironment.

**Inspect a running deployment:** `curl https://<host>/actuator/info` (backend) or
`curl https://<host>/version.json` (frontend).

---

## Artifact packaging & naming convention

Immutable, unambiguous, version-stamped:

| Artifact | Name | Notes |
|---|---|---|
| Backend | `hms-backend-<version>.jar` | executable Spring Boot JAR; version embedded |
| Frontend | `hms-frontend-<version>.zip` | production `dist/` incl. `version.json` |
| SBOM | `hms-backend-sbom-<version>.cdx.json` | CycloneDX (Phase 6) |
| Metadata | `build-metadata.json` | full build provenance |
| Integrity | `SHA256SUMS`, `SHA512SUMS` | checksums for all assets |

- **Predictable:** always `hms-<component>-<version>.<ext>` — no `latest`, no bare `app.jar`.
- **Immutable:** a published tag/version is never rebuilt to a different content; rebuild ⇒ new
  version. CI artifacts are keyed by commit SHA (`*-<sha>`); release assets by version.

> The in-CI build artifacts (`backend-jar-<sha>`, `frontend-dist-<sha>`) are **unchanged** — the
> deploy pipeline consumes them by those exact names. Release assets are an additional, versioned
> packaging layer on top; nothing existing was renamed.

---

## Checksums & integrity

`scripts/generate-checksums.sh` produces `SHA256SUMS` + `SHA512SUMS` over every release asset.

Verify **before** deploying:
```bash
sha256sum -c SHA256SUMS      # each line must report: OK
sha512sum -c SHA512SUMS
```
Future: Cosign signatures over these artifacts (prepared in Phase 6, `ENABLE_COSIGN`) and SLSA
build provenance land with the deployment phase — see `docs/security/SUPPLY_CHAIN.md`.

---

## Artifact repository strategy

Today artifacts live in **GitHub Artifacts** (CI, SHA-keyed, short retention) and **GitHub
Releases** (versioned assets + checksums + notes, long retention). The naming/metadata scheme is
deliberately portable so an external repository can be adopted later **without changing the build**:

| Target | How it maps | Prereqs (not required today) |
|---|---|---|
| GitHub Artifacts | current CI upload | — |
| GitHub Releases | current release assets | — |
| JFrog Artifactory | push `hms-backend-<ver>.jar` by version | Artifactory URL + creds |
| Sonatype Nexus | Maven `deploy` to a hosted repo | `distributionManagement` + creds |
| Cloud object storage (S3/GCS/Azure) | upload versioned assets + checksums | bucket + credentials |

No external infrastructure is required now; adopting one is an additive publish step.

---

## Artifact lifecycle

```
 commit ──build──► CI artifact (SHA-keyed, 7d)
                        │
   tag vX.Y.Z ──release──► release assets + checksums + notes ──► DRAFT GitHub Release (90d artifact)
                        │                                              │ human publishes
                        │                                              ▼
                        │                                   Published Release (permanent, audit record)
                        │                                              │ promote (later phase)
                        ▼                                              ▼
                  verify checksums ─────────────────────────►  staging ──► production
```

| Stage | Retention / policy |
|---|---|
| CI build artifacts | 7 days (transient; SHA-keyed) |
| Release workflow artifacts | 90 days |
| Published GitHub Release assets | **permanent** — audit/compliance record for healthcare |
| SBOM / build-metadata | with the release, permanent |

- **Expiration/cleanup:** only transient CI artifacts expire; **published release assets are never
  auto-deleted** (regulatory traceability — know exactly what ran in production and when).
- **Promotion:** the *same* verified artifact moves environments; it is not rebuilt per environment.
- **Archiving/version history:** GitHub Releases is the immutable version history; tags are
  permanent.

---

## Build reproducibility

Pinned inputs so a future engineer reproduces the same artifact:

| Input | Value / source |
|---|---|
| Java | 17 (`vars.JAVA_VERSION`, Temurin via `setup-java`) |
| Node | 22 (`vars.NODE_VERSION`, via `setup-node`) |
| Maven | wrapper / runner Maven (Spring Boot parent `3.3.5` manages dep versions) |
| Backend deps | `pom.xml` + Spring Boot BOM (pinned) |
| Frontend deps | `frontend/package-lock.json` (installed with `npm ci`) |
| Build commands | backend `mvn -B package -DskipTests`; frontend `npm ci && vite build` |
| Environment | Ubuntu runner; frontend bakes `VITE_API_BASE_URL` at build time |

Determinism notes: JARs include a build timestamp (`build-info`), so byte-for-byte equality is not
a goal — **content/version reproducibility** is. Same tag + same commit ⇒ same code and versions.

---

## Release workflow summary

`release.yml` — triggered by a `v*` tag (or manual dry run):

1. Derive & validate version → channel (`derive-version.sh`).
2. Stamp backend version (`versions:set`), build JAR (embeds build-info + git.properties).
3. Build frontend, write `dist/version.json`, zip.
4. Collect SBOM, write `build-metadata.json`.
5. Generate `SHA256SUMS` + `SHA512SUMS`.
6. Generate categorised release notes (`.github/release.yml`) + a metadata header.
7. **Create a DRAFT GitHub Release** with all assets (pre-release flagged for `-alpha/-beta/-rc`).
   Manual dispatch = dry run (artifacts only, no Release).

It **never deploys**: the Release is a draft, and no deploy job listens to release/tag events.

---

## Procedures

### Standard release
1. Ensure `main` is green (CI, security, quality all pass).
2. Decide the version (SemVer). Tag: `git tag -a vX.Y.Z -m "HMS X.Y.Z" && git push origin vX.Y.Z`.
3. The workflow prepares a **draft** Release. Review notes + assets; **verify checksums**.
4. Publish the Release. Promotion/deploy follows the deployment phase's process.

### Emergency / hotfix release
1. Branch from the affected release tag, apply the minimal fix, get expedited review.
2. Tag `vX.Y.(Z+1)-hotfix.1` (or the next patch) and push.
3. Same workflow produces verified artifacts; fast-track review of notes + checksums, publish.
4. Forward-port the fix to `main`.

### Rollback relationship
Releases are immutable and versioned, which is what makes rollback safe: to roll back, **re-promote
the previous published version's already-built, checksum-verified artifact** — no rebuild. The
mechanics of switching a running environment back (and automated rollback) belong to the
deployment phase; this phase guarantees a prior known-good artifact always exists to roll back to.

---

## Manual GitHub configuration required
- **Actions permission:** *Settings → Actions → General → Workflow permissions* must allow
  `contents: write` (the release job requests it) so the workflow can create Releases. No PAT
  needed — it uses the built-in `GITHUB_TOKEN`.
- **Tag protection (recommended):** protect `v*` tags so only maintainers can push release tags.
- **Environments/approvals** for publish→deploy gating are configured in the deployment phase.

## Related
[docs/ci/CI_ARCHITECTURE.md](../ci/CI_ARCHITECTURE.md) ·
[docs/security/SUPPLY_CHAIN.md](../security/SUPPLY_CHAIN.md) ·
[docs/governance/BRANCHING_AND_RELEASE_STRATEGY.md](../governance/BRANCHING_AND_RELEASE_STRATEGY.md) ·
[CHANGELOG.md](../../CHANGELOG.md)
