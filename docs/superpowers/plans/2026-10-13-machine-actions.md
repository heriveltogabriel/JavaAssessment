# Machine actions Implementation Plan

> **For agentic workers:** Use executing-plans; backend and frontend delegated within the user's existing parallel workflow.

**Goal:** Ship per-machine move, detach and guarded permanent deletion from an accessible three-dot menu.

**Architecture:** Reuse environment assignment APIs; add atomic ADMIN DELETE/api/hosts with an exact-name confirmation and revoked/uninstalled eligibility. Retain minimal revoked-identity tombstones and remove only selected host data. Share menu rendering/controller across host table and overview cards.

**Tech Stack:** Java17/SQLite, JavaScript/CSS, Playwright, Python release builder.

- [x] Backend: implement DELETE/api/hosts `{hostId,confirmationName}` in AssessmentServer or focused helper. Require revoked identity or uninstalled lifecycle, reject pending/unconfirmed uninstall. Delete host inventory/jobs/receipts/schedules/uninstall records in existing transaction; revoke/minimize retained identity. Return `{deleted:true,hostId}`. Test401/403/CSRF/name/active/pending, isolation, rollback and restart/old-token/old-installation retries.
- [x] Frontend: per-host ⋮ buttons in Machines table and machine cards. Move existing environment dialog; detach confirmation with inventory-preservation text; delete name confirmation and red separated action. Block ineligible delete with visible reason. Preserve environment context after detach/delete and hide from READER. Test keyboard/outsideclick/escape/mobile/revoked machine/stale target/errors.
- [x] Integration: new local real HTTP/browser regressions exercise create/revoke/move/detach/delete, free environment deletion, retained other hosts and reader denial. Keep all fixtures loopback-only and disposable.
- [x] Delivery: add new tests/docs to release generator, reserve/compile1.5.7 with loopback permission, run relevant account/environment/uninstall regressions, package and verify checksums/source/runtime. Preserve1.5.6 packages and operational recovery revision2. Supply manual publisher command and evidence, accurately distinguishing prepared/deployed.

Workspace is ignored by parent Git; do not force-add or touch unrelated sibling projects. Existing design already approved; no further approval gate for implementation.

Validation: 73 machine HTTP checks; 16 mock browser scenarios; real browser1.5.7 move/detach/delete/freeenvironment/reader/mobile flows; account and old-assignment browser regressions; existing uninstall, inventory and CSV HTTP regressions; 50 publisher tests. Package and runtime/source checksum evidence is recorded under output/dashboard-releases/1.5.7/evidence. Remote publication remains manual; no production records changed.
