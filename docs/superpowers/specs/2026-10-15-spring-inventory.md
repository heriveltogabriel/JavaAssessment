# Spring application inventory

Approved scope: passive collection by default, optional application integration. User authorized implementation and a new release on 2026-10-15. Do not deploy to remote hosts or change application settings automatically.

## Passive collection
Identify running Spring Boot JARs, WARs and exploded classpaths. Read application/framework/build metadata without loading application classes. Resolve bounded local base/profile files, explicit config locations, selected launch properties and allowlisted process environment settings; do not persist raw credentials or JDBC URLs. Retain configuration evidence rather than claiming runtime use. Unknown imports, dynamic sources or ambiguous profiles must be explicit and cannot be represented as verified effective configuration. Preserve per-process datasource identity where effective inputs differ. Custom datasource prefixes require explicit collector configuration.

## Optional integration
Use explicitly configured local JSON reports produced by the application, not automatic network access. Bind reports to application path, PID, process start ticks and freshness. Allowlist names, driver/database versions and pool metadata; ignore arbitrary report properties. Expose evidence as application-reported, not collector-verified connectivity. Provide a dependency-free Java helper and Spring wiring example; connection metadata reading is explicitly enabled by the application operator. No password is exported. Keep importer disabled when no mappings are configured.

## Bounds and delivery
Keep collector Java 6 language/runtime compatibility and 96 MiB heap. Read bounded ZIP central directories and selected entries for large archives; cap work and reject malformed archives. Show database version independently of JDBC driver version in details/reports/CSV/PDF. Keep existing server discovery and updater support unchanged. Prepare a new immutable dashboard/agent release pair, verify signed support profiles, publish scripts and installer locally. Test passive fixtures, privacy, optional reports, report exports and regressions; explicitly report that real customer hosts and 32-bit are untested.
