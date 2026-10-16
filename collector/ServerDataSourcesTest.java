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
        text(new File(base, "conf/context.xml"), "<Context>" + tomcatResource("jdbc/default") + "</Context>");
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
        AssessmentScan.ProcessContext context = context("Tomcat", base, base, 1);
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
        File base = new File(root, "cache-budget"); AssessmentScan.ProcessContext context = context("Tomcat", base, base, 1);
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
    public static void main(String[] args) throws Exception {
        File root = CollectorTest.temp("server-datasources").getCanonicalFile(); Map export;
        try { export = AssessmentScan.map("tomcat", tomcat(root), "jboss", wildfly(root)); safeParser(); budgets(root); boundedCache(root); }
        finally { CollectorTest.remove(root); }
        if (args.length == 2 && args[0].equals("--export")) { File target = new File(args[1]); if (target.getParentFile() != null) target.getParentFile().mkdirs(); text(target, Json.stringify(export)); }
        System.out.println("SERVER_DATASOURCE_TESTS=PASS checks=" + checks + " Tomcat JBoss selected-config namespaces XA disabled privacy XXE limits excludes associations");
    }
}
