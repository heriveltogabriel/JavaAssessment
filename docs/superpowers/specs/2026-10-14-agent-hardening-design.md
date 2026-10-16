# Agent hardening — 2.0.2

Authorized by the user after the 2.0.0 production review. Linux/Java 32-bit execution is explicitly deferred; retain Java 6 bytecode compatibility without claiming that this is runtime certification.

Use a 16 MiB initial heap and a 96 MiB maximum heap, as requested by the user. Keep compact SHA-256 keys for visited paths to avoid retaining complete long paths. Replace eager directory arrays with a closeable streaming reader. Java 7+ uses DirectoryStream through reflection, preserving Java 6 linkage. Java 6 uses a fixed, argument-separated GNU find process with memory/CPU/time bounds and a bounded NUL-delimited reader. Unreadable, oversized or timed-out directories become coverage warnings. Never execute discovered application binaries. Retain JDBC priority and exclusion semantics.

Heartbeat transport timeouts are inconclusive and retried with the transaction journal intact; failed code probes still roll back. Set the same process-local nonblocking RNG option already used by the collector on updater JVMs. Preserve the end of bounded diagnostics and avoid starting an updater JVM when there is no transaction/request.

Exclude the dedicated private runtime and updater processes from application inventory, while retaining actual application use of that runtime. Preserve strict ownership/symlink checks. The old lab-only private runtime preparation script must stop creating runtime trees inside the collector package.

Ship unified version 2.0.2. Changed updater scripts require one full installer run. Advertise updater generation 2 only when its installed marker is present; sign the new release with minimum updater 2; enforce this at preview, creation, delivery and claim. Keep the existing action protocol. Old archives remain immutable.

Validation: regression suites under 64 MiB; original wide-directory OOM reproduction; protocol tests for stalled/malformed legacy enumeration; updater timeout/retry/rollback and child cleanup; generation eligibility; installer/root trust/migration; signed packaging and Java 6 class-file checks. Real Java 6 and Linux32 are unverified in this environment.

User scope update: stop additional large-directory stress runs. Preserve the 2.0.1 long-path OOM evidence; the compact-key fix is compiled and checked by ordinary discovery regressions only. No claim that the heavy failing scenario has been rerun.
