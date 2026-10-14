"""Compile the real agent with the shared release version; preserve verified public assets."""
import io
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import zipfile

from dashboard_release import agent_version

ECJ = Path(os.environ.get('ASSESSMENT_ECJ_JAR', str(Path(__file__).resolve().parents[1] / 'tools/ecj-3.33.0.jar')))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def compile_product(root, destination, java, sources):
    require(ECJ.is_file(), 'Set ASSESSMENT_ECJ_JAR to an Eclipse compiler supporting Java 6 targets (for example 3.33.0)')
    result = subprocess.run([str(java), '-jar', str(ECJ), '-source', '1.6', '-target', '1.6', '-nowarn',
                             '-cp', ':'.join(str(p) for p in sorted((root / 'lib').glob('bc*.jar'))),
                             '-d', str(destination), *map(str, sources)], capture_output=True, text=True, timeout=120)
    require(result.returncode == 0, 'Agent compilation failed: ' + result.stderr[-4000:])
    return result.stdout + result.stderr


def build(root, out, release, baseline, support, java, full_bundle=False, bundle_bootstrap=None):
    version = release['version']
    require(agent_version((root / 'collector/AgentTransport.java').read_text()) == version,
            'Agent source version differs from shared release')
    source_paths = [*sorted((root / 'shared').glob('*.java')), *[p for p in sorted((root / 'collector').glob('*.java'))
                                               if not p.stem.endswith('Test')]]
    source_bytes = {p: p.read_bytes() for p in source_paths}
    classes = out / 'build/collector'
    classes.mkdir(parents=True, exist_ok=True)
    (out / 'evidence/compile-agent.log').write_text(compile_product(root, classes, java, source_paths))
    allowed = {p.stem for p in source_paths}
    tested = {p.name: p.read_bytes() for p in (classes / 'assessment').glob('*.class')
              if p.stem.split('$')[0] in allowed}
    require(tested and all(int.from_bytes(b[6:8], 'big') == 50 for b in tested.values()), 'Agent must retain Java6 bytecode')
    with tempfile.TemporaryDirectory(prefix='assessment-unified-agent-') as temporary:
        compile_product(root, Path(temporary), java, source_paths)
        rebuilt = {p.name: p.read_bytes() for p in (Path(temporary) / 'assessment').glob('*.class')}
        require(rebuilt == tested, 'Independent agent compilation differs from tested classes')
    require(all(p.read_bytes() == data for p, data in source_bytes.items()), 'Agent source changed while compiling')
    entries = support.read_zip(baseline)
    entries.pop('SHA256SUMS')
    jar_buffer = io.BytesIO()
    with zipfile.ZipFile(jar_buffer, 'w', zipfile.ZIP_DEFLATED) as jar:
        files = {'assessment/' + name: data for name, data in tested.items()}
        files['META-INF/MANIFEST.MF'] = ('Manifest-Version: 1.0\nImplementation-Version: ' + version + '\n\n').encode()
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0));info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16;jar.writestr(info, data)
    entries['collector.jar'] = jar_buffer.getvalue()
    deps = json.loads(entries['DEPENDENCIES.json'])
    deps['version'] = version
    require(support.digest(entries['conf/ca.pem']) == deps['ca']['sha256'], 'Agent public CA changed')
    for item in deps['tls']:
        require(support.digest(entries['lib/' + item['name']]) == item['sha256'], 'Agent TLS dependency changed')
    entries['DEPENDENCIES.json'] = (json.dumps(deps, indent=2) + '\n').encode()
    # A new remote-update bootstrap adds reviewed helpers to the full installer.
    # Runtime libraries and the collector CA still come from the verified baseline.
    for path in sorted((root / 'collector').glob('[a-z]*.sh')):
        require(path.is_file() and not path.is_symlink(), 'Linked collector installation script')
        entries[path.name] = path.read_bytes()
    entries['update-public.der'] = (root / 'collector/public/agent-update-public.der').read_bytes()
    for name in entries:
        if name.endswith('.sh'):
            require(entries[name] == (root / 'collector' / name).read_bytes(), 'Agent installation source changed: ' + name)
        elif name in ('README.md', 'INSTALL.md'):
            entries[name] = (root / 'collector' / name).read_bytes()
        if name.startswith('conf/'):
            require(name in ('conf/ca.pem', 'conf/collector.properties.example'), 'Private identity in generic agent')
    # Private signing key, installed identity and pending state never enter this ZIP.
    builder = support.load_builder()
    secrets = builder.private_literals()
    for name, data in entries.items():
        support.scan('agent-package/' + name, data, secrets)
    entries['SHA256SUMS'] = ''.join(support.digest(data) + '  ' + name + '\n' for name, data in sorted(entries.items())).encode()
    public = out / 'build' / ('assessment-collector-' + version + '.zip')
    with zipfile.ZipFile(public, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0));info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100000 | (0o755 if name.endswith('.sh') else 0o644)) << 16
            archive.writestr(info, data)
    support.read_zip(public)
    if full_bundle:
        build_bundle(root, out, public, version, bundle_bootstrap)
    return public


