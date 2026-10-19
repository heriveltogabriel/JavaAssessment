package assessment;

import java.io.*;
import java.lang.management.*;
import java.net.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Bounded metadata-first discovery. Discovered executables are never launched. */
public final class AssessmentScan {
    final Properties config;
    final Map settings;
    final String hostId, hostname, timestamp;
    final String collectorInstallationRoot, collectorPrivateRuntimeRoot, collectorExternalRuntimeRoot;
    final long start = System.currentTimeMillis();
    final long cpuStart = processCpu();
    final long deadline;
    final int maxFiles, batchSize, pauseMillis;
    // filesVisited counts both phases; process-linked evidence uses filesVisited - sweepFiles.
    int filesVisited, archivesInspected, sweepFiles;
    long bytesRead, peakHeap;
    boolean partial, fileLimitReached;
    final List warnings = new ArrayList();
    final Set<String> criticalWarnings = new HashSet<String>();
    final LinkedHashMap<String, Map> runtimes = new LinkedHashMap<String, Map>();
    final LinkedHashMap<String, Map> servers = new LinkedHashMap<String, Map>();
    final JdbcDriverResolver jdbcDrivers = new JdbcDriverResolver(this);
    final List processes = new ArrayList(), libraries = new ArrayList(), applications = new ArrayList(), datasources = new ArrayList();
    final Set<String> libraryKeys = new HashSet<String>();
    final Map<String, Map> libraryRows = new LinkedHashMap<String, Map>();
    final Map<String, Map> applicationsByPath = new LinkedHashMap<String, Map>();
    final Map<String, Map> configuredDatasourceRows = new LinkedHashMap<String, Map>();
    final List<String> excluded = new ArrayList<String>();
    final List<String> networkMounts = new ArrayList<String>();
    final Set<String> visited = new HashSet<String>();
    final List<String> productInternals = new ArrayList<String>(), processRoots = new ArrayList<String>();
    static final List<String> IGNORED_DIRECTORIES = Arrays.asList(".git", ".cache", "node_modules", "logs", "tmp", "temp",
        ".m2", ".gradle", ".npm", ".ivy2", "__pycache__", "oradata", "diag", "lost+found");
    MessageDigest visitedDigest;
    final List<File> deferredArchives = new ArrayList<File>();
    final ArchiveMetadata archiveMetadata = new ArchiveMetadata(this);
    final ServerDataSources serverDataSources = new ServerDataSources(this);
    int restrictedProcesses, restrictedArtifacts;

    public AssessmentScan(Properties config, String hostId, String hostname, Map settings) {
        this(config, hostId, hostname, settings, collectorCodeLocation());
    }
    AssessmentScan(Properties config, String hostId, String hostname, Map settings, File codeLocation) {
        this.config = config; this.hostId = hostId; this.hostname = hostname; this.settings = settings;
        collectorInstallationRoot = installationRoot(codeLocation);
        collectorPrivateRuntimeRoot = privateRuntimeRoot(codeLocation);
        collectorExternalRuntimeRoot = externalRuntimeRoot(codeLocation);
        timestamp = now();
        deadline = start + number(settings.get("maxDurationSeconds"), 180, 10, 900) * 1000L;
        maxFiles = number(settings.get("maxFiles"), 50000, 100, 500000);
        batchSize = number(settings.get("batchSize"), 100, 1, 500);
        pauseMillis = number(settings.get("pauseMillis"), 30, 0, 2000);
        excluded.addAll(Arrays.asList("/proc", "/sys", "/dev", "/tmp"));
        Object exclusions = settings.get("excludePaths");
        // A trailing slash would keep the excluded directory itself in scope and defeat the find -path prune.
        if (exclusions instanceof List) for (Object path : (List) exclusions) if (path instanceof String) excluded.add(((String) path).replaceAll("(.)/+$", "$1"));
    }

    static File collectorCodeLocation() {
        try {
            java.security.CodeSource source = AssessmentScan.class.getProtectionDomain().getCodeSource();
            URL location = source == null ? null : source.getLocation();
            return location != null && "file".equals(location.getProtocol()) ? new File(location.toURI()) : null;
        } catch (Exception e) { return null; }
    }
    static String installationRoot(File codeLocation) {
        if (codeLocation == null) return null;
        try {
            File jar = codeLocation.getCanonicalFile();
            if (!"collector.jar".equals(jar.getName()) || !jar.isFile()) return null;
            File root = jar.getParentFile();
            if (root == null || root.getParentFile() == null) return null;
            // Full updates execute from <installation>/releases/<release>/collector.jar.
            File releases = root.getParentFile(), installation = releases.getParentFile();
            if ("releases".equals(releases.getName()) && installation != null
                && "java-assessment-collector".equals(installation.getName())) return installation.getPath();
            return root.getPath();
        } catch (IOException e) { return null; }
    }
    static String privateRuntimeRoot(File codeLocation) {
        String installation = installationRoot(codeLocation);
        if (installation == null) return null;
        try {
            File root = new File(installation, "runtime"), resolved = root.getCanonicalFile();
            // A symlink into a shared Java directory is not a private runtime.
            return root.equals(resolved) ? resolved.getPath() : null;
        } catch (IOException e) { return null; }
    }

    static String externalRuntimeRoot(File codeLocation) {
        String installation = installationRoot(codeLocation);
        if (installation == null || !"java-assessment-collector".equals(new File(installation).getName())) return null;
        try {
            File root = new File(new File(installation).getParentFile(), "java-assessment-runtime");
            return root.equals(root.getCanonicalFile()) ? root.getPath() : null;
        } catch (IOException e) { return null; }
    }
    boolean privateRuntime(String path) {
        return collectorPrivateRuntimeRoot != null && under(path, collectorPrivateRuntimeRoot)
            || collectorExternalRuntimeRoot != null && under(path, collectorExternalRuntimeRoot);
    }

    boolean internalSweepPath(String path) {
        if (collectorInstallationRoot != null && under(path, collectorInstallationRoot)) return true;
        if (privateRuntime(path)) return true;
        if (collectorInstallationRoot == null || !"java-assessment-collector".equals(new File(collectorInstallationRoot).getName())) return false;
        File parent = new File(collectorInstallationRoot).getParentFile();
        return under(path, new File(parent, "java-assessment-update").getPath())
            || under(path, new File(parent, "java-assessment-uninstall").getPath());
    }

