# Developer Guide

How to set up, run, and safely contribute to the Hospital Management System (HMS).
The local developer platform (git hooks, linting, formatting, commit rules, secret
scanning) exists so that **broken code fails on your machine, before it reaches GitHub**.

---

## 1. Required software

| Tool           | Version               | Notes                                             |
| -------------- | --------------------- | ------------------------------------------------- |
| **Node.js**    | ≥ 20 (22 recommended) | Runs the frontend, git hooks, lint/format tooling |
| **Java (JDK)** | 17 (Temurin)          | Backend                                           |
| **Maven**      | ≥ 3.9                 | Backend build                                     |
| **MySQL**      | 8.x                   | Database                                          |
| **Redis**      | optional locally      | Cache (staging/prod)                              |
| **Git**        | ≥ 2.30                | Hooks use `core.hooksPath` (set by Husky)         |

macOS/Linux/Windows are all supported. On Windows, use **Git Bash** (bundled with
Git for Windows) so the hook shell scripts run consistently.

---

## 2. First-time setup

```bash
# 1. Clone and enter the repo
git clone https://github.com/axonxmedtech/HOSPITAL.git
cd HOSPITAL

# 2. Install the developer platform + frontend deps, and activate git hooks
npm run bootstrap          # = npm install (root) + npm --prefix frontend install
                           # the root "prepare" script runs `husky` and installs hooks

# 3. Backend + frontend env files
cp backend/.env.example backend/.env      # fill in DB + JWT
cp frontend/.env.example frontend/.env    # set VITE_API_BASE_URL
```

Verify hooks are installed:

```bash
git config core.hooksPath      # should print: .husky/_   (or .husky)
ls .husky                      # pre-commit, commit-msg, pre-push
```

---

## 3. Running locally

```bash
# Backend  (http://localhost:8080)
cd backend && mvn spring-boot:run

# Frontend (http://localhost:5173)
cd frontend && npm run dev
```

---

## 4. The local quality gates (what runs when)

| Git action   | Hook           | What runs                                                                                        | Target time           |
| ------------ | -------------- | ------------------------------------------------------------------------------------------------ | --------------------- |
| `git commit` | **pre-commit** | secret scan on staged changes + `lint-staged` (ESLint --fix & Prettier on staged frontend files) | **< 15s**             |
| `git commit` | **commit-msg** | commitlint (Conventional Commits)                                                                | ~1s                   |
| `git push`   | **pre-push**   | frontend build (`tsc` + Vite) + unit tests; backend `mvn compile` + unit tests                   | **~2–4 min** (see §7) |

Bypass in a real emergency only: `git commit --no-verify` / `git push --no-verify`.

---

## 5. Developer commands (run from repo root)

```bash
npm run lint            # ESLint on frontend JS/JSX
npm run lint:fix        # ESLint autofix
npm run format          # Prettier write (frontend + root docs)
npm run format:check    # Prettier check (no writes)
npm run format:java     # Spotless (Palantir) format for backend Java  (see §6)
npm run typecheck       # tsc --noEmit (frontend)
npm run test:frontend   # Vitest
npm run test:backend    # mvn test
npm run secret-scan     # scan ALL tracked files for secrets
npm run verify          # lint + typecheck + frontend tests + backend tests (the full local gate)
```

Backend-only:

```bash
mvn -f backend/pom.xml compile     # compile
mvn -f backend/pom.xml test        # unit + integration tests
mvn -f backend/pom.xml verify      # full verify (does NOT run Spotless — see §6)
```

---

## 6. Formatting

- **Frontend** — ESLint (flat config, `eslint.config.js`) + Prettier (`.prettierrc.json`).
  Staged files are auto-fixed on commit. Rules are lenient for gradual adoption on the
  existing codebase, so legacy files are not blocked; they improve as they are touched.
- **Backend** — **Spotless** (Palantir Java Format) is configured in `backend/pom.xml`
  with `ratchetFrom=origin/main`, so it only touches files changed since `main`. It is
  **not** bound to the Maven lifecycle (so `mvn test`/`verify` won't fail on formatting)
  and is **not** auto-run in git hooks yet — run it explicitly:

  ```bash
  npm run format:java          # apply
  npm run format:java:check    # verify only
  ```

  > It is intentionally not wired into hooks/CI yet: until the current integration branch
  > lands on `main`, `ratchetFrom=origin/main` would flag hundreds of already-existing
  > files. Wiring Spotless into pre-commit/CI is a later DevOps phase.

- **EditorConfig** (`.editorconfig`) keeps indentation/whitespace consistent across editors.

---

## 7. Performance notes

- **Pre-commit** stays under ~15s because it only touches **staged** files and never
  invokes Maven or the test suite.
- **Pre-push backend tests** (`mvn test`) currently run ~2–4 min because several
  `@SpringBootTest` context-loading tests (context load, auth boundary, cross-tenant
  isolation) each boot the Spring context. This can exceed the 2–3 min target on a cold
  JVM / Maven cache. For rapid iteration:
  ```bash
  SKIP_BACKEND_TESTS=1 git push     # runs backend compile only; CI still runs full tests
  ```
  Trimming this (test tagging / offloading heavy tests to CI) is a later phase.

---

## 8. Commit message convention (Conventional Commits)

```
<type>(<optional scope>): <summary>
```

Types: `feat fix docs style refactor perf test build ci chore revert security`.
Scopes (optional): `opd ipd pharmacy nurse ot billing auth security platform backend
frontend ci deps docs release`. Enforced by `commitlint` on every commit.

Examples: `feat(pharmacy): add expiry alerts` · `fix(billing): reject overpayment` ·
`security: scope IPD lookups to hospitalId`.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the full workflow.

---

## 9. Secret protection

The pre-commit `secret-scan` blocks commits containing private keys, cloud/API tokens,
JWTs, hardcoded credential assignments, and forbidden files (`.env`, `*.pem`, `*.key`,
`id_rsa`, …). Never commit real secrets — use `.env` (gitignored). Genuine false
positive? Append `pragma: allowlist secret` to that line.

---

## 10. Troubleshooting

| Symptom                                           | Fix                                                                          |
| ------------------------------------------------- | ---------------------------------------------------------------------------- |
| Hooks don't run                                   | `npm run bootstrap` (runs `husky`); check `git config core.hooksPath`        |
| `husky: command not found` on commit              | run `npm install` at repo root                                               |
| Windows: hook "permission denied" / not executing | use **Git Bash**, not CMD/PowerShell, for git operations                     |
| ESLint reports many legacy warnings               | expected — rules are `warn`; only your staged files are auto-fixed           |
| Pre-push too slow                                 | `SKIP_BACKEND_TESTS=1 git push` for iteration; full suite runs in CI         |
| commitlint rejects my message                     | follow `type(scope): summary`; see §8                                        |
| Spotless changes many files                       | you ran it before `main` is current — see §6; use `format:java` deliberately |
