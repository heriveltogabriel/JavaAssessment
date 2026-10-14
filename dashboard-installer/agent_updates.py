"""Validate and copy public signed agent update assets; never load a signing key."""
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import struct
import subprocess
import tempfile
import zipfile

VERSION = re.compile(r'(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})')
SHA = re.compile(r'[a-f0-9]{64}')
ASSET = re.compile(r'[A-Za-z0-9_-]+\.sh|lib/[A-Za-z0-9_.-]+\.jar|update-public\.der')
MAX_BUNDLE, MAX_EXPANDED, MAX_FILES = 64 * 1024 * 1024, 128 * 1024 * 1024, 256


def need(condition, message='Invalid signed agent update catalog'):
    if not condition:
        raise ValueError(message)


def digest(content):
    return hashlib.sha256(content).hexdigest()


def version(value):
    need(isinstance(value, str) and VERSION.fullmatch(value), 'Invalid update version')
    return tuple(map(int, value.split('.')))


def _object(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, 'Duplicate signed JSON field')
        result[key] = value
    return result


def document(raw):
    need(isinstance(raw, bytes) and 0 < len(raw) <= 65536, 'Signed metadata size limit exceeded')
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=_object)
    need(isinstance(value, dict), 'Signed metadata must be an object')
    return value


def signature(public, raw, signed):
    need(0 < len(public) <= 1024 and len(signed) == 384, 'Invalid signature or public pin size')
    with tempfile.TemporaryDirectory(prefix='public-agent-verification-') as temporary:
        folder = Path(temporary)
        for name, data in [('public.der', public), ('payload', raw), ('signature', signed)]:
            (folder / name).write_bytes(data)
        key = subprocess.run(['openssl', 'pkey', '-pubin', '-inform', 'DER', '-in', str(folder / 'public.der'),
                              '-text', '-noout'], capture_output=True, timeout=30)
        need(key.returncode == 0 and re.search(br'(?:RSA )?Public-Key: \(3072 bit\)', key.stdout), 'Invalid RSA-3072 public pin')
        pem = subprocess.run(['openssl', 'pkey', '-pubin', '-inform', 'DER', '-in', str(folder / 'public.der'),
                              '-out', str(folder / 'public.pem')], capture_output=True, timeout=30)
        need(pem.returncode == 0, 'Invalid public pin')
        checked = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(folder / 'public.pem'),
                                  '-signature', str(folder / 'signature'), str(folder / 'payload')],
                                 capture_output=True, timeout=30)
        need(checked.returncode == 0, 'Signed agent update signature is invalid')


def filesystem_reader(root):
    root = Path(root).absolute()

    def read(name, maximum):
        need(not name.startswith('/') and '\\' not in name and '..' not in Path(name).parts, 'Unsafe public asset path')
        path = root / name
        for component in [root, *[root.joinpath(*Path(name).parts[:i]) for i in range(1, len(Path(name).parts) + 1)]]:
            need(not component.is_symlink(), 'Public update catalog contains a linked path')
        if not path.exists():
            raise FileNotFoundError(name)
        need(path.is_file() and 0 < path.stat().st_size <= maximum, 'Invalid public update asset size')
        data = path.read_bytes()
        need(len(data) <= maximum, 'Public update asset grew beyond its limit')
        return data
    return read


def archive_reader(archive):
    names = archive.namelist()

    def read(name, maximum):
        count = names.count(name)
        if not count:
            raise FileNotFoundError(name)
        need(count == 1, 'Public update catalog is duplicated')
        item = archive.getinfo(name)
        need(not item.is_dir() and stat.S_IFMT(item.external_attr >> 16) in (0, stat.S_IFREG)
             and not item.flag_bits & 1 and 0 < item.file_size <= maximum, 'Unsafe or oversized public update asset')
        data = archive.read(item)
        need(len(data) == item.file_size, 'Public update asset size mismatch')
        return data
    return read


def optional(read, name, maximum):
    try:
        return read(name, maximum)
    except FileNotFoundError:
        return None


