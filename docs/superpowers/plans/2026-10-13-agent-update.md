# Agent Remote Update Implementation Plan

> **For agentic workers:** Use executing-plans and the user's explicitly authorized parallel agents. The proposal is approved. No additional approval is needed for local implementation and disposable tests.

**Goal:** Deliver signed administrator-triggered remote agent upgrades over outbound HTTPS, with local rollback and a reproducible test scenario.

**Architecture:** Existing authenticated polling delivers typed update requests. A separate protected helper replaces only collector.jar after offline-signature and required-assets validation, preserving identity and data. Java17/SQLite tracks durable per-host progress and gradual batches; a shared browser dialog handles individual/environment selection.

**Tech Stack:** Java17 server, Java6-compatible agent bytecode50, shell systemd/SysV service helpers, Python offline release signer, JavaScript and Playwright.

The exact payload and endpoint contract is docs/superpowers/specs/2026-10-13-agent-update-design.md. Every worker reads it first and reports any contract changes before implementing them. Existing ignored project checkout is reused; no force-add/commit or unrelated project changes.

## Parallel tasks

- [x] Backend agent: create server/AgentUpdateRegistry.java and server/AgentUpdateCatalog.java; integrate server/AssessmentServer.java, InventoryStore.java, MachineActions.java, UninstallRegistry.java. Add tests/AgentUpdateHttpTest.java. Test session401, reader403, CSRF403, host ownership, requestKey replay, active capability, signed current release, version monotonicity, job/uninstall/update conflicts, one delivered host per batch, status transitions, cancellation, restart and ACK retry. Compile shared/Json.java server/*.java tests/TestAccounts.java tests/AgentUpdateHttpTest.java to output/agent-update/backend-classes with javac --release17 and run assessment.AgentUpdateHttpTest using loopback only.
- [x] Collector agent: create collector/AgentUpdateWorker.java, AgentUpdateProbe.java and update-install.sh/update-service.sh/update-local.sh as appropriate; integrate collector/AssessmentCollector.java, AgentTransport.java, root-system-install.sh, uninstall-local.sh/uninstall-install.sh for lifecycle cleanup/conflicts. Add Java and Python tests for signed manifest verification, version/JAR match, requiredFiles, bounded transport, atomic swap, crash recovery, rollback and service state. Use Java6 language/bytecode with the existing ECJ compiler. No dashboard/ops/web changes.
- [x] Frontend agent: create focused web/agent-updates.js/css, integrate menu/actions/index/access/app hooks. Add tests/agent-updates-browser.cjs and snapshots. Test eligible preview/selection by host or scope, stale target/errors, gradual status, exact target version, cancellation, reader/session restrictions, desktop/mobile. No backend/collector/ops changes.
- [x] Root: create ops/agent_update_signing.py; update ops/unified_agent.py and ops/build-dashboard-release.py to include new reviewed shell/public assets and signed current release, preserving immutable prior releases. Signer generates RSA3072 private key with mode0600, public DER, exact manifest and signature; never include private key in artifacts. Integrate dashboard deployment and installer dist assets while preserving config/CA/database. Tests must prove package/checksum/version/secret exclusions.
- [x] Root integration: run a real Java/SQLite end-to-end update fixture with a signed good payload and a signed failing test payload in a disposable filesystem. Verify version changes only after actual probe/heartbeat and rollback preserves old version/identity/inventory. Run existing machine-actions, account, uninstall, root-service and installer regressions impacted by changes.
- [x] Root delivery: build sequential shared dashboard/agent releases after tests stabilize. Prepare enabling release and following target so the user can install once and exercise a real remote upgrade. Supply an exact manual runbook and receipts/checksums; no remote SSH/publication or real VM actions by agents.

## Integration review checkpoints

1. Contract alignment before agents implement; reject arbitrary command/URL/path input and unsigned package paths.
2. Freeze each component, read its security-sensitive changes and independently run meaningful tests.
3. Verify the private signer stays local, trusted public key is pinned initially and update payload never overwrites configuration, runtime libraries, applications or Java installations.
4. Confirm outages versus bad code are distinguished, backup survives lost ACK and a batch cannot advance while outcome is unconfirmed.
5. Verify current-version catalog and manual-bootstrap limitation are explicit in the UI/runbook. Package only after local browser/service simulations pass and disclose the boundary of simulated Linux service tests.
