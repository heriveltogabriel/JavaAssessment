package assessment;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import com.sun.net.httpserver.*;

/** Local fixtures only; never contacts OCI or publishes invented production rows. */
public final class CollectorTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static File temp(String name) throws Exception {
        File path = File.createTempFile(name, "-collector-test");
        if (!path.delete() || !path.mkdir()) throw new IOException("Cannot create fixture directory");
        return path.getCanonicalFile();
    }
    static void remove(File file) {
        File[] files = file.listFiles(); if (files != null) for (File child : files) remove(child);
        file.delete();
    }
    static AssessmentScan scan(Map settings) { return new AssessmentScan(new Properties(), "test-host", "test-hostname", settings); }
    static void limitsAndElf() throws Exception {
        File root = temp("java-home");
        try {
            File bin = new File(root, "bin"); bin.mkdir(); File executable = new File(bin, "java");
            new File(bin, "javac").createNewFile();
            byte[] header = new byte[20]; header[0] = 127; header[1] = 'E'; header[2] = 'L'; header[3] = 'F'; header[4] = 1; header[5] = 1; header[18] = 3;
            FileOutputStream output = new FileOutputStream(executable); output.write(header); output.close();
            File inventory = new File(root, "application-inventory"); inventory.mkdir();
            for (int i = 0; i < 150; i++) new File(inventory, "fixture-" + i).createNewFile();
            AssessmentScan scan = scan(AssessmentScan.map("maxFiles", 100, "pauseMillis", 0));
            Map runtime = scan.addRuntime(root, false, "test fixture", "2001-01-01T00:00:00.000Z", null, null, null);
            check("i386 (32-bit)".equals(runtime.get("architecture")), "ELF installation must remain 32-bit regardless of host");
            check(Boolean.FALSE.equals(runtime.get("inUse")), "An installation is not automatically in use");
            check(runtime.get("version") == null, "Unknown version must stay unknown");
            scan.excluded.clear(); scan.walk(inventory, 0);
            check(scan.filesVisited <= 100 && scan.partial, "File budget must stop scanning and mark partial coverage");
            check(!AssessmentScan.under("/opt/jdk-extra", "/opt/jdk"), "Excluded prefixes must respect path boundaries");
        } finally { remove(root); }
    }
    static void manifestAndSnapshot() throws Exception {
        File root = temp("metadata");
        try {
            Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Implementation-Version", "test-version");
            File jarFile = new File(root, "derby.jar"); JarOutputStream jar = new JarOutputStream(new FileOutputStream(jarFile), manifest); jar.close();
            AssessmentScan noArchive = scan(AssessmentScan.map("inspectArchives", Boolean.FALSE)); noArchive.inspectJar(jarFile, "derby.jar");
            check(((Map) noArchive.libraries.get(0)).get("version") == null, "Archive setting must suppress manifest inspection");
            AssessmentScan archive = scan(AssessmentScan.map("inspectArchives", Boolean.TRUE)); archive.inspectJar(jarFile, "derby.jar");
            check("test-version".equals(((Map) archive.libraries.get(0)).get("version")), "Manifest version must be preserved");
            File event = new File(root, "event"); event.mkdir();
            AssessmentScan.atomicWrite(new File(root, "latest-event-path"), event.getCanonicalPath());
            String old = "2001-01-01T00:00:00.000Z";
            AssessmentScan.atomicWrite(new File(event, "snapshot.json"), Json.stringify(AssessmentScan.map("capturedAt", old,
                "serverName", "OldSnapshot", "serverState", "RUNNING", "weblogicVersion", "10.3.6.0", "jvmProperties", AssessmentScan.map(),
                "jdbcDataSources", Arrays.asList(AssessmentScan.map("name", "Ds", "driverName", "Example JDBC", "driverVersion", "1.0", "poolCapacity", 1)))));
            Properties properties = new Properties(); properties.setProperty("snapshotRoot", root.getCanonicalPath());
            AssessmentScan reused = new AssessmentScan(properties, "test-host", "test-hostname", AssessmentScan.map()); reused.reuseWebLogicSnapshot();
            Map server = reused.servers.get("WebLogic:OldSnapshot");
            check(old.equals(server.get("observedAt")), "Reused observations must keep original timestamp");
            check(old.equals(((Map) reused.datasources.get(0)).get("observedAt")), "Datasource timestamp must not be refreshed");
            check(((Map) reused.libraries.get(0)).get("path") == null, "JDBC runtime observation does not prove a JAR path");
        } finally { remove(root); }
    }
    static void retryIsIdempotent() throws Exception {
        final List<String> eventIds = new ArrayList<String>(); final int[] polls = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                int status = 200; Object body;
                String path = exchange.getRequestURI().getPath();
                try {
                    Map requestBody = null;
                    if ("POST".equals(exchange.getRequestMethod())) {
                        InputStream in = exchange.getRequestBody();
                        try { requestBody = (Map) Json.parse(AssessmentScan.readStream(in, 2 * 1024 * 1024)); }
                        finally { in.close(); }
                    }
                    check("Bearer unit-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")), "Agent must send configured bearer");
                    if (path.endsWith("/heartbeat")) body = AssessmentScan.map("accepted", Boolean.TRUE);
                    else if (path.endsWith("/poll")) body = AssessmentScan.map("jobId", polls[0]++ == 0 ? "unit-test-job" : null,
                        "settings", AssessmentScan.map("includePaths", new ArrayList(), "maxFiles", 1, "maxDurationSeconds", 5, "pauseMillis", 0));
                    else {
                        Map result = requestBody;
                        String id = (String) result.get("eventId"); eventIds.add(id);
                        if (eventIds.size() == 1) { status = 503; body = AssessmentScan.map("error", "temporary fixture failure"); }
                        else body = AssessmentScan.map("accepted", Boolean.TRUE, "eventId", id);
                    }
                    byte[] bytes = Json.stringify(body).getBytes("UTF-8"); exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            }
        });
        File spool = temp("spool"); server.start();
        try {
            Properties config = new Properties(); config.setProperty("hostId", "unit-test-host"); config.setProperty("hostname", "unit-test-hostname");
            config.setProperty("agentToken", "unit-test-token"); config.setProperty("serverUrl", "http://127.0.0.1:" + server.getAddress().getPort()); config.setProperty("spoolDirectory", spool.getCanonicalPath());
            boolean failed = false;
            try { new AssessmentCollector(config).run(); } catch (IOException expected) { failed = true; }
            check(failed, "Temporary transport failure must be reported");
            new AssessmentCollector(config).run();
            check(eventIds.size() == 2 && eventIds.get(0).equals(eventIds.get(1)), "Retry must preserve eventId");
            for (File file : spool.listFiles()) check(!file.getName().startsWith("pending-"), "Only acknowledged result leaves pending queue");
            check(new File(spool, "latest-snapshot.json").isFile(), "Latest accepted snapshot must remain locally inspectable");
        } finally { server.stop(0); remove(spool); }
    }
    public static void main(String[] args) throws Exception {
        limitsAndElf(); manifestAndSnapshot(); retryIsIdempotent();
        System.out.println("COLLECTOR_TESTS=PASS limits ELF32 manifest timestamp retry idempotency");
    }
}
