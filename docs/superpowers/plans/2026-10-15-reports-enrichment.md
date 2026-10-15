# Report enrichment implementation plan

User authorized enriching all reports and JDBC filters in the existing feature. No further design approval needed; do not deploy automatically.

**Goal:** JDBC context in every default report; useful server runtime details and richer JDBC search.
**Architecture:** Extend allowlisted SQL projections and correlated predicates in Reports.java. Shared JDBC text keeps datasource-driver-version together. Server association uses same-host recorded PID/name (including association arrays); Java version uses recorded javaHome matched to runtime inventory. UI groups filters and offers bounded inventory suggestions. CSV/PDF share query output.

- [x] Backend/tests: add JDBC to machines/java/servers/jdbc/environments defaults; server IP/OS/Java version/path/PID fields. Match server JDBC to recorded association; host JDBC optional and explicitly labeled. Add databaseType, driverClass, driverReference, serverName, applicationName, resourceType, datasourceState, jdbcPresence filters; filter datasource name also covers recorded JNDI. All JDBC conditions must match same datasource; server model filters must match server-associated datasource. Use scalar type guards, bounded options (100 values per field), parameterized SQL, existing result limits. Environment JDBC groups distinct tuples with bounded text. Tests: conflicting drivers/versions, multiple servers/runtimes/associations, unknown metadata, no data leakage, all defaults, presence/absence, exports.
- [x] UI/tests: groups General and JDBC; new text controls with native datalist suggestions, JDBC presence select; grouped layout and clear scope guidance; default tables richer without page overflow; preserve CSV/PDF and session behavior. Integration tests use real query options and all models.
- [x] Package: run query/HTTP/PDF/browser regressions, reserve new pair 2.0.16/2.0.17 preserving 2.0.15; build, verify signed/internal checksums, installer, notes; commit locally. Warn agents need separate update, reports use stored inventory. No publish or push.

Completed locally: 2.0.17. Final ZIP internal/outer checksums, signatures, 10 legacy profiles and installer verified. Native environment validation remains pending.
