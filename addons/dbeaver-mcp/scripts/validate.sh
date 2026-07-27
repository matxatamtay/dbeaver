#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
upstream=${DBEAVER_UPSTREAM:-"$default_upstream"}

python3 - "$repo_root" <<'PY'
from pathlib import Path
import json, sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1])
errors=[]
xml_files=list(root.glob('bundles/*/plugin.xml'))+list(root.glob('bundles/*/schema/*.exsd'))+list(root.glob('features/*/feature.xml'))+list(root.glob('repositories/*/category.xml'))+list(root.glob('**/pom.xml'))
for path in xml_files:
    try: ET.parse(path)
    except Exception as e: errors.append(f'{path.relative_to(root)}: invalid XML: {e}')
for path in root.glob('bundles/*/schema/*.json'):
    try: json.loads(path.read_text())
    except Exception as e: errors.append(f'{path.relative_to(root)}: invalid JSON: {e}')
try:
    matrix=json.loads((root/'config/compatibility-matrix.json').read_text())
    assert matrix['schema_version']==1
    assert len(matrix['supported'])>=2
    assert all(x.get('dbeaver_ref') for x in matrix['supported']+matrix['best_effort'])
except Exception as e: errors.append(f'config/compatibility-matrix.json: {e}')
for manifest in root.glob('bundles/*/META-INF/MANIFEST.MF'):
    text=manifest.read_text()
    required=['Bundle-SymbolicName:','Bundle-Version:','Bundle-RequiredExecutionEnvironment: JavaSE-21']
    for marker in required:
        if marker not in text: errors.append(f'{manifest.relative_to(root)}: missing {marker}')
if errors:
    print('\n'.join(errors),file=sys.stderr);sys.exit(1)
print(f'Validated XML={len(xml_files)}, JSON schemas/configuration, and bundle manifests')
PY

# Volatile DBeaver APIs may only appear in the compatibility bundle. UI may use Eclipse UI,
# but engine, reports, AI, and database adapters must not import DBeaver implementation APIs.
violations=$(grep -RIn --include='*.java' -E '^import org\.jkiss\.dbeaver\.(model|registry|ui|runtime|tools|ext)\.' \
  "$repo_root/bundles" \
  --exclude-dir=org.jkiss.dbeaver.mcp \
  --exclude-dir=org.jkiss.dbeaver.teststudio.compat.dbeaver26 \
  --exclude-dir=target || true)
if [[ -n "$violations" ]]; then
  echo "DBeaver API imports escaped the compatibility boundary:" >&2
  echo "$violations" >&2
  exit 1
fi
internal=$(grep -RIn --include='*.java' -E '^import .+\.internal\.' "$repo_root/bundles" --exclude-dir=target || true)
if [[ -n "$internal" ]]; then
  echo "Internal implementation imports are forbidden:" >&2
  echo "$internal" >&2
  exit 1
fi

if [[ -d "$upstream" ]]; then
  if [[ -n $(git -C "$upstream" status --porcelain --untracked-files=no) ]]; then
    echo "Disposable upstream worktree contains tracked changes:" >&2
    git -C "$upstream" status --short --untracked-files=no >&2
    exit 1
  fi
fi

git -C "$repo_root" diff --check

if grep -RInE --exclude-dir=target --exclude-dir=.git \
  '(AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{24,})' \
  "$repo_root"; then
  echo "Credential-shaped literal detected" >&2
  exit 1
fi

printf 'Validation passed: schemas, manifests, compatibility boundary, upstream cleanliness, whitespace, and secret patterns\n'