def role(name):
    if not isinstance(name, str) or len(name) > 180 or '..' in name:
        return None
    if name == 'code/collector.jar' or re.fullmatch(r'code/[A-Za-z0-9_-]+\.sh|code/lib/[A-Za-z0-9_.-]+\.jar', name):
        return 'code'
    if name == 'manager/helper.jar' or re.fullmatch(r'manager/lib/[A-Za-z0-9_.-]+\.jar', name):
        return 'manager'
    if name in ('bootstrap/anchor.jar', 'bootstrap/root-trust.sh', 'bootstrap/update-bootstrap.sh') or re.fullmatch(r'bootstrap/lib/[A-Za-z0-9_.-]+\.jar', name):
        return 'bootstrap'
    if re.fullmatch(r'defaults/[A-Za-z0-9_.-]+\.example', name):
        return 'default'
    return None


def bundle_files(metadata, content, target):
    need(set(metadata) == {'schema', 'type', 'format', 'version', 'minUpdater', 'minJava', 'platform', 'bytes', 'sha256', 'files'})
    need(type(metadata['schema']) is int and metadata['schema'] == 1
         and metadata['type'] == 'ASSESSMENT_AGENT_BUNDLE_V1' and metadata['format'] == 'agent-bundle-v1'
         and metadata['version'] == target and type(metadata['minUpdater']) is int and metadata['minUpdater'] == 3
         and type(metadata['minJava']) is int and metadata['minJava'] == 6 and metadata['platform'] == 'linux')
    need(type(metadata['bytes']) is int and 0 < metadata['bytes'] <= MAX_BUNDLE
         and len(content) == metadata['bytes'] and digest(content) == metadata['sha256'], 'Signed bundle hash or size differs')
    files = metadata['files']
    need(isinstance(files, dict) and 0 < len(files) <= MAX_FILES
         and {'code/collector.jar', 'manager/helper.jar'}.issubset(files), 'Signed bundle inventory is incomplete')
    expanded = 0
    for name, entry in files.items():
        need(isinstance(entry, dict) and set(entry) == {'bytes', 'sha256', 'mode', 'role'}
             and role(name) is not None and entry['role'] == role(name)
             and entry['mode'] == ('700' if name.endswith('.sh') else '600')
             and type(entry['bytes']) is int and 0 <= entry['bytes'] <= MAX_EXPANDED
             and isinstance(entry['sha256'], str) and SHA.fullmatch(entry['sha256']), 'Invalid signed bundle file')
        expanded += entry['bytes']
    need(expanded <= MAX_EXPANDED, 'Expanded bundle size limit exceeded')
    bootstrap = {name for name in files if name.startswith('bootstrap/')}
    need(not bootstrap or {'bootstrap/anchor.jar', 'bootstrap/root-trust.sh', 'bootstrap/update-bootstrap.sh'}.issubset(files), 'Initial bootstrap is incomplete')
    need(len(content) >= 22 and content[-22:-18] == b'PK\x05\x06', 'Bundle ZIP has trailing data or comment')
    end = struct.unpack('<4s4H2LH', content[-22:])
    need(end[1:3] == (0, 0) and end[3] == end[4] == len(files) and end[5] + end[6] == len(content) - 22 and end[7] == 0, 'Bundle ZIP directory is invalid')
    result = {}
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        infos = archive.infolist()
        need(len(infos) == len(files) and {i.filename for i in infos} == set(files), 'Bundle ZIP inventory differs')
        cursor = 0
        for item in sorted(infos, key=lambda i: i.header_offset):
            expected = files[item.filename]
            need(item.create_system == 3 and item.extract_version <= 20 and item.flag_bits & ~(8 | 2048) == 0
                 and item.compress_type in (0, 8) and not item.extra and not item.comment and item.external_attr & 0xffff == 0
                 and item.external_attr >> 16 == stat.S_IFREG | int(expected['mode'], 8)
                 and item.file_size == expected['bytes'] and item.header_offset == cursor, 'Bundle ZIP contains unsafe metadata or file type')
            need(cursor + 30 <= end[6], 'Truncated bundle ZIP local header')
            header = struct.unpack('<4s5H3L2H', content[cursor:cursor + 30])
            name = item.filename.encode('ascii')
            need(header[0] == b'PK\x03\x04' and header[1] <= 20 and header[2] == item.flag_bits and header[3] == item.compress_type
                 and header[-2] == len(name) and header[-1] == 0
                 and content[cursor + 30:cursor + 30 + len(name)] == name, 'Bundle ZIP local header differs')
            if item.flag_bits & 8:
                need(header[6:9] == (0, 0, 0), 'Invalid bundle data descriptor header')
            else:
                need(header[6:9] == (item.CRC, item.compress_size, item.file_size), 'Bundle ZIP local size differs')
            cursor += 30 + len(name) + item.compress_size
            if item.flag_bits & 8:
                need(cursor + 16 <= end[6], 'Truncated bundle ZIP data descriptor')
                need(struct.unpack('<4s3L', content[cursor:cursor + 16]) == (b'PK\x07\x08', item.CRC, item.compress_size, item.file_size), 'Bundle ZIP data descriptor differs')
                cursor += 16
            need(cursor <= end[6], 'Bundle ZIP entries overlap its directory')
            data = archive.read(item)
            need(len(data) == expected['bytes'] and digest(data) == expected['sha256'], 'Signed bundle file hash differs')
            result[item.filename] = data
        need(cursor == end[6], 'Bundle ZIP contains unowned bytes')
    jar_version(result['code/collector.jar'], target)
    return result


