# Spring Inventory Implementation Plan

> **For agentic workers:** Execute tasks with the executing-plans workflow; isolated components can be delegated with explicit file ownership.

**Goal:** Deliver richer passive Spring discovery and optional application-reported database versions in a new immutable release.

**Architecture:** Process-linked allowlisted inputs feed a bounded Spring configuration resolver. The optional local report reader enriches only its matching live application. Existing snapshot storage and report projections carry explicitly labelled database metadata.

**Tech Stack:** Java 6-compatible collector, Java 17 dashboard, vanilla JavaScript, Python release packaging.

---

### Task 1: Passive resolver (main worker)
Files: collector/SpringConfiguration.java, SpringDataSource.java, ArchiveMetadata.java, ProcessLaunch.java, AssessmentScan.java, SpringConfigurationTest.java.
- [x] Add fixtures asserting profile overrides, external properties/YAML, unresolved values, different processes sharing a JAR, custom prefixes, no credentials in JSON, and bounded large JAR reads.
- [x] Capture selected startup settings into transient ProcessLaunch maps; discard secrets and raw environment data.
- [x] Resolve local sources in order, flag unsupported config imports/activation, and retain only approved output fields. Keep dynamic config explicitly uncertain.
- [x] Read build metadata and exploded layouts without executing classes; use bounded ZIP entry reads.
- [x] Compile all sources and run collector suites with `java -Xmx96m`.

### Task 2: Optional integration (isolated worker)
Files: collector/SpringIntegration.java, SpringIntegrationTest.java, integration/spring/*, docs/SPRING_INTEGRATION.md.
- [x] Validate disabled default, explicit mapping, PID/start identity, freshness, file size and data allowlist with synthetic reports.
- [x] Implement `SpringIntegration.enrich(AssessmentScan scan, AssessmentScan.ProcessContext context, File processDirectory)` invoked after passive process discovery.
- [x] Supply dependency-free application helper and opt-in Spring configuration example. Export only permitted metadata; bound frequency and close borrowed connections.
- [x] Document permissions and differences between declared and application-reported data.

### Task 3: Presentation (isolated worker)
Files: web/app.js, web/dashboard.js, web/reports.js, server/InventoryReports.java and related report tests as discovered.
- [x] Expose databaseVersion and databaseVersionEvidence separately from driverVersion; recognize APPLICATION_REPORTED evidence without claiming independent connectivity validation.
- [x] Add database version to JDBC report/filter/CSV/PDF using existing shared projection. Preserve older snapshots.
- [x] Run report and UI tests including malicious names and missing fields.

### Task 4: Integration and release (main worker)
- [x] Run source regression, integration test and release checks. Review secrets, bounds, parser failure behavior, and 96 MiB heap.
- [x] Reserve the next bridge/target pair using existing release builder. Reuse reviewed origin profiles and immutable signing anchors.
- [x] Generate publish script and installer locally; verify inner checksums and version alignment. Do not overwrite 2.0.17.
- [x] Add release notes and validation results, with agent-update warning and real-host/32-bit limits.

Final candidate: 2.0.23 / bridge 2.0.22. 2.0.19 and 2.0.21 failed final classification/catalog gates and were preserved, excluded from origins and not published. All final package checks passed. Real Spring/database, Java 6 runtime and 32-bit validation remain operational follow-ups. No remote deployment or Git push.
