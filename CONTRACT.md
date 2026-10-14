# Java Assessment 1.0 Beta — interface and storage contract

The dashboard targets Enterprise Linux 8+ and Java 17+. Collectors retain the Java 6 protocol and can run on legacy hosts. This release is independent of Oracle JMS. Deployment requires explicit user approval; building a release does not authorize a remote update.

## Persistence and memory

`data/inventory.sqlite` stores machines, environments, individual inventory records, jobs, receipts, identities, enrollments and settings. Inventory records have indexed host, kind, version and search columns plus their own JSON payload. There is no resident whole-fleet object and no normal-write `state.json`.

SQLite uses WAL, full synchronous commits, one serialized writer and four bounded reader connections. Dashboard queries use read snapshots; result acceptance commits the host inventory, job and receipt atomically. Network response writes happen after transactions release. Exports use one private temporary file at a time, built in bounded pages under a consistent read snapshot, then streamed and removed.

On first boot, a bounded one-time migration imports legacy `state.json` (maximum 32 MiB) and `auth-registry.json` (maximum 8 MiB), preserving originals and private backups. A transaction marker prevents repeated imports. Never copy a live SQLite database alone while WAL writes are active; use the installer `database.sh backup` helper.

## Browser API

The browser uses an HTTP-only session cookie. `GET /api/session` returns `{authenticated:true,csrfToken}`. `POST /api/login` accepts `{password}`; mutations require `X-CSRF-Token`. Errors return `{error}`. Sessions expire after eight hours and are bounded to 128 active sessions.

`GET /api/state` returns a **scoped, bounded view**, not the whole inventory. Parameters:

| Parameter | Meaning |
| --- | --- |
| `view` | `overview`, `machine`, `environments`, `versions`, `hosts`, `runtimes`, `applications`, `servers`, `libraries`, `datasources`, `processes`, `jobs`, `settings` |
| `environmentId` | Empty for all; an environment ID; `__unassigned__` for machines without an environment |
| `hostId` | Optional selected machine, validated against the environment |
| `page`, `pageSize` | One-based page; default 20, maximum 100 rows |
| `q` | Literal substring search, maximum 200 characters; inventory search includes the machine name |
| `version`, `versionMode` | Major family (`Java 6`) or exact version (`1.6.0_45`); modes `family` / `exact` |
| `subset` | Contextual subset such as `jdbc`, `datasources` or server `runtime` |
| `from`, `to`, `range` | ISO timestamps or dates; range `all`, `24h`, `7d`, `30d`; records lacking timestamps remain visible |

The response includes `scoped:true`, `version`, `scope`, `serverTime`, `summary`, `coverage`, `presence`, `settings` (effective scope), `defaultSettings` (global defaults), `javaGroups`, `exactJavaGroups`, `osGroups`, `datasourceSummary`, `environments` and collection arrays. `pagination` contains `{collection,page,pageSize,total,pages}`. Summary counts cover the selected environment/machine; table totals honor search and date filters. The browser must use these totals rather than counting the current page.

Overview contains at most six machine previews. Machine overview contains bounded inventory previews. Version groups are paginated; selecting a group loads a page of its installations. The libraries tab combines driver/library and datasource records in one shared page. Selected machine details remain available even when a table page has no rows. Enrollment summaries are restricted to the selected environment and omitted in machine scope.

`GET /api/item?kind=...&id=...&hostId=...` fetches an exact item with validated ownership and up to 20 related records per category. It returns `{item,host,related,relatedLimit}`.

`GET /api/export` exports all pages in the selected scope. Include the current view and filters to apply its table filter to that collection. Export contains no enrollment secrets or agent credentials.

`GET /api/export.csv` exports a single CSV across all inventory categories, with one row per record. Only `environmentId` and optional `hostId` select the scope; table search, date, view and pagination parameters do not limit this export. Environment and machine ownership are validated. An empty scope returns the header. The authenticated response uses `text/csv; charset=utf-8`, a UTF-8 BOM, comma delimiters, CRLF and a quoted attachment filename. Explicit columns omit credentials and configuration objects; formula-like text is escaped. The Actions menu opens a scope selector for all environments, an environment, unassigned machines or the current machine. See `docs/VERSIONED_DOWNLOAD_CSV.md`.

