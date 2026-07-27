#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/package-common.sh"

require_product
mkdir -p "$linux_dist" "$package_work"

deb_log="$package_work/package-deb.log"
appimage_log="$package_work/package-appimage.log"
"$repo_root/scripts/package-deb.sh" | tee "$deb_log"
deb=$(tail -n 1 "$deb_log")
"$repo_root/scripts/package-appimage.sh" | tee "$appimage_log"
appimage=$(tail -n 1 "$appimage_log")

(
  cd "$linux_dist"
  sha256sum -- "$(basename "$deb")" "$(basename "$appimage")" > SHA256SUMS
)

printf 'Linux packages:\n'
printf '  %s\n' "$deb"
printf '  %s\n' "$appimage"
cat "$linux_dist/SHA256SUMS"
