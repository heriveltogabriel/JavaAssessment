# Unified agent download implementation plan

**Goal:** One Download agent entry opens ZIP and terminal methods with their own instructions and downloadable curl/wget scripts.
**Architecture:** Keep the existing environment dialog and ZIP lifecycle. Mount terminal access as its second panel, retaining the existing authenticated API and credential lifecycle. Clear terminal credentials when leaving its panel; guard mutations and ignore stale responses.
**Tech stack:** Vanilla JavaScript/CSS; Playwright browser fixtures; existing signed release builder.

- [x] Update browser fixtures to assert one entry, two accessible method tabs, exact script contents, ZIP option preservation, role controls, stale responses and mobile layout.
- [x] Update web/environments.js with method navigation and isolated panels; preserve ZIP enrollment flow. Refactor web/install-access.js into an embedded panel, add credential-bearing script downloads with explicit warnings.
- [x] Style panels and keyboard focus in web/environments.css. Run both installation browser suites and dashboard/interface regression tests; inspect screenshots.
- [x] Document both methods and prepare immutable release 2.0.31 (bridge 2.0.30); verify final embedded ZIP, HTTP download routes, signed candidate and complete installer. Do not publish or push.
