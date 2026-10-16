#!/usr/bin/env python3
"""One-shot, authorized update of the inspected dashboard. Never operates agents."""
import datetime
import hashlib
import io
import http.client
import socket
import json
import os
from pathlib import Path
import pwd
import shutil
import sqlite3
import ssl
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse
import zipfile

BASE = Path('/opt/java-assessment-dashboard')
UNIT = 'java-assessment-dashboard.service'
URL = 'https://167.126.4.37'
LOCAL_MODE = False
RUNTIME_SHA = '@RUNTIME_SHA@'
SOURCE_SHA = '@SOURCE_SHA@'
DASHBOARD_VERSION = '@DASHBOARD_VERSION@'
COMPONENTS = ['classes', 'web', 'server', 'shared', 'dist', 'VERSION', 'maintenance']
CGROUP_ROOT = Path('/sys/fs/cgroup')
SERVICE_PROPERTIES = ('LoadState', 'ActiveState', 'SubState', 'MainPID', 'ControlPID',
                      'ControlGroup', 'Result', 'ExecMainCode', 'ExecMainStatus')
UNINSTALL_TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'}
UNINSTALL_BUSY = {'WAITING', 'UNINSTALLING', 'UNCONFIRMED'}
UPDATE_TERMINAL = {'SUCCEEDED', 'FAILED', 'ROLLED_BACK', 'CANCELLED', 'EXPIRED'}
UPDATE_BUSY = {'WAITING', 'DOWNLOADING', 'INSTALLING', 'VERIFYING', 'UNCONFIRMED'}

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def backup_install_access_key(data_directory, backup):
    """Keep the environment-download master key with the private DB backup."""
    source = data_directory / '.environment-install.key'
    try:
        descriptor = os.open(source, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return False
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size == 32
                and stat.S_IMODE(info.st_mode) == 0o600
                and info.st_uid == data_directory.stat().st_uid,
                'Unsafe environment installation access key')
        value = stream.read(33)
        require(len(value) == 32, 'Invalid environment installation access key')
    destination = backup / '.environment-install.key'
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())
    return True

def hashes(directory):
    result = {}
    for p in sorted(directory.rglob('*')):
        require(not p.is_symlink(), 'Unexpected symlink: ' + str(p))
        if p.is_file():
            result[str(p.relative_to(directory))] = sha(p.read_bytes())
    return result

def command(*args):
    return subprocess.check_output(args, universal_newlines=True, stderr=subprocess.STDOUT).strip()

def service_snapshot():
    output = command('systemctl', 'show', UNIT, '--no-pager',
                     '--property=' + ','.join(SERVICE_PROPERTIES))
    values = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    require(all(key in values for key in SERVICE_PROPERTIES), 'Incomplete service state: ' + output)
    return {key: values[key] for key in SERVICE_PROPERTIES}

def cgroup_empty(group):
    # The path comes from systemd, and must name this service, never a parent group.
    parts = Path(group).parts
    require(group.startswith('/') and '..' not in parts and parts[-1] == UNIT,
            'Unexpected dashboard cgroup: ' + group)
    if (CGROUP_ROOT / 'cgroup.controllers').is_file():
        directory = CGROUP_ROOT / group.lstrip('/')
        try:
            events = dict(line.split() for line in (directory / 'cgroup.events').read_text().splitlines())
        except FileNotFoundError:
            require(not directory.exists(), 'Missing cgroup events for existing service group')
            return True
        require(events.get('populated') in ('0', '1'), 'Missing cgroup population state')
        # cgroup v2's populated value includes every descendant group.
        return events['populated'] == '0'
    hierarchy = CGROUP_ROOT / 'systemd'
    require((hierarchy / 'cgroup.procs').is_file(), 'Unsupported cgroup layout')
    directory = hierarchy / group.lstrip('/')
    if not directory.exists():
        return True
    def unreadable(error):
        raise error
    for current, _, _ in os.walk(directory, onerror=unreadable):
        try:
            if (Path(current) / 'cgroup.procs').read_text().strip():
                return False
        except FileNotFoundError:
            require(not Path(current).exists(), 'Missing process list for existing cgroup')
    return True

