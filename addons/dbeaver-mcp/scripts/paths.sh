#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# The source can live standalone next to a DBeaver checkout, or under
# <dbeaver>/addons/dbeaver-mcp. Resolve both layouts without absolute paths.
if [[ -d "$repo_root/../../plugins" && -d "$repo_root/../../product" ]]; then
  dbeaver_checkout=$(cd "$repo_root/../.." && pwd)
  workspace_root=$(cd "$dbeaver_checkout/.." && pwd)
else
  workspace_root=$(cd "$repo_root/.." && pwd)
  dbeaver_checkout=${DBEAVER_CHECKOUT:-"$workspace_root/dbeaver"}
fi

default_upstream="$workspace_root/dbeaver-upstream-mcp-test"
default_common="$workspace_root/dbeaver-common"
default_product="$dbeaver_checkout/product/community/target/products/org.jkiss.dbeaver.core.product/linux/gtk/x86_64/dbeaver"
default_repository="$default_upstream/product/repositories/org.jkiss.dbeaver.mcp.repository/target/repository"

export repo_root workspace_root dbeaver_checkout default_upstream default_common default_product default_repository
