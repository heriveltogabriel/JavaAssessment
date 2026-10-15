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
    require(set(case) == {'schema', 'host', 'operation', 'prepared', 'bridgeSha256', 'helperSha256', 'scriptSha256'}
            and type(case['schema']) is int and case['schema'] == 1, 'INVALID_CASE_SCHEMA')
    require(str(ipaddress.ip_address(case['host'])) == case['host']
            and re.fullmatch(r'[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}', case['operation'])
            and re.fullmatch(r'prepared-[0-9]{1,2}', case['prepared'])
            and all(re.fullmatch('[a-f0-9]{64}', case[name]) for name in ('bridgeSha256', 'helperSha256', 'scriptSha256')),
            'INVALID_CASE_VALUES')
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
    require(receipt['targetVersion'] == TARGET and receipt['bridgeVersion'] == BRIDGE, 'WRONG_CANDIDATE')
    with zipfile.ZipFile(release / 'packages' / ('assessment-dashboard-runtime-' + TARGET + '.zip')) as archive:
        runtime = {name: archive.read(name) for name in archive.namelist() if name.startswith('dist/')}
    bundle_prefix = 'dist/agent-bundles/' + TARGET + '/'
    header_raw = runtime[bundle_prefix + 'manifest.json']
    header = verifier.parse(header_raw)
    with zipfile.ZipFile(io.BytesIO(runtime[bundle_prefix + 'bundle.zip'])) as archive:
        members = {name: archive.read(name) for name in archive.namelist()}
    routes = verifier.parse(runtime['dist/agent-update-routes/' + TARGET + '/routes.json'])
    route = next(row for row in routes['routes'] if row['fromVersion'] == '1.5.12')
    bridge_jar = runtime['dist/agent-bridges/' + BRIDGE + '/' + route['id'] + '/collector.jar']
    require(verifier.sha(bridge_jar) == case['bridgeSha256'], 'BRIDGE_RECEIPT_DIFFERS_FROM_IDENTIFIED_HOST')
    with zipfile.ZipFile(io.BytesIO(bridge_jar)) as archive:
        migration = verifier.parse(archive.read('migration/bridge.json'))
    require(case['helperSha256'] in migration['allowedHelperHashes'] and verifier.sha(members['bootstrap/update-bootstrap.sh']) == case['scriptSha256'],
            'BOOTSTRAP_OR_HELPER_RECEIPT_DIFFERS')
    template = members['bootstrap/update-bootstrap.sh'].split(b"<<'UNIT'\n", 1)[1].split(b'\nUNIT\n', 1)[0] + b'\n'
    boot = {name[10:]: row['sha256'] for name, row in header['files'].items() if name.startswith('bootstrap/')}
    boot.update({'bundle-manifest.json': verifier.sha(header_raw),
                 'bundle-manifest.sig': verifier.sha(runtime[bundle_prefix + 'manifest.sig'])})
    return {'host': case['host'], 'operation': case['operation'], 'target': TARGET, 'bridge': BRIDGE, 'prepared': case['prepared'],
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
                  'java-assessment-update.service', 'java-assessment-recovery.service'), 'INVALID_SERVICE')
    result = subprocess.run(['/usr/bin/systemctl', 'show', name, '-p', 'LoadState', '-p', 'ActiveState',
                             '-p', 'SubState', '-p', 'MainPID', '-p', 'ProtectSystem'],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
    need(result.returncode == 0 and len(result.stdout) < 4096, 'SERVICE_DIAGNOSTIC_FAILED')
    fields = dict(line.split('=', 1) for line in result.stdout.decode('ascii').splitlines() if '=' in line)
    need(fields.get('MainPID', '0').isdigit(), 'INVALID_SERVICE_PID')
    return fields

def validate_journal(journal):
    need(journal.get('schema') == 1 and journal.get('operationId') == EXPECTED['operation']
         and journal.get('phase') == 'PREPARED' and journal.get('targetVersion') == EXPECTED['target']
         and journal.get('previousVersion') == '1.5.12' and journal.get('previousPointer') is None
         and journal.get('preparedDirectory') == EXPECTED['prepared']
         and journal.get('bundleSha256') == EXPECTED['bundleSha256']
         and journal.get('manifestSha256') == EXPECTED['manifestSha256'], 'WRONG_OR_CHANGED_TRANSACTION')

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
        code = execute_bootstrap(script)
    finally:
        after = snapshot_identity()
        evidence(after, 'AFTER')
    need(before == after, 'IDENTITY_CHANGED_STOP_AND_REVIEW_AUDIT')
    need(code == 0, 'BOOTSTRAP_FAILED_NO_AUTOMATIC_RETRY')
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
    directory = Path(tempfile.mkdtemp(prefix='resume-2.0.9-', dir=str(output_root)))
    report = directory / 'resume-result.json'
    try:
        result = runner(command, input=payload(contract), stdout=subprocess.PIPE, timeout=180)
    except subprocess.TimeoutExpired as timeout:
        result = subprocess.CompletedProcess(command, 124, stdout=timeout.output or b'')
    output = result.stdout.decode('ascii', errors='replace')
    markers = {}
    fixed = {'RESUME_OPERATION': contract['operation'], 'RESUME_TARGET': TARGET, 'RESUME_PRESERVATION': 'UNCHANGED',
             'RESUME_RECOVERY': 'ACTIVE', 'RESUME_COMPLETE': 'HANDOFF_ONLY'}
    for line in output.splitlines():
        if '=' not in line:
            continue
        name, value = line.split('=', 1)
        if (name in fixed and value == fixed[name]
            or name in ('RESUME_COLLECTOR', 'RESUME_TIMER') and value in ('ACTIVE', 'INACTIVE', 'ACTIVATING', 'DEACTIVATING', 'FAILED')
            or name == 'RESUME_STOP' and re.fullmatch('[A-Z0-9_]{1,96}', value)
            or re.fullmatch(r'RESUME_(BEFORE|AFTER)_(CONFIG|INSTALLATION_ID|JAVA_PATH|CA|PUBLIC_KEY|HOST_ID_HASH)', name)
               and re.fullmatch('[a-f0-9]{64}', value)):
            require(name not in markers, 'DUPLICATE_REMOTE_MARKER')
            markers[name] = value
    descriptor = os.open(str(report), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as target:
        json.dump({'schema': 1, 'operation': contract['operation'], 'targetVersion': TARGET,
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
    parser.add_argument('--case', type=Path, default=CASE_PATH)
    parser.add_argument('--identity', type=Path, default=Path.home() / 'OneDrive/id_rsa')
    parser.add_argument('--user', default='opc')
    parser.add_argument('--release', type=Path, default=PROJECT.parent / 'jms-teste-macos/assessment-pilot/output/dashboard-releases/2.0.9')
    args = parser.parse_args()
    case = load_case(args.case)
    require(args.host == case['host'], 'HOST_DIFFERS_FROM_PRIVATE_CASE')
    contract = public_contract(args.release, case)
    print('Retomando somente o pedido identificado para 2.0.9. Nenhum pacote sera enviado.')
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
