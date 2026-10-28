"""Local Ansible integration with the actual recovery helper and simulated Linux.
No SSH, network, system Java or /opt changes. Requires ansible-core and PyYAML.
"""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
import yaml
from ansible_recovery_fixture import RecoveryFixture, REPO

SOURCE=REPO/'install/ansible'
TOKEN='eia1_'+'a'*64
ANSIBLE=str(Path(sys.executable).parent/'ansible-playbook')

def tasks(items):
    for task in items:
        yield task
        for kind in ('block','rescue','always'):yield from tasks(task.get(kind,[]))

class InstallAutomationTest(unittest.TestCase):
    def run_fixture(self,mode='ok',environment='env-producao',existing=False,partial=False,reinstall=False,v3=False,java_policy=False):
        with tempfile.TemporaryDirectory(prefix='ja-ansible-fixture-',dir='/private/tmp') as temporary:
            directory=Path(temporary);f=RecoveryFixture(directory)
            if java_policy:
                f.java_candidate('usr/lib/jvm/ibm-runtime/bin/java','1.8','IBM Corporation')
                if mode!='java-policy':f.java_candidate('usr/java/customer-specific-update/bin/java','17','Eclipse Adoptium')
            # Download staging really uses /tmp; all product paths remain isolated.
            f.script.write_text(f.script.read_text().replace(str(directory/'tmp/java-assessment-ansible.'),'/tmp/java-assessment-ansible.'))
            f.executable('sleep','#!/bin/sh\nexit 0\n')
            f.executable('wget',f'''#!{sys.executable}
import os,sys,shutil,stat
from pathlib import Path
config=Path(os.environ['WGETRC']);assert stat.S_IMODE(config.stat().st_mode)==0o600
text=config.read_text()
assert 'header = Authorization: Bearer {TOKEN}' in text
assert 'max_redirect = 0' in text and 'check_certificate = on' in text
assert '{TOKEN}' not in ' '.join(sys.argv)
root=Path(os.environ['JA_TEST_ROOT']);counter=root/'counter'
n=int(counter.read_text())+1 if counter.exists() else 1;counter.write_text(str(n))
(root/'request').write_text(sys.argv[-1]);mode=os.environ['JA_FIXTURE_MODE']
if mode=='network' or (mode=='retry' and n==1):sys.exit(4)
output=next(line.split(' = ',1)[1] for line in text.splitlines() if line.startswith('output_document'))
(root/'stage').write_text(str(Path(output).parent));shutil.copyfile(root/'agent.zip',output)
''')
            # Make sure private installer output cannot leak through Ansible.
            p=f.source/'install.sh';p.write_text(p.read_text().replace('set -eu','set -eu\necho '+TOKEN))
            f.seal()
            if mode=='checksum':(f.source/'collector.jar').write_text('corrupted')
            if mode=='traversal':(f.source.parent/'escape').write_text('not permitted')
            with zipfile.ZipFile(directory/'agent.zip','w') as archive:
                for p in f.source.rglob('*'):
                    if p.is_file():archive.write(p,str(p.relative_to(f.source)))
                if mode=='traversal':archive.writestr('../escape','not permitted')
            if existing:f.installation(v3=v3,active=not partial)
            elif partial:f.root.mkdir()
            if mode=='java':(directory/'java-bad').touch()
            if mode=='install':(directory/'install-bad').touch()
            (directory/'ca.pem').write_text('-----BEGIN CERTIFICATE-----\nYWJj\n-----END CERTIFICATE-----\n')
            inventory={'all':{'children':{'assessment_agents':{'hosts':{'fixture':{
                'ansible_connection':'local','ansible_python_interpreter':sys.executable,
                'ansible_remote_tmp':str(directory/'remote'),
                'assessment_dashboard_url':'https://dashboard.example.invalid',
                'assessment_environment_id':environment,'assessment_install_token':TOKEN,
                'assessment_ca_file':str(directory/'ca.pem'),
                'assessment_install_mode':'reinstall' if reinstall else 'auto',
            }}}}}}
            if java_policy:
                inventory['all']['children']['assessment_agents']['hosts']['fixture'].update(
                    assessment_java_min_major=8,assessment_java_exclude_ibm=True)
            play=yaml.safe_load((SOURCE/'install-agents.yml').read_text())
            task_list=list(tasks(play[0]['tasks']))
            download=next(t for t in task_list if t.get('name')=='Baixar e verificar pacote como usuário SSH')
            install=next(t for t in task_list if t.get('name')=='Instalar e configurar serviços com sudo')
            self.assertFalse(download['become']);self.assertTrue(install['become'])
            self.assertTrue(download['no_log']);self.assertTrue(install['no_log'])
            for task in task_list:
                if 'become' in task:task['become']=False
                if 'ansible.builtin.script' in task:
                    task['ansible.builtin.script']['cmd']=task['ansible.builtin.script']['cmd'].replace('files/manage-agent.sh',str(f.script))
                if 'ansible.builtin.template' in task:task['ansible.builtin.template']['src']=str(SOURCE/'templates/download-agent.sh.j2')
            (directory/'inventory.yml').write_text(yaml.safe_dump(inventory))
            (directory/'play.yml').write_text(yaml.safe_dump(play,sort_keys=False))
            env=dict(f.env,JA_FIXTURE_MODE=mode,ANSIBLE_HOME=str(directory/'ansible-home'),
                ANSIBLE_LOCAL_TEMP=str(directory/'local'),ANSIBLE_REMOTE_TEMP=str(directory/'remote-localhost'),
                ANSIBLE_NOCOLOR='1',ANSIBLE_HOST_KEY_CHECKING='True',TMPDIR=str(directory))
            command=[ANSIBLE,'-i',str(directory/'inventory.yml'),str(directory/'play.yml')]
            if mode=='check':command.append('--check')
            result=subprocess.run(command,env=env,capture_output=True,text=True,timeout=150)
            output=result.stdout+result.stderr
            self.assertNotIn(TOKEN,output,'Credential leaked through Ansible output')
            self.assertNotIn('existing-private-token',output)
            successful=mode in ('ok','retry')
            self.assertEqual(result.returncode==0,successful,output)
            self.assertFalse(list(directory.glob('java-assessment-ansible-*')),'Controller secret not cleaned')
            stage_record=directory/'stage'
            if stage_record.exists():self.assertFalse(Path(stage_record.read_text()).exists(),'Remote staging not cleaned')
            if existing and not partial and not reinstall:
                self.assertFalse((directory/'request').exists())
                self.assertIn('instalação preservada',output)
            elif successful:
                self.assertTrue((f.root/'conf/installation-id').is_file())
                self.assertEqual((directory/'request').read_text(),'https://dashboard.example.invalid/api/install/package?environmentId='+environment)
            if java_policy and successful and not existing:
                self.assertIn('customer-specific-update/bin/java',(directory/'install-args').read_text())
            if mode=='java-policy':self.assertIn('JAVA_POLICY_NO_MATCH',output)
            if mode=='retry':self.assertEqual((directory/'counter').read_text(),'2')
            if mode=='network':self.assertEqual((directory/'counter').read_text(),'6')
            if mode in ('checksum','traversal','network','java','check','java-policy'):
                self.assertFalse((directory/'installed').exists())
            if mode in ('checksum','traversal','network','install','java','java-policy'):
                self.assertIn('rescued=1',output)
            if existing and not reinstall:
                self.assertIn('existing-private-token',(f.root/'conf/collector.properties').read_text())

    def test_java_policy_finds_non_ibm_without_fixed_path(self):self.run_fixture(java_policy=True)
    def test_java_policy_no_match_has_actionable_error(self):self.run_fixture(mode='java-policy',java_policy=True)
    def test_java_policy_keeps_healthy_agent(self):self.run_fixture(existing=True,java_policy=True)

    def test_new_production(self):self.run_fixture()
    def test_new_homologation(self):self.run_fixture(environment='env-homologacao')
    def test_existing_preserved(self):self.run_fixture(existing=True)
    def test_residue_recovered(self):self.run_fixture(partial=True)
    def test_classic_recovered(self):self.run_fixture(existing=True,partial=True)
    def test_v3_services_recovered(self):self.run_fixture(existing=True,partial=True,v3=True)
    def test_explicit_clean_reinstall(self):self.run_fixture(existing=True,reinstall=True,v3=True)
    def test_retry(self):self.run_fixture('retry')
    def test_checksum_refused(self):self.run_fixture('checksum')
    def test_path_traversal_refused(self):self.run_fixture('traversal')
    def test_network_failure(self):self.run_fixture('network')
    def test_invalid_java_refused(self):self.run_fixture('java')
    def test_install_failure_cleanup_and_no_secret_output(self):self.run_fixture('install')
    def test_check_refused_without_changes(self):self.run_fixture('check')

if __name__=='__main__':unittest.main()
