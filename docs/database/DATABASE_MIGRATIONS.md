# Database Migrations, Schema Management & Data Safety

How HMS evolves its MySQL schema safely. For a hospital system the rule is simple: **code can be
redeployed, patient data cannot be recreated.** So schema changes are versioned, validated,
reviewed, and always recoverable. This phase **evolves** the existing strategy (Hibernate +
`DatabaseMigrationRunner`) rather than replacing it abruptly.

Backups & restore live in [BACKUP_AND_RESTORE.md](BACKUP_AND_RESTORE.md).

---

## Where we started (and why it was risky)

- `spring.jpa.hibernate.ddl-auto=update` applied to **all** profiles — so **production auto-altered
  its own schema** on every boot (uncontrolled changes).
- `config/DatabaseMigrationRunner` — idempotent Java `ensureXxx()` fixups (widen columns, add
  tables) that compensate for `update`'s limitations. Load-bearing; **kept**.
- `setup/schema-full.sql` — canonical full schema (a MySQL dump) used to provision fresh DBs.
- A dormant `backend/src/main/resources/db/migration/` with `V6`–`V11` — **committed but never
  executed** (no Flyway was wired up; some files even contain Postgres-only syntax).

---

## What this phase establishes

1. **Flyway** is the forward migration framework (staging/prod), added without disturbing data.
2. **Hibernate is made safe per environment** — no more uncontrolled production schema edits.
3. **Migration validation + safety gates** run in CI before anything reaches a database.
4. **`DatabaseMigrationRunner` stays** as an idempotent safety net during the transition.

### Environment configuration summary

| | Hibernate `ddl-auto` | Flyway | Schema authority |
|---|---|---|---|
| **dev** (default profile) | `update` | disabled | Hibernate (friction-free local dev) |
| **test** | `create-drop` / H2 | disabled | test harness |
| **staging** | `validate` * | enabled, baseline V11 | Flyway migrations |
| **production** | `validate` * | enabled, baseline V11 | Flyway migrations |

\* Override with `HIBERNATE_DDL_AUTO`. `validate` = Hibernate only checks the live schema matches
the entities and **never modifies** it. If validation is too strict against real drift, set
`HIBERNATE_DDL_AUTO=none` (Flyway still owns schema). **Never** set `update`/`create` in prod.
`spring.flyway.clean-disabled=true` everywhere — `flyway:clean` (drops the whole schema) can never
run.

---

## The baseline — start new migrations at V12

Existing staging/prod databases already contain the full schema and live data. Flyway is enabled
with **`baseline-on-migrate=true`, `baseline-version=11`**:

- On first run against a populated DB, Flyway creates `flyway_schema_history`, records a **baseline
  at V11**, and **never executes V1–V11** — no table is recreated, **no data is touched**.
- The dormant `V6`–`V11` files are retained below the baseline as historical record (inert).
- **All new schema changes are `V12__…`, `V13__…` (MySQL syntax).**

### Migration lifecycle

```
author V12__add_x.sql ──► CI: validate-migrations.sh (naming, dupes, destructive-SQL gate)
        │                          │ fail → pipeline stops
        ▼                          ▼ pass
   PR review (+ DB reviewer for schema)      merge → staging
        │                                        │
        │                         staging deploy → Flyway applies V12 → ddl-auto=validate
        │                                        │ verify on staging
        ▼                                        ▼
   promote to main ──► prod pre-deploy BACKUP ──► Flyway applies V12 ──► validate ──► health-gated
                                                                                     (auto-rollback)
```

Flyway itself enforces order, checksums (an edited applied migration fails), and records who/when
in `flyway_schema_history` = the **audit trail**. Applied at app startup; a failed migration fails
startup → the Phase-8 health gate triggers **auto-rollback** (and the pre-deploy backup is the
data safety net).

---

## Database architecture

