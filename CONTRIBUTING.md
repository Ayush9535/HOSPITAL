# Contributing to the Hospital Management System

Thanks for contributing. This is a multi-tenant healthcare platform, so we hold a
high bar for **tenant isolation, data safety, and tested changes**. Please read
this before opening a pull request.

---

## 1. Local setup

**Prerequisites:** Java 17, Maven, Node.js 22, MySQL 8, (optional) Redis.

```bash
# Backend
cd backend
cp .env.example .env         # fill in DB + JWT values
mvn spring-boot:run          # http://localhost:8080

# Frontend
cd frontend
cp .env.example .env         # set VITE_API_BASE_URL
npm install
npm run dev                  # http://localhost:5173
```

Database bootstrap: create the schema, then run `setup/setup-super-admin.sql`.
See the [README](README.md) for details.

---

## 2. Branching strategy

We use **environment branches with short-lived feature branches** (see
[`docs/governance/BRANCHING_AND_RELEASE_STRATEGY.md`](docs/governance/BRANCHING_AND_RELEASE_STRATEGY.md)
for the full model).

| Branch | Purpose | Deploys to |
| ------ | ------- | ---------- |
| `main` | Production-ready, released code | Production |
| `staging` | Pre-production integration | Staging |
| `feat/*`, `fix/*`, `chore/*`, … | Your work | — |
| `hotfix/*` | Urgent production fix | branched from `main` |

**Never commit directly to `main` or `staging`.** Open a PR.

### Branch naming

`<type>/<short-kebab-description>` — e.g. `feat/ipd-bulk-discharge`,
`fix/billing-partial-payment`, `hotfix/jwt-expiry`, `chore/repo-governance`.

---

## 3. Commit messages — Conventional Commits

Every commit message MUST follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <short summary>

<optional body>

<optional footer, e.g. BREAKING CHANGE: ... or Closes #123>
```

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`,
`ci`, `chore`, `revert`, `security`.

**Scopes (examples):** `opd`, `ipd`, `pharmacy`, `nurse`, `ot`, `billing`,
`auth`, `security`, `backend`, `frontend`, `ci`, `deps`.

Examples:

```
feat(pharmacy): add batch-wise expiry alerts
fix(billing): reject overpayment on partial-paid invoice
security: scope IPD lookups to the caller's hospital
```

A breaking change is flagged with `!` after the type/scope **or** a
`BREAKING CHANGE:` footer — this drives the version bump (see §6).

Commit format is validated by **commitlint** (`commitlint.config.js`). To enable
the local pre-commit check:

```bash
npm install                        # installs husky + commitlint (root package.json)
```

If a root `package.json` / husky is not yet set up in your checkout, commit
format is still enforced at PR time once the commitlint CI check is enabled
(a later DevOps phase).

---

## 4. Pull request process

1. Branch from the correct base (`staging` for features, `main` for hotfixes).
2. Keep the PR **small and focused** — one logical change.
3. Fill in the **PR template** completely, including the multi-tenant/security checklist.
4. Ensure CI is green: build, tests, CodeQL, dependency review, secret scan.
5. At least **one approving review** (CODEOWNERS for sensitive paths) is required.
6. Use **Squash and merge** so `main`/`staging` history stays one-commit-per-PR
   and Conventional-Commit-clean.

---

## 5. Testing expectations

- **Backend:** add/keep unit tests; add a cross-tenant test to
  `CrossTenantIsolationTest` for any new tenant-owned endpoint. Run `mvn test`.
- **Frontend:** run `npm run build` (type-check) and `npm test`.
- **Never** put real patient data (PHI) or secrets in tests or fixtures — use
  synthetic data.

The architecture guard (`TenantScopingArchTest`) will fail the build if you add a
repository lookup-by-id without registering it as tenant-reviewed.

---

## 6. Versioning & releases

We follow [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.
Releases are cut from `main` and tagged `vX.Y.Z`. See the
[branching & release strategy](docs/governance/BRANCHING_AND_RELEASE_STRATEGY.md).

---

## 7. Code of Conduct

By participating you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).
Security issues follow the [Security Policy](SECURITY.md), **not** public issues.
