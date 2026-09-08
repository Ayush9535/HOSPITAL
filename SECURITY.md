# Security Policy

The Hospital Management System (HMS) processes **sensitive patient and hospital
data**. We take security seriously and appreciate responsible disclosure.

## Supported Versions

Security fixes are applied to the latest released minor version and the current
`main` branch. Older versions are not patched.

| Version | Supported |
| ------- | --------- |
| Latest `main` / latest release | ✅ |
| Previous minor | ⚠️ critical fixes only |
| Older | ❌ |

## Reporting a Vulnerability

**Do not open a public GitHub issue, PR, or discussion for a security problem.**
Public disclosure before a fix puts patient data at risk.

Report privately using **either**:

1. **GitHub Security Advisories** (preferred) —
   <https://github.com/axonxmedtech/HOSPITAL/security/advisories/new>
2. **Email** — `TODO@axonxmedtech.example` *(replace with your real security contact)*

Please include:

- A description of the vulnerability and its impact.
- Steps to reproduce or a proof of concept.
- Affected version / commit, if known.
- **Redact all real patient data (PHI), credentials, and tokens** from your report.

## What to expect

| Stage | Target |
| ----- | ------ |
| Acknowledgement of your report | within **48 hours** |
| Initial severity assessment | within **5 business days** |
| Fix or mitigation for critical issues | as fast as possible; coordinated disclosure |

*These targets are commitments to responsible reporters; adjust them to match your
team's real capacity.*

## Scope

In scope: this repository's backend, frontend, CI/CD, and deployment configuration.

Out of scope: findings that require a compromised host or stolen credentials,
volumetric denial-of-service, social engineering, and issues in third-party
services (report those to the vendor).

## Handling of patient data

This is a healthcare system subject to regulations such as HIPAA / GDPR / India's
DPDP Act. Never attach or transmit real patient data when demonstrating an issue —
use synthetic data. Reports containing real PHI will be deleted and re-requested.

## Recognition

We credit reporters who follow this policy in the release notes of the fix, unless
you ask to remain anonymous.

## Security engineering (DevSecOps)

How we verify security on every change — SAST, dependency scanning, secret detection, SBOM,
supply-chain integrity, and license compliance — is documented under `docs/security/`:

- [Security Architecture](docs/security/SECURITY_ARCHITECTURE.md) — the pipeline, control map, diagrams, review & incident process.
- [Dependency & Third-Party Package Policy](docs/security/DEPENDENCY_POLICY.md) — vuln gates, Dependabot, licenses.
- [Secret Handling](docs/security/SECRET_HANDLING.md) — secret management, scanning, and leak response.
- [Supply Chain Security](docs/security/SUPPLY_CHAIN.md) — SBOM, Trivy, provenance, and artifact signing.

### Manual GitHub settings

Enable under **Settings → Code security and analysis**: Dependabot alerts, Dependabot security
updates, Dependency graph, Secret scanning, and Secret scanning push protection. These back the
in-repo controls; the pipeline functions without organization-level GitHub Advanced Security.
