#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
upstream=${DBEAVER_UPSTREAM:-"$default_upstream"}
[[ -d "$upstream/.git" || -f "$upstream/.git" ]] || { echo "Not a DBeaver git worktree: $upstream" >&2; exit 2; }
if [[ -n $(git -C "$upstream" status --porcelain --untracked-files=no) ]]; then
  echo "Refusing overlay: upstream worktree has tracked changes" >&2
  git -C "$upstream" status --short --untracked-files=no >&2
  exit 3
fi
copy_tree() {
  local source=$1 destination=$2
  mkdir -p "$destination"
  rsync -a --delete --exclude target --exclude .codegraph "$source/" "$destination/"
}
for bundle in "$repo_root"/bundles/*; do [[ -d "$bundle" ]] && copy_tree "$bundle" "$upstream/plugins/$(basename "$bundle")"; done
for feature in "$repo_root"/features/*; do [[ -d "$feature" ]] && copy_tree "$feature" "$upstream/features/$(basename "$feature")"; done
for repository in "$repo_root"/repositories/*; do [[ -d "$repository" ]] && copy_tree "$repository" "$upstream/product/repositories/$(basename "$repository")"; done
copy_tree "$repo_root/releng/org.jkiss.dbeaver.mcp.build" "$upstream/releng/org.jkiss.dbeaver.mcp.build"
if [[ -n $(git -C "$upstream" diff --name-only) ]]; then
  echo "Overlay modified tracked upstream files; this violates the fork-friendly boundary" >&2
  git -C "$upstream" diff --name-only >&2
  exit 4
fi
printf 'Overlay ready in %s\n' "$upstream"
