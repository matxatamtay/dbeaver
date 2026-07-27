#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
source "$repo_root/scripts/lib/p2-common.sh"
require_product "${PRODUCT_DIR:-${1:-}}"
uninstall_if_present org.jkiss.dbeaver.teststudio.feature.feature.group /tmp/dbeaver-teststudio-uninstall.log
if [[ ${KEEP_MCP:-false} != true ]]; then
  uninstall_if_present org.jkiss.dbeaver.mcp.feature.feature.group /tmp/dbeaver-mcp-uninstall.log
fi
printf 'Uninstall completed. KEEP_MCP=%s\n' "${KEEP_MCP:-false}"
