#!/usr/bin/env python3
"""One-off authorized 2.1.16 revision; restore the unchanged published agent ZIP.
Run from the repository after building the revised installer.
"""
from pathlib import Path
import zipfile,io,hashlib,os
original=Path('output/dashboard-releases/2.1.16/original-installer-2.1.16.zip')
assert hashlib.sha256(original.read_bytes()).hexdigest()=='8ab1a2186f4c1db7c82cf2aa2e14679bda842c8d65ac4e7f15963770ab3dafec'
with zipfile.ZipFile(original) as z:agent=z.read('dist/assessment-collector.zip')
def entries(raw):
 with zipfile.ZipFile(io.BytesIO(raw)) as z:return {n:z.read(n) for n in z.namelist()}
for name in ['assessment-dashboard-installer-2.1.16.zip','assessment-dashboard-installer.zip']:
 path=Path('dist')/name
 with zipfile.ZipFile(path) as z:
  files={n:z.read(n) for n in z.namelist()};infos=z.infolist()
 assert entries(files['dist/assessment-collector.zip'])==entries(agent),'Agent content changed'
 files['dist/assessment-collector.zip']=agent
 files['SHA256SUMS']=''.join(hashlib.sha256(data).hexdigest()+'  '+n+'\n' for n,data in sorted(files.items()) if n!='SHA256SUMS').encode()
 tmp=path.with_suffix('.repacked.zip')
 with zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED) as z:
  for item in infos:z.writestr(item,files[item.filename])
 with zipfile.ZipFile(tmp) as z:assert z.testzip() is None
 os.replace(tmp,path)
 path.with_name(path.name+'.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name+'\n')
print('Original installer agent ZIP preserved byte-for-byte; only ZIP metadata had differed, all contents already equal.')
