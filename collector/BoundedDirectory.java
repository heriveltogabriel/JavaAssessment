package assessment;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Streaming enumeration: never materialize an unbounded directory in the collector heap. */
final class BoundedDirectory implements Closeable {
    private final long deadline;
    private Closeable stream;
    private Iterator iterator;
    private Method toFile;
    private Process process;
    private Thread reader;
    private final BlockingQueue<Object> entries = new ArrayBlockingQueue<Object>(8);
    private static final Object END = new Object();
    private volatile boolean closed;

    private BoundedDirectory(long deadline) { this.deadline = deadline; }

    static BoundedDirectory open(File directory, long deadline) throws IOException {
        BoundedDirectory result = new BoundedDirectory(deadline);
        try {
            Class path = Class.forName("java.nio.file.Path");
            Object value = File.class.getMethod("toPath").invoke(directory);
            Object directoryStream = Class.forName("java.nio.file.Files").getMethod("newDirectoryStream", path).invoke(null, value);
            result.stream = (Closeable)directoryStream;
            result.toFile = path.getMethod("toFile");
            result.iterator = ((Iterable)directoryStream).iterator();
            return result;
        } catch (ClassNotFoundException legacy) {
            if (!"Linux".equals(System.getProperty("os.name"))) throw new IOException("Legacy directory enumeration requires Linux");
            // No command interpolation, no recursive walk, and no following directory symlinks.
            // GNU find may allocate before emitting output on older Linux; bound that child too.
            String command = "ulimit -v 65536 || exit 70; ulimit -t 5 || exit 70; exec /usr/bin/find \"$1\" -mindepth 1 -maxdepth 1 -print0 2>/dev/null";
            ProcessBuilder builder = new ProcessBuilder(new String[]{"/bin/sh", "-c", command, "assessment-directory", directory.getAbsolutePath()});
            builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin"); builder.environment().put("LC_ALL", "C");
            return legacy(directory, deadline, builder.start());
        } catch (Exception error) {
            result.close(); throw new IOException("Directory cannot be enumerated", error);
        }
    }

    // Package-private process injection lets fixtures exercise the Java 6 protocol without a Java 6 VM.
    static BoundedDirectory legacy(final File directory, long deadline, Process process) throws IOException {
        final BoundedDirectory result = new BoundedDirectory(deadline); result.process = process;
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        result.reader = new Thread(new Runnable() { public void run() {
            try {
                InputStream input = new BufferedInputStream(result.process.getInputStream(), 4096);
                try {
                    ByteArrayOutputStream name = new ByteArrayOutputStream(512);
                    for (int value; !result.closed && (value = input.read()) != -1;) {
                        if (value != 0) { if (name.size() >= 4096) throw new IOException("Directory entry length limit"); name.write(value); continue; }
                        String text = new String(name.toByteArray(), System.getProperty("sun.jnu.encoding", System.getProperty("file.encoding", "UTF-8")));
                        File child = new File(text);
                        if (name.size() == 0 || !directory.getAbsoluteFile().equals(child.getParentFile()) || child.getName().equals(".") || child.getName().equals(".."))
                            throw new IOException("Invalid directory entry");
                        result.offer(child); name.reset();
                    }
                    if (name.size() != 0) throw new IOException("Incomplete directory entry");
                    if (!result.closed && result.process.waitFor() != 0) throw new IOException("Directory enumeration failed or exceeded resource limit");
                    result.offer(END);
                } finally { input.close(); }
            } catch (Exception error) { result.offer(new IOException("Directory enumeration incomplete", error)); }
        } }, "assessment-directory-reader");
        result.reader.setDaemon(true); result.reader.start(); return result;
    }

    private void offer(Object entry) {
        try { while (!closed && !entries.offer(entry, 100, TimeUnit.MILLISECONDS)) { } }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    File next() throws IOException {
        if (closed) return null;
        if (System.currentTimeMillis() >= deadline) { close(); throw new IOException("Directory enumeration time limit"); }
        try {
            if (iterator != null) {
                if (!iterator.hasNext()) { close(); return null; }
                return (File)toFile.invoke(iterator.next());
            }
            Object value = entries.poll(Math.max(1L, Math.min(5000L, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS);
            if (value == null) { close(); throw new IOException("Directory enumeration time limit"); }
            if (value == END) { close(); return null; }
            if (value instanceof IOException) { close(); throw (IOException)value; }
            return (File)value;
        } catch (InterruptedException error) {
            close(); Thread.currentThread().interrupt(); throw new IOException("Directory enumeration interrupted", error);
        } catch (IOException error) { throw error; }
        catch (Exception error) { close(); throw new IOException("Directory enumeration incomplete", error); }
    }

    public void close() {
        if (closed) return; closed = true;
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
        if (process != null) {
            process.destroy();
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            // Destroy first: closing an input stream while another thread blocks in read can wait for its lock.
            if (reader != null) {
                reader.interrupt();
                try { reader.join(1000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        }
        entries.clear();
    }
}
