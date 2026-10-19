package assessment;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Passive, bounded reader for the HotSpot PerfData file (hsperfdata) of a live JVM.
 * The file is never mapped or written; only allowlisted string counters are retained.
 */
final class HsPerfData {
    static final int READ_LIMIT = 512 * 1024;
    // HotSpot caps string constants at PerfMaxStringConstLength (default 1024 bytes).
    static final int STRING_CAP = 1024;
    static final List<String> KEYS = Arrays.asList("java.property.java.version", "java.property.java.vendor",
        "java.property.java.vm.version", "java.property.java.vm.vendor", "java.property.java.home",
        "java.property.java.class.path", "java.rt.vmArgs", "sun.rt.javaCommand");

    /** Executable and perf-data paths a live process has mapped, as seen inside its own mount namespace. */
    static final class Mapping { String executable, perfData; boolean replaced; }

    static Mapping mapping(String maps) {
        if (maps == null) return null;
        Mapping result = new Mapping();
        for (String line : maps.split("\n")) {
            String[] columns = line.trim().split("\\s+", 6);
            if (columns.length < 6 || !columns[5].startsWith("/")) continue;
            String path = columns[5]; boolean deleted = path.endsWith(" (deleted)");
            if (deleted) path = path.substring(0, path.length() - 10);
            if (path.endsWith("/bin/java")) { if (result.executable == null) result.executable = path; result.replaced |= deleted; }
            else if (path.endsWith("/libjvm.so")) result.replaced |= deleted;
            else if (result.perfData == null && !deleted && path.matches(".*/hsperfdata_[^/]+/[0-9]+")) result.perfData = path;
        }
        return result;
    }

    /** Reads the file only when it is a regular file, not a symlink, and owned by the process user. */
    static Map<String,String> read(AssessmentScan scan, File file, Long uid) {
        if (uid == null || !scan.available() || !owned(file, uid.longValue())) return null;
        if (scan.bytesRead >= ArchiveMetadata.READ_LIMIT) { scan.warn("Metadata read budget of 32 MiB reached"); return null; }
        // shortcut: ownership is checked before open, so a same-user swap between check and open is possible; upgrade with O_NOFOLLOW channels when Java 6 support ends.
        InputStream input = null;
        try {
            input = new FileInputStream(file); byte[] data = new byte[READ_LIMIT]; int length = 0;
            while (length < data.length && scan.available()) {
                int count = input.read(data, length, data.length - length); if (count < 0) break;
                length += count; scan.bytesRead += count;
            }
            if (length == data.length && input.read() >= 0) return null;
            return parse(data, length);
        } catch (IOException e) { return null; }
        finally { if (input != null) try { input.close(); } catch (IOException e) { } }
    }

    static boolean owned(File file, long uid) {
        try {
            Class path = Class.forName("java.nio.file.Path"), option = Class.forName("java.nio.file.LinkOption");
            Object options = Array.newInstance(option, 1); Array.set(options, 0, option.getField("NOFOLLOW_LINKS").get(null));
            Object value = File.class.getMethod("toPath").invoke(file);
            Map attributes = (Map) Class.forName("java.nio.file.Files").getMethod("readAttributes", path, String.class, options.getClass())
                .invoke(null, value, "unix:uid,isRegularFile", options);
            return Boolean.TRUE.equals(attributes.get("isRegularFile")) && attributes.get("uid") instanceof Number && ((Number) attributes.get("uid")).longValue() == uid;
        } catch (ClassNotFoundException legacy) { return ownedByStat(file, uid); }
        catch (Exception e) { return false; }
    }

    /** Java 6 has no lstat; coreutils stat without -L reports the link itself, never its target. */
    static boolean ownedByStat(File file, long uid) {
        File stat = new File("/usr/bin/stat"); if (!stat.isFile()) stat = new File("/bin/stat");
        if (!stat.isFile()) return false;
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(stat.getPath(), "-c", "%u %F", "--", file.getAbsolutePath());
            builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin"); builder.environment().put("LC_ALL", "C");
            process = builder.start(); process.getOutputStream().close();
            long stop = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < stop) {
                try { return process.exitValue() == 0 && (uid + " regular file").equals(AssessmentScan.readStream(process.getInputStream(), 256).trim()); }
                catch (IllegalThreadStateException running) { Thread.sleep(20); }
            }
        } catch (Exception e) { }
        finally { if (process != null) process.destroy(); }
        return false;
    }

    static int integer(byte[] data, int offset, boolean little) {
        if (offset < 0 || offset + 4 > data.length) return -1;
        int a = data[offset] & 255, b = data[offset + 1] & 255, c = data[offset + 2] & 255, d = data[offset + 3] & 255;
        return little ? a | b << 8 | c << 16 | d << 24 : d | c << 8 | b << 16 | a << 24;
    }
    static int terminator(byte[] data, int start, int end) {
        for (int i = start; i < end; i++) if (data[i] == 0) return i;
        return end;
    }

    /** Supports PerfData 1.0 (JDK 1.4.2) and 2.0 (JDK 5+). Malformed input yields what was parsed before the fault. */
    static Map<String,String> parse(byte[] data, int length) {
        if (length < 32 || length > data.length || (data[0] & 255) != 0xca || (data[1] & 255) != 0xfe || (data[2] & 255) != 0xc0 || (data[3] & 255) != 0xc0) return null;
        boolean little = data[4] == 1; int major = data[5];
        int offset, count, end = length;
        if (major == 2) { offset = integer(data, 24, little); count = integer(data, 28, little); }
        else if (major == 1) { offset = 24; count = Integer.MAX_VALUE; end = Math.min(length, Math.max(0, integer(data, 8, little))); }
        else return null;
        Map<String,String> result = new LinkedHashMap<String,String>();
        try {
            for (int i = 0; i < count && i < 8192 && offset >= 24 && offset + 20 <= end; i++) {
                int entry = integer(data, offset, little), vector = integer(data, offset + 8, little);
                if (entry <= 0 || entry > end - offset) break;
                int name, value;
                if (major == 2) { name = offset + integer(data, offset + 4, little); value = offset + integer(data, offset + 16, little); }
                else { name = offset + 16; value = name + integer(data, offset + 4, little); }
                int limit = offset + entry;
                if (name > offset && name < limit && data[offset + 12] == 'B' && data[offset + 14] == 5 && vector > 0 && value > offset && value <= limit - vector) {
                    String key = new String(data, name, terminator(data, name, limit) - name, "UTF-8");
                    if (KEYS.contains(key) && !result.containsKey(key)) {
                        int stop = terminator(data, value, value + vector);
                        String text = new String(data, value, stop - value, "UTF-8");
                        if (stop - value >= STRING_CAP && stop - value == vector - 1) text = capped(key, text);
                        if (text != null && text.length() > 0) result.put(key, text);
                    }
                }
                offset += entry;
            }
        } catch (UnsupportedEncodingException e) { return null; }
        return result;
    }
    /** A value at the HotSpot cap may be cut mid-element: keep only whole classpath entries or arguments. */
    static String capped(String key, String value) {
        int separator = key.equals("java.property.java.class.path") ? value.lastIndexOf(':') : key.equals("java.rt.vmArgs") || key.equals("sun.rt.javaCommand") ? value.lastIndexOf(' ') : -1;
        return separator > 0 ? value.substring(0, separator) : null;
    }
}
