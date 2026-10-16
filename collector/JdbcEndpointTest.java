package assessment;

import java.io.*;
import java.util.*;

public final class JdbcEndpointTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void endpoint(String url, String host, Integer port, String name) {
        Map parsed = JdbcEndpoint.parse(url);
        check(host.equals(parsed.get("databaseHost")), "Host: " + host);
        check(port == null ? parsed.get("databasePort") == null : port.equals(parsed.get("databasePort")), "Port");
        check(name == null ? parsed.get("databaseName") == null : name.equals(parsed.get("databaseName")), "Database name");
        check(Json.stringify(parsed).indexOf("SECRET") < 0 && !Json.stringify(parsed).contains("jdbc:"), "No credential or raw URL persisted");
    }
    static String xml(String name, String url) { return "<jdbc-data-source xmlns='http://xmlns.oracle.com/weblogic/jdbc-data-source'><name>" + name + "</name><jdbc-driver-params><url>" + url + "</url><driver-name>oracle.jdbc.OracleDriver</driver-name><password-encrypted>SECRET_ENCRYPTED</password-encrypted></jdbc-driver-params><jdbc-data-source-params><jndi-name>jdbc/" + name + "</jndi-name></jdbc-data-source-params></jdbc-data-source>"; }
    static void domain(File base, String host) throws Exception {
        ProcessLaunchTest.text(new File(base, "config/config.xml"), "<domain xmlns='http://xmlns.oracle.com/weblogic/domain'><jdbc-system-resource><name>Orders</name><descriptor-file-name>jdbc/orders.xml</descriptor-file-name><target>AdminServer</target></jdbc-system-resource></domain>");
        ProcessLaunchTest.text(new File(base, "config/jdbc/orders.xml"), xml("Orders", "jdbc:oracle:thin:SECRET_USER/SECRET_PASSWORD@//" + host + ":1521/ORDERS"));
    }
    static void domains() throws Exception {
        File root = CollectorTest.temp("jdbc-endpoints");
        try {
            File one = new File(root, "domains/one"), two = new File(root, "custom-location/two"), stopped = new File(root, "domains/stopped"), proc = new File(root, "proc");
            domain(one, "db-one.local"); domain(two, "db-two.local"); domain(stopped, "db-stopped.local");
            ProcessLaunchTest.text(new File(one, "config/jdbc/extra.xml"), xml("Extra", "jdbc:oracle:thin:@//db-extra.local:1522/EXTRA"));
            ProcessLaunchTest.process(proc, "98700001", "java", "-Dweblogic.RootDirectory=" + one.getCanonicalPath(), "-Dweblogic.Name=AdminServer", "weblogic.Server");
            ProcessLaunchTest.process(proc, "98700002", "java", "-Dweblogic.RootDirectory=" + one.getCanonicalPath(), "-Dweblogic.Name=ManagedServer", "weblogic.Server");
            ProcessLaunchTest.process(proc, "98700003", "java", "-Dweblogic.RootDirectory=" + two.getCanonicalPath(), "-Dweblogic.Name=AdminServer", "weblogic.Server");
            AssessmentScan scan = ProcessLaunchTest.scanner(); scan.discoverProcesses(proc);
            check(scan.datasources.size() == 3, "Two running domains, shared process deduplicated, unreferenced descriptor retained");
            check(scan.servers.size() == 3, "Same server name in distinct running domains is not merged");
            String json = Json.stringify(scan.datasources);
            check(!json.contains("db-stopped") && !json.contains("SECRET") && !json.contains("jdbc:oracle:"), "Stopped domain and secrets excluded");
            for (Object object : scan.datasources) {
                Map row = (Map)object;
                check(row.get("domainName") != null && row.get("domainPath") != null, "Domain identity");
                if ("one".equals(row.get("domainName"))) check(((List)row.get("associations")).size() == 2, "Two server associations one datasource");
                for (Object association : (List)row.get("associations")) check(((Map)association).get("serverId") != null, "Explicit server identity retained");
                if ("Extra".equals(row.get("name"))) check("domain-unreferenced-module".equals(row.get("configurationScope")), "Unreferenced does not claim deployment");
            }
            AssessmentScan nested = ProcessLaunchTest.scanner();
            ProcessLaunch launch = ProcessLaunch.parse(new String[]{"java", "weblogic.Server"}, new File(one, "servers/AdminServer"));
            nested.serverDataSources.discover(new AssessmentScan.ProcessContext(launch, 77, null, AssessmentScan.map(), null));
            check(nested.datasources.size() == 2, "Resolve domain ancestor from running process cwd");
            AssessmentScan excluded = ProcessLaunchTest.scanner(); excluded.excluded.add(one.getCanonicalPath()); excluded.discoverProcesses(proc);
            check(excluded.datasources.size() == 1, "Explicit exclusions retained");
            File linked = new File(root, "linked-domain");
            ProcessLaunchTest.text(new File(linked, "config/config.xml"), "<domain xmlns='http://xmlns.oracle.com/weblogic/domain'/>");
            if (new File("/bin/ln").isFile()) {
                check(new ProcessBuilder("/bin/ln", "-s", new File(stopped, "config/jdbc").getCanonicalPath(), new File(linked, "config/jdbc").getPath()).start().waitFor() == 0, "Symlink fixture");
                AssessmentScan noEscape = ProcessLaunchTest.scanner();
                ProcessLaunch linkedLaunch = ProcessLaunch.parse(new String[]{"java", "-Dweblogic.RootDirectory=" + linked.getCanonicalPath(), "weblogic.Server"}, null);
                noEscape.serverDataSources.discover(new AssessmentScan.ProcessContext(linkedLaunch, 78, null, AssessmentScan.map(), null));
                check(noEscape.datasources.isEmpty(), "Active domain must not traverse linked JDBC directory into stopped domain");
            }
        } finally { CollectorTest.remove(root); }
    }
    public static void main(String[] args) throws Exception {
        endpoint("jdbc:oracle:thin:SECRET_USER/SECRET_PASS@//Db.Local:1521/ORDERS?password=SECRET_QUERY", "db.local", Integer.valueOf(1521), "ORDERS");
        endpoint("jdbc:oracle:thin:@db.local:1521:ORCL", "db.local", Integer.valueOf(1521), "ORCL");
        endpoint("jdbc:oracle:thin:@[2001:db8::1]:1521", "2001:db8::1", Integer.valueOf(1521), null);
        endpoint("jdbc:postgresql://SECRET_USER:SECRET_PASS@[2001:db8::1]:5432/orders?password=SECRET_QUERY", "2001:db8::1", Integer.valueOf(5432), "orders");
        endpoint("jdbc:mysql://db.local/orders?user=SECRET_USER&password=SECRET_PASS", "db.local", null, "orders");
        endpoint("jdbc:sqlserver://db.local\\INSTANCE:1433;databaseName=Orders;user=SECRET_USER;password=SECRET_PASS", "db.local", Integer.valueOf(1433), "Orders");
        endpoint("jdbc:sqlserver://db.local;databaseName=Orders;password={abc;databaseName=SECRET;xyz}", "db.local", null, null);
        endpoint("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db.local)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=ORDERS)))", "db.local", Integer.valueOf(1521), "ORDERS");
        Map rac = JdbcEndpoint.parse("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=rac1.local)(PORT=1521))(ADDRESS=(PROTOCOL=TCPS)(HOST=rac2.local)(PORT=1522)))(CONNECT_DATA=(SERVICE_NAME=RACDB)))");
        check(((List)rac.get("databaseEndpoints")).size() == 2 && "RACDB".equals(rac.get("databaseName")), "Oracle RAC endpoints retained");
        check(((List)JdbcEndpoint.parse("jdbc:postgresql://pg1:5432,pg2:5433/orders").get("databaseEndpoints")).size() == 2, "Multiple PostgreSQL endpoints");
        for (String invalid : new String[]{"jdbc:oracle:thin:@MY_TNS_ALIAS", "jdbc:h2:mem:SECRET_DB", "${DATABASE_URL}", "jdbc:postgresql://host:99999/db", "jdbc:postgresql://host/db\nSECRET", "jdbc:postgresql://host%40secret/db", "jdbc:unknown://host/db", "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=x)))"}) check(JdbcEndpoint.parse(invalid).isEmpty(), "Unresolved, local, unsupported or malformed URL omitted");
        Map spring = SpringDataSource.parse("application.properties", "spring.datasource.url=jdbc:postgresql://spring.local:5432/app?password=SECRET\n".getBytes("UTF-8")).fields;
        check("spring.local".equals(spring.get("databaseHost")), "Packaged Spring parser retains safe destination");
        domains();
        System.out.println("JDBC_ENDPOINT_TESTS=PASS checks=" + checks);
    }
}
