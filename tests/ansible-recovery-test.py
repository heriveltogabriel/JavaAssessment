import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from ansible_recovery_fixture import RecoveryFixture

class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='ja-recovery-', dir='/private/tmp')
        self.addCleanup(self.temp.cleanup)
        self.f=RecoveryFixture(Path(self.temp.name))

    def run_helper(self, action='apply', mode='auto', success=True):
        args=['/bin/sh',str(self.f.script),action]
        if action=='apply':args += [mode,str(self.f.source),'']
        p=subprocess.run(args,env=self.f.env,text=True,capture_output=True,timeout=120)
        self.assertEqual(p.returncode==0,success,p.stdout+p.stderr)
        self.assertNotIn('existing-private-token',p.stdout+p.stderr)
        return p.stdout+p.stderr

    def test_sysv_healthy_preserved(self):
        self.f.installation(v3=True)
        self.f.sysv(v3=True,active=True)
        self.assertIn('PRESERVED',self.run_helper())
        self.assertFalse((self.f.base/'installed').exists())

    def test_sysv_v3_services_recovered(self):
        self.f.installation(v3=True,active=False)
        self.f.sysv(v3=True,active=False)
        self.assertIn('SERVICES_RECOVERED',self.run_helper())
        self.assertIn('HEALTHY',self.run_helper('probe'))

    def test_sysv_clean_reinstall(self):
        self.f.installation(v3=True)
        self.f.sysv(v3=True,active=True)
        self.assertIn('INSTALLED',self.run_helper(mode='reinstall'))

    def test_fresh_install(self):
        self.assertIn('JA_RESULT=INSTALLED',self.run_helper())

    def test_healthy_oneshot_is_preserved(self):
        self.f.installation()
        self.assertIn('JA_STATE=HEALTHY',self.run_helper('probe'))
        self.assertIn('PRESERVED',self.run_helper())
        self.assertFalse((self.f.base/'installed').exists())

    def test_classic_repair_keeps_identity_and_backup(self):
        self.f.installation(active=False)
        cfg=(self.f.root/'conf/collector.properties').read_bytes()
        self.assertIn('INSTALLED',self.run_helper())
        self.assertEqual((self.f.root/'conf/collector.properties').read_bytes(),cfg)
        backups=list(self.f.opt.glob('java-assessment-ansible.*/backup'))
        self.assertEqual(len(backups),1)
        self.assertEqual(backups[0].stat().st_mode&0o777,0o700)
        self.assertEqual((backups[0]/'java-assessment-collector/conf/collector.properties').read_bytes(),cfg)

    def test_v3_reactivates_services_without_replacing_signed_files(self):
        self.f.installation(v3=True,active=False)
        self.assertIn('SERVICES_RECOVERED',self.run_helper())
        self.assertFalse((self.f.base/'installed').exists())
        self.assertIn('HEALTHY',self.run_helper('probe'))

    def test_empty_preregistration_residue_is_preserved_outside_target(self):
        self.f.root.mkdir()
        self.assertIn('INSTALLED',self.run_helper())
        self.assertTrue(list(self.f.opt.glob('java-assessment-ansible.*/residue')))

    def test_partial_identity_not_erased(self):
        (self.f.root/'conf').mkdir(parents=True)
        (self.f.root/'conf/installation-id').write_text('partial')
        self.assertIn('RESIDUE_REQUIRES_DIAGNOSIS',self.run_helper(success=False))
        self.assertEqual((self.f.root/'conf/installation-id').read_text(),'partial')

    def test_forced_reinstall_uses_official_removal(self):
        self.f.installation(v3=True)
        self.assertIn('INSTALLED',self.run_helper(mode='reinstall'))
        self.assertTrue(list(self.f.opt.glob('java-assessment-ansible.*/backup/java-assessment-collector/current.json')))

    def test_official_removal_refusal_never_forces_rm(self):
        self.f.installation(v3=True)
        (self.f.base/'uninstall-refuse').touch()
        self.assertIn('UNINSTALL_REFUSED',self.run_helper(mode='reinstall',success=False))
        self.assertTrue((self.f.root/'current.json').exists())
        self.assertFalse((self.f.base/'installed').exists())

    def test_failed_java_preserves_running_services(self):
        self.f.installation(v3=True)
        before=self.f.services.read_bytes()
        (self.f.base/'java-bad').touch()
        self.assertIn('PREFLIGHT_JAVA',self.run_helper(mode='reinstall',success=False))
        self.assertEqual(self.f.services.read_bytes(),before)
        self.assertFalse((self.f.base/'actions').exists())

    def test_bad_checksum_preserves_running_services(self):
        self.f.installation()
        (self.f.source/'collector.jar').write_text('corrupted')
        self.assertIn('PREFLIGHT_PACKAGE',self.run_helper(mode='reinstall',success=False))
        self.assertFalse((self.f.base/'actions').exists())

    def test_pending_update_blocks_before_download_copy(self):
        self.f.installation(v3=True)
        (self.f.update/'state').mkdir()
        (self.f.update/'state/transaction.json').write_text('{}')
        self.assertIn('PENDING_OPERATION',self.run_helper(mode='reinstall',success=False))
        self.assertFalse([p for p in self.f.opt.glob('java-assessment-ansible.*') if p.is_dir()])

    def test_root_symlink_refused(self):
        self.f.root.symlink_to(self.f.base/'other')
        self.assertIn('UNTRUSTED_TREE',self.run_helper('probe',success=False))

    def test_nested_symlink_refused(self):
        self.f.installation()
        (self.f.root/'bad').symlink_to('/etc')
        self.assertIn('UNSAFE_TREE_MEMBER',self.run_helper(mode='reinstall',success=False))

    def test_ownership_refused(self):
        self.f.installation(active=False)
        (self.f.base/'bad-owner').touch()
        self.run_helper(success=False)
        self.assertFalse((self.f.base/'installed').exists())

    def test_unexpected_unit_refused(self):
        self.f.installation(active=False)
        (self.f.units/'java-assessment-collector.service').write_text('changed')
        self.assertIn('SERVICE_DEFINITION_CHANGED',self.run_helper(success=False))
        self.assertFalse((self.f.base/'actions').exists())

    def test_missing_v3_pointer_is_never_classic_reinstalled(self):
        self.f.installation(v3=True,active=False)
        (self.f.root/'current.json').unlink()
        self.assertIn('V3_POINTER_MISSING',self.run_helper(success=False))
        self.assertFalse((self.f.base/'installed').exists())

    def test_invalid_v3_code_is_not_started(self):
        self.f.installation(v3=True,active=False)
        (self.f.base/'v3-code-bad').touch()
        self.assertIn('V3_CODE_INVALID',self.run_helper(success=False))
        self.assertFalse((self.f.base/'actions').exists())

    def test_residue_with_open_file_not_moved(self):
        self.f.root.mkdir()
        proc=self.f.base/'proc/123'
        (proc/'fd').mkdir(parents=True)
        (proc/'cmdline').write_bytes(b'java\0')
        (proc/'fd/1').symlink_to(self.f.root/'old.jar')
        self.assertIn('AGENT_PROCESS_PRESENT',self.run_helper(success=False))
        self.assertTrue(self.f.root.exists())

    def test_symlink_in_package_is_refused(self):
        (self.f.source/'link').symlink_to('/etc')
        self.assertIn('PACKAGE_MEMBER',self.run_helper(success=False))
        self.assertFalse((self.f.base/'installed').exists())

    def test_nested_mount_is_refused(self):
        self.f.installation()
        (self.f.base/'proc/mounts').write_text('dev '+str(self.f.root/'logs')+' ext4 rw 0 0\n')
        self.assertIn('MOUNTED_TREE',self.run_helper('probe',success=False))

    def test_custom_service_override_is_refused(self):
        self.f.installation(active=False)
        states=json.loads(self.f.services.read_text())
        states['java-assessment-collector.service']['DropInPaths']='/etc/custom.conf'
        self.f.services.write_text(json.dumps(states))
        self.assertIn('SERVICE_OVERRIDE',self.run_helper(success=False))
        self.assertFalse((self.f.base/'actions').exists())

    def test_automation_lock_busy(self):
        (self.f.base/'busy').touch()
        self.assertIn('AUTOMATION_BUSY',self.run_helper(success=False))

    def test_install_failure_keeps_backup(self):
        self.f.installation(active=False)
        (self.f.base/'install-bad').touch()
        self.assertIn('INSTALL_FAILED',self.run_helper(success=False))
        self.assertTrue(list(self.f.opt.glob('java-assessment-ansible.*/backup')))

    def test_unknown_residue_never_moved(self):
        self.f.root.mkdir()
        (self.f.root/'customer.txt').write_text('keep')
        self.assertIn('UNKNOWN_RESIDUE',self.run_helper(success=False))
        self.assertEqual((self.f.root/'customer.txt').read_text(),'keep')

if __name__=='__main__':unittest.main()
