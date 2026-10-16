# Environment Install Access Implementation Plan

**Goal:** Add reusable environment installation commands while retaining existing ZIP downloads.
**Architecture:** Dedicated HMAC credential registry + existing enrollment registry; narrowly scoped download route; isolated admin modal; existing root installer unchanged.
**Tech Stack:** Java 17 dashboard, SQLite, JavaScript, POSIX shell, Python release tooling.

### 1. Credential lifecycle (isolated backend ownership)
- [x] Add server/EnvironmentInstallAccess.java: status, create, renew, revoke, credential, issueDownload; protected key in data and metadata namespace `environmentInstallAccess:`. Atomic store transactions, safe audit, fail-closed key handling, environment validation. Use HmacSHA256 and random generation UUID.
- [x] Extend server/EnrollmentRegistry.java registration with generation validation only for enrollment records marked installAccessGeneration. Ordinary enrollments unchanged.
- [x] Add tests/EnvironmentInstallAccessTest.java with restart, batch, revoked ZIPs and live-agent credential preservation, no-secret serialization and key permission/link rejection. Run Java test on isolated SQLite with assertions.

### 2. Command generation and HTTP (main ownership)
- [x] Add server/InstallCommand.java with bounded CA PEM, HTTPS URL validation, shell quoting and separate curl/wget private configs; authenticated download, no redirects/TLS bypass, unzip validation, private staging and separate install instruction.
- [x] Modify server/AssessmentServer.java to create access on environment POST, expose admin lifecycle endpoint and specific unauthenticated-with-bearer download route. Use semaphore 2 and authenticated per-environment emission limits; never generic auth bypass.
- [x] Add tests/EnvironmentInstallHttpTest.java and InstallCommandTest.java: role/CSRF/session separation, environment isolation, token query refusal, ZIP content, credentials absent from inventory/export, renew/revoke survival and current ZIP behavior. Compile via javac --release 17 and run local HTTP fixtures.

### 3. Interface (isolated UI ownership)
- [x] Add web/install-access.js and styles in environments.css, include script in index.html. Independent admin dialog with current access state, curl/wget tabs, copy, renew/revoke/enable and warnings about persistent credential. No localStorage or cookie credentials. Clear dialog and cancel stale requests on close/logout.
- [x] Add `Instalar via terminal` action alongside unchanged download action in environments.js; offer modal after creation. Existing environments need explicit activation.
- [x] Add browser fixture tests covering copy, renew, revoke, old environment, role/no secret retention, responsive layout; keep existing ZIP tests passing.

### 4. Integration and release
- [x] Review cross-component contracts and tests; run source regression and relevant browser checks; document usage/backup/rotation/revocation and legacy HTTPS limits.
- [x] Sync reviewed sources to operational mirror, reserve next B/T pair with --lab-canary, preserve previous artifacts. Register only already validated previous release pins; exclude failed releases.
- [x] Verify candidate signatures/routes/bytecode/internal hashes; build installer and run installer tests. Keep final source versions aligned and provide publish command plus mandatory agent-update guidance.

Execution authorized by user; do not ask to approve this written restatement again. Preserve prior Spring changes. Do not commit unrelated work, publish remotely or push Git.

## Completion evidence

Release 2.0.25 (internal bridge 2.0.24) prepared locally; no deployment or Git push. Source regression passed. Environment credential/HTTP/command suites passed 43/53/37 checks, real curl TLS fixture passed, and both new terminal modal and existing ZIP browser flows passed. Candidate verification authenticated 12 known legacy profiles; 24 candidate rejection/verification checks and 8 installer tests passed. All installer internal checksums verified. Private access key is omitted from public packages and included when present in the publisher's private backup (64 publisher regression tests passed).

Operational build tools remain in the existing assessment-pilot mirror; the versioned public source package includes them. Wget was tested via a controlled executable fixture; real distribution/client TLS and remote installation are pending. New access is additive; the existing ZIP workflow is unchanged.

## Follow-up: final-package regression (2.0.27)
- [x] Reproduce 2.0.25 refusal using ZIP extracted from the final runtime.
- [x] Accept the exact signed full-update trio, reject incomplete/unknown entries, preserve legacy ZIPs.
- [x] Test both download endpoints and unchanged signed bytes with the real final artifact.
- [x] Move release gate after full-update embedding; build and authenticate a fresh 2.0.29 delivery (2.0.27 failed the historical helper bound and remains unpublished).
- [x] Deliver publisher command; remote publication and VM validation remain user-operated.

Release tool follow-up: the next immutable package had 17 reviewed helper hashes; the old 16-entry build-time cap failed. Centralized validation at 32 explicit, unique SHA-256 hashes in signing/bridge/full-release tools; retain the required original hash and independent catalog comparison. Boundary tests cover 16, 17, 32, refusal of 33, duplicates, missing original, malformed and non-string values. No installed agent protocol or bootstrap trust change.

2.0.29 completion: final ZIP gate 135 checks, final-package HTTP gate 53 checks, helper-bound 16 checks, independent signed candidate 24 checks/13 legacy profiles, installer 8 tests and full internal checksum/source consistency PASS. No dashboard or agent was remotely altered; production confirmation awaits user publication.
