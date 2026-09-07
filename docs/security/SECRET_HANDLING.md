# Secret Handling

Secrets (JWT signing keys, DB passwords, API keys, SSH keys, tokens, certificates) must never
live in the repository or in build logs. This document is the practical guide; the detection
tooling backs it up so a mistake is caught, not shipped.

---

## Golden rules

1. **Secrets never enter git.** Not in code, config, tests, fixtures, or commit messages —
   not even "temporarily."
2. **Config comes from the environment.** Backend reads `.env` / env vars (`JWT_SECRET`,
   `SPRING_DATASOURCE_*`, `SPRING_REDIS_*`); frontend build reads `VITE_*`. CI reads **GitHub
   Secrets**. Only non-sensitive settings belong in **GitHub Variables**.
3. **A leaked secret is rotated, not just deleted.** Git history and any fork/clone retain the
   old value — removal alone does not make it safe.

---

## What we scan, and where

| Control | What it catches | When |
|---|---|---|
| **Gitleaks** (pre-commit) | Secrets about to be committed | local `git commit` |
| **Gitleaks** (CI, `_security.yml`) | Secrets anywhere in history (`fetch-depth: 0`) — **blocking** | every push/PR |
| **Trivy secret scanner** | Additional secret patterns in the working tree | supply-chain job (observe) |
| **GitHub Secret Scanning** | Known provider token formats, push protection | GitHub (manual enable) |

Gitleaks is configured by [`.gitleaks.toml`](../../.gitleaks.toml): the full default ruleset
(cloud keys, tokens, private keys, JWTs, DB URLs) plus a **tight allowlist** of obvious
test/CI placeholders. Every allowlist entry is a documented hole — keep it minimal.

### Kinds of secrets these prevent from leaking
API keys · JWT signing secrets (`JWT_SECRET`) · database passwords · SSH private keys ·
TLS/certificates · OAuth / provider tokens · webhook URLs with embedded tokens.

---

## Where secrets actually live

| Secret | Storage | Consumed by |
|---|---|---|
| `JWT_SECRET` | server `.env` / deploy env | backend runtime |
| `SPRING_DATASOURCE_PASSWORD` | server `.env` / deploy env | backend runtime |
| `SONAR_TOKEN`, `NVD_API_KEY` | GitHub **Secrets** | CI |
| `SSH_PRIVATE_KEY`, `SSH_USERNAME`, Slack webhook | GitHub **Secrets** | deploy (later phase) |
| API base URLs, Sonar org/keys, versions | GitHub **Variables** | CI (non-sensitive) |

CI test runs use **obvious placeholders** (e.g. `JWT_SECRET: ci-test-secret-not-used-in-real-environment`)
— never a real key. These are allowlisted in `.gitleaks.toml`.

---

## Response — a secret was exposed

Treat as a security incident:

1. **Rotate immediately** — invalidate the exposed credential and issue a new one at the source
   (DB user password, JWT secret, token, SSH key). This is the step that actually stops the leak.
2. **Revoke** any sessions/tokens derived from it (e.g. rotating `JWT_SECRET` invalidates issued
   JWTs — intended).
3. **Purge from history** if warranted (`git filter-repo` / BFG) and force-update — coordinate,
   since it rewrites history.
4. **Record** what leaked, blast radius, and remediation per [SECURITY.md](../../SECURITY.md).
5. **Prevent recurrence** — if Gitleaks missed it, tighten the rules; if an allowlist entry hid
   it, remove that entry.

---

## Manual GitHub settings required

**Settings → Code security and analysis:**

- **Secret scanning** — detects known token formats pushed to the repo.
- **Push protection** — blocks commits containing recognized secrets at push time (defense in
  depth alongside the pre-commit hook).

These complement Gitleaks; they are not a substitute for keeping secrets out in the first place.