def wait_service_stopped(before, timeout=10):
    group = before.get('ControlGroup', '')
    deadline = time.monotonic() + timeout
    while True:
        state = service_snapshot()
        current_group = state.get('ControlGroup', '')
        require(not group or not current_group or group == current_group, 'Dashboard cgroup changed during stop')
        group = group or current_group
        terminal = state.get('ActiveState') in ('inactive', 'failed')
        no_pids = state.get('MainPID') == '0' and state.get('ControlPID') == '0'
        require(state.get('LoadState') == 'loaded', 'Dashboard unit is no longer loaded')
        if terminal and no_pids:
            require(bool(group), 'Cannot verify stopped service without its cgroup')
            if cgroup_empty(group):
                return state
        if time.monotonic() >= deadline:
            raise RuntimeError('Service stop not verified: ' + json.dumps(state, sort_keys=True))
        time.sleep(0.25)

def stop_service(report, key):
    diagnostic = report.setdefault(key, {})
    diagnostic['before'] = service_snapshot()
    try:
        result = subprocess.run(['systemctl', 'stop', UNIT], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=45)
        diagnostic['commandReturnCode'] = result.returncode
        diagnostic['commandOutput'] = (result.stdout + result.stderr).strip()[-4000:]
        diagnostic['after'] = service_snapshot()
        require(result.returncode == 0, 'systemctl stop failed: ' + result.stderr.strip())
        reference = dict(diagnostic['before'])
        if not reference.get('ControlGroup'):
            reference['ControlGroup'] = report.get('stop', {}).get('before', {}).get('ControlGroup', '')
        diagnostic['verified'] = wait_service_stopped(reference)
    except Exception as exc:
        diagnostic['error'] = str(exc)
        raise

def fix_sqlite_sidecars(account):
    # Read-only SQLite tools can create sidecars while the service is stopped.
    for name in ('inventory.sqlite-wal', 'inventory.sqlite-shm'):
        sidecar = BASE / 'data' / name
        if sidecar.exists():
            require(sidecar.is_file() and not sidecar.is_symlink(), 'Unexpected SQLite sidecar')
            os.chown(sidecar, account.pw_uid, account.pw_gid)
            sidecar.chmod(0o600)

def database(reject_agent_updates=True):
    p = BASE / 'data/inventory.sqlite'
    require(p.is_file() and not p.is_symlink(), 'Database path changed')
    con = sqlite3.connect('file:' + str(p) + '?mode=ro', uri=True, timeout=10)
    try:
        # Keep integrity, counts and the uninstall guard in one read-only snapshot.
        con.execute('BEGIN')
        require(con.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'Database integrity failed')
        tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        counts = {t: con.execute('SELECT count(*) FROM ' + t).fetchone()[0]
                  for t in ['hosts', 'environments', 'identities', 'jobs', 'inventory', 'uninstall_requests', 'agent_update_requests', 'agent_update_operations'] if t in tables}
        if 'uninstall_requests' in tables:
            statuses = dict(con.execute('SELECT status, count(*) FROM uninstall_requests GROUP BY status').fetchall())
            unknown = set(statuses) - UNINSTALL_TERMINAL - UNINSTALL_BUSY
            require(not unknown, 'Unknown uninstall request status; update refused')
            busy = {status: count for status, count in statuses.items() if status in UNINSTALL_BUSY}
            require(not busy, 'Uninstall requests still pending or unconfirmed; update refused: ' + json.dumps(busy, sort_keys=True))
        if 'agent_update_requests' in tables:
            statuses = dict(con.execute('SELECT status,count(*) FROM agent_update_requests GROUP BY status').fetchall())
            require(not set(statuses) - UPDATE_TERMINAL - UPDATE_BUSY, 'Unknown agent update status; dashboard update refused')
            busy = {status: count for status, count in statuses.items() if status in UPDATE_BUSY}
            if reject_agent_updates:
                require(not busy, 'Agent updates still pending or unconfirmed; dashboard update refused: ' + json.dumps(busy, sort_keys=True))
        if 'agent_update_operations' in tables:
            statuses = dict(con.execute('SELECT status,count(*) FROM agent_update_operations GROUP BY status').fetchall())
            require(not set(statuses) - UPDATE_TERMINAL - UPDATE_BUSY, 'Unknown full agent update state; dashboard update refused')
            busy = {status: count for status, count in statuses.items() if status in UPDATE_BUSY}
            if reject_agent_updates:
                require(not busy, 'Full agent updates still pending or unconfirmed; dashboard update refused: ' + json.dumps(busy, sort_keys=True))
        return counts
    finally:
        con.close()

