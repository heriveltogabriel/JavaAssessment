#!/usr/bin/env python3
"""One-off, user-authorized 2.1.16 dashboard revision; preserve signed agents byte for byte.

Build to a NEW output directory, never overwrite an original release here.
Requires the original published 2.1.16 installer and immutable local receipts.
"""
import argparse, hashlib, importlib.util, json, shutil, subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ORIGINAL_COMMIT='3ce956d7f12287e09c8cf52651ad534c38866807'
ORIGINAL_INSTALLER_SHA='8ab1a2186f4c1db7c82cf2aa2e14679bda842c8d65ac4e7f15963770ab3dafec'

def main():
    p=argparse.ArgumentParser();p.add_argument('--original-installer',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    assert not a.output.exists(),'Output must be a new directory'
    assert hashlib.sha256(a.original_installer.read_bytes()).hexdigest()==ORIGINAL_INSTALLER_SHA,'Original published installer changed'
    subprocess.check_call(['git','diff','--exit-code',ORIGINAL_COMMIT,'--','collector','integration','lib'],cwd=ROOT)
    spec=importlib.util.spec_from_file_location('release_builder',ROOT/'ops/build-dashboard-release.py');builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
    support=builder.support_module();baselines,_,_=builder.known_artifacts(support)
    original=support.read_zip(a.original_installer)
    # Published installer supplies signed assets; receipts independently pin runtime and sources.
    release=next(v for v in json.loads((ROOT/'release/dashboard.json').read_text())['releases'] if v['version']=='2.1.16')
    assert release['status']=='prepared'
    original_dir=ROOT/'output/dashboard-revisions/2.1.16-original'
    if not original_dir.is_dir():original_dir=ROOT/'output/dashboard-releases/2.1.16'
    runtime=support.read_zip(original_dir/'packages/assessment-dashboard-runtime-2.1.16.zip')
    assert {k:v for k,v in runtime.items() if k.startswith('dist/') and k!='dist/assessment-collector.zip'}=={k:v for k,v in original.items() if k.startswith('dist/') and k!='dist/assessment-collector.zip'}
    # Fresh-installer packaging changes only its example configuration and its checksum.
    import io, zipfile
    with zipfile.ZipFile(io.BytesIO(runtime['dist/assessment-collector.zip'])) as r, zipfile.ZipFile(io.BytesIO(original['dist/assessment-collector.zip'])) as i:
        assert set(r.namelist())==set(i.namelist())
        assert all(r.read(n)==i.read(n) for n in r.namelist() if n not in ('conf/collector.properties.example','SHA256SUMS'))
    agent=original_dir/'packages/assessment-collector-2.1.16.zip'
    assert agent.read_bytes()==runtime['dist/assessment-collector.zip']
    out=a.output.resolve();(out/'build').mkdir(parents=True);(out/'evidence').mkdir()
    # These fixture classes were compiled by the full source suite immediately before packaging.
    shutil.copytree(ROOT/'tmp/source-classes',out/'build/server')
    receipt=json.loads((original_dir/'packages/package-manifest.json').read_text())
    hashes={v['file']:v['sha256'] for v in receipt['artifacts']}
    original_baseline,_=builder.baseline(support,original_dir/'packages/assessment-dashboard-runtime-2.1.16.zip',original_dir/'deployment/dashboard-update-sources.zip',hashes['packages/assessment-dashboard-runtime-2.1.16.zip'],hashes['deployment/dashboard-update-sources.zip'],'original dashboard 2.1.16 before single-file revision','2.1.16')
    (out/'original-dashboard-baseline.json').write_text(json.dumps(original_baseline,indent=2)+'\n')
    baselines=[v for v in baselines if v['version']!='2.1.16']
    builder.package(release,out,support,runtime,baselines,agent,preserved_agent_runtime=runtime)
    revised=support.read_zip(out/'packages/assessment-dashboard-runtime-2.1.16.zip')
    assert {k:v for k,v in revised.items() if k.startswith('dist/')}=={k:v for k,v in runtime.items() if k.startswith('dist/')}
    assert (ROOT/'server/DashboardVersion.java').read_bytes()==original['server/DashboardVersion.java']
    print('REVISION_216=PASS dashboard rebuilt; all signed agent assets and version identity unchanged')

if __name__=='__main__':main()
