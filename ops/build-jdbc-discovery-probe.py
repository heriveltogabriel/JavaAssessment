#!/usr/bin/env python3
"""Build isolated Java 6 lab probe; never creates a released collector or changes VERSION."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--ecj', type=Path, required=True)
    args = parser.parse_args()
    parent = ROOT / 'output/jdbc-discovery'
    parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='probe-', dir=str(parent)))
    out.chmod(0o700)
    classes = out / 'classes'
    classes.mkdir()
    sources = [*sorted((ROOT / 'shared').glob('*.java')), *[p for p in sorted((ROOT / 'collector').glob('*.java')) if not p.stem.endswith('Test')], ROOT / 'ops/JdbcDiscoveryProbe.java']
    sources += [ROOT / 'collector' / name for name in ('JdbcEndpointTest.java', 'CollectorTest.java', 'ProcessLaunchTest.java')]
    hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    command = [str(args.java_home / 'bin/java'), '-jar', str(args.ecj), '-source', '1.6', '-target', '1.6', '-nowarn', '-cp', ':'.join(str(p) for p in sorted((ROOT / 'lib').glob('bc*.jar'))), '-d', str(classes)] + [str(p) for p in sources]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (out / 'compile.log').write_bytes(result.stdout)
    if result.returncode:
        raise SystemExit('Compilation failed: ' + str(out / 'compile.log'))
    if hashes != {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}:
        raise SystemExit('Sources changed during build; rebuild probe')
    jar = out / 'jdbc-discovery-probe.jar'
    with zipfile.ZipFile(str(jar), 'w', zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nMain-Class: assessment.JdbcDiscoveryProbe\nImplementation-Title: Isolated JDBC discovery probe - not a released agent\n\n')
        for path in sorted(classes.rglob('*.class')):
            data = path.read_bytes()
            if int.from_bytes(data[6:8], 'big') != 50:
                raise SystemExit('Unexpected class version')
            bundle.writestr(str(path.relative_to(classes)), data)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    (out / 'SHA256SUMS').write_text(digest + '  ' + jar.name + '\n')
    (out / 'source-hashes.json').write_text(json.dumps(hashes, indent=2) + '\n')
    print(str(jar))
    print('SHA256=' + digest)

if __name__ == '__main__':
    main()