def human_auth_activated():
    """Read the permanent downgrade boundary without revealing account data."""
    path = BASE / 'data/inventory.sqlite'
    require(path.is_file() and not path.is_symlink(), 'Cannot determine authentication rollback safety')
    con = sqlite3.connect('file:' + str(path) + '?mode=ro', uri=True, timeout=10)
    try:
        con.execute('BEGIN')
        require(con.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'Authentication rollback database integrity failed')
        tables = {row[0] for row in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        marker = None
        if 'metadata' in tables:
            row = con.execute('SELECT payload FROM metadata WHERE key=?', ('humanAuthActivated',)).fetchone()
            if row is not None:
                marker = json.loads(row[0])
                require(isinstance(marker, bool), 'Unknown authentication activation state; rollback refused')
        users = con.execute('SELECT count(*) FROM human_users').fetchone()[0] if 'human_users' in tables else 0
        # Accounts remain a boundary even if an incomplete/older tool lost the marker.
        return marker is True or users > 0
    finally:
        con.close()

def supports_human_auth(paths):
    return {'server/UserAuth.java', 'server/UserAdminTool.java'}.issubset(paths)

def require_safe_auth_rollback(previous_supports_accounts):
    require(previous_supports_accounts or not human_auth_activated(),
            'Account authentication has been activated; rollback to shared-password software refused')

def get(path):
    context = ssl.create_default_context(cafile=str(BASE / 'conf/agent-ca.pem'))
    if LOCAL_MODE:
        parsed = urllib.parse.urlsplit(URL)
        with socket.create_connection(('127.0.0.1', parsed.port or 443), timeout=4) as plain:
            with context.wrap_socket(plain, server_hostname=parsed.hostname) as secure:
                secure.sendall(('GET ' + path + ' HTTP/1.1\r\nHost: ' + parsed.netloc + '\r\nConnection: close\r\n\r\n').encode('ascii'))
                response = http.client.HTTPResponse(secure); response.begin()
                require(response.status == 200, 'Local HTTPS check failed: ' + path)
                body = response.read(16 * 1024 * 1024 + 1)
                require(len(body) <= 16 * 1024 * 1024, 'HTTPS body exceeds verification limit')
                return body
    with urllib.request.urlopen(URL + path, context=context, timeout=4) as response:
        require(response.status == 200, 'HTTP check failed: ' + path)
        return response.read()

def check_health(expected_version=None):
    last = None
    for attempt in range(20):
        try:
            value = json.loads(get('/health'))
            require(value.get('status') == 'ok' and value.get('storage') == 'sqlite', 'Invalid health response')
            require(expected_version is None or value.get('version') == expected_version,
                    'Dashboard version mismatch: expected ' + str(expected_version) + ', got ' + str(value.get('version')))
            return value
        except Exception as exc:
            last = exc
            time.sleep(1)
    raise RuntimeError('Dashboard health failed: ' + str(last))

def read_zip(path, expected_sha):
    data = path.read_bytes()
    require(sha(data) == expected_sha, 'Package checksum mismatch: ' + path.name)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        require(len(z.namelist()) == len(set(z.namelist())), 'Duplicate ZIP entries')
        require(all(not n.startswith('/') and '..' not in Path(n).parts for n in z.namelist()), 'Unsafe ZIP entry')
        result = {n: z.read(n) for n in z.namelist()}
    if 'SHA256SUMS' in result:
        for line in result['SHA256SUMS'].decode().splitlines():
            digest, name = line.split('  ', 1)
            require(sha(result[name]) == digest, 'Internal checksum mismatch: ' + name)
    return result

def collector_with_ca(data, ca):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as original, zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED) as updated:
        names = original.namelist()
        require(len(names) == len(set(names)), 'Collector duplicate entry')
        for line in original.read('SHA256SUMS').decode().splitlines():
            digest, name = line.split('  ', 1)
            require(sha(original.read(name)) == digest, 'Collector checksum mismatch')
        lines = []
        for name in names:
            if name == 'SHA256SUMS':
                continue
            content = original.read(name)
            if name == 'conf/ca.pem':
                content = ca
            elif name == 'DEPENDENCIES.json':
                deps = json.loads(content)
                deps['ca'] = {'source': 'existing dashboard CA retained during authorized update', 'sha256': sha(ca)}
                content = (json.dumps(deps, indent=2) + '\n').encode()
            updated.writestr(original.getinfo(name), content)
            lines.append(sha(content) + '  ' + name)
        updated.writestr('SHA256SUMS', '\n'.join(lines) + '\n')
    with zipfile.ZipFile(io.BytesIO(result.getvalue())) as z:
        require(z.read('conf/ca.pem') == ca, 'Collector CA not retained')
        for line in z.read('SHA256SUMS').decode().splitlines():
            digest, name = line.split('  ', 1)
            require(sha(z.read(name)) == digest, 'Configured collector checksum mismatch')
    return result.getvalue()

