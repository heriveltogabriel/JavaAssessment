# Machine cleanup implementation plan

**Goal:** Implement the approved explicit, current-inventory cleanup without an archive.
**Architecture:** Reuse paginated host inventory with a disconnected subset and minimum age. Add a focused MachineCleanup service with preview/revalidation; share complete deletion with MachineActions. Keep selection and confirmation in a focused browser module.
**Tech Stack:** Java 17, SQLite, vanilla JS/CSS, real HTTP/Playwright fixtures.

- [x] Backend: add period validation and disconnected SQL subset in InventoryQueries/AssessmentServer; MachineCleanup preview/execute with immutable target metadata and per-host skips in a write transaction.
- [x] Purge: replace retained machine snapshots in MachineActions with deletion of machine-linked update rows and removal from shared batch selection/skipped. Preserve others and minimal credential tombstones.
- [x] UI: add machine-cleanup.js/css, quick filter, minimum-age selector, selection column, elapsed contact column, server-backed confirmation and result dialog. Integrate URL/session/navigation lifecycle in app.js.
- [x] Verification: add MachineCleanupHttpTest with auth, CSRF, heartbeat races, moved/reinstalled hosts, blocked lifecycle, shared batches and rollback. Verify real HTTP browser flow and existing MachineActions/WorkRequests regressions.
- [x] Release: sync builder, pin verified 2.0.51 baseline, generate next version, build installer, verify signed catalog and archive hashes. Include agent-update notice and publication script.
