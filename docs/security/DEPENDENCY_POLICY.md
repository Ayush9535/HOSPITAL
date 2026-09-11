# Dependency & Third-Party Package Policy

How HMS adds, updates, audits, and licenses third-party code. The goal: stay current (so urgent
security bumps are small), catch known-vulnerable dependencies before they ship, and avoid
license terms incompatible with a commercial healthcare product.

---

## Adding a new dependency

Before adding a package, confirm:

1. **It's necessary** — prefer the standard library / existing deps over a new transitive tree.
2. **It's healthy** — maintained, reasonable release cadence, no known-abandoned status.
3. **Its license is allowed** (see §Licenses).
4. **It doesn't duplicate** something already present.

Pin versions (Maven manages via the Spring Boot BOM where possible; npm via the lockfile).
Commit the updated lockfile — CI runs `npm ci` and fails on drift.

---

## Vulnerability gates (blocking)

| Ecosystem | Tool | Threshold (build fails at) |
|---|---|---|
| Frontend (npm) | `npm audit --audit-level=high` | **high** |
| Backend (Maven) | OWASP Dependency-Check | **CVSS ≥ 7.0** |
| PR diff (both) | Dependency Review | **high** severity, or a denied license |

Trivy (filesystem) additionally reports CRITICAL/HIGH dependency and OS-package issues in
*observe mode* (non-blocking today — see [SUPPLY_CHAIN.md](SUPPLY_CHAIN.md)).

### When a gate fails
1. **Update** the dependency to a fixed version (often the fastest path).
2. If no fix exists but the vulnerability isn't reachable/exploitable in our usage, add a
   **documented, time-boxed exception** (see below) and open a tracking issue.
3. Never disable the gate globally to unblock a single finding.

### Exceptions
- **Backend (OWASP):** add a suppression to `.github/owasp-suppressions.xml` with a `<notes>`
  justification (CVE, why it's not exploitable here, review date).
- **Trivy:** add the CVE to `.trivyignore` with justification, owner, and `review-by` date.
- Every exception is reviewed on its date; prefer removing it by upgrading.

---

## Automated updates — Dependabot

Configured in `.github/dependabot.yml`:

- **Ecosystems:** frontend npm (`/frontend`), root tooling npm (`/`), backend Maven (`/backend`),
  GitHub Actions (`/`).
- **Cadence:** weekly (Monday), grouped minor/patch updates into one PR per ecosystem to reduce
  noise; **majors come as individual PRs** for deliberate review.
- **Security updates:** GitHub raises Dependabot security PRs automatically once **Dependabot
  Alerts / security updates** are enabled in repo Settings (see §Manual GitHub settings).
- **Guardrail:** Spring Boot **major** version bumps are not auto-proposed (breaking) — they're
  handled intentionally.

Every Dependabot PR runs the full CI + security pipeline; a grouped minor/patch PR that stays
green can be merged routinely.

---

## Licenses

We ship a commercial healthcare product, so **strong copyleft is not acceptable** in distributed
code.

| Category | Examples | Policy |
|---|---|---|
| ✅ Allowed | MIT, Apache-2.0, BSD-2/3, ISC, Unlicense, CC0 | Use freely |
| ⚠️ Review | MPL-2.0, EPL, weak-copyleft, dual-licensed | Legal/tech review before use |
| ❌ Disallowed (distributed code) | GPL-2.0/3.0, AGPL, LGPL, SSPL, BUSL, CC-BY-NC, EUPL | Do not add |

- **Enforcement now:** Dependency Review **denies** `GPL-2.0, GPL-3.0, AGPL-3.0, LGPL-2.1` on PRs
  (blocking). The supply-chain job additionally produces a **full license inventory** (frontend
  `license-checker`, backend `license-maven-plugin` → `THIRD-PARTY.txt`) and flags any
  restricted/unknown license in the run summary (observe).
- **Unknown/missing license metadata** is treated as ⚠️ review — resolve it (find the actual
  license) before merge.

---

## Manual GitHub settings required

Enable in **Settings → Code security and analysis** (not settable from the repo):

- **Dependabot alerts** — surfaces known-vulnerable deps.
- **Dependabot security updates** — auto-PRs the fixes (pairs with `dependabot.yml`).
- **Dependency graph** — required for the above and for Dependency Review.

Organization-level features (GitHub Advanced Security, security overview dashboard) are used if
available but are **not assumed** — the pipeline works on a standard repo without them.