| Mutation | Effect |
| --- | --- |
| `POST /api/jobs {hostId}` | Queue one bounded assessment; reject overlapping queued/running jobs |
| `POST /api/jobs/run-scope {environmentId,hostId}` | Queue every eligible machine in the scope, independent of the visible page; return queued/skipped counts |
| `PUT /api/settings` | Update global collection defaults |
| `PUT /api/hosts/settings` | Set or replace machine overrides |
| `POST`, `PUT`, `DELETE /api/environments` | Create, edit or delete environments; deletion requires the exact name and an empty environment |
| `PUT /api/hosts/environment` | Move one machine |
| `POST /api/environments/package` | Download a package containing the public dashboard URL, CA and temporary environment enrollment credential |
| `POST /api/hosts/revoke` | Revoke one collector's credential |

`GET /api/collector.zip` downloads a generic credential-free package. There is no arbitrary remote command endpoint.

`GET /api/collector/info` returns the authenticated `{version,filename}` metadata for that ZIP; optional `environmentId` selects the environment filename. Version comes from the served ZIP's validated `DEPENDENCIES.json`, not the dashboard version. Downloads use `assessment-collector-<version>.zip` or `assessment-<environment-slug>-<version>.zip`. The download's `Content-Disposition` is authoritative when metadata changes between preview and download. Missing/invalid version metadata makes the package unavailable and is checked before creating an enrollment credential.

## Collector protocol (schema 1.0)

The collector registers with an enrollment token, receives a unique host credential and persists it locally. Each installed collector then uses `Authorization: Bearer <agentToken>`. Registration retries with the same installation identity are idempotent. Revoking an enrollment prevents further registration; already registered machine credentials are independent.

- `POST /api/agent/register`: establish identity and retrieve effective settings.
- `POST /api/agent/heartbeat {hostId,hostname,javaVersion}`: update contact time only; no scan.
- `GET /api/agent/poll?hostId=...`: return `{jobId,settings}` and lease a due job. With no job, do not scan.
- `POST /api/agent/results`: submit a snapshot; acknowledge only after database commit.
- `GET /api/agent/settings?hostId=...`: obtain effective settings.
- `POST /api/agent/install-events`: record bounded installation diagnostics.

Snapshot shape:

```json
{
  "schemaVersion": "1.0",
  "eventId": "unique-event-id",
  "jobId": "leased-job-id",
  "capturedAt": "2026-10-13T12:00:00.000Z",
  "collectorJavaVersion": "1.6.0_45",
  "host": {"id": "registered-host-id", "hostname": "example"},
  "runtimes": [],
  "processes": [],
  "servers": [],
  "libraries": [],
  "applications": [],
  "datasources": [],
  "coverage": {"status": "complete", "warnings": [], "stats": {}}
}
```

Each inventory record has `id` and `hostId`; IDs need only be unique within the collection and machine. Evidence is retained with its original observation timestamp. Missing information is omitted or null. Java installation counts include discovered JDKs and runtimes without a compiler when observed in application use (`kind:JRE`, `inUse:true`); a running application does not require `javac`. Unused standalone JRE records remain excluded. Several JVMs using the same canonical Java home count as one installation. Historical runtime sources retain their original observation time. Datasource configuration is distinguished from runtime evidence.

Results retry by `(hostId,jobId,eventId)` without duplicate inventory. A result arriving after a server restart or lease timeout may complete its original job; an older captured snapshot cannot replace newer machine inventory. Invalid ownership or a database error rejects the entire snapshot.

## Limits and operation

- 1,000 registered machines; 200 environments; 500 enrollment records.
- Result body 2 MiB; other JSON bodies 32 KiB. Up to 2,000 rows per inventory kind; 16 KiB per row; 64 KiB host detail record.
- HTTP and HTTPS each use eight workers and a queue of 32; exports are serialized.
- Default retention: 100 completed jobs and 200 result receipts per machine. `jobHistoryPerHost` (10–1,000) and `receiptHistoryPerHost` (200–2,000) configure these limits. Queued/running jobs are preserved.
- Installer dashboard heap: Xms/Xmx 1,536 MiB; systemd total-memory ceiling: 2 GiB. Native and OS memory are additional to Java heap. OOM exits the process so systemd can restart it.
- Recurrence is disabled initially. Collection defaults: 120 seconds, 10,000 files, batches of 100 with a 30 ms pause. Scope paths and limits can be overridden per environment/machine.
- The collector continues using existing protected JVM discovery, system service, bounded scans and HTTPS. Application directory permissions are not changed.

See `docs/BETA_VALIDATION.md` for measured local load and the target-VM validation still required.

## Individual remote uninstall (collector 1.5.0, review release)

