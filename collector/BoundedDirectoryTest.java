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
    static String record(char type, File file) { return type + file.getPath() + '\0'; }
    static BoundedDirectory tree(File root, String output) throws Exception {
        return BoundedDirectory.legacy(root, System.currentTimeMillis() + 2000, new Producer(output.getBytes("UTF-8"), 0), true);
    }
    static void treeProtocol(File root) throws Exception {
        BoundedDirectory reader = tree(root, record('d', new File(root, "a")) + record('f', new File(root, "a/line\nb.war")) + record('l', new File(root, "a/link")));
        try {
            check(reader.next().getPath().equals(new File(root, "a").getPath()) && reader.type() == 'd', "tree record carries the directory type");
            check(reader.next().getName().equals("line\nb.war") && reader.type() == 'f' && reader.next().getName().equals("link") && reader.type() == 'l' && reader.next() == null, "nested paths keep NUL framing");
        } finally { reader.close(); }
        for (String bad : new String[]{"d/elsewhere/x\0", record('d', root), "d" + root.getPath() + "/a/../../x\0", "1" + root.getPath() + "/a\0", root.getPath() + "/a\0"}) {
            reader = tree(root, bad);
            try { while (reader.next() != null) { } throw new AssertionError("invalid tree entry accepted: " + bad); }
            catch (IOException expected) { checks++; }
            finally { reader.close(); }
        }
        AssessmentScan scan = new AssessmentScan(new Properties(), "test", "test", AssessmentScan.map("maxFiles", 100, "pauseMillis", 0)); scan.excluded.clear();
        File war = new File(root, "app/x.war"); ProcessLaunchTest.text(war, "war"); ProcessLaunchTest.text(new File(root, "z.war"), "war");
        scan.walkTree(root.getPath(), 0, tree(root, record('d', new File(root, "app")) + record('f', war) + record('d', new File(root, "app/node_modules"))
            + record('f', new File(root, "app/node_modules/y.war")) + record('l', new File(root, "loop")) + record('f', new File(root, "z.war"))));
        check(scan.applicationsByPath.containsKey(war.getPath()) && scan.applicationsByPath.containsKey(new File(root, "z.war").getPath())
            && !scan.applicationsByPath.containsKey(new File(root, "app/node_modules/y.war").getPath()) && scan.filesVisited == 3,
            "injected tree applies ignored-directory and symlink rules: " + scan.filesVisited);
    }
    /** The Java 6 single-find sweep must match the per-directory sweep on the same tree. */
    static void treeEquivalence() throws Exception {
        File root = CollectorTest.temp("tree-equivalence").getCanonicalFile();
        try {
            ProcessLaunchTest.text(new File(root, "apps/orders.war"), "war");
            ProcessLaunchTest.text(new File(root, "apps/.git/objects/x.war"), "ignored");
            ProcessLaunchTest.text(new File(root, "apps/node_modules/m/y.war"), "ignored");
            ProcessLaunchTest.text(new File(root, "excluded/z.war"), "excluded");
            ProcessLaunchTest.text(new File(root, "with[glob]*/w.war"), "special characters");
            ProcessLaunchTest.text(new File(root, "with[glob]*x/kept.war"), "a glob in the exclusion must not prune a sibling");
            File jdk = JdkInventoryTest.home(root, "jdk1.6.0_45", true); ProcessLaunchTest.text(new File(jdk, "lib/internal.jar"), "jdk internals");
            ProcessLaunchTest.text(new File(root, "tomcat/lib/catalina.jar"), "not a zip"); ProcessLaunchTest.text(new File(root, "tomcat/bin/catalina.sh"), "#!/bin/sh");
            ProcessLaunchTest.text(new File(root, "tomcat/lib/tomcat-util.jar"), "internal"); ProcessLaunchTest.text(new File(root, "tomcat/webapps/shop.war"), "war");
            File deep = root; for (int i = 0; i < 15; i++) deep = new File(deep, "d" + i); ProcessLaunchTest.text(new File(deep, "deep.war"), "too deep");
            new ProcessBuilder("ln", "-s", new File(root, "apps").getPath(), new File(root, "apps-link").getPath()).start().waitFor();
            String[] results = new String[2];
            for (int mode = 0; mode < 2; mode++) {
                BoundedDirectory.NIO = mode == 0;
                AssessmentScan scan = new AssessmentScan(new Properties(), "test", "test", AssessmentScan.map("maxFiles", 1000, "pauseMillis", 0));
                scan.excluded.clear(); scan.excluded.add(new File(root, "excluded").getPath()); scan.excluded.add(new File(root, "with[glob]*").getPath());
                scan.walk(root, 0); scan.flushSweepNotes();
                // The per-directory sweep notes a symlink only if its target was not visited yet, which depends on directory order.
                for (Iterator i = scan.warnings.iterator(); i.hasNext();) if (String.valueOf(i.next()).contains("simbólico(s) ignorado(s)")) i.remove();
                results[mode] = scan.filesVisited + " " + new TreeSet(scan.applicationsByPath.keySet()) + " " + scan.runtimes.keySet() + " " + new TreeSet(scan.servers.keySet())
                    + " " + scan.deferredArchives.size() + " " + new TreeSet(scan.warnings) + " " + scan.partial;
            }
            check(results[0].equals(results[1]), "tree and per-directory sweeps differ:\n" + results[0] + "\n" + results[1]);
            check(results[0].contains("orders.war") && results[0].contains("kept.war") && results[0].contains("shop.war") && !results[0].contains("y.war") && !results[0].contains("/w.war")
                && !results[0].contains("deep.war") && results[0].contains("Limite de profundidade de diretórios atingido em 1 caminho(s)") && !results[0].endsWith(" true") && !results[0].contains("apps-link") && results[0].contains("tomcat/lib/catalina.jar]"),
                "sweep rules hold: " + results[0]);
            File pkg = new File(root, "pkgroot/assessment-agent-1.5.9.ClZDXfW4/package");
            ProcessLaunchTest.text(new File(pkg, "collector.jar"), "agent"); ProcessLaunchTest.text(new File(pkg, "DEPENDENCIES.json"), "{\n  \"version\": \"1.5.9\"\n}\n");
            ProcessLaunchTest.write(new File(pkg, "lib/bcprov-jdk15to18-1.86.jar"), ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Implementation-Version", "1.86"), ProcessLaunchTest.entries()));
            ProcessLaunchTest.text(new File(root, "pkgroot/other/app.war"), "war");
            for (int mode = 0; mode < 2; mode++) {
                BoundedDirectory.NIO = mode == 0;
                AssessmentScan agent = new AssessmentScan(new Properties(), "test", "test", AssessmentScan.map("maxFiles", 1000, "pauseMillis", 0, "inspectArchives", true));
                agent.excluded.clear(); agent.walk(new File(root, "pkgroot"), 0);
                String libs = Json.stringify(agent.libraries) + Json.stringify(agent.deferredArchives.toString());
                check(!libs.contains("bcprov") && agent.applicationsByPath.toString().contains("app.war"), "agent package copy not inventoried, siblings still swept: " + libs);
                boolean noted = false; for (Object w : agent.warnings) noted |= String.valueOf(w).startsWith("Cópia de pacote do agente encontrada em " + new File(root, "pkgroot/assessment-agent-1.5.9.ClZDXfW4").getPath() + " (versão 1.5.9)");
                check(noted, "agent package copy reported: " + agent.warnings);
            }
            AssessmentScan noisy = new AssessmentScan(new Properties(), "test", "test", AssessmentScan.map("pauseMillis", 0));
            for (int i = 0; i < 60; i++) noisy.symlinkSkipped("/opt/monitor/embedded/lib/libz.so." + i);
            noisy.note("Observação JDBC importante"); for (int i = 0; i < 5; i++) noisy.symlinkSkipped("/opt/app/lib/x" + i + ".jar");
            noisy.flushSweepNotes();
            check(noisy.warnings.contains("Observação JDBC importante") && noisy.warnings.size() == 2 && String.valueOf(noisy.warnings.get(1)).startsWith("5 link(s)") && !noisy.partial,
                "symlink noise aggregated, .so links silent, other observations kept: " + noisy.warnings);
        } finally { BoundedDirectory.NIO = BoundedDirectory.nioAvailable(); CollectorTest.remove(root); }
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
            treeProtocol(root);
        } finally { CollectorTest.remove(root); }
        if ("Linux".equals(System.getProperty("os.name"))) treeEquivalence(); // the Java 6 find mode is Linux-only
        check(AssessmentScan.pauseFor(null, 2, 30) == 30 && AssessmentScan.pauseFor("garbage", 2, 30) == 30, "no load average keeps the fixed pause");
        check(AssessmentScan.pauseFor("1.90 1.0 1.0 1/100 1", 2, 30) == 0, "idle host is not paused");
        check(AssessmentScan.pauseFor("3.00 1.0 1.0 1/100 1", 2, 30) == 15 && AssessmentScan.pauseFor("9.00 1.0 1.0 1/100 1", 2, 30) == 30, "pause grows with load up to pauseMillis");
        System.out.println("BoundedDirectoryTest PASS " + checks + " checks");
    }
}
