# Portuguese interface implementation plan

**Goal:** Consistent Brazilian Portuguese across the user-facing application.
**Architecture:** Translate source UI literals while preserving keys/selectors/commands and supplied data. Localize known server presentation messages without changing protocol state. Use explicit pt-BR formatting and translated report/export headers.
**Tech stack:** Vanilla JS/HTML, Java 17 dashboard, existing PDF/CSV exporters, local browser fixtures and signed release tooling.

- [x] Inventory UI text; translate web/index.html and JavaScript views, including accessibility text, empty states, ZIP instructions and dynamic plural fragments.
- [x] Translate server UI errors/summaries and report/export presentation; preserve raw records and technical identifiers. Add focused locale regression tests.
- [x] Update existing text expectations, exercise UI/download/report flows and inspect screenshots/PDF output.
- [x] Build the next immutable paired release, verify signed packages and complete installer, document the update warning. Do not publish or push.

Delivered locally as 2.0.37 (bridge 2.0.36). Browser tests also exercised web files extracted from the final ZIP. Verified 799 checksums in six ZIPs, 24 public candidate checks with 16 reviewed profiles, eight installer tests, HTTP/authentication/inventory/CSV and PDF/report regressions. Candidates 2.0.33 and 2.0.35 were superseded before publication. No VM accessed, deployment or Git push performed.