```
 Spring Boot ─┬─ Flyway (staging/prod) ── flyway_schema_history ─► applies V12+ at startup
              ├─ Hibernate ddl-auto=validate ─► verifies schema == entities (no changes)
              └─ DatabaseMigrationRunner (ApplicationReadyEvent) ─► idempotent safety-net fixups
                         │
                         ▼
                    MySQL  (patients, appointments, ipd, billing, pharmacy, users, audit_logs, …)
```

---

## Migration validation & safety gates (CI, no DB)

`scripts/db/validate-migrations.sh` runs in the **`DB Migration Validation`** CI job and **gates
deployment**:

- **Naming** — `V<n>__desc.sql` / `R__desc.sql`.
- **Duplicate versions** — rejected.
- **Empty migrations** — rejected.
- **Destructive/dangerous SQL above the baseline** — `DROP TABLE/COLUMN`, `DROP DATABASE/SCHEMA`,
  `TRUNCATE`, `RENAME TABLE`, `ALTER … DROP`, column type `MODIFY/CHANGE` — **fail** unless the file
  carries an explicit review ack:
  ```sql
  -- flyway:safety-ack: <reason and review reference>
  ```

Flyway's own **checksum/order/failed-migration** validation happens at startup
(`validate-on-migrate=true`, `out-of-order=false`).

---

## Safe migration guidelines (backward compatible)

Hospitals may run older versions, so **prefer additive, reversible steps** and stage destructive
changes across releases:

| Want to… | Do (safe) | Avoid (risky) |
|---|---|---|
| Add data | new table / **nullable** column + backfill | `NOT NULL` column with no default on a big table |
| Rename a column | add new → backfill → dual-write → drop old **next release** | rename in place (breaks running code) |
| Change a type | add new column → migrate → switch → drop old later | `MODIFY` in place on populated tables |
| Remove a column/table | stop using it → drop a **later** release (acked) | drop in the same release it's removed from code |

Every migration is **one logical change**, reviewed like code; schema-touching PRs get an extra DB
review.

## Data integrity notes (STEP 8)
Keep primary keys, foreign keys, `hospital_id` indexes (multi-tenant filtering — see `V11`), unique
constraints, and sensible nullability. Add indexes/constraints via additive migrations; validate FK
integrity before adding a constraint to existing data. Don't loosen constraints without justification.

## Rollback considerations
- **App/artifact rollback** is automated (Phase 8). **Schema is not auto-rolled-back** — forward-fix
  with a new migration, or restore from the pre-deploy backup (emergency, see runbook).
- This is exactly why migrations must be **backward compatible**: the previous app version must run
  against the new schema during a rollback window.

## Provisioning a brand-new environment
1. Create the database, load `setup/schema-full.sql` (+ `setup/setup-super-admin.sql`).
2. Enable the profile (`staging`/`prod`) — Flyway baselines the now-populated DB at V11.
3. Deploy as usual; future changes apply as V12+.
(Dev needs none of this — `ddl-auto=update` builds the local schema, Flyway is off.)

## Manual operational steps required
- Set env per environment: `HIBERNATE_DDL_AUTO` only if you need the `none` escape hatch.
- First staging/prod boot after this phase: confirm `flyway_schema_history` shows a **baseline at
  V11** and the app is healthy (validate passed). If `validate` errors on legacy drift, set
  `HIBERNATE_DDL_AUTO=none` and open a ticket to reconcile.
- Author new schema changes as `V12__…` (never edit V6–V11).

## Remaining technical debt
- `DatabaseMigrationRunner` fixups should be gradually reframed as explicit V12+ migrations, then the
  runner retired once staging/prod are confirmed converged.
- `setup/schema-full.sql` is a raw dump (references a legacy DB name) — regenerate a clean canonical
  schema when convenient.
- Consider a CI job that boots the app against an ephemeral MySQL (Testcontainers) to actually run
  Flyway on a fresh baseline — currently validation is static + at-deploy.
