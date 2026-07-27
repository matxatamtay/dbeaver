# Release and P2 lifecycle

## Build

```bash
./scripts/release.sh
./scripts/e2e-databases.sh
```

Release output includes the P2 ZIP, compatibility report, SPDX 2.3 SBOM, release manifest, and SHA-256 checksums.

## Install

```bash
PRODUCT_DIR=/opt/dbeaver \
REPOSITORY=/path/to/repository \
./scripts/install.sh
```

## Upgrade

`upgrade.sh` snapshots installed roots, uninstalls Studio and MCP independently, and installs both from the new repository. Independent uninstall prevents a missing optional IU from aborting the whole upgrade.

## Rollback

Rollback is deterministic and requires a previous repository:

```bash
PRODUCT_DIR=/opt/dbeaver \
PREVIOUS_REPOSITORY=/path/to/previous/repository \
./scripts/rollback.sh
```

## CI gates

- XML/JSON/manifest validation.
- Compatibility-boundary and secret scans.
- Tycho/P2 build.
- Legacy MCP tests.
- Studio core, AI, and adapter tests.
- PostgreSQL and MySQL transaction E2E.
- Current/previous/devel compatibility matrix.
- Scheduled full DBeaver CE product install/runtime smoke.
- Artifact checksums and SBOM.
