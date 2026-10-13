package assessment;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;

/** One-shot agent: heartbeat, retry pending result, poll, bounded scan, deliver. */
public final class AssessmentCollector {
    static final int MAX_RESULT_BYTES = 2 * 1024 * 1024;
    private final Properties config;
    private final AgentTransport transport;
    private final String token;
    private final String hostId;
    private final File spool;

    public AssessmentCollector(Properties config) throws Exception {
        this.config = config;
        transport = new AgentTransport(config);
        token = required(config, "agentToken");
        if (token.length() > 1024 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid agentToken");
        hostId = required(config, "hostId");
        if (hostId.length() > 256) throw new IllegalArgumentException("hostId too long");
        spool = new File(required(config, "spoolDirectory")).getCanonicalFile();
        if (!spool.isDirectory() && !spool.mkdirs()) throw new IOException("Cannot create spool directory");
    }

    static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().length() == 0) throw new IllegalArgumentException("Missing configuration: " + key);
        return value.trim();
    }

    private Map request(String method, String path, String body) throws Exception {
        return transport.request(method, path, body, token);
    }

    private void send(File file) throws Exception {
        String body = AssessmentScan.readFile(file, MAX_RESULT_BYTES);
        Map result = (Map) Json.parse(body);
        Map ack = request("POST", "/api/agent/results", body);
        if (!Boolean.TRUE.equals(ack.get("accepted")) || !result.get("eventId").equals(ack.get("eventId"))) {
            throw new IOException("Server did not acknowledge the same eventId");
        }
        AssessmentScan.atomicWrite(new File(spool, "last-ack.json"), Json.stringify(ack));
        AssessmentScan.atomicWrite(new File(spool, "latest-snapshot.json"), body);
        if (!file.delete()) throw new IOException("Acknowledged result could not be removed from pending queue");
        System.out.println("ASSESSMENT_ACCEPTED=" + result.get("eventId"));
    }

    public void run() throws Exception {
        RandomAccessFile lockFile = new RandomAccessFile(new File(spool, ".collector.lock"), "rw");
        FileLock lock = null;
        try {
            lock = lockFile.getChannel().tryLock();
            if (lock == null) throw new IOException("Another collector is running");
            String hostname = config.getProperty("hostname");
            if (hostname == null || hostname.trim().length() == 0) {
                try { hostname = InetAddress.getLocalHost().getHostName(); }
                catch (Exception e) { hostname = hostId; }
            }
            request("POST", "/api/agent/heartbeat", Json.stringify(AssessmentScan.map(
                "hostId", hostId, "hostname", hostname, "javaVersion", System.getProperty("java.version"), "collectorVersion", AgentTransport.VERSION)));
            File[] pending = spool.listFiles(new FilenameFilter() {
                public boolean accept(File directory, String name) { return name.startsWith("pending-") && name.endsWith(".json"); }
            });
            if (pending == null) throw new IOException("Cannot list pending queue");
            Arrays.sort(pending);
            for (int i = 0; i < pending.length; i++) {
                if (i >= 3) { System.out.println("ASSESSMENT_PENDING_RESULTS_REMAIN"); return; }
                send(pending[i]);
            }
            Map poll = request("GET", "/api/agent/poll?hostId=" + URLEncoder.encode(hostId, "UTF-8"), null);
            Object jobId = poll.get("jobId");
            if (jobId == null) { System.out.println("ASSESSMENT_NO_JOB"); return; }
            if (!(jobId instanceof String) || ((String) jobId).length() > 256) throw new IOException("Invalid leased job ID");
            Object settings = poll.get("settings");
            if (!(settings instanceof Map)) throw new IOException("Missing assessment settings");
            Map snapshot = new AssessmentScan(config, hostId, hostname, (Map) settings).scan((String) jobId);
            String body = Json.stringify(snapshot);
            if (body.getBytes("UTF-8").length > MAX_RESULT_BYTES) throw new IOException("Bounded snapshot exceeds 2 MiB; reduce scan limits");
            File file = new File(spool, "pending-" + snapshot.get("eventId") + ".json");
            AssessmentScan.atomicWrite(file, body);
            send(file);
        } finally {
            if (lock != null) lock.release();
            lockFile.close();
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Usage: AssessmentCollector /absolute/path/collector.properties");
            Properties properties = new Properties();
            InputStream input = new FileInputStream(args[0]);
            try { properties.load(input); } finally { input.close(); }
            new AssessmentCollector(properties).run();
        } catch (Exception error) {
            // No response body, headers, token, or connection credentials are logged.
            System.err.println("ASSESSMENT_FAILED=" + AgentTransport.safeError(error));
            System.exit(1);
        }
    }
}
