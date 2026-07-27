# Test Plan DSL 1.0

The canonical format is JSON with suffix `.dbtest.json`. YAML may be added only as an import/export format; JSON remains the persisted source of truth.

## Required root fields

```json
{
  "schema_version": "1.0",
  "id": "user-registration",
  "name": "User registration",
  "targets": {
    "default": {
      "connection": "staging-postgres",
      "project": "General",
      "sandbox": "transaction",
      "auto_connect": true
    }
  },
  "steps": []
}
```

Optional root fields are `variables`, `setup`, `cleanup`, `evidence`, `policy`, timestamps, and extension metadata. Unknown fields are preserved by load/save and remain available to providers.

## Safety limits

- Plan: 1 MiB.
- Setup: 100 steps.
- Main steps: 500.
- Cleanup: 100 steps.
- Fixture: 10 MiB and 10,000 rows.
- Step timeout: 1–3,600 seconds; values above five minutes produce a warning.
- Retry attempts: at most six; non-idempotent steps cannot retry automatically.

Keys resembling passwords, credentials, tokens, authorization values, usernames, or private keys are rejected recursively. Plans store connection references, never credentials.

## Variables

Literal values and generators are supported:

```json
{
  "variables": {
    "email": {"generator": "unique_email"},
    "id": {"generator": "uuid"},
    "created_at": {"generator": "timestamp"},
    "token": {"generator": "random_string", "length": 24, "sensitive": true},
    "answer": {"value": 42}
  }
}
```

Substitution uses `${name}` or nested `${step.field}` references. Sensitive values are masked in evidence.

## Step types

- `query` — read-only SQL only.
- `sql` — read or mutation SQL, governed by sandbox and approval.
- `call_tool` — invokes an existing MCP tool through the policy-preserving registry.
- `insert_fixture` / `import_fixture` — inline, JSON, or CSV rows.
- `wait_until` — bounded polling of a read-only/idempotent condition.
- `assert` — asserts a literal, variable, or earlier step result.
- `snapshot` / `compare_snapshot` — bounded in-run baselines.
- `schema_contract` — delegates to the typed quality workflow.
- `migration_rehearsal` — delegates to the typed migration workflow.
- `group` — sequential nested steps.
- `parallel_read` — read-only nested steps; currently a declared sequential fallback.

Every step may include `id`, `target`, `timeout_seconds`, `attempts`, `retry_delay_ms`, `idempotent`, `continue_on_failure`, `save_as`, and `assertions`.

## Persistence

Plans are stored at:

```text
<Project>/Test Studio/Plans/<id>.dbtest.json
```

Fixtures live under `Test Studio/Fixtures`; runs and reports live under `Test Studio/Runs`. Eclipse resource APIs provide local history and workspace refresh semantics.

## Migration

`migrate_plan` creates a deterministic preview before optional save. A plan is never overwritten unless `overwrite=true` is supplied. Schema 0.9 migration renames `cases` to `steps`, creates `targets.default`, and initializes setup/cleanup.
