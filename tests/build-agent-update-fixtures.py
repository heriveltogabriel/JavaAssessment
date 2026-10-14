#!/usr/bin/env python3
"""Create signed disposable test payloads with a separate test key; never touch production trust."""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ops'))
from agent_update_signing import ensure_keypair,sign_release,sha
from unified_agent import compile_product


def jar(classes,version):
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nImplementation-Version: '+version+'\n\n')
        for file in sorted(classes.rglob('*.class')): archive.writestr(str(file.relative_to(classes)),file.read_bytes())
    return stream.getvalue()


def build(destination,version):
    destination=Path(destination);destination.mkdir(parents=True,exist_ok=True)
    _,public=ensure_keypair(destination,create=True)
    java=Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java')
    baseline=ROOT/'dist/assessment-collector.zip'
    with zipfile.ZipFile(baseline) as archive:
        entries={name:archive.read(name) for name in archive.namelist() if name not in ('SHA256SUMS','collector.jar')}
    entries.update({p.name:p.read_bytes() for p in (ROOT/'collector').glob('[a-z]*.sh')})
    entries['update-public.der']=public
    for name in ('old','good','bad'):
        selected='0.0.1' if name=='old' else version
        with tempfile.TemporaryDirectory(prefix='agent-update-build-') as temporary:
            scratch=Path(temporary);classes=scratch/'classes';classes.mkdir()
            transport=scratch/'AgentTransport.java'
            import re
            transport.write_text(re.sub(r'static final String VERSION = "[^"]+";', 'static final String VERSION = "'+selected+'";', (ROOT/'collector/AgentTransport.java').read_text()))
            sources=[*sorted((ROOT/'shared').glob('*.java')),transport,*[p for p in sorted((ROOT/'collector').glob('*.java')) if not p.stem.endswith('Test') and p.name!='AgentTransport.java']]
            compile_product(ROOT,classes,java,sources)
            if name=='bad':
                # Pass staging probe, then fail from the installed path: exercise actual rollback.
                broken=scratch/'AgentUpdateProbe.java'
                broken.write_text('package assessment; public final class AgentUpdateProbe { public static void main(String[] a) { if(AgentUpdateProbe.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/collector.jar")) System.exit(20); System.out.println("FIXTURE_STAGING_PROBE_OK"); } }')
                compile_product(ROOT,classes,java,[broken])
            payload=jar(classes,selected)
        target=destination/name;target.mkdir(exist_ok=True)
        (target/'collector.jar').write_bytes(payload)
        if name=='old':continue
        content={**entries,'collector.jar':payload,'DEPENDENCIES.json':(json.dumps({'version':selected})+'\n').encode()}
        content['SHA256SUMS']=''.join(sha(data)+'  '+file+'\n' for file,data in sorted(content.items())).encode()
        package=target/'assessment-collector.zip'
        with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as archive:
            for file,data in sorted(content.items()):archive.writestr(file,data)
        assets=sign_release(destination,package,selected)
        for file,data in assets.items():
            output=target/file;output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(data)
    (destination/'fixture.json').write_text(json.dumps({'version':version,'previousVersion':'0.0.1','testOnly':True,'productionTrustUsed':False})+'\n')
    print('Disposable signed good/bad payloads ready: '+str(destination))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('destination');parser.add_argument('version');args=parser.parse_args();build(args.destination,args.version)
