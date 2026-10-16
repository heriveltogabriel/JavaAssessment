# Password minimum and access recovery Implementation Plan

> **For agentic workers:** Use executing-plans to implement this plan task-by-task; delivery helper delegated within the parallel workflow already authorized for individual access.

**Goal:** Accept eight-character personal passwords and restore operator access with protected offline recovery.

**Architecture:** Reuse PasswordHash policy, existing account endpoints and UserAdminTool; retain Argon2id and session revocation. Ship a manual publisher/recovery wrapper and Linux helper with fixed target, private backup, service-stop verification and service-account execution.

**Tech Stack:** Java17/SQLite, browser JavaScript, Python3/systemd/SSH.

- [x] Update UserAuthTest boundary cases to reject 7 and 129, accept 8, 14, 128; exercise actual bootstrap/password change/login with 8 in UserAccessHttpTest and browser fixture. Confirm old implementation fails 8.
- [x] Change PasswordHash validation, access.js fields/hints, installer validation and current documentation from 15 to 8. Give bootstrap 401 a specific current-password message, retaining generic login responses.
- [x] Implement and test recover-access-on-host.py: pending bootstrap vs existing ADMIN, protected paths/backup/temporary credential, service restart on failures, no secret argv. Use existing UserAdminTool without direct hash/DB edits.
- [x] Implement atualizar-e-recuperar-acesso.py: run publisher, verify helper checksum, upload fixed target, invoke via interactive SSH; no local credential capture. Package helper/wrapper with release.
- [x] Run Java account/HTTP, Python installer and recovery tests and real browser workflow. Prepare then seal 1.5.6 with standard release checks. Verify manifest and source/runtime agreement.
- [x] Deliver exact manual command and state remote execution still required. Record evidence without secrets.

Validation: Java unit60 checks; full HTTP authorization PASS with8-character credentials; browserrealPASS; installerpolicy8tests; helper24tests; wrapper7tests; updater43tests. Five archives and deployment checksums verified. Release1.5.5 was reserved but failed when local HTTP binding was denied by sandbox;1.5.6 was prepared with permitted loopback tests. Published=false; remote reset requires the supplied Terminal command. This live plan was completed after sealing; packaged plan retains the preparation snapshot.

## Recovery revision2 after confirmed publication

- [x] Receipt confirms1.5.6 deployed with inventory/configuration preserved; recovery failed during preflight before stopping service or changing passwords.
- [x] Reproduce readable/traversable root-parent failure and terminal systemd failed-state blocking; keep writable parents, symlinks and populated cgroups rejected.
- [x] Require private mode on newly created backup; keep root-parent modes unchanged. Accept failed only with zero process IDs and empty cgroup.
- [x] Test29helper cases plus9wrapper cases. Deliver separate immutable operational bundle1.5.6-r2 with recovery-only entry and verified helper checksum. Published dashboard artifacts remain unchanged.
- [x] Supply replacement Terminal command. Remote recovery remains to be executed by the user because automated SSH execution is blocked.
