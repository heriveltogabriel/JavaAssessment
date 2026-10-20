package assessment;

import java.io.*;
import java.util.*;

/** Java 6-compatible fixtures for sweep budgets and exact collector-package exclusion. */
public final class CollectorSweepTest {
    static void check(boolean value, String message) { CollectorTest.check(value, message); }
    static void text(File file, String value) throws Exception { ProcessLaunchTest.text(file, value); }
    static void write(File file, byte[] value) throws Exception { ProcessLaunchTest.write(file, value); }
    static AssessmentScan scanner(File jar, Map settings) {
        AssessmentScan scan = new AssessmentScan(new Properties(), "sweep-host", "sweep-host", settings, jar);
        scan.excluded.clear(); return scan;
    }
    static Map settings() { return AssessmentScan.map("maxFiles", 100, "pauseMillis", 0, "inspectArchives", Boolean.TRUE); }
    static byte[] plainJar() throws Exception { return ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Implementation-Version", "1.0"), ProcessLaunchTest.entries()); }
    static void packageExclusion() throws Exception {
        File root = CollectorTest.temp("own-package-sweep").getCanonicalFile();
        try {
            File jar = new File(root, "installation/collector.jar"); write(jar, plainJar());
            File installation = jar.getParentFile();
            for (int i = 0; i < 45; i++) write(new File(installation, "lib/bcprov-fixture-" + i + ".jar"), plainJar());
            write(new File(installation, "lib/postgresql-42.7.4.jar"), plainJar());
            text(new File(installation, "logs/install.log"), "fixture installation event");
            text(new File(installation, "collector-spool/latest-snapshot.json"), "{}");
            File privateJdk = JdkInventoryTest.home(installation, "runtime/jdk6", true);
            text(new File(privateJdk, "lib/deep/metadata"), "fixture metadata");
            AssessmentScan scan = scanner(jar, settings());
            scan.walk(installation, 0); scan.walk(jar, 13); scan.walk(privateJdk, 0);
            check(scan.filesVisited == 0 && scan.archivesInspected == 0 && scan.bytesRead == 0 && scan.deferredArchives.isEmpty() && scan.visited.size() == 0,
                "Collector package, libraries, logs, spool and runtime must consume no sweep budget");
            check(scan.runtimes.isEmpty() && scan.libraries.isEmpty() && scan.applications.isEmpty() && !scan.partial,
                "Collector self-files must neither become application inventory nor incomplete coverage");
            scan.sweepFiles = scan.filesVisited = scan.maxFiles; scan.walk(installation, 13);
            check(!scan.partial && !scan.fileLimitReached, "Intentional package exclusion must precede depth and file limits");
            File configured = new File(root, "configured-exclusion"); text(new File(configured, "artifact.jar"), "ignored");
            scan.excluded.add(configured.getPath()); scan.walk(configured, 13);
            check(!scan.partial, "Configured exclusions must also precede depth and file limits");
            File actual = new File(root, "actual-file"); text(actual, "eligible file"); scan.walk(actual, 0);
            check(scan.partial && scan.fileLimitReached, "A real eligible file beyond the budget must still report partial coverage");
        } finally { CollectorTest.remove(root); }
    }
    static void nearbyAndProcessLinked() throws Exception {
        File root = CollectorTest.temp("linked-package-sweep").getCanonicalFile();
        try {
            File jar = new File(root, "installation/collector.jar"); write(jar, plainJar());
            File nearby = new File(root, "installation-app"), war = new File(nearby, "demo.war"); write(war, plainJar());
            File shared = JdkInventoryTest.home(nearby, "jdk17", true);
            text(new File(shared, "release"), "JAVA_VERSION=\"17.0.16\"\n");
            Map<String,byte[]> nested = ProcessLaunchTest.entries();
            nested.put("BOOT-INF/lib/postgresql-42.7.4.jar", ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Implementation-Title", "PostgreSQL JDBC Driver", "Implementation-Version", "42.7.4"), ProcessLaunchTest.entries()));
            File app = new File(jar.getParentFile(), "explicitly-linked/app.jar");
            write(app, ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Main-Class", "org.springframework.boot.loader.launch.JarLauncher", "Start-Class", "com.example.Service", "Spring-Boot-Version", "3.3.4"), nested));
            File proc = new File(root, "proc");
            ProcessLaunchTest.process(proc, "98767001", new File(shared, "bin/java").getPath(), "-jar", app.getPath());
            AssessmentScan scan = scanner(jar, settings()); scan.walk(jar.getParentFile(), 0);
            check(scan.filesVisited == 0 && scan.bytesRead == 0, "Broad sweep must skip even process-linked artifacts until an actual process identifies them");
            scan.discoverProcesses(proc);
            Map runtime = scan.runtimes.get(shared.getPath());
            check(runtime != null && Boolean.TRUE.equals(runtime.get("inUse")) && "17.0.16".equals(runtime.get("version")),
                "The shared JDK remains inventoried and in use");
            check(scan.processes.size() == 1 && scan.applications.size() == 1 && "Spring Boot".equals(((Map)scan.applications.get(0)).get("applicationType")),
                "Explicit process-linked application evidence must bypass only the package sweep exclusion");
            check(scan.libraries.size() == 1 && "42.7.4".equals(((Map)scan.libraries.get(0)).get("version")) && scan.datasources.isEmpty(),
                "Process-linked nested JDBC stays inventoried without claiming a datasource");
            scan.walk(nearby, 0);
            check(scan.applicationsByPath.containsKey(war.getPath()) && scan.runtimes.size() == 1,
                "A nearby directory with the same name prefix remains in scope and shared JDK is deduplicated");
        } finally { CollectorTest.remove(root); }
    }
    static void exactBudgetAndDefaults() throws Exception {
        File root = CollectorTest.temp("sweep-exact-budget").getCanonicalFile();
        try {
            File jar = new File(root, "z-collector/collector.jar"); write(jar, plainJar());
            text(new File(root, "a-file"), "eligible file");
            AssessmentScan scan = scanner(jar, settings()); scan.sweepFiles = scan.filesVisited = scan.maxFiles - 2;
            scan.walk(root, 0);
            check(scan.filesVisited == scan.maxFiles && !scan.partial && !scan.fileLimitReached,
                "Reaching the exact budget with only excluded package files left is complete");
            text(new File(root, "b-file"), "another eligible file");
            AssessmentScan limited = scanner(jar, settings()); limited.sweepFiles = limited.filesVisited = limited.maxFiles - 2; limited.walk(root, 0);
            check(limited.filesVisited == limited.maxFiles && limited.partial && limited.fileLimitReached,
                "An additional eligible file must stop traversal and preserve the partial warning");
            AssessmentScan defaults = scanner(jar, AssessmentScan.map());
            check(defaults.maxFiles == 50000, "Collector fallback file budget must be 50000");
        } finally { CollectorTest.remove(root); }
    }
    static void systemPackageRoots() throws Exception {
        File root = CollectorTest.temp("system-package-roots").getCanonicalFile();
        try {
            File legacy = new File(root, "java-assessment-collector/collector.jar"); write(legacy, plainJar());
            File versioned = new File(root, "java-assessment-collector/releases/release-123/collector.jar"); write(versioned, plainJar());
            File runtime = JdkInventoryTest.home(root, "java-assessment-runtime/jdk6", true);
            for (File jar : new File[]{legacy, versioned}) {
                AssessmentScan scan = scanner(jar, settings());
                check(legacy.getParent().equals(scan.collectorInstallationRoot), "Both layouts resolve the installation root");
                for (String name : new String[]{"java-assessment-collector", "java-assessment-update", "java-assessment-uninstall", "java-assessment-runtime"}) {
                    File directory = new File(root, name); File library = new File(directory, "managers/manager-123/lib/bcprov.jar"); write(library, plainJar());
                    scan.walk(directory, 0); scan.walk(library, 14);
                }
                check(scan.filesVisited == 0 && scan.bytesRead == 0 && scan.deferredArchives.isEmpty() && !scan.partial, "Internal trees consume no file or metadata budget");
                check(scan.addRuntime(runtime,true,"fixture",scan.timestamp,"1.6","fixture",null)==null && scan.runtimes.isEmpty(), "Dedicated runtime never enters installation inventory");
                File nearby = new File(root, "java-assessment-update-app/orders.war"); write(nearby, plainJar()); scan.walk(nearby,0);
                check(scan.filesVisited == 1, "Similar prefix belonging to a customer remains eligible");
                File proc = new File(root,"proc");
                ProcessLaunchTest.process(proc,"98766901",new File(runtime,"bin/java").getPath(),"-cp",new File(root,"java-assessment-update/managers/manager-123/helper.jar").getPath(),"assessment.AgentUpdateWorker");
                scan.discoverProcesses(proc); check(scan.processes.isEmpty(), "Versioned manager is not a customer application");
            }
        } finally {CollectorTest.remove(root);}
    }
    static void separateBudgets() throws Exception {
        File root = CollectorTest.temp("separate-budgets").getCanonicalFile();
        try {
            File jar = new File(root, "z-collector/collector.jar"); write(jar, plainJar());
            File file = new File(root, "eligible"); text(file, "eligible file");
            AssessmentScan scan = scanner(jar, settings()); scan.filesVisited = scan.maxFiles;
            check(!scan.visitLinked(new File(root, "linked")), "Process evidence stops at its own budget");
            scan.walk(file, 0);
            check(scan.sweepFiles == 1 && !scan.fileLimitReached, "Process-linked evidence does not consume the sweep budget");
            AssessmentScan sweep = scanner(jar, settings()); sweep.sweepFiles = sweep.filesVisited = sweep.maxFiles;
            check(sweep.visitLinked(file), "A spent sweep budget leaves process evidence available");
            for (String name : new String[]{".m2", "oradata", "diag", "lost+found"}) text(new File(root, "tree/" + name + "/hidden.war"), "ignored");
            text(new File(root, "tree/diagnostics/kept.war"), "a similar name stays in scope");
            AssessmentScan ignored = scanner(jar, settings()); ignored.walk(new File(root, "tree"), 0);
            check(ignored.applications.size() == 1 && ignored.sweepFiles == 3, "Default ignored directories are skipped by exact name before budgets");
            check(scanner(jar, AssessmentScan.map("maxFiles", 900000)).maxFiles == 500000 && scanner(jar, AssessmentScan.map("maxFiles", 200000)).maxFiles == 200000,
                "maxFiles accepts larger budgets up to 500000");
            AssessmentScan slash = new AssessmentScan(new Properties(), "h", "h", AssessmentScan.map("excludePaths", Arrays.asList("/u01/app/", "/")), jar);
            check(slash.excluded.contains("/u01/app") && slash.excluded.contains("/"), "Exclusions are normalized without a trailing slash");
        } finally { CollectorTest.remove(root); }
    }
    public static void main(String[] args) throws Exception {
        packageExclusion(); nearbyAndProcessLinked(); exactBudgetAndDefaults(); systemPackageRoots(); separateBudgets();
        System.out.println("COLLECTOR_SWEEP_TESTS=PASS own-package-zero-budget nearby-apps process-linked-Boot-JDBC shared-JDK exact-limits default-50000 separate-budgets ignored-dirs");
    }
}
