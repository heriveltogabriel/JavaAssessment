"""Version reservations in temporary directories. No VM access."""
import importlib.util
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'ops/dashboard_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix='dashboard-release-test-')
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        (self.root / 'release').mkdir()
        (self.root / 'release/dashboard.json').write_text(json.dumps({'version': '1.0.0-beta', 'releases': []}))

    def test_full_patch_increment(self):
        self.assertEqual(release.next_version('1.0-beta'), '1.0.1')
        self.assertEqual(release.next_version('1.0.1'), '1.0.2')
        self.assertEqual(release.next_version('1.2.9-beta'), '1.2.10')

    def test_invalid_versions(self):
        for value in ('1.0 Beta', '1.0.01', '01.0.1-beta', '1.0.0-beta\n', '1.0.1-beta+injected', '../oops'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.next_version(value)

    def test_migration_uses_highest_component_version_and_removes_beta(self):
        (self.root / 'release/dashboard.json').write_text(json.dumps({'version': '1.0.2-beta', 'releases': []}))
        first, _ = release.reserve(self.root, minimum_version='1.5.1')
        self.assertEqual(first['version'], '1.5.2')
        second, _ = release.reserve(self.root, minimum_version=first['version'])
        self.assertEqual(second['version'], '1.5.3')

    def test_higher_dashboard_version_never_downgrades_to_agent(self):
        (self.root / 'release/dashboard.json').write_text(json.dumps({'version': '2.0.9', 'releases': []}))
        item, _ = release.reserve(self.root, minimum_version='1.5.1')
        self.assertEqual(item['version'], '2.0.10')

    def test_requested_major_version_preserves_history_and_next_patch(self):
        old, old_output = release.reserve(self.root, minimum_version='1.5.12')
        (old_output / 'sentinel').write_text('immutable 1.5.13')
        release.finish(self.root, old['version'], 'prepared')
        chosen, output = release.reserve(self.root, minimum_version='1.5.13', requested_version='2.0.0')
        self.assertEqual(chosen['version'], '2.0.0')
        self.assertEqual(output.name, '2.0.0')
        self.assertEqual((old_output / 'sentinel').read_text(), 'immutable 1.5.13')
        state = json.loads((self.root / 'release/dashboard.json').read_text())
        self.assertEqual([(item['version'], item['status']) for item in state['releases']],
                         [('1.5.13', 'prepared'), ('2.0.0', 'reserved')])
        self.assertEqual(release.reserve(self.root)[0]['version'], '2.0.1')

    def test_requested_duplicate_backward_and_malformed_versions_leave_ledger_unchanged(self):
        release.reserve(self.root, minimum_version='1.5.13', requested_version='2.0.0')
        state_path = self.root / 'release/dashboard.json'
        before = state_path.read_bytes()
        for version in ('2.0.0', '1.9.99', '1.5.14', '02.0.1', '2.0', '2.0.1-beta',
                        '2.0.1+build', '2.0.1\n', '../2.0.1', ''):
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.reserve(self.root, requested_version=version)
            self.assertEqual(state_path.read_bytes(), before)
        self.assertEqual(sorted(path.name for path in (self.root / 'output/dashboard-releases').iterdir()), ['2.0.0'])

    def test_requested_version_cannot_downgrade_newer_agent(self):
        with self.assertRaises(ValueError):
            release.reserve(self.root, minimum_version='2.1.0', requested_version='2.0.0')
        self.assertFalse((self.root / 'output/dashboard-releases').exists())

    def test_reserved_history_wins_over_stale_counter(self):
        state = {'version': '1.5.13', 'releases': [
            {'version': '2.0.0', 'builtAt': '2026-10-14T12:00:00+00:00', 'status': 'failed'}]}
        (self.root / 'release/dashboard.json').write_text(json.dumps(state))
        with self.assertRaises(ValueError):
            release.reserve(self.root, requested_version='2.0.0')
        self.assertEqual(release.reserve(self.root)[0]['version'], '2.0.1')

    def test_requested_existing_output_is_never_overwritten(self):
        output = self.root / 'output/dashboard-releases/2.0.0'
        output.mkdir(parents=True)
        (output / 'sentinel').write_text('immutable output')
        before = (self.root / 'release/dashboard.json').read_bytes()
        with self.assertRaises(FileExistsError):
            release.reserve(self.root, requested_version='2.0.0')
        self.assertEqual((self.root / 'release/dashboard.json').read_bytes(), before)
        self.assertEqual((output / 'sentinel').read_text(), 'immutable output')

    def test_failed_atomic_write_keeps_ledger_intact_and_output_reserved(self):
        state_path = self.root / 'release/dashboard.json'
        before = state_path.read_bytes()
        with patch.object(Path, 'replace', side_effect=OSError('fixture disk failure')):
            with self.assertRaises(OSError):
                release.reserve(self.root, requested_version='2.0.0')
        self.assertEqual(state_path.read_bytes(), before)
        self.assertTrue((self.root / 'output/dashboard-releases/2.0.0').is_dir())
        self.assertEqual(list(state_path.parent.glob('*.next')), [])
        with self.assertRaises(FileExistsError):
            release.reserve(self.root, requested_version='2.0.0')

    def test_concurrent_explicit_version_has_one_winner(self):
        def attempt(_):
            try:
                return release.reserve(self.root, requested_version='2.0.0')[0]['version']
            except ValueError:
                return 'rejected'
        with ThreadPoolExecutor(max_workers=4) as pool:
            outcomes = list(pool.map(attempt, range(4)))
        self.assertEqual(outcomes.count('2.0.0'), 1)
        self.assertEqual(outcomes.count('rejected'), 3)
        state = json.loads((self.root / 'release/dashboard.json').read_text())
        self.assertEqual(len(state['releases']), 1)
        self.assertEqual(state['version'], '2.0.0')

    def test_agent_constant_is_generated_from_shared_release(self):
        source = 'package assessment; class AgentTransport { static final String VERSION = "1.5.1"; }'
        generated = release.agent_source(source, '1.5.2')
        self.assertEqual(release.agent_version(generated), '1.5.2')
        self.assertEqual(generated.replace('1.5.2', '1.5.1'), source)
        for invalid in ('1.5.2"; injected', 'latest'):
            with self.assertRaises(ValueError): release.agent_source(source, invalid)
        with self.assertRaises(ValueError): release.agent_version(source + source)

    def test_two_new_packages_have_distinct_versions_and_no_overwrite(self):
        first, a = release.reserve(self.root)
        (a / 'sentinel').write_text('immutable first release')
        release.finish(self.root, first['version'], 'prepared')
        second, b = release.reserve(self.root)
        self.assertEqual((first['version'], second['version']), ('1.0.1', '1.0.2'))
        self.assertNotEqual(a, b)
        self.assertEqual((a / 'sentinel').read_text(), 'immutable first release')

    def test_reserved_failed_number_is_not_reused(self):
        first, _ = release.reserve(self.root)
        release.finish(self.root, first['version'], 'failed')
        second, _ = release.reserve(self.root)
        self.assertEqual(second['version'], '1.0.2')

    def test_existing_output_refused_without_counter_change(self):
        (self.root / 'output/dashboard-releases/1.0.1').mkdir(parents=True)
        with self.assertRaises(FileExistsError):
            release.reserve(self.root)
        self.assertEqual(json.loads((self.root / 'release/dashboard.json').read_text())['version'], '1.0.0-beta')

    def test_concurrent_reservations_are_serialized(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            versions = [v['version'] for v, _ in pool.map(lambda _: release.reserve(self.root), range(4))]
        self.assertEqual(set(versions), {f'1.0.{i}' for i in range(1, 5)})
        self.assertEqual(json.loads((self.root / 'release/dashboard.json').read_text())['version'], '1.0.4')

    def test_generated_java_has_exact_release_and_timestamp(self):
        item, _ = release.reserve(self.root)
        text = release.java_source(item)
        self.assertIn('VERSION = "1.0.1";', text)
        self.assertIn(json.dumps(item['builtAt']), text)
        with self.assertRaises(ValueError):
            release.java_source({'version': '1.0.1', 'builtAt': '2026-10-13T12:00:00'})


if __name__ == '__main__':
    unittest.main(verbosity=2)
