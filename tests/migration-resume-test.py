#!/usr/bin/env python3
"""Offline guards, fake SSH/OS and one-time native invocation; no host access."""
import argparse
import contextlib
import copy
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
    except (RuntimeError, subprocess.TimeoutExpired):
        check(True)
        return
    raise AssertionError('Unsafe repair input accepted')


def public_test_case(release):
    """Synthetic host/operation; all hashes come exclusively from public ZIPs."""
    metadata = json.loads((release / 'packages/package-manifest.json').read_text())
    target, bridge_version = metadata['dashboardVersion'], metadata['agentBridgeVersion']
    with zipfile.ZipFile(release / ('packages/assessment-dashboard-runtime-' + target + '.zip')) as archive:
        routes = json.loads(archive.read('dist/agent-update-routes/' + target + '/routes.json'))
        route = next(row for row in routes['routes'] if row['fromVersion'] == '1.5.12')
        bridge = archive.read('dist/agent-bridges/' + bridge_version + '/' + route['id'] + '/collector.jar')
        bundle = archive.read('dist/agent-bundles/' + target + '/bundle.zip')
    with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
        bootstrap = archive.read('bootstrap/update-bootstrap.sh')
    helper_version = '1.5.12' if target == '2.0.11' else '1.5.8'
    with zipfile.ZipFile(release.parent / helper_version / ('packages/assessment-collector-' + helper_version + '.zip')) as archive:
        helper = archive.read('collector.jar')
    sha = lambda raw: hashlib.sha256(raw).hexdigest()
    result = {'schema': 1, 'host': '192.0.2.10', 'operation': '12345678-1234-1234-1234-123456789abc',
            'prepared': 'prepared-0', 'bridgeSha256': sha(bridge),
            'helperSha256': sha(helper), 'scriptSha256': sha(bootstrap)}
    if target == '2.0.11':
        result.update(schema=2, target=target, bridge=bridge_version, bootstrapState='ABSENT',
                      identitySha256={name: 'a' * 64 for name in ('config', 'installationId', 'javaPath', 'ca', 'publicKey')})
    return result


