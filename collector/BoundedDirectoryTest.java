package assessment;

import java.io.*;
import java.util.*;

/** Real streaming directory reads and injected legacy producer protocol; no application execution. */
public final class BoundedDirectoryTest {
    static int checks;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    static final class Producer extends Process {
        final InputStream input; final int status; volatile boolean destroyed;
        Producer(byte[] bytes, int status) { this.input = new ByteArrayInputStream(bytes); this.status = status; }
        Producer() { status = 0; input = new InputStream() { public int read() throws IOException {
            while (!destroyed) try { Thread.sleep(10); } catch (InterruptedException e) { throw new IOException("closed"); }
            return -1;
        } }; }
        public InputStream getInputStream() { return input; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public int waitFor() { return status; }
        public int exitValue() { return status; }
        public void destroy() { destroyed = true; }
    }
    static BoundedDirectory legacy(File root, String output, int status) throws Exception {
        return BoundedDirectory.legacy(root, System.currentTimeMillis() + 2000, new Producer(output.getBytes("UTF-8"), status));
    }
    static void reject(File root, String output, int status) throws Exception {
        BoundedDirectory reader = legacy(root, output, status);
        try { while (reader.next() != null) { } throw new AssertionError("invalid producer accepted"); }
        catch (IOException expected) { checks++; }
        finally { reader.close(); }
    }
    public static void main(String[] args) throws Exception {
        check("update-v1".equals(AssessmentCollector.capabilityMarker("update-v1\n")), "old installer stays generation 1");
        check("update-v2".equals(AssessmentCollector.capabilityMarker("update-v2\n")), "new installer advertises generation 2");
        check("".equals(AssessmentCollector.capabilityMarker("update-v99")), "unknown marker cannot enable updates");
        File root = CollectorTest.temp("bounded-directory").getCanonicalFile();
        try {
            for (String name : new String[]{"normal", "with space", "with\nnewline"}) new File(root, name).createNewFile();
            BoundedDirectory reader = BoundedDirectory.open(root, System.currentTimeMillis() + 5000);
            Set<String> names = new HashSet<String>(); try { for (File f; (f = reader.next()) != null;) names.add(f.getName()); } finally { reader.close(); }
            check(names.size() == 3 && names.contains("with\nnewline"), "NIO preserves file names");
            reader = legacy(root, new File(root, "with\nnewline").getPath() + '\0' + new File(root, "with space").getPath() + '\0', 0);
            try { check(reader.next().getName().equals("with\nnewline") && reader.next().getName().equals("with space") && reader.next() == null, "NUL framing preserves newlines/spaces"); } finally { reader.close(); }
            reject(root, "outside/secret\0", 0); reject(root, new File(root, "unfinished").getPath(), 0); reject(root, "", 1);
            char[] huge = new char[5000]; Arrays.fill(huge, 'x'); reject(root, new String(huge) + '\0', 0);
            Producer stalled = new Producer(); long start = System.currentTimeMillis();
            reader = BoundedDirectory.legacy(root, start + 100, stalled);
            try { reader.next(); throw new AssertionError("stalled producer accepted"); } catch (IOException expected) { checks++; } finally { reader.close(); }
            check(stalled.destroyed && System.currentTimeMillis() - start < 2000, "deadline destroys stalled producer promptly");
            StringBuilder many = new StringBuilder(); for (int i = 0; i < 1000; i++) many.append(new File(root, "entry" + i).getPath()).append('\0');
            Producer producer = new Producer(many.toString().getBytes("UTF-8"), 0);
            reader = BoundedDirectory.legacy(root, System.currentTimeMillis() + 5000, producer);
            check(reader.next() != null, "bounded queue produces first result"); reader.close(); check(producer.destroyed, "early budget exit closes producer");
            Process real = new ProcessBuilder(new String[]{"/bin/sh", "-c", "exec sleep 30"}).start();
            reader = BoundedDirectory.legacy(root, System.currentTimeMillis() + 100, real);
            try { reader.next(); throw new AssertionError("real stalled process accepted"); } catch (IOException expected) { checks++; } finally { reader.close(); }
            check(real.waitFor() != 0, "real stalled child terminated and reaped");
            Process namesProcess = new ProcessBuilder(new String[]{"/bin/sh", "-c", "printf '%s\\000' \"$1/a b\" \"$1/line\nnext\"", "fixture", root.getPath()}).start();
            reader = BoundedDirectory.legacy(root, System.currentTimeMillis() + 2000, namesProcess);
            try { check(reader.next().getName().equals("a b") && reader.next().getName().equals("line\nnext") && reader.next() == null, "real NUL-delimited child stream"); } finally { reader.close(); }
            AssessmentScan scan = new AssessmentScan(new Properties(), "test", "test", AssessmentScan.map("maxFiles", 100, "pauseMillis", 0)); scan.excluded.clear(); scan.walk(root, 0);
            check(scan.filesVisited == 4 && !scan.partial, "complete small directory retains coverage");
        } finally { CollectorTest.remove(root); }
        System.out.println("BoundedDirectoryTest PASS " + checks + " checks");
    }
}
