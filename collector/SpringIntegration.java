package assessment;

import java.io.*;
import java.util.*;

/** Explicit local application reports only. This reader never opens a JDBC or HTTP connection. */
final class SpringIntegration {
    static final int MAX_BYTES = 65536, MAX_ROWS = 32, MAX_MAPPINGS = 32;
    static final long MAX_AGE = 600000L;
    static final String PREFIX = "spring.integration.";
    static final String REPORTED = "Reported by application JDBC metadata; not independently verified";

    static void enrich(AssessmentScan scan, AssessmentScan.ProcessContext context, File processDirectory) {
        if (context.application == null || processDirectory == null) return;
        Set<String> mappings = new TreeSet<String>();
        for (Object raw : scan.config.keySet()) {
            String key = String.valueOf(raw);
            if (key.matches("spring\\.integration\\.[A-Za-z0-9_-]{1,64}\\.applicationPath")) {
                if (mappings.size() >= MAX_MAPPINGS) { scan.warn("Application integration mapping limit reached"); break; }
                mappings.add(key.substring(PREFIX.length(), key.length() - ".applicationPath".length()));
            }
        }
        Set<String> seen = new HashSet<String>();
        for (String mapping : mappings) {
            if (System.currentTimeMillis() > scan.deadline) return;
            String applicationPath = scan.config.getProperty(PREFIX + mapping + ".applicationPath");
            String reportPath = scan.config.getProperty(PREFIX + mapping + ".file");
            try {
                if (!absolute(applicationPath) || !matchesApplication(context, applicationPath)) continue;
                if (scan.excluded(applicationPath) || scan.excluded(new File(applicationPath).getCanonicalPath())) throw new IOException("Excluded application");
                if (!absolute(reportPath)) throw new IOException("Invalid report path");
                File file = new File(reportPath);
                if (scan.excluded(reportPath) || scan.excluded(file.getCanonicalPath())) throw new IOException("Excluded report");
                if (!file.getAbsoluteFile().equals(file.getCanonicalFile()) || !file.isFile() || file.length() < 2 || file.length() > MAX_BYTES)
                    throw new IOException("Report must be a bounded regular file without symlinks");
                for (String mount : scan.networkMounts) if (AssessmentScan.under(file.getPath(), mount)) throw new IOException("Network report path rejected");
                if (!seen.add(file.getPath())) continue;
                long ticks = startTicks(scan, processDirectory, context.pid);
                String raw = scan.metadataText(file, MAX_BYTES, false);
                if (raw == null) throw new IOException("Report read failed or budget exhausted");
                Object parsed = Json.parse(raw, MAX_BYTES);
                if (!(parsed instanceof Map)) throw new IOException("Invalid report");
                Map report = (Map)parsed;
                long now = System.currentTimeMillis(), generated = integer(report.get("generatedAtEpochMillis"));
                if (integer(report.get("schemaVersion")) != 1 || integer(report.get("pid")) != context.pid || ticks <= 0 ||
                    integer(report.get("processStartTicks")) != ticks || generated <= 0 || generated > now || now - generated > MAX_AGE ||
                    !absolute(report.get("applicationPath")) || !AssessmentScan.samePath(applicationPath, (String)report.get("applicationPath")))
                    throw new IOException("Stale or unrelated report");
                Object rows = report.get("datasources");
                if (!(rows instanceof List) || ((List)rows).size() > MAX_ROWS) throw new IOException("Invalid row count");
                // Check PID reuse once more after reading. Never attach a previous process's report.
                if (startTicks(scan, processDirectory, context.pid) != ticks) throw new IOException("Process identity changed");
                List<Map> accepted = new ArrayList<Map>(); Set<String> names = new HashSet<String>(); Set<String> keys = new HashSet<String>();
                for (Object row : (List)rows) {
                    if (!(row instanceof Map)) throw new IOException("Invalid row");
                    Map fields = fields((Map)row); String name = (String)fields.get("name");
                    if (name == null || !names.add(name)) throw new IOException("Missing or duplicate datasource name");
                    String key = (String)fields.get("configurationKey");
                    if (key != null && !keys.add(key)) throw new IOException("Ambiguous configuration association");
                    accepted.add(fields);
                }
                for (Map fields : accepted) add(scan, context, applicationPath, fields, generated);
            } catch (IOException e) { scan.warn("Application integration report unavailable, stale, invalid or not bound to the live process"); }
              catch (IllegalArgumentException e) { scan.warn("Application integration report contains invalid JSON"); }
              catch (SecurityException e) { scan.warn("Application integration report is not readable"); }
        }
    }

