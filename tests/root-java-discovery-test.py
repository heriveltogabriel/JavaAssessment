"""Exercise real root Java selection with local fixtures; no sudo or network.

The trust helper is replaced only at the privileged filesystem boundary: the
fixture checks real modes, but does not require the test user's files to belong
to root. Candidate enumeration is replaced with a finite local list. Selection,
probe execution and fallback come from the production shell helper unchanged.
An executable rejected candidate writes a sentinel if it is ever invoked.
"""
from pathlib import Path
import json
import os
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "collector/root-java-discovery.sh"
INSTALLER = ROOT / "collector/root-system-install.sh"


def executable(path, content):
    path.write_text(content)
    path.chmod(0o755)


class RootJavaDiscovery(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(
            prefix="assessment-root-java-",
            dir="/private/tmp" if Path("/private/tmp").exists() else "/tmp",
        )
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.events = self.base / "events"
        self.package = self.base / "package"
        self.package.mkdir()
        (self.package / "lib").mkdir()
        (self.package / "collector.jar").write_bytes(b"fixture collector classpath")
        self.search_root = self.base / "search-fixture"
        self.search_root.mkdir()
        self.find_arguments = self.base / "find-arguments.json"
        self.find_pid = self.base / "find.pid"
        self.candidates = []
        # This adapter never executes the candidate. The real Linux root trust
        # policy is outside this portable selection test; a world-writable
        # runtime file still makes an otherwise executable fixture fail here.
        trust = self.base / "trust-fixture.py"
        trust.write_text(
            "import os, pathlib, stat, sys\n"
            + "base = pathlib.Path(" + repr(str(self.base)) + ")\n"
            + "events = pathlib.Path(" + repr(str(self.events)) + ")\n"
            + "mode, named = sys.argv[1:]\n"
            + "with events.open('a') as f: f.write('trust:' + mode + ':' + named + '\\n')\n"
            + "path = pathlib.Path(named)\n"
            + "if not path.is_absolute() or base not in path.parents: sys.exit(1)\n"
            + "if mode == 'package':\n"
            + "    safe = path == base / 'package' and not (stat.S_IMODE(path.stat().st_mode) & 0o022)\n"
            + "    sys.exit(0 if safe else 1)\n"
            + "if mode != 'java' or not path.is_file(): sys.exit(1)\n"
            + "for entry in (path, *path.parents):\n"
            + "    if entry == base: break\n"
            + "    if stat.S_IMODE(entry.stat().st_mode) & 0o022:\n"
            + "        print('Fixture runtime is writable by another account', file=sys.stderr)\n"
            + "        sys.exit(1)\n"
            + "print(path.resolve())\n"
        )
        executable(
            self.package / "root-trust.sh",
            "#!/bin/sh\nexec " + shlex.quote(sys.executable) + " "
            + shlex.quote(str(trust)) + ' "$@"\n',
        )

    def java(self, name, compatible=True, trusted=True):
        home = self.base / name
        (home / "bin").mkdir(parents=True)
        candidate = home / "bin/java"
        sentinel = self.base / (name.replace("/", "_") + ".executed")
        executable(
            candidate,
            "#!/bin/sh\n"
            + ": > " + shlex.quote(str(sentinel)) + "\n"
            + "printf '%s\\n' " + shlex.quote("exec:" + str(candidate))
            + " >> " + shlex.quote(str(self.events)) + "\n"
            + "probe=false\nfor argument do\n"
            + '  test "$argument" != assessment.JavaProbe || probe=true\n'
            + "done\n$probe || exit 91\n"
            + ("exit 0\n" if compatible else "exit 1\n"),
        )
        if not trusted:
            candidate.chmod(0o777)
        self.candidates.append(candidate)
        return candidate, sentinel

    def event_lines(self):
        return self.events.read_text().splitlines() if self.events.exists() else []

    def run_shell(self, harness, args, timeout=20):
        # Isolate background probes/find/watchdog in one process group so even
        # a failing timeout test cannot leave a worker behind on the developer
        # machine. Also detect orphaned workers after a supposedly clean exit.
        process = subprocess.Popen(
            ["/bin/sh", "-c", harness, *args], text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
        )
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate()
            raise
        # Check only our own isolated process group; this also works when the
        # desktop sandbox disallows listing unrelated processes with ps.
        remaining = False
        for _ in range(5):
            try:
                os.killpg(process.pid, 0)
                remaining = True
            except ProcessLookupError:
                remaining = False
                break
            time.sleep(0.02)
        if remaining:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        self.assertFalse(remaining, "Discovery left workers in its background process group")
        return subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)

    def run_discovery(self, candidates=None, real_candidates=False, homes=(), search_entries=(), find_mode="entries"):
        # Remove only the CLI entrypoint, retaining the real functions and
        # environment sanitization. No product test flag or trust bypass exists.
        source = HELPER.read_text()
        main = '\nassessment_root_java_main "$@"\n'
        self.assertTrue(source.endswith(main), "Discovery entrypoint changed")
        definitions = source[:-len(main)]
        paths = self.candidates if candidates is None else candidates
        enumerator = ""
        if not real_candidates:
            enumerator = "assessment_root_java_candidates() {\n"
            if paths:
                enumerator += "printf '%s\\n' " + " ".join(shlex.quote(str(p)) for p in paths) + "\n"
            else:
                enumerator += ":\n"
            enumerator += "}\n"
        # Feed a controlled find stream to the real awk/entry-budget/watchdog.
        # The adapter never walks a directory, even when a test fails. The
        # production root enumerator is replaced in every test, not just tests
        # that currently happen to reach the final fallback.
        find_fixture = self.base / "find-fixture.py"
        find_fixture.write_text(
            "import json, os, pathlib, sys, time\n"
            + "pathlib.Path(" + repr(str(self.find_arguments)) + ").write_text(json.dumps(sys.argv[1:]))\n"
            + "pathlib.Path(" + repr(str(self.find_pid)) + ").write_text(str(os.getpid()))\n"
            + "assert sys.argv[1] == " + repr(str(self.search_root)) + "\n"
            + "entries = " + repr(list(search_entries)) + "\n"
            + "mode = " + repr(find_mode) + "\n"
            + "if mode == 'entry_limit':\n"
            + "    for i in range(10000): print('f /fixture/entry-' + str(i))\n"
            + "for entry in entries: print(entry)\n"
            + "sys.stdout.flush()\n"
            + "if mode == 'stall': time.sleep(60)\n"
        )
        boundaries = '\nid() { printf "0\\n"; }\n'
        boundaries += 'readlink() { printf "proc:skipped\\n" >> ' + shlex.quote(str(self.events)) + '; return 1; }\n'
        boundaries += 'assessment_root_search_roots() { printf "search:roots\\n" >> ' + shlex.quote(str(self.events)) + '; '
        boundaries += "printf '%s\\n' " + shlex.quote(str(self.search_root)) + '; }\n'
        boundaries += 'find() { exec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(find_fixture)) + ' "$@"; }\n'
        harness = definitions + boundaries + enumerator
        harness += 'assessment_root_java_main "$@"\n'
        args = ["fixture", str(self.package)]
        if homes:
            args += ["", *map(str, homes)]
        return self.run_shell(harness, args)

    def run_explicit_installer_selection(self, candidate):
        # Exercise the actual installer argument parsing/explicit trust gate and
        # final selection branch, skipping all unrelated filesystem mutations.
        # If the explicit path were silently replaced, this stub leaves proof.
        discovery_sentinel = self.base / "discovery.executed"
        executable(
            self.package / "root-java-discovery.sh",
            "#!/bin/sh\n: > " + shlex.quote(str(discovery_sentinel))
            + "\nprintf '/unexpected/fallback/bin/java\\n'\n",
        )
        source = INSTALLER.read_text()
        bootstrap = '\n. "$SOURCE/bootstrap-config.sh"\n'
        self.assertEqual(source.count(bootstrap), 1)
        prefix = source.split(bootstrap, 1)[0]
        # Lifecycle locks/services are covered by the isolated update tests.
        # Keep this JVM-selection harness away from every production path.
        executable(self.package / "lifecycle-lock.sh", "#!/bin/sh\n:\n")
        prefix = prefix.replace("/opt/java-assessment-collector", str(self.package))
        prefix = prefix.replace("/opt/java-assessment-uninstall", str(self.base / "uninstall"))
        prefix = prefix.replace("/opt/java-assessment-update", str(self.base / "update"))
        begin = 'if test -z "$JAVA_PATH"; then\n'
        end = "printf '%s\\n' \"$JAVA_PATH\" > \"$DEST/conf/java.path\""
        self.assertEqual(source.count(begin), 1)
        self.assertEqual(source.count(end), 1)
        selection = source.split(begin, 1)[1].split(end, 1)[0]
        harness = 'id() { printf "0\\n"; }\n' + prefix
        harness += '\nDEST=$SOURCE\n' + begin + selection
        harness += '\nprintf "%s\\n" "$JAVA_PATH"\n'
        result = subprocess.run(
            ["/bin/sh", "-c", harness, str(self.package / "root-system-install.sh"),
             "--java", str(candidate)],
            text=True, capture_output=True, timeout=10,
        )
        return result, discovery_sentinel

    def test_untrusted_executable_is_never_executed_and_fallback_is_checked_first(self):
        rejected, rejected_sentinel = self.java("untrusted-java", trusted=False)
        accepted, accepted_sentinel = self.java("trusted-java")
        result = self.run_discovery()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(accepted))
        self.assertFalse(rejected_sentinel.exists(), "Rejected executable ran as a candidate")
        self.assertTrue(accepted_sentinel.exists())
        events = self.event_lines()
        self.assertEqual(events[0], "trust:package:" + str(self.package))
        self.assertIn("trust:java:" + str(rejected), events)
        self.assertLess(events.index("trust:java:" + str(accepted)), events.index("exec:" + str(accepted)))

    def test_incompatible_trusted_java_falls_back_to_compatible_java(self):
        incompatible, first_sentinel = self.java("old-or-incompatible-java", compatible=False)
        compatible, second_sentinel = self.java("compatible-java")
        result = self.run_discovery()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(compatible))
        self.assertTrue(first_sentinel.exists())
        self.assertTrue(second_sentinel.exists())
        events = self.event_lines()
        for candidate in (incompatible, compatible):
            self.assertLess(events.index("trust:java:" + str(candidate)), events.index("exec:" + str(candidate)))
        self.assertLess(events.index("exec:" + str(incompatible)), events.index("exec:" + str(compatible)))

    def test_stops_after_first_compatible_candidate(self):
        accepted, accepted_sentinel = self.java("first-java")
        unused, unused_sentinel = self.java("unused-java")
        result = self.run_discovery()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(accepted))
        self.assertTrue(accepted_sentinel.exists())
        self.assertFalse(unused_sentinel.exists())
        self.assertNotIn("trust:java:" + str(unused), self.event_lines())
        self.assertNotIn("proc:skipped", self.event_lines())
        self.assertNotIn("search:roots", self.event_lines())
        self.assertFalse(self.find_arguments.exists(), "Successful known candidate still launched a tree search")

    def test_rejected_package_prevents_any_java_execution(self):
        _, sentinel = self.java("otherwise-valid-java")
        self.package.chmod(0o777)
        result = self.run_discovery()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(sentinel.exists())
        self.assertEqual(self.event_lines(), ["trust:package:" + str(self.package)])

    def test_no_java_reports_actionable_error_without_claiming_success(self):
        result = self.run_discovery([self.base / "missing/bin/java"])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("No compatible root-protected JVM", result.stderr)
        self.assertIn("--java /absolute/path/bin/java", result.stderr)

    def test_all_rejected_candidates_do_not_execute(self):
        _, sentinel = self.java("rejected-only-java", trusted=False)
        result = self.run_discovery()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(sentinel.exists())
        self.assertEqual(result.stdout, "")
        self.assertIn("--java", result.stderr)

    def test_explicit_trusted_path_is_preserved_without_discovery(self):
        candidate, sentinel = self.java("explicit-java")
        self.java("available-alternative-java")
        result, discovery_sentinel = self.run_explicit_installer_selection(candidate)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(candidate))
        self.assertFalse(discovery_sentinel.exists(), "Explicit override triggered discovery")
        self.assertFalse(sentinel.exists(), "The installer trust gate executed its candidate")

    def test_explicit_rejected_path_fails_without_substitution_or_execution(self):
        candidate, sentinel = self.java("explicit-rejected-java", trusted=False)
        self.java("available-alternative-java")
        result, discovery_sentinel = self.run_explicit_installer_selection(candidate)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(discovery_sentinel.exists(), "Rejected explicit path was silently substituted")
        self.assertFalse(sentinel.exists())
        self.assertNotIn("/unexpected/fallback", result.stdout)

    def test_explicit_incompatible_java_fails_in_real_launcher_without_fallback(self):
        candidate, explicit_sentinel = self.java("explicit-incompatible-java", compatible=False)
        alternative, alternative_sentinel = self.java("otherwise-compatible-java")
        source = (ROOT / "collector/launcher-common.sh").read_text()
        # Source the real launcher functions, skipping its host UID/package
        # startup checks; those need a privileged Linux filesystem to exercise.
        marker = "assessment_check_private_file() {\n"
        self.assertEqual(source.count(marker), 1)
        definitions = marker + source.split(marker, 1)[1]
        harness = definitions + '\nASSESSMENT_ROOT=$1\nASSESSMENT_RUN_UID=0\n'
        harness += 'ASSESSMENT_JAVA=$2\nASSESSMENT_JAVA_HOME=$3\nassessment_select_java\n'
        result = subprocess.run(
            ["/bin/sh", "-c", harness, "fixture", str(self.package),
             str(candidate), str(alternative.parent.parent)],
            text=True, capture_output=True, timeout=10,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(explicit_sentinel.exists())
        self.assertFalse(alternative_sentinel.exists(), "Incompatible explicit Java was substituted")
        self.assertIn("Explicit ASSESSMENT_JAVA is not usable", result.stderr)
        events = self.event_lines()
        self.assertLess(events.index("trust:java:" + str(candidate)), events.index("exec:" + str(candidate)))

    def test_java_home_and_jdk_home_hints_are_validated_before_execution(self):
        unsafe, unsafe_sentinel = self.java("custom vendor/unsafe+jdk", trusted=False)
        safe, safe_sentinel = self.java("custom vendor/safe+jdk")
        result = self.run_discovery(
            real_candidates=True, homes=(unsafe.parent.parent, safe.parent.parent),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(safe))
        self.assertFalse(unsafe_sentinel.exists(), "Untrusted JAVA_HOME was executed")
        self.assertTrue(safe_sentinel.exists())
        events = self.event_lines()
        self.assertIn("trust:java:" + str(unsafe), events)
        self.assertLess(events.index("trust:java:" + str(safe)), events.index("exec:" + str(safe)))
        self.assertNotIn("proc:skipped", events)
        self.assertFalse(self.find_arguments.exists())

    def test_bounded_search_discovers_custom_path_and_stops_after_success(self):
        rejected, rejected_sentinel = self.java("search-fixture/vendor data/unsafe+jdk", trusted=False)
        found, found_sentinel = self.java("search-fixture/vendor data/trusted+jdk")
        unused, unused_sentinel = self.java("search-fixture/unused+jdk")
        result = self.run_discovery(
            candidates=[], search_entries=["f " + str(path) for path in (rejected, found, unused)],
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(found))
        self.assertFalse(rejected_sentinel.exists())
        self.assertTrue(found_sentinel.exists())
        self.assertFalse(unused_sentinel.exists())
        events = self.event_lines()
        self.assertLess(events.index("trust:java:" + str(found)), events.index("exec:" + str(found)))
        self.assertNotIn("trust:java:" + str(unused), events)
        arguments = json.loads(self.find_arguments.read_text())
        self.assertEqual(arguments[0], str(self.search_root))
        self.assertIn("-xdev", arguments)
        self.assertEqual(arguments[arguments.index("-maxdepth") + 1], "8")
        self.assertFalse(list(self.package.glob(".java-discovery.*")), "Search scratch directory was retained")

    def test_search_entry_budget_excludes_candidate_after_10000_entries(self):
        excluded, sentinel = self.java("search-fixture/beyond-entry-budget")
        result = self.run_discovery(
            candidates=[], search_entries=["f " + str(excluded)], find_mode="entry_limit",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(sentinel.exists(), "Java outside the entry budget was executed")
        self.assertNotIn("trust:java:" + str(excluded), self.event_lines())
        self.assertIn("entry limit", result.stderr)
        self.assertFalse(list(self.package.glob(".java-discovery.*")))

    def test_fast_empty_search_does_not_wait_for_the_watchdog(self):
        started = time.monotonic()
        result = self.run_discovery(candidates=[])
        elapsed = time.monotonic() - started
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.find_arguments.exists())
        self.assertLess(elapsed, 2.5, "Fast search was kept alive by its watchdog")
        self.assertFalse(list(self.package.glob(".java-discovery.*")))

    def test_search_time_budget_stops_and_reaps_stalled_find(self):
        started = time.monotonic()
        result = self.run_discovery(candidates=[], find_mode="stall")
        elapsed = time.monotonic() - started
        self.assertNotEqual(result.returncode, 0)
        self.assertGreaterEqual(elapsed, 9, "The configured ten-second budget was not exercised")
        self.assertLess(elapsed, 14, "Stalled find exceeded its watchdog budget")
        self.assertIn("time limit", result.stderr)
        self.assertTrue(self.find_pid.exists())
        self.assertFalse(list(self.package.glob(".java-discovery.*")))


if __name__ == "__main__":
    unittest.main()