def full_tree_checks(release, contract):
    """Real public 1.5.12/2.0.11 bytes, full safe fake tree and fake systemd."""
    fields_namespace = {'__name__': 'offline_native_fields', 'EXPECTED': contract}
    exec(compile(module.REMOTE, '<offline-native-fields>', 'exec'), fields_namespace)
    native_fields = {'LoadState': 'loaded', 'ActiveState': 'inactive', 'SubState': 'dead', 'MainPID': '0',
                     'ControlPID': '0', 'Job': '', 'ControlGroup': '', 'KillMode': 'control-group'}
    def service_bytes(fields):
        return ('\n'.join(key + '=' + value for key, value in fields.items()) + '\n').encode()
    returned = subprocess.CompletedProcess([], 0, stdout=service_bytes(native_fields))
    with patch.object(fields_namespace['subprocess'], 'run', return_value=returned) as observed:
        parsed = fields_namespace['service']('java-assessment-update.service')
        check(fields_namespace['helper_inactive'](parsed) and parsed['Job'] == '' and parsed['ControlGroup'] == '')
        check('--all' in observed.call_args.args[0] and '--no-pager' in observed.call_args.args[0])
        # Older `show` output could omit default zero/empty properties. It must
        # not be mistaken for an explicit confirmed value, nor called timeout.
        for omitted in ('MainPID', 'ControlPID', 'Job', 'ControlGroup', 'SubState'):
            returned.stdout = service_bytes({key: value for key, value in native_fields.items() if key != omitted})
            reject(lambda: fields_namespace['service']('java-assessment-update.service'))
        returned.stdout = service_bytes(native_fields)
        for key, value in (('MainPID', '21'), ('ControlPID', '22'), ('Job', '23'),
                           ('ControlGroup', '/system.slice/java-assessment-update.service'), ('ActiveState', 'failed')):
            changed = {**native_fields, key: value}
            returned.stdout = service_bytes(changed)
            check(not fields_namespace['helper_inactive'](fields_namespace['service']('java-assessment-update.service')))
        returned.stdout = service_bytes({**native_fields, 'Job': '0'})
        check(fields_namespace['helper_inactive'](fields_namespace['service']('java-assessment-update.service')))
    with zipfile.ZipFile(release.parent / '1.5.12/packages/assessment-collector-1.5.12.zip') as archive:
        original = {name: archive.read(name) for name in archive.namelist()}
    with zipfile.ZipFile(release / 'packages/assessment-dashboard-runtime-2.0.11.zip') as archive:
        route = next(row for row in json.loads(archive.read('dist/agent-update-routes/2.0.11/routes.json'))['routes'] if row['fromVersion'] == '1.5.12')
        bridge = archive.read('dist/agent-bridges/2.0.10/' + route['id'] + '/collector.jar')
        manifest = archive.read('dist/agent-bundles/2.0.11/manifest.json')
        signature = archive.read('dist/agent-bundles/2.0.11/manifest.sig')
        bundle = archive.read('dist/agent-bundles/2.0.11/bundle.zip')
    with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
        stage_members = {name: archive.read(name) for name in archive.namelist()}
    template = stage_members['bootstrap/update-bootstrap.sh'].split(b"<<'UNIT'\n", 1)[1].split(b'\nUNIT\n', 1)[0] + b'\n'
    native_lstat, native_fstat, native_run = os.lstat, os.fstat, subprocess.run
    with tempfile.TemporaryDirectory(prefix='resume-211-full-tree-', dir='/private/tmp') as temporary:
        base = Path(temporary)
        root, home, unit = base / 'collector', base / 'update', base / 'units/recovery.service'
        for directory in (root, home / 'state', unit.parent):
            directory.mkdir(parents=True); directory.chmod(0o700)
        def put(path, data, mode=0o600):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data); path.chmod(mode)
        for name in contract['origin']['supportFiles']:
            put(root / name, original[name], 0o700 if name.endswith('.sh') else 0o600)
        put(root / 'collector.jar', bridge)
        put(home / 'helper.jar', original['collector.jar'])
        put(home / 'scheduler', b'systemd\n')
        ca = base / 'public-ca.pem'; put(ca, original['conf/ca.pem'])
        host = 'host-12345678-1234-1234-1234-123456789abc'
        put(root / 'conf/collector.properties', ('hostId=' + host + '\ntlsCaFile=' + str(ca) + '\n').encode())
        put(root / 'conf/installation-id', b'public synthetic identity\n')
        put(root / 'conf/java.path', b'/usr/bin/java\n')
        put(root / 'conf/root-service-mode', b'system-root\n')
        put(root / 'conf/update-capability', b'update-v1\n')
        tx = home / 'transactions' / contract['operation']
        put(tx / 'manifest.json', manifest); put(tx / 'manifest.sig', signature); put(tx / 'bundle.zip', bundle)
        stage = tx / contract['prepared']
        for name, data in stage_members.items():
            put(stage / name, data, int(contract['stage'][name]['mode'], 8))
        native = {'__name__': 'offline_full_tree', 'EXPECTED': copy.deepcopy(contract)}
        exec(compile(module.REMOTE, '<offline-full-tree>', 'exec'), native)
        native.update(ROOT=root, HOME=home, UNIT=unit)
        def root_info(info, mode=None):
            return SimpleNamespace(st_uid=0, st_mode=info.st_mode if mode is None else mode, st_nlink=info.st_nlink,
                                   st_dev=info.st_dev, st_ino=info.st_ino, st_size=info.st_size)
        def root_lstat(path, *, dir_fd=None):
            info = native_lstat(path, dir_fd=dir_fd)
            mode = stat.S_IFDIR | 0o755 if str(path) == '/private/tmp' else info.st_mode
            return root_info(info, mode)
        props = {'LoadState': 'loaded', 'ActiveState': 'inactive', 'SubState': 'dead', 'MainPID': '0',
                 'ControlPID': '0', 'Job': '', 'ControlGroup': '', 'KillMode': 'control-group',
                 'ProtectSystem': 'full', 'ExecMainStatus': '0', 'Transient': 'no', 'FragmentPath': '/etc/systemd/system/static.service'}
        services = {
            'java-assessment-collector.service': dict(props),
            'java-assessment-collector.timer': {**props, 'ActiveState': 'active'},
            'java-assessment-update.service': {**props, 'ActiveState': 'active', 'SubState': 'running', 'MainPID': '2310',
                                              'ControlGroup': '/system.slice/java-assessment-update.service'},
            'java-assessment-recovery.service': {**props, 'LoadState': 'not-found', 'ProtectSystem': 'no'},
            'java-assessment-bootstrap-' + contract['operation'] + '.service':
                {**props, 'ActiveState': 'failed', 'SubState': 'failed', 'ExecMainStatus': '1', 'ProtectSystem': 'no',
                 'Transient': 'yes', 'FragmentPath': '/run/systemd/transient/java-assessment-bootstrap-' + contract['operation'] + '.service'},
        }
        calls, behavior = [], {}
        def fake_run(argv, **kwargs):
            if argv[0] == '/usr/bin/openssl':
                return native_run(argv, **kwargs)
            check(argv[:1] == ['/usr/bin/systemctl'])
            action, name = argv[1:3]
            calls.append((action, name, services.get(name, {}).get('ActiveState'), services.get(name, {}).get('MainPID')))
            if action == 'show':
                assert '--all' in argv and '--no-pager' in argv
                result = services[name]
                return subprocess.CompletedProcess(argv, 0, stdout=('\n'.join(k + '=' + v for k, v in result.items()) + '\n').encode())
            check(name == 'java-assessment-update.service' and action in ('stop', 'start'))
            if action == 'stop':
                if behavior.get('stop_timeout'):
                    raise subprocess.TimeoutExpired(argv, 120)
                if not behavior.get('stay_active'):
                    services[name].update(ActiveState='inactive', SubState='dead', MainPID='0', ControlGroup='')
                    put(home / 'stop', b'')
                if behavior.get('v1_during_stop'):
                    put(home / 'journal.json', b'{}')
                return subprocess.CompletedProcess(argv, behavior.get('stop_code', 0))
            # Native 1.5.12 ExecStart=...loop leaves an existing stop marker in
            # place, so it cannot remain active unless restoration cleared it.
            if (home / 'stop').exists():
                return subprocess.CompletedProcess(argv, 0)
            services[name].update(ActiveState='active', SubState='running', MainPID='5000',
                                  ControlGroup='/system.slice/java-assessment-update.service')
            return subprocess.CompletedProcess(argv, 0)
        with patch.object(native['os'], 'lstat', side_effect=root_lstat), \
             patch.object(native['os'], 'fstat', side_effect=lambda fd: root_info(native_fstat(fd))), \
             patch.object(native['os'], 'geteuid', return_value=0), patch.object(native['sys'], 'platform', 'linux'), \
             patch.object(native['subprocess'], 'run', side_effect=fake_run), patch.object(native['time'], 'sleep'):
            identity = native['snapshot_identity']()
            native['EXPECTED']['identitySha256'] = {name: identity[name] for name in contract['identitySha256']}
            journal = {'schema': 1, 'operationId': contract['operation'], 'phase': 'PREPARED', 'hostId': host,
                       'targetVersion': contract['target'], 'previousVersion': '1.5.12', 'previousPointer': None,
                       'preparedDirectory': contract['prepared'], 'bundleSha256': contract['bundleSha256'],
                       'manifestSha256': contract['manifestSha256'], 'configSha256': identity['config'],
                       'javaPathSha256': identity['javaPath'], 'caSha256': identity['ca'], 'pinSha256': identity['publicKey']}
            put(home / 'state/transaction.json', json.dumps(journal).encode())
            signed_script, before = native['verify_state']()
            check(signed_script == stage / 'bootstrap/update-bootstrap.sh' and before == identity)
            check(not any(call[0] in ('stop', 'start') for call in calls))
            native['HELPER_CGROUPS'] = (base / 'cgroup-v2/owned-unit', base / 'cgroup-v1/owned-unit')
            # Full guards reject corruption before stopping or executing code.
            for path, changed in ((root / 'update-local.sh', b'changed'),
                                  (stage / 'bootstrap/update-bootstrap.sh', b'changed'),
                                  (root / 'conf/root-service-mode', b'other\n'),
                                  (root / 'conf/update-capability', b'update-v3\n')):
                saved, mode = path.read_bytes(), stat.S_IMODE(path.stat().st_mode)
                put(path, changed, mode); reject(native['verify_state']); put(path, saved, mode)
            for directory in (home / 'bootstrap', home / 'state/origin'):
                directory.mkdir(); reject(native['verify_state']); directory.rmdir()
            transient = services['java-assessment-bootstrap-' + contract['operation'] + '.service']
            for field, changed in (('MainPID', '456'), ('ActiveState', 'active'), ('ExecMainStatus', '2'),
                                   ('ProtectSystem', 'full'), ('Transient', 'no'), ('FragmentPath', '/wrong')):
                saved = transient[field]; transient[field] = changed
                reject(native['verify_state']); transient[field] = saved
            recovery = services['java-assessment-recovery.service']
            for field, changed in (('ControlPID', '456'), ('Job', '457'), ('ControlGroup', '/system.slice/unexpected.service')):
                saved = recovery[field]; recovery[field] = changed
                reject(native['verify_state']); recovery[field] = saved
            check(not any(call[0] in ('stop', 'start') for call in calls))
            # The actual signed path is invoked only after confirmed inactive.
            def execute_signed(path):
                check(path == signed_script and services['java-assessment-update.service']['ActiveState'] == 'inactive'
                      and services['java-assessment-update.service']['MainPID'] == '0')
                calls.append(('bootstrap', str(path)))
                if behavior.get('create_unit') or behavior.get('success'):
                    put(unit, template, 0o644)
                if behavior.get('start_recovery') or behavior.get('success'):
                    services['java-assessment-recovery.service'].update(LoadState='loaded', ActiveState='active', MainPID='7000')
                if behavior.get('queue_recovery'):
                    services['java-assessment-recovery.service']['Job'] = '458'
                if behavior.get('change_journal'):
                    put(home / 'state/transaction.json', json.dumps({**journal, 'phase': 'INSTALLING'}).encode())
                return 0 if behavior.get('success') else 1
            native['execute_bootstrap'] = execute_signed
            def reset(active=True):
                behavior.clear(); calls.clear(); native['CHECKPOINT'].clear()
                services['java-assessment-update.service'].update(ActiveState='active' if active else 'inactive',
                    SubState='running' if active else 'dead', MainPID='2310' if active else '0', ControlPID='0', Job='',
                    ControlGroup='/system.slice/java-assessment-update.service' if active else '')
                services['java-assessment-recovery.service'].update(LoadState='not-found', ActiveState='inactive', MainPID='0',
                                                                  ControlPID='0', Job='', ControlGroup='')
                if unit.exists(): unit.unlink()
                if (home / 'journal.json').exists(): (home / 'journal.json').unlink()
                if os.path.lexists(str(home / 'stop')): (home / 'stop').unlink()
                if not active: put(home / 'stop', b'')
                put(home / 'state/transaction.json', json.dumps(journal).encode())
            reset(); behavior['success'] = True
            captured = io.StringIO()
            with contextlib.redirect_stdout(captured): native['remote_main']()
            actions = [call[0] for call in calls]
            check(actions.index('stop') < actions.index('bootstrap') and actions.count('stop') == 1 and 'start' not in actions)
            between = calls[actions.index('stop') + 1:actions.index('bootstrap')]
            check(any(call[:2] == ('show', 'java-assessment-update.service') and call[2:] == ('inactive', '0') for call in between))
            check('RESUME_COMPLETE=HANDOFF_ONLY' in captured.getvalue() and native['snapshot_identity']() == before)
            # Execution/stop failures restore only the previously active helper,
            # and never after created/active recovery or changed transaction.
            for options, restart in (({}, True), ({'stop_code': 1}, True), ({'stop_timeout': True}, False),
                                     ({'stay_active': True}, False), ({'create_unit': True}, False),
                                     ({'start_recovery': True}, False), ({'change_journal': True}, False),
                                     ({'v1_during_stop': True}, False), ({'queue_recovery': True}, False)):
                reset(); behavior.update(options)
                output = io.StringIO()
                with contextlib.redirect_stdout(output): reject(native['remote_main'])
                check(any(call[0] == 'start' for call in calls) is restart)
                check('RESUME_COMPLETE=HANDOFF_ONLY' not in output.getvalue())
                if options.get('stop_code') or options.get('stop_timeout') or options.get('stay_active') or options.get('v1_during_stop'):
                    check(not any(call[0] == 'bootstrap' for call in calls))
            reset(active=False)
            with contextlib.redirect_stdout(io.StringIO()): reject(native['remote_main'])
            check(not any(call[0] in ('stop', 'start') for call in calls) and (home / 'stop').exists())
            reset(active=False); behavior['success'] = True
            with contextlib.redirect_stdout(io.StringIO()): native['remote_main']()
            check(not any(call[0] in ('stop', 'start') for call in calls) and (home / 'stop').exists())


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
    journal = {'schema': 1, 'operationId': case['operation'], 'phase': 'PREPARED', 'targetVersion': contract['target'],
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
    # Shared sealed-state behavior remains exactly the previous schema1 flow;
    # the absent state is exercised separately with the full real file tree.
    namespace['EXPECTED'] = {**contract, 'bootstrapState': 'SEALED'}
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
    check('.mkdir(' not in module.REMOTE and '.write_text(' not in module.REMOTE
          and "os.unlink('stop', dir_fd=parent)" in module.REMOTE and 'reset-failed' not in module.REMOTE)
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
        def failed_result(raw, code, expected):
            attempts = []
            def failed_ssh(argv, **kwargs):
                attempts.append(argv)
                return subprocess.CompletedProcess(argv, code, stdout=raw)
            reports_before = set((directory / 'evidence').glob('*/resume-result.json'))
            with contextlib.redirect_stdout(io.StringIO()):
                try:
                    module.execute(case['host'], key, 'opc', contract, runner=failed_ssh,
                                   output_root=directory / 'evidence')
                except RuntimeError as failure:
                    check(str(failure) == expected)
                else:
                    raise AssertionError('Failed SSH accepted')
            reports_after = set((directory / 'evidence').glob('*/resume-result.json'))
            check(len(attempts) == 1 and len(reports_after - reports_before) == 1)
            saved = json.loads(next(iter(reports_after - reports_before)).read_text())
            check(saved['sshExitCode'] == code)
        failed_result(b'', 255, 'SSH_FAILED_WITHOUT_REMOTE_CONFIRMATION')
        failed_result(b'', 0, 'REMOTE_RESULT_MISSING_CHECK_CONNECTION')
        failed_result(b'RESUME_STOP=STAGED_FILE_CHANGED\n', 1, 'STAGED_FILE_CHANGED')
        failed_result(b'RESUME_BEFORE_CONFIG=' + b'a' * 64 + b'\n', 124,
                      'SSH_TIMEOUT_RESULT_UNKNOWN_DO_NOT_REPEAT_BLINDLY')
        changed = captured.getvalue().replace('RESUME_AFTER_CONFIG=' + 'a' * 64,
                                              'RESUME_AFTER_CONFIG=' + 'b' * 64)
        failed_result(changed.encode(), 0, 'PRESERVATION_NOT_CONFIRMED_CHECK_PRIVATE_REPORT')
    if contract['bootstrapState'] == 'ABSENT':
        full_tree_checks(release, contract)
    print('Migration resume PASS ' + str(checks) + ' offline checks; fake SSH/OS, public artifacts only; no host access.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, default=ROOT.parent / 'jms-teste-macos/assessment-pilot/output/dashboard-releases/2.0.9')
    main(parser.parse_args().release)
