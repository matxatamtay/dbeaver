#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
source "$repo_root/scripts/lib/p2-common.sh"
require_product "${PRODUCT_DIR:-${1:-}}"
previous=${PREVIOUS_REPOSITORY:-${2:-}}
[[ -n "$previous" ]] || { echo "PREVIOUS_REPOSITORY is required for deterministic rollback" >&2; exit 2; }
uri=$(repository_uri "$previous")
uninstall_if_present org.jkiss.dbeaver.teststudio.feature.feature.group /tmp/dbeaver-teststudio-rollback-uninstall.log
uninstall_if_present org.jkiss.dbeaver.mcp.feature.feature.group /tmp/dbeaver-mcp-rollback-uninstall.log
p2 -repository "$uri" \
  -installIU org.jkiss.dbeaver.mcp.feature.feature.group,org.jkiss.dbeaver.teststudio.feature.feature.group \
  -profileProperties org.eclipse.update.install.features=true
printf 'Rollback installed from %s\n' "$uri"