def build_bundle(root, out, agent_path, version, bootstrap=None):
    """Explicit opt-in full artifacts; existing JAR-only release builds stay unchanged."""
    from agent_update_signing import sign_bundle_release
    assets = sign_bundle_release(root, agent_path, version, bootstrap)
    for name, data in assets.items():
        target = out / 'build' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        require(not target.exists(), 'Immutable full bundle output already exists: ' + name)
        target.write_bytes(data)
    return assets


def build_bridge(root, out, origin_zip, bridge_version, target_version, java,
                 origin_sha256, allowed_helper_hashes=None):
    """Compile staged Java-6 bridge sources with a private version override.

    Source files in the checkout are never changed. The baseline package is
    independently pinned; the original collector is embedded for post-ACK
    recovery. No signing private key is used by this compilation function.
    """
    from agent_update_signing import legacy_bridge_profile, validate_jar, VERSION
    require(VERSION.fullmatch(bridge_version) and VERSION.fullmatch(target_version), 'Invalid bridge version')
    original, profile = legacy_bridge_profile(origin_zip, origin_sha256)
    numeric = lambda value: tuple(map(int, value.split('.')))
    require(numeric('1.5.12') <= numeric(profile['fromVersion']) < numeric(bridge_version) < numeric(target_version), 'Invalid bridge ordering')
    helpers = list(allowed_helper_hashes or [profile['fromCollectorSha256']])
    require(profile['fromCollectorSha256'] in helpers and len(helpers) <= 16 and len(set(helpers)) == len(helpers)
            and all(re.fullmatch('[a-f0-9]{64}', value) for value in helpers), 'Bridge helper hashes must be explicit known baselines')
    metadata = {'schema': 1, **profile, 'bridgeVersion': bridge_version, 'targetVersion': target_version,
                'recoveryResource': 'migration/baseline/collector.jar', 'allowedHelperHashes': helpers}
    target = out / 'build' / ('assessment-bridge-' + bridge_version + '-' + profile['fromVersion'] + '.jar')
    target.parent.mkdir(parents=True, exist_ok=True)
    require(not target.exists(), 'Immutable bridge build output already exists')
    with tempfile.TemporaryDirectory(prefix='bridge-compile-', dir=target.parent) as temporary:
        scratch = Path(temporary)
        sources = scratch / 'sources'
        sources.mkdir()
        paths = [*sorted((root / 'shared').glob('*.java')), *[p for p in sorted((root / 'collector').glob('*.java')) if not p.stem.endswith('Test')]]
        original_sources = {path: path.read_bytes() for path in paths}
        staged = []
        for path, data in original_sources.items():
            if path.name == 'AgentTransport.java':
                text, count = re.subn(r'static final String VERSION = "[^"]+";', 'static final String VERSION = "' + bridge_version + '";', data.decode())
                require(count == 1, 'Bridge source version override is ambiguous')
                data = text.encode()
            copy = sources / path.name
            copy.write_bytes(data)
            staged.append(copy)
        classes = scratch / 'classes'
        classes.mkdir()
        compile_product(root, classes, java, staged)
        files = {path.relative_to(classes).as_posix(): path.read_bytes() for path in classes.rglob('*.class')}
        require(files and all(int.from_bytes(data[6:8], 'big') == 50 for data in files.values()), 'Bridge must retain Java-6 bytecode')
        files['META-INF/MANIFEST.MF'] = ('Manifest-Version: 1.0\nImplementation-Version: ' + bridge_version + '\n\n').encode()
        files['migration/bridge.json'] = (json.dumps(metadata, sort_keys=True, separators=(',', ':')) + '\n').encode()
        files['migration/baseline/collector.jar'] = original['collector.jar']
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as jar:
            for name, data in sorted(files.items()):
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100600 << 16
                jar.writestr(info, data)
        payload = stream.getvalue()
        validate_jar(payload, bridge_version)
        require(all(path.read_bytes() == data for path, data in original_sources.items()), 'Checkout source changed during bridge compilation')
        target.write_bytes(payload)
    return target
