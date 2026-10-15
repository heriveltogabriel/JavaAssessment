# Rich inventory reports

User requested JDBC columns in every report, more server information (IP, Java version, JDBC), and richer JDBC filters. Extend the approved report workflow, preserving no collection/coverage columns and the existing export contract.

Default reports show a compact JDBC column with one datasource-driver-version tuple per line. Machines and Java installations show JDBC of the machine, without implying every installed Java uses it. Servers show JDBC linked by recorded associations; optional machine JDBC is explicitly labeled. Environment summary shows distinct datasource-driver-version tuples across filtered machines. JDBC detail retains individual driver/class/version fields and adds database, IP and application context.

Server Java version comes from recorded javaHome matching a runtime on that host; it never uses the collector JVM or an arbitrary installed version. Missing association is shown as unknown. JDBC server/application associations support the collector's array form as well as scalar legacy fields. No association across hosts or merely equal server names on different machines.

Filter groups: general host/server scope and JDBC. JDBC offers datasource/name JNDI, database type, driver/name/class/version/reference, server/application association, resource type, observed state, presence/absence. Suggestions use existing bounded scalar metadata, not external sources or connection strings. Text search remains literal case-insensitive, exact driver/server versions. Multiple JDBC predicates apply to the same datasource, including when filtering machines or environments. On server reports, JDBC filters require a datasource associated with the current server. Fields absent from old inventory remain unknown; no new collection or application probing.

PDF/CSV include selected columns for all rows; existing memory/row/cell bounds stay enforced. UI wraps JDBC tuples, retains horizontal table scrolling, and labels association scope. New local release 2.0.17; do not modify 2.0.15 artifacts or deploy remotely.
