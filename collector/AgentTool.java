package assessment;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;

/** Bootstrap and diagnostics only; never scans, polls jobs, or prints credentials. */
public final class AgentTool {
    static Properties load(File file) throws Exception {
        if (!file.isFile() || file.length() > 32768) throw new IOException("Configuration missing or oversized");
        Properties config = new Properties(); InputStream in = new FileInputStream(file);
        try { config.load(in); } finally { in.close(); }
        return config;
    }
    static String hostname(Properties config) {
        String name = config.getProperty("hostname");
        if (name == null || name.trim().length() == 0) try { name = InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { name = "unknown-host"; }
        name = name.trim();
        if (name.length() > 200 || name.matches(".*[\\p{Cntrl}].*")) throw new IllegalArgumentException("Invalid hostname");
        return name;
    }
    static void ownerOnly(File file) throws IOException {
        if (!file.setReadable(false, false) || !file.setWritable(false, false) || !file.setExecutable(false, false)
                || !file.setReadable(true, true) || !file.setWritable(true, true)) throw new IOException("Cannot protect local credential file");
    }
    static void writePrivate(File target, String value) throws Exception {
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory()) throw new IOException("Parent directory must already exist");
        File temporary = File.createTempFile(".assessment-", ".tmp", parent); ownerOnly(temporary);
        try {
            FileOutputStream out = new FileOutputStream(temporary);
            try { out.write(value.getBytes("UTF-8")); out.getFD().sync(); } finally { out.close(); }
            if (!temporary.renameTo(target)) throw new IOException("Cannot persist collector state");
        } finally { temporary.delete(); }
    }
    static void saveConfig(File target, Properties properties) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); properties.store(out, "Java assessment collector; keep this file private");
        writePrivate(target, new String(out.toByteArray(), "ISO-8859-1"));
    }
    static Map heartbeat(Properties config, AgentTransport transport) throws Exception {
        String hostId = AssessmentCollector.required(config, "hostId");
        Map ack = transport.request("POST", "/api/agent/heartbeat", Json.stringify(AssessmentScan.map(
            "hostId", hostId, "hostname", hostname(config), "javaVersion", System.getProperty("java.version"),
            "collectorVersion", AgentTransport.VERSION, "updateCapability", AssessmentCollector.updateCapability(config))), AssessmentCollector.required(config, "agentToken"));
        if (!Boolean.TRUE.equals(ack.get("accepted"))) throw new IOException("Heartbeat was not acknowledged");
        return ack;
    }
    static void health(AgentTransport transport) throws Exception {
        Map ack = transport.request("GET", "/health", null, null);
        if (!"ok".equals(ack.get("status"))) throw new IOException("Dashboard health check failed");
    }
    static void register(File bootstrapFile, File finalFile) throws Exception {
        if (finalFile.exists()) {
            Properties existing = load(finalFile); AgentTransport transport = new AgentTransport(existing);
            health(transport); heartbeat(existing, transport);
            System.out.println("ASSESSMENT_REGISTRATION=PRESERVED"); return;
        }
        Properties bootstrap = load(bootstrapFile);
        File installationFile = new File(AssessmentCollector.required(bootstrap, "installationIdPath"));
        File lockPath = new File(installationFile.getAbsolutePath() + ".lock");
        RandomAccessFile lockFile = new RandomAccessFile(lockPath, "rw"); ownerOnly(lockPath); FileLock lock = null;
        try {
            lock = lockFile.getChannel().tryLock();
            if (lock == null) throw new IOException("Another registration is running");
            // Repeat the check under the installation lock so retries never replace a saved credential.
            if (finalFile.exists()) {
                Properties existing = load(finalFile); AgentTransport transport = new AgentTransport(existing);
                health(transport); heartbeat(existing, transport); System.out.println("ASSESSMENT_REGISTRATION=PRESERVED"); return;
            }
            String installationId;
            if (installationFile.exists()) installationId = AssessmentScan.readFile(installationFile, 128).trim();
            else { installationId = UUID.randomUUID().toString(); writePrivate(installationFile, installationId + "\n"); }
            if (!installationId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
                throw new IllegalArgumentException("Invalid persisted installation identity");
            AgentTransport transport = new AgentTransport(bootstrap); health(transport);
            System.out.println("ASSESSMENT_TLS=VERIFIED");
            Map ack = transport.request("POST", "/api/agent/register", Json.stringify(AssessmentScan.map(
                "installationId", installationId, "hostname", hostname(bootstrap), "collectorVersion", AgentTransport.VERSION,
                "javaVersion", System.getProperty("java.version"), "osName", System.getProperty("os.name"),
                "architecture", System.getProperty("os.arch"))), AssessmentCollector.required(bootstrap, "enrollmentToken"));
            if (!Boolean.TRUE.equals(ack.get("registered")) || !(ack.get("hostId") instanceof String)
                    || !(ack.get("agentToken") instanceof String)) throw new IOException("Registration response missing identity");
            String hostId = (String) ack.get("hostId"), credential = (String) ack.get("agentToken");
            if (!hostId.matches("[A-Za-z0-9_.:-]{1,256}") || !credential.matches("[A-Za-z0-9_.~+/=-]{16,1024}"))
                throw new IOException("Registration response contains invalid identity");
            Properties config = new Properties();
            // Keep the verified origin from the administrator's bootstrap, never follow a response URL.
            config.setProperty("serverUrl", transport.server); config.setProperty("hostId", hostId); config.setProperty("agentToken", credential);
            config.setProperty("spoolDirectory", AssessmentCollector.required(bootstrap, "spoolDirectory"));
            for (String name : new String[] {"hostname", "tlsProvider", "tlsCaFile", "snapshotRoot", "logDirectory"})
                if (bootstrap.getProperty(name) != null) config.setProperty(name, bootstrap.getProperty(name));
            Object settings = ack.get("settings");
            if (settings instanceof Map && ((Map) settings).get("includePaths") instanceof List)
                config.setProperty("cachedIncludePaths", Json.stringify(((Map) settings).get("includePaths")));
            saveConfig(finalFile, config);
            System.out.println("ASSESSMENT_REGISTRATION=CONFIRMED");
        } finally { if (lock != null) lock.release(); lockFile.close(); }
    }
    static Map permissionReport(Properties config, Map settings) throws Exception {
        Object paths = settings == null ? null : settings.get("includePaths");
        if (!(paths instanceof List)) {
            Object cached = Json.parse(config.getProperty("cachedIncludePaths", "[]"));
            paths = cached instanceof List ? cached : new ArrayList();
        }
        if (((List) paths).size() > 64) throw new IOException("Too many configured assessment paths");
        List entries = new ArrayList(); int readable = 0, denied = 0;
        for (Object item : (List) paths) {
            if (!(item instanceof String)) throw new IOException("Invalid assessment path");
            String path = (String) item;
            if (path.length() > 4096 || !new File(path).isAbsolute()) throw new IOException("Invalid assessment path");
            File file = new File(path);
            boolean accessible = file.isDirectory() ? file.canRead() && file.canExecute() : file.isFile() && file.canRead();
            if (accessible) readable++; else denied++;
            entries.add(AssessmentScan.map("path", path, "exists", file.exists(), "topLevelReadable", accessible));
        }
        return AssessmentScan.map("checkedAt", AssessmentScan.now(), "scope", "configured path entry only; descendants checked during assessment",
            "readablePaths", readable, "inaccessiblePaths", denied, "paths", entries);
    }
    static void diagnose(File configFile) throws Exception {
        Properties config = load(configFile); AgentTransport transport = new AgentTransport(config);
        health(transport); System.out.println("ASSESSMENT_CONNECTION=VERIFIED");
        heartbeat(config, transport); System.out.println("ASSESSMENT_HEARTBEAT=CONFIRMED");
        Map response = transport.request("GET", "/api/agent/settings?hostId=" + URLEncoder.encode(AssessmentCollector.required(config, "hostId"), "UTF-8"),
            null, AssessmentCollector.required(config, "agentToken"));
        if (!(response.get("settings") instanceof Map)) throw new IOException("Settings response is missing");
        Map report = permissionReport(config, (Map) response.get("settings"));
        File defaultLogs = new File(configFile.getAbsoluteFile().getParentFile().getParentFile(), "logs");
        File logs = new File(config.getProperty("logDirectory", defaultLogs.getPath()));
        if (!logs.isDirectory() && !logs.mkdirs()) throw new IOException("Cannot create diagnostic log directory");
        writePrivate(new File(logs, "permission-check.json"), Json.stringify(report));
        System.out.println("ASSESSMENT_READABLE_PATHS=" + report.get("readablePaths"));
        System.out.println("ASSESSMENT_INACCESSIBLE_PATHS=" + report.get("inaccessiblePaths"));
        List events = Arrays.asList(AssessmentScan.map("eventId", UUID.randomUUID().toString(), "time", AssessmentScan.now(),
            "stage", "diagnostic", "status", ((Number) report.get("inaccessiblePaths")).intValue() > 0 ? "WARNING" : "OK",
            "message", "Authentication and dashboard connectivity verified. Configured path access checked; descendants are checked during collection."));
        events(config, events, transport);
        System.out.println("ASSESSMENT_DIAGNOSTIC=PASS");
    }
    static void events(Properties config, Object value, AgentTransport transport) throws Exception {
        if (value instanceof Map) value = ((Map) value).get("events");
        if (!(value instanceof List) || ((List) value).size() > 30) throw new IllegalArgumentException("Expected at most 30 installation events");
        List sanitized = new ArrayList();
        Set stages = new HashSet(Arrays.asList("system", "java", "permissions", "tls", "registration", "scheduler", "heartbeat", "complete", "diagnostic", "failure"));
        Set statuses = new HashSet(Arrays.asList("INFO", "OK", "WARNING", "ERROR"));
        String token = AssessmentCollector.required(config, "agentToken");
        for (Object item : (List) value) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("Invalid installation event");
            Map event = (Map) item; Map clean = new LinkedHashMap();
            for (String key : new String[] {"eventId", "time", "stage", "status", "message"}) {
                Object text = event.get(key);
                if (!(text instanceof String) || ((String) text).length() == 0 || ((String) text).length() > (key.equals("message") ? 300 : 80)
                        || ((String) text).matches(".*[\\p{Cntrl}].*") || ((String) text).indexOf(token) >= 0)
                    throw new IllegalArgumentException("Invalid or sensitive event content");
                clean.put(key, text);
            }
            if (!stages.contains(clean.get("stage")) || !statuses.contains(clean.get("status"))) throw new IllegalArgumentException("Unknown installation stage or status");
            sanitized.add(clean);
        }
        Map ack = transport.request("POST", "/api/agent/install-events", Json.stringify(AssessmentScan.map("hostId", AssessmentCollector.required(config, "hostId"), "events", sanitized)), token);
        if (!Boolean.TRUE.equals(ack.get("accepted"))) throw new IOException("Installation events were not acknowledged");
    }
    public static void main(String[] args) {
        try {
            if (args.length == 3 && args[0].equals("register")) register(new File(args[1]), new File(args[2]));
            else if (args.length == 2 && args[0].equals("diagnose")) diagnose(new File(args[1]));
            else if (args.length == 3 && args[0].equals("events")) {
                Properties config = load(new File(args[1]));
                events(config, Json.parse(AssessmentScan.readFile(new File(args[2]), 32768)), new AgentTransport(config));
                System.out.println("ASSESSMENT_INSTALL_EVENTS=CONFIRMED");
            } else throw new IllegalArgumentException("Usage: AgentTool register bootstrap final-config | diagnose config | events config events-json");
        } catch (Exception error) {
            System.err.println("ASSESSMENT_TOOL_FAILED=" + AgentTransport.safeError(error)); System.exit(1);
        }
    }
}
