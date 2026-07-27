#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/package-common.sh"

require_product
mkdir -p "$linux_dist"

deb=$($repo_root/scripts/package-deb.sh)
appimage=$($repo_root/scripts/package-appimage.sh)

(
  cd "$linux_dist"
  sha256sum -- "$(basename "$deb")" "$(basename "$appimage")" > SHA256SUMS
)

printf 'Linux packages:\n'
printf '  %s\n' "$deb"
printf '  %s\n' "$appimage"
cat "$linux_dist/SHA256SUMS"
