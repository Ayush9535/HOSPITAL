# Security Architecture — DevSecOps Pipeline

How the Hospital Management System (HMS) verifies the security of every change before it can
reach production. This is a healthcare system handling PHI, so **every commit passes automated
security verification**, and the controls are layered (defense in depth) rather than relying on
any single gate.

> Scope of this document: the **DevSecOps / supply-chain pipeline** (Phase 6). Runtime security
> (JWT auth, tenant isolation, RBAC, module gating) is documented in `CLAUDE.md` and the code;
> deployment/infra security is a later phase and is intentionally not covered here.

---

## Control map

| Layer | Control | Tool | Where | Blocking? |
|---|---|---|---|---|
| **SAST** | Static analysis (Java + JS/TS) | CodeQL (`security-extended` + `security-and-quality`) | `_security.yml` | ✅ gate |
| **SAST** | Java security bugs | SpotBugs + FindSecBugs | `_quality.yml` | report |
| **SAST** | Deep static + hotspots | SonarCloud | `ci.yml` sonar job | ✅ gate |
| **Dependencies** | Frontend advisories | `npm audit` (≥ high) | `_security.yml` | ✅ gate |
| **Dependencies** | Backend CVEs | OWASP Dependency-Check (CVSS ≥ 7) | `_security.yml` | ✅ gate |
| **Dependencies** | PR diff risk + licenses | Dependency Review | `_security.yml` (PRs) | ✅ gate |
| **Dependencies** | Automated updates | Dependabot | `.github/dependabot.yml` | n/a |
| **Secrets** | Credential detection | Gitleaks (+ `.gitleaks.toml`) | `_security.yml` + pre-commit | ✅ gate |
| **Secrets** | Push/history scanning | GitHub Secret Scanning | GitHub (manual enable) | alert |
| **Supply chain** | SBOM | CycloneDX (Maven + npm) | pom + `_supply-chain.yml` | artifact |
| **Supply chain** | Vuln/secret/misconfig + Dockerfile | Trivy (fs) | `_supply-chain.yml` | observe |
| **Supply chain** | License compliance | license-checker + license-maven-plugin | `_supply-chain.yml` | observe |
| **Supply chain** | Artifact signing (prepared) | Cosign (keyless) | `_supply-chain.yml` (gated off) | off |

"Gate" = fails the required `security` job and therefore blocks merge/deploy. "Observe" =
uploads SARIF/reports and summaries but does not fail the build (yet) — see
[SUPPLY_CHAIN.md](SUPPLY_CHAIN.md) for the promotion path.

---

## Security architecture diagram

```
Developer
   │  git commit
   ▼
┌─────────────────────────────────────────────────────────────┐
│ Local (pre-commit)                                          │
│   • lint-staged / format   • Gitleaks (.gitleaks.toml)      │
└─────────────────────────────────────────────────────────────┘
   │  git push / PR
   ▼
┌─────────────────────────────────────────────────────────────┐
│ GitHub Actions — CI (ci.yml)                                │
│                                                             │
│   validate ─┬─► build-frontend ─┐                           │
│             ├─► build-backend  ─┼─► sonar (SAST gate) ──┐    │
│             ├─► security  ✅GATE ┘                       │    │
│             │     ├ CodeQL  ├ Dependency Review          │    │
│             │     ├ Gitleaks├ npm audit  ├ OWASP         │    │
│             ├─► quality (report-only)                    │    │
│             └─► supply-chain (observe)                   │    │
│                   ├ SBOM (CycloneDX) → artifact          │    │
│                   ├ Trivy fs+Dockerfile → SARIF          │    │
│                   └ License compliance → report          │    │
│                                                          ▼    │
│   ci-summary (aggregates; fails on required-gate failure)    │
└─────────────────────────────────────────────────────────────┘
   │  (branch = staging/main, all gates green)
   ▼
   deploy-staging / deploy-production   ← UNCHANGED by Phase 6
```

SARIF results (CodeQL, Gitleaks, Trivy) flow to the **GitHub Security → Code scanning** tab.
Dependabot and GitHub Secret Scanning post to their own alert surfaces.

---

## Security workflow diagram (a finding's lifecycle)

```
  scan finds issue
        │
        ▼
  ┌───────────────┐   blocking control?   ┌────────────────────┐
  │ CI run        │──────── yes ─────────►│ build fails → merge │
  │               │                       │ blocked; fix or     │
  │               │                       │ documented waiver   │
  │               │──────── no ──────────►│ SARIF → Security tab │
  └───────────────┘   (observe)           │ + step summary       │
        │                                 └────────────────────┘
        ▼
  triage (severity, exploitability, reachability)
        │
   ┌────┴─────┬──────────────┬───────────────┐
   ▼          ▼              ▼               ▼
  fix      update dep     suppress w/       accept risk
 (code)   (Dependabot)   justification     (documented,
                         + review date       time-boxed)
```

---

## Secure coding expectations

- **Never trust tenant boundaries to the client.** Every hospital-scoped query filters by
  `hospitalId` from the JWT (`SecurityHelper.getCurrentHospitalId()`); this is enforced
  server-side and guarded at build time by `TenantScopingArchTest`.
- **No secrets in code or config.** Use env vars / GitHub Secrets. Gitleaks blocks commits that
  contain them. See [SECRET_HANDLING.md](SECRET_HANDLING.md).
- **Validate and encode all input.** Bean Validation on DTOs; parameterized JPA queries (no
  string-concatenated SQL); the frontend escapes by default (React) — avoid `dangerouslySetInnerHTML`.
- **Least privilege in CI.** Workflows declare minimal `permissions:`; jobs elevate only what
  they need (e.g. `security-events: write` for SARIF upload).
- **Fail closed on auth, fail open on observability.** Auth/isolation bugs block; a flaky SBOM
  or license scan must never block a security fix from shipping.

---

## Security review process

1. **Automated** — every PR runs the full pipeline above. Required gates must pass.
2. **Human** — `CODEOWNERS` requires review; reviewers check the CI Summary + Security tab, not
   just the diff. Any new dependency, new external call, or auth/tenant change gets extra scrutiny.
3. **Findings** — new Code Scanning alerts on a PR are triaged before merge (fix, update, or a
   documented, time-boxed waiver). Criticals are not merged unresolved.

---

## Security incident reporting

- **Vulnerability in our code / a dependency:** follow [SECURITY.md](../../SECURITY.md) — private
  report via GitHub Security Advisories, never a public issue. PHI must be redacted.
- **A secret was committed / leaked:** treat as an incident — **rotate the credential first**
  (removal alone is insufficient; git history retains it), then scrub history if needed. See
  [SECRET_HANDLING.md](SECRET_HANDLING.md) §Response.
- **Suspected data exposure (PHI):** escalate immediately to the security contact in SECURITY.md;
  regulatory timelines (HIPAA/GDPR/DPDP) may apply.

---

## Related documents

- [DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md) — third-party packages, vuln gates, licenses, Dependabot.
- [SECRET_HANDLING.md](SECRET_HANDLING.md) — secret management & response.
- [SUPPLY_CHAIN.md](SUPPLY_CHAIN.md) — SBOM, provenance, signing, Trivy, promotion to blocking.
- [SECURITY.md](../../SECURITY.md) — disclosure policy.
- [docs/ci/CI_ARCHITECTURE.md](../ci/CI_ARCHITECTURE.md) · [docs/quality/CODE_QUALITY.md](../quality/CODE_QUALITY.md)
