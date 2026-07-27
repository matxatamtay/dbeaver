#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
source "$repo_root/scripts/lib/p2-common.sh"
require_product "${PRODUCT_DIR:-${1:-}}"
repository=${REPOSITORY:-${2:-$default_repository}}
uri=$(repository_uri "$repository")
state="$repo_root/release/install-state-$(date -u +%Y%m%dT%H%M%SZ).txt"
snapshot_installed_roots "$state"
p2 -repository "$uri" \
  -installIU org.jkiss.dbeaver.mcp.feature.feature.group,org.jkiss.dbeaver.teststudio.feature.feature.group \
  -profileProperties org.eclipse.update.install.features=true
verify_bundle org.jkiss.dbeaver.mcp 1.6.0
verify_bundle org.jkiss.dbeaver.teststudio.core 2.0.0
printf 'Install completed. Previous installed roots: %s\n' "$state"
