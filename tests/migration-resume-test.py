#!/usr/bin/env python3
"""Offline guards, fake SSH/OS and one-time native invocation; no host access."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import tempfile
import zipfile
from types import SimpleNamespace
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('resume', ROOT / 'ops/retomar-migracao.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
checks = 0


def check(value):
    global checks
    assert value
    checks += 1


def reject(call):
    try:
        call()
    except RuntimeError:
        check(True)
        return
    raise AssertionError('Unsafe repair input accepted')


def public_test_case(release):
    """Synthetic host/operation; all hashes come exclusively from public ZIPs."""
    with zipfile.ZipFile(release / 'packages/assessment-dashboard-runtime-2.0.9.zip') as archive:
        routes = json.loads(archive.read('dist/agent-update-routes/2.0.9/routes.json'))
        route = next(row for row in routes['routes'] if row['fromVersion'] == '1.5.12')
        bridge = archive.read('dist/agent-bridges/2.0.8/' + route['id'] + '/collector.jar')
        bundle = archive.read('dist/agent-bundles/2.0.9/bundle.zip')
    with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
        bootstrap = archive.read('bootstrap/update-bootstrap.sh')
    with zipfile.ZipFile(release.parent / '1.5.8/packages/assessment-collector-1.5.8.zip') as archive:
        helper = archive.read('collector.jar')
    sha = lambda raw: hashlib.sha256(raw).hexdigest()
    return {'schema': 1, 'host': '192.0.2.10', 'operation': '12345678-1234-1234-1234-123456789abc',
            'prepared': 'prepared-0', 'bridgeSha256': sha(bridge),
            'helperSha256': sha(helper), 'scriptSha256': sha(bootstrap)}


def main(release):
    case = public_test_case(release)
    with tempfile.TemporaryDirectory(prefix='synthetic-recovery-case-', dir='/private/tmp') as temp:
        path = Path(temp) / 'case.json'
        path.write_text(json.dumps(case)); path.chmod(0o600)
        check(module.load_case(path) == case)
        path.chmod(0o644); reject(lambda: module.load_case(path)); path.chmod(0o600)
        path.parent.chmod(0o755); reject(lambda: module.load_case(path)); path.parent.chmod(0o700)
    contract = module.public_contract(release, case)
    check(contract['operation'] == case['operation'] and contract['bridgeSha256'] == case['bridgeSha256'])
    check(contract['helperSha256'] in contract['origin']['allowedHelperHashes'])
    namespace = {'__name__': 'offline_remote', 'EXPECTED': contract}
    exec(compile(module.REMOTE, '<offline-remote>', 'exec'), namespace)
    journal = {'schema': 1, 'operationId': case['operation'], 'phase': 'PREPARED', 'targetVersion': module.TARGET,
               'previousVersion': '1.5.12', 'previousPointer': None, 'preparedDirectory': case['prepared'],
               'bundleSha256': contract['bundleSha256'], 'manifestSha256': contract['manifestSha256']}
    namespace['validate_journal'](journal); check(True)
    for name, wrong in (('phase', 'ACTIVATING'), ('operationId', '0' * 36), ('targetVersion', '2.0.10'),
                        ('preparedDirectory', '../../elsewhere'), ('bundleSha256', '0' * 64)):
        reject(lambda: namespace['validate_journal']({**journal, name: wrong}))
    # A fake root identity tests physical file protections without needing root.
    native_lstat = os.lstat
    def root_lstat(path):
        info = native_lstat(path)
        mode = stat.S_IFDIR | 0o755 if str(path) == '/private/tmp' else info.st_mode
        return SimpleNamespace(st_uid=0, st_mode=mode, st_nlink=info.st_nlink)
    with tempfile.TemporaryDirectory(prefix='resume-guards-', dir='/private/tmp') as temp:
        directory = Path(temp)
        regular = directory / 'regular'; regular.write_bytes(b'public test'); regular.chmod(0o600)
        with patch.object(namespace['os'], 'lstat', side_effect=root_lstat):
            namespace['trusted'](regular, private=True); check(True)
            regular.chmod(0o660); reject(lambda: namespace['trusted'](regular)); regular.chmod(0o600)
            link = directory / 'linked'; link.symlink_to(regular); reject(lambda: namespace['trusted'](link)); link.unlink()
            hard = directory / 'hard'; os.link(regular, hard); reject(lambda: namespace['trusted'](regular)); hard.unlink()
            regular.chmod(0o644); reject(lambda: namespace['trusted'](regular, private=True)); regular.chmod(0o600)
            directory.chmod(0o777); reject(lambda: namespace['trusted'](regular)); directory.chmod(0o700)
    original_execute = namespace['execute_bootstrap']
    child = Mock(pid=4321); child.wait.side_effect = [subprocess.TimeoutExpired('fixed bootstrap', 120), 0]
    with patch.object(namespace['subprocess'], 'Popen', return_value=child) as opened, patch.object(namespace['os'], 'killpg') as killed:
        reject(lambda: original_execute(Path('/opt/java-assessment-update/transactions/fixed/bootstrap/update-bootstrap.sh')))
        check(opened.call_args.args[0][-2:] == ['install-bootstrap', case['operation']])
        check(opened.call_args.kwargs['start_new_session'] is True)
        killed.assert_called_once_with(4321, signal.SIGTERM); check(True)
        check(opened.call_count == 1)
    child = Mock(pid=4321)
    child.wait.side_effect = [subprocess.TimeoutExpired('fixed bootstrap', 120), subprocess.TimeoutExpired('fixed bootstrap', 3), 0]
    with patch.object(namespace['subprocess'], 'Popen', return_value=child) as opened, patch.object(namespace['os'], 'killpg') as killed:
        reject(lambda: original_execute(Path('/opt/java-assessment-update/transactions/fixed/bootstrap/update-bootstrap.sh')))
        check(killed.call_args_list[0].args == (4321, signal.SIGTERM)
              and killed.call_args_list[1].args == (4321, signal.SIGKILL) and killed.call_count == 2)
        check(opened.call_count == 1)
    identity = {name: 'a' * 64 for name in ('config', 'installationId', 'javaPath', 'ca', 'publicKey', 'hostIdSha256')}
    script = Path('/opt/java-assessment-update/transactions') / case['operation'] / case['prepared'] / 'bootstrap/update-bootstrap.sh'
    invoked = []
    recovery_checks = []
    def becoming_active(name):
        if name.endswith('recovery.service'):
            recovery_checks.append(name)
            return {'ActiveState': 'activating', 'MainPID': '0'} if len(recovery_checks) == 1 else {'ActiveState': 'active', 'MainPID': '123'}
        return {'ActiveState': 'inactive', 'MainPID': '0'}
    namespace.update({'verify_state': lambda: (script, identity), 'snapshot_identity': lambda: dict(identity),
                      'execute_bootstrap': lambda path: invoked.append(path) or 0,
                      'service': becoming_active,
                      'trusted': lambda *args, **kwargs: None, 'digest': lambda path: contract['templateSha256']})
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured), patch.object(namespace['time'], 'sleep') as slept:
        namespace['remote_main']()
    check(invoked == [script] and 'RESUME_COMPLETE=HANDOFF_ONLY' in captured.getvalue())
    check(len(recovery_checks) == 2 and slept.call_count == 1)
    namespace['service'] = lambda name: {'ActiveState': 'activating', 'MainPID': '0'}
    incomplete = io.StringIO()
    with contextlib.redirect_stdout(incomplete), patch.object(namespace['time'], 'sleep') as slept:
        reject(lambda: namespace['remote_main']())
    check(slept.call_count == 30 and invoked == [script, script])
    check('RESUME_COMPLETE=HANDOFF_ONLY' not in incomplete.getvalue()
          and 'RESUME_AFTER_CONFIG=' in incomplete.getvalue())
    check('.mkdir(' not in module.REMOTE and 'os.open(' not in module.REMOTE and '.write_text(' not in module.REMOTE)
    with tempfile.TemporaryDirectory(prefix='resume-mac-', dir='/private/tmp') as temp:
        directory = Path(temp)
        key = directory / 'key-placeholder'; key.write_bytes(b'not a real key')
        calls = []
        def fake_ssh(argv, **kwargs):
            calls.append((argv, kwargs))
            return subprocess.CompletedProcess(argv, 0, stdout=captured.getvalue().encode() + b'UNEXPECTED_TOKEN=must-never-be-printed\n')
        visible = io.StringIO()
        with contextlib.redirect_stdout(visible):
            report = module.execute(case['host'], key, 'opc', contract, runner=fake_ssh, output_root=directory / 'evidence')
        check(len(calls) == 1 and calls[0][0][-1] == 'sudo -n /usr/bin/python3 -I -')
        check(calls[0][0][0] == 'ssh' and calls[0][1]['input'] == module.payload(contract))
        check(stat.S_IMODE(report.stat().st_mode) == 0o600 and stat.S_IMODE(report.parent.stat().st_mode) == 0o700)
        check('must-never-be-printed' not in visible.getvalue() and 'must-never-be-printed' not in report.read_text())
        receipt = json.loads(report.read_text())
        check(receipt['markers']['RESUME_BEFORE_CONFIG'] == receipt['markers']['RESUME_AFTER_CONFIG'])
        reject(lambda: module.execute('192.0.2.1', key, 'opc', contract, runner=fake_ssh, output_root=directory / 'evidence'))
        check(len(calls) == 1)
    print('Migration resume PASS ' + str(checks) + ' offline checks; fake SSH/OS, public artifacts only; no host access.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, default=ROOT.parent / 'jms-teste-macos/assessment-pilot/output/dashboard-releases/2.0.9')
    main(parser.parse_args().release)
