#!/usr/bin/env python3
"""Offline contract tests: fake HTTPS opener, fake SSH, no network/services."""
import argparse
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("canary_runner", ROOT / "ops/testar-atualizacao-canario.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
H = "host-12345678-1234-1234-1234-123456789abc"
U = "12345678-1234-1234-1234-123456789abc"
B = "22345678-1234-1234-1234-123456789abc"
J = "32345678-1234-1234-1234-123456789abc"
BOOT = "42345678-1234-1234-1234-123456789abc"
BOOT2 = "52345678-1234-1234-1234-123456789abc"
V = "2.0.9"
EXPECTED = {"bundleSha256": "b" * 64, "managerSha256": "c" * 64}


def before():
    return {"HOST_ID": H, "BOOT_ID": BOOT, "CAPABILITY": "update-v1", "COLLECTOR_JAR_VERSION": "1.5.12",
            "LEGACY_HELPER_VERSION": "1.5.12", "applications": [{"pid": 123, "startTicks": 456}], "pending": [],
            "services": {unit: {"LoadState": "loaded", "ActiveState": "active"}
                         for unit in ("java-assessment-collector.timer", "java-assessment-update.service")},
            **{key: "a" * 64 for key in runner.PRESERVED}}


def after(reboot=False):
    value = before()
    value.update(CAPABILITY="update-v3", POINTER_VERSION=V, BUNDLE_SHA256="b" * 64,
                 MANAGER_SHA256="c" * 64)
    value["services"]["java-assessment-recovery.service"] = {"LoadState": "loaded", "ActiveState": "active"}
    if reboot:
        value["BOOT_ID"] = BOOT2
        value["applications"] = [{"pid": 222, "startTicks": 800}]
    return value


def host(final=False, seen="2026-10-14T15:00:00Z"):
    return {"id": H, "credentialStatus": "active", "environmentId": "test-env",
            "collectorVersion": V if final else "1.5.12", "updateCapability": "update-v3" if final else "update-v1",
            "settings": {"maxDurationSeconds": 120, "maxFiles": 5000, "inspectArchives": True},
            "bundleSha256": EXPECTED["bundleSha256"], "managerSha256": EXPECTED["managerSha256"],
            "lastSeen": seen}


def operation(status="WAITING", assessment=None):
    return {"id": U, "hostId": H, "targetVersion": V, "status": status, "updateMode": "MIGRATION",
            "runAssessmentAfterUpdate": True, "assessment": assessment}


class FakeAPI:
    def __init__(self):
        self.calls = []
        self.details = []
        self.contact = 0
        self.final = False

    def login(self):
        pass

    def request(self, path, body=None):
        self.calls.append((path, copy.deepcopy(body)))
        if path == "/health":
            return {"status": "ok", "version": V}
        if path == "/api/agent-updates":
            return {"batchId": B, "requests": [operation()], "skipped": []}
        if path.startswith("/api/agent-updates?"):
            return {"release": {"version": V, "available": True, "format": "agent-bundle-v1"},
                    "hosts": [{"id": H, "eligible": True, "collectorVersion": "1.5.12", "updateMode": "MIGRATION"}]}
        if path.startswith("/api/item?"):
            self.contact += 1
            return {"item": host(self.final, "2026-10-14T15:%02d:00Z" % self.contact)}
        if path.startswith("/api/work-requests/detail?"):
            self.final = True
            if self.details:
                return {"machines": [self.details.pop(0)], "skipped": []}
            return {"machines": [operation("SUCCEEDED", {"operationId": U, "hostId": H, "jobId": J, "status": "SUCCEEDED"})], "skipped": []}
        if path.startswith("/api/work-requests?"):
            return {"pagination": {"total": 0}}
        raise AssertionError(path)


class CanaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.root.chmod(0o700)
        self.report_path = self.root / "canary.json"

    def tearDown(self):
        self.temp.cleanup()

    def report(self):
        return {"format": "agent-canary-v1", "hostIp": "134.65.19.12", "hostId": H, "version": V,
                "origin": "https://192.0.2.1", "phase": "FOLLOWING", "rebootRequested": True,
                "before": before(), "expected": EXPECTED, "policy": runner.policy(host()), "batchId": B, "operationId": U,
                "request": {"hostIds": [H], "targetVersion": V, "requestKey": J,
                            "expiresInMinutes": 60, "runAssessmentAfterUpdate": True}}

    def test_private_atomic_receipt(self):
        runner.save(self.report_path, self.report())
        self.assertEqual(self.report_path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(runner.load_json(self.report_path, private=True)["hostId"], H)
        self.report_path.chmod(0o644)
        with self.assertRaises(runner.Stop):
            runner.load_json(self.report_path, private=True)

    def test_ssh_no_shell_and_only_literal_host(self):
        key = self.root / "key"
        key.touch()
        with self.assertRaises(runner.Stop):
            runner.SSH("134.65.19.12;reboot", "opc", key)
        ssh = runner.SSH("134.65.19.12", "opc", key)
        with patch.object(runner.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"OK")) as call:
            self.assertEqual(ssh.execute("echo OK\n"), "OK")
            args, kwargs = call.call_args
            self.assertIn("StrictHostKeyChecking=ask", args[0])
            self.assertEqual(args[0][-2:], ["opc@134.65.19.12", "sudo -n /bin/sh -s"])
            self.assertEqual(kwargs["input"], b"echo OK\n")
            self.assertNotIn("shell", kwargs)

    def test_parse_remote_only_whitelisted_evidence(self):
        raw = "\n".join(["HOST_ID=" + H, "BOOT_ID=" + BOOT, "PREFLIGHT_COMPLETE=READ_ONLY",
                         "APPLICATION_PROCESSES=[{\"pid\":123,\"startTicks\":456}]", "agentToken=SECRET", "PASSWORD=SECRET"] +
                        [k + "=" + "a" * 64 for k in runner.PRESERVED])
        result = runner.parse_remote(raw)
        self.assertNotIn("SECRET", json.dumps(result))
        with self.assertRaises(runner.Stop):
            runner.parse_remote(raw.replace(H, "invalid; shell"))
        with self.assertRaises(runner.Stop):
            runner.parse_remote(raw + "\nHOST_ID=" + H)

    def test_origin_pending_service_guards(self):
        runner.initial_remote(before())
        for mutation in (lambda x: x.update(CAPABILITY="update-v3"), lambda x: x["pending"].append("pending"),
                         lambda x: x["services"].pop("java-assessment-update.service")):
            value = before()
            mutation(value)
            with self.assertRaises(runner.Stop):
                runner.initial_remote(value)

    def test_preserves_hashes_and_process_start_ticks(self):
        runner.final_remote(before(), after(), V, EXPECTED)
        for mutation in (lambda x: x.update(CONFIG_SHA256="d" * 64),
                         lambda x: x["applications"][0].update(startTicks=457),
                         lambda x: x.update(BOOT_ID=BOOT2),
                         lambda x: x["services"].pop("java-assessment-recovery.service")):
            value = after()
            mutation(value)
            with self.assertRaises(runner.Stop):
                runner.final_remote(before(), value, V, EXPECTED)
        runner.final_remote(before(), after(True), V, EXPECTED, same_processes=False)

    def test_request_is_durable_before_post_and_resumable_same_key(self):
        report = self.report()
        api = FakeAPI()
        original = api.request
        def lost(path, body=None):
            saved = runner.load_json(self.report_path, private=True)
            self.assertEqual(saved["phase"], "REQUEST_PERSISTED")
            self.assertEqual(body, saved["request"])
            raise ConnectionError("lost response")
        api.request = lost
        with self.assertRaises(ConnectionError):
            runner.create_operation(api, report, self.report_path)
        resumed = runner.load_json(self.report_path, private=True)
        api.request = original
        runner.create_operation(api, resumed, self.report_path)
        self.assertEqual(api.calls[-1][1]["requestKey"], J)
        self.assertEqual(api.calls[-1][1]["hostIds"], [H])
        self.assertTrue(api.calls[-1][1]["runAssessmentAfterUpdate"])

    def test_preview_rejects_multiple_hosts_and_running_assessment(self):
        api = FakeAPI()
        runner.validate_preview(api, H, V)
        original = api.request
        def duplicate(path, body=None):
            result = original(path, body)
            if path.startswith("/api/agent-updates?"):
                result["hosts"] *= 2
            return result
        api.request = duplicate
        with self.assertRaises(runner.Stop):
            runner.validate_preview(api, H, V)
        def running(path, body=None):
            result = original(path, body)
            if path.startswith("/api/work-requests?"):
                result["pagination"]["total"] = 1
            return result
        api.request = running
        with self.assertRaises(runner.Stop):
            runner.validate_preview(api, H, V)

    def test_wait_is_bound_to_operation_and_stops_on_rollback(self):
        report = self.report()
        api = FakeAPI()
        api.details = [operation("ROLLED_BACK")]
        with self.assertRaises(runner.Stop):
            runner.wait_operation(api, report, self.report_path, 1, sleep=lambda _: None, monotonic=lambda: 0)
        self.assertNotEqual(report["phase"], "UPDATED")
        bad = operation("SUCCEEDED")
        bad["hostId"] = "another-host"
        api.details = [bad]
        with self.assertRaises(runner.Stop):
            runner.wait_operation(api, report, self.report_path, 1, sleep=lambda _: None, monotonic=lambda: 0)

    def test_wait_final_and_contacts_are_agent_timestamps(self):
        report = self.report()
        api = FakeAPI()
        runner.wait_operation(api, report, self.report_path, 1, sleep=lambda _: None, monotonic=lambda: 0)
        self.assertEqual(report["phase"], "UPDATED")
        values = runner.wait_contacts(api, report, self.report_path, 1, sleep=lambda _: None, monotonic=lambda: 0)
        self.assertEqual(len(values), 2)
        self.assertNotEqual(values[0], values[1])

    def test_wrong_final_bundle_is_rejected_locally_and_by_api(self):
        wrong = after()
        wrong["BUNDLE_SHA256"] = "f" * 64
        with self.assertRaises(runner.Stop):
            runner.final_remote(before(), wrong, V, EXPECTED)
        api = FakeAPI()
        api.final = True
        original = api.request
        def request(path, body=None):
            value = original(path, body)
            if "item" in value:
                value["item"]["managerSha256"] = "f" * 64
            return value
        api.request = request
        with self.assertRaises(runner.Stop):
            runner.wait_contacts(api, self.report(), self.report_path, 1, after="2026-10-14T00:00:00Z",
                                 sleep=lambda _: None, monotonic=lambda: 0)

    def test_expected_bundle_is_bound_to_runtime_receipt_and_signature(self):
        packages = self.root / "packages"
        packages.mkdir()
        runtime = packages / ("assessment-dashboard-runtime-" + V + ".zip")
        raw_bundle = b"fake signed bundle bytes"
        public = b"public key fixture"
        metadata = {"version": V, "format": "agent-bundle-v1", "minUpdater": 3,
                    "bytes": len(raw_bundle), "sha256": hashlib.sha256(raw_bundle).hexdigest(),
                    "files": {"manager/helper.jar": {"sha256": EXPECTED["managerSha256"]}}}
        prefix = "dist/agent-bundles/" + V + "/"
        with zipfile.ZipFile(runtime, "w") as archive:
            archive.writestr(prefix + "manifest.json", json.dumps(metadata))
            archive.writestr(prefix + "manifest.sig", b"s" * 384)
            archive.writestr(prefix + "bundle.zip", raw_bundle)
            archive.writestr("dist/agent-update-public.der", public)
        digest = hashlib.sha256(runtime.read_bytes()).hexdigest()
        manifest = {"agentUpdatePublicKeySha256": hashlib.sha256(public).hexdigest(),
                    "artifacts": [{"file": "packages/" + runtime.name, "sha256": digest}]}
        with patch.object(runner, "verify_signature") as verify:
            expected = runner.expected_bundle(self.root, manifest, {"runtimeSha256": digest}, V)
            self.assertEqual(expected["bundleSha256"], metadata["sha256"])
            verify.assert_called_once()
            with self.assertRaises(runner.Stop):
                runner.expected_bundle(self.root, manifest, {"runtimeSha256": "f" * 64}, V)
            runtime.write_bytes(runtime.read_bytes() + b"changed")
            with self.assertRaises(runner.Stop):
                runner.expected_bundle(self.root, manifest, {"runtimeSha256": digest}, V)

    def test_https_rejects_redirects_and_does_not_persist_cookies(self):
        self.assertIsNone(runner.NoRedirect().redirect_request(None, None, 302, "", {}, "http://evil.invalid"))
        class FakeResponse:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, limit): return b'{"authenticated":true,"csrfToken":"12345678901234567890","user":{"role":"ADMIN"},"mustChangePassword":false}'
        class FakeOpener:
            def open(self, request, timeout):
                self.request = request
                return FakeResponse()
        fake = FakeOpener()
        with patch.object(runner.ssl, "create_default_context") as context, \
             patch.object(runner.urllib.request, "build_opener", return_value=fake) as build:
            api = runner.API("https://192.0.2.1", self.root / "ca.pem")
            with patch("builtins.input", return_value="admin@example.test"), patch.object(runner.getpass, "getpass", return_value="SECRET"):
                api.login()
            api.request("/api/agent-updates", {"hostIds": [H]})
            self.assertEqual(fake.request.get_header("Origin"), "https://192.0.2.1")
            self.assertEqual(fake.request.get_header("X-csrf-token"), api.csrf)
            self.assertNotIn("SECRET", json.dumps(self.report()))
            context.assert_called_once_with(cafile=str(self.root / "ca.pem"))
            self.assertTrue(any(isinstance(arg, runner.NoRedirect) for arg in build.call_args.args))

    def test_receipt_manifest_versions_and_candidate_guard(self):
        (self.root / "packages").mkdir()
        (self.root / "deployment").mkdir()
        manifest = {"dashboardVersion": V, "agentVersion": V, "candidateOnly": True}
        receipt = {"status": "deployed", "dashboardVersion": V, "collectorVersion": V,
                   "externalHttpsVerified": True, "externalVersion": V, "health": {"version": V}, "target": "https://192.0.2.1"}
        (self.root / "packages/package-manifest.json").write_text(json.dumps(manifest))
        (self.root / "deployment/deployment-result.json").write_text(json.dumps(receipt))
        (self.root / "deployment/dashboard-public-ca.pem").write_text("test CA")
        with patch.object(runner, "expected_bundle", return_value=EXPECTED):
            self.assertEqual(runner.package(self.root)[0], V)
        for field in ("externalHttpsVerified", "dashboardVersion", "target"):
            modified = dict(receipt)
            modified[field] = False if field == "externalHttpsVerified" else "http://192.0.2.1"
            (self.root / "deployment/deployment-result.json").write_text(json.dumps(modified))
            with self.assertRaises(runner.Stop):
                runner.package(self.root)

    def args(self, resume=None):
        return argparse.Namespace(release_dir=self.root, host="134.65.19.12", user="opc", identity=self.root / "key",
                                  reiniciar=True, resume_file=resume, timeout_minutes=30)

    def test_full_flow_reboot_once_after_success_with_fake_ssh(self):
        api = FakeAPI()
        class FakeSSH:
            count = 0
            reboots = 0
            def __init__(self, *args): pass
            def inspect(self):
                self.count += 1
                return before() if self.count == 1 else after(self.reboots > 0)
            def reboot(self):
                saved = runner.load_json(self_test.report_path)
                self_test.assertEqual(saved["phase"], "REBOOT_REQUESTED")
                self_test.assertEqual(saved["bootBeforeReboot"], BOOT)
                self.reboots += 1
        self_test = self
        ssh = FakeSSH()
        with patch.object(runner, "package", return_value=(V, "https://192.0.2.1", self.root / "ca", EXPECTED)), \
             patch.object(runner, "API", return_value=api), patch.object(runner, "SSH", return_value=ssh), \
             patch.object(runner.tempfile, "mkdtemp", return_value=str(self.root)), \
             patch.object(runner.time, "sleep", return_value=None), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(runner.run(self.args()), 0)
        result = runner.load_json(self.report_path)
        self.assertEqual(result["phase"], "COMPLETE")
        self.assertEqual(ssh.reboots, 1)
        self.assertEqual(len(result["contactsAfterReboot"]), 2)
        self.assertNotIn("csrf", json.dumps(result).lower())

    def test_resume_reboot_intent_never_sends_second_reboot(self):
        report = self.report()
        report.update(phase="REBOOT_REQUESTED", bootBeforeReboot=BOOT)
        runner.save(self.report_path, report)
        api = FakeAPI()
        api.final = True
        class FakeSSH:
            checks = 0
            def inspect(self):
                self.checks += 1
                evidence = after(True)
                if self.checks == 1:
                    evidence["services"]["java-assessment-recovery.service"]["ActiveState"] = "activating"
                return evidence
            def reboot(self): raise AssertionError("No second reboot permitted")
        with patch.object(runner, "package", return_value=(V, "https://192.0.2.1", self.root / "ca", EXPECTED)), \
             patch.object(runner, "API", return_value=api), patch.object(runner, "SSH", return_value=FakeSSH()), \
             patch.object(runner.time, "sleep", return_value=None), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(runner.run(self.args(self.report_path)), 0)
        self.assertEqual(runner.load_json(self.report_path)["phase"], "COMPLETE")

    def test_resume_after_post_does_not_recheck_origin_1512(self):
        report = self.report()
        report["phase"] = "REQUEST_PERSISTED"
        report.pop("batchId")
        report.pop("operationId")
        runner.save(self.report_path, report)
        api = FakeAPI()
        api.final = True
        class FakeSSH:
            reboots = 0
            def inspect(self): return after(self.reboots > 0)
            def reboot(self): self.reboots += 1
        ssh = FakeSSH()
        with patch.object(runner, "package", return_value=(V, "https://192.0.2.1", self.root / "ca", EXPECTED)), \
             patch.object(runner, "API", return_value=api), patch.object(runner, "SSH", return_value=ssh), \
             patch.object(runner.time, "sleep", return_value=None), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(runner.run(self.args(self.report_path)), 0)
        self.assertEqual(sum(path == "/api/agent-updates" for path, _ in api.calls), 1)
        self.assertFalse(any(path.startswith("/api/agent-updates?") for path, _ in api.calls))


if __name__ == "__main__":
    unittest.main(verbosity=2)
