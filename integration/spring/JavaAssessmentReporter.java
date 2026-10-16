package example.assessment;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.*;
import javax.sql.DataSource;

/** Optional application-side adapter. No dependencies beyond Java/JDBC; no network listener. */
public final class JavaAssessmentReporter {
    private static final long CACHE_MILLIS = 300000L;
    private final Map<String, Cached> cache = new HashMap<String, Cached>();
    private static final class Cached {
        final DataSource source; final long at; final String json;
        Cached(DataSource source, long at, String json) { this.source = source; this.at = at; this.json = json; }
    }

    /**
     * Call from a dedicated scheduled worker, never a request handler. Disabled calls perform no I/O.
     * The application operator must configure bounded pool acquisition and JDBC/network timeouts.
     * This method cannot impose a portable timeout on arbitrary JDBC driver implementations.
     */
    public synchronized boolean write(boolean explicitlyEnabled, File applicationPath, File output,
                                       Map<String, DataSource> sources, Map<String, String> configurationKeys) throws Exception {
        if (!explicitlyEnabled) return false;
        if (sources == null || sources.size() > 32) throw new IllegalArgumentException("At most 32 explicitly selected datasources");
        if (!applicationPath.isAbsolute() || !output.isAbsolute()) throw new IllegalArgumentException("Absolute paths required");
        File parent = output.getParentFile();
        if (!parent.isDirectory() || !parent.getAbsoluteFile().equals(parent.getCanonicalFile()) || !output.getAbsoluteFile().equals(output.getCanonicalFile()))
            throw new IOException("Precreate a private report directory without symlinks");
        if (output.exists() && !output.isFile()) throw new IOException("Report destination must be regular");
        long pid = pid(), ticks = startTicks(pid), now = System.currentTimeMillis();
        cache.keySet().retainAll(sources.keySet());
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, DataSource> entry : sources.entrySet()) {
            String name = safe(entry.getKey(), 128);
            if (name == null || !name.equals(entry.getKey()) || entry.getValue() == null) throw new IllegalArgumentException("Invalid datasource binding");
            String metadata = cachedMetadata(name, entry.getValue(), now);
            String key = configurationKeys == null ? null : configurationKeys.get(name);
            if (key != null && !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) throw new IllegalArgumentException("Invalid configuration key");
            if (rows.length() > 0) rows.append(',');
            rows.append("{\"name\":").append(quote(name));
            if (key != null) rows.append(",\"configurationKey\":").append(quote(key));
            rows.append(metadata).append('}');
        }
        String json = "{\"schemaVersion\":1,\"applicationPath\":" + quote(applicationPath.getCanonicalPath()) +
            ",\"pid\":" + pid + ",\"processStartTicks\":" + ticks + ",\"generatedAtEpochMillis\":" + System.currentTimeMillis() +
            ",\"datasources\":[" + rows + "]}";
        byte[] bytes = json.getBytes("UTF-8");
        if (bytes.length > 65536) throw new IOException("Report size limit");
        File temporary = File.createTempFile("assessment-", ".json.tmp", parent);
        try {
            if (!temporary.setReadable(false, false) || !temporary.setWritable(false, false) ||
                !temporary.setReadable(true, true) || !temporary.setWritable(true, true)) throw new IOException("Private file permissions required");
            FileOutputStream stream = new FileOutputStream(temporary);
            try { stream.write(bytes); stream.getFD().sync(); } finally { stream.close(); }
            if (!temporary.renameTo(output)) throw new IOException("Atomic report replacement failed");
        } finally { if (temporary.exists()) temporary.delete(); }
        return true;
    }
    String cachedMetadata(String name, DataSource source, long now) throws Exception {
        Cached cached = cache.get(name);
        if (cached == null || cached.source != source || now < cached.at || now - cached.at >= CACHE_MILLIS) {
            String value = metadata(source);
            cached = new Cached(source, now, value); cache.put(name, cached);
        }
        return cached.json;
    }
    static String metadata(DataSource source) throws Exception {
        Connection connection = source.getConnection();
        try {
            DatabaseMetaData db = connection.getMetaData();
            return field("driverName", db.getDriverName()) + field("driverVersion", db.getDriverVersion()) +
                field("databaseType", db.getDatabaseProductName()) + field("databaseVersion", db.getDatabaseProductVersion());
        } finally { connection.close(); }
    }
    static String safe(String value, int max) {
        if (value == null) return null;
        value = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return value.length() > 0 && value.length() <= max && value.matches("[A-Za-z0-9][A-Za-z0-9_.+() -]*") ? value : null;
    }
    static String field(String name, String raw) {
        String value = safe(raw, 256);
        if (name.endsWith("Version") && raw != null) {
            String text = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
            if (text.length() > 256) text = text.substring(0, 256);
            value = text.indexOf("://") < 0 && text.toLowerCase(Locale.ENGLISH).indexOf("jdbc:") < 0 && text.matches("[A-Za-z0-9][A-Za-z0-9_.+() /,:\\[\\]-]*") ? text : null;
        }
        return value == null ? "" : "," + quote(name) + ":" + quote(value);
    }
    static String quote(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i);
            if (c < 32 || c == 127) throw new IllegalArgumentException("Control characters not allowed");
            if (c == '\\' || c == '"') b.append('\\'); b.append(c);
        }
        return b.append('"').toString();
    }
    static long pid() throws IOException {
        String runtime = ManagementFactory.getRuntimeMXBean().getName(); int separator = runtime.indexOf('@');
        if (separator <= 0 || !runtime.substring(0, separator).matches("[0-9]{1,18}")) throw new IOException("Linux PID not available");
        return Long.parseLong(runtime.substring(0, separator));
    }
    static long startTicks(long pid) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader("/proc/self/stat"));
        String stat; try { stat = reader.readLine(); } finally { reader.close(); }
        int end = stat == null ? -1 : stat.lastIndexOf(')');
        if (end < 0 || !stat.startsWith(pid + " (")) throw new IOException("Linux process identity not available");
        String[] parts = stat.substring(end + 1).trim().split("\\s+");
        if (parts.length < 20 || !parts[19].matches("[0-9]{1,18}")) throw new IOException("Linux process start unavailable");
        return Long.parseLong(parts[19]);
    }
}
