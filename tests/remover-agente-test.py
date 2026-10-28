"""Run shell functions against disposable fixtures; never remove a real agent."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/remover-agente.sh'
FUNCTIONS = SCRIPT.read_text().split('# MAIN:')[0]

class Removal(unittest.TestCase):
    def run_case(self, setup, action):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root, work = base/'collector', base/'diagnostic'
            (root/'collector-spool').mkdir(parents=True)
            work.mkdir()
            setup(root)
            # Trust checks are Linux/GNU-specific. Mock only these in macOS
            # fixtures; production always uses real ownership/mount checks.
            code = FUNCTIONS + '\nROOT=' + shlex.quote(str(root)) + '\nWORK=' + shlex.quote(str(work)) + '\nUPDATE=' + shlex.quote(str(base/'update')) + '\nHELPER=' + shlex.quote(str(base/'helper')) + '\ncheck_tree() { test -d "$1" && test ! -L "$1" || fail UNSAFE_TREE; }\n' + action
            result = subprocess.run(['/bin/sh', '-c', code], capture_output=True, text=True)
            return result, [p.name for p in work.iterdir()], (root/'collector-spool/.poll-lock').exists()

    def test_empty_lock_preserved(self):
        r, files, exists = self.run_case(lambda root: (root/'collector-spool/.poll-lock').mkdir(), 'preserve_locks')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('.poll-lock', files)
        self.assertFalse(exists)

    def test_live_pid_refused(self):
        def setup(root):
            p=root/'collector-spool/.poll-lock'; p.mkdir(); (p/'pid').write_text(str(os.getpid()))
        r, files, exists = self.run_case(setup, 'preserve_locks')
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('LOCK_OWNER_ALIVE', r.stderr)
        self.assertTrue(exists)
        self.assertEqual(files, [])

    def test_unknown_member_refused(self):
        def setup(root):
            p=root/'collector-spool/.poll-lock'; p.mkdir(); (p/'important').write_text('keep')
        r, files, exists = self.run_case(setup, 'preserve_locks')
        self.assertIn('UNKNOWN_LOCK_MEMBER', r.stderr)
        self.assertTrue(exists)
        self.assertEqual(files, [])

    def test_symlink_refused(self):
        def setup(root):
            (root/'collector-spool/.poll-lock').symlink_to(root, target_is_directory=True)
        r, files, _ = self.run_case(setup, 'preserve_locks')
        self.assertIn('UNSAFE_TREE', r.stderr)
        self.assertEqual(files, [])

    def test_previous_recovery_lock_preserved(self):
        name='.poll-lock-preservado-20261027T121348Z'
        r, files, _ = self.run_case(lambda root: (root/'collector-spool'/name).mkdir(), 'preserve_locks')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(files, [name])

    def test_pending_update_refused(self):
        r, _, _ = self.run_case(lambda root: (root/'collector-spool/update-v3-request.json').write_text('{}'), 'no_pending')
        self.assertIn('PENDING_OPERATION', r.stderr)
        self.assertNotEqual(r.returncode, 0)

    def test_stop_failure_prevents_cleanup(self):
        r, files, _ = self.run_case(lambda root: None, '''
verify_units() { :; }
systemd() { return 0; }
units() { echo java-assessment-collector.service; }
systemctl() { if test "$1" = show; then echo LoadState=loaded; else return 1; fi; }
stop_services
: > "$WORK/should-not-exist"
''')
        self.assertIn('SERVICE_STOP_FAILED', r.stderr)
        self.assertEqual(files, [])

    def test_help_and_invalid_arguments(self):
        for arg, expected in [('--help', 0), ('--force', 2)]:
            r=subprocess.run(['/bin/sh', str(SCRIPT), arg], capture_output=True)
            self.assertEqual(r.returncode, expected)

if __name__ == '__main__': unittest.main()
