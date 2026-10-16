package assessment;

import java.io.*;
import java.util.*;

/** Reproduces the RHEL 5 inventory gaps without executing Java, WebLogic or a database. */
public final class LegacyDiscoveryTest {
    static int checks;
    static void check(boolean value, String message) { checks++; CollectorTest.check(value, message); }
    static void text(File file, String value) throws Exception { ProcessLaunchTest.text(file, value); }
    static AssessmentScan scanner() { return ProcessLaunchTest.scanner(); }
    static File runtime(File root, boolean jdk, Map<String,String> attrs, boolean largeIndex) throws Exception {
        text(new File(root, "bin/java"), "discovered executable must never run");
        if (jdk) text(new File(root, "bin/javac"), "compiler not required for a used JVM");
        Map<String,byte[]> entries = ProcessLaunchTest.entries();
        if (largeIndex) for (int i = 0; i < 6000; i++) entries.put("java/fixture/Class" + i + ".class", new byte[0]);
        File jar = new File(root, jdk ? "jre/lib/rt.jar" : "lib/rt.jar");
        ProcessLaunchTest.write(jar, ProcessLaunchTest.jar(attrs, entries)); return jar;
    }
    static Map<String,String> attributes() { return ProcessLaunchTest.attrs("Implementation-Title", "Java Runtime Environment", "Implementation-Version", "1.6.0_45", "Implementation-Vendor", "Sun Microsystems, Inc.", "Created-By", "99.0.0 (PRIVATE_CANARY)", "Specification-Version", "1.6"); }
    static void runtimeChecks(File root) throws Exception {
        File home = new File(root, "misleading-jdk-99"); runtime(home, true, attributes(), true);
        AssessmentScan scan = scanner(); Map row = scan.addRuntime(home, true, "Observed process", scan.timestamp, null, null, null);
        check("1.6.0_45".equals(row.get("version")), "Java 6 version must come from the rt.jar implementation manifest");
        check("Sun Microsystems, Inc.".equals(row.get("vendor")), "Legacy implementation vendor retained");
        check(String.valueOf(row.get("evidence")).contains("rt.jar"), "Legacy metadata source recorded");
        check(scan.bytesRead < ArchiveMetadata.READ_LIMIT, "Realistic runtime archive index remains within global read budget");
        long reads = scan.bytesRead; scan.addRuntime(home, true, "Second process", scan.timestamp, null, null, null);
        check(scan.bytesRead == reads && scan.runtimes.size() == 1, "Shared runtime is read once");
        check(String.valueOf(row.get("evidence")).contains("rt.jar"), "Second process does not erase metadata provenance");
        File jre = new File(root, "standalone-jre"); runtime(jre, false, attributes(), false);
        scan = scanner(); row = scan.addRuntime(jre, true, "Observed JVM", scan.timestamp, null, null, null);
        check("JRE".equals(row.get("kind")) && "1.6.0_45".equals(row.get("version")), "Used JRE needs no compiler");
        scan = scanner(); scan.settings.put("inspectArchives", Boolean.FALSE); row = scan.addRuntime(jre, true, "Observed JVM", scan.timestamp, null, null, null);
        check("1.6.0_45".equals(row.get("version")), "Core runtime identity remains available when application archive discovery is off");
        text(new File(home, "release"), "JAVA_VERSION=\"17.0.1\"\nIMPLEMENTOR=\"Modern vendor\"\n");
        scan = scanner(); row = scan.addRuntime(home, true, "Observed JVM", scan.timestamp, null, null, null);
        check("17.0.1".equals(row.get("version")) && "Modern vendor".equals(row.get("vendor")), "Release metadata wins over legacy fallback");
        scan = scanner(); scan.excluded.add(jre.getCanonicalPath()); row = scan.addRuntime(jre, true, "Observed JVM", scan.timestamp, null, null, null);
        check(row.get("version") == null && scan.archivesInspected == 0, "Excluded archive not inspected");
        File other = new File(root, "unknown"); runtime(other, false, ProcessLaunchTest.attrs("Implementation-Title", "Other library", "Implementation-Version", "1.6.0_45", "Created-By", "99.0.0"), false);
        scan = scanner(); row = scan.addRuntime(other, true, "Observed JVM", scan.timestamp, null, null, null);
        check(row.get("version") == null, "Arbitrary library/build version is not runtime version");
        runtime(other, false, ProcessLaunchTest.attrs("Implementation-Title", "Java Runtime Environment", "Specification-Version", "1.6", "Created-By", "99.0.0"), false);
        scan = scanner(); row = scan.addRuntime(other, true, "Observed JVM", scan.timestamp, null, null, null);
        check(row.get("version") == null, "No exact version inferred from specification or folder");
        Map<String,String> huge = attributes(); char[] padding = new char[40000]; Arrays.fill(padding, 'x'); huge.put("Unrelated-Field", new String(padding)); runtime(other, false, huge, false);
        scan = scanner(); row = scan.addRuntime(other, true, "Observed JVM", scan.timestamp, null, null, null);
        check(row.get("version") == null && scan.partial, "Oversized runtime manifest rejected without loading unrelated metadata");
        scan = scanner(); scan.bytesRead = ArchiveMetadata.READ_LIMIT; row = scan.addRuntime(jre, true, "Observed JVM", scan.timestamp, null, null, null);
        check(row.get("version") == null && scan.partial, "Exhausted metadata budget blocks runtime fallback");
        check(!Json.stringify(scan.runtimes).contains("PRIVATE_CANARY"), "Runtime metadata fields allowlisted");
    }
    static String descriptor() {
        return "<jdbc-data-source xmlns='http://xmlns.oracle.com/weblogic/jdbc-data-source'><name>JmsLabDerby</name><jdbc-driver-params><url>jdbc:derby:memory:PRIVATE_CANARY;create=true</url><driver-name>org.apache.derby.jdbc.EmbeddedDriver</driver-name><password-encrypted>PRIVATE_CANARY</password-encrypted><properties><property><name>user</name><value>PRIVATE_CANARY</value></property></properties></jdbc-driver-params><jdbc-connection-pool-params><max-capacity>5</max-capacity></jdbc-connection-pool-params><jdbc-data-source-params><jndi-name>jdbc/JmsLabDerby</jndi-name></jdbc-data-source-params></jdbc-data-source>";
    }
    static String reference(String path) { return "<jdbc-system-resource><name>JmsLabDerby</name><target>AdminServer</target><descriptor-file-name>" + path + "</descriptor-file-name></jdbc-system-resource>"; }
    static String domain(String refs) { return "<domain xmlns='http://xmlns.oracle.com/weblogic/domain'><name>jms_lab</name>" + refs + "</domain>"; }
    static Map weblogicChecks(File root) throws Exception {
        File base = new File(root, "domain"), proc = new File(root, "proc");
        text(new File(base, "config/config.xml"), domain(reference("jdbc/JmsLabDerby-jdbc.xml")));
        text(new File(base, "config/jdbc/JmsLabDerby-jdbc.xml"), descriptor());
        text(new File(base, "config/jdbc/orphan-jdbc.xml"), descriptor().replace("JmsLabDerby", "Orphan"));
        ProcessLaunchTest.process(proc, "98666101", "java", "-Dweblogic.RootDirectory=" + base.getCanonicalPath(), "-Dweblogic.Name=AdminServer", "weblogic.Server");
        ProcessLaunchTest.process(proc, "98666102", "java", "-Dweblogic.RootDirectory=" + base.getCanonicalPath(), "-Dweblogic.Name=OtherServer", "weblogic.Server");
        AssessmentScan scan = scanner(); scan.discoverProcesses(proc);
        check(scan.datasources.size() == 2, "WebLogic referenced and unreferenced descriptors in running domain must be discovered without a snapshot");
        check("domain-unreferenced-module".equals(((Map)scan.datasources.get(1)).get("configurationScope")), "Unreferenced descriptor is not presented as deployed");
        Map row = (Map)scan.datasources.get(0);
        check("JmsLabDerby".equals(row.get("name")) && "Derby".equals(row.get("databaseType")), "Derby resource and database family identified");
        check("org.apache.derby.jdbc.EmbeddedDriver".equals(row.get("driverName")) && Integer.valueOf(5).equals(row.get("configuredMaxPoolSize")), "Driver class and configured pool limit retained");
        check("CONFIGURED".equals(row.get("state")) && row.get("poolCapacity") == null, "Configuration must not claim a running pool");
        check(((List)row.get("associations")).size() == 2, "Domain configuration retains both observed process associations without duplicate resources");
        check(Json.stringify(row.get("configuredTargets")).contains("AdminServer"), "Configured target is separate from observed process associations");
        check(Json.stringify(row.get("jndiNames")).contains("jdbc/JmsLabDerby"), "Safe JNDI name retained");
        check(!Json.stringify(ServerDataSourcesTest.inventory(scan)).contains("PRIVATE_CANARY"), "No URL, username or encrypted password retained");
        Map exported = ServerDataSourcesTest.inventory(scan);
        check(new File(base, "config/jdbc/orphan-jdbc.xml").delete(), "Remove unreferenced fixture before rejection tests");
        ProcessLaunch cwd = ProcessLaunch.parse(new String[]{"java", "-Dweblogic.Name=AdminServer", "weblogic.Server"}, base);
        check(base.getCanonicalPath().equals(cwd.serverBase), "WebLogic default domain is observed process cwd");
        text(new File(base, "config/config.xml"), domain(reference("../external-jdbc.xml")));
        text(new File(base, "external-jdbc.xml"), descriptor()); scan = scanner(); scan.discoverProcesses(proc);
        check(scan.datasources.isEmpty() && scan.partial, "Descriptor traversal rejected with coverage warning");
        text(new File(base, "config/config.xml"), domain(reference("jdbc/JmsLabDerby-jdbc.xml")));
        scan = scanner(); scan.excluded.add(new File(base, "config/jdbc").getCanonicalPath()); scan.discoverProcesses(proc);
        check(scan.datasources.isEmpty(), "Excluded JDBC paths remain excluded");
        text(new File(base, "config/jdbc/JmsLabDerby-jdbc.xml"), "<!DOCTYPE jdbc-data-source [<!ENTITY x SYSTEM 'file:///PRIVATE_CANARY'>]>" + descriptor());
        scan = scanner(); scan.discoverProcesses(proc); check(scan.datasources.isEmpty() && scan.partial, "External entities rejected");
        check(!Json.stringify(scan.warnings).contains("PRIVATE_CANARY"), "XML diagnostics never echo source values");
        text(new File(base, "config/jdbc/JmsLabDerby-jdbc.xml"), descriptor().replace("http://xmlns.oracle.com/weblogic/jdbc-data-source", "urn:unrelated"));
        scan = scanner(); scan.discoverProcesses(proc); check(scan.datasources.isEmpty(), "Unrelated XML namespace rejected");
        text(new File(base, "config/jdbc/JmsLabDerby-jdbc.xml"), descriptor());
        scan = scanner(); scan.serverDataSources.cachedRows = 512; scan.discoverProcesses(proc);
        check(scan.datasources.isEmpty() && scan.partial, "Shared JDBC row budget applies to WebLogic");
        StringBuilder many = new StringBuilder(); for (int i = 0; i < 129; i++) many.append(reference("jdbc/JmsLabDerby-jdbc.xml"));
        text(new File(base, "config/config.xml"), domain(many.toString())); scan = scanner(); scan.discoverProcesses(proc);
        check(scan.datasources.isEmpty() && scan.partial, "Excessive domain references rejected atomically");
        return exported;
    }
    public static void main(String[] args) throws Exception {
        File root = CollectorTest.temp("legacy-discovery");
        try {
            if (args.length == 0 || !"weblogic".equals(args[0])) runtimeChecks(new File(root, "runtime"));
            Map weblogic = null;
            if (args.length == 0 || !"runtime".equals(args[0])) weblogic = weblogicChecks(new File(root, "weblogic"));
            if (args.length == 2 && "--export".equals(args[0])) {
                File javaHome = new File(root, "export-java6"); runtime(javaHome, true, attributes(), false);
                AssessmentScan scan = scanner(); scan.addRuntime(javaHome, true, "Observed JVM", scan.timestamp, null, null, null);
                weblogic.put("runtimes", new ArrayList(scan.runtimes.values()));
                text(new File(args[1]), Json.stringify(weblogic));
            }
            System.out.println("LEGACY_DISCOVERY_TESTS=PASS checks=" + checks);
        } finally { CollectorTest.remove(root); }
    }
}
