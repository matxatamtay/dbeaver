#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/package-common.sh"

require_product
runtime=$(ensure_java_runtime)
version=$(package_version)
case $(uname -m) in
  x86_64) arch=amd64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) echo "Unsupported Debian architecture: $(uname -m)" >&2; exit 2 ;;
esac

root="$package_work/deb-root"
app_home="$root/opt/$install_dir_name"
output="$linux_dist/${package_id}_${version}_${arch}.deb"
rm -rf "$root"
mkdir -p \
  "$root/DEBIAN" \
  "$root/usr/bin" \
  "$root/usr/share/applications" \
  "$root/usr/share/icons/hicolor/256x256/apps" \
  "$linux_dist"

copy_product "$app_home" "$runtime"
write_launcher "$root/usr/bin/dbeaver-mcp-studio" "/opt/$install_dir_name"
write_desktop_file \
  "$root/usr/share/applications/dbeaver-mcp-studio.desktop" \
  dbeaver-mcp-studio \
  dbeaver-mcp-studio
install -m 0644 "$product_dir/dbeaver.png" \
  "$root/usr/share/icons/hicolor/256x256/apps/dbeaver-mcp-studio.png"

installed_size=$(du -sk "$root" | awk '{print $1}')
cat > "$root/DEBIAN/control" <<EOF
Package: $package_id
Version: $version
Section: devel
Priority: optional
Architecture: $arch
Maintainer: 8-hand Massage <lqvinh.contact@gmail.com>
Installed-Size: $installed_size
Depends: libc6 (>= 2.31), libglib2.0-0, libgtk-3-0, libx11-6
Homepage: https://github.com/matxatamtay/dbeaver
Description: DBeaver Community with MCP and AI Database Test Studio
 A self-contained Java runtime, DBeaver Community, the local MCP server,
 and AI Database Test Studio are included in one desktop package.
EOF
cat > "$root/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
exit 0
EOF
cat > "$root/DEBIAN/postrm" <<'EOF'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
exit 0
EOF
chmod 0755 "$root/DEBIAN/postinst" "$root/DEBIAN/postrm"

rm -f "$output"
dpkg-deb --root-owner-group --build "$root" "$output"
dpkg-deb --info "$output" > "$package_work/deb-info.log"

extract="$package_work/deb-extract"
rm -rf "$extract"
mkdir -p "$extract"
dpkg-deb -x "$output" "$extract"
smoke_product "$extract/opt/$install_dir_name"
printf '%s\n' "$output"
