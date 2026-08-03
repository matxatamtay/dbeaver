# Changelog

## 2.0.2 — Upstream-synchronized custom build

- Re-synchronized the fork and build dependencies with the latest upstream revisions.
- Bumped the DBeaver MCP bundle to 1.6.2 and AI Database Test Studio to 2.0.2.
- Rebuilt the full DBeaver Community product and self-contained Debian distribution.

## 2.0.1 — Custom distribution patch release

- Bumped the DBeaver MCP bundle to 1.6.1 and AI Database Test Studio to 2.0.1.
- Kept the embedded DBeaver Community base at its upstream 26.1.5 version.
- Refreshed install verification, runtime smoke assertions, and Debian package metadata.

## 2.0.0 — AI Database Test Studio

- Extracted MCP into a standalone fork-friendly source repository.
- Added versioned test-plan DSL, project persistence, migration, variable generation, and secret rejection.
- Added fingerprint-bound native approval and deterministic setup/steps/cleanup runner.
- Added assertion, report, AI, bridge, and database adapter SPIs.
- Added bounded evidence, retention, screenshots, JSON/JUnit/HTML/Markdown reports, and aborted-run recovery.
- Added native Test Studio view/editor and offline heuristic AI provider.
- Added PostgreSQL, MySQL/MariaDB, and SQLite adapters.
- Added real PostgreSQL/MySQL Docker E2E and DBeaver SQLite runtime E2E.
- Added compatibility matrix, P2 lifecycle scripts, scheduled upstream checks, SBOM, and release automation.
- Preserved all existing MCP tools; Test Studio adds one compact facade.
