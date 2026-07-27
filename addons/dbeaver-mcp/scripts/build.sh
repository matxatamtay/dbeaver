#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
upstream=${DBEAVER_UPSTREAM:-"$default_upstream"}
common=${DBEAVER_COMMON:-"$default_common"}
"$repo_root/scripts/overlay.sh"
"$common/mvnw" package -f "$upstream/releng/org.jkiss.dbeaver.mcp.build/pom.xml" -DskipTests "$@"
mkdir -p "$repo_root/dist"
find "$upstream/product/repositories" -path '*/target/*.zip' -name '*updateSite*.zip' -exec cp -f {} "$repo_root/dist/" \;
if [[ -n $(git -C "$upstream" diff --name-only) ]]; then
  echo "Build modified tracked upstream files" >&2
  git -C "$upstream" diff --name-only >&2
  exit 5
fi
sha256sum "$repo_root"/dist/*.zip > "$repo_root/dist/SHA256SUMS"
cat "$repo_root/dist/SHA256SUMS"
