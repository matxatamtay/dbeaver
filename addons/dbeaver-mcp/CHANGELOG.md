# Changelog

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
