#!/usr/bin/env python3
"""One-time, operator-run repair of the identified laboratory migration only."""
import argparse
import importlib.util
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
TARGET = '2.0.9'
BRIDGE = '2.0.8'
CASE_PATH = PROJECT / 'output/agent-canaries/recovery-case-2.0.9.json'


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def load_case(path):
    info, parent = path.lstat(), path.parent.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == os.getuid()
            and stat.S_IMODE(info.st_mode) == 0o600 and stat.S_ISDIR(parent.st_mode)
            and parent.st_uid == os.getuid() and stat.S_IMODE(parent.st_mode) == 0o700, 'CASE_MUST_BE_PRIVATE')
    require(info.st_size < 8192, 'CASE_LIMIT')
    case = json.loads(path.read_text())
    common = {'schema', 'host', 'operation', 'prepared', 'bridgeSha256', 'helperSha256', 'scriptSha256'}
    require(type(case.get('schema')) is int and case['schema'] in (1, 2), 'INVALID_CASE_SCHEMA')
    require(set(case) == (common if case['schema'] == 1 else common | {'target', 'bridge', 'bootstrapState', 'identitySha256'}),
            'INVALID_CASE_SCHEMA')
    require(str(ipaddress.ip_address(case['host'])) == case['host']
            and re.fullmatch(r'[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}', case['operation'])
            and re.fullmatch(r'prepared-[0-9]{1,2}', case['prepared'])
            and all(re.fullmatch('[a-f0-9]{64}', case[name]) for name in ('bridgeSha256', 'helperSha256', 'scriptSha256')),
            'INVALID_CASE_VALUES')
    if case['schema'] == 2:
        require(case['target'] == '2.0.11' and case['bridge'] == '2.0.10' and case['bootstrapState'] == 'ABSENT'
                and set(case['identitySha256']) == {'config', 'installationId', 'javaPath', 'ca', 'publicKey'}
                and all(re.fullmatch('[a-f0-9]{64}', value) for value in case['identitySha256'].values()), 'INVALID_CASE_VALUES')
    return case


def public_contract(release, case):
    """Read public final ZIPs and verify with only the already-pinned DER key."""
    if str(PROJECT / 'ops') not in sys.path:
        sys.path.insert(0, str(PROJECT / 'ops'))
    spec = importlib.util.spec_from_file_location('candidate_verifier', PROJECT / 'ops/verify-full-agent-candidate.py')
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    receipt = verifier.verify(release, release.parent,
                              PROJECT / 'docs/validation/agent-migration-baselines.json',
                              PROJECT / 'collector/public/agent-update-public.der')
    target, bridge = (case['target'], case['bridge']) if case['schema'] == 2 else (TARGET, BRIDGE)
    require(receipt['result'] == 'PUBLIC_CANDIDATE_VERIFIED' and receipt['targetVersion'] == target
            and receipt['bridgeVersion'] == bridge and receipt['immutableBootstrapVerified'] is True, 'WRONG_CANDIDATE')
    with zipfile.ZipFile(release / 'packages' / ('assessment-dashboard-runtime-' + target + '.zip')) as archive:
        runtime = {name: archive.read(name) for name in archive.namelist() if name.startswith('dist/')}
    bundle_prefix = 'dist/agent-bundles/' + target + '/'
    header_raw = runtime[bundle_prefix + 'manifest.json']
    header = verifier.parse(header_raw)
    with zipfile.ZipFile(io.BytesIO(runtime[bundle_prefix + 'bundle.zip'])) as archive:
        members = {name: archive.read(name) for name in archive.namelist()}
    routes = verifier.parse(runtime['dist/agent-update-routes/' + target + '/routes.json'])
    route = next(row for row in routes['routes'] if row['fromVersion'] == '1.5.12')
    bridge_jar = runtime['dist/agent-bridges/' + bridge + '/' + route['id'] + '/collector.jar']
    require(verifier.sha(bridge_jar) == case['bridgeSha256'], 'BRIDGE_RECEIPT_DIFFERS_FROM_IDENTIFIED_HOST')
    with zipfile.ZipFile(io.BytesIO(bridge_jar)) as archive:
        migration = verifier.parse(archive.read('migration/bridge.json'))
    require(case['helperSha256'] in migration['allowedHelperHashes'] and verifier.sha(members['bootstrap/update-bootstrap.sh']) == case['scriptSha256'],
            'BOOTSTRAP_OR_HELPER_RECEIPT_DIFFERS')
    if case['schema'] == 2:
        require(case['helperSha256'] == migration['fromCollectorSha256'], 'ORIGINAL_HELPER_REQUIRED_FOR_ABSENT_BOOTSTRAP')
    template = members['bootstrap/update-bootstrap.sh'].split(b"<<'UNIT'\n", 1)[1].split(b'\nUNIT\n', 1)[0] + b'\n'
    boot = {name[10:]: row['sha256'] for name, row in header['files'].items() if name.startswith('bootstrap/')}
    boot.update({'bundle-manifest.json': verifier.sha(header_raw),
                 'bundle-manifest.sig': verifier.sha(runtime[bundle_prefix + 'manifest.sig'])})
    return {'host': case['host'], 'operation': case['operation'], 'target': target, 'bridge': bridge, 'prepared': case['prepared'],
            'bootstrapState': case.get('bootstrapState', 'SEALED'), 'identitySha256': case.get('identitySha256'),
            'bridgeSha256': case['bridgeSha256'], 'helperSha256': case['helperSha256'], 'publicSha256': receipt['publicKeySha256'],
            'manifestSha256': verifier.sha(header_raw), 'signatureSha256': verifier.sha(runtime[bundle_prefix + 'manifest.sig']),
            'bundleSha256': header['sha256'], 'bundleBytes': header['bytes'], 'stage': header['files'], 'boot': boot,
            'templateSha256': verifier.sha(template), 'origin': migration}


