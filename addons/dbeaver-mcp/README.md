# DBeaver MCP & AI Database Test Studio

Installable DBeaver add-ons that make DBeaver operable through MCP and add a deterministic database testing product layer.

This directory is the source of truth. It can live standalone or at `addons/dbeaver-mcp` inside a DBeaver fork. A separate clean DBeaver worktree remains the disposable compile/runtime target; add-on sources are overlaid into new paths and the build fails if any tracked upstream file changes.

## Features

- Existing DBeaver MCP server with 61 legacy and compact tools preserved.
- `dbeaver_teststudio`: one compact facade for plans, runs, fixtures, assertions, evidence, reports, UI, AI assistance, and database adapters.
- Canonical versioned `*.dbtest.json` plans stored through Eclipse project resources.
- One-time approval bound to the resolved plan, variables, targets, and step list.
- Setup/steps/cleanup lifecycle with cleanup in `finally` and transaction rollback by default.
- Built-in JSON, data, schema, performance, and operational assertions.
- Evidence retention, attachment checksums, privacy redaction, and aborted-run recovery.
- JSON, JUnit XML, offline HTML, and Markdown reports.
- Native Test Studio view and JSON plan editor without DBeaver product or perspective patches.
- Optional AI and database adapter SPIs; built-in offline heuristic provider plus PostgreSQL, MySQL/MariaDB, and SQLite adapters.

## Repository layout

- `bundles/` — Eclipse/OSGi plug-ins.
- `features/` — independently installable MCP and Test Studio features.
- `repositories/` — P2 update site.
- `releng/` — additive Tycho reactor used inside a disposable DBeaver checkout.
- `scripts/` — overlay, build, validation, tests, P2 lifecycle, compatibility, and release automation.
- `integration-tests/` — unit, PostgreSQL/MySQL Docker, and runtime test assets.
- `config/compatibility-matrix.json` — the only supported-upstream matrix to update for a new DBeaver release.

## Local build

Supported layouts:

```text
# Monorepo/fork layout
dbeaver/
└── addons/dbeaver-mcp/
dbeaver-common/
dbeaver-upstream-mcp-test/

# Standalone layout
dbeaver-mcp/
dbeaver-common/
dbeaver-upstream-mcp-test/
```

The scripts auto-detect both layouts without absolute paths.

Then run:

```bash
make validate
make build
make test
make e2e-databases
make release
```

Override disposable targets when needed:

```bash
DBEAVER_UPSTREAM=/path/to/clean/dbeaver \
DBEAVER_COMMON=/path/to/dbeaver-common \
./scripts/build.sh
```

`overlay.sh`, `build.sh`, and `validate.sh` reject tracked changes in the DBeaver target.

## Install and upgrade

```bash
PRODUCT_DIR=/path/to/dbeaver \
REPOSITORY=/path/to/p2/repository \
./scripts/install.sh
```

Upgrade safely:

```bash
PRODUCT_DIR=/path/to/dbeaver \
REPOSITORY=/path/to/new/repository \
./scripts/upgrade.sh
```

Rollback requires an explicit previous P2 repository:

```bash
PRODUCT_DIR=/path/to/dbeaver \
PREVIOUS_REPOSITORY=/path/to/previous/repository \
./scripts/rollback.sh
```

## Linux desktop packages

Build a self-contained `.deb` and `.AppImage` from a Linux DBeaver product that already has the MCP and Test Studio features installed:

```bash
make package-linux
```

Override the product location when necessary:

```bash
DBEAVER_PRODUCT_DIR=/path/to/dbeaver \
./scripts/package-linux.sh
```

Both packages bundle a reduced Java runtime, enable MCP on localhost by default, validate their desktop metadata, and run a headless P2 smoke test before completion. Outputs and SHA-256 checksums are written to `dist/linux/`.

## Test Studio workflow

```text
validate plan
→ resolve variables and targets
→ preview exact mutation/sandbox risk
→ native DBeaver approval
→ consume one-time fingerprint-bound token
→ setup
→ test steps and assertions
→ cleanup in finally
→ transaction rollback or explicit commit policy
→ evidence finalization
→ report generation
```

Use `dbeaver_teststudio` with `action=discover` for current action contracts.

## Upstream update workflow

1. Change only `config/compatibility-matrix.json`.
2. Run the compatibility workflow for current, previous, and `devel`.
3. If a compile/runtime break occurs, update `org.jkiss.dbeaver.teststudio.compat.dbeaver26` or add a new compatibility bundle.
4. Do not patch DBeaver core, products, perspectives, or existing features.

## Current limitations

- MCP query results are not mirrored into DBeaver's native result grid.
- Query Manager deep links are not implemented.
- `parallel_read` currently reports and uses a bounded sequential fallback.
- Savepoint and temporary-schema strategies may degrade to transaction or explicit cleanup based on bridge capability.
- The built-in AI provider is an offline safe template provider; richer model providers are expected as optional plug-ins.
- Real transaction E2E currently covers PostgreSQL, MySQL, and DBeaver SQLite runtime. SQL Server and Oracle adapters are not included yet.

See `docs/` for the DSL, runner, privacy, compatibility, and release contracts.