This is local review code; publishing requires explicit approval. New root service installs add an independent restricted uninstall executor; the collector service retains `ProtectSystem=full`. Cron/manual installations do not advertise support.

- Heartbeat includes `uninstallCapability: "uninstall-v1"` only when the updated root service helper is installed. Missing capability clears prior support.
- `POST /api/hosts/uninstall {hostId,confirmationName,requestKey,expiresInMinutes}` uses browser session + CSRF, requires the exact current hostname, and queues one `UNINSTALL_AGENT_V1`. Validity is 5–1,440 minutes, default 60. Duplicate `requestKey` for the same host returns the same record. No command/path fields are accepted.
- `POST /api/hosts/uninstall/cancel {hostId,requestId}` cancels only `WAITING`. Cancellation and claim serialize in SQLite.
- `GET /api/agent/actions?hostId=...&capability=uninstall-v1` returns `{action}` before pending results retry. New polls can also return `{jobId:null,action:{type,requestId,expiresMillis}}`. Old polls never receive typed actions. Pending uninstall blocks new assessments; a running assessment blocks claim/delivery.
- `POST /api/agent/uninstall/claim {hostId,requestId}` requires the host's operational token. It commits `UNINSTALLING` and returns `{requestId,completionToken,confirmUntil}`. Retries recover the same credential. Cancelled/expired claims return `{requestId,executable:false}` without execution. Terminal/invalid claims are rejected.
- `POST /api/agent/uninstall/complete {hostId,requestId,outcome,verification}` accepts only the exclusive completion credential. `SUCCEEDED` requires `OWN_FILES_AND_SCHEDULER_ABSENT_V1`. Commit atomically records the outcome, marks lifecycle `uninstalled` and revokes the operational token. Repeating the same confirmation remains valid until the 24-hour completion window expires, including after revocation/server restart. Conflicting outcomes are rejected.

States: `WAITING`, `UNINSTALLING`, `SUCCEEDED`, `FAILED`, `UNCONFIRMED`, `CANCELLED`, `EXPIRED`. After 30 minutes without a result, a claimed request becomes `UNCONFIRMED`; it may still receive a result inside the completion window. Heartbeat absence never changes a request to success. Maintenance every 30 seconds expires pending actions and removes expired completion secrets.

At most 100 records per machine and 20 audit entries per request are retained. Valid completion receipts are not evicted early; new requests are refused if retention is full. Public host views/exports contain the latest request and bounded audit, never completion credentials or request keys. `summary.activeAgents` counts active operational identities and excludes confirmed uninstalled agents. Host/inventory counts remain historical.

The fixed remover rejects unknown files, symlinks, hardlinks, nested mounts, changed service units, systemd overrides, alternative scheduler files and collector entries in crontabs. It removes only inspected owned files; application JVMs and files are untouched. Existing administrator rollback directories outside `/opt/java-assessment-collector` are preserved. Nonempty internal backup directories require inspection. Helper identity/CA/completion state is removed after confirmation or its local 24-hour deadline. One bounded credential-free diagnostic record remains. See `docs/REMOTE_UNINSTALL_REVIEW.md` for validation and operational limits.

## Complete agent update (V3, not yet published)

The full implementation and release gates are specified in `docs/AGENT_UPDATE_V3.md` and `docs/validation/full-agent-update.md`. Known protected installations from 1.5.12 may migrate through a signed, origin-specific legacy bridge; version alone does not establish compatibility. Existing V3 managers receive a complete signed bundle directly.

One logical operation spans bridge and final generation. Dedicated V3 claim, pinned route/bundle/manager hashes, a new manager process and final authenticated heartbeat are required for success. V1 reports cannot complete a V3 parent. Recovery, active/previous code and immutable bootstrap are independent of configuration, identity, CA, JVM and spool. Unknown files are preserved.

`runAssessmentAfterUpdate` is a strict boolean included in request-key identity. The UI defaults to true; omitted API fields default to false. Only final success enables an idempotent post-update assessment. It may reuse a job without a lease, uses current effective settings, and is limited to one linked collection per environment and two globally, with ten seconds between starts in one environment. Collection outcome remains separate from update success.

The complete bundle is bounded at 64 MiB transferred / 128 MiB expanded / 256 files, with RSA-3072 signatures and exact file inventory. The legacy JAR route remains bounded at 32 MiB. Collector and manager launchers retain a 96 MiB maximum heap. Real Linux service/reboot and Java 6 validation remain release gates; 32-bit support is not claimed as tested.