REMOTE = r'''
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time

ROOT = Path('/opt/java-assessment-collector')
HOME = Path('/opt/java-assessment-update')
UNIT = Path('/etc/systemd/system/java-assessment-recovery.service')
CHECKPOINT = {}
HELPER_CGROUPS = (Path('/sys/fs/cgroup/system.slice/java-assessment-update.service'),
                 Path('/sys/fs/cgroup/systemd/system.slice/java-assessment-update.service'))

def need(ok, code):
    if not ok:
        raise RuntimeError(code)

def trusted(path, private=False, directory=False):
    path = Path(path)
    for item in (path,) + tuple(path.parents):
        info = os.lstat(str(item))
        need(info.st_uid == 0 and not stat.S_ISLNK(info.st_mode) and not info.st_mode & 0o022, 'UNTRUSTED_PATH')
        if item == path and not directory:
            need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, 'UNTRUSTED_FILE')
            if private:
                need(stat.S_IMODE(info.st_mode) in (0o400, 0o600), 'NON_PRIVATE_FILE')
        else:
            need(stat.S_ISDIR(info.st_mode), 'UNTRUSTED_DIRECTORY')
    return path

def digest(path, private=False):
    trusted(path, private=private)
    result = hashlib.sha256()
    with open(str(path), 'rb') as source:
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            result.update(chunk)
    return result.hexdigest()

def document(path, limit=65536):
    trusted(path, private=True)
    need(path.stat().st_size <= limit, 'JSON_LIMIT')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            need(key not in result, 'DUPLICATE_JSON_MEMBER')
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique)

def absent(path, code):
    need(not os.path.lexists(str(path)), code)

def properties():
    path = ROOT / 'conf/collector.properties'
    trusted(path, private=True)
    need(path.stat().st_size <= 65536, 'CONFIG_LIMIT')
    text = path.read_bytes().decode('latin-1')
    ca = re.findall(r'^\s*tlsCaFile\s*[=:]\s*(/[-A-Za-z0-9_./]+)\s*$', text, re.M)
    host = re.findall(r'^\s*hostId\s*[=:]\s*(host-[a-f0-9-]{36})\s*$', text, re.M)
    need(len(ca) == len(host) == 1 and '..' not in Path(ca[0]).parts, 'UNSUPPORTED_CONFIG_FORMAT')
    return Path(ca[0]), host[0]

def snapshot_identity():
    ca, host = properties()
    return {'config': digest(ROOT / 'conf/collector.properties', True),
            'installationId': digest(ROOT / 'conf/installation-id', True),
            'javaPath': digest(ROOT / 'conf/java.path', True), 'ca': digest(ca),
            'publicKey': digest(ROOT / 'update-public.der'), 'hostIdSha256': hashlib.sha256(host.encode()).hexdigest()}

def service(name):
    need(name in ('java-assessment-collector.service', 'java-assessment-collector.timer',
                  'java-assessment-update.service', 'java-assessment-recovery.service',
                  'java-assessment-bootstrap-' + EXPECTED['operation'] + '.service'), 'INVALID_SERVICE')
    result = subprocess.run(['/usr/bin/systemctl', 'show', name, '--all', '--no-pager', '-p', 'LoadState', '-p', 'ActiveState',
                             '-p', 'SubState', '-p', 'MainPID', '-p', 'ProtectSystem', '-p', 'ExecMainStatus',
                             '-p', 'Transient', '-p', 'FragmentPath', '-p', 'ControlPID', '-p', 'Job',
                             '-p', 'ControlGroup', '-p', 'KillMode'],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
    need(len(result.stdout) < 4096, 'SERVICE_DIAGNOSTIC_FAILED')
    fields = {}
    for line in result.stdout.decode('ascii').splitlines():
        need('=' in line, 'INVALID_SERVICE_DIAGNOSTIC')
        key, value = line.split('=', 1)
        need(key not in fields, 'DUPLICATE_SERVICE_DIAGNOSTIC')
        fields[key] = value
    need({'LoadState', 'ActiveState', 'SubState', 'MainPID', 'ControlPID', 'Job', 'ControlGroup', 'KillMode'} <= set(fields),
         'INCOMPLETE_SERVICE_DIAGNOSTIC')
    need(result.returncode == 0 or result.returncode in (1, 4) and fields.get('LoadState') == 'not-found'
         and fields.get('ActiveState') == 'inactive' and fields.get('MainPID', '0') == '0', 'SERVICE_DIAGNOSTIC_FAILED')
    need(all(re.fullmatch('[0-9]{1,10}', fields[key]) for key in ('MainPID', 'ControlPID')), 'INVALID_SERVICE_PID')
    need(fields['Job'] == '' or re.fullmatch('[0-9]{1,12}(?: /org/freedesktop/systemd1/job/[0-9]{1,12})?', fields['Job']),
         'INVALID_SERVICE_JOB')
    return fields

def helper_cgroups_empty():
    for path in HELPER_CGROUPS:
        if not os.path.lexists(str(path)):
            continue
        trusted(path, directory=True)
        # No recursive process inspection, arguments or arbitrary cgroup paths.
        need(not any(item.is_symlink() or item.is_dir() for item in path.iterdir()), 'HELPER_CGROUP_HAS_DESCENDANTS')
        processes = path / 'cgroup.procs'
        trusted(processes)
        need(processes.stat().st_size <= 65536, 'HELPER_CGROUP_LIMIT')
        with processes.open('rb') as source:
            raw = source.read(65537)
        need(len(raw) <= 65536 and raw.strip() == b'', 'HELPER_CGROUP_NOT_EMPTY')

def helper_inactive(fields):
    return (fields.get('LoadState') == 'loaded' and fields.get('ActiveState') == 'inactive'
            and fields.get('SubState') == 'dead' and fields.get('MainPID') == '0'
            and fields.get('ControlPID') == '0' and fields.get('Job') in ('', '0')
            and fields.get('ControlGroup') == '' and fields.get('KillMode') == 'control-group')

def helper_active(fields):
    return (fields.get('LoadState') == 'loaded' and fields.get('ActiveState') == 'active' and fields.get('SubState') == 'running'
            and int(fields.get('MainPID', '0')) > 0 and fields.get('ControlPID') == '0'
            and fields.get('Job') in ('', '0') and fields.get('KillMode') == 'control-group'
            and fields.get('ControlGroup') == '/system.slice/java-assessment-update.service')

def recovery_absent(fields):
    return (fields.get('LoadState') == 'not-found' and fields.get('ActiveState') == 'inactive'
            and fields.get('MainPID') == '0' and fields.get('ControlPID') == '0'
            and fields.get('Job') in ('', '0') and fields.get('ControlGroup') == '')

def helper_stop_evidence(fields):
    for key in ('ActiveState', 'SubState'):
        value = fields.get(key, '')
        if re.fullmatch('[a-z-]{1,32}', value):
            print('RESUME_HELPER_STOP_' + key.upper() + '=' + value.upper(), flush=True)
    for key in ('MainPID', 'ControlPID'):
        value = fields.get(key, '')
        if re.fullmatch('[0-9]{1,10}', value):
            print('RESUME_HELPER_STOP_' + key.upper() + '=' + value, flush=True)
    print('RESUME_HELPER_STOP_JOB=' + ('NONE' if fields.get('Job') in ('', '0') else 'PENDING_OR_UNKNOWN'), flush=True)

def validate_journal(journal):
    need(journal.get('schema') == 1 and journal.get('operationId') == EXPECTED['operation']
         and journal.get('phase') == 'PREPARED' and journal.get('targetVersion') == EXPECTED['target']
         and journal.get('previousVersion') == '1.5.12' and journal.get('previousPointer') is None
         and journal.get('preparedDirectory') == EXPECTED['prepared']
         and journal.get('bundleSha256') == EXPECTED['bundleSha256']
         and journal.get('manifestSha256') == EXPECTED['manifestSha256'], 'WRONG_OR_CHANGED_TRANSACTION')

def original_support():
    baseline = EXPECTED['origin']
    for name, expected in baseline['supportFiles'].items():
        need(digest(ROOT / name) == expected, 'ORIGINAL_SUPPORT_CHANGED')
    need(digest(ROOT / 'collector.jar') == EXPECTED['bridgeSha256']
         and digest(HOME / 'helper.jar') == EXPECTED['helperSha256']
         and EXPECTED['helperSha256'] == baseline['fromCollectorSha256'], 'ORIGINAL_HELPER_OR_BRIDGE_CHANGED')

def guard_absent_bootstrap():
    absent(HOME / 'bootstrap', 'BOOTSTRAP_ALREADY_EXISTS')
    absent(HOME / 'state/origin', 'ORIGIN_BACKUP_ALREADY_EXISTS')
    for name, value in (('root-service-mode', 'system-root'), ('update-capability', 'update-v1')):
        marker = ROOT / 'conf' / name
        trusted(marker, private=True)
        need(marker.stat().st_size <= 64 and marker.read_text().strip() == value, 'ORIGINAL_INSTALL_MODE_CHANGED')
    original_support()
    transient_name = 'java-assessment-bootstrap-' + EXPECTED['operation'] + '.service'
    transient = service(transient_name)
    need(transient.get('LoadState') == 'loaded' and transient.get('ActiveState') == 'failed'
         and transient.get('SubState') == 'failed' and transient.get('MainPID') == '0'
         and transient.get('ExecMainStatus') == '1' and transient.get('ProtectSystem') == 'no'
         and transient.get('Transient') == 'yes'
         and transient.get('FragmentPath') == '/run/systemd/transient/' + transient_name,
         'IDENTIFIED_FAILED_TRANSIENT_CHANGED')
    recovery = service('java-assessment-recovery.service')
    need(recovery_absent(recovery), 'RECOVERY_ALREADY_KNOWN_OR_ACTIVE')
    helper = service('java-assessment-update.service')
    need(helper_active(helper) or helper_inactive(helper), 'ORIGINAL_HELPER_STATE_UNCONFIRMED')
    CHECKPOINT['helperWasActive'] = helper_active(helper)
    if helper_inactive(helper):
        helper_cgroups_empty()

def pause_original_helper(before):
    # No external flock: the signed bootstrap obtains its own lifecycle lock.
    helper = service('java-assessment-update.service')
    was_inactive = helper_inactive(helper)
    if not was_inactive:
        need(CHECKPOINT.get('helperWasActive') and helper_active(helper), 'ORIGINAL_HELPER_STATE_CHANGED')
        result = subprocess.run(['/usr/bin/systemctl', 'stop', 'java-assessment-update.service'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        need(result.returncode == 0, 'ORIGINAL_HELPER_STOP_FAILED')
        for attempt in range(31):
            helper = service('java-assessment-update.service')
            if helper_inactive(helper):
                break
            if attempt < 30:
                time.sleep(1)
        if not helper_inactive(helper):
            helper_stop_evidence(helper)
            raise RuntimeError('ORIGINAL_HELPER_DID_NOT_STOP')
    helper_cgroups_empty()
    for path in (HOME / 'journal.json', ROOT / 'collector-spool/update-request.json'):
        absent(path, 'LEGACY_OPERATION_STARTED_DURING_STOP')
    journal = document(HOME / 'state/transaction.json')
    validate_journal(journal)
    need(digest(HOME / 'state/transaction.json', True) == CHECKPOINT['journalSha256']
         and snapshot_identity() == before, 'STATE_CHANGED_DURING_HELPER_STOP')
    original_support()
    print('RESUME_LEGACY_HELPER=' + ('ALREADY_INACTIVE' if was_inactive else 'STOPPED'), flush=True)

def clear_restore_stop_marker():
    marker = HOME / 'stop'
    if not os.path.lexists(str(marker)):
        return
    trusted(marker, private=True)
    known = os.lstat(str(marker))
    need(stat.S_IMODE(known.st_mode) == 0o600 and known.st_size == 0, 'UNEXPECTED_HELPER_STOP_MARKER')
    parent = os.open(str(trusted(HOME, directory=True)), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    descriptor = None
    try:
        descriptor = os.open('stop', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        opened = os.fstat(descriptor)
        current = os.lstat('stop', dir_fd=parent)
        need(stat.S_ISREG(opened.st_mode) and opened.st_uid == 0 and opened.st_nlink == 1
             and stat.S_IMODE(opened.st_mode) == 0o600 and opened.st_size == 0
             and (known.st_dev, known.st_ino) == (opened.st_dev, opened.st_ino) == (current.st_dev, current.st_ino)
             and stat.S_ISREG(current.st_mode) and current.st_uid == 0 and current.st_nlink == 1
             and stat.S_IMODE(current.st_mode) == 0o600 and current.st_size == 0, 'HELPER_STOP_MARKER_CHANGED')
        os.unlink('stop', dir_fd=parent)
        os.fsync(parent)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent)

def restore_original_helper(before):
    if not CHECKPOINT.get('helperWasActive'):
        print('RESUME_HELPER_RESTORE=NOT_REQUIRED', flush=True)
        return
    try:
        # A created recovery unit is already handoff evidence, even if it has
        # not become active yet. Never restart the old helper in that state.
        if os.path.lexists(str(UNIT)):
            print('RESUME_HELPER_RESTORE=SKIPPED_HANDOFF', flush=True)
            return
        recovery = service('java-assessment-recovery.service')
        need(recovery_absent(recovery), 'RECOVERY_MAY_HAVE_STARTED')
        need(snapshot_identity() == before, 'IDENTITY_CHANGED')
        journal = document(HOME / 'state/transaction.json')
        validate_journal(journal)
        need(digest(HOME / 'state/transaction.json', True) == CHECKPOINT['journalSha256'], 'JOURNAL_CHANGED')
        original_support()
        for path in (HOME / 'journal.json', ROOT / 'collector-spool/update-request.json', ROOT / 'current.json'):
            absent(path, 'OTHER_OPERATION_STARTED')
        helper = service('java-assessment-update.service')
        if helper_active(helper):
            print('RESUME_HELPER_RESTORE=ALREADY_ACTIVE', flush=True)
            return
        need(helper_inactive(helper), 'HELPER_STATE_UNKNOWN')
        helper_cgroups_empty()
        clear_restore_stop_marker()
        restored = subprocess.run(['/usr/bin/systemctl', 'start', 'java-assessment-update.service'],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        helper = service('java-assessment-update.service')
        need(restored.returncode == 0 and helper_active(helper), 'HELPER_RESTART_NOT_CONFIRMED')
        print('RESUME_HELPER_RESTORE=RESTORED', flush=True)
    except Exception:
        print('RESUME_HELPER_RESTORE=NOT_CONFIRMED_UNSAFE_TO_FORCE', flush=True)

def verify_state():
    need(os.geteuid() == 0 and sys.platform.startswith('linux'), 'ROOT_LINUX_REQUIRED')
    for name in ('LD_PRELOAD', 'LD_LIBRARY_PATH', 'LD_AUDIT', 'LD_DEBUG', 'JAVA_TOOL_OPTIONS',
                 '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'ENV', 'BASH_ENV', 'PYTHONPATH'):
        os.environ.pop(name, None)
    os.environ['PATH'] = '/usr/sbin:/usr/bin:/sbin:/bin'
    os.umask(0o077)
    trusted(ROOT, directory=True); trusted(HOME, directory=True)
    journal = document(HOME / 'state/transaction.json')
    validate_journal(journal)
    for path in (HOME / 'journal.json', ROOT / 'collector-spool/update-request.json',
                 ROOT / 'collector-spool/uninstall-request.json', Path('/opt/java-assessment-uninstall/request.json'),
                 Path('/opt/java-assessment-uninstall/claim.json')):
        absent(path, 'OTHER_OPERATION_PENDING')
    absent(ROOT / 'current.json', 'ALREADY_PROMOTED')
    absent(UNIT, 'RECOVERY_UNIT_ALREADY_EXISTS')
    absent(ROOT / 'collector-spool/.poll-lock', 'COLLECTOR_CYCLE_ACTIVE_OR_STALE_LOCK')
    collector = service('java-assessment-collector.service')
    need(collector.get('LoadState') == 'loaded' and collector.get('MainPID') == '0'
         and collector.get('ActiveState') in ('inactive', 'failed') and collector.get('ProtectSystem') == 'full', 'COLLECTOR_NOT_IDLE_OR_UNEXPECTED_UNIT')
    need(digest(ROOT / 'collector.jar') == EXPECTED['bridgeSha256']
         and digest(HOME / 'helper.jar') == EXPECTED['helperSha256']
         and digest(ROOT / 'update-public.der') == EXPECTED['publicSha256'], 'INSTALLED_ORIGIN_CHANGED')
    trusted(HOME / 'scheduler', private=True)
    need((HOME / 'scheduler').read_text().strip() == 'systemd', 'SYSTEMD_REQUIRED')
    tx = HOME / 'transactions' / EXPECTED['operation']
    need(digest(tx / 'manifest.json', True) == EXPECTED['manifestSha256']
         and digest(tx / 'manifest.sig', True) == EXPECTED['signatureSha256']
         and (tx / 'bundle.zip').stat().st_size == EXPECTED['bundleBytes']
         and digest(tx / 'bundle.zip', True) == EXPECTED['bundleSha256'], 'TRANSACTION_ASSETS_CHANGED')
    pem = subprocess.run(['/usr/bin/openssl', 'pkey', '-pubin', '-inform', 'DER', '-in', str(ROOT / 'update-public.der'), '-outform', 'PEM'],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
    need(pem.returncode == 0 and 300 < len(pem.stdout) < 2048, 'PUBLIC_KEY_READ_FAILED')
    verified = subprocess.run(['/usr/bin/openssl', 'dgst', '-sha256', '-verify', '/dev/stdin',
                               '-signature', str(tx / 'manifest.sig'), str(tx / 'manifest.json')],
                              input=pem.stdout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
    need(verified.returncode == 0, 'BUNDLE_SIGNATURE_INVALID')
    stage = tx / EXPECTED['prepared']
    for name, item in EXPECTED['stage'].items():
        need(digest(stage / name) == item['sha256']
             and stat.S_IMODE((stage / name).stat().st_mode) == int(item['mode'], 8), 'STAGED_FILE_CHANGED')
    actual = set()
    for parent, directories, files in os.walk(str(stage), followlinks=False):
        trusted(Path(parent), directory=True)
        for name in directories:
            trusted(Path(parent) / name, directory=True)
        actual.update((Path(parent) / name).relative_to(stage).as_posix() for name in files)
    need(actual == set(EXPECTED['stage']), 'STAGED_INVENTORY_CHANGED')
    if EXPECTED.get('bootstrapState', 'SEALED') == 'ABSENT':
        guard_absent_bootstrap()
    else:
        boot = HOME / 'bootstrap'
        for name, expected in EXPECTED['boot'].items():
            need(digest(boot / name, True) == expected, 'BOOTSTRAP_FILE_CHANGED')
        seal = document(boot / 'bootstrap-owned.json')
        need(seal == {'schema': 1, 'files': EXPECTED['boot']}
             and digest(boot / 'recovery.service', True) == EXPECTED['templateSha256'], 'BOOTSTRAP_SEAL_OR_TEMPLATE_CHANGED')
        origin = HOME / 'state/origin'
        stored = document(origin / 'origin.json')
        baseline = EXPECTED['origin']
        need(stored.get('schema') == 1 and stored.get('version') == '1.5.12'
             and stored.get('supportProfileId') == baseline['supportProfileId']
             and stored.get('helperSha256') == EXPECTED['helperSha256'], 'ORIGIN_RECEIPT_CHANGED')
        required = dict(baseline['supportFiles']); required['collector.jar'] = baseline['fromCollectorSha256']
        files = stored.get('files', {})
        allowed = set(required) | {'conf/root-service-mode', 'conf/update-capability', 'conf/uninstall-capability'}
        need(set(required) <= set(files) <= allowed and all(files[name] == value for name, value in required.items()), 'ORIGIN_INVENTORY_CHANGED')
        for name, expected in files.items():
            need(digest(origin / 'files' / name) == expected, 'ORIGIN_BACKUP_CHANGED')
        need(digest(origin / 'helper.jar', True) == EXPECTED['helperSha256'], 'ORIGIN_HELPER_CHANGED')
    ca, host = properties()
    need(journal.get('hostId') == host, 'HOST_ID_CHANGED')
    before = snapshot_identity()
    need(before['config'] == journal.get('configSha256') and before['javaPath'] == journal.get('javaPathSha256')
         and before['ca'] == journal.get('caSha256') and before['publicKey'] == journal.get('pinSha256'), 'PRESERVED_IDENTITY_CHANGED')
    if EXPECTED.get('identitySha256') is not None:
        need(all(before[name] == value for name, value in EXPECTED['identitySha256'].items()), 'IDENTIFIED_HOST_IDENTITY_CHANGED')
    CHECKPOINT['journalSha256'] = digest(HOME / 'state/transaction.json', True)
    return stage / 'bootstrap/update-bootstrap.sh', before

def evidence(value, prefix):
    labels = {'config': 'CONFIG', 'installationId': 'INSTALLATION_ID', 'javaPath': 'JAVA_PATH',
              'ca': 'CA', 'publicKey': 'PUBLIC_KEY', 'hostIdSha256': 'HOST_ID_HASH'}
    for name, label in labels.items():
        print('RESUME_' + prefix + '_' + label + '=' + value[name], flush=True)

def execute_bootstrap(script):
    child = subprocess.Popen(['/bin/sh', str(script), 'install-bootstrap', EXPECTED['operation']],
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
    try:
        return child.wait(timeout=120)
    except subprocess.TimeoutExpired:
        os.killpg(child.pid, signal.SIGTERM)
        try:
            child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL); child.wait()
        raise RuntimeError('BOOTSTRAP_TIMEOUT_NO_AUTOMATIC_RETRY')

def remote_main():
    script, before = verify_state()
    evidence(before, 'BEFORE')
    # No shell expression, replacement artifact, service override or journal edit.
    try:
        if EXPECTED.get('bootstrapState') == 'ABSENT':
            pause_original_helper(before)
        code = execute_bootstrap(script)
        need(code == 0, 'BOOTSTRAP_FAILED_NO_AUTOMATIC_RETRY')
    except Exception:
        if EXPECTED.get('bootstrapState') == 'ABSENT':
            restore_original_helper(before)
        raise
    finally:
        after = snapshot_identity()
        evidence(after, 'AFTER')
    need(before == after, 'IDENTITY_CHANGED_STOP_AND_REVIEW_AUDIT')
    recovery = service('java-assessment-recovery.service')
    for attempt in range(30):
        if recovery.get('ActiveState') == 'active' and int(recovery.get('MainPID', '0')) > 0:
            break
        time.sleep(1)
        recovery = service('java-assessment-recovery.service')
    need(recovery.get('ActiveState') == 'active' and int(recovery.get('MainPID', '0')) > 0, 'RECOVERY_NOT_CONFIRMED_CHECK_DASHBOARD')
    trusted(UNIT)
    need(digest(UNIT) == EXPECTED['templateSha256'], 'RECOVERY_UNIT_CHANGED')
    print('RESUME_OPERATION=' + EXPECTED['operation'])
    print('RESUME_PRESERVATION=UNCHANGED')
    print('RESUME_RECOVERY=ACTIVE')
    print('RESUME_TARGET=' + EXPECTED['target'])
    for name, label in (('java-assessment-collector.service', 'COLLECTOR'), ('java-assessment-collector.timer', 'TIMER')):
        state = service(name).get('ActiveState')
        need(state in ('active', 'inactive', 'activating', 'deactivating', 'failed'), 'UNEXPECTED_SERVICE_STATE')
        print('RESUME_' + label + '=' + state.upper())
    print('RESUME_COMPLETE=HANDOFF_ONLY')

if __name__ == '__main__':
    try:
        remote_main()
    except Exception as failure:
        code = str(failure)
        if not re.fullmatch('[A-Z0-9_]{1,96}', code):
            code = 'LOCAL_GUARD_OR_EXECUTION_FAILED'
        print('RESUME_STOP=' + code)
        sys.exit(1)
'''


