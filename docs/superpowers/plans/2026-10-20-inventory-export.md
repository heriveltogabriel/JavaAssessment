# Inventory ZIP Export Implementation Plan

> Execute inline using the executing-plans workflow; no delegated work is required.

**Goal:** Export the current technical inventory from an Administration tab as one ZIP for a separate analysis application.

**Architecture:** A dedicated `InventoryArchive` writer filters documents through explicit field schemas and streams collection JSON entries into a bounded ZIP within one SQLite read snapshot. The server authenticates administrators, owns temporary-file cleanup and download concurrency. A separate browser module provides the tab content and download state.

**Tech Stack:** Java 17, existing SQLite InventoryStore, JDK ZIP streams, plain browser JavaScript.

## Tasks

- [x] Add `server/InventoryArchive.java` and `tests/InventoryArchiveTest.java`. Verify manifest counts, IDs/associations, JDBC candidate versions, unsupported fields and nested credentials omitted, URL/assignment credentials redacted, empty data and size limits. Stream rows with `InventoryStore.forEachRow`, not a fleet-sized array.
- [x] Add administrator-only `GET /api/admin/inventory-export` in `server/AssessmentServer.java`. Reuse `exportSlot`, read snapshot, private temp file, reauthorization before download, and unconditional deletion. Add `tests/InventoryArchiveHttpTest.java` covering anonymous/reader/admin, filename/content type, archive contents and cleanup.
- [x] Add `web/inventory-export.js`, styles, script inclusion in `web/index.html`, and tab/navigation in `web/access.js`. Button downloads only ZIP. Explain included technical data and omitted access data, show busy/error states and preserve authentication epoch. Add browser-module tests for double-click, download failure, and session changes.
- [x] Update `README.md` and add `docs/INVENTORY_EXPORT.md` with format v1, contents, scope and confidentiality boundaries. Register meaningful tests in `ops/test-source.sh`.
- [x] Run targeted new Java and JavaScript tests, then `ops/test-source.sh` with the bundled JDK 17 and Node. Inspect the tab in a browser using fixture data if available. Keep release version and published installers untouched.

Commands use `JAVA_HOME=tools/jdk17/jdk-17.0.20.1+1/Contents/Home` and the runtime Node binary. Java compilation follows `ops/test-source.sh`; targeted classes run from `tmp/source-classes:lib/*`. Expected final regression output: `Source regression checks passed; heavy stress and external hosts were not exercised.`

## Resultado

Suíte completa aprovada em 2026-10-20. Teste real `tests/inventory-export-browser.cjs` aprovado com download ZIP, navegação por teclado e layouts de 1440 e 390 pixels. Arquivo baixado conferido por CRC, contagens e ausência das credenciais da fixture. Nenhuma máquina remota ou instalador publicado foi alterado.
