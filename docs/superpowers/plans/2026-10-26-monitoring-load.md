# Monitoring load implementation plan

> **For agentic workers:** Use executing-plans to implement the tasks sequentially.

**Goal:** Reduce browser request traffic and keep monitoring reads off the SQLite writer.

**Architecture:** Work request GETs use existing read snapshots; scheduled maintenance owns expiry. Browser polls after completion every 30 seconds and backs off after errors, without the additional inventory poll on jobs.

**Tech Stack:** Java 17, SQLite, plain JavaScript, Node test harness and Playwright.

- [x] Add regression to `tests/WorkRequestsHttpTest.java`: hold writer transaction and require all three monitoring GETs to return before releasing it. Assert no expiry during reads, then expire through the maintenance path.
- [x] Extend `tests/interface-refresh.test.cjs`: fake timers exercise polling intervals, no overlap, backoff, hidden tab and disposal; jobs background state makes no request, explicit refresh still works.
- [x] Change only work request GET dispatch and transaction selection in `server/AssessmentServer.java`; retain the independent scheduler and all mutation protections.
- [x] Replace work request interval with completion-scheduled timeout; adjust visible interval description; skip background state for jobs in `web/app.js`.
- [x] Run targeted Java/Node tests, existing browser tests and full `ops/test-source.sh`; record actual outcomes and limits in validation docs.
- [x] Update campaign guide and TODO. Commit correction without version increment or replacing 2.1.26 installers.
