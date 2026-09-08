# Deployment & Continuous Delivery

How HMS deploys to the Ubuntu VPS: environments, the promotion flow, approvals, health
verification, rollback, and the operational procedures. Designed for a 24×7×365 hospital system —
**safe, repeatable, auditable, recoverable**, with minimal downtime — while staying on the current
VPS (Nginx + systemd + Spring Boot + MySQL). No containers, no orchestration, no IaC.

> This phase builds on the proven `_deploy.yml` (SSH diagnostics, artifact download, VPS git-reset,
> SCP-with-retries, systemd restart, health-gated auto-rollback) — it is **improved, not replaced**.

---

## Deployment architecture

```
 Developer ──push/merge──► GitHub ──► GitHub Actions (ci.yml)
                                          │
                    build → quality → security → supply-chain → sonar
                                          │  (artifacts: backend-jar-<sha>, frontend-dist-<sha>)
                                          ▼
                         ┌──────────────────────────────────┐
                         │ Deploy job (_deploy.yml)          │
                         │  validate config → SSH diag       │
                         │  → download artifacts → checksums │
                         │  → capture prev state             │
                         │  → SCP package → systemd restart  │
                         │  → health gate (200) ─┬─ fail ─► auto-rollback (prod/staging)
                         │  → extended verify    │           │
                         │  → manifest + history └─ ok ──────┘
                         └───────────────┬──────────────────┘
                                         ▼   SSH (key)
                    Ubuntu VPS: Nginx ─► systemd service ─► Spring Boot ─► MySQL / Redis
```

The GitHub runner never holds long-lived server state; it pushes a verified artifact package over
SSH and drives systemd. Nginx (unchanged) fronts the service and serves the frontend `dist`.

---

## Environment strategy

