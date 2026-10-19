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
    static void schemes() {
        String[][] cases = {
            {"jdbc:jtds:sqlserver://jtds.example:1433/legacy;user=SECRET_USER;password=SECRET_PASS", "jtds.example", "1433", "legacy", "SQL Server"},
            {"jdbc:jtds:sybase://syb.example:5000/pubs;password=SECRET_PASS", "syb.example", "5000", "pubs", "Sybase"},
            {"jdbc:sybase:Tds:tds.example:4100/pubs2?user=SECRET_USER&password=SECRET_PASS", "tds.example", "4100", "pubs2", "Sybase"},
            {"jdbc:informix-sqli://ifx.example:9088/stores:INFORMIXSERVER=ol_ifx;user=SECRET_USER;password=SECRET_PASS", "ifx.example", "9088", "stores", "Informix"},
            {"jdbc:as400://as400.example/LIB;password=SECRET_PASS", "as400.example", null, "LIB", "DB2 for i"},
            {"jdbc:teradata://td.example/DATABASE=sales,DBS_PORT=1025,USER=SECRET_USER,PASSWORD=SECRET_PASS", "td.example", "1025", "sales", "Teradata"},
            {"jdbc:sap://hana.example:30015/?databaseName=HDB&password=SECRET_PASS", "hana.example", "30015", "HDB", "SAP HANA"},
            {"jdbc:snowflake://acct.snowflakecomputing.com:443/?db=ANALYTICS&password=SECRET_PASS", "acct.snowflakecomputing.com", "443", "ANALYTICS", "Snowflake"}};
        for (String[] c : cases) {
            endpoint(c[0], c[1], c[2] == null ? null : Integer.valueOf(c[2]), c[3]);
            check(c[4].equals(JdbcEndpoint.databaseType(c[0])), "Database type for " + c[4]);
        }
        Map ldap = JdbcEndpoint.parse("jdbc:oracle:thin:@ldap://oid.example:389/orcl,cn=OracleContext,dc=example");
        check("directory-naming".equals(ldap.get("databaseEndpointEvidence")) && ldap.get("databaseHost") == null && !Json.stringify(ldap).contains("oid.example"), "LDAP naming is not a database endpoint");
    }
    static void resolver(File root) throws Exception {
        File proc = new File(root, "resolver-proc"), tns = new File(root, "tns-admin"), url = new File(root, "url-tns"), home = new File(root, "oracle-home");
        String descriptor = "(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=tns-db.example)(PORT=1525))(CONNECT_DATA=(SERVICE_NAME=ORDERSVC)))";
        ProcessLaunchTest.text(new File(tns, "tnsnames.ora"), "# SECRET comment\nIFILE = /nonexistent/other.ora\nORDERS.WORLD, ORDERS =\n  " + descriptor + "\nOTHER = (DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=other.example)(PORT=1521))(CONNECT_DATA=(SID=OTH)))\n");
        ProcessLaunchTest.text(new File(url, "tnsnames.ora"), "RAC = (DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=rac-a.example)(PORT=1521))(ADDRESS=(PROTOCOL=TCP)(HOST=rac-b.example)(PORT=1521)))(CONNECT_DATA=(SERVICE_NAME=RACSVC)))\n");
        ProcessLaunchTest.text(new File(home, "network/admin/tnsnames.ora"), "HOMEALIAS = (DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=home-db.example)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=HOMESVC)))\n");
        ProcessLaunchTest.process(proc, "1", "java", "-Dloop=${loop}", "-Dapp.host=prop.example", "-Dapp.password=SECRET_PROPERTY", "Main");
        ProcessLaunchTest.text(new File(proc, "1/environ"), "TNS_ADMIN=" + tns.getCanonicalPath() + "\u0000DB_HOST=env.example\u0000DB_TOKEN=SECRET_TOKEN\u0000");
        ProcessLaunchTest.process(proc, "2", "java", "-Doracle.net.tns_admin=" + url.getCanonicalPath(), "Main");
        ProcessLaunchTest.process(proc, "3", "java", "Main");
        ProcessLaunchTest.text(new File(proc, "3/environ"), "ORACLE_HOME=" + home.getCanonicalPath() + "\u0000");
        AssessmentScan scan = ProcessLaunchTest.scanner();
        JdbcEndpoint.Resolver one = new JdbcEndpoint.Resolver(scan, new File(proc, "1")), two = new JdbcEndpoint.Resolver(scan, new File(proc, "2")), three = new JdbcEndpoint.Resolver(scan, new File(proc, "3"));
        List<String> sources = new ArrayList<String>();
        check("jdbc:x://prop.example:1521/env.example".equals(one.expand("jdbc:x://${app.host}:${app.port:1521}/${env.DB_HOST}", sources)) && sources.equals(Arrays.asList("system-property", "default", "environment")), "Embedded system property, default and environment placeholders");
        check("env.example".equals(one.expand("${missing:${env.DB_HOST}}", new ArrayList<String>())), "Nested default resolved");
        check("prop.example".equals(one.expand("${missing,app.host}", new ArrayList<String>())), "JBoss alternative keys");
        for (String rejected : new String[]{"${app.password}", "${env.DB_TOKEN:fallback}", "${loop}", "#{expression}", "${unclosed", "${bad key}", "${missing}", "ENC(abc)"})
            check(one.expand(rejected, new ArrayList<String>()) == null, "Rejected expression: " + rejected);
        StringBuilder many = new StringBuilder(); for (int i = 0; i < 17; i++) many.append("${x:").append(i).append('}');
        check(one.expand(many.toString(), new ArrayList<String>()) == null, "Placeholder count bounded");
        Map tnsRow = new LinkedHashMap(); JdbcEndpoint.addTo(tnsRow, "jdbc:oracle:thin:@orders", one);
        check("tns-db.example".equals(tnsRow.get("databaseHost")) && Integer.valueOf(1525).equals(tnsRow.get("databasePort")) && "ORDERSVC".equals(tnsRow.get("databaseName")), "TNS alias from TNS_ADMIN environment");
        check("tnsnames".equals(tnsRow.get("databaseEndpointEvidence")) && Arrays.asList("tnsnames").equals(tnsRow.get("resolvedFrom")) && "Oracle".equals(tnsRow.get("databaseType")), "TNS evidence recorded");
        Map rac = new LinkedHashMap(); JdbcEndpoint.addTo(rac, "jdbc:oracle:thin:@RAC?TNS_ADMIN=" + url.getCanonicalPath(), one);
        check(((List)rac.get("databaseEndpoints")).size() == 2 && "RACSVC".equals(rac.get("databaseName")), "URL TNS_ADMIN parameter wins");
        Map property = new LinkedHashMap(); JdbcEndpoint.addTo(property, "jdbc:oracle:thin:@RAC", two);
        check("rac-a.example".equals(property.get("databaseHost")), "-Doracle.net.tns_admin directory");
        Map oracleHome = new LinkedHashMap(); JdbcEndpoint.addTo(oracleHome, "jdbc:oracle:thin:@HOMEALIAS", three);
        check("home-db.example".equals(oracleHome.get("databaseHost")), "ORACLE_HOME/network/admin fallback");
        for (String alias : new String[]{"jdbc:oracle:thin:@UNKNOWN", "jdbc:oracle:thin:@ORDERS.WORLD.EXTRA"}) { Map none = new LinkedHashMap(); JdbcEndpoint.addTo(none, alias, one); check(none.get("databaseHost") == null, "Unknown alias stays unresolved"); }
        Map placeholder = new LinkedHashMap(); JdbcEndpoint.addTo(placeholder, "jdbc:oracle:thin:@${env.ALIAS:ORDERS}", one);
        check("tns-db.example".equals(placeholder.get("databaseHost")) && ((List)placeholder.get("resolvedFrom")).containsAll(Arrays.asList("default", "tnsnames")), "Placeholder and TNS resolution combine");
        ProcessLaunchTest.text(new File(tns, "tnsnames.ora"), "ORDERS = (DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=a.example)(PORT=1)))\nORDERS = (DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=b.example)(PORT=2)))\n");
        Map duplicate = new LinkedHashMap(); JdbcEndpoint.addTo(duplicate, "jdbc:oracle:thin:@ORDERS", new JdbcEndpoint.Resolver(scan, new File(proc, "1")));
        check(duplicate.get("databaseHost") == null && scan.partial, "Ambiguous tnsnames.ora alias is not guessed");
        String json = Json.stringify(Arrays.asList(tnsRow, rac, property, oracleHome, placeholder, scan.warnings));
        check(!json.contains("SECRET") && !json.contains("jdbc:") && !json.contains("DESCRIPTION"), "No TNS text, URL or secrets retained");
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
        domains(); schemes();
        File root = CollectorTest.temp("jdbc-resolver");
        try { resolver(root); } finally { CollectorTest.remove(root); }
        System.out.println("JDBC_ENDPOINT_TESTS=PASS checks=" + checks);
    }
}
