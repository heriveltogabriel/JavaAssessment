# Agent release notice implementation plan

> Execute inline in the existing checkout, on `codex/agent-release-notice`. The user approved the notice both in the dashboard and in delivery instructions. No remote updater redesign or deployment is included.

**Goal:** Remind administrators to update older agents after dashboard releases and retain the full updater improvement in the backlog.

**Architecture:** Add a global administrative summary to `/api/state`; render a persistent notice using the existing agent update component and modal. Keep update eligibility and explicit confirmation unchanged.

**Tech Stack:** Java 17, SQLite, plain JavaScript/CSS, Java HTTP fixtures and Node tests.

---

## Task 1 — Global version summary

- [x] Add `AgentUpdateRegistry.versionNotice()` returning `map("version", DashboardVersion.VERSION, "outdatedCount", outdated)`. Query active identities joined with hosts; group by `json_extract(h.payload,'$.collectorVersion')`, excluding lifecycle `uninstalled`. Count only versions for which `AgentUpdateCatalog.version(version)` and `AgentUpdateCatalog.compare(DashboardVersion.VERSION,version)>0`.
- [x] Add `view.put("agentUpdateNotice", updates.versionNotice())` to `AssessmentServer.publicView` only for administrators. Do not derive the count from filtered or paginated hosts.
- [x] Extend `DashboardVersionHttpTest`: seed old, equal, newer, unknown, revoked and uninstalled agents; assert the count in overview and a filtered one-row machines page; update an old agent via heartbeat; assert the refreshed count and no update requests.

## Task 2 — Administrative notice

- [x] Add a hidden section to `web/index.html` inside `main-content` before the current workspace content. Use title, explanatory text and button IDs prefixed `agent-release-notice`.
- [x] Add `renderNotice(summary)` to `web/agent-updates.js`, validate the version and positive integer count, set text through `textContent`, hide and clear when unavailable or not admin. Wire the button to `open({environmentId:'',label:'Todos os ambientes'})`.
- [x] Call it from `web/app.js` render and clear it on logout. Add wrapping responsive styles to `web/agent-updates.css`.
- [x] Add a focused JS behavior check for visibility, text and no mutation on render; run it together with existing version and interface-refresh checks. Inspect desktop and mobile presentation using a local fixture.

## Task 3 — Persistent release instructions and backlog

- [x] Add root `AGENTS.md` requiring the reminder in future delivery instructions and linking the release procedure/backlog.
- [x] Create `docs/RELEASE_PROCESS.md` with compatibility, reminder and completion verification steps and a reusable delivery message.
- [x] Create `docs/TODO.md` describing the deferred signed full-package updater, migration requirement, identity/configuration/CA preservation, rollback and compatibility validation.
- [x] Link both documents from README and add an explicit reminder to the current 2.0.3 release notes, without rewriting packaged artifacts.
- [x] Run focused tests, inspect `git diff --check`, and record results here. Clearly report that the feature is prepared locally for the next release.


## Validation results

- Compiled server, collector and test sources with JDK 17 in an isolated output directory.
- `DashboardVersionHttpTest`: passed, including restart, global count independent of filters/pagination, real authenticated heartbeat, zero pending count and reader permissions.
- `AgentUpdateHttpTest`: 149 checks passed; `AgentUpdateWorkerTest`: 165 checks passed.
- `agent-release-notice.test.cjs`, `interface-refresh.test.cjs` (17 checks) and `version-js.test.cjs`: passed.
- `agent-updates-real-browser.cjs`: passed with real local HTTP/SQLite and signed public catalog, including desktop/mobile notice, global read-only review, update flow and session clearing. Desktop and mobile screenshots inspected; no clipping or horizontal overflow.
- `git diff --check`: passed.

No production machine, release archive or published version was modified. This change is ready in source for the next versioned delivery; the full-package updater remains deferred.
