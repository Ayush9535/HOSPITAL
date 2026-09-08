<!--
  Thanks for contributing to the Hospital Management System.
  Fill in every section. PRs that skip the checklist may be sent back.
  Keep PRs small and focused — one logical change per PR.
-->

## Summary

<!-- What does this PR do, and why? Link the issue it closes. -->

Closes #

## Type of change

<!-- Tick all that apply. This should match your commit type (feat/fix/…). -->

- [ ] `feat` — new feature
- [ ] `fix` — bug fix
- [ ] `refactor` — no behaviour change
- [ ] `perf` — performance
- [ ] `docs` — documentation only
- [ ] `test` — tests only
- [ ] `chore` / `ci` / `build` — tooling, pipeline, deps
- [ ] `security` — security fix or hardening

## How was this tested?

<!-- Commands run, scenarios exercised, screenshots for UI. -->

- [ ] Backend unit/integration tests pass (`mvn -f backend/pom.xml test`)
- [ ] Frontend builds and tests pass (`npm --prefix frontend run build && npm --prefix frontend test`)
- [ ] Manually verified the affected flow

## Multi-tenant & security checklist (required for backend changes)

<!-- This is a healthcare, multi-tenant system. Tenant isolation is not optional. -->

- [ ] Any new endpoint that reads/writes tenant data is scoped to the caller's `hospitalId`
- [ ] No repository `findById(...)` on a tenant-owned entity without an ownership check
      (the `TenantScopingArchTest` guard passes)
- [ ] Role / module gating (`@PreAuthorize`, `@RequireModule`, `@TenantType`) applied where needed
- [ ] No secrets, credentials, tokens, or real patient data (PHI) in code, tests, logs, or fixtures
- [ ] No new dependency with a known critical/high CVE (dependency-review passes)

## Database changes

- [ ] No schema change **or** a reviewed forward-only migration is included
- [ ] `application-prod.properties` does not enable destructive auto-DDL
- [ ] Backward compatible with existing data, or a data-migration plan is documented

## Reviewer notes

<!-- Anything reviewers should focus on, known trade-offs, follow-ups. -->
