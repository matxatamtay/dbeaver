#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
"$repo_root/scripts/validate.sh"
"$repo_root/scripts/build.sh"
"$repo_root/scripts/test.sh"
"$repo_root/scripts/compat-report.sh" > "$repo_root/dist/compatibility-report.json"
"$repo_root/scripts/generate-sbom.py"
(
  cd "$repo_root/dist"
  rm -f SHA256SUMS
  sha256sum -- *.zip *.json > SHA256SUMS
)
python3 - "$repo_root/dist" <<'PY'
from pathlib import Path
import json,sys
root=Path(sys.argv[1])
checks={line.split()[1]:line.split()[0] for line in (root/'SHA256SUMS').read_text().splitlines() if line.strip()}
manifest={'schema_version':1,'artifacts':[]}
for name,digest in sorted(checks.items()):
 p=root/name
 manifest['artifacts'].append({'name':name,'sha256':digest,'bytes':p.stat().st_size})
(root/'release-manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
PY
(
  cd "$repo_root/dist"
  sha256sum release-manifest.json >> SHA256SUMS
)
printf 'Release artifacts:\n'
find "$repo_root/dist" -maxdepth 1 -type f -printf '%f %s bytes\n' | sort
