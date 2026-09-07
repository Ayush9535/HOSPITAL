# HMS Documentation Index

Central index of engineering documentation. Start with the **[Production Readiness Review](PRODUCTION_READINESS_REVIEW.md)**
for the whole-system assessment; use the sections below to go deep on any area.

## 🚦 Production readiness
- **[Production Readiness Review](PRODUCTION_READINESS_REVIEW.md)** — final assessment: checklist,
  risk register, scores, architecture diagrams, release recommendation.

## 🏗️ Platform & delivery
| Area | Document | What it covers |
|---|---|---|
| CI | [ci/CI_ARCHITECTURE.md](ci/CI_ARCHITECTURE.md) | Orchestrator, reusable workflows, gates, artifacts |
| Code quality | [quality/CODE_QUALITY.md](quality/CODE_QUALITY.md) | Sonar gate, JaCoCo floor, Checkstyle/PMD/SpotBugs, Spotless |
| Testing | [testing/TESTING_STRATEGY.md](testing/TESTING_STRATEGY.md) | Pyramid, tiers, local/CI runs, seeding, test data |
| Release eng. | [release/RELEASE_ENGINEERING.md](release/RELEASE_ENGINEERING.md) | SemVer, build metadata, artifacts, checksums, GitHub Releases |
| Deployment | [deployment/DEPLOYMENT.md](deployment/DEPLOYMENT.md) | Environments, promotion, approvals, health, rollback, runbooks |

## 🔒 Security
| Document | What it covers |
|---|---|
| [security/SECURITY_ARCHITECTURE.md](security/SECURITY_ARCHITECTURE.md) | Control map, pipeline, review & incident process |
| [security/DEPENDENCY_POLICY.md](security/DEPENDENCY_POLICY.md) | Vuln gates, Dependabot, license policy |
| [security/SECRET_HANDLING.md](security/SECRET_HANDLING.md) | Secret management, scanning, leak response |
| [security/SUPPLY_CHAIN.md](security/SUPPLY_CHAIN.md) | SBOM, Trivy, provenance, artifact signing |

## 🗄️ Database
| Document | What it covers |
|---|---|
| [database/DATABASE_MIGRATIONS.md](database/DATABASE_MIGRATIONS.md) | Flyway strategy, baseline V11, Hibernate safety, safe-migration rules |
| [database/BACKUP_AND_RESTORE.md](database/BACKUP_AND_RESTORE.md) | Backup/retention policy, restore + drills, recovery checklist |

## 🧭 Governance (repository root)
| Document | What it covers |
|---|---|
| [../README.md](../README.md) | Project overview & setup |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | How to contribute, branch/commit conventions |
| [governance/BRANCHING_AND_RELEASE_STRATEGY.md](governance/BRANCHING_AND_RELEASE_STRATEGY.md) | Branches, promotion, release flow |
| [../SECURITY.md](../SECURITY.md) | Vulnerability disclosure + DevSecOps pointers |
| [../SUPPORT.md](../SUPPORT.md) · [../CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md) · [../CHANGELOG.md](../CHANGELOG.md) | Support, conduct, changelog |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Local developer setup & workflow |

## 📐 Product design history
`superpowers/` holds dated product plans (`plans/`) and specifications (`specs/`) for feature work
(nursing, OT, pharmacy, inventory, etc.). These are **product** design records, distinct from the
platform/DevOps docs above, and are retained for historical context.

---
*Reusable workflows live in `.github/workflows/` (`_*.yml`); operational scripts in `scripts/`
(`deploy/`, `db/`). See the Production Readiness Review for how they fit together.*