def collector_version(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        version = json.loads(archive.read('DEPENDENCIES.json')).get('version')
        require(version == DASHBOARD_VERSION, 'Agent ZIP version differs from dashboard release')
        with zipfile.ZipFile(io.BytesIO(archive.read('collector.jar'))) as jar:
            lines = jar.read('META-INF/MANIFEST.MF').decode().splitlines()
            versions = [line[len('Implementation-Version: '):] for line in lines if line.startswith('Implementation-Version: ')]
            require(versions == [DASHBOARD_VERSION], 'Agent JAR version differs from dashboard release')
    return version

def select_baseline(actual, candidates):
    for candidate in candidates:
        if actual == candidate['hashes']:
            return candidate
    raise RuntimeError('Installed files differ from every known dashboard baseline; no files changed')

def main():
    global LOCAL_MODE, URL
    arguments = sys.argv[1:]
    if arguments and arguments[0] == '--local':
        LOCAL_MODE = True
        arguments = arguments[1:]
        config = BASE / 'conf/server.properties'
        require(config.is_file() and not config.is_symlink(), 'Missing local configuration')
        values = dict(line.split('=', 1) for line in config.read_text().splitlines()
                      if '=' in line and not line.startswith(('#', '!')))
        URL = values.get('publicUrl', '').strip()
        parsed = urllib.parse.urlsplit(URL)
        require(parsed.scheme == 'https' and parsed.hostname and not parsed.username and not parsed.password
                and parsed.path in ('', '/') and not parsed.query and not parsed.fragment, 'Invalid configured publicUrl')
    require(len(arguments) == 2, 'Usage: update-dashboard.py [--local] runtime.zip sources.zip')
    os.umask(0o077)
    require(os.geteuid() == 0, 'Run as root')
    if not LOCAL_MODE:
        require(command('hostname') == 'dashboard', 'Unexpected hostname')
    updater_state = Path('/var/lib/java-assessment-dashboard-updater/journal.json')
    if updater_state.exists():
        require(not updater_state.is_symlink() and json.loads(updater_state.read_text()).get('phase') in ('IDLE','SUCCEEDED','ROLLED_BACK','FAILED','CANCELLED'),
                'Dashboard updater operation is pending; use the administration screen')
        # Never compete with the independent executor. This script bootstraps older installations.
        require(command('systemctl','show','java-assessment-dashboard-updater.service','--value','-p','ActiveState') != 'active',
                'Use the dashboard administration updater; independent executor is active')
    require(command('systemctl', 'is-active', UNIT) == 'active', 'Dashboard is not active')
    require(command('systemctl', 'show', UNIT, '--value', '-p', 'User') == 'java-assessment-dashboard', 'Unexpected service user')
    require(not BASE.is_symlink() and BASE.stat().st_uid == 0, 'Unexpected dashboard directory')
    runtime = read_zip(Path(arguments[0]), RUNTIME_SHA)
    collector_version(runtime['dist/assessment-collector.zip'])
    pin = BASE / 'dist/agent-update-public.der'
    require('dist/agent-update-public.der' in runtime, 'Signed agent update trust key missing')
    if pin.exists() or pin.is_symlink():
        require(pin.is_file() and not pin.is_symlink() and pin.read_bytes() == runtime['dist/agent-update-public.der'],
                'Installed agent update trust key cannot be replaced by a dashboard publication')
    sources = read_zip(Path(arguments[1]), SOURCE_SHA)
    expected = json.loads(sources.pop('expected-baselines.json'))
    sources.pop('SHA256SUMS', None)  # Already verified; not a deployed source file.
    actual = {str(p.relative_to(BASE)): sha(p.read_bytes())
              for d in ('server', 'shared', 'web', 'lib') for p in (BASE / d).rglob('*') if p.is_file()}
    baseline = select_baseline(actual, expected)
    previous_supports_accounts = supports_human_auth(actual)
    require(supports_human_auth(sources) or not human_auth_activated(),
            'Account authentication has been activated; downgrade payload refused')
    for name, data in runtime.items():
        if name.startswith('lib/'):
            require((BASE / name).is_file() and sha((BASE / name).read_bytes()) == sha(data), 'Runtime library mismatch')
    for component in COMPONENTS:
        path = BASE / component
        if component == 'maintenance' and not path.exists():
            continue
        require(path.exists() and not path.is_symlink(), 'Missing or linked component: ' + component)
        require(path.stat().st_dev == Path('/root').stat().st_dev,
                'Backup and dashboard must share a filesystem for atomic file replacement')
        if path.is_dir():
            hashes(path)
    before_counts = database()
    conf_before = hashes(BASE / 'conf')
    protected = {str(p): sha(p.read_bytes()) for p in (BASE / 'run-server.sh', BASE / 'database.sh', Path('/etc/systemd/system') / UNIT)}
    check_health(baseline['version'])
    backup = Path(tempfile.mkdtemp(prefix='java-assessment-dashboard-update-', dir='/root'))
    stage = backup / 'new'
    stage.mkdir()
    old = backup / 'previous-components'
    old.mkdir()
    account = pwd.getpwnam('java-assessment-dashboard')
    # Preserve older signed catalog files and any existing public dist assets.
    # The whole directory is swapped atomically, including first-time trust setup.
    shutil.copytree(BASE / 'dist', stage / 'dist')
    staged = {name: data for name, data in runtime.items() if name.startswith(('classes/', 'web/', 'dist/agent-updates/', 'dist/agent-bundles/', 'dist/agent-bridges/', 'dist/agent-update-routes/')) or name in ('VERSION', 'dist/agent-update-public.der')}
    staged.update(sources)
    staged['dist/assessment-collector.zip'] = collector_with_ca(runtime['dist/assessment-collector.zip'], (BASE / 'conf/agent-ca.pem').read_bytes())
    collector_version(staged['dist/assessment-collector.zip'])
    for name, data in staged.items():
        require(name.startswith(('classes/', 'web/', 'server/', 'shared/', 'maintenance/', 'dist/agent-updates/', 'dist/agent-bundles/', 'dist/agent-bridges/', 'dist/agent-update-routes/')) or name in ('dist/assessment-collector.zip', 'dist/agent-update-public.der', 'VERSION'), 'Unexpected payload')
        path = stage / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        os.chown(path, 0, account.pw_gid)
        path.chmod(0o640)
    for path in stage.rglob('*'):
        if path.is_dir():
            os.chown(path, 0, account.pw_gid)
            path.chmod(0o750)
    report = {'target': URL, 'runtimeSha256': RUNTIME_SHA, 'sourcesSha256': SOURCE_SHA,
              'dashboardVersion': DASHBOARD_VERSION, 'previousVersion': baseline['version'], 'baseline': baseline['label'],
              'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'backup': str(backup), 'beforeCounts': before_counts, 'agentsModified': False,
              'databaseBackupComplete': False, 'databasePreservedBeforeStart': False,
              'scriptRevision': 'service-stop-verification-v2'}
    (backup / 'before.json').write_text(json.dumps(report, indent=2) + '\n')
    with tarfile.open(backup / 'configuration-and-launchers.tar.gz', 'w:gz') as tar:
        for name in ('conf', 'run-server.sh', 'database.sh', 'VERSION', 'DatabaseTool.java'):
            tar.add(BASE / name, arcname=name)
        tar.add('/etc/systemd/system/' + UNIT, arcname=UNIT)
    moved = []
    stop_attempted = False
    try:
        stop_attempted = True
        stop_service(report, 'stop')
        report['beforeCounts'] = database()
        require(supports_human_auth(sources) or not human_auth_activated(),
                'Account authentication has been activated; downgrade payload refused')
        backup_output = command(str(BASE / 'database.sh'), 'backup', str(BASE / 'data/inventory.sqlite'), str(backup / 'inventory.sqlite'))
        require('BACKUP_OK' in backup_output, 'Consistent database backup failed')
        report['databaseBackupComplete'] = True
        report['installAccessKeyBackedUp'] = backup_install_access_key(BASE / 'data', backup)
        fix_sqlite_sidecars(account)
        for component in COMPONENTS:
            saved = old / component
            saved.parent.mkdir(parents=True, exist_ok=True)
            if (BASE / component).exists():
                (BASE / component).rename(saved)
            moved.append(component)
            (stage / component).rename(BASE / component)
        if shutil.which('restorecon'):
            command('restorecon', '-RF', *[str(BASE / p) for p in COMPONENTS])
        require(hashes(BASE / 'conf') == conf_before, 'Configuration changed')
        require(all(sha(Path(p).read_bytes()) == h for p, h in protected.items()), 'Service configuration changed')
        # Only files are replaced during publication. Compare database counts while
        # the verified service is still stopped: after startup an administrator may
        # legitimately delete a machine and its history during asset verification.
        report['stoppedCounts'] = database()
        require(report['stoppedCounts'] == report['beforeCounts'], 'Database counts changed while service was stopped')
        report['databasePreservedBeforeStart'] = True
        fix_sqlite_sidecars(account)
        updater_unit = Path('/etc/systemd/system') / 'java-assessment-dashboard-updater.service'
        updater_access = Path('/etc/systemd/system/java-assessment-dashboard.service.d/dashboard-updater.conf')
        report['updaterUnitExisted'] = updater_unit.exists()
        report['updaterAccessExisted'] = updater_access.exists()
        command(sys.executable, str(BASE / 'maintenance/install.py'))
        command('systemctl', 'start', UNIT)
        report['health'] = check_health(DASHBOARD_VERSION)
        for name, data in staged.items():
            require(sha((BASE / name).read_bytes()) == sha(data), 'Deployed file mismatch: ' + name)
        for name in (name for name in staged if name.startswith('web/') and name.endswith(('.html', '.js', '.css'))):
            require(sha(get('/' + name[4:])) == sha(staged[name]), 'Served asset mismatch: ' + name)
        # New requests after startup are legitimate. Keep integrity checks without
        # undoing a release that has already authorized its target agent version.
        after = database(reject_agent_updates=False)
        # Retain live integrity/uninstall checks and diagnostics, without treating
        # legitimate user mutations after startup as publication data loss.
        require(set(report['beforeCounts']).issubset(after), 'Database tables missing after service start')
        report.update({'status': 'deployed', 'afterCounts': after, 'configurationPreserved': True,
                       'collectorCaPreserved': True, 'collectorVersion': DASHBOARD_VERSION,
                       'collectorSha256': sha(staged['dist/assessment-collector.zip']),
                       'updatedComponents': COMPONENTS,
                       'completedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()})
    except Exception as exc:
        report['status'] = 'failed'
        report['error'] = str(exc)
        if stop_attempted:
            try:
                if moved:
                    if report.get('updaterAccessExisted') is False:
                        updater_access = Path('/etc/systemd/system/java-assessment-dashboard.service.d/dashboard-updater.conf')
                        if updater_access.exists():
                            require(not updater_access.is_symlink() and updater_access.read_text().startswith('# Java Assessment dashboard update inbox v1\n'), 'Unexpected dashboard access during bootstrap rollback')
                            updater_access.unlink()
                            command('systemctl','daemon-reload')
                    if report.get('updaterUnitExisted') is False:
                        updater_unit = Path('/etc/systemd/system') / 'java-assessment-dashboard-updater.service'
                        if updater_unit.exists():
                            require(not updater_unit.is_symlink() and updater_unit.read_text().startswith('# Java Assessment independent dashboard updater v1\n'), 'Unexpected updater unit during bootstrap rollback')
                            command('systemctl','disable','--now',updater_unit.name)
                            updater_unit.unlink()
                            command('systemctl','daemon-reload')
                    # Never swap files back while the replacement process is alive.
                    stop_service(report, 'rollbackStop')
                    # Activation may have occurred during post-start verification.
                    # Check only after verified stop, so it cannot race this decision.
                    try:
                        require_safe_auth_rollback(previous_supports_accounts)
                        # A request can have been created/claimed after startup.
                        # Removing its catalog/API now would strand that agent.
                        database()
                    except Exception as safety_error:
                        report['rollbackBlocked'] = True
                        report['rollbackReason'] = str(safety_error)
                        report['rollback'] = 'replacement components and database retained; unsafe code rollback refused'
                        # A partial replacement cannot be restarted safely. A complete
                        # account-capable replacement may be retried without downgrade.
                        if len(moved) == len(COMPONENTS) and supports_human_auth(sources):
                            fix_sqlite_sidecars(account)
                            command('systemctl', 'start', UNIT)
                            report['retainedHealth'] = check_health(DASHBOARD_VERSION)
                        raise RuntimeError('Unsafe rollback blocked; replacement code retained') from safety_error
                    failed = backup / 'failed-components'
                    for component in reversed(moved):
                        target = BASE / component
                        if target.exists():
                            destination = failed / component
                            destination.parent.mkdir(parents=True, exist_ok=True)
                            target.rename(destination)
                        if (old / component).exists():
                            (old / component).rename(target)
                fix_sqlite_sidecars(account)
                command('systemctl', 'start', UNIT)
                report['rollbackHealth'] = check_health(baseline['version'])
                report['rollback'] = ('previous code restored; database retained' if moved else
                                      'previous code restarted; no components replaced; database retained')
            except Exception as recovery_error:
                report['rollbackError'] = str(recovery_error)
                if not report.get('rollbackBlocked'):
                    report['rollback'] = 'automatic recovery not confirmed; see private backup'
        raise
    finally:
        (backup / 'deployment-result.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2), flush=True)

if __name__ == '__main__':
    main()
