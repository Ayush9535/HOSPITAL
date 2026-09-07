# Testing Strategy

How we test the Hospital Management System, why the suite is shaped the way it is, and how to
run and extend it. The guiding principle: **tests earn their place by protecting a real workflow,
not by moving a coverage number.** A single test that exercises patient registration →
appointment → OPD → billing → dispensing is worth more than a dozen assertions on a string
helper.

---

## The pyramid

```
                 ┌─────────────────────────┐
                 │  E2E (Playwright)        │  slow · few · dispatch/label
                 │  real browser + stack    │
              ┌──┴─────────────────────────┴──┐
              │  API / Integration            │  medium · @SpringBootTest,
              │  Testcontainers (real MySQL)  │  Failsafe *IT, real HTTP
          ┌───┴───────────────────────────────┴───┐
          │  Unit (JUnit5 + Mockito, Vitest)       │  fast · many · every push
          └────────────────────────────────────────┘

          Performance (k6)  — separate axis, dispatch-only
```

| Level | Tech | Runs in | Speed | What it proves |
|---|---|---|---|---|
| Unit | JUnit 5 + Mockito (backend), Vitest (frontend) | every push (`mvn test`, `npm test`) | fast | a single class/function behaves |
| API | `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` on H2 | every push (`mvn test`) | fast | an endpoint's contract, auth, tenant scoping |
| Integration | Testcontainers real MySQL, Failsafe `*IT` | CI `mvn verify` (needs Docker) | medium | persistence & tenant isolation on the real DB engine |
| E2E | Playwright (Chromium) | dispatch / `e2e` PR label | slow | the app works end-to-end in a browser |
| Performance | k6 | dispatch only | slow | response-time / error-rate baselines |

---

## Test tiers & how CI uses them

The suite is split so the **fast tier gates every PR** while the **slow tier is opt-in**:

- **Fast** — `mvn test` (Surefire, `*Test`) + `npm test` (Vitest). No Docker, no browser. Runs in
  the main `ci.yml` build jobs on every push/PR. Unit + API tests live here.
- **Medium** — `mvn verify` (Failsafe, `*IT`). Testcontainers spins up a real MySQL. Runs in CI
  where Docker is available. **Locally these SKIP** (not fail) when Docker is absent, via
  `@Testcontainers(disabledWithoutDocker = true)` — so `mvn verify` is green on a dev laptop.
- **Slow** — Playwright E2E (`.github/workflows/e2e.yml`) and k6 perf
  (`.github/workflows/perf.yml`). Separate workflows, triggered by `workflow_dispatch` or (E2E)
  an `e2e` label on a PR. Never in the hot PR path — they're too slow and need a live environment.

---

## Running tests locally

### Backend

```bash
cd backend
mvn test        # fast tier: unit + API tests on H2 (no Docker)
mvn verify      # + integration *IT on real MySQL (needs Docker; SKIPs without it)
```

Coverage (JaCoCo) is produced by `mvn verify`; the report lands in
`backend/target/site/jacoco/index.html`. A floor is enforced at `verify` (line 20%, branch 10%) —
raise it as coverage of critical paths grows, not to chase a number.

### Frontend

```bash
cd frontend
npm test        # Vitest unit tests
```

### E2E (Playwright)

```bash
npm ci                       # from repo root — installs @playwright/test
npx playwright install chromium

# Smoke (no login, no backend) — just needs a served frontend:
E2E_BASE_URL=http://localhost:5173 npx playwright test --config e2e/playwright.config.js smoke.spec.js

# Full critical workflows — need a running stack + a seeded hospital admin:
E2E_BASE_URL=http://localhost:5173 \
E2E_ADMIN_EMAIL=admin@yourhospital.com \
E2E_ADMIN_PASSWORD='...' \
npx playwright test --config e2e/playwright.config.js
```

### Performance (k6)

```bash
k6 run perf/smoke.js                                   # against localhost:8080
PERF_BASE_URL=https://staging.example.com k6 run perf/smoke.js
```

---

## Business-critical workflows we protect

These are the paths a hospital cannot lose. Every one has coverage at the API and/or E2E level;
new features touching them must keep that coverage green.

1. **Login / logout** — `PatientApiTest` (unauthorized rejection), `smoke.spec.js`,
   `critical-workflows.spec.js`.
