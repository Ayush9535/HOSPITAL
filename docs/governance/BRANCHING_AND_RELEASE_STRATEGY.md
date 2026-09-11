# Branching & Release Strategy

This document defines how we branch, review, version, tag, and release the
Hospital Management System. It reflects and formalises the model the repository
already uses (`main` + `staging` + PRs), rather than imposing heavier GitFlow.

---

## 1. Branching model — environment branches + short-lived feature branches

```
                    hotfix/*  ─┐ (urgent prod fix, branched from main)
                               ▼
feat/* fix/* chore/*  ──►  staging  ──►  main
   (short-lived)         (pre-prod)    (production)
                          deploys to    deploys to
                           STAGING      PRODUCTION
```

| Branch | Role | Protected | Deploys to |
| ------ | ---- | --------- | ---------- |
| `main` | Always releasable production code | ✅ | Production |
| `staging` | Integration / pre-production | ✅ | Staging |
| `feat/*`, `fix/*`, `chore/*`, `docs/*`, `refactor/*`, `perf/*`, `test/*` | Day-to-day work | — | — |
| `hotfix/*` | Urgent production fix | — | via `main` |
| `release/*` | *Optional* release stabilisation (only if a release needs hardening while `staging` moves on) | — | — |

**Rules**
- No direct commits to `main` or `staging` — always via PR.
- Feature branches are **short-lived** (aim < 1 week) and branch from `staging`.
- `main` only ever receives merges from `staging` (normal flow) or `hotfix/*`.

---

## 2. Normal flow (feature → production)

1. Branch `feat/<desc>` from `staging`.
2. Open a PR into `staging`. CI + review + CODEOWNERS gate it.
3. Squash-merge into `staging` → auto-deploys to **Staging**.
4. Validate on Staging (smoke test).
5. Open a PR `staging → main` (a "release" PR).
6. Merge → auto-deploys to **Production** → tag the release (§4).

---

## 3. Hotfix flow (urgent production fix)

1. Branch `hotfix/<desc>` from **`main`** (not staging).
2. Fix + test. Open a PR into `main`.
3. Merge → deploys to Production → tag a **PATCH** release.
4. **Back-merge** `main` → `staging` immediately so staging isn't behind prod.

---

## 4. Versioning & tags — Semantic Versioning

We follow [SemVer](https://semver.org/): **`MAJOR.MINOR.PATCH`**.

| Bump | When | Driven by commit |
| ---- | ---- | ---------------- |
| **MAJOR** (`2.0.0`) | Backwards-incompatible API/DB/behaviour change | `feat!:` or `BREAKING CHANGE:` footer |
| **MINOR** (`1.3.0`) | New backwards-compatible feature | `feat:` |
| **PATCH** (`1.2.4`) | Backwards-compatible bug/security fix | `fix:`, `security:`, `perf:` |

**Tags**
- Every production release is tagged `vMAJOR.MINOR.PATCH` (annotated tag) on `main`.
- Tags are immutable — never move or delete a released tag.
- Suggested first tag once this baseline lands: `v1.0.0`.

```bash
git checkout main && git pull
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0
```

---

## 5. Releases & release notes

- Create a **GitHub Release** from each `vX.Y.Z` tag.
- Use **“Generate release notes”** — categories come from
  [`.github/release.yml`](../../.github/release.yml), grouped by PR label
  (Features / Bug Fixes / Security / Breaking / Dependencies / …).
- Because we squash-merge with Conventional-Commit titles, notes are accurate
  and require little hand-editing.
- Keep a human summary at the top for anything operationally important
  (migrations, config changes, env vars).

---

## 6. Milestones

- Create a milestone per planned **MINOR** release (e.g. `v1.3.0`) or per sprint.
- Attach issues/PRs to milestones to track scope and completion.
- A milestone is "done" when all its issues are closed and the tag is cut.

---

## 7. Pull request workflow

- **Template:** every PR uses `.github/PULL_REQUEST_TEMPLATE.md` (incl. the
  multi-tenant/security checklist).
- **Reviews:** ≥ 1 approval; CODEOWNERS review required for `security/`, `config/`,
  DB, and `.github/` paths.
- **Merge method:** **Squash and merge** (one clean Conventional-Commit per PR).
- **CI:** build + tests + CodeQL + dependency-review + secret-scan must pass.

---

## 8. Branch protection — recommended settings (apply in GitHub UI)

> These are **GitHub repository settings**, not files. Apply under
> Settings → Branches → Branch protection rules for both `main` and `staging`.

For **`main`** (strictest) and **`staging`**:

- ✅ Require a pull request before merging
  - ✅ Require **1+ approvals** (2 for `main`)
  - ✅ Require review from **Code Owners**
  - ✅ Dismiss stale approvals on new commits
- ✅ Require **status checks to pass** before merging, and require branches to be up to date:
  - `build-frontend`, `build-backend`, `security` (CodeQL, Gitleaks, dependency-review), and the SonarCloud check
- ✅ Require **conversation resolution** before merging
- ✅ Require **linear history** (pairs with squash-merge)
- ✅ Require **signed commits** *(recommended for a healthcare system)*
- ✅ **Do not allow bypassing** the above (including admins) on `main`
- ✅ Restrict who can push to `main` / `staging`
- ✅ Do not allow force pushes or deletions

**Also enable (repo/org settings):**
- Secret scanning + push protection
- Dependabot alerts + security updates
- Require the **Production environment** approval gate (Settings → Environments →
  Production → Required reviewers) so production deploys need a human click.

---

## 9. Quick reference

| I want to… | Branch from | PR into | Result |
| ---------- | ----------- | ------- | ------ |
| Build a feature | `staging` | `staging` | Deploys to Staging |
| Ship to prod | `staging` | `main` | Deploys to Production + tag |
| Fix prod urgently | `main` | `main` | PATCH release + back-merge to staging |
| Update docs/tooling | `staging` | `staging` | — |
