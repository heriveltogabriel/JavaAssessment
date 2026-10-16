# JDBC endpoints and discovery implementation plan

> Execution: implement tasks in this session with focused worker ownership, followed by integration review and tests. The user authorized uninterrupted execution; no extra approval checkpoint or release bump.

**Goal:** Collect configured database destinations and improve active WebLogic/Spring discovery, preserving passive collection and legacy Java compatibility.

**Architecture:** A shared Java 6 endpoint parser returns allowlisted fields to existing datasource collectors. Existing JSON inventory carries additive fields into SQL reports, CSV/PDF and dashboard; isolated lab probe validates without replacing installed agents.

**Tech Stack:** Java 6 collector, Java 17 dashboard, SQLite JSON, browser JavaScript, shell/Python operational tooling.

## Tasks

- [x] Endpoint parser and active WebLogic: add `collector/JdbcEndpoint.java` and tests; integrate in `SpringDataSource`, `SpringConfiguration`, `ServerDataSources`, `WebLogicDataSources`. Test Oracle SID/service/RAC, IPv6, credentials, unsupported aliases and hostile XML. Review `ProcessLaunch` domain selection and verify running-domain-only association/deduplication.
- [x] Spring discovery: improve `SpringConfiguration`, `ArchiveMetadata`, `AssessmentScan` and targeted fixtures. Test direct-class classpath application, Boot archive recognition without manifest version, external config, multiple datasources and constrained archive budgets. No generic Java false-positive.
- [x] Reports/UI: extend `Reports.java`, `web/app.js` and report definitions/filter presets to include `databaseHost` plus multi-host endpoints. Add regression fixtures for all report models and verify CSV/PDF share selected columns and filters. No raw URL.
- [x] Limits: change defaults to 180 seconds/50,000 files/16 MiB, preserve explicit saved settings and 96 MiB heap; update tests and docs. Run relevant fixtures using JDK 17 compilation then Java 6 bytecode compilation with ECJ.
- [x] Lab probe: create a standalone passive probe with private output and no registration/network transport. Build an isolated artifact outside published dist/install. Attempt SSH to known lab hosts with existing identity and strict host verification; record failure or sanitized result and provide fallback commands.
- [x] Integration validation: compile shared/server/collector/tests; run endpoint, Spring, WebLogic, report HTTP/filter/export and UI tests. Inspect diff for secrets, broad file access, unbounded work and version changes. Update TODO/design evidence and user runbook with exact commands/results.

Compilation baseline: `JAVA_HOME=/private/tmp/assessment-jdk17/jdk-17.0.20.1+1/Contents/Home`; `javac --release 17 -encoding UTF-8 -cp 'lib/*' -d tmp/jdbc-discovery-classes shared/*.java server/*.java collector/*.java tests/*.java`. Run collector fixture classes with `java -Xmx96m -cp 'tmp/jdbc-discovery-classes:lib/*' assessment.CLASS`; dashboard/report tests use `-Xmx384m`. Existing `ops/test-source.sh` supplies integration regression coverage. Additional test commands and measured results are recorded in the validation document as each task is completed.

Resultado e limites: [validação de 2026-10-16](../../validation/jdbc-discovery-2026-10-16.md). Sem alteração de versão nem publicação.
