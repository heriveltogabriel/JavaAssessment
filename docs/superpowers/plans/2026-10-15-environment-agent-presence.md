# Environment agent presence implementation plan

**Goal:** Replace coverage summaries on environment cards with live agent communication counts and scoped machine links.
**Architecture:** Aggregate in SQLite across the whole environment. Use the existing 150-second delayed and 300-second silent thresholds, a single server timestamp, and distinct revoked/uninstalled/awaiting/unknown states. Reuse the existing 15-second refresh and subset URL state.
**Tech stack:** Java 17, SQLite, vanilla JS/CSS, Java integration tests and Playwright.

- [x] Add a shared SQL presence expression to InventoryQueries; use it for aggregate and paginated host subsets. Include environment presence in the environments view.
- [x] Replace assessmentInsights in web/environments.js, retaining last assessment and coverage elsewhere. Add accessible status links, color and explanatory threshold text.
- [x] Extend web/app.js environment navigation, subset labels, URL restoration and fixture filtering.
- [x] Test boundary timestamps, revoked/uninstalled/unknown, >100 hosts, environment isolation, pagination and return to communication. Test card navigation, filter clearing, reload and mobile layout in browser.
- [x] Prepare immutable release, verify signed migration profiles and installer checksums, and provide publication command. Do not deploy implicitly or commit unrelated work.
