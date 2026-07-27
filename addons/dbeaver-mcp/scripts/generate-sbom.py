#!/usr/bin/env python3
from __future__ import annotations
import datetime as dt, hashlib, json, pathlib, re, sys, uuid
root=pathlib.Path(__file__).resolve().parents[1]
dist=root/'dist'

def manifest_value(text:str,key:str)->str:
    # unfold OSGi continuation lines
    unfolded=re.sub(r'\n ', '', text)
    match=re.search(rf'(?m)^{re.escape(key)}:\s*([^\n]+)',unfolded)
    return match.group(1).strip() if match else ''

packages=[]
relationships=[]
for manifest in sorted(root.glob('bundles/*/META-INF/MANIFEST.MF')):
    text=manifest.read_text()
    symbolic=manifest_value(text,'Bundle-SymbolicName').split(';',1)[0]
    version=manifest_value(text,'Bundle-Version').replace('.qualifier','')
    spdxid='SPDXRef-Package-'+re.sub(r'[^A-Za-z0-9.-]','-',symbolic)
    packages.append({
        'SPDXID':spdxid,'name':symbolic,'versionInfo':version,
        'downloadLocation':'NOASSERTION','filesAnalyzed':False,
        'licenseConcluded':'Apache-2.0','licenseDeclared':'Apache-2.0',
        'copyrightText':'Copyright DBeaver Corp and contributors'
    })
    relationships.append({'spdxElementId':'SPDXRef-DOCUMENT','relationshipType':'DESCRIBES','relatedSpdxElement':spdxid})
artifacts=[]
for path in sorted(dist.glob('*.zip')):
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    artifacts.append({'name':path.name,'sha256':digest,'bytes':path.stat().st_size})
doc={
  'spdxVersion':'SPDX-2.3','dataLicense':'CC0-1.0','SPDXID':'SPDXRef-DOCUMENT',
  'name':'DBeaver MCP and AI Database Test Studio',
  'documentNamespace':'https://dbeaver-mcp.local/spdx/'+str(uuid.uuid4()),
  'creationInfo':{'created':dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z'),'creators':['Tool: dbeaver-mcp/scripts/generate-sbom.py']},
  'packages':packages,'relationships':relationships,
  'annotations':[{'annotationDate':dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z'),'annotationType':'OTHER','annotator':'Tool: dbeaver-mcp release','comment':json.dumps({'artifacts':artifacts},sort_keys=True)}]
}
out=dist/'sbom.spdx.json'
out.write_text(json.dumps(doc,indent=2,sort_keys=True)+'\n')
print(out)
