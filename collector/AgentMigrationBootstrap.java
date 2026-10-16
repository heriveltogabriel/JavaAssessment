package assessment;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

/** Fixed-root bootstrap and recovery entry. Never accepts remote code paths. */
public final class AgentMigrationBootstrap {
    static Map bridge(File jar) throws Exception {
        if (!jar.isFile()) return null;
        JarFile archive = new JarFile(jar, false);
        try {
            JarEntry entry = archive.getJarEntry("migration/bridge.json"); if (entry == null) return null;
            AgentInstallLayout.require(entry.getSize() > 0 && entry.getSize() <= 32768, "INVALID_BRIDGE");
            InputStream input = archive.getInputStream(entry); Object parsed;
            try { parsed = Json.parse(AssessmentScan.readStream(input, 32768)); } finally { input.close(); }
            AgentInstallLayout.require(parsed instanceof Map, "INVALID_BRIDGE"); Map metadata = (Map)parsed;
            AgentInstallLayout.require(AgentInstallLayout.number(metadata.get("schema")) == 1
                && AgentUpdateWorker.version(AgentInstallLayout.text(metadata.get("fromVersion")))
                && AgentUpdateWorker.compare(AgentInstallLayout.text(metadata.get("fromVersion")), "1.5.12") >= 0
                && AgentUpdateWorker.version(AgentInstallLayout.text(metadata.get("bridgeVersion")))
                && AgentUpdateWorker.version(AgentInstallLayout.text(metadata.get("targetVersion")))
                && AgentInstallLayout.digest(AgentInstallLayout.text(metadata.get("fromCollectorSha256")))
                && metadata.get("supportFiles") instanceof Map && metadata.get("allowedHelperHashes") instanceof List
                && "migration/baseline/collector.jar".equals(metadata.get("recoveryResource")), "INVALID_BRIDGE");
            AgentInstallLayout.require(AgentInstallLayout.text(metadata.get("bridgeVersion")).equals(AgentUpdateWorker.jarVersion(jar)), "INVALID_BRIDGE");
            return metadata;
        } finally { archive.close(); }
    }
    static String originVersion(AgentInstallLayout layout) throws Exception {
        File stored = AgentInstallLayout.child(layout.state, "origin/origin.json");
        if (stored.isFile()) return AgentInstallLayout.text(AgentInstallLayout.read(stored).get("version"));
        Map metadata = bridge(AgentInstallLayout.child(layout.root, "collector.jar"));
        AgentInstallLayout.require(metadata != null, "KNOWN_BRIDGE_REQUIRED"); return AgentInstallLayout.text(metadata.get("fromVersion"));
    }
    static void probeBridge() throws Exception {
        File source = new File(AgentMigrationBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map metadata = bridge(source); if (metadata == null) return;
        File installed = new File(AgentInstallLayout.ROOT, "collector.jar");
        String installedHash = AgentBundleManifest.hash(installed);
        AgentInstallLayout.require(installedHash.equals(metadata.get("fromCollectorSha256")) || installedHash.equals(AgentBundleManifest.hash(source)), "BRIDGE_BASELINE_MISMATCH");
        JarFile archive = new JarFile(source, false);
        try {
            JarEntry resource = archive.getJarEntry("migration/baseline/collector.jar");
            AgentInstallLayout.require(resource != null && resource.getSize() > 0 && resource.getSize() <= 32L * 1024 * 1024, "INVALID_BRIDGE");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); InputStream input = archive.getInputStream(resource);
            try { byte[] block = new byte[16384]; long bytes = 0; int n; while ((n = input.read(block)) != -1) { bytes += n; AgentInstallLayout.require(bytes <= 32L * 1024 * 1024, "INVALID_BRIDGE"); digest.update(block, 0, n); } }
            finally { input.close(); }
            StringBuffer hex = new StringBuffer(); byte[] bytes = digest.digest();
            for (int i = 0; i < bytes.length; i++) { int n = bytes[i] & 255; if (n < 16) hex.append('0'); hex.append(Integer.toHexString(n)); }
            AgentInstallLayout.require(hex.toString().equals(metadata.get("fromCollectorSha256")), "INVALID_BRIDGE");
        } finally { archive.close(); }
    }
    /** Executed under the real lifecycle flock, after the legacy ACK/cleanup. */
    static void captureOrigin(AgentInstallLayout layout) throws Exception { captureOrigin(layout, new Production(layout)); }
    static AgentBundleInstaller recoveryCopier(AgentInstallLayout layout, AgentBundleInstaller.Platform platform) throws Exception {
        AgentBundleInstaller copier = new AgentBundleInstaller(layout, platform, null, AgentTool.load(AgentInstallLayout.child(layout.root, "conf/collector.properties")));
        copier.journal = AgentInstallLayout.read(layout.journal()); copier.validateJournal(); return copier;
    }
    static void captureOrigin(AgentInstallLayout layout, AgentBundleInstaller.Platform platform) throws Exception {
        AgentInstallLayout.require(!new File(layout.home, "journal.json").exists() && !new File(layout.root, "collector-spool/update-request.json").exists(), "LEGACY_UPDATE_ACTIVE");
        File directory = AgentInstallLayout.child(layout.state, "origin"), record = AgentInstallLayout.child(directory, "origin.json");
        if (record.isFile()) {
            Map metadata = bridge(AgentInstallLayout.child(layout.root, "collector.jar")); Map prior = AgentInstallLayout.read(record);
            AgentInstallLayout.require(metadata != null && metadata.get("fromVersion").equals(prior.get("version"))
                && metadata.get("supportProfileId").equals(prior.get("supportProfileId")), "RECOVERY_REQUIRED");
            Map files = (Map)prior.get("files");
            for (Object item : files.keySet()) AgentInstallLayout.require(files.get(item).equals(AgentBundleManifest.hash(AgentInstallLayout.child(directory, "files/" + item))), "RECOVERY_REQUIRED");
            return;
        }
        File jar = AgentInstallLayout.child(layout.root, "collector.jar"); Map metadata = bridge(jar);
        AgentInstallLayout.require(metadata != null, "KNOWN_BRIDGE_REQUIRED");
        Map supports = (Map)metadata.get("supportFiles"), files = new LinkedHashMap();
        AgentInstallLayout.require(supports.size() > 0 && supports.size() <= 128, "INVALID_BRIDGE");
        for (Object item : supports.keySet()) {
            String name = AgentInstallLayout.text(item);
            AgentInstallLayout.require(name.matches("([A-Za-z0-9_-]+[.]sh|lib/[A-Za-z0-9_.-]+[.]jar|update-public[.]der)")
                && supports.get(item).equals(AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, name))), "INCOMPATIBLE_ASSETS");
        }
        File helper = AgentInstallLayout.child(layout.home, "helper.jar");
        AgentInstallLayout.require(helper.isFile() && ((List)metadata.get("allowedHelperHashes")).contains(AgentBundleManifest.hash(helper)), "INCOMPATIBLE_HELPER");
        AgentInstallLayout.directory(directory); AgentBundleInstaller copier = recoveryCopier(layout, platform);
        for (Object item : supports.keySet()) {
            String name = AgentInstallLayout.text(item); copier.copySlotFile(AgentInstallLayout.child(layout.root, name), AgentInstallLayout.child(directory, "files/" + name)); files.put(name, supports.get(item));
        }
        File baseline = AgentInstallLayout.child(directory, "files/collector.jar"); AgentInstallLayout.directory(baseline.getParentFile());
        String expectedBaseline = AgentInstallLayout.text(metadata.get("fromCollectorSha256"));
        boolean baselineReady = baseline.isFile() && expectedBaseline.equals(AgentBundleManifest.hash(baseline));
        if (!baselineReady) {
        AgentInstallLayout.require(!baseline.exists() || expectedBaseline.equals(copier.journal.get("originExtractIntent")), "RECOVERY_REQUIRED");
        File extracted = new File(baseline.getPath() + ".extract");
        AgentInstallLayout.require(!extracted.exists() || expectedBaseline.equals(copier.journal.get("originExtractIntent"))
            && extracted.equals(extracted.getCanonicalFile()) && extracted.isFile() && extracted.delete(), "LOCAL_CHECK_FAILED");
        copier.journal.put("originExtractIntent", expectedBaseline); copier.save("PREPARED");
        JarFile archive = new JarFile(jar, false);
        try {
            JarEntry resource = archive.getJarEntry("migration/baseline/collector.jar");
            AgentInstallLayout.require(resource != null && resource.getSize() > 0 && resource.getSize() <= 32L * 1024 * 1024, "INVALID_BRIDGE");
            InputStream input = archive.getInputStream(resource); FileOutputStream output = new FileOutputStream(extracted);
            try { byte[] buffer = new byte[16384]; long bytes = 0; int n;
                while ((n = input.read(buffer)) != -1) { bytes += n; AgentInstallLayout.require(bytes <= 32L * 1024 * 1024, "INVALID_BRIDGE"); output.write(buffer, 0, n); }
                output.getFD().sync();
            } finally { try { input.close(); } finally { output.close(); } }
        } finally { archive.close(); }
        AgentTool.ownerOnly(extracted);
        AgentInstallLayout.require(expectedBaseline.equals(AgentBundleManifest.hash(extracted)) && extracted.renameTo(baseline), "INVALID_BRIDGE");
        }
        AgentInstallLayout.require(metadata.get("fromCollectorSha256").equals(AgentBundleManifest.hash(baseline))
            && metadata.get("fromVersion").equals(AgentUpdateWorker.jarVersion(baseline)), "INVALID_BRIDGE");
        if (copier.journal.remove("originExtractIntent") != null) copier.save("PREPARED");
        files.put("collector.jar", metadata.get("fromCollectorSha256"));
        for (String name : new String[]{"conf/root-service-mode", "conf/update-capability", "conf/uninstall-capability"}) {
            File source = AgentInstallLayout.child(layout.root, name);
            if (source.isFile()) { copier.copySlotFile(source, AgentInstallLayout.child(directory, "files/" + name)); files.put(name, AgentBundleManifest.hash(source)); }
        }
        copier.copySlotFile(helper, AgentInstallLayout.child(directory, "helper.jar"));
        layout.write(record, AssessmentScan.map("schema", new Long(1), "version", metadata.get("fromVersion"), "supportProfileId", metadata.get("supportProfileId"), "files", files, "helperSha256", AgentBundleManifest.hash(helper)));
    }
    static void captureLocalOrigin(AgentInstallLayout layout, Map journal) throws Exception { captureLocalOrigin(layout, journal, new Production(layout)); }
    static void captureLocalOrigin(AgentInstallLayout layout, Map journal, AgentBundleInstaller.Platform platform) throws Exception {
        File record = AgentInstallLayout.child(layout.state, "origin/origin.json");
        if (record.isFile()) {
            Map existing = AgentInstallLayout.read(record);
            AgentInstallLayout.require(Boolean.TRUE.equals(existing.get("localInstall")) && journal.get("targetVersion").equals(existing.get("version")), "RECOVERY_REQUIRED"); return;
        }
        File tx = layout.transaction(AgentInstallLayout.text(journal.get("operationId")));
        Map signed = AgentBundleManifest.verify(AgentInstallLayout.child(tx, "manifest.json"), AgentInstallLayout.child(tx, "manifest.sig"),
            AgentInstallLayout.child(layout.root, "update-public.der"), AgentInstallLayout.child(tx, "bundle.zip"), AgentInstallLayout.text(journal.get("targetVersion")));
        Map inventory = (Map)signed.get("files"), files = new LinkedHashMap(); File origin = record.getParentFile(); AgentInstallLayout.directory(origin);
        AgentBundleInstaller copier = recoveryCopier(layout, platform);
        for (Object item : inventory.keySet()) {
            String name = AgentInstallLayout.text(item); if (!name.startsWith("code/")) continue; name = name.substring(5);
            File source = AgentInstallLayout.child(layout.root, name); if (!source.exists()) continue;
            Object expected = ((Map)inventory.get(item)).get("sha256");
            AgentInstallLayout.require(expected.equals(AgentBundleManifest.hash(source)), "LOCAL_CHECK_FAILED");
            copier.copySlotFile(source, AgentInstallLayout.child(origin, "files/" + name)); files.put(name, expected);
        }
        AgentInstallLayout.require(files.containsKey("collector.jar") && journal.get("targetVersion").equals(AgentUpdateWorker.jarVersion(AgentInstallLayout.child(layout.root, "collector.jar"))), "LOCAL_CHECK_FAILED");
        for (String name : new String[]{"conf/root-service-mode", "conf/update-capability", "conf/uninstall-capability"}) {
            File source = AgentInstallLayout.child(layout.root, name);
            if (source.isFile()) { copier.copySlotFile(source, AgentInstallLayout.child(origin, "files/" + name)); files.put(name, AgentBundleManifest.hash(source)); }
        }
        File helper = AgentInstallLayout.child(layout.home, "helper.jar"); copier.copySlotFile(helper, AgentInstallLayout.child(origin, "helper.jar"));
        layout.write(record, AssessmentScan.map("schema", new Long(1), "version", journal.get("targetVersion"), "files", files, "helperSha256", AgentBundleManifest.hash(helper), "localInstall", Boolean.TRUE));
    }
    static void revalidateIdentity(AgentInstallLayout layout, Map journal) throws Exception {
        Properties config = AgentTool.load(AgentInstallLayout.child(layout.root, "conf/collector.properties"));
        AgentInstallLayout.require(journal.get("hostId").equals(AssessmentCollector.required(config, "hostId"))
            && journal.get("configSha256").equals(AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "conf/collector.properties")))
            && journal.get("pinSha256").equals(AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "update-public.der")))
            && journal.get("javaPathSha256").equals(AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "conf/java.path")))
            && journal.get("caSha256").equals(AgentBundleManifest.hash(new File(AssessmentCollector.required(config, "tlsCaFile")))), "IDENTITY_CHANGED");
        if (journal.get("previousPointer") == null) AgentInstallLayout.require(journal.get("bridgeSha256").equals(AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "collector.jar"))), "IDENTITY_CHANGED");
        else AgentInstallLayout.require(journal.get("previousPointer").equals(layout.current()), "IDENTITY_CHANGED");
    }
    static void checkExternalCa(Properties config) throws Exception {
        if (!"/opt/java-assessment-collector/collector-spool".equals(config.getProperty("spoolDirectory"))) return;
        File file = new File(AssessmentCollector.required(config, "tlsCaFile")).getAbsoluteFile();
        AgentInstallLayout.require(file.isFile() && file.equals(file.getCanonicalFile()), "LOCAL_CHECK_FAILED");
        File stat = new File("/usr/bin/stat"); if (!stat.canExecute()) stat = new File("/bin/stat");
        AgentInstallLayout.require(stat.canExecute(), "LOCAL_CHECK_FAILED");
        // Check the named CA and every parent before any authenticated download.
        // Public mode 644 is fine; ownership and other-account write access are
        // what matters. No value from this command is exposed in diagnostics.
        while (file != null) {
            Process process = new ProcessBuilder(new String[]{stat.getPath(), "-c", "%u:%a", file.getPath()}).start();
            String value; InputStream input = process.getInputStream();
            try { value = AssessmentScan.readStream(input, 128).trim(); } finally { input.close(); }
            AgentInstallLayout.require(process.waitFor() == 0 && value.matches("0:[0-7]{3,4}")
                && (Integer.parseInt(value.substring(2), 8) & 022) == 0, "LOCAL_CHECK_FAILED");
            file = file.getParentFile();
        }
    }
    static boolean continueBridge(Properties config) throws Exception {
        if (!"/opt/java-assessment-collector/collector-spool".equals(config.getProperty("spoolDirectory"))) return false;
        AgentInstallLayout layout = AgentInstallLayout.production();
        if (layout.current() != null || bridge(AgentInstallLayout.child(layout.root, "collector.jar")) == null) return false;
        // A terminal result still waiting for the exact old ACK is not a handoff.
        if (new File(layout.home, "journal.json").exists() || new File(layout.root, "collector-spool/update-request.json").exists()) return true;
        AgentBundleTransport remote = new AgentBundleTransport(config); Map response = remote.action();
        Object action = response.get("action"); if (!(action instanceof Map) && "UPDATE_AGENT_V3".equals(response.get("type"))) action = response;
        if (!(action instanceof Map)) return true; // Bridge never leases an assessment.
        AgentBundleInstaller installer = new AgentBundleInstaller(layout, new Production(layout), remote, config);
        try { installer.prepareContinuation((Map)action); }
        catch (Exception failure) {
            if (installer.journal != null && installer.journal.get("bundleSha256") != null) try {
                installer.report("UNCONFIRMED", failure instanceof AgentInstallLayout.Failure ? ((AgentInstallLayout.Failure)failure).diagnostic : "NETWORK_UNCONFIRMED", AgentTransport.VERSION);
            } catch (Exception ignored) {}
            throw failure;
        }
        return true;
    }
    static final class Production implements AgentBundleInstaller.Platform {
        static final String LEGACY_SERVICE = "java-assessment-update.service";
        static final String RECOVERY_SERVICE = "java-assessment-recovery.service";
        static final String LEGACY_WAS_ACTIVE = "bootstrapLegacyHelperWasActive";
        final AgentInstallLayout layout;
        final Commands commands;
        static final class ServiceState {
            final String load, active, sub;
            final long mainPid, controlPid, job;
            ServiceState(String load, String active, String sub, long mainPid, long controlPid, long job) {
                this.load = load; this.active = active; this.sub = sub;
                this.mainPid = mainPid; this.controlPid = controlPid; this.job = job;
            }
            boolean running() { return "loaded".equals(load) && ("active".equals(active) || "reloading".equals(active)) && mainPid > 0; }
            boolean stopped() { return ("loaded".equals(load) || "not-found".equals(load))
                && ("inactive".equals(active) || "failed".equals(active)) && mainPid == 0 && controlPid == 0 && job == 0; }
            static ServiceState parse(String raw, int status) throws Exception {
                AgentInstallLayout.require(raw != null && raw.length() <= 8192, "SERVICE_STATE_UNKNOWN");
                Map fields = new LinkedHashMap(); String[] lines = raw.split("\\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i]; if (line.length() == 0) continue;
                    int split = line.indexOf('=');
                    AgentInstallLayout.require(split > 0 && !fields.containsKey(line.substring(0, split)), "SERVICE_STATE_UNKNOWN");
                    fields.put(line.substring(0, split), line.substring(split + 1));
                }
                AgentInstallLayout.require(fields.keySet().equals(new HashSet(Arrays.asList(new String[]{"LoadState", "ActiveState", "SubState", "MainPID", "ControlPID", "Job"}))), "SERVICE_STATE_UNKNOWN");
                String load = AgentInstallLayout.text(fields.get("LoadState")), active = AgentInstallLayout.text(fields.get("ActiveState")), sub = AgentInstallLayout.text(fields.get("SubState"));
                AgentInstallLayout.require(load.matches("loaded|not-found|error|masked|stub|merged")
                    && active.matches("active|reloading|inactive|failed|activating|deactivating") && sub.matches("[a-z-]{1,64}")
                    && (status == 0 || (status == 1 || status == 4) && "not-found".equals(load)), "SERVICE_STATE_UNKNOWN");
                String[] counters = {"MainPID", "ControlPID", "Job"}; long[] values = new long[3];
                for (int i = 0; i < counters.length; i++) {
                    String value = AgentInstallLayout.text(fields.get(counters[i]));
                    // systemctl219/239 may print an explicitly empty Job=
                    // instead of Job=0. A missing property is still rejected
                    // by the exact field inventory above.
                    if (i == 2 && value.length() == 0) { values[i] = 0; continue; }
                    AgentInstallLayout.require(value.matches("[0-9]{1,10}"), "SERVICE_STATE_UNKNOWN"); values[i] = Long.parseLong(value);
                    AgentInstallLayout.require(values[i] <= 4294967295L, "SERVICE_STATE_UNKNOWN");
                }
                ServiceState result = new ServiceState(load, active, sub, values[0], values[1], values[2]);
                AgentInstallLayout.require(status == 0 || "not-found".equals(load) && "inactive".equals(active) && result.stopped(), "SERVICE_STATE_UNKNOWN");
                return result;
            }
        }
        // Object adapters remain package-private, never CLI/environment switches.
        static class Commands {
            static final class Captured {
                final String text; final int status;
                Captured(String text, int status) { this.text = text; this.status = status; }
            }
            boolean systemd(AgentInstallLayout layout) throws Exception {
                String mode = new String(AgentInstallLayout.bytes(new File(layout.home, "scheduler"), 64), "UTF-8").trim();
                AgentInstallLayout.require(mode.equals("systemd") || mode.equals("sysv"), "LOCAL_CHECK_FAILED");
                if (!mode.equals("systemd")) return false;
                AgentInstallLayout.require(new File("/run/systemd/system").isDirectory(), "LOCAL_CHECK_FAILED");
                return true;
            }
            String executable(String name) throws Exception {
                AgentInstallLayout.require(name.equals("systemd-run") || name.equals("systemctl") || name.equals("stat"), "LOCAL_CHECK_FAILED");
                File path = new File("/usr/bin", name); if (!path.canExecute()) path = new File("/bin", name);
                AgentInstallLayout.require(path.isFile() && path.canExecute(), "SERVICE_FAILED"); return path.getPath();
            }
            int run(String[] arguments, long timeout) throws Exception { return AgentUpdateWorker.Production.process(arguments, timeout); }
            ServiceState status(String unit) throws Exception {
                String prefix = "java-assessment-bootstrap-", suffix = ".service";
                AgentInstallLayout.require(LEGACY_SERVICE.equals(unit) || RECOVERY_SERVICE.equals(unit)
                    || unit.startsWith(prefix) && unit.endsWith(suffix)
                        && AgentInstallLayout.uuid(unit.substring(prefix.length(), unit.length() - suffix.length())), "INVALID_OPERATION");
                Captured output = read(new String[]{executable("systemctl"), "show", "--all", "--no-pager", unit,
                    "-p", "LoadState", "-p", "ActiveState", "-p", "SubState", "-p", "MainPID", "-p", "ControlPID", "-p", "Job"});
                return ServiceState.parse(output.text, output.status);
            }
            String fileMetadata(File marker) throws Exception {
                Captured output = read(new String[]{executable("stat"), "-c", "%u:%a:%h:%f", marker.getPath()});
                AgentInstallLayout.require(output.status == 0 && output.text.length() <= 128, "LOCAL_CHECK_FAILED"); return output.text.trim();
            }
            Captured read(String[] arguments) throws Exception {
                ProcessBuilder builder = new ProcessBuilder(arguments);
                builder.redirectErrorStream(true); builder.environment().put("LC_ALL", "C"); builder.environment().put("SYSTEMD_LOG_LEVEL", "err");
                final Process process = builder.start(); final ByteArrayOutputStream output = new ByteArrayOutputStream();
                final boolean[] invalid = new boolean[1];
                Thread drain = new Thread(new Runnable() { public void run() {
                    try {
                        InputStream input = process.getInputStream();
                        try { byte[] block = new byte[1024]; int count;
                            while ((count = input.read(block)) != -1) {
                                if (output.size() + count > 8192) { invalid[0] = true; process.destroy(); break; }
                                output.write(block, 0, count);
                            }
                        } finally { input.close(); }
                    } catch (IOException failure) { invalid[0] = true; }
                }}); drain.setDaemon(true); drain.start();
                try {
                    process.getOutputStream().close(); long deadline = System.currentTimeMillis() + 15000L;
                    while (true) {
                        try {
                            int result = process.exitValue(); drain.join(1000L);
                            AgentInstallLayout.require(!drain.isAlive() && !invalid[0], "SERVICE_STATE_UNKNOWN");
                            return new Captured(new String(output.toByteArray(), "UTF-8"), result);
                        } catch (IllegalThreadStateException running) {
                            AgentInstallLayout.require(System.currentTimeMillis() < deadline, "SERVICE_STATE_UNKNOWN"); Thread.sleep(100L);
                        }
                    }
                } finally { process.destroy(); try { process.getInputStream().close(); } catch (IOException ignored) {} }
            }
        }
        Production(AgentInstallLayout layout) { this(layout, new Commands()); }
        Production(AgentInstallLayout layout, Commands commands) { this.layout = layout; this.commands = commands; }
        void preparedIdentity(Map transaction) throws Exception {
            AgentInstallLayout.require(transaction != null && AgentInstallLayout.uuid(AgentInstallLayout.text(transaction.get("operationId")))
                && "PREPARED".equals(transaction.get("phase")) && transaction.get("previousPointer") == null
                && AgentInstallLayout.text(transaction.get("preparedDirectory")).matches("prepared-[0-9]{1,8}")
                && !AgentInstallLayout.child(layout.root, "current.json").exists()
                && !AgentInstallLayout.child(layout.home, "journal.json").exists()
                && !AgentInstallLayout.child(layout.root, "collector-spool/update-request.json").exists(), "LEGACY_UPDATE_ACTIVE");
            Map stored = AgentInstallLayout.read(layout.journal());
            AgentInstallLayout.require(stored.equals(transaction), "RECOVERY_REQUIRED");
            AgentMigrationBootstrap.revalidateIdentity(layout, stored);
        }
        void rememberLegacyState(Map transaction) throws Exception {
            if (transaction.containsKey(LEGACY_WAS_ACTIVE)) {
                AgentInstallLayout.require(transaction.get(LEGACY_WAS_ACTIVE) instanceof Boolean, "RECOVERY_REQUIRED"); return;
            }
            ServiceState state = commands.status(LEGACY_SERVICE);
            AgentInstallLayout.require("loaded".equals(state.load) && (state.running() && state.controlPid == 0 && state.job == 0 || state.stopped()), "SERVICE_STATE_UNKNOWN");
            AgentBundleInstaller recorder = recoveryCopier(layout, this);
            AgentInstallLayout.require(recorder.journal.equals(transaction), "RECOVERY_REQUIRED");
            transaction.put(LEGACY_WAS_ACTIVE, Boolean.valueOf(state.running()));
            recorder.journal = transaction; recorder.save("PREPARED");
        }
        void clearLegacyStop(Map transaction) throws Exception {
            File marker = AgentInstallLayout.child(layout.home, "stop"); if (!marker.exists()) return;
            AgentInstallLayout.require(marker.isFile() && marker.length() == 0 && commands.run(new String[]{"/bin/sh", AgentInstallLayout.child(layout.root, "root-trust.sh").getPath(), "private", marker.getPath()}, 30000L) == 0, "LOCAL_CHECK_FAILED");
            String metadata = commands.fileMetadata(marker);
            AgentInstallLayout.require(metadata.matches("0:(400|600):1:[a-f0-9]{4,8}"), "LOCAL_CHECK_FAILED");
            String[] fields = metadata.split(":"); long mode = Long.parseLong(fields[3], 16);
            AgentInstallLayout.require((mode & 0xf000) == 0x8000 && (mode & 07777) == Integer.parseInt(fields[1], 8), "LOCAL_CHECK_FAILED");
            preparedIdentity(transaction); ServiceState helper = commands.status(LEGACY_SERVICE);
            AgentInstallLayout.require("loaded".equals(helper.load) && helper.stopped() && marker.delete(), "LOCAL_CHECK_FAILED");
        }
        boolean restoreLegacyAfterQueueFailure(Map transaction, ServiceState bootstrap) {
            try {
                if (!Boolean.TRUE.equals(transaction.get(LEGACY_WAS_ACTIVE)) || !bootstrap.stopped()
                    || AgentInstallLayout.child(layout.home, "bootstrap").exists()) return false;
                preparedIdentity(transaction);
                ServiceState recovery = commands.status(RECOVERY_SERVICE);
                if (!recovery.stopped()) return false;
                ServiceState helper = commands.status(LEGACY_SERVICE);
                if (!"loaded".equals(helper.load) || !helper.stopped()) return false;
                preparedIdentity(transaction);
                // A known failed enqueue may restore only the previously active
                // helper. Unknown jobs, timeouts and an installed BOOT stay put.
                // The original loop leaves this private marker on SIGTERM;
                // PID1's ExecStart=loop does not perform the SysV start cleanup.
                clearLegacyStop(transaction);
                int started = commands.run(new String[]{commands.executable("systemctl"), "start", LEGACY_SERVICE}, 30000L);
                if (started != 0) return false;
                ServiceState restored = commands.status(LEGACY_SERVICE);
                return restored.running() && restored.controlPid == 0 && restored.job == 0;
            } catch (Exception uncertain) { return false; /* Unconfirmed restoration is never reported as success. */ }
        }
        void queueBootstrap(File script, Map transaction, String operation) throws Exception {
            preparedIdentity(transaction); rememberLegacyState(transaction); preparedIdentity(transaction);
            int stopped = commands.run(new String[]{commands.executable("systemctl"), "stop", LEGACY_SERVICE}, 60000L);
            AgentInstallLayout.require(stopped == 0, "SERVICE_FAILED");
            ServiceState helper = commands.status(LEGACY_SERVICE);
            // Older systemd may retain failed/143 after a successful stop of the
            // shell supervisor. Only quiescence permits handoff, not exit status:
            // loaded + inactive/failed + zero main/control PID + no pending job.
            AgentInstallLayout.require("loaded".equals(helper.load) && helper.stopped(), "SERVICE_FAILED");
            preparedIdentity(transaction);
            String unit = "java-assessment-bootstrap-" + operation + ".service";
            int result = commands.run(new String[]{commands.executable("systemd-run"), "--unit=" + unit,
                "--uid=root", "--gid=root", "--service-type=simple", "/bin/sh", script.getPath(), "install-bootstrap", operation}, 60000L);
            if (result == 0) return;
            // A timed-out client may already have queued a PID-1 job. Never
            // restart the old helper in that indeterminate case.
            AgentInstallLayout.require(result != 30 && result != 31, "SERVICE_FAILED");
            ServiceState bootstrap = commands.status(unit);
            if (bootstrap.running()) return;
            restoreLegacyAfterQueueFailure(transaction, bootstrap);
            throw new AgentInstallLayout.Failure("SERVICE_FAILED");
        }
        File script(Map transaction) throws Exception {
            File script = AgentInstallLayout.child(layout.home, "bootstrap/update-bootstrap.sh");
            if (!script.isFile() && transaction != null) script = AgentInstallLayout.child(layout.transaction(AgentInstallLayout.text(transaction.get("operationId"))), AgentInstallLayout.text(transaction.get("preparedDirectory")) + "/bootstrap/update-bootstrap.sh");
            return script;
        }
        public void local(String action, Map transaction) throws Exception {
            AgentInstallLayout.require(Arrays.asList(new String[]{"check", "sync", "snapshot-services", "snapshot-uninstall", "activate-uninstall", "restore-uninstall", "pause", "start", "activate-services", "restore-services", "install-bootstrap", "probe-manager"}).contains(action), "LOCAL_CHECK_FAILED");
            if ("sync".equals(action)) { AgentInstallLayout.require(commands.run(new String[]{"/bin/sync"}, 30000) == 0, "LOCAL_CHECK_FAILED"); return; }
            File script = script(transaction);
            if ("install-bootstrap".equals(action)) script = AgentInstallLayout.child(layout.transaction(AgentInstallLayout.text(transaction.get("operationId"))), AgentInstallLayout.text(transaction.get("preparedDirectory")) + "/bootstrap/update-bootstrap.sh");
            if (!script.isFile() && "check".equals(action)) {
                AgentInstallLayout.require(AgentUpdateWorker.Production.process(new String[]{"/bin/sh", "/opt/java-assessment-collector/root-trust.sh", "package", "/opt/java-assessment-collector"}, 30000) == 0
                    && AgentUpdateWorker.Production.process(new String[]{"/bin/sh", "/opt/java-assessment-collector/root-trust.sh", "package", "/opt/java-assessment-update"}, 30000) == 0, "LOCAL_CHECK_FAILED"); return;
            }
            String operation = transaction == null ? "none" : AgentInstallLayout.text(transaction.get("operationId"));
            AgentInstallLayout.require(operation.equals("none") || AgentInstallLayout.uuid(operation), "INVALID_OPERATION");
            if ("install-bootstrap".equals(action) && commands.systemd(layout)) {
                AgentInstallLayout.require(AgentInstallLayout.uuid(operation), "INVALID_OPERATION");
                // The original collector has ProtectSystem=full. Its children
                // cannot create /etc/systemd/system recovery units. PID 1 must
                // launch a separate SERVICE, without inheriting that namespace.
                // These options are available in systemd 219 (RHEL 7); --scope,
                // --wait, --collect and --no-block are deliberately not used.
                queueBootstrap(script, transaction, operation); return;
            }
            AgentInstallLayout.require(commands.run(new String[]{"/bin/sh", script.getPath(), action, operation}, 1700000) == 0, "SERVICE_FAILED");
        }
        public int probe(File code, String version, boolean heartbeat) throws Exception {
            AgentInstallLayout.require(code.getParentFile().equals(layout.releases) && AgentInstallLayout.identifier(code.getName()) && AgentUpdateWorker.version(version), "LOCAL_CHECK_FAILED");
            Map transaction = layout.journal().isFile() ? AgentInstallLayout.read(layout.journal()) : null;
            return AgentUpdateWorker.Production.probeResult(AgentUpdateWorker.Production.process(new String[]{"/bin/sh", script(transaction).getPath(), "probe", code.getName(), version, heartbeat ? "heartbeat" : "probe"}, 90000), heartbeat);
        }
        public boolean serviceEvidence(Map pointer) throws Exception {
            File source = new File(AgentMigrationBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!source.isFile() || !pointer.get("managerSha256").equals(AgentBundleManifest.hash(source))) return false;
            if (AgentUpdateWorker.Production.process(new String[]{"/bin/sh", script(null).getPath(), "evidence", AgentInstallLayout.text(pointer.get("managerSha256"))}, 30000) != 0) return false;
            AgentRecoveryService.install(layout, new File("/etc/systemd/system"), new AgentRecoveryService.Host());
            return true;
        }
        public boolean processUses(File directory) throws Exception {
            File[] processes = new File("/proc").listFiles(); if (processes == null) return true;
            for (int i = 0; i < processes.length; i++) if (processes[i].getName().matches("[0-9]+")) {
                File command = new File(processes[i], "cmdline");
                File cwd = null;
                try {
                    cwd = new File(processes[i], "cwd").getCanonicalFile();
                    if (directory.isDirectory() && (cwd.equals(directory) || cwd.getPath().startsWith(directory.getPath() + "/"))) return true;
                } catch (IOException gone) { /* process may exit */ }
                if (command.canRead()) try {
                    InputStream input = new FileInputStream(command);
                    try {
                        if (commandReferencesWithCwd(AssessmentScan.readStream(input, 65536), directory, cwd)) return true;
                    } finally { input.close(); }
                } catch (Exception ignored) { /* process may exit */ }
            }
            return false;
        }
    }
    static boolean commandReferences(String commandLine, String exact) {
        if (commandLine.indexOf(exact + "/") >= 0) return true;
        int index = commandLine.indexOf(exact);
        while (index >= 0) {
            int end = index + exact.length();
            boolean left = index == 0 || commandLine.charAt(index - 1) == '\000' || commandLine.charAt(index - 1) == ':' || commandLine.charAt(index - 1) == ' ';
            boolean right = end == commandLine.length() || commandLine.charAt(end) == '\000' || commandLine.charAt(end) == ':' || commandLine.charAt(end) == ' ';
            if (left && right) return true;
            index = commandLine.indexOf(exact, index + 1);
        }
        return false;
    }
    static boolean commandReferencesWithCwd(String commandLine, File target, File cwd) throws Exception {
        if (commandReferences(commandLine, target.getPath())) return true;
        if (cwd == null) return false;
        String[] arguments = commandLine.split("\000");
        for (int i = 0; i < arguments.length; i++) {
            String[] entries = arguments[i].split(":");
            for (int j = 0; j < entries.length; j++) {
                String entry = entries[j]; if (entry.length() == 0 || entry.startsWith("-")) continue;
                boolean wildcard = entry.endsWith("/*"); if (wildcard) entry = entry.substring(0, entry.length() - 2);
                File resolved = new File(entry); if (!resolved.isAbsolute()) resolved = new File(cwd, entry); resolved = resolved.getCanonicalFile();
                if (resolved.equals(target) || resolved.getPath().startsWith(target.getPath() + "/")
                    || wildcard && target.getPath().startsWith(resolved.getPath() + "/")) return true;
            }
        }
        return false;
    }
    static AgentBundleInstaller installer(AgentInstallLayout layout) throws Exception {
        Properties config = AgentTool.load(AgentInstallLayout.child(layout.root, "conf/collector.properties"));
        return new AgentBundleInstaller(layout, new Production(layout), new AgentBundleTransport(config), config);
    }
    public static void main(String[] args) {
        AgentBundleInstaller running = null;
        try {
            AgentInstallLayout layout = AgentInstallLayout.production();
            if (args.length == 1 && "cycle".equals(args[0])) { running = installer(layout); running.cycle(); return; }
            if (args.length == 1 && "maintenance".equals(args[0])) { new AgentOwnedCleanup(layout, new Production(layout)).maintain(System.currentTimeMillis()); return; }
            if (args.length == 1 && "initialize-local".equals(args[0])) {
                Properties config = AgentTool.load(AgentInstallLayout.child(layout.root, "conf/collector.properties"));
                AgentBundleInstaller.Remote noServerOperation = new AgentBundleInstaller.Remote() {
                    public Map request(String action, Map body) throws Exception { throw new IOException("Local administrative installation has no remote operation"); }
                    public void download(String asset, File target, long limit, String operationId) throws Exception { throw new IOException("Local administrative installation cannot download"); }
                };
                new AgentBundleInstaller(layout, new Production(layout), noServerOperation, config).initializeLocal(); return;
            }
            if (args.length == 1 && "code-path".equals(args[0])) { Map pointer = layout.current(); AgentInstallLayout.require(pointer != null, "RECOVERY_REQUIRED"); System.out.println(layout.code(pointer).getPath()); return; }
            if (args.length == 1 && "manager-path".equals(args[0])) { Map pointer = layout.current(); System.out.println(pointer == null ? new File(layout.home, "bootstrap/anchor.jar").getPath() : new File(layout.manager(pointer), "helper.jar").getPath()); return; }
            if (args.length == 1 && "manager-hash".equals(args[0])) { Map pointer = layout.current(); AgentInstallLayout.require(pointer != null, "RECOVERY_REQUIRED"); System.out.println(pointer.get("managerSha256")); return; }
            if (args.length == 2 && "manager-probe".equals(args[0])) {
                File source = new File(AgentMigrationBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                AgentInstallLayout.require(AgentInstallLayout.digest(args[1]) && args[1].equals(AgentBundleManifest.hash(source)), "MANAGER_PROBE_FAILED");
                Class.forName("assessment.AgentBundleRecovery"); Class.forName("assessment.AgentBundleInstaller");
                System.out.println("AGENT_MANAGER_PROBE=OK"); return;
            }
            if (args.length == 2 && "capture-origin".equals(args[0])) {
                AgentInstallLayout.require(AgentInstallLayout.uuid(args[1]), "INVALID_OPERATION"); Map journal = AgentInstallLayout.read(layout.journal()); revalidateIdentity(layout, journal);
                if (Boolean.TRUE.equals(journal.get("localInstall"))) captureLocalOrigin(layout, journal); else captureOrigin(layout); return;
            }
            if (args.length == 2 && "validate-prepared".equals(args[0])) {
                AgentInstallLayout.require(AgentInstallLayout.uuid(args[1]), "INVALID_OPERATION"); Map journal = AgentInstallLayout.read(layout.journal());
                AgentInstallLayout.require(args[1].equals(journal.get("operationId")) && "PREPARED".equals(journal.get("phase")), "INVALID_OPERATION");
                revalidateIdentity(layout, journal);
                File tx = layout.transaction(args[1]); File prepared = AgentInstallLayout.child(tx, AgentInstallLayout.text(journal.get("preparedDirectory")));
                Map manifest = AgentBundleManifest.verify(AgentInstallLayout.child(tx, "manifest.json"), AgentInstallLayout.child(tx, "manifest.sig"),
                    AgentInstallLayout.child(layout.root, "update-public.der"), AgentInstallLayout.child(tx, "bundle.zip"), AgentInstallLayout.text(journal.get("targetVersion")));
                Map files = (Map)manifest.get("files");
                for (Object item : files.keySet()) AgentInstallLayout.require(((Map)files.get(item)).get("sha256").equals(AgentBundleManifest.hash(AgentInstallLayout.child(prepared, AgentInstallLayout.text(item)))), "INVALID_BUNDLE");
                File trust = AgentInstallLayout.child(prepared, "bootstrap/root-trust.sh"); Properties config = AgentTool.load(AgentInstallLayout.child(layout.root, "conf/collector.properties"));
                AgentInstallLayout.require(AgentUpdateWorker.Production.process(new String[]{"/bin/sh", trust.getPath(), "ca", AssessmentCollector.required(config, "tlsCaFile")}, 30000) == 0, "LOCAL_CHECK_FAILED");
                return;
            }
            if (args.length == 2 && "seal-bootstrap".equals(args[0])) {
                AgentInstallLayout.require(AgentInstallLayout.uuid(args[1]), "INVALID_OPERATION");
                Map journal = AgentInstallLayout.read(layout.journal());
                AgentInstallLayout.require(args[1].equals(journal.get("operationId")), "INVALID_OPERATION");
                File tx = layout.transaction(args[1]);
                Map manifest = AgentBundleManifest.verify(AgentInstallLayout.child(tx, "manifest.json"), AgentInstallLayout.child(tx, "manifest.sig"),
                    AgentInstallLayout.child(layout.root, "update-public.der"), AgentInstallLayout.child(tx, "bundle.zip"), AgentInstallLayout.text(journal.get("targetVersion")));
                Map expected = new LinkedHashMap(), inventory = (Map)manifest.get("files");
                for (Object item : inventory.keySet()) {
                    String name = AgentInstallLayout.text(item), destination = "";
                    if (name.startsWith("bootstrap/")) destination = name.substring(10);
                    if (!destination.equals("")) expected.put(destination, ((Map)inventory.get(item)).get("sha256"));
                }
                expected.put("bundle-manifest.json", AgentBundleManifest.hash(AgentInstallLayout.child(tx, "manifest.json")));
                expected.put("bundle-manifest.sig", AgentBundleManifest.hash(AgentInstallLayout.child(tx, "manifest.sig")));
                File boot = AgentInstallLayout.child(layout.home, "bootstrap");
                File seal = AgentInstallLayout.child(boot, "bootstrap-owned.json");
                if (seal.isFile()) {
                    Map installed = AgentInstallLayout.read(seal); Map actual = AgentInstallLayout.inventory(boot);
                    actual.remove("bootstrap-owned.json"); actual.remove("recovery.service"); actual.remove("recovery.init");
                    AgentInstallLayout.require(actual.equals(installed.get("files")), "RECOVERY_REQUIRED");
                } else {
                    Map actual = AgentInstallLayout.inventory(boot);
                    AgentInstallLayout.require(actual.equals(expected), "INVALID_BUNDLE");
                    layout.write(seal, AssessmentScan.map("schema", new Long(1), "files", expected));
                }
                return;
            }
            if (args.length == 1 && "recover-manager".equals(args[0])) {
                AgentBundleInstaller installer = installer(layout); installer.journal = AgentInstallLayout.read(layout.journal()); installer.validateJournal();
                installer.rollback("MANAGER_PROBE_FAILED"); installer.finishFailure(); return;
            }
            throw new IllegalArgumentException("Unsupported bootstrap operation");
        } catch (Exception failure) {
            if (running != null && running.journal != null && !running.localInstall()) try {
                String version = AgentInstallLayout.text(running.journal.get("previousVersion")); Map pointer = running.layout.current();
                if (pointer != null) version = AgentInstallLayout.text(pointer.get("version"));
                running.report("UNCONFIRMED", failure instanceof AgentInstallLayout.Failure ? ((AgentInstallLayout.Failure)failure).diagnostic : "NETWORK_UNCONFIRMED", version);
            } catch (Exception ignored) { /* Best-effort status never destroys the durable transaction. */ }
            System.err.println("AGENT_BUNDLE_RETRY=" + (failure instanceof AgentInstallLayout.Failure ? ((AgentInstallLayout.Failure)failure).diagnostic : AgentTransport.safeError(failure)));
            System.exit(1);
        }
    }
}
