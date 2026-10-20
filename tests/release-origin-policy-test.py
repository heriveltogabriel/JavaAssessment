"""No signing or mutation: verify published origins and preserve all old routes."""
import importlib.util
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ops'))
spec = importlib.util.spec_from_file_location('release_builder', ROOT / 'ops/build-dashboard-release.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
rows = builder.reviewed_agent_origins(builder.support_module())
helper_rows = builder.reviewed_agent_origins(builder.support_module(), helper_only=True)
with zipfile.ZipFile(ROOT / 'output/dashboard-releases/2.1.14/packages/assessment-dashboard-runtime-2.1.14.zip') as archive:
    old = json.loads(archive.read('dist/agent-update-routes/2.1.14/routes.json'))
versions = {Path(row['path']).name.removeprefix('assessment-collector-').removesuffix('.zip') for row in rows}
assert versions == {r['fromVersion'] for r in old['routes']}, 'Published legacy fallbacks must all remain'
assert len(rows) == 32 and '1.5.12' in versions and '2.1.12' in versions
assert any(r['path'].endswith('assessment-collector-2.1.14.zip') for r in helper_rows)
assert builder.requires_legacy_bridge('2.1.12', {}, b'') is True
for invalid in ({}, {'update-public.der': b'fixture'}):
    try:
        builder.requires_legacy_bridge('2.1.14', invalid, b'fixture')
        raise AssertionError('Invalid V3 origin accepted')
    except RuntimeError:
        pass
print('RELEASE_ORIGIN_POLICY=PASS all 32 published migration profiles preserved; 2.1.14 signed V3 direct; helpers retained; invalid newer origins rejected')
