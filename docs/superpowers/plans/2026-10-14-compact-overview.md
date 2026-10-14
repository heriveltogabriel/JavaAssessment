# Compact overview implementation plan

**Goal:** Add compact server/JDBC distributions and replace overview host cards with a bounded, searchable table.

**Architecture:** SQL aggregations in `InventoryQueries`, shared compact panel rendering in `web/dashboard.js`, and the existing app state/pagination flow. All totals remain environment scoped rather than page scoped.

**Tech Stack:** Java 17/SQLite, plain JavaScript/CSS, Node and local Chrome fixtures.

## Tasks

- [x] Add bounded `productGroups(scope,kind)` for servers (product/version, observed state) and datasources (datasource name/driver version), returning `{name,version,count,hostCount}` plus an `other` group. Compute the remainder from the full summary, not the preview page.
- [x] Return `serverGroups` and `jdbcGroups` on overview and use `page(scope,"hosts")` with ten rows, requested page/search and independent six-item activity preview. Return machines pagination and query; preserve machine detail behavior.
- [x] Render four compact distribution panels with up to three named rows and an Others row. Preserve Java group navigation and inventory links; use explicit unknown-version text and a JDBC driver label.
- [x] Render a host table with ten rows, contact state, environment, OS, Java, servers, datasources and existing actions. Add search submit/reset handlers and preserve search input focus on background refresh.
- [x] Update relevant overview selectors in browser fixtures and add focused Java/Node/browser coverage for a 100-host environment, grouping, paging, search and environment isolation.
- [x] Inspect desktop/mobile screenshots, run focused regressions and `git diff --check`; record results. No publication in this task.


## Validation results

- Compiled Java server, collector and tests with JDK 17 in `tmp/compact-overview/classes`.
- `CompactOverviewTest`: passed with 100 local hosts, ten-row SQL pages, groups/remainders, missing driver versions, environment isolation, full-inventory search/IP, empty results and machine detail.
- `compact-overview-browser.cjs`: passed against real loopback HTTP/SQLite. Verified four panels, search outside the first page, scoped totals, menus, drilldowns and desktop/mobile. Inspected screenshots; mobile table scroll remains inside the table and the document does not overflow.
- `dashboard.test.cjs`, `interface-refresh.test.cjs` (17 checks), `agent-release-notice.test.cjs`: passed.
- `InventoryStoreTest`, `DashboardVersionHttpTest`, `AgentUpdateHttpTest` (149 checks): passed.
- `agent-updates-real-browser.cjs`: passed with the new machine rows; the earlier update reminder and explicit update workflow are preserved.
- Existing browser fixtures were migrated from card selectors to row selectors. The entire historical browser suite and the 1,000-host fixture were not run; they are not included in the validation claims above.
- `git diff --check`: passed. No production host, agent or published release was modified.

Evidence: `tmp/compact-overview/evidence/overview-panels.png`, `overview-desktop.png`, `overview-mobile.png` (local ignored files, generated test data).
