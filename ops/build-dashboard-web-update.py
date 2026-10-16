#!/usr/bin/env python3
"""Build the signed browser-update package from an already prepared release."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
from agent_update_signing import ensure_keypair

ROOT=Path(__file__).resolve().parents[1]

def build(runtime_path,sources_path,output,key,notes):
    with zipfile.ZipFile(runtime_path) as z:runtime={n:z.read(n) for n in z.namelist()}
    with zipfile.ZipFile(sources_path) as z:sources={n:z.read(n) for n in z.namelist()}
    version=runtime['VERSION'].decode().strip()
    candidates=json.loads(sources.pop('expected-baselines.json'));sources.pop('SHA256SUMS',None)
    payload={n:v for n,v in runtime.items() if n=='VERSION' or n.startswith(('classes/','web/','dist/','lib/'))}
    payload.update({n:v for n,v in sources.items() if n.startswith(('server/','shared/','web/','maintenance/'))})
    manifest={'format':'java-assessment-dashboard-update-v1','version':version,
              'fromVersions':sorted({item['version'] for item in candidates}),
              'baselines':[item['hashes'] for item in candidates],
              'rollback':'preserve-database-v1','notes':notes,
              'files':{n:{'size':len(v),'sha256':hashlib.sha256(v).hexdigest()} for n,v in sorted(payload.items())}}
    raw=(json.dumps(manifest,sort_keys=True,separators=(',',':'))+'\n').encode()
    with tempfile.TemporaryDirectory(prefix='dashboard-web-sign-') as tmp:
        path=Path(tmp)/'manifest';sig=Path(tmp)/'signature';path.write_bytes(raw)
        subprocess.check_call(['openssl','dgst','-sha256','-sign',str(key),'-out',str(sig),str(path)])
        signature=sig.read_bytes()
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('xb') as stream,zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('manifest.json',raw);z.writestr('manifest.sig',signature)
        for name,data in sorted(payload.items()):z.writestr('payload/'+name,data)
    digest=hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_name(output.name+'.sha256').write_text(digest+'  '+output.name+'\n')
    return manifest

def main():
    p=argparse.ArgumentParser();p.add_argument('--release',required=True);args=p.parse_args()
    folder=ROOT/'output/dashboard-releases'/args.release
    key,_=ensure_keypair(ROOT,create=False)
    build(folder/'packages'/('assessment-dashboard-runtime-'+args.release+'.zip'),folder/'deployment/dashboard-update-sources.zip',
          folder/'packages'/('assessment-dashboard-web-update-'+args.release+'.zip'),key,
          'Atualização do dashboard '+args.release+'. Atualize os agentes separadamente pelo painel.')

if __name__=='__main__':main()
