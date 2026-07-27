#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/paths.sh"

product_dir=${DBEAVER_PRODUCT_DIR:-$default_product}
linux_dist=${LINUX_DIST_DIR:-"$repo_root/dist/linux"}
package_work=${PACKAGE_WORK_DIR:-"$repo_root/.work/linux-package"}
package_id=dbeaver-ce-mcp-studio
install_dir_name=dbeaver-mcp-studio

require_product() {
  [[ -x "$product_dir/dbeaver" ]] || { echo "DBeaver product executable not found: $product_dir/dbeaver" >&2; exit 2; }
  [[ -f "$product_dir/dbeaver.ini" ]] || { echo "DBeaver product ini not found: $product_dir/dbeaver.ini" >&2; exit 2; }
  grep -q 'org.jkiss.dbeaver.mcp,' "$product_dir/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" || {
    echo "MCP bundle is not installed in product: $product_dir" >&2; exit 2;
  }
  grep -q 'org.jkiss.dbeaver.teststudio.core,' "$product_dir/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" || {
    echo "Test Studio bundle is not installed in product: $product_dir" >&2; exit 2;
  }
}

core_version() {
  local jar base
  jar=$(find "$product_dir/plugins" -maxdepth 1 -type f -name 'org.jkiss.dbeaver.core_*.jar' | sort -V | tail -1)
  [[ -n "$jar" ]] || { echo "Unable to detect DBeaver core version" >&2; exit 2; }
  base=$(basename "$jar")
  base=${base#org.jkiss.dbeaver.core_}
  printf '%s\n' "${base%.jar}"
}

studio_version() {
  local jar base
  jar=$(find "$product_dir/plugins" -maxdepth 1 -type f -name 'org.jkiss.dbeaver.teststudio.core_*.jar' | sort -V | tail -1)
  [[ -n "$jar" ]] || { echo "Unable to detect Test Studio version" >&2; exit 2; }
  base=$(basename "$jar")
  base=${base#org.jkiss.dbeaver.teststudio.core_}
  base=${base%.jar}
  printf '%s\n' "$base" | cut -d. -f1-3
}

package_version() {
  local core studio
  core=$(core_version)
  studio=$(studio_version)
  printf '%s\n' "${DBEAVER_PACKAGE_VERSION:-${core}+studio${studio}}"
}

ensure_java_runtime() {
  local java_bin java_home runtime marker current
  runtime="$package_work/jre"
  marker="$runtime/.source-release"
  java_bin=$(readlink -f "$(command -v java)")
  java_home=$(dirname "$(dirname "$java_bin")")
  current=$(tr '\n' ' ' < "$java_home/release")
  if [[ ! -x "$runtime/bin/java" || ! -f "$marker" || $(cat "$marker") != "$current" ]]; then
    rm -rf "$runtime"
    mkdir -p "$package_work"
    "$java_home/bin/jlink" \
      --add-modules ALL-MODULE-PATH \
      --bind-services \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output "$runtime"
    printf '%s' "$current" > "$marker"
  fi
  printf '%s\n' "$runtime"
}

write_desktop_file() {
  local output exec_name icon_name
  output=$1
  exec_name=$2
  icon_name=$3
  cat > "$output" <<EOF
[Desktop Entry]
Type=Application
Name=DBeaver MCP Test Studio
GenericName=Database Manager and AI Test Studio
Comment=DBeaver Community with MCP and AI Database Test Studio
Exec=${exec_name} %U
Icon=${icon_name}
Terminal=false
StartupNotify=true
StartupWMClass=DBeaver
Categories=Development;Database;IDE;
MimeType=application/sql;text/x-sql;
Keywords=database;sql;mcp;ai;test;studio;dbeaver;
EOF
  desktop-file-validate "$output"
}

copy_product() {
  local destination runtime
  destination=$1
  runtime=$2
  mkdir -p "$destination"
  rsync -a --delete "$product_dir/" "$destination/"
  rm -rf "$destination/jre"
  rsync -a "$runtime/" "$destination/jre/"
}

write_launcher() {
  local output app_home
  output=$1
  app_home=$2
  cat > "$output" <<EOF
#!/bin/sh
set -eu
export DBEAVER_MCP_ENABLED="\${DBEAVER_MCP_ENABLED:-true}"
export DBEAVER_MCP_HOST="\${DBEAVER_MCP_HOST:-127.0.0.1}"
exec "$app_home/dbeaver" -vm "$app_home/jre/bin/java" "\$@"
EOF
  chmod 0755 "$output"
}

smoke_product() {
  local app_home
  app_home=$1
  "$app_home/dbeaver" -vm "$app_home/jre/bin/java" -nosplash \
    -application org.eclipse.equinox.p2.director -listInstalledRoots \
    > "$package_work/p2-roots-$(basename "$app_home").log" 2>&1
  grep -q 'org.jkiss.dbeaver.mcp.feature.feature.group' "$package_work/p2-roots-$(basename "$app_home").log"
  grep -q 'org.jkiss.dbeaver.teststudio.feature.feature.group' "$package_work/p2-roots-$(basename "$app_home").log"
}
