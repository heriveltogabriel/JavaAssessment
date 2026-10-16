# Individual User Access Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task. The user approved the proposal and explicitly requested parallel agent work. Steps use checkbox syntax for tracking.

**Goal:** Deliver individual email/password access, fixed ADMIN and READER groups, mandatory first-access password change, and administration with audit.

**Architecture:** UserAuth owns SQLite human-account and audit persistence, hashing and account invariants. AssessmentServer owns HTTP sessions, CSRF, per-request authorization and DTO redaction. A focused web/access module owns login, bootstrap, profile and administration; app.js integrates permissions into existing inventory views. Agent authentication remains separate. The existing release workflow provides the next common version.

**Tech Stack:** Java 17, SQLite, bundled Bouncy Castle Argon2id, plain JS/CSS, Python deployment tooling, Java real HTTP tests and Playwright.

---

## Approved reference and workflow

Spec: docs/superpowers/specs/2026-10-13-user-access-proposal.md, accepted by the user on 2026-10-13 (“Gostei”, asking parallel workflow). Use current checkout: this project is ignored by its parent repository; do not force-add it or alter another clone. All tests use isolated temporary fixtures. No remote shell or deployment.

Dependencies: contract → parallel backend / frontend / installation and rollback → integrate → independent security review and regression → release 1.5.4 if counter still allows → deliver local publisher. No worker changes another worker's assigned files without coordination. Root owns integration, security matrix tests, release execution and plan/evidence.

## Shared HTTP contract

All endpoint paths below are prefixed with /api. JSON request bodies; authenticated writes use X-CSRF-Token; login/bootstrap reject foreign Origin. Server ignores client-provided identity/permissions.

```json
{"authenticated":true,"csrfToken":"random","user":{"id":"uuid","name":"Marina","email":"marina@example.com","role":"ADMIN","status":"ACTIVE","mustChangePassword":false,"lastLoginAt":"ISO8601"},"mustChangePassword":false}
```

Roles: ADMIN / READER. Status: ACTIVE / FIRST_ACCESS / DISABLED. User DTO never includes hash, salts, revision or session keys. Fixed groups are display labels Administradores / Leitura.

- GET /auth/status: {bootstrapRequired:boolean}. Public, reveals no account identity.
- POST /auth/bootstrap: {currentPassword,name,email,password}. Uses the current legacy admin secret once, creates first ADMIN with new personal password; atomic single winner; returns session envelope.
- POST /login: {email,password}. Generic 401 on invalid/disabled/expired account. Returns session envelope. Legacy password without email never grants inventory access.
- GET /session: session envelope. Restricted first-access sessions can only use this, own password and logout.
- POST /account/password: {currentPassword,newPassword}. Requires 15–128 characters and different from old credential, revokes old sessions and returns a fresh session envelope.
- POST /logout: {ok:true}.
- GET /admin/users: {users:[user DTO]}; includes counts derivable in frontend.
- POST /admin/users: {name,email,role,adminPassword}; returns 201 {user,temporaryPassword,expiresAt}. Default role READER; random password valid24h, shown once. Reauthenticate administrator for all account mutations (simple consistent policy).
- PUT /admin/users: {id,name,email,role,enabled,adminPassword}; returns {user}. Enforce last enabled ADMIN in a serialized database transaction. Email/role/disable invalidate target sessions via auth revision.
- POST /admin/users/reset: {id,adminPassword}; returns {user,temporaryPassword,expiresAt}, invalidates target sessions.
- GET /admin/audit: {events:[{id,at,actorId,actorName,action,targetId,targetName}]}, newest200, no secret values.
- Existing GET /state, /item, /export.csv and /export remain available for READER, with admin-only fields removed. /state?view=settings is ADMIN-only. All other inventory endpoints default ADMIN-only, including package/info GET. Agent routes keep their own bearer authentication and never accept human sessions instead.

First-install tooling: invoke local protected provisioning CLI with name/email/password-file before service start. Do not store a plaintext provisioning password in server.properties. Legacy shared-secret configuration remains only for guarded existing-install migration. Persist metadata key `humanAuthActivated` true on activation and table `human_users`; this is the downgrade boundary for deployment tooling. Installation/rollback owner coordinates exact CLI with backend before integration.

## Task 1 — backend worker

Files: new server/UserAuth.java, server/PasswordHash.java, optional server/UserAdminTool.java; modify server/AssessmentServer.java; own new tests/UserAuthTest.java. Do not edit InventoryStore unless a new primitive is needed (current public exec/rows/scalar/transaction suffice).

- [x] Implement account schema and Argon2id parameters (19MiB, t2, p1 minimum), random salts and bounded hash parsing; measure actual local cost.
- [x] Implement bootstrap, authentication, one-time24h credentials, account CRUD without deletion, transactional last ADMIN, auth revision and sanitized audit. Preserve metadata/inventory.
- [x] Replace shared anonymous sessions with identity/revision, 30minute idle/8hour absolute limits, rotation on login/password, generic errors and per-account+IP progressive throttling. Recheck authority per request.
- [x] Enforce deny-by-default route permissions and first-access restriction before side effects. Redact administrative fields from reader views/export/details. Keep CSRF and secure cookies.
- [x] Add protected machine-side provisioning/recovery CLI and document args to installer worker; never emit existing password/hash. Recovery requires stopped service/exclusive process protection and private password file.
- [x] Test uniqueness, restart, bootstrap race, temporary expiration, disable/reset/revision, concurrent last-admin protection, hashing and secret-free DTO/audit.

