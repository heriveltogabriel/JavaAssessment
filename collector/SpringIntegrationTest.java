package assessment;

import java.io.*;
import java.util.*;

/** Synthetic local reports only; no process launches, database connections or HTTP requests. */
public final class SpringIntegrationTest {
    static int assertions;
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static File root, application, report, process;
    static long pid = 12345, ticks = 98765;
    static void write(File file, String value) throws Exception {
        FileOutputStream out = new FileOutputStream(file); try { out.write(value.getBytes("UTF-8")); } finally { out.close(); }
    }
    static AssessmentScan scan(boolean enabled) {
        Properties properties = new Properties();
        if (enabled) { properties.setProperty("spring.integration.orders.applicationPath", application.getPath()); properties.setProperty("spring.integration.orders.file", report.getPath()); }
        AssessmentScan scan = new AssessmentScan(properties, "test-host", "test", AssessmentScan.map("pauseMillis", 0));
        scan.excluded.clear(); return scan;
    }
    static AssessmentScan.ProcessContext context() {
        ProcessLaunch launch = new ProcessLaunch(); launch.jarPath = application.getPath();
        return new AssessmentScan.ProcessContext(launch, pid, "/opt/java", new LinkedHashMap(), AssessmentScan.map("id", "app1", "path", application.getPath(), "name", "orders"));
    }
    static Map data() { return AssessmentScan.map("name", "dataSource", "configurationKey", "spring.datasource", "driverName", "PostgreSQL JDBC Driver", "driverVersion", "42.7.4", "databaseType", "PostgreSQL", "databaseVersion", "16.5", "configuredMaxPoolSize", 20); }
    static Map report(List rows) { return AssessmentScan.map("schemaVersion", 1, "applicationPath", application.getPath(), "pid", pid, "processStartTicks", ticks, "generatedAtEpochMillis", System.currentTimeMillis() - 500, "datasources", rows); }
    static void save(Map value) throws Exception { write(report, Json.stringify(value)); }
    static void enrich(AssessmentScan scan) { SpringIntegration.enrich(scan, context(), process); }
    static void reject(Map value, String why) throws Exception { save(value); AssessmentScan scan = scan(true); enrich(scan); check(scan.datasources.isEmpty() && !scan.warnings.isEmpty(), why); }
    static void acceptanceAndPrivacy() throws Exception {
        Map fields = data(); fields.put("password", "PASSWORD_SECRET"); fields.put("username", "USER_SECRET"); fields.put("jdbcUrl", "jdbc:postgresql://SECRET/database"); fields.put("arbitrary", "ARBITRARY_SECRET");
        Map value = report(Arrays.asList(fields)); value.put("token", "TOKEN_SECRET"); save(value);
        AssessmentScan disabled = scan(false); enrich(disabled); check(disabled.datasources.isEmpty() && disabled.bytesRead == 0, "Integration disabled without mappings");
        AssessmentScan scan = scan(true); enrich(scan); check(scan.datasources.size() == 1, "One report datasource");
        Map row = (Map)scan.datasources.get(0);
        check("16.5".equals(row.get("databaseVersion")) && "42.7.4".equals(row.get("driverVersion")), "Separate database and driver versions");
        check("APPLICATION_REPORTED".equals(row.get("state")) && "application-reported".equals(row.get("evidenceLevel")), "No independent verification claim");
        check(SpringIntegration.REPORTED.equals(row.get("databaseVersionEvidence")), "Version provenance");
        check(((Number)row.get("pid")).longValue() == pid && "app1".equals(row.get("applicationId")), "Process association");
        check(Json.stringify(AssessmentScan.map("rows", scan.datasources, "warnings", scan.warnings)).indexOf("SECRET") < 0, "Unknown fields and credentials discarded");
        enrich(scan); check(scan.datasources.size() == 1, "Repeat import does not duplicate");
        Map unsafe = data(); unsafe.put("driverName", "jdbc:postgresql://SECRET"); unsafe.put("databaseVersion", "16.5\nPASSWORD_SECRET");
        check(SpringIntegration.version("16.5 (Ubuntu 16.5-1) / x86_64: build 1") != null && SpringIntegration.version("jdbc:oracle:thin:secret") == null, "Version suffixes retained without URL-shaped values");
        check(!SpringIntegration.fields(unsafe).containsKey("driverName") && !SpringIntegration.fields(unsafe).containsKey("databaseVersion"), "Control characters and URL-shaped metadata rejected");
    }
    static void bindingAndBounds() throws Exception {
        Map value = report(Arrays.asList(data())); value.put("pid", pid + 1); reject(value, "Wrong PID rejected");
        value = report(Arrays.asList(data())); value.put("processStartTicks", ticks + 1); reject(value, "PID reuse rejected");
        value = report(Arrays.asList(data())); value.put("applicationPath", root + "/other.jar"); reject(value, "Wrong application rejected");
        value = report(Arrays.asList(data())); value.put("generatedAtEpochMillis", System.currentTimeMillis() - 600001L); reject(value, "Expired report rejected");
        value = report(Arrays.asList(data())); value.put("generatedAtEpochMillis", System.currentTimeMillis() + 60000); reject(value, "Future report rejected");
        value = report(Arrays.asList(data())); value.put("schemaVersion", 2); reject(value, "Unknown schema rejected");
        value = report(Arrays.asList(data())); value.put("processStartTicks", Double.valueOf(ticks)); reject(value, "Nonintegral identity type rejected");
        List tooMany = new ArrayList(); for (int i = 0; i < 33; i++) tooMany.add(data()); reject(report(tooMany), "Bounded report rows");
        reject(report(Arrays.asList(data(), data())), "Duplicate names reject whole report");
        Map duplicateKey = data(); duplicateKey.put("name", "other"); reject(report(Arrays.asList(data(), duplicateKey)), "Duplicate config keys reject association");
        char[] huge = new char[65537]; Arrays.fill(huge, ' '); write(report, new String(huge));
        AssessmentScan oversized = scan(true); enrich(oversized); check(oversized.datasources.isEmpty() && oversized.bytesRead == 0, "Oversized report rejected before read");
        save(report(Arrays.asList(data())));
        AssessmentScan exhausted = scan(true); exhausted.bytesRead = ArchiveMetadata.READ_LIMIT; enrich(exhausted);
        check(exhausted.datasources.isEmpty() && exhausted.bytesRead == ArchiveMetadata.READ_LIMIT, "Global metadata budget prevents additional report I/O");
        AssessmentScan excludedReport = scan(true); excludedReport.excluded.add(report.getPath()); enrich(excludedReport);
        check(excludedReport.datasources.isEmpty() && excludedReport.bytesRead == 0, "Explicit mapping does not override report exclusions");
        AssessmentScan excludedApp = scan(true); excludedApp.excluded.add(application.getPath()); enrich(excludedApp);
        check(excludedApp.datasources.isEmpty() && excludedApp.bytesRead == 0, "Explicit mapping does not override application exclusions");
        AssessmentScan directory = scan(true); directory.config.setProperty("spring.integration.orders.file", process.getPath()); enrich(directory);
        check(directory.datasources.isEmpty(), "Directories are not report files");
        AssessmentScan relative = scan(true); relative.config.setProperty("spring.integration.orders.file", "report.json"); enrich(relative);
        check(relative.datasources.isEmpty(), "Relative report paths rejected");
        AssessmentScan network = scan(true); network.networkMounts.add(root.getPath()); enrich(network);
        check(network.datasources.isEmpty(), "Known network mounts are not read");
        AssessmentScan unrelated = scan(true); unrelated.config.setProperty("spring.integration.orders.applicationPath", root + "/other.jar"); enrich(unrelated);
        check(unrelated.datasources.isEmpty() && unrelated.bytesRead == 0, "Configured application must match observed process");
        String stat = AssessmentScan.readFile(new File(process, "stat"), 8192); write(new File(process, "stat"), "12345 (bad) S");
        AssessmentScan malformed = scan(true); enrich(malformed); check(malformed.datasources.isEmpty(), "Missing process start rejected"); write(new File(process, "stat"), stat);
        write(report, "{\"schemaVersion\":1,\"schemaVersion\":1}"); AssessmentScan duplicate = scan(true); enrich(duplicate); check(duplicate.datasources.isEmpty(), "Duplicate JSON keys rejected");
    }
    static Map passive(String key, long owner) {
        return AssessmentScan.map("id", "passive1", "sourcePath", application.getPath(), "configurationKey", key, "name", "orders datasource", "pid", owner, "state", "CONFIGURED", "driverVersion", "42.6");
    }
    static void merging() throws Exception {
        save(report(Arrays.asList(data()))); AssessmentScan scan = scan(true); Map passive = passive("spring.datasource", pid); scan.datasources.add(passive); enrich(scan);
        check(scan.datasources.size() == 1 && "passive1".equals(((Map)scan.datasources.get(0)).get("id")), "Unambiguous default enriches existing row");
        check("dataSource".equals(passive.get("name")) && "42.7.4".equals(passive.get("driverVersion")), "Application name and loaded driver supersede configuration");
        AssessmentScan otherPid = scan(true); otherPid.datasources.add(passive("spring.datasource", pid + 1)); enrich(otherPid);
        check(otherPid.datasources.size() == 2 && "CONFIGURED".equals(((Map)otherPid.datasources.get(0)).get("state")), "Do not enrich another process sharing same JAR");
        AssessmentScan shared = scan(true); Map row = passive("spring.datasource", pid); row.put("associations", Arrays.asList(AssessmentScan.map("pid", pid), AssessmentScan.map("pid", pid + 1))); shared.datasources.add(row); enrich(shared);
        check(shared.datasources.size() == 2 && "CONFIGURED".equals(row.get("state")), "Shared ambiguous passive row not incorrectly enriched");
        Map second = data(); second.put("name", "audit"); second.put("configurationKey", "app.audit"); second.put("databaseVersion", "15.2");
        save(report(Arrays.asList(data(), second))); AssessmentScan multiple = scan(true); enrich(multiple);
        check(multiple.datasources.size() == 2 && "15.2".equals(((Map)multiple.datasources.get(1)).get("databaseVersion")), "Multiple named datasource versions remain distinct");
    }
    public static void main(String[] args) throws Exception {
        root = CollectorTest.temp("spring-integration").getCanonicalFile(); application = new File(root, "orders.jar"); report = new File(root, "report.json"); process = new File(root, "proc"); process.mkdir(); write(application, "fixture");
        StringBuilder stat = new StringBuilder(pid + " (name with ) parentheses) S"); for (int i = 1; i < 19; i++) stat.append(" 0"); stat.append(' ').append(ticks); write(new File(process, "stat"), stat.toString());
        try { acceptanceAndPrivacy(); bindingAndBounds(); merging(); }
        finally { CollectorTest.remove(root); }
        System.out.println("SPRING_INTEGRATION_TESTS=PASS assertions=" + assertions + " disabled-default identity freshness bounds privacy association");
    }
}
