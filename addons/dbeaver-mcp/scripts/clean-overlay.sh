#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
upstream=${DBEAVER_UPSTREAM:-"$default_upstream"}
for bundle in "$repo_root"/bundles/*; do rm -rf "$upstream/plugins/$(basename "$bundle")"; done
for feature in "$repo_root"/features/*; do rm -rf "$upstream/features/$(basename "$feature")"; done
for repository in "$repo_root"/repositories/*; do rm -rf "$upstream/product/repositories/$(basename "$repository")"; done
rm -rf "$upstream/releng/org.jkiss.dbeaver.mcp.build"
if [[ -n $(git -C "$upstream" status --porcelain --untracked-files=no) ]]; then
  echo "Tracked upstream changes remain" >&2
  git -C "$upstream" status --short --untracked-files=no >&2
  exit 1
fi
