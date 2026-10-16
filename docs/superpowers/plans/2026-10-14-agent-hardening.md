# Agent hardening Implementation Plan

> **For agentic workers:** Use executing-plans inline. Existing workspace contains authorized changes and ignored project files; do not create commits or overwrite old releases.

**Goal:** Correct the verified collector resource and updater reliability faults and package unified 2.0.2.

**Architecture:** Closeable, bounded directory enumeration with a Java 6 fallback; preserve transactional updater state on communication uncertainty. An explicit updater generation prevents offering incompatible JAR-only upgrades.

**Tech Stack:** Java 6-compatible collector, Java 17 server, POSIX shell, Python release tooling.

## 1. Bounded discovery

- [ ] Add `collector/BoundedDirectory.java`, with `open(File,long)`, `next()` and `close()`; reflective NIO and bounded legacy subprocess implementations.
- [ ] Replace `listFiles()` in `AssessmentScan.walk`, `discoverProcesses`, `linkedTree`, and `ServerDataSources.children`. Keep budget/exclusion checks and priority passes.
- [ ] Add `collector/BoundedDirectoryTest.java` for streaming, partial reads, process failure, NUL names, deadline and cleanup. Extend `JdkInventoryTest` for external private runtime/updater exclusion and genuine application use.
- [ ] Compile collector tests with the local JDK17; run discovery suites with `-Xmx64m`. The wide-directory reproduction already passed in 2.0.1; do not repeat the long-path stress run, per the user’s request.

## 2. Reliable updater

- [ ] Update `collector/update-service.sh`: process-local RNG, heartbeat watchdog exit 10, idle fast path, last 4096 bytes of nonempty diagnostic output.
- [ ] Update `AgentUpdateWorker.Production`: distinguish process timeout/output overflow from actual code-probe exit 20, normalize only inconclusive heartbeat outcomes to 10.
- [ ] Extend `tests/agent-update-service-test.py` and `tests/AgentUpdateWorkerTest.java` to verify timeout, retry, rollback, no orphan JVM, idle behavior and retained final diagnostics.
- [ ] Run shell supervision fixtures and worker suite at 64 MiB.

## 3. Safe full-installer boundary

- [ ] Write update-v2 markers in `update-install.sh`, check them in `update-local.sh`, report installed marker in `AssessmentCollector` and `AgentTool`.
- [ ] Set signed `minUpdater=2` in `ops/agent_update_signing.py`; accept supported generations in worker, server catalog and installer build validation.
- [ ] Compare capability generation with the signed minimum in all `AgentUpdateRegistry` eligibility/delivery/claim paths; preserve v1 heartbeat metadata.
- [ ] Extend HTTP eligibility tests, installer tests and manifest tests. Show manual installer requirement in the existing dashboard reason text.
- [ ] Correct the historical lab private-runtime destination without weakening root-trust checks; document migration for already installed legacy copies.

## 4. Release evidence

- [ ] Extend `ops/build-dashboard-release.py` to run collector regression suites at 64 MiB, including the new tests.
- [ ] Run relevant Python installer, migration, trust and updater fixtures; resolve failures before reserving release.
- [ ] Build `python3 ops/build-dashboard-release.py --version 2.0.2` with loopback access for HTTP/TLS tests.
- [ ] Verify artifact hashes, manifests, signature, class-file level, unified versions and unchanged 2.0.0 artifacts. Record actual test/platform coverage and upgrade commands in `docs/agent-hardening-2.0.2.md` and release evidence.
- [ ] Deliver local package/publisher paths, one-time full installation requirement and explicit untested32-bit limitation. Do not publish remotely from this session.

## User scope update

- [x] Stop further heavy stress scans; none remains running. Preserve the failed 2.0.1 long-path evidence and archives.
- [x] Set the normal collector launcher to `-Xms16m -Xmx96m`; lightweight probes remain 64 MiB and the updater worker already uses 96 MiB.
- [x] Package 2.0.2 with ordinary release checks; report that the large long-path reproduction was not repeated.

Completion record: the 2.0.2 release builder exited successfully. Its ordinary test logs are in `output/dashboard-releases/2.0.2/evidence`. The real launcher reported a 16 MiB initial heap and a 96 MiB maximum heap. Artifact verification checked all 15 archive hashes across 2.0.0, 2.0.1 and 2.0.2 and the 36 collector classes (major version 50). See `output/agent-hardening/2.0.2/artifact-verification.json`. The failed 2.0.1 long-path stress result remains preserved; that scenario was not rerun. No remote deployment or real Java 6/32-bit validation was performed.