def jar_version(content, target):
    need(0 < len(content) <= 32 * 1024 * 1024, 'Invalid collector JAR size')
    with zipfile.ZipFile(io.BytesIO(content)) as jar:
        names = jar.namelist()
        need(len(names) == len(set(names)) and len(names) <= 20000
             and sum(i.file_size for i in jar.infolist()) <= 64 * 1024 * 1024, 'Invalid collector JAR inventory')
        versions = [line[len('Implementation-Version: '):] for line in jar.read('META-INF/MANIFEST.MF').decode('utf-8').splitlines() if line.startswith('Implementation-Version: ')]
        need(versions == [target], 'Collector JAR version differs')


def collect_full(version_value, read):
    """Return exactly the current signed route, its bundle and referenced bridges."""
    version(version_value)
    assets = {}

    def get(name, maximum):
        data = read(name, maximum)
        assets[name] = data
        return data

    public = get('dist/agent-update-public.der', 1024)
    prefix = 'dist/agent-update-routes/' + version_value + '/'
    raw, signed = get(prefix + 'routes.json', 65536), get(prefix + 'routes.sig', 512)
    signature(public, raw, signed)
    routes = document(raw)
    need(set(routes) == {'schema', 'type', 'targetVersion', 'minimumAgentVersion', 'bundleManifestSha256', 'routes'}
         and type(routes['schema']) is int and routes['schema'] == 1 and routes['type'] == 'ASSESSMENT_AGENT_ROUTES_V1'
         and routes['targetVersion'] == version_value and routes['minimumAgentVersion'] == '1.5.12'
         and isinstance(routes['routes'], list) and len(routes['routes']) <= 32, 'Invalid signed route schema')
    prefix = 'dist/agent-bundles/' + version_value + '/'
    raw, signed = get(prefix + 'manifest.json', 65536), get(prefix + 'manifest.sig', 512)
    signature(public, raw, signed)
    need(digest(raw) == routes['bundleManifestSha256'], 'Route points to a different bundle manifest')
    bundle = get(prefix + 'bundle.zip', MAX_BUNDLE)
    bundle_files(document(raw), bundle, version_value)
    seen = set()
    for route in routes['routes']:
        need(isinstance(route, dict) and set(route) == {'id', 'fromVersion', 'fromCollectorSha256', 'supportProfileId', 'minUpdater', 'bridgeVersion', 'bridgeManifestSha256'}, 'Invalid bridge route')
        need(all(isinstance(route[key], str) and SHA.fullmatch(route[key]) for key in ('id', 'fromCollectorSha256', 'supportProfileId', 'bridgeManifestSha256'))
             and route['id'] not in seen and type(route['minUpdater']) is int and route['minUpdater'] in (1, 2)
             and version('1.5.12') <= version(route['fromVersion']) < version(route['bridgeVersion']) < version(version_value), 'Invalid bridge profile or version')
        seen.add(route['id'])
        prefix = 'dist/agent-bridges/' + route['bridgeVersion'] + '/' + route['id'] + '/'
        raw, signed = get(prefix + 'manifest.json', 65536), get(prefix + 'manifest.sig', 512)
        signature(public, raw, signed)
        need(digest(raw) == route['id'] == route['bridgeManifestSha256'], 'Bridge manifest identity differs')
        manifest = document(raw)
        need(set(manifest) == {'schema', 'type', 'format', 'version', 'minUpdater', 'bytes', 'sha256', 'requiredFiles'}
             and type(manifest['schema']) is int and manifest['schema'] == 1 and manifest['type'] == 'ASSESSMENT_AGENT_UPDATE_V1'
             and manifest['format'] == 'collector-jar-v1' and manifest['version'] == route['bridgeVersion']
             and type(manifest['minUpdater']) is int and manifest['minUpdater'] == route['minUpdater'], 'Invalid original bridge wire format')
        required = manifest['requiredFiles']
        need(isinstance(required, dict) and 0 < len(required) <= 128 and 'update-public.der' in required
             and all(ASSET.fullmatch(name) and isinstance(value, str) and SHA.fullmatch(value) for name, value in required.items()), 'Invalid bridge support profile')
        need(required['update-public.der'] == digest(public), 'Bridge trust pin differs from catalog')
        need(digest(json.dumps(required, sort_keys=True, separators=(',', ':')).encode('ascii')) == route['supportProfileId'], 'Bridge support inventory differs from its profile')
        payload = get(prefix + 'collector.jar', 32 * 1024 * 1024)
        need(type(manifest['bytes']) is int and manifest['bytes'] == len(payload) and manifest['sha256'] == digest(payload), 'Bridge payload hash differs')
        jar_version(payload, route['bridgeVersion'])
        with zipfile.ZipFile(io.BytesIO(payload)) as jar:
            metadata = document(jar.read('migration/bridge.json'))
            need(metadata.get('schema') == 1 and all(metadata.get(k) == route[k] for k in ('fromVersion', 'fromCollectorSha256', 'supportProfileId', 'bridgeVersion'))
                 and metadata.get('targetVersion') == version_value and metadata.get('recoveryResource') == 'migration/baseline/collector.jar', 'Bridge recovery metadata differs')
            baseline = jar.getinfo('migration/baseline/collector.jar')
            need(0 < baseline.file_size <= 32 * 1024 * 1024 and digest(jar.read(baseline)) == route['fromCollectorSha256'], 'Bridge original recovery baseline differs')
    return assets


