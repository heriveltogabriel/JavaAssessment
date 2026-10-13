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

Each inventory record has `id` and `hostId`; IDs need only be unique within the collection and machine. Evidence is retained with its original observation timestamp. Missing information is omitted or null. JRE-only records are excluded from inventory and job installation counts. Datasource configuration is distinguished from runtime evidence.

Results retry by `(hostId,jobId,eventId)` without duplicate inventory. A result arriving after a server restart or lease timeout may complete its original job; an older captured snapshot cannot replace newer machine inventory. Invalid ownership or a database error rejects the entire snapshot.

## Beta limits and operation

- 1,000 registered machines; 200 environments; 500 enrollment records.
- Result body 2 MiB; other JSON bodies 32 KiB. Up to 2,000 rows per inventory kind; 16 KiB per row; 64 KiB host detail record.
- HTTP and HTTPS each use eight workers and a queue of 32; exports are serialized.
- Default retention: 100 completed jobs and 200 result receipts per machine. `jobHistoryPerHost` (10–1,000) and `receiptHistoryPerHost` (200–2,000) configure these limits. Queued/running jobs are preserved.
- Installer dashboard heap: 384 MiB; systemd total-memory ceiling: 768 MiB. Native and OS memory are additional to Java heap. OOM exits the process so systemd can restart it.
- Recurrence is disabled initially. Collection defaults: 120 seconds, 10,000 files, batches of 100 with a 30 ms pause. Scope paths and limits can be overridden per environment/machine.
- The collector continues using existing protected JVM discovery, system service, bounded scans and HTTPS. Application directory permissions are not changed.

See `docs/BETA_VALIDATION.md` for measured local load and the target-VM validation still required.
