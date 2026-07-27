# Deterministic runner contract

## Approval lifecycle

`plan_run` resolves variables, targets, step ordering, sandbox policy, and mutation risk. It returns a candidate and SHA-256 fingerprint. `approve_run` opens a native DBeaver dialog and creates a one-time token. `run_plan` consumes that token and rejects any changed fingerprint.

Tokens expire after five minutes and are removed on first consume attempt. Mutation runs additionally require the MCP `data_write` scope.

## Execution lifecycle

```text
validate
resolve variables
open targets lazily
setup
main steps
cleanup in finally
rollback/commit/close every target
finalize canonical evidence
render reports independently
```

Cleanup runs after assertion failure, database error, timeout, cooperative cancellation, or runner failure. A cleanup failure is stored separately and never replaces the original error.

## Sandbox strategies

- `transaction` — isolated execution context, rollback by default.
- `savepoint` — may degrade to transaction when unsupported.
- `explicit_cleanup` — relies on declared cleanup steps.
- `read_only` — blocks mutation SQL and fixtures.
- `temp_schema` — currently degrades to explicit cleanup.
- `none` — no transactional guarantee.

`commit_on_success` defaults to false. Rollback cannot undo sequence increments, messages, external calls, files, autonomous transactions, or jobs; the native approval dialog states this explicitly.

## Retry and cancellation

Read-only steps and steps explicitly marked `idempotent=true` may retry. Failed mutation steps are not automatically retried. Job cancellation is cooperative at section, step, wait, and fixture-row boundaries. Cleanup ignores the cancellation flag so it can finalize safely.

## Database adapters

The core runner only depends on `DatabaseAdapter` and `StudioBridge` SPIs. DBeaver 26 APIs are isolated in the compatibility bundle. PostgreSQL, MySQL/MariaDB, and SQLite provide dialect quoting and capability declarations.