def generic_entries(content):
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        names = archive.namelist()
        need(len(names) == len(set(names)) and len(names) <= 512
             and sum(i.file_size for i in archive.infolist()) <= MAX_EXPANDED, 'Generic collector inventory limit exceeded')
        for item in archive.infolist():
            need(not item.filename.startswith('/') and '\\' not in item.filename and '..' not in Path(item.filename).parts
                 and not item.is_dir() and stat.S_IFMT(item.external_attr >> 16) in (0, stat.S_IFREG)
                 and not item.flag_bits & 1, 'Unsafe generic collector entry')
        entries = {name: archive.read(name) for name in names}
    expected = {}
    for line in entries.get('SHA256SUMS', b'').decode('ascii').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  (.+)', line)
        need(match and match.group(2) not in expected, 'Invalid generic collector checksum inventory')
        expected[match.group(2)] = match.group(1)
    need(set(expected) == set(entries) - {'SHA256SUMS'} and all(digest(entries[name]) == value for name, value in expected.items()), 'Generic collector checksum inventory differs')
    return entries


def embed_full(collector, assets, target):
    """Preserve every signed executable byte; embed only the already signed trio."""
    entries = generic_entries(collector)
    need(entries.get('update-public.der') == assets['dist/agent-update-public.der'], 'Generic collector trust pin differs')
    prefix = 'dist/agent-bundles/' + target + '/'
    content = bundle_files(document(assets[prefix + 'manifest.json']), assets[prefix + 'bundle.zip'], target)
    need({'bootstrap/anchor.jar', 'bootstrap/root-trust.sh', 'bootstrap/update-bootstrap.sh'}.issubset(content), 'Fresh full installer requires its immutable bootstrap')
    need(content['code/collector.jar'] == entries.get('collector.jar'), 'Generic collector differs from signed bundle')
    for name, data in content.items():
        if name.startswith('code/'):
            need(entries.get(name[len('code/'):]) == data, 'Generic executable differs from signed bundle')
    expected = {'full-update/' + name: assets[prefix + name] for name in ('manifest.json', 'manifest.sig', 'bundle.zip')}
    embedded = {name for name in entries if name.startswith('full-update/')}
    need(not embedded or embedded == set(expected) and all(entries[name] == expected[name] for name in embedded), 'Embedded full update differs from signed catalog')
    if embedded:
        return collector
    entries.update(expected)
    entries['SHA256SUMS'] = ''.join(digest(data) + '  ' + name + '\n' for name, data in sorted(entries.items()) if name != 'SHA256SUMS').encode('ascii')
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            item = zipfile.ZipInfo(name)
            item.compress_type = zipfile.ZIP_DEFLATED
            item.create_system = 3
            item.external_attr = (stat.S_IFREG | (0o700 if name.endswith('.sh') else 0o600)) << 16
            archive.writestr(item, data)
    return buffer.getvalue()