    static boolean absolute(Object value) {
        if (!(value instanceof String)) return false;
        String path = (String)value;
        return path.length() <= 4096 && path.indexOf('\n') < 0 && path.indexOf('\r') < 0 && path.indexOf('\000') < 0 && new File(path).isAbsolute();
    }
    static boolean matchesApplication(AssessmentScan.ProcessContext context, String path) {
        Object known = context.application.get("path");
        if (known instanceof String && AssessmentScan.samePath(path, (String)known)) return true;
        if (context.launch.jarPath != null) return AssessmentScan.samePath(path, context.launch.jarPath);
        for (String entry : context.launch.classpath) if (AssessmentScan.samePath(path, entry)) return true;
        return false;
    }
    static long integer(Object value) { return value instanceof Long || value instanceof Integer ? ((Number)value).longValue() : -1; }
    static long startTicks(AssessmentScan scan, File processDirectory, long pid) throws IOException {
        String stat = scan.metadataText(new File(processDirectory, "stat"), 8192, false);
        if (stat == null) throw new IOException("Process identity unavailable or budget exhausted");
        int end = stat.lastIndexOf(')');
        if (!stat.startsWith(String.valueOf(pid) + " (") || end < 0) throw new IOException("Invalid process identity");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20 || !fields[19].matches("[0-9]{1,18}")) throw new IOException("Missing start time");
        return Long.parseLong(fields[19]);
    }
    static String label(Object value, int maximum) {
        if (!(value instanceof String)) return null;
        String text = ((String)value).trim();
        return text.length() > 0 && text.length() <= maximum && text.matches("[A-Za-z0-9][A-Za-z0-9_.+() -]*") ? text : null;
    }
    static String version(Object value) {
        if (!(value instanceof String)) return null;
        String text = ((String)value).trim();
        if (text.length() == 0 || text.length() > 256 || text.indexOf("://") >= 0 || text.toLowerCase(Locale.ENGLISH).indexOf("jdbc:") >= 0) return null;
        return text.matches("[A-Za-z0-9][A-Za-z0-9_.+() /,:\\[\\]-]*") ? text : null;
    }
    static Map fields(Map input) {
        Map out = new LinkedHashMap();
        for (String field : new String[]{"name", "driverName", "driverVersion", "databaseType", "databaseVersion"}) {
            String value = field.endsWith("Version") ? version(input.get(field)) : label(input.get(field), field.equals("name") ? 128 : 256);
            if (value != null) out.put(field, value);
        }
        Object rawKey = input.get("configurationKey");
        if (rawKey instanceof String && ((String)rawKey).matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) out.put("configurationKey", rawKey);
        long pool = integer(input.get("configuredMaxPoolSize"));
        if (pool > 0 && pool <= 100000) out.put("configuredMaxPoolSize", Long.valueOf(pool));
        return out;
    }
    static boolean belongs(Map row, AssessmentScan.ProcessContext context, String path) {
        Object source = row.get("sourcePath");
        if (!(source instanceof String) || !AssessmentScan.samePath(path, (String)source)) return false;
        if (row.get("associations") instanceof List) {
            List associations = (List)row.get("associations");
            if (associations.size() != 1 || !(associations.get(0) instanceof Map)) return false;
            return integer(((Map)associations.get(0)).get("pid")) == context.pid;
        }
        return integer(row.get("pid")) == context.pid;
    }
    static void add(AssessmentScan scan, AssessmentScan.ProcessContext context, String path, Map fields, long generated) {
        Map target = null; int matches = 0;
        for (Object raw : scan.datasources) if (raw instanceof Map) {
            Map row = (Map)raw;
            if (!belongs(row, context, path)) continue;
            Object key = fields.get("configurationKey");
            boolean sameKey = key != null && key.equals(row.get("configurationKey"));
            // Compatibility with the former single packaged default datasource representation.
            if ("spring.datasource".equals(key) && row.get("configurationKey") == null && "CONFIGURED".equals(row.get("state")))
                sameKey = scan.configuredDatasourceRows.get("spring-default:" + path) == row;
            if (sameKey || fields.get("name").equals(row.get("name"))) { matches++; target = row; }
        }
        if (matches != 1) {
            if (scan.datasources.size() >= 512) { scan.warn("Datasource row limit reached"); return; }
            target = AssessmentScan.map("id", AssessmentScan.id("datasource", scan.hostId + "reported:" + path + ":" + context.pid + ":" + fields.get("name")),
                "hostId", scan.hostId, "sourcePath", path, "pid", Long.valueOf(context.pid),
                "applicationId", context.application.get("id"), "applicationName", context.application.get("name"),
                "associations", Arrays.asList(AssessmentScan.map("pid", Long.valueOf(context.pid), "applicationId", context.application.get("id"), "applicationName", context.application.get("name"))));
            scan.datasources.add(target);
        }
        target.putAll(fields);
        if (fields.containsKey("configurationKey")) target.put("datasourceKey", fields.get("configurationKey"));
        target.put("state", "APPLICATION_REPORTED"); target.put("evidenceLevel", "application-reported");
        target.put("evidence", "Opt-in local application report bound to live process; connectivity and metadata not independently verified by collector");
        target.put("scanScope", "process-linked"); target.put("observedAt", scan.timestamp); target.put("applicationReportedAtEpochMillis", Long.valueOf(generated));
        if (fields.containsKey("databaseVersion")) target.put("databaseVersionEvidence", REPORTED);
        if (fields.containsKey("driverVersion")) target.put("driverVersionEvidence", REPORTED);
    }
}