    String visitedKey(String path) {
        try {
            if (visitedDigest == null) visitedDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = visitedDigest.digest(path.getBytes("UTF-8"));
            char[] key = new char[digest.length * 2]; String hex = "0123456789abcdef";
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 255; key[i * 2] = hex.charAt(value >>> 4); key[i * 2 + 1] = hex.charAt(value & 15);
            }
            return new String(key);
        } catch (Exception e) { throw new IllegalStateException("Cannot identify visited path", e); }
    }

    static Map map(Object... pairs) {
        Map result = new LinkedHashMap();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
    static int number(Object value, int fallback, int minimum, int maximum) {
        if (!(value instanceof Number)) return fallback;
        return Math.max(minimum, Math.min(maximum, ((Number) value).intValue()));
    }
    static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date());
    }
    static String readStream(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > limit) throw new IOException("Input exceeds bounded read limit");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), "UTF-8");
    }
    static String readFile(File file, int limit) throws IOException {
        InputStream input = new FileInputStream(file);
        try { return readStream(input, limit); } finally { input.close(); }
    }
    static void atomicWrite(File target, String text) throws IOException {
        File temporary = new File(target.getPath() + ".tmp");
        OutputStream output = new FileOutputStream(temporary);
        try { output.write(text.getBytes("UTF-8")); } finally { output.close(); }
        if (!temporary.renameTo(target)) throw new IOException("Cannot publish atomic file");
    }
    void note(String warning) { if (warnings.size() < 40 && !warnings.contains(warning)) warnings.add(warning); }
    void warn(String warning) {
        partial = true; criticalWarnings.add(warning);
        if (warnings.contains(warning)) return;
        if (warnings.size() >= 40) { int replace = 0; for (int i = 0; i < warnings.size(); i++) if (!criticalWarnings.contains(warnings.get(i))) { replace = i; break; } criticalWarnings.remove(warnings.remove(replace)); }
        warnings.add(warning);
    }
    boolean available() {
        peakHeap = Math.max(peakHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        if (System.currentTimeMillis() >= deadline) { warn("Assessment deadline reached; remaining paths were not examined"); return false; }
        return true;
    }
    String read(File file, int limit) {
        return metadataText(file, limit, true);
    }
    String metadataText(File file, int limit, boolean report) {
        if (!available()) return null;
        if (bytesRead >= ArchiveMetadata.READ_LIMIT) { warn("Metadata read budget of 32 MiB reached"); return null; }
        InputStream input = null;
        try {
            input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while (available()) {
                int remaining = (int)Math.min(limit - out.size() + 1L, ArchiveMetadata.READ_LIMIT - bytesRead);
                if (remaining <= 0) { warn("Metadata read budget of 32 MiB reached"); return null; }
                count = input.read(buffer, 0, Math.min(buffer.length, remaining)); if (count < 0) return new String(out.toByteArray(), "UTF-8");
                bytesRead += count; if (out.size() + count > limit) { warn("Bounded metadata entry size limit reached"); return null; } out.write(buffer, 0, count);
            }
        } catch (IOException e) { if (report) warn("Could not read bounded metadata: " + file.getPath()); }
        finally { if (input != null) try { input.close(); } catch (IOException e) { } }
        return null;
    }
    static String id(String prefix, String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
            StringBuilder text = new StringBuilder(prefix + "-");
            for (int i = 0; i < 12; i++) text.append(String.format("%02x", hash[i] & 255));
            return text.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String clean(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).replace('\u0000', ' ').trim();
        if (text.length() > 512) text = text.substring(0, 512);
        return text;
    }
    static long processCpu() {
        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            Object value = Class.forName("com.sun.management.OperatingSystemMXBean").getMethod("getProcessCpuTime").invoke(bean);
            return ((Number) value).longValue();
        } catch (Exception e) { return -1; }
    }

    public Map scan(String jobId) {
        Map host = host();
        discoverProcesses();
        // Dedicated collector runtimes remain outside the application Java inventory.
        addRuntime(new File(System.getProperty("java.home")), false, "Collector runtime properties; collector alone is excluded from application usage", timestamp,
            System.getProperty("java.version"), System.getProperty("java.vendor"), System.getProperty("os.arch"));
        Object includes = settings.get("includePaths");
        if (!(includes instanceof List)) includes = Arrays.asList("/home/opc/oracle", "/usr/java", "/usr/lib/jvm", "/opt");
        for (Object item : (List) includes) {
            if (!available()) break;
            if (!(item instanceof String)) continue;
            File root = new File((String) item);
            if (!root.isAbsolute() || root.getPath().equals("/")) { warn("Rejected unrestricted or relative include path: " + clean(item)); continue; }
            if (!root.exists()) { note("Include path is absent: " + clean(item)); continue; }
            walk(root, 0);
        }
        List<String> processScanRoots = walkProcessRoots((List) includes);
        for (File file : deferredArchives) { if (!available()) break; inspectArtifact(file, null, false); }
        reuseWebLogicSnapshot();
        long cpu = processCpu();
        Map stats = map("durationMillis", System.currentTimeMillis() - start, "filesVisited", filesVisited,
            "bytesRead", bytesRead, "collectorCpuMillis", cpuStart >= 0 && cpu >= 0 ? Long.valueOf((cpu - cpuStart) / 1000000) : null,
            "peakHeapBytes", peakHeap, "collectorPeakRssBytes", procKilobytes(new File("/proc/self/status"), "VmHWM:"),
            "archivesInspected", archivesInspected, "scanRoots", includes, "processScanRoots", processScanRoots,
            "maxFiles", maxFiles, "sweepFilesVisited", sweepFiles, "processLinkedFilesVisited", filesVisited - sweepFiles);
        Map coverage = map("status", partial ? "partial" : "complete", "warnings", warnings, "stats", stats);
        return map("schemaVersion", "1.0", "eventId", UUID.randomUUID().toString(), "jobId", jobId,
            "host", host, "runtimes", new ArrayList(runtimes.values()), "processes", processes,
            "servers", new ArrayList(servers.values()), "libraries", libraries, "applications", applications,
            "datasources", datasources, "coverage", coverage, "capturedAt", timestamp,
            "collectorJavaVersion", System.getProperty("java.version"));
    }

    /** Observed server bases outside includePaths still hold deployments and JDBC descriptors. */
    List<String> walkProcessRoots(List includes) {
        List<String> walked = new ArrayList<String>();
        for (String base : processRoots) {
            if (!available()) break;
            boolean covered = false;
            for (Object item : includes) if (item instanceof String && under(base, (String) item)) covered = true;
            if (covered) continue;
            walked.add(base); walk(new File(base), 0);
        }
        return walked;
    }
    Long procKilobytes(File file, String key) {
        String text = read(file, 65536); if (text == null) return null;
        for (String line : text.split("\n")) if (line.startsWith(key)) {
            try { return Long.valueOf(Long.parseLong(line.substring(key.length()).trim().split("\\s+")[0]) * 1024); }
            catch (Exception e) { return null; }
        }
        return null;
    }
    Map host() {
        Map host = map("id", hostId, "hostname", hostname, "osName", System.getProperty("os.name"),
            "kernelVersion", System.getProperty("os.version"), "logicalCpus", Runtime.getRuntime().availableProcessors(),
            "lastSeen", timestamp, "collectorJavaVersion", System.getProperty("java.version"));
        try { host.put("fqdn", InetAddress.getLocalHost().getCanonicalHostName()); }
        catch (Exception e) { warn("FQDN lookup unavailable"); }
        List addresses = new ArrayList();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                Enumeration<InetAddress> current = interfaces.nextElement().getInetAddresses();
                while (current.hasMoreElements()) addresses.add(current.nextElement().getHostAddress());
            }
        } catch (Exception e) { warn("Network interface enumeration unavailable"); }
        host.put("ipAddresses", addresses);
        String arch = systemArchitecture(); host.put("architecture", arch);
        File redhat = new File("/etc/redhat-release");
        if (redhat.isFile()) host.put("distribution", clean(read(redhat, 4096)));
        File osRelease = new File("/etc/os-release");
        if (!host.containsKey("distribution") && osRelease.isFile()) {
            String value = read(osRelease, 16384);
            if (value != null) for (String line : value.split("\n")) if (line.startsWith("PRETTY_NAME=")) host.put("distribution", unquote(line.substring(12)));
        }
        File cpuinfo = new File("/proc/cpuinfo");
        if (cpuinfo.isFile()) {
            String cpu = read(cpuinfo, 256 * 1024);
            if (cpu != null) for (String line : cpu.split("\n")) if (line.startsWith("model name") && line.indexOf(':') > 0) { host.put("cpuModel", clean(line.substring(line.indexOf(':') + 1))); break; }
            host.put("memoryTotalBytes", procKilobytes(new File("/proc/meminfo"), "MemTotal:"));
            host.put("memoryAvailableBytes", procKilobytes(new File("/proc/meminfo"), "MemAvailable:"));
            host.put("memoryFreeBytes", procKilobytes(new File("/proc/meminfo"), "MemFree:"));
            if (host.get("memoryAvailableBytes") == null) note("Kernel has no MemAvailable counter; available memory is unknown, not estimated");
        } else warn("Linux /proc metadata is unavailable on this host");
        List disks = new ArrayList(); Set<String> mounts = new HashSet<String>();
        String mountText = read(new File("/proc/mounts"), 128 * 1024);
        if (mountText != null) for (String line : mountText.split("\n")) {
            String[] cols = line.split("\\s+"); if (cols.length < 3) continue;
            String mount = cols[1].replace("\\040", " ").replace("\\011", "\t");
            String type = cols[2];
            if (type.startsWith("nfs") || type.equals("cifs") || type.startsWith("smb") || type.contains("sshfs") || type.equals("afs")) { networkMounts.add(mount); continue; }
            if (Arrays.asList("proc", "sysfs", "tmpfs", "devtmpfs", "devpts", "securityfs", "cgroup", "cgroup2", "debugfs", "mqueue", "pstore", "fusectl", "configfs", "hugetlbfs").contains(type)) continue;
            File path = new File(mount);
            if (mounts.add(mount) && path.getTotalSpace() > 0) disks.add(map("mount", mount, "totalBytes", path.getTotalSpace(), "availableBytes", path.getUsableSpace()));
        }
        if (disks.isEmpty()) for (File path : File.listRoots()) if (path.getTotalSpace() > 0) disks.add(map("mount", path.getPath(), "totalBytes", path.getTotalSpace(), "availableBytes", path.getUsableSpace()));
        host.put("disks", disks); return host;
    }

    String systemArchitecture() {
        File uname = new File("/bin/uname");
        if (!uname.isFile()) { warn("Host architecture command unavailable; collector architecture is not substituted"); return null; }
        Process process = null;
        try {
            process = new ProcessBuilder(uname.getPath(), "-m").start();
            long stop = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < stop) {
                try { if (process.exitValue() == 0) return clean(readStream(process.getInputStream(), 256)); else break; }
                catch (IllegalThreadStateException running) { Thread.sleep(20); }
            }
        } catch (Exception e) { warn("Could not query host architecture"); }
        finally { if (process != null) process.destroy(); }
        return null;
    }

    static String unquote(String value) { return value.replaceAll("^\"|\"$", "").trim(); }
    static boolean under(String path, String root) { return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/"); }
    boolean skipped(String path) {
        if (excluded(path) || internalSweepPath(path)) return true;
        for (String root : productInternals) if (under(path, root)) return true;
        return false;
    }
    boolean excluded(String path) {
        for (String root : excluded) if (under(path, root)) return true;
        for (String root : networkMounts) if (under(path, root)) return true;
        return false;
    }

    void walk(File file, int depth) {
        String path, key;
        try {
            path = file.getCanonicalPath();
            // Sweep exclusions do not apply to explicit process-linked evidence.
            // Apply them before budgets: intentionally skipped trees are not gaps.
            if (skipped(path)) return;
            key = visitedKey(path);
            if (visited.contains(key)) return;
            if (!file.getAbsolutePath().equals(path)) { note("Symlink path skipped: " + file.getPath()); return; }
        } catch (IOException e) { warn("Cannot resolve path: " + file.getPath()); return; }
        if (!visit(file, path, key, depth, file.isDirectory())) return;
        if (!BoundedDirectory.NIO) {
            // Java 6: one find per root instead of one process per directory.
            List<String> prune = new ArrayList<String>();
            for (List<String> roots : Arrays.asList(excluded, networkMounts, productInternals)) for (String root : roots) if (under(root, path) && !root.equals(path)) prune.add(root);
            try { walkTree(path, depth, BoundedDirectory.tree(file, deadline, 13 - depth, prune, IGNORED_DIRECTORIES)); }
            catch (IOException e) { warn("Directory enumeration incomplete (read or resource limit): " + path); }
            return;
        }
        BoundedDirectory children = null;
        try {
            children = BoundedDirectory.open(file, deadline);
            for (File child; available() && !fileLimitReached && (child = children.next()) != null;) walk(child, depth + 1);
        } catch (IOException e) { warn("Directory enumeration incomplete (read or resource limit): " + path); }
        finally { if (children != null) children.close(); }
    }
    /** Consumes a pre-order find stream with walk's rules; find never follows symlinks, so its paths are canonical. */
    void walkTree(String rootPath, int depth, BoundedDirectory tree) {
        String skip = null;
        try {
            for (File child; available() && !fileLimitReached && (child = tree.next()) != null;) {
                String path = child.getPath();
                if (skip != null && path.startsWith(skip)) continue;
                skip = null;
                char type = tree.type();
                String key = visitedKey(path);
                if (skipped(path) || visited.contains(key)) { skip = path + "/"; continue; }
                if (type == 'l') { note("Symlink path skipped: " + path); continue; }
                int level = depth + 1;
                for (int i = rootPath.length() + 1; i < path.length(); i++) if (path.charAt(i) == '/') level++;
                if (!visit(child, path, key, level, type == 'd') && type == 'd') skip = path + "/";
            }
        } catch (IOException e) { warn("Directory enumeration incomplete (read or resource limit): " + rootPath); }
        finally { tree.close(); }
    }
    /** Counts one sweep entry; true only for a directory whose children must be walked. */
    boolean visit(File file, String path, String key, int depth, boolean directory) {
        // Ignored directories, like exclusions, are skipped before budgets in both enumeration modes.
        if (directory && IGNORED_DIRECTORIES.contains(file.getName())) return false;
        if (!available()) return false;
        if (sweepFiles >= maxFiles) { fileLimitReached = true; warn("File count limit reached; remaining paths were not examined"); return false; }
        if (depth > 12) { warn("Directory depth limit reached"); return false; }
        visited.add(key);
        filesVisited++; sweepFiles++;
        if (!pace(sweepFiles)) return false;
        if (directory) {
            if (runtimes.containsKey(path)) return false;
            if (new File(file, "bin/java").isFile()) {
                addRuntime(file, false, "Java home found; runtime metadata inventoried without traversing JDK internals", timestamp, null, null, null); return false;
            }
            return !ProductHomes.sweep(this, file, path);
        }
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        if (name.equals("java") && file.getParentFile() != null && file.getParentFile().getName().equals("bin")) {
            addRuntime(file.getParentFile().getParentFile(), false, "Installation binary found; discovered executable was not run", timestamp, null, null, null);
        }
        // File presence is evidence independently of the optional archive metadata budget.
        if (file.isFile() && (name.endsWith(".war") || name.endsWith(".ear")) && !applicationsByPath.containsKey(path)) {
            if (applications.size() < 500) {
                Map application = map("id", id("app", hostId + path), "hostId", hostId, "name", file.getName(),
                    "type", name.endsWith(".war") ? "WAR" : "EAR", "path", path, "state", null,
                    "evidence", "Archive file found; deployment and runtime state not verified", "observedAt", timestamp);
                applications.add(application); applicationsByPath.put(path, application);
            } else warn("Application row limit reached; additional archive file findings were not retained");
        }
        if (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
            if (ArchiveMetadata.isKnownCandidate(name)) inspectJar(file, name);
            else if (deferredArchives.size() < 256) deferredArchives.add(file);
            else warn("Generic application archive candidate limit reached; priority server and JDBC metadata was retained");
        }
        return false;
    }

    /** Sleeps between batches only while the 1-minute load exceeds the CPU count; pauseMillis is the ceiling. */
    boolean pace(int count) {
        if (pauseMillis <= 0 || count % batchSize != 0) return true;
        int millis = pauseFor(loadAverage(), Runtime.getRuntime().availableProcessors(), pauseMillis);
        if (millis > 0) try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); warn("Assessment interrupted"); return false; }
        return true;
    }
    /** Full pause at twice the CPU count; no /proc/loadavg keeps the fixed pause. */
    static int pauseFor(String loadavg, int cpus, int pauseMillis) {
        if (loadavg == null) return pauseMillis;
        try {
            double load = Double.parseDouble(loadavg.trim().split("\\s+")[0]), excess = (load - cpus) / Math.max(1, cpus);
            return excess <= 0 ? 0 : (int) Math.min(pauseMillis, Math.ceil(pauseMillis * excess));
        } catch (RuntimeException e) { return pauseMillis; }
    }
    static String loadAverage() {
        try { return readFile(new File("/proc/loadavg"), 256); } catch (IOException e) { return null; }
    }

    String normalizeHome(File candidate) throws IOException {
        File home = candidate.getCanonicalFile();
        if (home.getName().equals("jre") && new File(home.getParentFile(), "bin/javac").isFile()) home = home.getParentFile();
        return home.getPath();
    }
    Map addRuntime(File candidate, boolean used, String evidence, String observedAt, String version, String vendor, String arch) {
        try {
            String path = normalizeHome(candidate);
            // Application JVM evidence establishes a runtime without a compiler.
            // Unused standalone runtimes remain outside the application inventory.
            Map existing = runtimes.get(path);
            boolean compiler = new File(path, "bin/javac").isFile();
            if (!new File(path, "bin/java").isFile() || (!compiler && !used && existing == null)) return null;
            if (privateRuntime(path)) return null;
            if (existing != null) {
                if (used) { existing.put("inUse", Boolean.TRUE); existing.put("evidence", evidence + (existing.get("runtimeMetadataSource") == null ? "" : "; runtime metadata " + existing.get("runtimeMetadataSource"))); existing.put("observedAt", observedAt); }
                if (version != null) existing.put("version", version);
                if (vendor != null) existing.put("vendor", vendor);
                if (arch != null) existing.put("architecture", arch);
                return existing;
            }
            if (runtimes.size() >= 256) { warn("Runtime row limit reached"); return null; }
            File release = new File(path, "release");
            if (release.isFile()) {
                String text = read(release, 32768);
                if (text != null) for (String line : text.split("\n")) {
                    if (version == null && line.startsWith("JAVA_VERSION=")) version = unquote(line.substring(13));
                    if (vendor == null && line.startsWith("IMPLEMENTOR=")) vendor = unquote(line.substring(12));
                    if (arch == null && line.startsWith("OS_ARCH=")) arch = unquote(line.substring(8));
                }
                evidence += "; release-file metadata";
            }
            Map legacy = version == null || vendor == null ? LegacyJavaMetadata.read(this, new File(path)) : Collections.emptyMap();
            if (version == null) version = (String) legacy.get("version");
            if (vendor == null) vendor = (String) legacy.get("vendor");
            if (legacy.get("runtimeMetadataSource") != null) evidence += "; runtime metadata " + legacy.get("runtimeMetadataSource");
            String elfArch = executableArchitecture(new File(path, "bin/java"));
            if (elfArch != null) arch = elfArch;
            Map row = map("id", id("runtime", hostId + path), "hostId", hostId, "javaHome", path,
                "version", version, "vendor", vendor, "architecture", arch, "kind", compiler ? "JDK" : "JRE",
                "inUse", Boolean.valueOf(used), "evidence", evidence, "observedAt", observedAt);
            if (legacy.get("runtimeMetadataSource") != null) row.put("runtimeMetadataSource", legacy.get("runtimeMetadataSource"));
            runtimes.put(path, row); return row;
        } catch (IOException e) { warn("Cannot resolve Java installation: " + candidate); return null; }
    }
    String executableArchitecture(File file) {
        if (!available()) return null;
        try {
            InputStream in = new FileInputStream(file); byte[] header = new byte[20]; int length = 0;
            try { while (length < header.length) { int n = in.read(header, length, header.length - length); if (n < 0) break; length += n; } } finally { in.close(); }
            bytesRead += length;
            if (length < 20 || header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return null;
            int machine = header[5] == 1 ? (header[18] & 255) + ((header[19] & 255) << 8) : (header[19] & 255) + ((header[18] & 255) << 8);
            if (machine == 3) return "i386 (32-bit)";
            if (machine == 62) return "x86_64 (64-bit)";
            if (machine == 183) return "aarch64 (64-bit)";
            return header[4] == 1 ? "32-bit ELF" : "64-bit ELF";
        } catch (IOException e) { return null; }
    }

    static final class ProcessContext {
        final ProcessLaunch launch; final long pid; final String javaHome; final Map process, application;
        Map server;
        boolean productVersion, springBootEvidence;
        String springBootVersion;
        ProcessContext(ProcessLaunch launch, long pid, String javaHome, Map process, Map application) {
            this.launch = launch; this.pid = pid; this.javaHome = javaHome; this.process = process; this.application = application;
        }
    }

    boolean collectorTool(ProcessLaunch launch) {
        if (!Arrays.asList("assessment.AssessmentCollector", "assessment.AgentTool", "assessment.UninstallWorker", "assessment.JavaProbe", "assessment.AgentUpdateWorker", "assessment.AgentUpdateProbe").contains(launch.mainClass)) return false;
        String helperRoot = collectorInstallationRoot == null ? "/opt/java-assessment-uninstall" :
            new File(new File(collectorInstallationRoot).getParentFile(), "java-assessment-uninstall").getPath();
        String updateRoot = new File(new File(helperRoot).getParentFile(), "java-assessment-update").getPath();
        for (String entry : launch.classpath) {
            if ((collectorInstallationRoot != null && under(entry, collectorInstallationRoot)) || under(entry, helperRoot) || under(entry, updateRoot)) return true;
        }
        return false;
    }

    void discoverProcesses() { discoverProcesses(new File("/proc")); }
    void discoverProcesses(File procRoot) {
        List<File> directories = new ArrayList<File>(); BoundedDirectory proc = null;
        String ownPid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        try {
            proc = BoundedDirectory.open(procRoot, deadline);
            for (File directory; available() && (directory = proc.next()) != null;) {
                String pid = directory.getName();
                if (!pid.matches("[0-9]+") || pid.equals(ownPid)) continue;
                if (directories.size() == 4096) { warn("Process metadata limit reached"); break; }
                directories.add(directory);
            }
        } catch (IOException e) { warn("Process directory enumeration incomplete (read or resource limit)"); }
        finally { if (proc != null) proc.close(); }
        Collections.sort(directories);
        for (File directory : directories) {
            if (!available()) break;
            String pid = directory.getName();
            File exe = null, cwd = null;
            try { File candidate = new File(directory, "exe"), resolved = candidate.getCanonicalFile(); if (!resolved.equals(candidate.getAbsoluteFile()) && ProcessLaunch.javaExecutable(resolved.getPath())) exe = resolved; }
            catch (IOException e) { }
            String comm = metadataText(new File(directory, "comm"), 256, false);
            // RHEL 5 may not expose comm. A readable cmdline remains useful when proc/exe is protected.
            if (exe == null && comm != null && !"java".equals(comm.trim())) continue;
            String text = metadataText(new File(directory, "cmdline"), 65536, false);
            if (text == null || text.length() == 0) { if ("java".equals(comm == null ? null : comm.trim())) restrictedProcesses++; continue; }
            String[] args = text.split("\u0000");
            if (exe == null && (args.length == 0 || !ProcessLaunch.javaExecutable(args[0]))) continue;
            if (processes.size() >= 512) { warn("Java process row limit reached"); break; }
            try { File candidate = new File(directory, "cwd"), resolved = candidate.getCanonicalFile(); if (!resolved.equals(candidate.getAbsoluteFile())) cwd = resolved; }
            catch (IOException e) { }
            ProcessLaunch launch = ProcessLaunch.parse(args, cwd);
            if (collectorTool(launch)) continue;
            File processHome = null;
            String runtimeEvidence = "Live /proc/" + pid + "/exe; collector process excluded";
            if (exe != null) processHome = exe.getParentFile().getParentFile();
            else {
                restrictedProcesses++;
                if (args.length > 0 && new File(args[0]).isAbsolute()) {
                    File candidate = linkedPath(new File(args[0]));
                    if (candidate != null && candidate.isFile() && ProcessLaunch.javaExecutable(candidate.getPath()) && candidate.getParentFile() != null && candidate.getParentFile().getName().equals("bin"))
                        processHome = candidate.getParentFile().getParentFile();
                }
                runtimeEvidence = "Java executable path from live process argv[0]; proc/exe access unavailable";
            }
            String javaHome = null;
            if (processHome != null) {
                // The observed Java home is inventoried even without a compiler.
                try { javaHome = normalizeHome(processHome); }
                catch (IOException e) { warn("Cannot resolve Java process home: " + processHome); }
                addRuntime(processHome, true, runtimeEvidence, timestamp, null, null, null);
            }
            String name = launch.mainClass != null ? launch.mainClass : launch.jarPath != null ? new File(launch.jarPath).getName() : launch.moduleName != null ? launch.moduleName : "Java process";
            Map process = map("id", id("process", hostId + ":" + pid), "hostId", hostId, "pid", Long.valueOf(pid), "name", name,
                "javaHome", javaHome, "mainClass", launch.mainClass, "launchJar", launch.jarPath, "moduleName", launch.moduleName,
                "serverType", launch.serverType, "applicationType", launch.applicationType, "uid", processUid(directory),
                "evidence", exe != null ? "Live Java executable and parsed launcher; application arguments are not retained" : "Live Java cmdline; executable identity and relative paths are limited by process permissions",
                "observedAt", timestamp);
            processes.add(process);
            if (launch.opaqueArguments) note("A Java argument file was not inspected; launcher classification may be incomplete");
            Map app = null;
            if ((launch.mainClass != null || launch.jarPath != null || launch.moduleName != null) && applications.size() < 500) {
                app = map("id", id("app", hostId + ":process:" + pid), "hostId", hostId, "name", name, "type", launch.applicationType == null ? "Java" : launch.applicationType,
                    "applicationType", launch.applicationType, "mainClass", launch.mainClass, "path", launch.jarPath, "pid", Long.valueOf(pid),
                    "javaHome", javaHome, "state", "PROCESS_RUNNING", "evidence", "Live Java launcher; application health and JDBC use are not tested", "observedAt", timestamp);
                applications.add(app); process.put("applicationId", app.get("id"));
                if (launch.jarPath != null) { File f = linkedPath(new File(launch.jarPath)); if (f != null) applicationsByPath.put(f.getPath(), app); }
            }
            ProcessContext context = new ProcessContext(launch, Long.parseLong(pid), javaHome, process, app);
            if (launch.serverType != null) {
                String serverName = launch.serverName == null ? launch.serverType + " process " + pid : launch.serverName;
                String key = launch.serverType + ":" + serverName + ("WebLogic".equals(launch.serverType) ? ":process:" + pid : "");
                context.server = map("id", id("server", hostId + key), "hostId", hostId, "type", launch.serverType, "name", serverName,
                    "version", null, "path", launch.serverBase, "pid", Long.valueOf(pid), "javaHome", javaHome, "mainClass", launch.mainClass,
                    "state", "PROCESS_RUNNING", "evidence", "Known server launcher in a live Java process; service health not tested", "observedAt", timestamp);
                if (servers.size() < 256) servers.put(key, context.server); else warn("Server row limit reached");
                if (app != null) { app.put("serverName", serverName); app.put("serverId", context.server.get("id")); }
            }
            serverDataSources.discover(context);
            jdbcDrivers.discover(context, directory);
            discoverLinked(context);
            SpringConfiguration.discover(this, context, directory);
            SpringIntegration.enrich(this, context, directory);
        }
        if (restrictedProcesses > 0) warn("Process permissions limited executable, working-directory or command metadata for " + restrictedProcesses + " Java process(es); readable launcher evidence was retained");
        if (restrictedArtifacts > 0) warn("The collector user could not access " + restrictedArtifacts + " process-linked artifact location(s); versions and JDBC inventory may be incomplete");
    }

    Long processUid(File directory) {
        String status = metadataText(new File(directory, "status"), 16384, false);
        if (status != null) for (String line : status.split("\n")) if (line.startsWith("Uid:")) {
            try { return Long.valueOf(line.substring(4).trim().split("\\s+")[0]); } catch (RuntimeException e) { return null; }
        }
        return null;
    }
    File linkedPath(File file) {
        if (file == null || !file.isAbsolute() || excluded(file.getPath())) return null;
        try { File canonical = file.getCanonicalFile(); if (canonical.getParentFile() == null || excluded(canonical.getPath())) return null; return canonical; }
        catch (IOException e) { return null; }
    }
    boolean visitLinked(File file) {
        if (!available()) return false;
        String key = visitedKey(file.getPath());
        if (visited.contains(key)) return true;
        // Process evidence has its own budget: the later sweep cannot starve it, nor it the sweep.
        if (filesVisited - sweepFiles >= maxFiles) { warn("File count limit reached; remaining process artifacts were not examined"); return false; }
        visited.add(key); filesVisited++;
        return pace(filesVisited - sweepFiles);
    }
    void linkedFile(File source, ProcessContext context) {
        File file = linkedPath(source); if (file == null || !visitLinked(file)) return;
        if (!file.isFile() || !file.canRead()) { restrictedArtifacts++; return; }
        inspectArtifact(file, context, true);
    }
    void linkedTree(File source, int depth, ProcessContext context, boolean knownOnly) {
        File root = linkedPath(source); if (root == null || !available()) return;
        if (!root.isDirectory() || !root.canRead()) { if (root.exists()) restrictedArtifacts++; return; }
        if (!visitLinked(root)) return;
        // Product/runtime/JDBC metadata precedes arbitrary application archives; each pass streams.
        for (int pass = 0; pass < 2; pass++) {
        BoundedDirectory files = null;
        try {
        files = BoundedDirectory.open(root, deadline);
        for (File child; (child = files.next()) != null;) {
            if (!available() || filesVisited - sweepFiles >= maxFiles) return;
            File canonical = linkedPath(child); if (canonical == null || !canonical.getPath().equals(child.getAbsolutePath())) continue;
            String name = child.getName().toLowerCase(Locale.ENGLISH);
            if (child.isDirectory()) {
                if (pass == 1 && depth > 0 && !Arrays.asList("logs", "log", "tmp", "temp", "data", ".git").contains(name)) linkedTree(child, depth - 1, context, knownOnly);
                continue;
            }
            boolean known = ArchiveMetadata.isKnownCandidate(name);
            if ((pass == 0) != known) continue;
            if (name.equals("manifest.mf") && context.server != null) { if (visitLinked(canonical)) inspectProductManifest(canonical, context); continue; }
            if ((known || !knownOnly) && (name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear"))) linkedFile(canonical, context);
            else visitLinked(canonical);
        }
        } catch (IOException e) { warn("Process-linked directory enumeration incomplete (read or resource limit): " + root.getPath()); }
        finally { if (files != null) files.close(); }
        }
    }
    void discoverLinked(ProcessContext context) {
        ProcessLaunch launch = context.launch;
        serverDataSources.discover(context);
        if (launch.jarPath != null) linkedFile(new File(launch.jarPath), context);
        if (launch.serverBase != null && !"JBoss".equals(launch.serverType)) processRoot(new File(launch.serverBase));
        if (launch.serverHome != null) {
            File home = new File(launch.serverHome);
            if ("Tomcat".equals(launch.serverType)) {
                linkedFile(new File(home, "lib/catalina.jar"), context);
                linkedTree(new File(home, "lib"), 0, context, true);
                if (launch.serverBase != null) { linkedTree(new File(launch.serverBase, "lib"), 0, context, true); linkedTree(new File(launch.serverBase, "webapps"), 3, context, false); }
            } else if ("WebLogic".equals(launch.serverType)) {
                // serverHome is WL_HOME/server; the Oracle Home inventory names the installed release and patches.
                File oracleHome = home.getParentFile() == null ? null : home.getParentFile().getParentFile();
                File registry = oracleHome == null ? null : linkedPath(new File(oracleHome, "inventory/registry.xml"));
                if (registry != null && registry.isFile() && visitLinked(registry) && context.server != null) {
                    Map product = ProductHomes.webLogicRegistry(this, registry);
                    if (product.get("version") != null && !context.productVersion) {
                        context.server.put("version", product.get("version")); context.productVersion = true;
                        context.server.put("evidence", "Live WebLogic launcher; Oracle Home inventory registry read without loading classes"); context.server.put("scanScope", "process-linked");
                        if (context.application != null) { context.application.put("type", "WebLogic"); context.application.put("applicationType", "WebLogic"); context.application.put("frameworkVersion", product.get("version")); }
                    }
                    for (String field : new String[]{"distribution", "patches"}) if (product.get(field) != null) context.server.put(field, product.get(field));
                }
                linkedFile(new File(home, "lib/weblogic.jar"), context);
            } else if ("JBoss".equals(launch.serverType)) {
                List<String> roots = new ArrayList<String>(launch.modulePaths);
                if (roots.isEmpty()) roots.add(new File(home, "modules").getPath());
                for (String path : roots) {
                    File module = new File(path);
                    for (String prefix : new String[]{"system/layers/base/", ""}) {
                        for (String product : new String[]{"org/jboss/as/product", "org/jboss/as/version", "org/jboss/as/server", "org/wildfly/core/version"}) linkedTree(new File(module, prefix + product), 4, context, true);
                    }
                }
                linkedTree(new File(home, "bin"), 0, context, true);
                File base = launch.serverBase != null && (launch.serverBaseExplicit || !launch.serverBase.equals(launch.serverHome)) ? new File(launch.serverBase) : new File(home, "standalone");
                linkedTree(new File(base, "deployments"), 3, context, false);
                processRoot(base);
                File versionText = linkedPath(new File(home, "version.txt"));
                // The product module manifest stays first; version.txt only fills a missing release.
                if (!context.productVersion && context.server != null && versionText != null && versionText.isFile() && versionText.length() <= 4096 && visitLinked(versionText)) {
                    String[] product = ProductHomes.versionText(read(versionText, 4096));
                    if (product != null) {
                        context.server.put("type", product[0]); context.server.put("version", product[1]); context.productVersion = true;
                        context.server.put("evidence", "Live JBoss-family launcher; product version.txt read without loading classes"); context.server.put("scanScope", "process-linked");
                        context.process.put("serverType", product[0]);
                    }
                }
            }
        }
        for (int pass = 0; pass < 2; pass++) for (String entry : launch.classpath) {
            File path = new File(entry); boolean wildcard = path.getName().equals("*");
            if (wildcard) { if (pass == 0 && path.getParentFile() != null) linkedTree(path.getParentFile(), 0, context, true); continue; }
            boolean known = ArchiveMetadata.isKnownCandidate(path.getName().toLowerCase(Locale.ENGLISH));
            if ((pass == 0) != known) continue;
            if (entry.toLowerCase(Locale.ENGLISH).endsWith(".jar")) linkedFile(path, context);
        }
    }
    void processRoot(File candidate) {
        File base = linkedPath(candidate);
        // Only recognizable server bases: a WebLogic cwd fallback may be any directory.
        if (base != null && !processRoots.contains(base.getPath()) && processRoots.size() < 64 && (new File(base, "config/config.xml").isFile()
            || new File(base, "conf/server.xml").isFile() || new File(base, "deployments").isDirectory())) processRoots.add(base.getPath());
    }
    void inspectProductManifest(File file, ProcessContext context) {
        if (!Boolean.TRUE.equals(settings.get("inspectArchives")) || context.server == null || !"JBoss".equals(context.launch.serverType)) return;
        String text = read(file, 65536); if (text == null) return;
        try {
            String[] release = productRelease(text);
            if (release != null) {
                String version = release[1];
                context.server.put("type", release[0]); context.server.put("version", version);
                context.productVersion = true;
                context.server.put("evidence", "Live JBoss-family launcher; product release manifest inspected without loading classes"); context.server.put("scanScope", "process-linked");
                context.process.put("serverType", context.server.get("type"));
                if (context.application != null) { context.application.put("applicationType", context.server.get("type")); context.application.put("type", context.server.get("type")); context.application.put("frameworkVersion", version); }
            }
        } catch (IOException e) { warn("Product release manifest could not be parsed"); }
    }
    /** {type, version} from a JBoss/WildFly product module manifest, or null. */
    static String[] productRelease(String text) throws IOException {
        Attributes attributes = new Manifest(new ByteArrayInputStream(text.getBytes("UTF-8"))).getMainAttributes();
        String product = clean(attributes.getValue("JBoss-Product-Release-Name")), version = clean(attributes.getValue("JBoss-Product-Release-Version"));
        if (product == null) product = clean(attributes.getValue("JBoss-Project-Release-Name"));
        if (version == null) version = clean(attributes.getValue("JBoss-Project-Release-Version"));
        if (product == null || version == null) return null;
        String lower = product.toLowerCase(Locale.ENGLISH);
        if (!lower.contains("wildfly") && !lower.contains("jboss")) return null;
        return new String[]{lower.contains("wildfly") ? "WildFly" : "JBoss", version};
    }

    void inspectJar(File file, String name) { inspectArtifact(file, null, false); }
    void inspectArtifact(File file, ProcessContext context, boolean linked) {
        if (!available()) return;
        String path;
        try { path = file.getCanonicalPath(); } catch (IOException e) { return; }
        Map metadata = archiveMetadata.inspect(file);
        if (metadata == null) metadata = Collections.emptyMap();
        if (context != null && context.launch.serverType == null && onProcessClasspath(context, path) && Boolean.TRUE.equals(metadata.get("springBootLibrary"))) {
            context.springBootEvidence = true;
            context.springBootVersion = clean(metadata.get("version"));
        }
        String evidence = clean(metadata.get("evidence")); if (evidence == null) evidence = "Archive file found; classes were not executed";
        if (linked) evidence += "; process-linked file; discovered independently of includePaths; runtime use of packaged libraries not verified";
        String appType = clean(metadata.get("applicationType")), main = ProcessLaunch.identifier(clean(metadata.get("startClass")));
        if (main == null) main = ProcessLaunch.identifier(clean(metadata.get("mainClass")));
        boolean launchArtifact = context != null && context.launch.jarPath != null && samePath(context.launch.jarPath, path);
        boolean applicationArtifact = appType != null || path.toLowerCase(Locale.ENGLISH).endsWith(".war") || path.toLowerCase(Locale.ENGLISH).endsWith(".ear");
        Map application = context == null || applicationArtifact && !launchArtifact ? applicationsByPath.get(path) : context.application;
        if (application == null && applicationArtifact && applications.size() < 500) {
            application = map("id", id("app", hostId + path), "hostId", hostId, "name", file.getName(), "type", appType == null ? path.toLowerCase(Locale.ENGLISH).endsWith(".war") ? "WAR" : "EAR" : appType,
                "path", path, "state", null, "evidence", evidence + "; deployment and runtime state not verified", "observedAt", timestamp);
            applications.add(application); applicationsByPath.put(path, application);
            if (context != null && context.server != null) { application.put("serverName", context.server.get("name")); application.put("pid", Long.valueOf(context.pid)); application.put("scanScope", "process-linked"); }
        }
        if (application != null && (context == null || launchArtifact || applicationArtifact)) {
            if (context == null && !metadata.isEmpty()) application.put("evidence", evidence + "; deployment and runtime state not verified");
            if (appType != null) { application.put("type", appType); application.put("applicationType", appType); }
            if (metadata.get("version") != null) application.put("version", metadata.get("version"));
            if (metadata.get("frameworkVersion") != null) application.put("frameworkVersion", metadata.get("frameworkVersion"));
            if (metadata.get("embeddedServers") != null) application.put("embeddedServers", metadata.get("embeddedServers"));
            if (main != null) application.put("mainClass", main);
            if (context != null && launchArtifact) {
                String title = ProcessLaunch.label(clean(metadata.get("title")));
                if (title != null && !title.equalsIgnoreCase("Spring Boot") && !title.equalsIgnoreCase("JBoss Modules")) application.put("name", title);
                application.put("evidence", "Live Java launcher; " + evidence); application.put("scanScope", "process-linked"); context.process.put("applicationType", appType == null ? context.launch.applicationType : appType); if (main != null) context.process.put("mainClass", main);
            }
        }
        String type = clean(metadata.get("serverType")), version = clean(metadata.get("serverVersion"));
        if (type != null) {
            if (context != null && context.server != null && (type.equals(context.launch.serverType) || "JBoss".equals(context.launch.serverType) && (type.equals("JBoss") || type.equals("WildFly")))) {
                if (version != null && !context.productVersion) {
                    context.server.put("version", version); context.server.put("type", type); context.process.put("serverType", type); context.server.put("evidence", "Live server launcher; " + evidence);
                    if (context.application != null) { context.application.put("type", type); context.application.put("applicationType", type); context.application.put("frameworkVersion", version); }
                }
                context.server.put("scanScope", "process-linked");
            } else if (context == null && servers.size() < 256) {
                String key = "file:" + path; servers.put(key, map("id", id("server", hostId + key), "hostId", hostId, "type", type, "name", type + " installation", "version", version, "path", path, "state", null, "evidence", evidence + "; runtime state unknown", "observedAt", timestamp));
            }
        }
        Object rows = metadata.get("libraries");
        if (rows instanceof List) for (Object value : (List)rows) if (value instanceof Map) {
            Map library = (Map)value;
            Map row = addLibrary(clean(library.get("name")), clean(library.get("version")), clean(library.get("path")), clean(library.get("category")), "found", evidence + "; " + clean(library.get("evidence")), timestamp);
            if (row != null && context != null) {
                row.put("scanScope", "process-linked");
                Map association = map("pid", Long.valueOf(context.pid));
                if (application != null) { association.put("applicationId", application.get("id")); association.put("applicationName", application.get("name")); }
                if (context.server != null) association.put("serverName", context.server.get("name"));
                List associations = row.get("associations") instanceof List ? (List)row.get("associations") : new ArrayList();
                if (!associations.contains(association) && associations.size() < 16) associations.add(association);
                row.put("associations", associations);
                for (String field : new String[]{"pid", "applicationId", "applicationName", "serverName"}) { if (associations.size() == 1 && association.containsKey(field)) row.put(field, association.get(field)); else row.remove(field); }
            }
        }
        // Per-process Spring resolution follows discovery, after all driver libraries are associated.
    }
    boolean onProcessClasspath(ProcessContext context, String path) {
        for (String entry : context.launch.classpath) {
            File file = new File(entry);
            if (file.getName().equals("*") && file.getParentFile() != null) {
                File parent = new File(path).getParentFile();
                if (parent != null && samePath(file.getParentFile().getPath(), parent.getPath())) return true;
            } else if (samePath(entry, path)) return true;
        }
        return false;
    }
    void addConfiguredSpringDatasource(String path, Map configuration, Map application, ProcessContext context) {
        if (!Boolean.TRUE.equals(configuration.get("configurationPresent"))) return;
        // Datasource candidates belong to a live application instance. URLs are not retained,
        // so PID isolation also prevents identical driver metadata from merging distinct endpoints.
        String key = "spring-config:" + path + ":pid:" + context.pid + ":" + Json.stringify(configuration);
        Map row = configuredDatasourceRows.get(key);
        if (row == null) {
            if (datasources.size() >= 512) { warn("Datasource row limit reached; remaining configuration findings were not retained"); return; }
            String name = ProcessLaunch.label(clean(application.get("name"))); if (name == null) name = "Spring Boot";
            row = map("id", id("datasource", hostId + key), "hostId", hostId, "name", configuration.get("configuredName") != null ? configuration.get("configuredName") : name + " datasource",
                "sourcePath", path, "configurationSource", clean(configuration.get("configurationSource")),
                "state", "CONFIGURED", "evidenceLevel", "configuration", "scanScope", "process-linked",
                "evidence", "Local Spring datasource configuration candidate; programmatic overrides, driver loading and database connectivity were not verified",
                "observedAt", timestamp);
            for (String field : new String[]{"driverName", "driverVersion", "driverArtifact", "databaseType", "databaseHost", "databasePort", "databaseName", "databaseEndpoints", "configuredMaxPoolSize", "configurationKey", "datasourceKey", "configurationResolution", "activeProfiles", "jndiName"})
                if (configuration.get(field) != null) row.put(field, configuration.get(field));
            if (row.get("driverVersion") != null) row.put("driverVersionEvidence", "Matching packaged JDBC library; runtime loading not verified");
            configuredDatasourceRows.put(key, row); datasources.add(row);
        }
        Map association = map("pid", Long.valueOf(context.pid), "applicationId", application.get("id"), "applicationName", application.get("name"));
        List associations = row.get("associations") instanceof List ? (List)row.get("associations") : new ArrayList();
        if (!associations.contains(association)) {
            if (associations.size() < 16) associations.add(association);
            else warn("Spring datasource process association limit reached; additional associations were not retained");
        }
        row.put("associations", associations);
        for (String field : new String[]{"pid", "applicationId", "applicationName"}) {
            if (associations.size() == 1) row.put(field, association.get(field)); else row.remove(field);
        }
    }
    static boolean samePath(String first, String second) {
        try { return new File(first).getCanonicalPath().equals(second); } catch (IOException e) { return false; }
    }
    Map addLibrary(String name, String version, String path, String category, String usage, String evidence, String observedAt) {
        String key = String.valueOf(path) + ":" + name + ":" + version;
        if (libraryRows.containsKey(key)) return libraryRows.get(key);
        if (libraries.size() >= 1000) { warn("Library row limit reached"); return null; }
        Map row = map("id", id("library", hostId + key), "hostId", hostId, "name", name, "version", version, "path", path, "category", category, "usage", usage, "evidence", evidence, "observedAt", observedAt);
        libraryKeys.add(key); libraryRows.put(key, row); libraries.add(row); return row;
    }

    void reuseWebLogicSnapshot() {
        String rootPath = config.getProperty("snapshotRoot");
        if (rootPath == null || rootPath.trim().length() == 0) return;
        if (!available()) return;
        try {
            File root = new File(rootPath).getCanonicalFile();
            File pointer = new File(root, "latest-event-path");
            if (!pointer.isFile()) { warn("Existing WebLogic snapshot pointer is unavailable"); return; }
            String path = read(pointer, 4096); if (path == null) return;
            File snapshotFile = new File(path.trim(), "snapshot.json").getCanonicalFile();
            if (!under(snapshotFile.getPath(), root.getPath())) { warn("Rejected WebLogic snapshot outside configured root"); return; }
            String text = read(snapshotFile, 1024 * 1024); if (text == null) return;
            Object parsed = Json.parse(text); if (!(parsed instanceof Map)) { warn("Existing snapshot is not an object"); return; }
            Map snapshot = (Map) parsed;
            String observedAt = clean(snapshot.get("capturedAt"));
            if (observedAt == null || !observedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z")) { warn("Existing snapshot has no valid original timestamp"); return; }
            String evidence = "Existing WebLogic JMX snapshot " + snapshotFile.getPath() + "; original observation time preserved; no new WLST execution";
            String serverName = clean(snapshot.get("serverName"));
            if (serverName == null) { warn("Existing snapshot lacks serverName"); return; }
            Map props = snapshot.get("jvmProperties") instanceof Map ? (Map) snapshot.get("jvmProperties") : Collections.emptyMap();
            if (props.get("java.home") != null) addRuntime(new File(String.valueOf(props.get("java.home"))), true, evidence, observedAt,
                clean(props.get("java.version")), clean(props.get("java.vendor")), clean(props.get("os.arch")));
            String key = "WebLogic:" + serverName;
            servers.put(key, map("id", id("server", hostId + key), "hostId", hostId, "type", "WebLogic", "name", serverName,
                "version", clean(snapshot.get("weblogicVersion")), "path", null, "state", clean(snapshot.get("serverState")),
                "evidence", evidence, "observedAt", observedAt));
            Object sources = snapshot.get("jdbcDataSources");
            if (sources instanceof List) for (Object value : (List) sources) {
                if (!(value instanceof Map) || datasources.size() >= 512) continue;
                Map ds = (Map) value; String name = clean(ds.get("name")); if (name == null) continue;
                Map row = map("id", id("datasource", hostId + serverName + name), "hostId", hostId, "name", name, "serverName", serverName,
                    "driverName", clean(ds.get("driverName")), "driverVersion", clean(ds.get("driverVersion")),
                    "state", clean(ds.get("state")), "poolCapacity", ds.get("poolCapacity"), "poolAvailable", ds.get("poolAvailable"),
                    "activeConnections", ds.get("activeConnections"), "evidence", evidence, "observedAt", observedAt);
                datasources.add(row);
                if (row.get("driverName") != null) addLibrary((String) row.get("driverName"), (String) row.get("driverVersion"), null,
                    "JDBC", "confirmed", evidence + "; driver reported by datasource " + name + "; JAR location not proven", observedAt);
            }
            note("WebLogic/JDBC runtime rows reuse observations from " + observedAt + "; check each row timestamp");
        } catch (Exception e) { warn("Existing WebLogic snapshot could not be processed: " + e.getClass().getSimpleName()); }
    }
}
