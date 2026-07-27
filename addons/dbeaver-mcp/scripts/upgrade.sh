#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
source "$repo_root/scripts/lib/p2-common.sh"
require_product "${PRODUCT_DIR:-${1:-}}"
repository=${REPOSITORY:-${2:-$default_repository}}
state="$repo_root/release/pre-upgrade-roots-$(date -u +%Y%m%dT%H%M%SZ).txt"
snapshot_installed_roots "$state"
uninstall_if_present org.jkiss.dbeaver.teststudio.feature.feature.group /tmp/dbeaver-teststudio-upgrade-uninstall.log
uninstall_if_present org.jkiss.dbeaver.mcp.feature.feature.group /tmp/dbeaver-mcp-upgrade-uninstall.log
REPOSITORY="$repository" PRODUCT_DIR="$PRODUCT_DIR" P2_PROFILE="$P2_PROFILE" "$repo_root/scripts/install.sh"
printf 'Upgrade completed. Rollback reference: %s\n' "$state"
