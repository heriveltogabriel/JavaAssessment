package assessment;

import java.io.*;
import java.util.*;

/** Synthetic process/config fixtures: no server process, database, management API or network is started. */
public final class ServerDataSourcesTest {
    static int checks;
    static void check(boolean value, String message) { checks++; CollectorTest.check(value, message); }
    static void text(File path, String value) throws Exception { ProcessLaunchTest.text(path, value); }
    static String tomcatResource(String name) {
        return "<Resource name='" + name + "' type='javax.sql.DataSource' driverClassName='org.postgresql.Driver' url='jdbc:postgresql://db-primary.example:5432,db-secondary.example:5433/database?password=PRIVATE_QUERY_CANARY' username='PRIVATE_USER_CANARY' password='PRIVATE_PASSWORD_CANARY' maxTotal='24'/>";
    }
    static String jbossDatasource(String name, boolean disabled) {
        return "<datasource jndi-name='java:/" + name + "' pool-name='" + name + "' enabled='" + !disabled + "'><connection-url>jdbc:h2:mem:PRIVATE_URL_CANARY;PASSWORD=PRIVATE_QUERY_CANARY</connection-url><driver>h2</driver><pool><max-pool-size>32</max-pool-size></pool><security><user-name>PRIVATE_USER_CANARY</user-name><password>PRIVATE_PASSWORD_CANARY</password></security></datasource>";
    }
    static String jboss(String body) { return "<server xmlns='urn:jboss:domain:20.0'><profile><subsystem xmlns='urn:jboss:domain:datasources:7.1'><datasources>" + body + "<drivers><driver name='h2' module='com.h2database.h2'><driver-class>org.h2.Driver</driver-class><xa-datasource-class>org.h2.jdbcx.JdbcDataSource</xa-datasource-class></driver></drivers></datasources></subsystem></profile></server>"; }
    static AssessmentScan scanner() { return ProcessLaunchTest.scanner(); }
    static Map inventory(AssessmentScan scan) { return AssessmentScan.map("host", AssessmentScan.map("id", scan.hostId, "hostname", scan.hostname), "datasources", scan.datasources, "servers", new ArrayList(scan.servers.values()), "applications", scan.applications, "processes", scan.processes, "runtimes", new ArrayList(scan.runtimes.values()), "libraries", scan.libraries, "coverage", AssessmentScan.map("status", scan.partial ? "partial" : "complete", "warnings", scan.warnings), "capturedAt", scan.timestamp); }
    static Map tomcat(File root) throws Exception {
        File home = new File(root, "tomcat-home"), base = new File(root, "tomcat-instance"), proc = new File(root, "tomcat-proc");
        text(new File(base, "conf/server.xml"), "<Server><GlobalNamingResources>" + tomcatResource("jdbc/global") + "</GlobalNamingResources><Service><Engine name='Catalina'><Host name='localhost'><Context path='/orders'>" + tomcatResource("jdbc/orders") + "</Context></Host><Host name='second'><Context path='/orders'>" + tomcatResource("jdbc/orders") + "</Context></Host></Engine></Service></Server>");
        text(new File(base, "conf/context.xml"), "<Context>" + tomcatResource("jdbc/default") + "<ResourceLink name='jdbc/shared' global='jdbc/global' type='javax.sql.DataSource'/></Context>");
        text(new File(base, "conf/Catalina/localhost/orders.xml"), "<Context>" + tomcatResource("jdbc/descriptor") + "<ResourceLink name='jdbc/link' global='jdbc/global' type='javax.sql.DataSource'/><Resource name='mail/test' type='javax.mail.Session'/></Context>");
        text(new File(base, "webapps/demo/META-INF/context.xml"), "<Context>" + tomcatResource("jdbc/application") + "</Context>");
        ProcessLaunchTest.process(proc, "98665001", "java", "-Dcatalina.home=" + home.getCanonicalPath(), "-Dcatalina.base=" + base.getCanonicalPath(), "org.apache.catalina.startup.Bootstrap", "start");
        ProcessLaunchTest.process(proc, "98665002", "java", "-Dcatalina.home=" + home.getCanonicalPath(), "-Dcatalina.base=" + base.getCanonicalPath(), "org.apache.catalina.startup.Bootstrap", "start");
        AssessmentScan scan = scanner(); scan.discoverProcesses(proc);
        check(scan.datasources.size() == 6, "Tomcat global, default, inline contexts, host descriptor and expanded app resource discovered; ResourceLink and non-JDBC not counted");
        for (Object value : scan.datasources) {
            Map row = (Map)value;
            check("CONFIGURED".equals(row.get("state")) && "configuration".equals(row.get("evidenceLevel")), "Configuration must not claim activation or connectivity");
            check("PostgreSQL".equals(row.get("databaseType")) && Integer.valueOf(24).equals(row.get("configuredMaxPoolSize")), "Tomcat safe driver/database and configured maximum retained");
            check(((List)row.get("associations")).size() == 2 && row.get("pid") == null && row.get("serverName") == null, "Shared config deduplicates with two process associations");
            check(row.get("poolCapacity") == null && row.get("activeConnections") == null && row.get("poolAvailable") == null, "No pool runtime counters fabricated");
        }
        String links = ""; for (Object value : scan.datasources) if ("jdbc/global".equals(((Map)value).get("name"))) links = Json.stringify(((Map)value).get("linkedContexts"));
        check(links.contains("\"context:default\"") && links.contains("jdbc/shared") && !links.contains("context:/context"), "conf/context.xml ResourceLink is labelled as the default context: " + links);
        long bytes = scan.bytesRead;
        for (Map rows : scan.configuredDatasourceRows.values()) check(rows.get("configurationSource") != null, "Source retained");
        AssessmentScan.ProcessContext context = context("Tomcat", base, home, 98665001);
        scan.serverDataSources.discover(context); check(scan.bytesRead == bytes && scan.datasources.size() == 6, "Repeated config reads use sanitized cache and stable identity");
        privacy(scan);
        AssessmentScan excluded = scanner(); excluded.excluded.add(base.getCanonicalPath()); excluded.discoverProcesses(proc);
        check(excluded.datasources.isEmpty(), "Explicit excludes override process-linked config");
        return inventory(scan);
    }
    static AssessmentScan.ProcessContext context(String type, File base, File home, long pid) throws Exception {
        ProcessLaunch launch = new ProcessLaunch(); launch.serverType = type; launch.serverBase = base.getCanonicalPath(); launch.serverHome = home.getCanonicalPath();
        launch.moduleName = "JBoss".equals(type) ? "org.jboss.as.standalone" : null;
        AssessmentScan.ProcessContext context = new AssessmentScan.ProcessContext(launch, pid, null, AssessmentScan.map(), null);
        context.server = AssessmentScan.map("name", type + " process " + pid); return context;
    }
    static Map wildfly(File root) throws Exception {
        File home = new File(root, "wildfly"), base = new File(root, "wildfly-instance"), conf = new File(root, "external-config"), proc = new File(root, "wildfly-proc");
        text(new File(base, "configuration/standalone.xml"), jboss(jbossDatasource("unused-default", false)));
        String xa = "<xa-datasource jndi-name='java:/OrdersXA' enabled='false'><xa-datasource-property name='URL'>jdbc:h2:mem:PRIVATE_XA_CANARY</xa-datasource-property><driver>h2</driver><xa-pool><max-pool-size>12</max-pool-size></xa-pool></xa-datasource>";
        text(new File(conf, "custom.xml"), jboss(jbossDatasource("OrdersDS", false) + xa));
        ProcessLaunchTest.process(proc, "98666001", "java", "-jar", new File(home, "jboss-modules.jar").getCanonicalPath(), "-mp", new File(home, "modules").getCanonicalPath(), "org.jboss.as.standalone", "-Djboss.server.base.dir=" + base.getCanonicalPath(), "-Djboss.server.config.dir=" + conf.getCanonicalPath(), "--server-config=custom.xml", "--password=PRIVATE_ARGS_CANARY");
        AssessmentScan scan = scanner(); scan.discoverProcesses(proc);
        check(scan.datasources.size() == 2, "JBoss selected configuration directory and filename override default");
        Map ordinary = (Map)scan.datasources.get(0), xarow = (Map)scan.datasources.get(1);
        check("java:/OrdersDS".equals(ordinary.get("name")) && "org.h2.Driver".equals(ordinary.get("driverName")) && "H2".equals(ordinary.get("databaseType")), "Namespaced driver reference resolves safe class");
        check("DISABLED".equals(xarow.get("state")) && Boolean.FALSE.equals(xarow.get("configuredEnabled")) && "org.h2.jdbcx.JdbcDataSource".equals(xarow.get("driverName")), "XA driver and explicit disabled configuration retained without active claims");
        check(Integer.valueOf(12).equals(xarow.get("configuredMaxPoolSize")) && xarow.get("activeConnections") == null, "XA configured max is not active pool metric");
        check(ordinary.get("pid") != null && ordinary.get("serverName") != null && ordinary.get("applicationId") != null, "Server process/application association retained");
        privacy(scan);
        for (String[] args : new String[][]{{"-c", "custom.xml"}, {"--server-config", "custom.xml"}, {"--read-only-server-config", "custom.xml"}, {"--read-only-server-config=custom.xml"}}) {
            List<String> command = new ArrayList<String>(Arrays.asList("java", "-jar", "/opt/wildfly/jboss-modules.jar", "org.jboss.as.standalone")); command.addAll(Arrays.asList(args));
            check("custom.xml".equals(ProcessLaunch.parse(command.toArray(new String[command.size()]), null).serverConfigFile), "JBoss selected configuration launcher forms supported");
        }
        AssessmentScan direct = scanner(); AssessmentScan.ProcessContext selected = context("JBoss", base, home, 1);
        direct.serverDataSources.discover(selected); check(direct.datasources.size() == 1 && "java:/unused-default".equals(((Map)direct.datasources.get(0)).get("name")), "Default standalone configuration supported");
        selected.launch.serverConfigFile = new File(conf, "custom.xml").getCanonicalPath();
        direct.serverDataSources.discover(selected); check(direct.datasources.size() == 3, "Absolute selected standalone configuration supported");
        AssessmentScan domain = scanner(); selected.launch.moduleName = "org.jboss.as.host-controller"; domain.serverDataSources.discover(selected);
        check(domain.datasources.isEmpty() && !domain.warnings.isEmpty(), "Domain profiles not misrepresented as active standalone resources");
        text(new File(home, "configuration/standalone.xml"), jboss(jbossDatasource("explicit-home-base", false)));
        ProcessLaunch explicit = ProcessLaunch.parse(new String[]{"java", "-Djboss.server.base.dir=" + home.getCanonicalPath(), "-jar", new File(home, "jboss-modules.jar").getCanonicalPath(), "org.jboss.as.standalone"}, null);
        AssessmentScan equalBase = scanner(); AssessmentScan.ProcessContext equalContext = new AssessmentScan.ProcessContext(explicit, 1, null, AssessmentScan.map(), null); equalBase.serverDataSources.discover(equalContext);
        check(equalBase.datasources.size() == 1 && "java:/explicit-home-base".equals(((Map)equalBase.datasources.get(0)).get("name")), "Explicit base equal to home must not append standalone");
        return inventory(scan);
    }
    static void privacy(AssessmentScan scan) {
        String json = Json.stringify(AssessmentScan.map("snapshot", inventory(scan), "cache", scan.serverDataSources.cache));
        check(json.indexOf("PRIVATE_") < 0 && json.indexOf("jdbc:postgresql:") < 0 && json.indexOf("jdbc:h2:") < 0, "URLs, credentials, raw XML and command arguments never retained in rows/cache/diagnostics");
    }
    static void safeParser() throws Exception {
        check(ServerDataSources.parse("<Context/>", "Tomcat", null).isEmpty(), "Empty config is empty, not stale inventory");
        check(ServerDataSources.parse("<Context><Resource name='driverOnly' type='java.lang.String' driverClassName='org.h2.Driver'/></Context>", "Tomcat", null).isEmpty(), "Driver attribute alone is not a JDBC resource");
        check(ServerDataSources.parse("<server><profile><subsystem xmlns='urn:unrelated:subsystem'><datasources>" + jbossDatasource("FalsePositive", false) + "</datasources></subsystem></profile></server>", "JBoss", null).isEmpty(), "Unrelated XML namespace must not create datasource findings");
        List<Map> unresolved = ServerDataSources.parse(jboss("<datasource jndi-name='java:/Unknown' enabled='${ENABLED}'><connection-url>${PRIVATE_URL_CANARY}</connection-url><driver>${PRIVATE_DRIVER_CANARY}</driver></datasource>"), "JBoss", null);
        check(unresolved.size() == 1 && Boolean.TRUE.equals(unresolved.get(0).get("activationUnresolved")) && unresolved.get(0).get("driverName") == null && unresolved.get(0).get("databaseType") == null, "Explicit resource declaration remains configuration evidence with unresolved values omitted");
        String[] unsafe = {
            "<!DOCTYPE Context [<!ENTITY stolen SYSTEM 'file:///PRIVATE_ENTITY_CANARY'>]><Context><Resource name='&stolen;' type='javax.sql.DataSource'/></Context>",
            "<!DOCTYPE Context SYSTEM 'https://PRIVATE_NETWORK_CANARY.invalid/resource'><Context/>",
            "<Context><Resource name='jdbc/x' type='javax.sql.DataSource'></Context>",
            "<not-a-server><datasource/></not-a-server>"
        };
        for (String xml : unsafe) {
            boolean rejected = false; try { ServerDataSources.parse(xml, "Tomcat", null); } catch (Exception expected) { rejected = true; }
            check(rejected, "DTD, external entities, malformed XML and unsupported root rejected");
        }
        StringBuilder deep = new StringBuilder("<Context>"); for (int i = 0; i < 70; i++) deep.append("<x>"); for (int i = 0; i < 70; i++) deep.append("</x>"); deep.append("</Context>");
        boolean rejected = false; try { ServerDataSources.parse(deep.toString(), "Tomcat", null); } catch (Exception expected) { rejected = true; } check(rejected, "XML depth bound enforced");
        StringBuilder many = new StringBuilder("<Context>"); for (int i = 0; i < 513; i++) many.append(tomcatResource("jdbc/" + i)); many.append("</Context>");
        rejected = false; try { ServerDataSources.parse(many.toString(), "Tomcat", null); } catch (Exception expected) { rejected = true; } check(rejected, "XML row bound enforced atomically");
    }
    static void budgets(File root) throws Exception {
        File base = new File(root, "budgets"); text(new File(base, "conf/server.xml"), "<Server><GlobalNamingResources>" + tomcatResource("jdbc/bounded") + "</GlobalNamingResources></Server>");
        AssessmentScan.ProcessContext context = context("Tomcat", base, base, 2147483000L); // no real /proc entry on Linux
        AssessmentScan bytes = scanner(); bytes.bytesRead = ArchiveMetadata.READ_LIMIT; bytes.serverDataSources.discover(context);
        check(bytes.datasources.isEmpty() && bytes.partial && bytes.bytesRead == ArchiveMetadata.READ_LIMIT, "Global byte budget enforced before config read");
        AssessmentScan files = scanner(); files.filesVisited = files.maxFiles; files.serverDataSources.discover(context);
        check(files.datasources.isEmpty() && files.partial && files.bytesRead == 0, "Global file budget prevents config read");
        AssessmentScan deadline = scanner(); java.lang.reflect.Field deadlineField = AssessmentScan.class.getDeclaredField("deadline"); deadlineField.setAccessible(true); deadlineField.setLong(deadline, 0L); deadline.serverDataSources.discover(context);
        check(deadline.datasources.isEmpty() && deadline.partial && deadline.bytesRead == 0, "Global deadline prevents config reads");
        AssessmentScan count = scanner(); for (int i = 0; i < ServerDataSources.MAX_CONFIGS; i++) count.serverDataSources.cache.put("fixture-" + i, new ArrayList<Map>()); count.serverDataSources.discover(context);
        check(count.datasources.isEmpty() && count.partial && count.bytesRead == 0, "Configuration count bounded");
        AssessmentScan rows = scanner(); for (int i = 0; i < 512; i++) rows.datasources.add(AssessmentScan.map("id", "preexisting" + i)); rows.serverDataSources.discover(context);
        check(rows.datasources.size() == 512 && rows.partial, "Global datasource row cap includes all discovery sources");
        char[] huge = new char[ServerDataSources.CONFIG_LIMIT + 10]; Arrays.fill(huge, 'x'); text(new File(base, "conf/server.xml"), new String(huge));
        AssessmentScan large = scanner(); large.serverDataSources.discover(context); check(large.datasources.isEmpty() && large.partial && large.bytesRead == ServerDataSources.CONFIG_LIMIT + 1, "Per-file bound and coverage warning");
        text(new File(base, "conf/server.xml"), "<!DOCTYPE Server [<!ENTITY secret SYSTEM 'file:///PRIVATE_LOCAL_CANARY'>]><Server>&secret;</Server>");
        AssessmentScan unsafe = scanner(); unsafe.serverDataSources.discover(context); check(unsafe.datasources.isEmpty() && unsafe.partial, "Unsafe config produces actionable coverage rather than false row"); privacy(unsafe);
        File missing = new File(root, "missing-config"); AssessmentScan absent = scanner(); absent.serverDataSources.discover(context("Tomcat", missing, missing, 1));
        check(absent.partial && absent.datasources.isEmpty(), "Missing selected server config is reported as a coverage gap");
        File duplicated = new File(base, "conf/context.xml"); text(duplicated, "<Context>" + tomcatResource("jdbc/repeated") + tomcatResource("jdbc/repeated") + "</Context>");
        AssessmentScan duplicates = scanner(); duplicates.serverDataSources.inspect(duplicated, "Tomcat", context);
        check(duplicates.datasources.size() == 1, "Duplicate declarations in the same origin and context are not double-counted");
        text(duplicated, "<Server><Context path='/foo'>" + tomcatResource("bar:baz") + "</Context><Context path='/foo:bar'>" + tomcatResource("baz") + "</Context></Server>");
        AssessmentScan tuples = scanner(); tuples.serverDataSources.inspect(duplicated, "Tomcat", context);
        check(tuples.datasources.size() == 2, "Delimiter characters in context and resource names cannot collide");
        text(duplicated, "<Server><Engine name='x/host:y'><Context path='/demo'>" + tomcatResource("jdbc/same") + "</Context></Engine><Engine name='x'><Host name='y'><Context path='/demo'>" + tomcatResource("jdbc/same") + "</Context></Host></Engine></Server>");
        AssessmentScan scopes = scanner(); scopes.serverDataSources.inspect(duplicated, "Tomcat", context);
        check(scopes.datasources.size() == 2, "Structured context ancestry prevents collisions in display labels");
        check(Json.stringify(scopes.datasources).indexOf("contextIdentity") < 0, "Internal structured dedup key is not exposed as a datasource field");
        File excludedTarget = new File(root, "excluded.xml"); text(excludedTarget, "<Context>" + tomcatResource("jdbc/secret") + "</Context>");
        File link = new File(base, "conf/linked.xml");
        if (new File("/bin/ln").isFile()) {
            check(new ProcessBuilder("/bin/ln", "-s", excludedTarget.getCanonicalPath(), link.getPath()).start().waitFor() == 0, "Create isolated symlink fixture");
            AssessmentScan excludedLink = scanner(); excludedLink.excluded.add(excludedTarget.getCanonicalPath()); excludedLink.serverDataSources.inspect(link, "Tomcat", context);
            check(excludedLink.datasources.isEmpty() && excludedLink.bytesRead == 0, "Canonical symlink target cannot bypass excludes");
        }
    }
    static void boundedCache(File root) throws Exception {
        File base = new File(root, "cache-budget"); AssessmentScan.ProcessContext context = context("Tomcat", base, base, 2147483000L); // no real /proc entry on Linux
        AssessmentScan scan = scanner();
        StringBuilder config = new StringBuilder("<Context>");
        for (int i = 0; i < 256; i++) config.append("<Resource type='javax.sql.DataSource' name='jdbc/cache" + i + "'/>");
        config.append("</Context>");
        for (int i = 0; i < 16; i++) {
            File file = new File(base, "context" + i + ".xml"); text(file, config.toString()); scan.serverDataSources.inspect(file, "Tomcat", context);
        }
        check(scan.datasources.size() == 512 && scan.serverDataSources.cachedRows == 512 && scan.partial, "Aggregate sanitized cache obeys global 512 row cap");
        check(scan.serverDataSources.cache.size() == 2 && scan.bytesRead < 50000, "Full row budget stops new config I/O while retaining cached associations");
        AssessmentScan.ProcessContext second = context("Tomcat", base, base, 2); scan.serverDataSources.inspect(new File(base, "context0.xml"), "Tomcat", second);
        check(((List)((Map)scan.datasources.get(0)).get("associations")).size() == 2, "Existing cached rows can add association after budget reached");
        StringBuilder nested = new StringBuilder("<Server>");
        char[] largeName = new char[180]; Arrays.fill(largeName, 'x');
        for (int i = 0; i < 55; i++) nested.append("<Context path='/" + new String(largeName) + "'>");
        for (int i = 0; i < 400; i++) nested.append("<Resource type='javax.sql.DataSource' name='jdbc/" + i + "'/>");
        for (int i = 0; i < 55; i++) nested.append("</Context>"); nested.append("</Server>");
        AssessmentScan bounded = scanner();
        for (int i = 0; i < 16; i++) { File file = new File(base, "nested" + i + ".xml"); text(file, nested.toString()); bounded.serverDataSources.inspect(file, "Tomcat", context); }
        check(bounded.datasources.isEmpty() && bounded.serverDataSources.cachedRows == 0 && bounded.partial, "Large repeated context scopes cannot amplify small XML into an unbounded cache");
    }
    static Map row(AssessmentScan scan, String name) {
        Map found = null;
        for (Object value : scan.datasources) if (name.equals(((Map)value).get("name"))) { check(found == null, "Single row named " + name); found = (Map)value; }
        check(found != null, "Datasource discovered: " + name); return found;
    }
    static void endpoint(Map row, String host, Integer port, String database) {
        check(host.equals(row.get("databaseHost")), "Host for " + row.get("name") + ": " + row.get("databaseHost"));
        check(port == null ? row.get("databasePort") == null : port.equals(row.get("databasePort")), "Port for " + row.get("name"));
        check(database == null ? row.get("databaseName") == null : database.equals(row.get("databaseName")), "Database for " + row.get("name"));
    }
    static void strict(AssessmentScan scan) {
        String json = Json.stringify(AssessmentScan.map("snapshot", inventory(scan), "cache", scan.serverDataSources.cache));
        check(json.indexOf("PRIVATE_") < 0 && json.indexOf("jdbc:") < 0, "No credentials, environment values or raw JDBC URLs retained");
    }
    static void environ(File proc, String pid, String... pairs) throws Exception {
        StringBuilder text = new StringBuilder(); for (String pair : pairs) text.append(pair).append('\u0000');
        text(new File(proc, pid + "/environ"), text.toString());
    }
    static String pool(String name, String attributes) { return "<Resource name='" + name + "' username='PRIVATE_USER_CANARY' password='PRIVATE_PASSWORD_CANARY' " + attributes + "/>"; }
    static void tomcatPools(File root) throws Exception {
        File base = new File(root, "tomcat-pools"), proc = new File(root, "tomcat-pools-proc");
        text(new File(base, "conf/server.xml"), "<Server><GlobalNamingResources>" +
            pool("jdbc/hikari", "type='com.zaxxer.hikari.HikariDataSource' factory='com.zaxxer.hikari.HikariJNDIFactory' jdbcUrl='jdbc:postgresql://hikari.example:5432/app?password=PRIVATE_QUERY_CANARY' driverClassName='org.postgresql.Driver' maximumPoolSize='15'") +
            pool("jdbc/ucp", "type='oracle.ucp.jdbc.PoolDataSource' factory='oracle.ucp.jdbc.PoolDataSourceImpl' URL='jdbc:oracle:thin:PRIVATE_USER_CANARY/PRIVATE_PASSWORD_CANARY@//ucp.example:1521/UCPSVC' connectionFactoryClassName='oracle.jdbc.pool.OracleDataSource' maxPoolSize='30'") +
            pool("jdbc/tomcatjdbc", "type='javax.sql.DataSource' factory='org.apache.tomcat.jdbc.pool.DataSourceFactory' url='jdbc:mysql://${db.host}:${db.port:3306}/shop' maxActive='9'") +
            pool("jdbc/dbcp", "type='org.apache.tomcat.dbcp.dbcp2.BasicDataSource' factory='org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory' url='jdbc:jtds:sqlserver://dbcp.example:1433/legacy;password=PRIVATE_QUERY_CANARY'") +
            pool("jdbc/secret", "type='javax.sql.DataSource' url='jdbc:mysql://${db.password}/x'") +
            pool("jdbc/custom", "type='com.example.CustomBean' factory='com.example.CustomFactory' url='jdbc:postgresql://custom.example/x'") +
            "</GlobalNamingResources><Service><Engine name='Catalina'><Host name='localhost' appBase='${catalina.base}/apps'/></Engine></Service></Server>");
        Map<String,byte[]> war = ProcessLaunchTest.entries();
        war.put("META-INF/context.xml", ("<Context><ResourceLink name='jdbc/local' global='jdbc/hikari' type='javax.sql.DataSource'/>" + pool("jdbc/warlocal", "type='javax.sql.DataSource' url='jdbc:postgresql://war-db.example:5432/war'") + "</Context>").getBytes("UTF-8"));
        ProcessLaunchTest.write(new File(base, "apps/shop.war"), ProcessLaunchTest.jar(ProcessLaunchTest.attrs(), war));
        text(new File(base, "apps/exploded/META-INF/context.xml"), "<Context><ResourceLink name='jdbc/ora' global='jdbc/ucp' type='javax.sql.DataSource'/></Context>");
        text(new File(base, "webapps/unused/META-INF/context.xml"), "<Context>" + pool("jdbc/notDeployed", "type='javax.sql.DataSource' url='jdbc:postgresql://unused.example/x'") + "</Context>");
        ProcessLaunchTest.process(proc, "98667001", "java", "-Dcatalina.base=" + base.getCanonicalPath(), "-Ddb.host=tomcat-db.example", "-Ddb.password=PRIVATE_PASSWORD_CANARY", "org.apache.catalina.startup.Bootstrap", "start");
        AssessmentScan scan = scanner(); scan.serverDataSources.procRoot = proc; scan.discoverProcesses(proc);
        check(scan.datasources.size() == 6, "Hikari, UCP, tomcat-jdbc, DBCP, unresolved and WAR resources found; unknown factory and unused appBase ignored: " + scan.datasources.size());
        Map hikari = row(scan, "jdbc/hikari"), ucp = row(scan, "jdbc/ucp"), tomcatJdbc = row(scan, "jdbc/tomcatjdbc"), dbcp = row(scan, "jdbc/dbcp"), local = row(scan, "jdbc/warlocal");
        endpoint(hikari, "hikari.example", Integer.valueOf(5432), "app"); check(Integer.valueOf(15).equals(hikari.get("configuredMaxPoolSize")) && "PostgreSQL".equals(hikari.get("databaseType")) && "com.zaxxer.hikari.HikariDataSource".equals(hikari.get("resourceType")), "Hikari jdbcUrl and maximumPoolSize");
        endpoint(ucp, "ucp.example", Integer.valueOf(1521), "UCPSVC"); check(Integer.valueOf(30).equals(ucp.get("configuredMaxPoolSize")) && "Oracle".equals(ucp.get("databaseType")), "UCP URL attribute and maxPoolSize");
        endpoint(tomcatJdbc, "tomcat-db.example", Integer.valueOf(3306), "shop");
        check("configured-resolved".equals(tomcatJdbc.get("databaseEndpointEvidence")) && ((List)tomcatJdbc.get("resolvedFrom")).containsAll(Arrays.asList("system-property", "default")), "Embedded -D and default placeholders marked as resolved");
        endpoint(dbcp, "dbcp.example", Integer.valueOf(1433), "legacy"); check("SQL Server".equals(dbcp.get("databaseType")), "jTDS URL type");
        check(row(scan, "jdbc/secret").get("databaseHost") == null, "Secret-like placeholder keys are never resolved");
        endpoint(local, "war-db.example", Integer.valueOf(5432), "war"); check(String.valueOf(local.get("sourcePath")).endsWith("shop.war!/META-INF/context.xml"), "Unexpanded WAR context entry read in place");
        check(Json.stringify(hikari.get("linkedContexts")).contains("context:/shop") && Json.stringify(ucp.get("linkedContexts")).contains("context:/exploded"), "ResourceLink associates application contexts with global resources");
        check(scan.bytesRead < 64 * 1024, "Bounded reads for pool fixtures");
        strict(scan);
    }
    static String subsystem(String body) { return "<subsystem xmlns='urn:jboss:domain:datasources:7.1'><datasources>" + body + "</datasources></subsystem>"; }
    static Map<String,byte[]> driverEntries(String name) throws Exception {
        Map<String,byte[]> entries = ProcessLaunchTest.entries();
        entries.put(name.replace('.', '/') + ".class", "fixture".getBytes("UTF-8"));
        entries.put("META-INF/services/java.sql.Driver", (name + "\n").getBytes("UTF-8"));
        return entries;
    }
    static void module(File modules, String name, String jar, String version) throws Exception {
        File directory = new File(modules, "system/layers/base/" + name.replace('.', '/') + "/main");
        text(new File(directory, "module.xml"), "<module xmlns='urn:jboss:module:1.9' name='" + name + "'><resources><resource-root path='" + jar + "'/></resources></module>");
        ProcessLaunchTest.write(new File(directory, jar), ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Implementation-Title", "JDBC driver", "Implementation-Version", version), driverEntries("oracle.jdbc.xa.client.OracleXADataSource")));
    }
    static void wildflyExpressions(File root) throws Exception {
        File home = new File(root, "wf-expr"), base = new File(home, "standalone"), proc = new File(root, "wf-expr-proc");
        module(new File(home, "modules"), "com.oracle", "ojdbc8.jar", "19.3.0.0");
        ProcessLaunchTest.write(new File(base, "deployments/postgresql-42.7.4.jar"), ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Implementation-Title", "PostgreSQL JDBC Driver", "Implementation-Version", "42.7.4"), driverEntries("org.postgresql.Driver")));
        text(new File(base, "configuration/standalone.xml"), "<server xmlns='urn:jboss:domain:20.0'><profile>" + subsystem(
            "<datasource jndi-name='java:/EnvDS' pool-name='EnvDS'><connection-url>${env.DB_URL:jdbc:oracle:thin:@fallback.example:1521/FALLBACK}</connection-url><driver>oracle</driver><security><password>PRIVATE_PASSWORD_CANARY</password></security></datasource>" +
            "<datasource jndi-name='java:/EmbeddedDS' pool-name='EmbeddedDS'><connection-url>jdbc:postgresql://${db.host:localhost}:${db.port:5432}/${env.DB_NAME}</connection-url><driver>postgresql-42.7.4.jar_org.postgresql.Driver_42_7</driver></datasource>" +
            "<datasource jndi-name='java:/SecretDS' pool-name='SecretDS'><connection-url>jdbc:postgresql://${db.password}/x</connection-url></datasource>" +
            "<datasource jndi-name='java:/DefaultDS' pool-name='DefaultDS'><connection-url>${unset.url:jdbc:postgresql://default.example/defdb}</connection-url></datasource>" +
            "<xa-datasource jndi-name='java:/PgXA' pool-name='PgXA'><xa-datasource-property name='ServerName'>${env.PG_HOST}</xa-datasource-property><xa-datasource-property name='PortNumber'>5433</xa-datasource-property><xa-datasource-property name='DatabaseName'>orders</xa-datasource-property><xa-datasource-property name='Password'>PRIVATE_PASSWORD_CANARY</xa-datasource-property><driver>pg</driver></xa-datasource>" +
            "<xa-datasource jndi-name='java:/OraXA' pool-name='OraXA'><xa-datasource-property name='Url'>jdbc:oracle:thin:@//oraxa.example:1521/XASVC</xa-datasource-property><driver>oracle</driver></xa-datasource>" +
            "<drivers><driver name='oracle' module='com.oracle'><xa-datasource-class>oracle.jdbc.xa.client.OracleXADataSource</xa-datasource-class></driver><driver name='pg' module='org.postgresql'><xa-datasource-class>org.postgresql.xa.PGXADataSource</xa-datasource-class></driver></drivers>") + "</profile></server>");
        String[] command = {"java", "-jar", new File(home, "jboss-modules.jar").getCanonicalPath(), "-mp", new File(home, "modules").getCanonicalPath(), "org.jboss.as.standalone", "-Djboss.home.dir=" + home.getCanonicalPath(), "-Ddb.host=embedded.example", "-Ddb.password=PRIVATE_PASSWORD_CANARY"};
        ProcessLaunchTest.process(proc, "98667101", command); ProcessLaunchTest.process(proc, "98667102", command);
        environ(proc, "98667101", "DB_URL=jdbc:oracle:thin:PRIVATE_USER_CANARY/PRIVATE_PASSWORD_CANARY@env-one.example:1521/ENVSVC", "DB_NAME=inventory", "PG_HOST=pg-xa.example", "DB_PASSWORD=PRIVATE_PASSWORD_CANARY");
        environ(proc, "98667102", "DB_URL=jdbc:oracle:thin:@//env-two.example:1522/ENVSVC", "DB_NAME=inventory", "PG_HOST=pg-xa.example");
        AssessmentScan scan = scanner(); scan.serverDataSources.procRoot = proc; scan.discoverProcesses(proc);
        int envRows = 0;
        for (Object value : scan.datasources) if ("java:/EnvDS".equals(((Map)value).get("name"))) {
            Map env = (Map)value; envRows++;
            check(Arrays.asList("env-one.example", "env-two.example").contains(env.get("databaseHost")) && "ENVSVC".equals(env.get("databaseName")), "env.DB_URL resolved per process");
            check(Arrays.asList("environment").equals(env.get("resolvedFrom")) && "configured-resolved".equals(env.get("databaseEndpointEvidence")), "Environment source recorded");
            check(String.valueOf(env.get("driverArtifact")).endsWith("com/oracle/main/ojdbc8.jar") && "19.3.0.0".equals(env.get("driverVersion")), "Module resource-root JAR is the driver candidate");
            check(((List)env.get("associations")).size() == 1, "Different resolved destinations are not merged");
        }
        check(envRows == 2, "Each process keeps its own resolved destination");
        Map embedded = row(scan, "java:/EmbeddedDS"), xa = row(scan, "java:/PgXA"), ora = row(scan, "java:/OraXA"), fallback = row(scan, "java:/DefaultDS");
        endpoint(embedded, "embedded.example", Integer.valueOf(5432), "inventory");
        check(((List)embedded.get("resolvedFrom")).containsAll(Arrays.asList("system-property", "default", "environment")), "Embedded placeholders resolved from -D, default and environment");
        check(String.valueOf(embedded.get("driverArtifact")).endsWith("deployments/postgresql-42.7.4.jar") && "42.7.4".equals(embedded.get("driverVersion")), "Deployment driver JAR is the driver candidate");
        check(row(scan, "java:/SecretDS").get("databaseHost") == null, "Secret-like system property never resolved");
        endpoint(fallback, "default.example", null, "defdb"); check(Arrays.asList("default").equals(fallback.get("resolvedFrom")) && ((List)fallback.get("associations")).size() == 2, "Default-only resolution is shared by identical processes");
        endpoint(xa, "pg-xa.example", Integer.valueOf(5433), "orders"); check("org.postgresql.xa.PGXADataSource".equals(xa.get("driverName")), "XA ServerName/PortNumber/DatabaseName properties");
        endpoint(ora, "oraxa.example", Integer.valueOf(1521), "XASVC"); check("Oracle".equals(ora.get("databaseType")), "XA Url property with mixed-case name");
        boolean library = false; for (Object value : scan.libraries) if (String.valueOf(((Map)value).get("path")).endsWith("ojdbc8.jar")) library = true;
        check(library, "Module driver JAR is inventoried as a process-linked JDBC library");
        strict(scan);
    }
    static String domainXml() {
        return "<domain xmlns='urn:jboss:domain:20.0'><profiles><profile name='default'>" + subsystem("<datasource jndi-name='java:/DefaultProfileDS' pool-name='A'><connection-url>jdbc:postgresql://default-profile.example/a</connection-url></datasource>") +
            "</profile><profile name='full'>" + subsystem("<datasource jndi-name='java:/FullDS' pool-name='B'><connection-url>jdbc:postgresql://full-profile.example/b?password=PRIVATE_QUERY_CANARY</connection-url></datasource><datasource jndi-name='java:/DefaultProfileDS' pool-name='A'><connection-url>jdbc:postgresql://other.example/a</connection-url></datasource>") +
            "</profile></profiles><server-groups><server-group name='main-server-group' profile='full'/><server-group name='other-server-group' profile='default'/></server-groups></domain>";
    }
    static AssessmentScan domainScan(File proc, String pid, String... command) throws Exception {
        ProcessLaunchTest.process(proc, pid, command);
        AssessmentScan scan = scanner(); scan.serverDataSources.procRoot = proc; scan.discoverProcesses(proc); return scan;
    }
    static void wildflyDomain(File root) throws Exception {
        File home = new File(root, "wf-domain");
        text(new File(home, "domain/configuration/domain.xml"), domainXml());
        text(new File(home, "domain/configuration/host.xml"), "<host xmlns='urn:jboss:domain:20.0' name='primary'><servers><server name='server-one' group='main-server-group'/><server name='server-two' group='other-server-group'/></servers></host>");
        String modules = new File(home, "jboss-modules.jar").getCanonicalPath(), dir = home.getCanonicalPath();
        AssessmentScan managed = domainScan(new File(root, "domain-proc-1"), "98667201", "java", "-D[Server:server-one]", "-Djboss.home.dir=" + dir, "-Djboss.server.log.dir=" + dir + "/domain/servers/server-one/log", "-jar", modules, "-mp", dir + "/modules", "org.jboss.as.server");
        check(managed.datasources.size() == 2, "Managed server inventories only its server-group profile: " + managed.datasources.size());
        Map full = row(managed, "java:/FullDS");
        check("domain profile full".equals(full.get("configurationContext")) && "domain-profile".equals(full.get("configurationScope")), "Domain profile context recorded");
        endpoint(full, "full-profile.example", null, "b");
        endpoint(row(managed, "java:/DefaultProfileDS"), "other.example", null, "a");
        AssessmentScan derived = domainScan(new File(root, "domain-proc-2"), "98667202", "java", "-Djboss.home.dir=" + dir, "-Djboss.server.data.dir=" + dir + "/domain/servers/server-two/data", "-jar", modules, "org.jboss.as.server");
        check(derived.datasources.size() == 1 && "default-profile.example".equals(((Map)derived.datasources.get(0)).get("databaseHost")), "Server name derived from managed server directories");
        AssessmentScan controller = domainScan(new File(root, "domain-proc-3"), "98667203", "java", "-D[Host Controller]", "-Djboss.home.dir=" + dir, "-jar", modules, "org.jboss.as.host-controller");
        check(controller.datasources.size() == 3, "Host controller inventories all domain profiles");
        AssessmentScan unknown = domainScan(new File(root, "domain-proc-4"), "98667204", "java", "-D[Server:unknown]", "-Djboss.home.dir=" + dir, "-jar", modules, "org.jboss.as.server");
        check(unknown.datasources.size() == 3 && Json.stringify(unknown.warnings).contains("profile was not resolved"), "Unresolved server profile inventories all profiles with a note");
        // Real EAP domain.xml files with several profiles exceed 64 KiB; the server-group still resolves within the configuration limit.
        File large = new File(root, "wf-domain-large"); StringBuilder padding = new StringBuilder("<!--");
        while (padding.length() < 80 * 1024) padding.append(" unused profile configuration padding ");
        text(new File(large, "domain/configuration/domain.xml"), domainXml().replace("</profiles>", "</profiles>" + padding + "-->"));
        text(new File(large, "domain/configuration/host.xml"), "<host xmlns='urn:jboss:domain:20.0' name='primary'><servers><server name='server-one' group='main-server-group'/></servers></host>");
        String largeDir = large.getCanonicalPath();
        AssessmentScan big = domainScan(new File(root, "domain-proc-5"), "98667205", "java", "-D[Server:server-one]", "-Djboss.home.dir=" + largeDir, "-jar", new File(large, "jboss-modules.jar").getCanonicalPath(), "org.jboss.as.server");
        check(big.datasources.size() == 2 && !Json.stringify(big.warnings).contains("rejected") && !Json.stringify(big.warnings).contains("profile was not resolved"), "80 KiB domain.xml still resolves the managed server profile: " + big.warnings);
        for (AssessmentScan scan : new AssessmentScan[]{managed, derived, controller, unknown}) { strict(scan); check(!Json.stringify(scan.warnings).contains("domain and legacy"), "Unsupported-domain note removed"); }
    }
    static void legacyJBoss(File root) throws Exception {
        File home = new File(root, "jboss5"), proc = new File(root, "jboss5-proc");
        text(new File(home, "bin/run.jar"), "launcher fixture");
        text(new File(home, "server/production/deploy/oracle-ds.xml"), "<datasources><local-tx-datasource><jndi-name>LegacyOracleDS</jndi-name><connection-url>jdbc:oracle:thin:@legacy-db.example:1521:LEGSID</connection-url><driver-class>oracle.jdbc.driver.OracleDriver</driver-class><user-name>PRIVATE_USER_CANARY</user-name><password>PRIVATE_PASSWORD_CANARY</password><max-pool-size>40</max-pool-size></local-tx-datasource>" +
            "<xa-datasource><jndi-name>LegacyXA</jndi-name><xa-datasource-class>oracle.jdbc.xa.client.OracleXADataSource</xa-datasource-class><xa-datasource-property name='URL'>jdbc:oracle:thin:@legacy-xa.example:1521:XASID</xa-datasource-property><xa-datasource-property name='Password'>PRIVATE_PASSWORD_CANARY</xa-datasource-property></xa-datasource>" +
            "<no-tx-datasource><jndi-name>NoTx</jndi-name><connection-url>jdbc:mysql://legacy-my.example/app</connection-url><max-pool-size>5</max-pool-size></no-tx-datasource></datasources>");
        text(new File(home, "server/production/deploy/messaging/jms-ds.xml"), "<connection-factories><tx-connection-factory><jndi-name>JmsXA</jndi-name></tx-connection-factory></connection-factories>");
        text(new File(home, "server/default/deploy/other-ds.xml"), "<datasources><local-tx-datasource><jndi-name>WrongConfig</jndi-name><connection-url>jdbc:mysql://wrong.example/x</connection-url></local-tx-datasource></datasources>");
        ProcessLaunchTest.process(proc, "98667301", "java", "-Dprogram.name=run.sh", "-classpath", new File(home, "bin/run.jar").getCanonicalPath(), "org.jboss.Main", "-c", "production", "-b", "0.0.0.0");
        AssessmentScan scan = scanner(); scan.serverDataSources.procRoot = proc; scan.discoverProcesses(proc);
        check(scan.datasources.size() == 3, "Legacy *-ds.xml of the selected configuration only: " + scan.datasources.size());
        Map oracle = row(scan, "LegacyOracleDS"), xa = row(scan, "LegacyXA"), notx = row(scan, "NoTx");
        endpoint(oracle, "legacy-db.example", Integer.valueOf(1521), "LEGSID"); check(Integer.valueOf(40).equals(oracle.get("configuredMaxPoolSize")) && "oracle.jdbc.driver.OracleDriver".equals(oracle.get("driverName")) && "JBoss deploy".equals(oracle.get("configurationContext")), "Legacy local-tx fields");
        endpoint(xa, "legacy-xa.example", Integer.valueOf(1521), "XASID"); check("javax.sql.XADataSource".equals(xa.get("resourceType")) && "oracle.jdbc.xa.client.OracleXADataSource".equals(xa.get("driverName")), "Legacy XA property URL and class");
        endpoint(notx, "legacy-my.example", null, "app"); check("MySQL".equals(notx.get("databaseType")) && Integer.valueOf(5).equals(notx.get("configuredMaxPoolSize")), "Legacy no-tx datasource");
        check(!Json.stringify(scan.warnings).contains("rejected"), "Non-datasource *-ds.xml roots are not reported as malformed");
        strict(scan);
    }
    public static void main(String[] args) throws Exception {
        File root = CollectorTest.temp("server-datasources").getCanonicalFile(); Map export;
        try { export = AssessmentScan.map("tomcat", tomcat(root), "jboss", wildfly(root)); safeParser(); budgets(root); boundedCache(root); tomcatPools(root); wildflyExpressions(root); wildflyDomain(root); legacyJBoss(root); }
        finally { CollectorTest.remove(root); }
        if (args.length == 2 && args[0].equals("--export")) { File target = new File(args[1]); if (target.getParentFile() != null) target.getParentFile().mkdirs(); text(target, Json.stringify(export)); }
        System.out.println("SERVER_DATASOURCE_TESTS=PASS checks=" + checks + " Tomcat JBoss selected-config namespaces XA disabled privacy XXE limits excludes associations pools links WAR appBase expressions modules domain legacy");
    }
}