| | Development | Staging | Production |
|---|---|---|---|
| **Purpose** | local dev | pre-prod verification / promotion gate | live hospital traffic |
| **Trigger** | manual / local | push to `staging` | push to `main` |
| **Approval** | none | none (auto) | **manual** (GitHub Environment reviewers) |
| **Build source** | working tree | `staging` branch artifacts | `main` branch artifacts |
| **Branch** | feature/* | `staging` | `main` |
| **Secrets** | local `.env` | `SSH_PRIVATE_KEY`, `SSH_USERNAME` | same + `PRODUCTION_SLACK_WEBHOOK` |
| **Key variables** | — | `STAGING_SSH_HOST/PORT`, `STAGING_APP_URL`, `STAGING_HEALTH_PORT` | `PRODUCTION_*` equivalents |
| **Auto-rollback** | n/a | enabled | enabled |
| **Deploy permission** | anyone local | merge to `staging` | merge to `main` **+** approve the deploy |

### Promotion flow

```
 feature/* ──PR──► staging ──(deploy + smoke)──► main ──(pre-deploy review + APPROVAL)──► Production
                    │  auto-deploy to Staging         │  manual approval gate
                    └─ verify on staging ─────────────┘  then deploy + verify
```

Promotion is by **branch merge**: merge to `staging` deploys staging and runs smoke tests; when
staging looks good, merge `staging → main`, which builds, shows a pre-deploy review, and waits for
approval before deploying production. The *same tested commit* moves forward.

---

## Deployment pipeline (stages)

`Build → Quality → Security → Artifacts → Deploy Staging → Smoke Tests → Verify → [Approval] →
Production Deploy → Production Verify → Complete`

| Stage | Where | Gate |
|---|---|---|
| Build / Quality / Security / Sonar | `ci.yml` | must pass (deploy `needs` them) |
| Artifacts | build jobs | `backend-jar-<sha>`, `frontend-dist-<sha>` |
| Deploy Staging | `deploy-staging` → `_deploy.yml` | branch `staging`, gates green |
| Smoke Tests | `staging-smoke` | Playwright smoke vs `STAGING_APP_URL` |
| Production Pre-Deploy Review | `production-preflight` | config present; summary + risk |
| **Manual Approval** | Production Environment | reviewer approves |
| Production Deploy + Verify | `deploy-production` → `_deploy.yml` | health gate + extended verify |

---

## Deployment sequence (single environment)

```
Runner                         VPS
  │ validate config             │
  │ SSH TCP diagnostics ───────►│
  │ capture prev HEAD ─────────►│ (rollback target)
  │ download artifacts          │
  │ compute checksums           │
  │ scp deploy.tar.gz ─────────►│ extract
  │ ssh: backup + restart ─────►│ systemd restart
  │ health gate (200) ◄─────────│ curl /api/public/health
  │   └ fail → auto-rollback ──►│ restore prev + restart + re-check
  │ extended verify ───────────►│ verify-deployment.sh (readiness/resources)
  │ manifest + archive ────────►│ deployments/<ts>-<sha>.json
  │ collect logs ◄──────────────│ journalctl
  └ summary + artifact upload
```

---

## Deployment validation & health verification

**Fail-fast validation (before touching the server):** `scripts/deploy/validate-config.sh`
confirms required host/key/path/service/branch are set (names only, never values). Missing config
stops the deploy immediately.

**Health verification** runs in two layers:

1. **Liveness gate (rollback trigger, proven):** up to 15×10s polls of `/api/public/health`; a
   non-200 triggers auto-rollback where enabled.
2. **Extended verification (`scripts/deploy/verify-deployment.sh`, report-only today):**
   - **Readiness** — `/actuator/health` overall `UP` (Spring's aggregate reflects **DB** + **Redis**
     + **disk** health indicators, which are enabled).
   - **Frontend availability** — the app URL returns 200 (static assets served).
   - **Host resources** — disk (critical ≥98%), memory, CPU load.

   Results are captured into the deployment manifest. It's `continue-on-error` for now so it
   augments signal without destabilising the proven flow; **promote it to a hard gate** by removing
   `continue-on-error` on the *Extended health verification* step once validated on staging.

---

## Approval workflow

Production requires a human. GitHub **Environment protection** (reviewers on the `Production`
environment) pauses the `deploy-production` job until approved. Before the gate, the
`production-preflight` job posts a **deployment + risk summary** (version, commit, changes since
the last tag, rollback status, DB-migration caveat) to the run summary — so reviewers decide with
context. Approvals are recorded by GitHub (who approved, when) = the **approval audit trail**.
Approvals are never bypassed in code.

---

## Rollback strategy

```
 health fail (auto)          operator (manual)
        │                          │  Actions ▶ "Rollback Deployment" (env + reason)
        ▼                          ▼  Production → approval gate
 _deploy.yml auto-rollback   deploy-rollback.yml → scripts/deploy/rollback.sh
        │                          │
        └──────────┬───────────────┘
                   ▼
   git reset → previous SHA · restore prev JAR + frontend dist · systemd restart
                   ▼
   re-verify health (15×8s) → rollback report + manifest (audit trail)
```

- **Automatic:** on a failed liveness gate, the deploy restores the previous artifact + SHA and
  re-checks health (enabled for staging and production).
- **Manual:** the **Rollback Deployment** workflow (`workflow_dispatch`) restores the last backup
  on demand; production rollback reuses the environment **approval** gate; every run records a
  report + logs artifact and requires a **reason**.
- **Restores:** application code (SHA), backend JAR, frontend `dist`, and the systemd env drop-in.

### Rollback limitations (important)
- **Database is NOT rolled back.** Schema/data migrations are outside this phase; a rollback that
  crosses a destructive migration can be incompatible. Treat DB changes as forward-only and
  backward-compatible, or coordinate a data restore separately.
- Rollback depends on the previous deployment having created `../rollback_backup` on the VPS. The
  first-ever deploy has nothing to roll back to — promote a known-good release artifact instead.
- Rollback targets the **immediately previous** deployment only (one step back).

---

## Deployment safety controls
- **Locking / no concurrent deploys:** `concurrency: deploy-<env>` (cancel-in-progress: false); the
  rollback workflow shares the same group, so deploy and rollback can't race.
- **Timeouts:** deploy job 30 min; SCP per-attempt 5 min with 3 retries; SSH command timeouts.
- **Accidental-prod protection:** production only on `refs/heads/main` **and** behind approval;
  `production-preflight` blocks if config is missing.
- **Artifact/version integrity:** checksums (SHA-256) recorded per deploy; commit SHA embedded in
  artifacts (Phase 7) and in the manifest.

---

## Deployment manifest (audit record)

Every deploy emits `deployment-manifest.json` (uploaded as a log artifact **and** archived to
`<repo>/../deployments/` on the VPS). Fields: environment, version, gitCommit(+short), branch,
buildNumber, runId/runUrl, deployer, deployedAt, artifact SHA-256s, previousVersion, rollbackTarget,
approval (required/environment), healthChecks (from verify-deployment), and result. This is the
per-deployment source of truth for troubleshooting, compliance, and incident response.

---

## Deployment diagnostics / logs

Each run uploads `deployment-logs/` (service `journalctl`, active commit, extended-verify output,
manifest) for 90 days. On failure the logs indicate **what** ran, **which** step failed, and a
**next action** (e.g. "check `journalctl -u <service> -n 120`"). Sensitive values are never printed.

---

## Procedures

### Standard deploy (staging → production)
1. Merge your PR into `staging`. CI builds, deploys staging, runs smoke tests. Verify staging.
2. Open a `staging → main` PR; merge it.
3. CI builds `main`, posts the **pre-deploy review**, and waits at the approval gate.
4. Review the summary + checksums; **approve** `Deploy — Production`. Watch the health gate pass.

### Manual deploy (re-run)
- Re-run the CI workflow for the target branch from the Actions tab (production still requires
  approval). Deploys are idempotent — same commit re-deploys the same artifacts.

### Emergency deploy (hotfix)
1. Branch from `main`, apply the minimal fix, fast-track review, merge to `main`.
2. Approve the production deploy. If it fails health, auto-rollback restores the previous version;
   otherwise trigger **Rollback Deployment** manually. Forward-port the fix.

### Rollback
- **Fast path:** Actions ▶ **Rollback Deployment** ▶ pick environment + reason ▶ (approve for prod).
- Confirm the rollback report shows `SUCCESS` and health `200`.

---

## Release checklist
- [ ] CI green (build, tests, security, quality, sonar).
- [ ] Staging deployed and **smoke tests passed**; key workflows spot-checked.
- [ ] Version/notes prepared (Phase 7 release, if cutting one).
- [ ] DB changes (if any) are backward-compatible / forward-only.
- [ ] Reviewer available to approve production.

## Operational checklist (per production deploy)
- [ ] Pre-deploy review summary looks correct (version, changes, risk).
- [ ] Approved by an authorized reviewer (audit trail recorded).
- [ ] Health gate + extended verification passed.
- [ ] Deployment manifest archived; logs uploaded.
- [ ] Rollback path confirmed available (previous backup exists).

---

## Manual GitHub configuration required
- **Environments** (*Settings → Environments*): create `Staging` and `Production`; add **required
  reviewers** to `Production` (the approval gate) and optionally a wait timer.
- **Variables** (per environment or repo): `STAGING_SSH_HOST`, `STAGING_SSH_PORT`,
  `STAGING_HEALTH_PORT`, `STAGING_APP_URL`, and the `PRODUCTION_*` equivalents; optional
  `STAGING_DEPLOY_PATH` / `PRODUCTION_DEPLOY_PATH` (default to the current repo paths).
- **Secrets:** `SSH_PRIVATE_KEY`, `SSH_USERNAME`, and (optional) `PRODUCTION_SLACK_WEBHOOK`.
- **Actions permissions:** allow the deploy workflows to run; keep branch protection on `main`.

## Manual VPS configuration required (already in place for current deploys)
- A deploy user with an authorized SSH key and **sudo for `systemctl`** on the service.
- systemd services `hms-staging` / `hms-production`; repo checkouts at the configured `repo_path`.
- Nginx serving the frontend `dist` and proxying the backend; firewall allows the SSH port.
- Writable parent dir for `../rollback_backup` and `../deployments` (deploy user owns them).
- Java runtime present to run the JAR. (No new VPS requirements are introduced by this phase.)

## Related
[docs/ci/CI_ARCHITECTURE.md](../ci/CI_ARCHITECTURE.md) ·
[docs/release/RELEASE_ENGINEERING.md](../release/RELEASE_ENGINEERING.md) ·
[docs/governance/BRANCHING_AND_RELEASE_STRATEGY.md](../governance/BRANCHING_AND_RELEASE_STRATEGY.md)
