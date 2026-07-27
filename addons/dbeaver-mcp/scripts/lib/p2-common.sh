#!/usr/bin/env bash
set -euo pipefail

require_product() {
  PRODUCT_DIR=${PRODUCT_DIR:-${1:-}}
  [[ -n "$PRODUCT_DIR" ]] || { echo "PRODUCT_DIR is required" >&2; exit 2; }
  PRODUCT_DIR=$(cd "$PRODUCT_DIR" && pwd)
  [[ -x "$PRODUCT_DIR/dbeaver" ]] || { echo "DBeaver launcher not found: $PRODUCT_DIR/dbeaver" >&2; exit 2; }
  P2_PROFILE=${P2_PROFILE:-DefaultProfile}
}

repository_uri() {
  local value=${1:?repository path or URI is required}
  if [[ "$value" == *://* || "$value" == file:* ]]; then
    printf '%s\n' "$value"
  else
    local absolute
    absolute=$(cd "$value" && pwd)
    printf 'file:%s\n' "$absolute"
  fi
}

p2() {
  "$PRODUCT_DIR/dbeaver" -nosplash -application org.eclipse.equinox.p2.director \
    -destination "$PRODUCT_DIR" -profile "$P2_PROFILE" -roaming "$@"
}

uninstall_if_present() {
  local iu=$1
  local log=${2:-/dev/stdout}
  if p2 -listInstalledRoots 2>/dev/null | grep -Fq "$iu"; then
    p2 -uninstallIU "$iu" >"$log" 2>&1
    cat "$log"
  else
    printf 'IU is not installed: %s\n' "$iu"
  fi
}

verify_bundle() {
  local symbolic=$1 expected_prefix=$2
  local info="$PRODUCT_DIR/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
  [[ -f "$info" ]] || { echo "bundles.info not found: $info" >&2; exit 4; }
  local line
  line=$(grep -E "^${symbolic//./\\.}," "$info" | tail -1 || true)
  [[ -n "$line" ]] || { echo "Bundle not installed: $symbolic" >&2; exit 4; }
  local version
  version=$(cut -d, -f2 <<<"$line")
  [[ "$version" == "$expected_prefix"* ]] || { echo "Unexpected $symbolic version: $version, expected $expected_prefix*" >&2; exit 4; }
  printf '%s\n' "$line"
}

snapshot_installed_roots() {
  local output=$1
  mkdir -p "$(dirname "$output")"
  p2 -listInstalledRoots >"$output"
}
