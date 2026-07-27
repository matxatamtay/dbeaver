#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/package-common.sh"

require_product
runtime=$(ensure_java_runtime)
version=$(package_version)
case $(uname -m) in
  x86_64) appimage_arch=x86_64; tool_name=appimagetool-x86_64.AppImage ;;
  aarch64|arm64) appimage_arch=aarch64; tool_name=appimagetool-aarch64.AppImage ;;
  *) echo "Unsupported AppImage architecture: $(uname -m)" >&2; exit 2 ;;
esac

appdir="$package_work/AppDir"
app_home="$appdir/usr/lib/$install_dir_name"
output="$linux_dist/DBeaver-MCP-Test-Studio-${version}-${appimage_arch}.AppImage"
tool="$repo_root/.work/tools/$tool_name"
rm -rf "$appdir"
mkdir -p \
  "$appdir/usr/bin" \
  "$appdir/usr/share/applications" \
  "$appdir/usr/share/icons/hicolor/256x256/apps" \
  "$linux_dist" \
  "$(dirname "$tool")"

copy_product "$app_home" "$runtime"
cat > "$appdir/AppRun" <<'EOF'
#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_HOME="$HERE/usr/lib/dbeaver-mcp-studio"
export DBEAVER_MCP_ENABLED="${DBEAVER_MCP_ENABLED:-true}"
export DBEAVER_MCP_HOST="${DBEAVER_MCP_HOST:-127.0.0.1}"
exec "$APP_HOME/dbeaver" -vm "$APP_HOME/jre/bin/java" "$@"
EOF
chmod 0755 "$appdir/AppRun"
ln -s ../lib/$install_dir_name/dbeaver "$appdir/usr/bin/dbeaver-mcp-studio"
write_desktop_file \
  "$appdir/dbeaver-mcp-studio.desktop" \
  dbeaver-mcp-studio \
  dbeaver-mcp-studio
cp "$appdir/dbeaver-mcp-studio.desktop" "$appdir/usr/share/applications/"
install -m 0644 "$product_dir/dbeaver.png" "$appdir/dbeaver-mcp-studio.png"
cp "$appdir/dbeaver-mcp-studio.png" \
  "$appdir/usr/share/icons/hicolor/256x256/apps/dbeaver-mcp-studio.png"

if [[ ! -x "$tool" ]]; then
  url=${APPIMAGETOOL_URL:-"https://github.com/AppImage/appimagetool/releases/download/continuous/$tool_name"}
  curl --fail --location --retry 3 --retry-delay 2 "$url" -o "$tool"
  chmod 0755 "$tool"
fi

rm -f "$output"
ARCH=$appimage_arch VERSION=$version APPIMAGE_EXTRACT_AND_RUN=1 \
  "$tool" "$appdir" "$output"
chmod 0755 "$output"

APPIMAGE_EXTRACT_AND_RUN=1 "$output" \
  -nosplash -application org.eclipse.equinox.p2.director -listInstalledRoots \
  > "$package_work/appimage-p2-roots.log" 2>&1
grep -q 'org.jkiss.dbeaver.mcp.feature.feature.group' "$package_work/appimage-p2-roots.log"
grep -q 'org.jkiss.dbeaver.teststudio.feature.feature.group' "$package_work/appimage-p2-roots.log"
printf '%s\n' "$output"
