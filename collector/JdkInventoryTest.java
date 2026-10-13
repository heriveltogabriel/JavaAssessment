package assessment;

import java.io.*;
import java.util.*;

/** Java 6-compatible isolated files/proc fixtures; no discovered executable is run. */
public final class JdkInventoryTest {
    static void check(boolean value, String message) { CollectorTest.check(value, message); }
    static void text(File file, String value) throws Exception { ProcessLaunchTest.text(file, value); }
    static File home(File root, String name, boolean compiler) throws Exception {
        File home = new File(root, name);
        text(new File(home, "bin/java"), "fixture executable is never launched");
        if (compiler) text(new File(home, "bin/javac"), "fixture compiler is never launched");
        return home.getCanonicalFile();
    }
    static AssessmentScan scanner() { return ProcessLaunchTest.scanner(); }
    static AssessmentScan scanner(File codeLocation) {
        AssessmentScan scan = new AssessmentScan(new Properties(), "fixture-host", "fixture-host",
            AssessmentScan.map("inspectArchives", Boolean.TRUE, "maxFiles", 1000, "maxDurationSeconds", 120, "pauseMillis", 0), codeLocation);
        scan.excluded.clear(); return scan;
    }
    static Map add(AssessmentScan scan, File home, boolean used, String version) {
        return scan.addRuntime(home, used, "isolated fixture", scan.timestamp, version, "Fixture vendor", null);
    }
    static void inventory() throws Exception {
        File root = CollectorTest.temp("jdk-only");
        try {
            File standalone = home(root, "jre6", false);
            text(new File(standalone, "release"), "JAVA_VERSION=\"1.6.0_45\"\n");
            text(new File(standalone, "lib/deep/derby.jar"), "must not scan JRE internals");
            AssessmentScan ignored = scanner();
            check(add(ignored, standalone, true, "1.6.0_45") == null, "An in-use standalone JRE must not enter JDK inventory");
            ignored.walk(standalone, 0);
            check(ignored.runtimes.isEmpty() && ignored.filesVisited == 1 && ignored.bytesRead == 0,
                "Standalone JRE is skipped without walking internals or reading metadata");
            check(!ignored.partial, "Intentional JRE omission must not mark collection incomplete");

            File legacy = home(root, "jdk6", true), nested = home(legacy, "jre", false);
            AssessmentScan scan = scanner();
            Map jdk = add(scan, nested, true, "1.6.0_45");
            check(jdk != null && "JDK".equals(jdk.get("kind")) && legacy.getPath().equals(jdk.get("javaHome")),
                "Java 6 nested java.home must resolve to its containing JDK");
            check(add(scan, legacy, false, null) == jdk && scan.runtimes.size() == 1 && Boolean.TRUE.equals(jdk.get("inUse")),
                "JDK root and nested JRE are one installation; in-use evidence survives discovery order");
            check("1.6.0_45".equals(jdk.get("version")), "Known legacy JDK version must be preserved");

            for (String version : new String[] { "17.0.16", "21.0.8" }) {
                File modern = home(root, "jdk" + version, true);
                text(new File(modern, "release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Fixture vendor\"\n");
                Map row = add(scan, modern, false, null);
                check(row != null && version.equals(row.get("version")) && "JDK".equals(row.get("kind")),
                    "Modern JDK release metadata must remain in inventory");
            }
            check(scan.runtimes.size() == 3, "Only the three JDK installations are retained");
        } finally { CollectorTest.remove(root); }
    }
    static void processEvidence() throws Exception {
        File root = CollectorTest.temp("jdk-processes");
        try {
            File standalone = home(root, "runtime", false), jdk = home(root, "jdk6", true), nested = home(jdk, "jre", false);
            File proc = new File(root, "proc");
            ProcessLaunchTest.process(proc, "98766001", new File(standalone, "bin/java").getPath(), "org.apache.catalina.startup.Bootstrap", "start");
            ProcessLaunchTest.process(proc, "98766002", new File(nested, "bin/java").getPath(), "com.example.Service");
            AssessmentScan scan = scanner(); scan.discoverProcesses(proc);
            check(scan.runtimes.size() == 1 && scan.processes.size() == 2 && scan.applications.size() == 2 && scan.servers.size() == 1,
                "Ignoring JRE inventory must retain its process, application and Tomcat server");
            Map jreProcess = (Map)scan.processes.get(0), jreApplication = (Map)scan.applications.get(0);
            check(standalone.getPath().equals(jreProcess.get("javaHome")) && standalone.getPath().equals(jreApplication.get("javaHome")),
                "Process/application Java home evidence must not depend on inventory inclusion");
            check(jreApplication.get("id").equals(jreProcess.get("applicationId")), "JRE process/application identity association is retained");
            Map server = (Map)scan.servers.values().iterator().next();
            check(standalone.getPath().equals(server.get("javaHome")) && "PROCESS_RUNNING".equals(server.get("state")),
                "Server evidence remains linked to the standalone JRE process");
            check(jdk.getPath().equals(((Map)scan.processes.get(1)).get("javaHome")) && Boolean.TRUE.equals(scan.runtimes.get(jdk.getPath()).get("inUse")),
                "Legacy JDK process links to canonical JDK inventory and marks it in use");
        } finally { CollectorTest.remove(root); }
    }
    static void snapshotEvidence() throws Exception {
        File root = CollectorTest.temp("jdk-snapshot");
        try {
            File standalone = home(root, "jre", false), event = new File(root, "event"); event.mkdir();
            text(new File(root, "latest-event-path"), event.getCanonicalPath());
            text(new File(event, "snapshot.json"), Json.stringify(AssessmentScan.map("capturedAt", "2001-01-01T00:00:00.000Z",
                "serverName", "Legacy", "serverState", "RUNNING", "weblogicVersion", "10.3.6.0",
                "jvmProperties", AssessmentScan.map("java.home", standalone.getPath(), "java.version", "1.6.0_45"),
                "jdbcDataSources", Arrays.asList(AssessmentScan.map("name", "Ds", "driverName", "Example JDBC", "driverVersion", "1.0")))));
            Properties config = new Properties(); config.setProperty("snapshotRoot", root.getCanonicalPath());
            AssessmentScan scan = new AssessmentScan(config, "fixture-host", "fixture-host", AssessmentScan.map()); scan.reuseWebLogicSnapshot();
            check(scan.runtimes.isEmpty() && scan.servers.size() == 1 && scan.datasources.size() == 1 && scan.libraries.size() == 1,
                "Snapshot JRE omission must retain WebLogic and JDBC observations");
        } finally { CollectorTest.remove(root); }
    }
    static void privateCollectorRuntime() throws Exception {
        File root = CollectorTest.temp("private-runtime");
        try {
            File jar = new File(root, "installation/collector.jar"); text(jar, "code-location fixture");
            File runtimeRoot = new File(jar.getParentFile(), "runtime");
            File privateJdk = home(runtimeRoot, "jdk6", true), nested = home(privateJdk, "jre", false);
            AssessmentScan scan = scanner(jar);
            check(runtimeRoot.getCanonicalPath().equals(scan.collectorPrivateRuntimeRoot), "Private root must come from collector.jar location");
            check(AssessmentScan.privateRuntimeRoot(null) == null && AssessmentScan.privateRuntimeRoot(jar.getParentFile()) == null,
                "Unknown code source or class directory must not invent a private root");
            File unrelatedJar = new File(root, "other.jar"); text(unrelatedJar, "unrelated code-location fixture");
            check(AssessmentScan.privateRuntimeRoot(unrelatedJar) == null, "An unrelated JAR must not imply a collector runtime");

            check(add(scan, nested, false, "1.6.0_45") == null, "Collector-only nested private Java must stay out of inventory");
            scan.walk(privateJdk, 0);
            check(scan.runtimes.isEmpty() && scan.filesVisited == 0 && scan.bytesRead == 0,
                "Filesystem rediscovery must not re-add the private JDK or traverse its internals");
            check(!scan.partial, "Omitting collector-only Java must not reduce collection coverage");

            File similarlyNamed = home(jar.getParentFile(), "runtime-extra/jdk", true);
            check(add(scan, similarlyNamed, false, "21.0.8") != null, "Private root matching must respect directory boundaries");
            File application = home(root, "application-collector/jdk", true);
            check(add(scan, application, false, "17.0.16") != null, "An application path containing collector must be preserved");
            for (String version : new String[] { "11.0.28", "17.0.16" }) {
                File shared = home(root, "system-java/jdk" + version, true);
                Map row = add(scan, shared, false, version);
                check(row != null && version.equals(row.get("version")) && !Boolean.TRUE.equals(row.get("inUse")),
                    "Shared JDK must retain known collector-JVM metadata without inventing application use");
                Map used = add(scan, shared, true, null);
                check(used == row && version.equals(used.get("version")), "Application discovery must keep existing shared-JDK version details");
            }

            File proc = new File(root, "proc");
            ProcessLaunchTest.process(proc, "98766003", new File(nested, "bin/java").getPath(), "org.apache.catalina.startup.Bootstrap", "start");
            AssessmentScan observed = scanner(jar); observed.discoverProcesses(proc);
            Map privateRow = add(observed, nested, false, "1.6.0_45");
            check(privateRow != null && observed.runtimes.size() == 1 && Boolean.TRUE.equals(privateRow.get("inUse")) && "1.6.0_45".equals(privateRow.get("version")),
                "Another application's use must retain private JDK and permit own-JVM metadata enrichment");
            Map process = (Map)observed.processes.get(0), app = (Map)observed.applications.get(0), server = (Map)observed.servers.values().iterator().next();
            check(privateJdk.getPath().equals(process.get("javaHome")) && privateJdk.getPath().equals(app.get("javaHome")) && privateJdk.getPath().equals(server.get("javaHome")),
                "Application-observed private JDK keeps process, application and server links");
            observed.walk(privateJdk, 0);
            check(observed.runtimes.size() == 1, "Later filesystem discovery must preserve the in-use private JDK without duplication");
        } finally { CollectorTest.remove(root); }
    }
    public static void main(String[] args) throws Exception {
        inventory(); processEvidence(); snapshotEvidence(); privateCollectorRuntime();
        System.out.println("JDK_INVENTORY_TESTS=PASS JRE-skip Java6-normalization JDK17 JDK21 process-links snapshot-evidence collector-private-runtime shared-JDK-preservation");
    }
}
