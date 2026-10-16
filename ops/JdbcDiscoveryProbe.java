package assessment;

import java.io.*;
import java.util.*;

/** Standalone lab probe: no enrollment, transport, service changes or installed configuration reads. */
public final class JdbcDiscoveryProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: JdbcDiscoveryProbe NEW_PRIVATE_OUTPUT_FILE");
        final File output = new File(args[0]);
        if (!output.isAbsolute() || !output.getCanonicalFile().equals(output) || output.exists()) throw new IOException("New absolute regular output required");
        Thread watchdog = new Thread(new Runnable() { public void run() { try { Thread.sleep(240000); } catch (InterruptedException ignored) { return; } System.err.println("PROBE_TIMEOUT"); System.exit(124); } }, "probe-timeout");
        watchdog.setDaemon(true); watchdog.start();
        Map settings = AssessmentScan.map("inspectArchives", Boolean.TRUE, "maxDurationSeconds", 180, "maxFiles", 50000,
            "pauseMillis", 30, "includePaths", new ArrayList(), "excludePaths", Arrays.asList("/proc", "/sys", "/dev", "/tmp", "/opt/java-assessment-collector", "/opt/java-assessment-update", "/opt/java-assessment-runtime"));
        AssessmentScan scan = new AssessmentScan(new Properties(), "isolated-lab-probe", "isolated-lab-probe", settings);
        Map snapshot = scan.scan("isolated-jdbc-validation");
        if (!output.createNewFile()) throw new IOException("Output already exists");
        Writer writer = new OutputStreamWriter(new FileOutputStream(output), "UTF-8");
        try { writer.write(Json.stringify(snapshot)); writer.write("\n"); } finally { writer.close(); }
        System.out.println("PROBE_JAVA=" + System.getProperty("java.version"));
        System.out.println("PROBE_APPLICATIONS=" + scan.applications.size());
        System.out.println("PROBE_SERVERS=" + scan.servers.size());
        System.out.println("PROBE_DATASOURCES=" + scan.datasources.size());
        System.out.println("PROBE_ELAPSED_MS=" + (System.currentTimeMillis() - scan.start));
        System.out.println("PROBE_METADATA_BYTES=" + scan.bytesRead);
        System.out.println("PROBE_RESULT=" + output.getPath());
        System.out.println("PROBE_COMPLETE=ISOLATED_NO_UPLOAD");
    }
}