2. **Multi-tenant isolation** — `CrossTenantIsolationTest`, `PatientPersistenceIT`
   (`tenantScopedFinderIsolatesAcrossHospitals`), `TenantScopingArchTest` (build-time guard).
3. **Patient registration** — `PatientApiTest`, `critical-workflows.spec.js`.
4. **Appointment booking, OPD consultation, billing, pharmacy dispensing** — navigation covered by
   `critical-workflows.spec.js`; extend with data-level API tests as these paths change.
5. **Permissions / RBAC** — API tests assert 401/403 on unauthenticated and cross-role access.

---

## Writing tests

### Naming & layout
- **Unit** → `SomethingTest.java` (Surefire). **Integration** → `SomethingIT.java` (Failsafe).
  The suffix chooses the tier — an `*IT` that needs a real DB must be `*IT`, not `*Test`.
- Method names describe the scenario and outcome:
  `registerPatient_missingDateOfBirth_returns400`.
- Backend integration tests extend `AbstractMySqlIT` (`com.hms.integration`) to get a real MySQL
  container + wired datasource.

### The seeding pattern (API / integration)
Seed a hospital via the repository, mint a JWT directly, drive over HTTP. From `PatientApiTest`:

```java
String token = jwtUtil.generateToken(
    user.getId(), user.getEmail(), "HOSPITAL_ADMIN",
    hospital.getId(), MODULES, null, "HOSPITAL", null);
// then: TestRestTemplate with Authorization: Bearer <token>
```

This avoids the multi-step platform onboarding flow while still exercising real auth + tenant
scoping.

### E2E specs
- Prefer role/text/label selectors (`getByRole`, `getByLabel`) over brittle CSS. UI tweaks
  shouldn't break a workflow test.
- Credentials and base URL come from env (`E2E_ADMIN_EMAIL`, `E2E_BASE_URL`) — never hard-code.
- Use the helpers in `e2e/tests/helpers.js` (`loginAsAdmin`, `openTab`, `trackConsoleErrors`).

### What NOT to write
- Tests for trivial getters/setters or one-line utilities with no branching.
- Tests that assert on implementation detail (private method calls, exact log strings).
- Snapshot tests of large volatile markup.

---

## Test data

- **Never commit real patient data (PHI) or secrets.** Test fixtures use obviously-fake data
  (`E2E Patient <timestamp>`, `nobody@example.com`).
- API/integration tests build their own hospital + users per test and roll back / use
  `create-drop` — no shared mutable state between tests.
- E2E against a shared environment uses a **dedicated seeded admin**, not a production account,
  and creates uniquely-named records so reruns don't collide.

---

## Reporting

- **Backend**: Surefire/Failsafe reports in `backend/target/*-reports`; JaCoCo HTML +
  `jacoco.xml` (consumed by SonarCloud) under `backend/target/site/jacoco`.
- **E2E**: Playwright HTML + JUnit reporters; the CI job uploads `playwright-report-<sha>`.
- **Performance**: k6 text summary + `k6-summary-<sha>` artifact.

---

## Performance — expansion path

`perf/smoke.js` is a deliberately small baseline (health + login-reject, p95 < 800ms, <1% errors).
To grow it into real load testing:

1. Add authenticated scenarios (login once, reuse the token for OPD/billing reads).
2. Introduce k6 `stages` for ramp-up/steady/ramp-down instead of a flat duration.
3. Add per-endpoint `Trend` metrics and tighten thresholds against observed baselines.
4. Run `perf.yml` against **staging**, never production, and coordinate the window.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `mvn verify` skips all `*IT` | Docker not running. Expected on laptops — start Docker to run them. |
| Testcontainers can't pull `mysql:8.0` | No network / registry auth. Retry with Docker Hub reachable. |
| Playwright: `browserType.launch` fails in CI | Missing `npx playwright install --with-deps chromium`. |
| E2E full specs fail at login | No seeded admin / wrong `E2E_ADMIN_*`. Smoke specs don't need auth. |
| k6 `thresholds` breach | A real regression, or the target is cold/underpowered — check the summary. |
| H2 warns about `ot_workflow_policies` `value` column | Pre-existing harmless DDL warning; context still starts. |
```
