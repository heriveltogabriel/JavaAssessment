package assessment;

import java.io.*;
import java.util.*;

/** Generated PerfData fixtures and fake /proc trees; an optional child JVM only sleeps. */
public final class HsPerfDataTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static void put(ByteArrayOutputStream out, int value, boolean little) {
        for (int i = 0; i < 4; i++) out.write(little ? value >>> (8 * i) : value >>> (8 * (3 - i)));
    }
    static byte[] zero(String value) throws Exception { byte[] text = value.getBytes("UTF-8"); return Arrays.copyOf(text, text.length + 1); }
    static int pad(int length) { return (length + 3) & ~3; }
    /** Writes PerfData 1.0 or 2.0 with string entries plus one long counter that must be skipped. */
    static byte[] perf(int major, boolean little, String... pairs) throws Exception {
        ByteArrayOutputStream entries = new ByteArrayOutputStream(); int count = 0;
        List<String> all = new ArrayList<String>(Arrays.asList(pairs)); all.add(0, "sun.rt.createVmBeginTime"); all.add(1, null);
        for (int i = 0; i < all.size(); i += 2) {
            byte[] name = zero(all.get(i)), data = all.get(i + 1) == null ? new byte[8] : zero(all.get(i + 1));
            int header = major == 2 ? 20 : 16, dataOffset = major == 2 ? pad(header + name.length) : header + name.length, length = pad(dataOffset + data.length);
            put(entries, length, little); put(entries, major == 2 ? header : name.length, little); put(entries, all.get(i + 1) == null ? 0 : data.length, little);
            entries.write(all.get(i + 1) == null ? 'J' : 'B'); entries.write(0); entries.write(all.get(i + 1) == null ? 4 : 5); entries.write(1);
            if (major == 2) put(entries, dataOffset, little);
            byte[] body = new byte[length - header];
            System.arraycopy(name, 0, body, 0, name.length); System.arraycopy(data, 0, body, dataOffset - header, data.length);
            entries.write(body); count++;
        }
        int prologue = major == 2 ? 32 : 24;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xca); out.write(0xfe); out.write(0xc0); out.write(0xc0); out.write(little ? 1 : 0); out.write(major); out.write(0); out.write(1);
        put(out, prologue + entries.size(), little); put(out, 0, little); put(out, 0, little); put(out, 0, little);
        if (major == 2) { put(out, prologue, little); put(out, count, little); }
        entries.writeTo(out);
        byte[] file = Arrays.copyOf(out.toByteArray(), 32768); return file;
    }
    static Map<String,String> parse(byte[] data) { return HsPerfData.parse(data, data.length); }

    static void format() throws Exception {
        String[] fields = {"java.property.java.version", "21.0.12", "java.property.java.vm.vendor", "Fixture Vendor", "java.property.user.name", "PRIVATE_CANARY",
            "java.rt.vmArgs", "-Dweblogic.Name=Admin -Dsecret=PRIVATE_CANARY", "sun.rt.javaCommand", "weblogic.Server PRIVATE_CANARY"};
        for (int major = 1; major <= 2; major++) for (int order = 0; order < 2; order++) {
            Map<String,String> values = parse(perf(major, order == 1, fields));
            check(values != null && "21.0.12".equals(values.get("java.property.java.version")) && "Fixture Vendor".equals(values.get("java.property.java.vm.vendor")), "PerfData " + major + ".0 strings in both byte orders");
            check(!values.containsKey("java.property.user.name") && !values.containsKey("sun.rt.createVmBeginTime") && values.size() == 4, "Only allowlisted string counters are retained");
        }
        byte[] valid = perf(2, true, fields);
        byte[] bad = valid.clone(); bad[0] = 0; check(parse(bad) == null, "Wrong magic is rejected");
        bad = valid.clone(); bad[5] = 3; check(parse(bad) == null, "Unknown major version is rejected");
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            byte[] fuzz = valid.clone(); int at = 8 + random.nextInt(600); fuzz[at] = (byte) random.nextInt(256);
            HsPerfData.parse(fuzz, 32 + random.nextInt(fuzz.length - 32));
        }
        StringBuilder classpath = new StringBuilder();
        for (int i = 0; classpath.length() < 1100; i++) classpath.append(i == 0 ? "" : ":").append("/opt/app/lib/library-").append(i).append(".jar");
        String capped = classpath.substring(0, 1024);
        String kept = parse(perf(2, true, "java.property.java.class.path", capped)).get("java.property.java.class.path");
        check(kept.equals(capped.substring(0, capped.lastIndexOf(':'))), "A capped classpath keeps only whole entries");
        Map<String,String> clipped=parse(perf(2,true,"java.property.java.class.path",capped));
        check("true".equals(clipped.get("assessment.truncated.java.property.java.class.path")),"Truncation identifies exact field");
        ProcessLaunch recovered=ProcessLaunch.parse(new String[]{"java","-cp",classpath.toString(),"weblogic.Server"},null,clipped);
        check(!recovered.runtimePathsIncomplete&&recovered.classpath.get(recovered.classpath.size()-1).endsWith(".jar"),"Complete matching command line survives truncated perfdata");
        ProcessLaunch conflict=ProcessLaunch.parse(new String[]{"java","-cp","/different.jar","weblogic.Server"},null,clipped);
        check(conflict.runtimePathsIncomplete&&conflict.pathsReason().contains("compatíveis"),"Conflicting prefix cannot establish precedence");
        ProcessLaunch opaque=ProcessLaunch.parse(new String[]{"java","@args"},null,clipped);
        check(opaque.runtimePathsIncomplete,"Opaque command line cannot repair partial classpath");
        clipped.put("assessment.truncated.java.rt.vmArgs","true");
        check(ProcessLaunch.parse(new String[]{"java","-cp",classpath.toString(),"weblogic.Server"},null,clipped).runtimePathsIncomplete,"Partial additional JVM options remain uncertain");
        HsPerfData.Mapping mapping = HsPerfData.mapping("55f337a19000-55f337a1a000 r--p 00000000 fe:00 240443                     /usr/lib/jvm/jdk 21/bin/java (deleted)\n"
            + "7f5ba5200000-7f5ba54a3000 r--p 00000000 fe:00 240804                     /usr/lib/jvm/jdk 21/lib/server/libjvm.so (deleted)\n"
            + "7f5ba6a15000-7f5ba6a1d000 rw-s 00000000 fe:00 960729                     /tmp/hsperfdata_app/77\n7f5ba6a1d000-7f5ba6a1e000 rw-p 00000000 00:00 0 \n");
        check("/usr/lib/jvm/jdk 21/bin/java".equals(mapping.executable) && mapping.replaced && "/tmp/hsperfdata_app/77".equals(mapping.perfData), "maps exposes deleted executable and perf file");
        check(!HsPerfData.mapping("1-2 r-xp 0 fe:00 1 /opt/jdk/bin/java\n").replaced, "Present executable is not replaced");
    }

    static void launch() throws Exception {
        Map<String,String> perf = new HashMap<String,String>();
        perf.put("java.rt.vmArgs", "-Xmx1g -Dweblogic.Name=AdminServer -Ddb.password=PRIVATE_CANARY");
        perf.put("sun.rt.javaCommand", "weblogic.Server --password PRIVATE_CANARY");
        perf.put("java.property.java.class.path", "/opt/wls/server/lib/weblogic.jar:/opt/wls/lib/extra.jar");
        ProcessLaunch argfile = ProcessLaunch.parse(new String[]{"/opt/jdk/bin/java", "@/u01/args"}, null, perf);
        check("WebLogic".equals(argfile.serverType) && "AdminServer".equals(argfile.serverName) && argfile.classpath.size() == 2 && argfile.opaqueArguments && argfile.perfDataCompleted,
            "An @argfile launcher is classified from perf data");
        perf.put("sun.rt.javaCommand", "app.jar --token PRIVATE_CANARY");
        ProcessLaunch jar = ProcessLaunch.parse(new String[]{"java", "@args"}, new File("/srv/app"), perf);
        check("/srv/app/app.jar".equals(jar.jarPath) && jar.classpath.isEmpty() && jar.mainClass == null, "An @argfile jar launcher keeps the jar, not its classpath");
        Map<String,String> environment = new HashMap<String,String>(); environment.put("java.property.java.class.path", "/opt/tomcat/bin/bootstrap.jar");
        ProcessLaunch tomcat = ProcessLaunch.parse(new String[]{"java", "org.apache.catalina.startup.Bootstrap", "start"}, null, environment);
        check("/opt/tomcat".equals(tomcat.serverHome) && tomcat.classpath.size() == 1 && !tomcat.opaqueArguments, "CLASSPATH from the environment completes the launcher");
        ProcessLaunch explicit = ProcessLaunch.parse(new String[]{"java", "-cp", "/a.jar", "Main"}, null, environment);
        check(explicit.classpath.equals(Arrays.asList("/opt/tomcat/bin/bootstrap.jar")) && explicit.perfDataCompleted, "Effective perfdata classpath supersedes launcher text");
    }

    static long uid() throws Exception {
        // Also on macOS release builds, which have no /proc.
        return new com.sun.security.auth.module.UnixSystem().getUid();
    }
    static void process(File proc, String pid, long uid, String home, boolean deleted, byte[] perf, String... args) throws Exception {
        ProcessLaunchTest.process(proc, pid, args);
        File dir = new File(proc, pid);
        ProcessLaunchTest.text(new File(dir, "status"), "Name:\tjava\nUid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid + "\nNSpid:\t" + pid + "\n");
        String suffix = deleted ? " (deleted)" : "";
        ProcessLaunchTest.text(new File(dir, "maps"), "55f337a19000-55f337a1a000 r-xp 00001000 fe:00 240443                     " + home + "/bin/java" + suffix + "\n"
            + "7f5ba54a3000-7f5ba631f000 r-xp 002a3000 fe:00 240804                     " + home + "/lib/server/libjvm.so" + suffix + "\n"
            + "7f5ba6a15000-7f5ba6a1d000 rw-s 00000000 fe:00 960729                     /tmp/hsperfdata_app/" + pid + "\n");
        if (perf != null) ProcessLaunchTest.write(new File(dir, "root/tmp/hsperfdata_app/" + pid), perf);
        mountinfo(dir, "22");
    }
    static void mountinfo(File dir, String rootId) throws Exception {
        ProcessLaunchTest.text(new File(dir, "mountinfo"), "1 0 0:1 / /proc rw - proc proc rw\n" + rootId + " 1 253:0 / / rw,relatime - xfs /dev/root rw\n");
    }
    static void replaced() throws Exception {
        File root = CollectorTest.temp("hsperf-replaced");
        try {
            long uid = uid();
            byte[] elf = new byte[20]; elf[0] = 127; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F'; elf[4] = 2; elf[5] = 1; elf[18] = 62;
            File home = new File(root, "jdk-21"); ProcessLaunchTest.write(new File(home, "bin/java"), elf); ProcessLaunchTest.write(new File(home, "bin/javac"), elf);
            ProcessLaunchTest.text(new File(home, "release"), "JAVA_VERSION=\"21.0.99\"\nIMPLEMENTOR=\"Disk Vendor\"\n");
            File gone = new File(root, "java-21-openjdk-21.0.4");
            File other = new File(root, "jdk-17"); ProcessLaunchTest.write(new File(other, "bin/java"), elf); ProcessLaunchTest.write(new File(other, "bin/javac"), elf);
            ProcessLaunchTest.text(new File(other, "release"), "JAVA_VERSION=\"17.0.1\"\n");
            File proc = new File(root, "proc");
            byte[] running = perf(2, true, "java.property.java.version", "21.0.12", "java.property.java.vm.vendor", "Running Vendor",
                "java.rt.vmArgs", "-Dweblogic.Name=Managed1 -Dsecret=PRIVATE_CANARY", "sun.rt.javaCommand", "weblogic.Server PRIVATE_CANARY");
            process(proc, "41001", uid, home.getPath(), true, running, "java", "@/u01/PRIVATE_CANARY.args");
            process(proc, "41002", uid, home.getPath(), false, perf(2, true, "java.property.java.version", "21.0.99"), "java", "Main");
            process(proc, "41003", uid, gone.getPath(), true, perf(2, false, "java.property.java.version", "21.0.4"), "java", "Main");
            process(proc, "41004", uid + 1, other.getPath(), false, perf(2, true, "java.property.java.version", "17.0.0-spoofed"), "java", "Main");
            process(proc, "41005", uid, other.getPath(), false, null, "java", "Main");
            process(proc, "41006", uid, home.getPath(), false, perf(2, true, "java.property.java.version", "11.0.20", "java.property.java.vendor", "Container Vendor"), "java", "Main");
            mountinfo(new File(proc, "41006"), "1474");
            process(proc, "41007", uid, other.getPath(), false, perf(2, true, "java.property.java.version", "17.0.9"), "java", "Main");
            new File(proc, "41007/mountinfo").delete();
            mountinfo(new File(proc, "self"), "22");
            File target = new File(root, "elsewhere"); ProcessLaunchTest.write(target, perf(2, true, "java.property.java.version", "17.0.0-linked"));
            File link = new File(proc, "41005/root/tmp/hsperfdata_app/41005"); link.getParentFile().mkdirs();
            check(new ProcessBuilder("ln", "-s", target.getPath(), link.getPath()).start().waitFor() == 0, "Fixture symlink");
            AssessmentScan scan = ProcessLaunchTest.scanner(); scan.discoverProcesses(proc);
            Map runtime = scan.runtimes.get(home.getCanonicalPath());
            check(scan.runtimes.size() == 3 && "21.0.99".equals(runtime.get("version")) && Arrays.asList("21.0.12").equals(runtime.get("runningVersions")), "Disk version kept, running divergence recorded, no duplicate installation; container version not applied: " + runtime);
            Map container = (Map) scan.processes.get(5), unknown = (Map) scan.processes.get(6);
            check("11.0.20".equals(container.get("javaVersion")) && Boolean.TRUE.equals(container.get("containerized")) && !"Container Vendor".equals(runtime.get("vendor")), "Other mount namespace: version stays on the process, host runtime untouched");
            check("17.0.9".equals(unknown.get("javaVersion")) && unknown.get("containerized") == null, "Unreadable mountinfo is not called a container");
            check(Boolean.TRUE.equals(runtime.get("runtimeReplacedOnDisk")) && scan.restrictedProcesses == 0, "Replaced binary is flagged and not treated as a permission limit");
            Map first = (Map) scan.processes.get(0), second = (Map) scan.processes.get(1), removed = (Map) scan.processes.get(2);
            check(second.get("containerized") == null, "Same mount namespace is not containerized");
            check("21.0.12".equals(first.get("javaVersion")) && Boolean.TRUE.equals(first.get("runtimeReplacedOnDisk")) && "WebLogic".equals(first.get("serverType")), "Process keeps its running version and argfile classification");
            check(String.valueOf(first.get("evidence")).contains("replaced on disk") && String.valueOf(runtime.get("evidence")).contains("restart pending"), "Evidence names the pending restart");
            check(scan.servers.values().iterator().next().get("name").equals("Managed1"), "WebLogic server name came from perf data -D");
            check("21.0.99".equals(second.get("javaVersion")) && second.get("runtimeReplacedOnDisk") == null, "Restarted JVM matches disk");
            Map removedRuntime = scan.runtimes.get(gone.getCanonicalPath());
            check(removedRuntime != null && "21.0.4".equals(removedRuntime.get("version")) && gone.getCanonicalPath().equals(removed.get("javaHome")) && Boolean.TRUE.equals(removedRuntime.get("inUse")), "Removed home is inventoried with the running version");
            Map otherRuntime = scan.runtimes.get(other.getCanonicalPath());
            check("17.0.1".equals(otherRuntime.get("version")) && otherRuntime.get("runningVersions") == null, "Foreign-owned or symlinked perf data is ignored");
            check(((Map) scan.processes.get(3)).get("javaVersion") == null && ((Map) scan.processes.get(4)).get("javaVersion") == null, "Untrusted perf files leave the version unknown");
            String output = Json.stringify(AssessmentScan.map("p", scan.processes, "r", new ArrayList(scan.runtimes.values()), "s", new ArrayList(scan.servers.values()), "a", scan.applications, "w", scan.warnings));
            check(!output.contains("PRIVATE_CANARY") && !output.contains("-Xmx"), "Command text from perf data is never retained");
        } finally { CollectorTest.remove(root); }
    }

    /** Optional: a real child JVM's hsperfdata through the live /proc tree. */
    static String live() throws Exception {
        File proc = new File("/proc/self/maps");
        if (!proc.isFile()) return "live-skipped";
        String marker = "-Dassessment.hsperf.marker=" + System.nanoTime();
        File java = new File(System.getProperty("java.home"), "bin/java");
        Process child = new ProcessBuilder(java.getPath(), "-Xmx16m", marker, "-cp", System.getProperty("java.class.path"), "assessment.HsPerfDataTest", "sleep").redirectErrorStream(true).start();
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                AssessmentScan scan = ProcessLaunchTest.scanner(); scan.discoverProcesses();
                for (Object row : scan.processes) {
                    Map process = (Map) row;
                    if (!"assessment.HsPerfDataTest".equals(process.get("mainClass")) || process.get("javaVersion") == null) continue;
                    check(System.getProperty("java.version").equals(process.get("javaVersion")), "Live perf data reports the running version");
                    return "live-child";
                }
                Thread.sleep(100);
            }
            return "live-unavailable";
        } finally { child.destroy(); child.waitFor(); }
    }

    /** A perf file swapped for a FIFO after the ownership check must not hang the collector. */
    static String fifo() throws Exception {
        File root = CollectorTest.temp("hsperf-fifo");
        try {
            File pipe = new File(root, "4242");
            if (!new File("/usr/bin/mkfifo").isFile() && !new File("/bin/mkfifo").isFile()) return "fifo-skipped";
            if (new ProcessBuilder("mkfifo", pipe.getPath()).start().waitFor() != 0) return "fifo-skipped";
            AssessmentScan scan = ProcessLaunchTest.scanner(); long start = System.currentTimeMillis();
            check(HsPerfData.readBounded(scan, pipe, 300) == null && System.currentTimeMillis() - start < 2000, "FIFO open times out instead of blocking");
            check(Json.stringify(scan.warnings).contains("did not open in time") && !scan.partial, "Timed-out perf file leaves a note");
            File regular = new File(root, "4243"); ProcessLaunchTest.write(regular, perf(2, true, "java.property.java.version", "21.0.5"));
            check("21.0.5".equals(HsPerfData.readBounded(scan, regular, 2000).get("java.property.java.version")) && scan.bytesRead == 32768, "Regular perf file read and charged once");
            return "fifo-timeout";
        } finally { CollectorTest.remove(root); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "sleep".equals(args[0])) { Thread.sleep(20000); return; }
        format(); launch(); replaced();
        String fifo = fifo();
        File owned = File.createTempFile("hsperf", ".bin"); ProcessLaunchTest.text(owned, "x");
        try { check(HsPerfData.ownedByStat(owned, uid()) && !HsPerfData.ownedByStat(owned, uid() + 1) || !new File("/usr/bin/stat").isFile() || !"Linux".equals(System.getProperty("os.name")), "Java 6 stat ownership fallback"); }
        finally { owned.delete(); }
        System.out.println("HSPERFDATA_TESTS=PASS v1 v2 byte-order fuzz cap maps argfile classpath replaced removed owner symlink no-command-retention " + fifo + " " + live());
    }
}