def has_full(read, target):
    markers = [('agent-update-routes', 'routes.json', 65536), ('agent-update-routes', 'routes.sig', 512),
               ('agent-bundles', 'manifest.json', 65536), ('agent-bundles', 'manifest.sig', 512),
               ('agent-bundles', 'bundle.zip', MAX_BUNDLE)]
    return any(optional(read, 'dist/' + folder + '/' + target + '/' + name, maximum) is not None
               for folder, name, maximum in markers)


def classic_assets(target, read, collector):
    """Verify the optional classic catalog against this exact immutable installer."""
    entries = generic_entries(collector)
    prefix = 'dist/agent-updates/' + target + '/'
    payload = optional(read, prefix + 'collector.jar', 32 * 1024 * 1024)
    raw = optional(read, prefix + 'manifest.json', 32768)
    signed = optional(read, prefix + 'manifest.sig', 512)
    if payload is None and raw is None and signed is None:
        return {}
    need(payload is not None and raw is not None and signed is not None, 'Public update catalog is incomplete')
    public = read('dist/agent-update-public.der', 1024)
    signature(public, raw, signed)
    metadata = document(raw)
    need(set(metadata) == {'schema', 'type', 'format', 'version', 'minUpdater', 'bytes', 'sha256', 'requiredFiles'}
         and type(metadata['schema']) is int and metadata['schema'] == 1 and metadata['type'] == 'ASSESSMENT_AGENT_UPDATE_V1'
         and metadata['format'] == 'collector-jar-v1' and metadata['version'] == target
         and type(metadata['minUpdater']) is int and metadata['minUpdater'] in (1, 2)
         and type(metadata['bytes']) is int and metadata['bytes'] == len(payload) and metadata['sha256'] == digest(payload), 'Classic update differs from target release')
    required = {name: digest(data) for name, data in entries.items() if ASSET.fullmatch(name)}
    need(required and metadata['requiredFiles'] == required and entries.get('update-public.der') == public
         and entries.get('collector.jar') == payload, 'Classic update differs from immutable collector assets')
    jar_version(payload, target)
    return {'dist/agent-update-public.der': public, prefix + 'manifest.json': raw,
            prefix + 'manifest.sig': signed, prefix + 'collector.jar': payload}


def distribution_assets(root, target, require_embedded=True):
    """Validate an unpacked dashboard installer before any service or identity mutation."""
    version(target)
    read = filesystem_reader(root)
    collector = read('dist/assessment-collector.zip', MAX_EXPANDED)
    entries = generic_entries(collector)
    assets = classic_assets(target, read, collector)
    if has_full(read, target):
        assets.update(collect_full(target, read))
        complete = embed_full(collector, assets, target)
        need(not require_embedded or complete == collector, 'Fresh installer is missing its embedded full update')
    else:
        need(not any(name.startswith('full-update/') for name in entries) and 'update-bootstrap.sh' not in entries,
             'V3 collector is missing its complete public update catalog')
    need('update-public.der' not in entries or assets, 'Signed collector is missing its public update catalog')
    return assets