def payload(contract):
    return ('EXPECTED = ' + repr(contract) + '\n' + REMOTE).encode()


def execute(host, identity, user, contract, runner=subprocess.run, output_root=None):
    require(host == contract['host'] and re.fullmatch('[A-Za-z_][A-Za-z0-9_-]*', user), 'UNEXPECTED_HOST_OR_USER')
    require(identity.is_file(), 'SSH_IDENTITY_NOT_FOUND')
    command = ['ssh', '-i', str(identity), '-o', 'ConnectTimeout=12', '-o', 'ServerAliveInterval=15',
               '-o', 'ServerAliveCountMax=2', '-o', 'StrictHostKeyChecking=yes', user + '@' + host,
               'sudo -n /usr/bin/python3 -I -']
    output_root = output_root or PROJECT / 'output/agent-canaries'
    output_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='resume-' + contract['target'] + '-', dir=str(output_root)))
    report = directory / 'resume-result.json'
    try:
        result = runner(command, input=payload(contract), stdout=subprocess.PIPE,
                        timeout=360 if contract.get('bootstrapState') == 'ABSENT' else 180)
    except subprocess.TimeoutExpired as timeout:
        result = subprocess.CompletedProcess(command, 124, stdout=timeout.output or b'')
    output = result.stdout.decode('ascii', errors='replace')
    markers = {}
    fixed = {'RESUME_OPERATION': contract['operation'], 'RESUME_TARGET': contract['target'], 'RESUME_PRESERVATION': 'UNCHANGED',
             'RESUME_RECOVERY': 'ACTIVE', 'RESUME_COMPLETE': 'HANDOFF_ONLY'}
    for line in output.splitlines():
        if '=' not in line:
            continue
        name, value = line.split('=', 1)
        if (name in fixed and value == fixed[name]
            or name in ('RESUME_COLLECTOR', 'RESUME_TIMER') and value in ('ACTIVE', 'INACTIVE', 'ACTIVATING', 'DEACTIVATING', 'FAILED')
            or name == 'RESUME_STOP' and re.fullmatch('[A-Z0-9_]{1,96}', value)
            or name == 'RESUME_LEGACY_HELPER' and value in ('STOPPED', 'ALREADY_INACTIVE')
            or name == 'RESUME_HELPER_RESTORE' and value in ('NOT_REQUIRED', 'SKIPPED_HANDOFF', 'ALREADY_ACTIVE', 'RESTORED', 'NOT_CONFIRMED_UNSAFE_TO_FORCE')
            or re.fullmatch(r'RESUME_HELPER_STOP_(ACTIVESTATE|SUBSTATE)', name) and re.fullmatch('[A-Z-]{1,32}', value)
            or re.fullmatch(r'RESUME_HELPER_STOP_(MAINPID|CONTROLPID)', name) and re.fullmatch('[0-9]{1,10}', value)
            or name == 'RESUME_HELPER_STOP_JOB' and value in ('NONE', 'PENDING_OR_UNKNOWN')
            or re.fullmatch(r'RESUME_(BEFORE|AFTER)_(CONFIG|INSTALLATION_ID|JAVA_PATH|CA|PUBLIC_KEY|HOST_ID_HASH)', name)
               and re.fullmatch('[a-f0-9]{64}', value)):
            require(name not in markers, 'DUPLICATE_REMOTE_MARKER')
            markers[name] = value
    descriptor = os.open(str(report), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as target:
        json.dump({'schema': 1, 'operation': contract['operation'], 'targetVersion': contract['target'],
                   'sshExitCode': result.returncode, 'markers': markers}, target, indent=2)
        target.write('\n'); target.flush(); os.fsync(target.fileno())
    print('Evidencia privada: ' + str(report))
    for name, value in markers.items():
        if not name.startswith(('RESUME_BEFORE_', 'RESUME_AFTER_')):
            print(name + '=' + value)
    # A connection/authentication failure can return no remote evidence at all.
    # It must not masquerade as an identity mismatch, or hide a precise guard.
    if 'RESUME_STOP' in markers:
        raise RuntimeError(markers['RESUME_STOP'])
    if result.returncode == 124:
        raise RuntimeError('SSH_TIMEOUT_RESULT_UNKNOWN_DO_NOT_REPEAT_BLINDLY')
    if not markers:
        raise RuntimeError('SSH_FAILED_WITHOUT_REMOTE_CONFIRMATION' if result.returncode != 0
                           else 'REMOTE_RESULT_MISSING_CHECK_CONNECTION')
    preserved = ('CONFIG', 'INSTALLATION_ID', 'JAVA_PATH', 'CA', 'PUBLIC_KEY', 'HOST_ID_HASH')
    require(all(markers.get('RESUME_BEFORE_' + name) and markers.get('RESUME_BEFORE_' + name) == markers.get('RESUME_AFTER_' + name)
                for name in preserved), 'PRESERVATION_NOT_CONFIRMED_CHECK_PRIVATE_REPORT')
    require(result.returncode == 0 and markers.get('RESUME_COMPLETE') == 'HANDOFF_ONLY', 'RESUME_NOT_CONFIRMED_DO_NOT_REPEAT_BLINDLY')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True)
    parser.add_argument('--target', choices=('2.0.9', '2.0.11'), default=TARGET)
    parser.add_argument('--case', type=Path)
    parser.add_argument('--identity', type=Path, default=Path.home() / 'OneDrive/id_rsa')
    parser.add_argument('--user', default='opc')
    parser.add_argument('--release', type=Path)
    args = parser.parse_args()
    case_path = args.case or PROJECT / 'output/agent-canaries' / ('recovery-case-' + args.target + '.json')
    release = args.release or PROJECT.parent / 'jms-teste-macos/assessment-pilot/output/dashboard-releases' / args.target
    case = load_case(case_path)
    require(args.host == case['host'], 'HOST_DIFFERS_FROM_PRIVATE_CASE')
    require(case.get('target', TARGET) == args.target, 'TARGET_DIFFERS_FROM_PRIVATE_CASE')
    contract = public_contract(release, case)
    print('Retomando somente o pedido identificado para ' + contract['target'] + '. Nenhum pacote sera enviado.')
    print('A identidade e a configuracao terao hashes comparados; o recuperador sera instalado pelo script assinado ja presente.')
    execute(args.host, args.identity.expanduser(), args.user, contract)
    print('Recuperador ativo e configuracao preservada. Acompanhe o mesmo Work request no dashboard; a versao final ainda precisa ser confirmada.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as failure:
        code = str(failure)
        message = {
            'SSH_FAILED_WITHOUT_REMOTE_CONFIRMATION': 'Falha no SSH, sem resultado remoto. Confira a conexao com a VM antes de tentar novamente.',
            'REMOTE_RESULT_MISSING_CHECK_CONNECTION': 'O SSH terminou sem resultado remoto. Confira a conexao e o estado do agente.',
            'SSH_TIMEOUT_RESULT_UNKNOWN_DO_NOT_REPEAT_BLINDLY': 'O SSH excedeu o limite; o resultado remoto e desconhecido. Confira o estado antes de repetir.',
        }.get(code, code if re.fullmatch('[A-Z0-9_]{1,96}', code) else 'Verificacao ou conexao nao confirmada; nao repita sem conferir o estado.')
        print('RETOMADA_INTERROMPIDA: ' + message, file=sys.stderr)
        sys.exit(1)