Compile exact command (from project):
```sh
/private/tmp/assessment-jdk17/jdk-17.0.20.1+1/Contents/Home/bin/javac --release 17 -cp 'lib/*' -d output/user-access/classes shared/Json.java server/*.java tests/UserAuthTest.java
/private/tmp/assessment-jdk17/jdk-17.0.20.1+1/Contents/Home/bin/java -cp 'output/user-access/classes:lib/*' assessment.UserAuthTest
```
Expected: PASS; all data rooted in temporary directories.

## Task 2 — frontend worker (parallel)

Files: web/access.js, web/access.css, web/index.html, web/app.js, web/environments.js, web/enrollment.js, web/dashboard.js as necessary; tests/user-access-browser.cjs. Preserve unrelated existing inventory and version behavior.

- [x] Build email/password login, protected first-admin setup and first-password screen with confirm/show-password, password-manager autocomplete and accessible errors.
- [x] Integrate server session user in topbar and Minha conta/Trocar senha/Sair; clear sensitive UI on session expiry/logout. Never persist credentials in storage.
- [x] Build Administration tabs Usuários/Grupos/Atividade; search/filter, add/edit/disable/reactivate/reset, fresh-password reauthentication and one-time secret display with expiry.
- [x] Hide/guard every mutation affordance for READER (including environment cards, machine drawers, enrollment/package links, settings, scheduling, empty-state actions); preserve detail/filter/CSV.
- [x] Test mock contract desktop/mobile, first access, create/reset/edit and reader mutation controls. Then root runs against actual backend.

Browser runtime: /Users/heriveltosantos/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node with NODE_PATH=/Users/heriveltosantos/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules. No remote URLs. Save screenshots under output/user-access.

## Task 3 — installation and migration worker (parallel)

Files: dashboard-installer/install.py, dashboard-installer/build.py and documentation; ops/dashboard-release/update-dashboard.py and its tests; ops/build-dashboard-release.py if required; existing Java test fixtures that currently use password-only login. Own fixture helper tests/TestAccounts.java.

- [x] Provision first ADMIN using protected temporary credential and required name/email, via backend CLI; persist no plaintext provisioning credential in server config. Update installer tests/help.
- [x] Refuse rollback to pre-account software after humanAuthActivated; stop service before deciding rollback, retain new code/database if rollback unsafe. Cover successful/failed bootstrap, no-users and active-users rollback branches with Python tests. Preserve existing stop/SQLite/uninstall protections.
- [x] Update test fixtures to bootstrap once and subsequently login email/password using only dummy test secrets. Do not retain any production legacy-login bypass.
- [x] Update builder regression list for new account tests and include access assets/sources/CLI; preserve package immutability and common version workflow.

Run Python unittest files for installer and updater. Root handles release execution only after integrated verification.

## Task 4 — root integration and security validation

Files: tests/UserAccessHttpTest.java, tests/user-access-real-browser.cjs, docs/USER_ACCESS.md, output/user-access evidence.

- [x] Exercise actual HTTP anonymous/admin/reader/first-access matrix for every route, CSRF, generic login failures, secret redaction, password rotation and immediate revocation; verify agent token paths still work.
- [x] Verify existing inventory survives bootstrap/restart and original shared secret cannot log in after migration. Race two initial-admin attempts and last-admin demotions.
- [x] Review backend and frontend together for bypasses, XSS, secret logging/storage and unsafe rollback. Fix discovered issues; rerun relevant tests only.
- [x] Run existing core Java, CSV/version/package/installer regression and desktop/mobile real browser scenarios.
- [x] Reserve next common release, validate class/package/version consistency, seal local package. Record what is tested and what remains manual (actual VM deployment and target-machine hash cost).
- [x] Mark workflow complete and deliver publisher command plus first-access instructions, clearly distinguishing prepared versus deployed.

## Checkpoints

1. Contract agreed across workers; no UI/backend naming divergence.
2. Each worker submits files, test evidence and known risks before integration.
3. Security checks and migration pass before package build.
4. No package marked ready on failing checks. No historical package overwritten.

## Integration evidence before packaging

Three parallel workers completed their owned tasks. Root real HTTP matrix and53account-unitchecks pass. Eleven Java regressions and four browser regressions pass. Updater43 tests and installer18 checks (including10actual TLS/SQLite suite cases) pass. Review found and corrected password-file owner/mode validation, bootstrap last-login timestamp, failure audit persistence and idle renewal by background polling. Source fixture/toolchain compilation corrected before release reservation. No remote operations.

## Delivery complete

Common release1.5.4 prepared and sealed locally. All archive manifests and source snapshot checked; final1.5.4real-browser/version check and installer10-case TLS/SQLite suite passed. Publisher: output/dashboard-releases/1.5.4/deployment/publish-dashboard.py. Production publication remains manual; no VM accessed. The packaged plan captures the pre-seal checkpoint; this live plan records the final seal.
