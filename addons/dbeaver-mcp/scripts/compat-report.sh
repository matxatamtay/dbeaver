#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
upstream=${DBEAVER_UPSTREAM:-"$default_upstream"}
python3 - "$repo_root" "$upstream" <<'PY'
from pathlib import Path
import json,re,subprocess,sys
root=Path(sys.argv[1]); upstream=Path(sys.argv[2])
def git(*args): return subprocess.check_output(['git','-C',str(upstream),*args],text=True).strip()
def manifest(path):
 text=path.read_text(); unfolded=re.sub(r'\n ', '', text)
 def field(key):
  m=re.search(rf'(?m)^{re.escape(key)}:\s*([^\n]+)',unfolded);return m.group(1).strip() if m else ''
 return {'id':field('Bundle-SymbolicName').split(';',1)[0],'version':field('Bundle-Version').replace('.qualifier','')}
matrix=json.loads((root/'config/compatibility-matrix.json').read_text())
tracked=git('status','--porcelain','--untracked-files=no').splitlines()
compat_imports=[]
compat=root/'bundles/org.jkiss.dbeaver.teststudio.compat.dbeaver26/src'
for path in compat.rglob('*.java'):
 for line in path.read_text().splitlines():
  if line.startswith('import org.jkiss.dbeaver.'):
   compat_imports.append(line.removeprefix('import ').removesuffix(';'))
report={
 'schema_version':1,
 'dbeaver_revision':git('describe','--always','--dirty'),
 'dbeaver_commit':git('rev-parse','HEAD'),
 'dbeaver_ref':git('branch','--show-current') or 'detached',
 'tracked_upstream_changes':len(tracked),
 'source_repository':'dbeaver-mcp',
 'build_strategy':'additive-overlay',
 'matrix':matrix,
 'bundles':[manifest(p) for p in sorted(root.glob('bundles/*/META-INF/MANIFEST.MF'))],
 'compatibility_boundary':{
   'bundle':'org.jkiss.dbeaver.teststudio.compat.dbeaver26',
   'dbeaver_api_import_count':len(sorted(set(compat_imports))),
   'dbeaver_api_imports':sorted(set(compat_imports)),
   'tracked_upstream_diff_required':False
 },
 'capabilities':{
   'test_plan_dsl':'supported','persistence':'supported','transaction_runner':'supported',
   'evidence':'supported','reports':['json','junit','html','markdown'],
   'native_ui':'supported','ai_provider_spi':'supported',
   'database_adapters':['postgresql','mysql','sqlite'],
   'native_result_grid':'unsupported','query_manager_link':'unsupported'
 }
}
print(json.dumps(report,indent=2,sort_keys=True))
PY
