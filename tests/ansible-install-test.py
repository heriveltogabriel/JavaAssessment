"""Local-only Ansible integration: fake downloader/installer; no SSH, Java or /opt writes.
Run with a Python environment containing ansible-core, PyYAML and Jinja2.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'install/ansible'
TOKEN = 'eia1_' + 'a' * 64
ANSIBLE = str(Path(sys.executable).parent / 'ansible-playbook')


def tasks(items):
    for task in items:
        yield task
        for kind in ('block', 'rescue', 'always'):
            yield from tasks(task.get(kind, []))


class InstallAutomationTest(unittest.TestCase):
    def run_fixture(self, mode='ok', environment='env-producao', existing=False, partial=False):
        with tempfile.TemporaryDirectory(prefix='ja-ansible-fixture-') as temporary:
            directory = Path(temporary)
            target = directory / 'target'
            target.mkdir()
            binaries = directory / 'bin'
            binaries.mkdir()
            def executable(name, source):
                p = binaries / name
                p.write_text(source)
                p.chmod(0o700)
            executable('uname', '#!/bin/sh\necho Linux\n')
            executable('sleep', '#!/bin/sh\nexit 0\n')
            executable('stat', f'#!{sys.executable}\nimport os,sys\nprint(os.stat(sys.argv[-1]).st_uid)\n')
            executable('sha256sum', f'''#!{sys.executable}
import hashlib,sys
from pathlib import Path
for line in Path(sys.argv[2]).read_text().splitlines():
 digest,name=line.split('  ',1)
 if hashlib.sha256(Path(name).read_bytes()).hexdigest()!=digest: sys.exit(1)
''')
            executable('wget', f'''#!{sys.executable}
import json,os,sys,shutil,stat
from pathlib import Path
config=Path(os.environ['WGETRC'])
assert stat.S_IMODE(config.stat().st_mode)==0o600
text=config.read_text()
assert 'header = Authorization: Bearer {TOKEN}' in text
assert 'max_redirect = 0' in text and 'check_certificate = on' in text
assert '{TOKEN}' not in ' '.join(sys.argv)
root=Path(os.environ['JA_FIXTURE'])
counter=root/'counter'
n=int(counter.read_text())+1 if counter.exists() else 1
counter.write_text(str(n))
(root/'request').write_text(sys.argv[-1])
mode=os.environ['JA_FIXTURE_MODE']
if mode=='network' or (mode=='retry' and n==1): sys.exit(4)
output=next(line.split(' = ',1)[1] for line in text.splitlines() if line.startswith('output_document'))
(root/'stage').write_text(str(Path(output).parent))
shutil.copyfile(root/'agent.zip',output)
''')
            root = target / 'java-assessment-collector'
            installer = f'''#!/bin/sh
set -eu
test "$1" = --system-root
mkdir -p {shlex.quote(str(root / 'conf'))}
printf fixture-id > {shlex.quote(str(root / 'conf/installation-id'))}
printf fixture-config > {shlex.quote(str(root / 'conf/collector.properties'))}
printf jar > {shlex.quote(str(root / 'collector.jar'))}
printf '#!/bin/sh\nexit 0\n' > {shlex.quote(str(root / 'root-service.sh'))}
echo {TOKEN}
'''.encode()
            if mode == 'install':
                installer = ('#!/bin/sh\necho ' + TOKEN + '\nexit 7\n').encode()
            contents = {'install.sh': installer, 'conf/bootstrap.properties': b'fixture-private-registration'}
            contents['SHA256SUMS'] = ''.join(
                hashlib.sha256(data).hexdigest() + '  ' + name + '\n' for name, data in contents.items()).encode()
            if mode == 'checksum':
                contents['install.sh'] += b'# changed after checksum\n'
            if mode == 'traversal':
                contents['../escape'] = b'not permitted'
            with zipfile.ZipFile(directory / 'agent.zip', 'w') as archive:
                for name, data in contents.items(): archive.writestr(name, data)
            if existing:
                (root / 'conf').mkdir(parents=True)
                (root / 'conf/installation-id').write_text('old-identity')
                (root / 'conf/collector.properties').write_text('old-config')
                (root / 'collector.jar').write_text('old-jar')
            elif partial:
                root.mkdir()
            # Public-only test PEM; wget is fake and asserts TLS settings instead of networking.
            (directory / 'ca.pem').write_text('-----BEGIN CERTIFICATE-----\nYWJj\n-----END CERTIFICATE-----\n')
            inventory = {'all': {'children': {'assessment_agents': {'hosts': {'fixture': {
                'ansible_connection': 'local',
                'ansible_python_interpreter': sys.executable,
                'ansible_remote_tmp': str(directory / 'remote'),
                'assessment_dashboard_url': 'https://dashboard.example.invalid',
                'assessment_environment_id': environment,
                'assessment_install_token': TOKEN,
                'assessment_ca_file': str(directory / 'ca.pem'),
            }}}}}}
            play = yaml.safe_load((SOURCE / 'install-agents.yml').read_text())
            task_list = list(tasks(play[0]['tasks']))
            # These boundaries must be present in the shipped playbook.
            download = next(t for t in task_list if 'ansible.builtin.script' in t)
            install = next(t for t in task_list if t.get('name') == 'Instalar e configurar serviços com sudo')
            self.assertFalse(download['become'])
            self.assertTrue(install['become'])
            self.assertTrue(download['no_log'])
            self.assertTrue(install['no_log'])
            # The test changes only OS/privilege boundaries, not task conditions or template.
            for task in task_list:
                if 'become' in task: task['become'] = False
                if 'ansible.builtin.raw' in task:
                    task['ansible.builtin.raw'] = task['ansible.builtin.raw'].replace('/opt/java-assessment-', str(target / 'java-assessment-'))
                if 'retries' in task: task['retries'] = 1
                if 'delay' in task: task['delay'] = 0
                if 'ansible.builtin.template' in task:
                    task['ansible.builtin.template']['src'] = str(SOURCE / 'templates/download-agent.sh.j2')
            (directory / 'inventory.yml').write_text(yaml.safe_dump(inventory))
            (directory / 'play.yml').write_text(yaml.safe_dump(play, sort_keys=False))
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                       JA_FIXTURE=str(directory), JA_FIXTURE_MODE=mode,
                       ANSIBLE_HOME=str(directory / 'ansible-home'),
                       ANSIBLE_LOCAL_TEMP=str(directory / 'local'),
                       ANSIBLE_REMOTE_TEMP=str(directory / 'remote-localhost'),
                       ANSIBLE_NOCOLOR='1', ANSIBLE_HOST_KEY_CHECKING='True',
                       TMPDIR=str(directory))
            command = [ANSIBLE, '-i', str(directory / 'inventory.yml'), str(directory / 'play.yml')]
            if mode == 'check': command.append('--check')
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=100)
            output = result.stdout + result.stderr
            self.assertNotIn(TOKEN, output, 'Credential leaked through Ansible output')
            successful = mode in ('ok', 'retry') and not partial
            self.assertEqual(result.returncode == 0, successful, output)
            self.assertFalse(list(directory.glob('java-assessment-ansible-*')), 'Controller secret not cleaned')
            stage_record = directory / 'stage'
            if stage_record.exists():
                self.assertFalse(Path(stage_record.read_text()).exists(), 'Remote staging not cleaned')
            if existing:
                self.assertFalse((directory / 'request').exists())
                self.assertEqual((root / 'conf/installation-id').read_text(), 'old-identity')
                self.assertIn('Agente já cadastrado', output)
            elif successful:
                self.assertEqual((root / 'conf/installation-id').read_text(), 'fixture-id')
                self.assertEqual((directory / 'request').read_text(), 'https://dashboard.example.invalid/api/install/package?environmentId=' + environment)
            if partial or mode == 'check': self.assertFalse((directory / 'request').exists())
            if mode == 'retry': self.assertEqual((directory / 'counter').read_text(), '2')
            if mode == 'network': self.assertEqual((directory / 'counter').read_text(), '6')
            if not successful and not partial: self.assertFalse(root.exists(), 'Installer ran after failed validation/download')
            if mode in ('checksum', 'traversal', 'network', 'install'):
                self.assertTrue((directory / 'request').exists(), output)
                self.assertIn('rescued=1', output, output)

    def test_new_production(self): self.run_fixture()
    def test_new_homologation(self): self.run_fixture(environment='env-homologacao')
    def test_existing_preserved(self): self.run_fixture(existing=True)
    def test_partial_refused(self): self.run_fixture(partial=True)
    def test_retry(self): self.run_fixture('retry')
    def test_checksum_refused(self): self.run_fixture('checksum')
    def test_path_traversal_refused(self): self.run_fixture('traversal')
    def test_network_failure(self): self.run_fixture('network')
    def test_install_failure_cleanup_and_no_secret_output(self): self.run_fixture('install')
    def test_check_refused_without_changes(self): self.run_fixture('check')


if __name__ == '__main__': unittest.main()
