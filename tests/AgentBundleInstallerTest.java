package assessment;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Real signed ZIP/journal/files; OS services, probes and network are explicit adapters. */
public final class AgentBundleInstallerTest {
    static int checks;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    static String text(Object value) { return AgentInstallLayout.text(value); }
    static void put(File file, String value) throws Exception { file.getParentFile().mkdirs(); Files.writeString(file.toPath(), value); }
    static void copy(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) { for (Path file : paths.toList()) {
            check(!Files.isSymbolicLink(file), "fixture tree has no links"); Path to = target.resolve(source.relativize(file));
            if (Files.isDirectory(file)) Files.createDirectories(to); else Files.copy(file, to);
        } }
    }
    static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) { for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
    static final class Death extends Error { Death(String phase) { super(phase); } }
    static final class Fixture implements AgentBundleInstaller.Platform, AgentBundleInstaller.Remote, AutoCloseable {
        final Path source, directory; final Map metadata; final AgentInstallLayout layout; final Properties config = new Properties();
        final Map<String,String> preserved = new LinkedHashMap<>(); final List<String> reports = new ArrayList<>();
        String operation = "00000000-0000-0000-0000-000000000003", version, managerHash, bundleHash;
        long expiresMillis = System.currentTimeMillis() + 600000L;
        boolean active = true, bootstrapped, loadedManager, failProbe, failManager, heartbeatOffline, loseAck, corruptSignature;
        int pauses, heartbeatCount, transfers; String crashPhase, diagnostic;
        String ioPoint, ioPath; boolean ioArmed, ioCrash, noSpace; int ioFailures;
        long availableSpace = -1;
        final Set<String> livePaths = new HashSet<>();
        Fixture(Path source) throws Exception {
            this.source = source.toRealPath(); metadata = AgentInstallLayout.read(this.source.resolve("fixture.json").toFile());
            version = text(metadata.get("targetVersion")); managerHash = text(metadata.get("managerSha256"));
            bundleHash = text(AgentInstallLayout.read(asset("manifest")).get("sha256"));
            directory = Files.createTempDirectory("full-agent-lifecycle-").toRealPath();
            Path root = directory.resolve("collector"), home = directory.resolve("helper");
            copy(asset("root").toPath(), root); copy(asset("home").toPath(), home);
            layout = new AgentInstallLayout(root.toFile(), home.toFile(), new AgentInstallLayout.Io() {
                @Override void point(String point, File file) throws Exception {
                    if (ioArmed && point.equals(ioPoint) && file.getPath().contains(ioPath)) {
                        ioArmed = false; ioFailures++;
                        if (ioCrash) throw new Death("io-" + point);
                        throw new IOException("simulated disk write or rename failure");
                    }
                }
                @Override long usableSpace(File file) { return noSpace ? 0 : availableSpace >= 0 ? availableSpace : super.usableSpace(file); }
            });
            config.setProperty("hostId", "fixture-host"); config.setProperty("agentToken", "private-fixture-token");
            config.setProperty("serverUrl", "http://127.0.0.1:1"); config.setProperty("spoolDirectory", root.resolve("collector-spool").toString());
            config.setProperty("tlsCaFile", directory.resolve("external/ca.pem").toString());
            layout.initialize();
            File configFile = new File(layout.root, "conf/collector.properties"); configFile.getParentFile().mkdirs();
            try (OutputStream output = new FileOutputStream(configFile)) { config.store(output, "private fixture"); }
            put(new File(layout.root, "conf/installation-id"), "preserved-installation\n");
            put(new File(layout.root, "conf/java.path"), "/protected/java6/bin/java\n");
            put(new File(layout.root, "conf/root-service-mode"), "system-root\n");
            put(new File(layout.root, "conf/update-capability"), "update-v1\n");
            put(new File(layout.root, "collector-spool/pending-assessment.json"), "{\"pending\":true}\n");
            put(new File(layout.root, "collector-spool/latest-snapshot.json"), "{\"snapshot\":true}\n");
            put(new File(layout.root, "logs/install.log"), "preserved logs\n");
            put(directory.resolve("external/ca.pem").toFile(), "EXTERNAL-CA\n");
            put(directory.resolve("private-java/bin/java").toFile(), "PRIVATE-JAVA-MUST-SURVIVE\n");
            for (String file : new String[]{"collector/conf/collector.properties", "collector/conf/installation-id", "collector/conf/java.path",
                "collector/collector-spool/pending-assessment.json", "collector/collector-spool/latest-snapshot.json", "collector/logs/install.log", "external/ca.pem", "private-java/bin/java"})
                preserved.put(file, AgentBundleManifest.hash(directory.resolve(file).toFile()));
        }
        File asset(String name) { return source.resolve(text(metadata.get(name))).toFile(); }
        Map action() { return AssessmentScan.map("type", "UPDATE_AGENT_V3", "operationId", operation, "targetVersion", version, "signedPlanHash", "a".repeat(64), "expiresMillis", expiresMillis); }
        AgentBundleInstaller installer() { return new AgentBundleInstaller(layout, this, this, config); }
        void prepare() throws Exception { installer().prepareContinuation(action()); }
        void cycle() throws Exception { installer().cycle(); }
        void finish() throws Exception { loadedManager = true; for (int i = 0; i < 4 && layout.journal().isFile(); i++) cycle(); }
        void unchanged() throws Exception {
            for (var file : preserved.entrySet()) check(file.getValue().equals(AgentBundleManifest.hash(directory.resolve(file.getKey()).toFile())), "preserved " + file.getKey());
        }
        public void local(String action, Map transaction) throws Exception {
            if ("sync".equals(action) && transaction != null && text(transaction.get("phase")).equals(crashPhase)) { String phase = crashPhase; crashPhase = null; throw new Death(phase); }
            if ("install-bootstrap".equals(action)) {
                check(!new File(layout.home, "journal.json").exists(), "legacy journal cleaned before bootstrap");
                check(!new File(layout.root, "collector-spool/update-request.json").exists(), "legacy request cleaned before bootstrap");
                if (Boolean.TRUE.equals(transaction.get("localInstall"))) AgentMigrationBootstrap.captureLocalOrigin(layout, transaction, this);
                else AgentMigrationBootstrap.captureOrigin(layout, this);
                File boot = new File(layout.home, "bootstrap");
                if (!boot.exists()) {
                    File source = new File(layout.transaction(text(transaction.get("operationId"))), text(transaction.get("preparedDirectory")) + "/bootstrap");
                    copy(source.toPath(), boot.toPath());
                    Files.copy(new File(layout.transaction(text(transaction.get("operationId"))), "manifest.json").toPath(), new File(boot, "bundle-manifest.json").toPath());
                    Files.copy(new File(layout.transaction(text(transaction.get("operationId"))), "manifest.sig").toPath(), new File(boot, "bundle-manifest.sig").toPath());
                    AgentInstallLayout.durable(new File(boot, "bootstrap-owned.json"), AssessmentScan.map("schema", 1L, "files", AgentInstallLayout.inventory(boot)));
                }
                bootstrapped = true;
            }
            if ("snapshot-services".equals(action)) {
                File saved = new File(layout.transaction(text(transaction.get("operationId"))), "previous-services/collector.service"); put(saved, "owned fixed collector service\n");
            }
            if ("pause".equals(action)) { active = false; pauses++; }
            if ("activate-services".equals(action)) put(new File(layout.root, "conf/update-capability"), "update-v3\n");
            if ("probe-manager".equals(action) && failManager) throw new AgentInstallLayout.Failure("MANAGER_PROBE_FAILED");
            if ("start".equals(action)) active = true;
        }
        public int probe(File code, String expected, boolean heartbeat) throws Exception {
            check(expected.equals(AgentUpdateWorker.jarVersion(new File(code, "collector.jar"))), "probe loads expected version file");
            unchanged();
            if (heartbeat) { heartbeatCount++; return heartbeatOffline ? 10 : 0; }
            return failProbe && layout.current() != null ? 20 : 0;
        }
        public boolean serviceEvidence(Map pointer) throws Exception { return loadedManager && active && managerHash.equals(AgentBundleManifest.hash(new File(layout.manager(pointer), "helper.jar"))); }
        public boolean processUses(File file) { return livePaths.contains(file.getPath()); }
        public Map request(String action, Map body) throws Exception {
            check(operation.equals(body.get("operationId")) && "fixture-host".equals(body.get("hostId")), "authenticated operation binding");
            if ("claim".equals(action)) return AssessmentScan.map("operationId", operation, "executable", true, "targetVersion", version,
                "signedPlanHash", "a".repeat(64), "expiresMillis", expiresMillis, "bundleSha256", bundleHash, "managerSha256", managerHash);
            String status = text(body.get("status")); reports.add(status); diagnostic = text(body.get("diagnostic"));
            if ("SUCCEEDED".equals(status)) { check(loadedManager && heartbeatCount > 0, "success requires new manager and heartbeat"); if (loseAck) { loseAck = false; throw new IOException("lost terminal ACK"); } }
            return AssessmentScan.map("accepted", true, "operationId", operation, "status", status);
        }
        public void download(String part, File target, long limit, String id) throws Exception {
            File from = part.equals("manifest") ? asset("manifest") : part.equals("signature") ? asset("signature") : asset("bundle");
            byte[] bytes = Files.readAllBytes(from.toPath()); if (part.equals("signature") && corruptSignature) bytes[0] ^= 1;
            check(bytes.length <= limit && id.equals(operation), "bounded signed asset download"); Files.write(target.toPath(), bytes); transfers++;
        }
        @Override public void close() throws Exception { remove(directory); }
    }
    static void success(Path source) throws Exception {
        try (Fixture f = new Fixture(source)) {
            f.prepare(); check(f.bootstrapped && f.pauses == 0, "bridge prepares independent service without stopping its own collector");
            check(new File(f.layout.state, "origin/files/collector.jar").isFile(), "origin survives legacy cleanup independently");
            f.cycle(); check("REMOTE_CONFIRMING".equals(AgentInstallLayout.read(f.layout.journal()).get("phase")), "activation waits for manager handoff");
            check(!f.reports.contains("SUCCEEDED"), "old process cannot certify replaced manager");
            f.finish(); check(f.reports.contains("SUCCEEDED") && !f.layout.journal().exists(), "new manager confirms complete update and cleans journal");
            check(f.version.equals(f.layout.current().get("version")), "active pointer identifies target");
            check(!f.layout.transaction(f.operation).exists(), "signed download and staging really removed after ACK");
            check(new File(f.layout.state, "last-good.json").isFile(), "last good pointer persisted");
            f.unchanged();
        }
    }
    static void ackAndNetwork(Path source) throws Exception {
        try (Fixture f = new Fixture(source)) {
            f.prepare(); f.cycle(); f.loadedManager = true; f.heartbeatOffline = true;
            try { f.cycle(); throw new AssertionError("offline heartbeat accepted"); } catch (IOException expected) { checks++; }
            check(f.layout.current() != null && f.active && f.layout.journal().exists(), "network uncertainty retains healthy target and journal");
            f.heartbeatOffline = false; f.loseAck = true;
            try { f.cycle(); throw new AssertionError("lost ACK accepted"); } catch (IOException expected) { checks++; }
            int transfers = f.transfers, pauses = f.pauses;
            f.finish(); check(f.transfers == transfers && f.pauses == pauses, "ACK replay does not reinstall or stop collector");
            check(f.reports.stream().filter("SUCCEEDED"::equals).count() == 2, "terminal result replayed exactly once"); f.unchanged();
        }
    }
    static void failures(Path source) throws Exception {
        for (boolean manager : new boolean[]{false, true}) try (Fixture f = new Fixture(source)) {
            f.prepare(); if (manager) f.failManager = true; else f.failProbe = true;
            f.cycle(); check(f.reports.contains("ROLLED_BACK"), "local candidate/manager failure rolls back");
            check(f.layout.current() == null && "1.5.12".equals(AgentUpdateWorker.jarVersion(new File(f.layout.root, "collector.jar"))), "recovery returns to original 1.5.12, not bridge");
            check(new File(f.layout.root, "run-once.sh").canExecute() && new File(f.layout.root, "agent-tool.sh").canExecute(), "rollback restores executable entry scripts used directly by legacy poll");
            check(f.active && !f.layout.journal().exists(), "rolled-back collector restarted and result acknowledged"); f.unchanged();
        }
        try (Fixture f = new Fixture(source)) {
            put(new File(f.layout.home, "journal.json"), "{\"legacy\":true}");
            try { f.prepare(); throw new AssertionError("bootstrap bypassed active legacy transaction"); }
            catch (AgentInstallLayout.Failure expected) { check("LEGACY_UPDATE_ACTIVE".equals(expected.diagnostic), "legacy cleanup is a hard gate"); }
            check(!f.bootstrapped && f.pauses == 0, "active old worker is never overwritten or stopped");
        }
        try (Fixture f = new Fixture(source)) {
            f.corruptSignature = true;
            try { f.prepare(); throw new AssertionError("invalid signature accepted"); }
            catch (AgentInstallLayout.Failure expected) { check("INVALID_BUNDLE".equals(expected.diagnostic), "invalid bundle signature rejected"); }
            check(!f.bootstrapped && f.pauses == 0 && f.layout.current() == null, "invalid bundle cannot install bootstrap or pause collector"); f.unchanged();
        }
        try (Fixture f = new Fixture(source)) {
            f.prepare(); put(new File(f.layout.root, "conf/collector.properties"), "hostId=other-host\nagentToken=other-private-token\n");
            f.cycle(); check(f.reports.contains("FAILED") && "IDENTITY_CHANGED".equals(f.diagnostic), "manual configuration switch invalidates the prepared authorization");
            check(f.pauses == 0 && f.layout.current() == null, "identity change is detected before any scheduler pause or promotion");
        }
    }
    static void freshLocal(Path source) throws Exception {
        for (boolean rollback : new boolean[]{false, true}) try (Fixture f = new Fixture(source)) {
            File prepared = f.directory.resolve("fresh-signed-code").toFile();
            Map signed = AgentBundleManifest.verify(f.asset("manifest"), f.asset("signature"), f.asset("pin"), f.asset("bundle"), f.version);
            AgentBundleManifest.extractVerified(signed, f.asset("bundle"), prepared);
            File code = new File(prepared, "code");
            for (Object item : AgentInstallLayout.inventory(code).keySet()) {
                File from = AgentInstallLayout.child(code, text(item)), to = AgentInstallLayout.child(f.layout.root, text(item));
                Files.createDirectories(to.toPath().getParent()); Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(new File(code, "collector.jar").toPath(), new File(f.layout.home, "helper.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
            File input = new File(f.layout.root, "full-update"); input.mkdirs();
            Files.copy(f.asset("manifest").toPath(), new File(input, "manifest.json").toPath());
            Files.copy(f.asset("signature").toPath(), new File(input, "manifest.sig").toPath());
            Files.copy(f.asset("bundle").toPath(), new File(input, "bundle.zip").toPath());
            f.installer().initializeLocal(); check(f.bootstrapped && f.reports.isEmpty() && f.transfers == 0, "fresh installation uses only local signed assets and creates no server operation");
            f.failProbe = rollback; f.cycle(); f.finish();
            check(f.reports.isEmpty() && f.transfers == 0, "local initialization never calls remote claim/report/download");
            if (rollback) check(f.layout.current() == null && f.version.equals(AgentUpdateWorker.jarVersion(new File(f.layout.root, "collector.jar"))), "fresh local failure restores signed classic target");
            else check(f.version.equals(f.layout.current().get("version")) && !input.exists(), "fresh local success activates V3 and removes only its consumed public assets");
            f.unchanged();
        }
    }
    static void crashes(Path source) throws Exception {
        for (String phase : new String[]{"ACCEPTED", "DOWNLOADING", "VERIFIED", "PREPARED", "QUIESCING", "ACTIVATING", "LOCAL_VERIFYING", "REMOTE_CONFIRMING", "COMMITTED", "CLEANING"}) {
            try (Fixture f = new Fixture(source)) {
                f.crashPhase = phase;
                try { f.prepare(); f.cycle(); f.loadedManager = true; f.cycle(); throw new AssertionError("missing injected crash " + phase); }
                catch (Death death) { check(phase.equals(death.getMessage()), "crash at durable " + phase); }
                if (!f.bootstrapped) f.prepare();
                f.loadedManager = true; f.finish();
                check(!f.layout.journal().exists() && f.active, "resume completes or restores at " + phase);
                Map current = f.layout.current();
                check(current == null ? "1.5.12".equals(AgentUpdateWorker.jarVersion(new File(f.layout.root, "collector.jar"))) : f.version.equals(current.get("version")), "resume has complete origin or complete target at " + phase);
                f.unchanged();
            }
        }
    }
    static void ownedCleanup(Path source) throws Exception {
        String command = "/java\000-cp\000/opt/collector/releases/old/lib/tls.jar:/other.jar\000assessment.Main\000";
        check(AgentMigrationBootstrap.commandReferences(command, "/opt/collector/releases/old"), "live classpath library protects its release directory");
        check(AgentMigrationBootstrap.commandReferences(command, "/opt/collector/releases/old/lib/tls.jar"), "exact live JAR classpath token protects file cleanup");
        check(!AgentMigrationBootstrap.commandReferences(command, "/opt/collector/releases/old/lib/tls"), "similar filename is not mistaken for exact open JAR token");
        check(AgentMigrationBootstrap.commandReferencesWithCwd("java\000-cp\000collector.jar:lib/*\000assessment.Main\000", new File("/opt/collector/releases/old/collector.jar"), new File("/opt/collector/releases/old")), "relative manual JAR classpath is resolved against process cwd");
        check(AgentMigrationBootstrap.commandReferencesWithCwd("java\000-cp\000collector.jar:lib/*\000assessment.Main\000", new File("/opt/collector/releases/old/lib/tls.jar"), new File("/opt/collector/releases/old")), "relative classpath wildcard protects its actual library files");
        try (Fixture f = new Fixture(source)) {
            File orphan = new File(f.layout.releases, "old-owned"); orphan.mkdirs(); put(new File(orphan, "collector.jar"), "old owned code\n");
            AgentInstallLayout.durable(new File(orphan, "installed.json"), AssessmentScan.map("schema", 1L, "files", AgentInstallLayout.inventory(orphan)));
            check(AgentInstallLayout.deleteOwned(orphan) && !orphan.exists(), "exact owned obsolete release removed");
            orphan.mkdirs(); put(new File(orphan, "collector.jar"), "old owned code\n");
            AgentInstallLayout.durable(new File(orphan, "installed.json"), AssessmentScan.map("schema", 1L, "files", AgentInstallLayout.inventory(orphan)));
            put(new File(orphan, "customer-note.txt"), "unknown file\n");
            check(!AgentInstallLayout.deleteOwned(orphan) && new File(orphan, "customer-note.txt").exists(), "unknown member preserves entire release");
            Files.delete(new File(orphan, "customer-note.txt").toPath()); new File(orphan, "customer-empty/nested").mkdirs();
            check(!AgentInstallLayout.deleteOwned(orphan) && new File(orphan, "customer-empty/nested").isDirectory()
                && new File(orphan, "collector.jar").exists(), "unknown empty directory preserves whole release before deleting any owned code");
            Files.delete(new File(orphan, "customer-empty/nested").toPath()); Files.delete(new File(orphan, "customer-empty").toPath());
            put(new File(orphan, "collector.jar"), "changed code\n");
            check(!AgentInstallLayout.deleteOwned(orphan) && new File(orphan, "collector.jar").exists(), "modified member preserves entire release");
            try { AgentInstallLayout.child(f.layout.root, "../external/ca.pem"); throw new AssertionError("traversal accepted"); } catch (IOException expected) { checks++; }
            Path link = f.directory.resolve("collector/untrusted"); Files.createSymbolicLink(link, f.directory.resolve("external"));
            try { AgentInstallLayout.child(f.layout.root, "untrusted/ca.pem"); throw new AssertionError("linked cleanup escaped root"); } catch (IOException expected) { checks++; }
            Files.delete(link); f.unchanged();
        }
    }
    static void ioFaults(Path source) throws Exception {
        for (String point : new String[]{"before-write", "after-write-before-sync", "after-sync-before-rename", "after-rename"}) try (Fixture f = new Fixture(source)) {
            f.ioPoint = point; f.ioPath = "state/transaction.json"; f.ioCrash = true; f.ioArmed = true;
            try { f.prepare(); throw new AssertionError("missing journal I/O crash " + point); } catch (Death expected) { checks++; }
            check(f.ioFailures == 1 && f.pauses == 0, "journal fault occurs before scheduler mutation: " + point);
            f.prepare(); f.cycle(); f.finish(); check(f.layout.current() != null && !f.layout.journal().exists(), "journal internal write boundary resumes: " + point); f.unchanged();
        }
        for (String point : new String[]{"copy-block", "copy-before-sync", "copy-before-rename", "copy-after-rename"}) try (Fixture f = new Fixture(source)) {
            f.ioPoint = point; f.ioPath = "/releases/"; f.ioCrash = true; f.ioArmed = true;
            try { f.prepare(); throw new AssertionError("missing slot I/O crash " + point); } catch (Death expected) { checks++; }
            check(f.ioFailures == 1 && f.pauses == 0, "slot copy crash leaves original running: " + point);
            check(AgentInstallLayout.read(f.layout.journal()).get("copyIntent") instanceof Map, "partial copy has durable ownership intent: " + point);
            f.prepare(); f.cycle(); f.finish(); check(f.layout.current() != null && !f.layout.journal().exists(), "partial signed slot resumes safely: " + point); f.unchanged();
        }
        for (String point : new String[]{"copy-block", "copy-before-rename", "copy-after-rename"}) try (Fixture f = new Fixture(source)) {
            f.ioPoint = point; f.ioPath = "state/origin/files/"; f.ioCrash = true; f.ioArmed = true;
            try { f.prepare(); throw new AssertionError("missing recovery preparation crash " + point); } catch (Death expected) { checks++; }
            check(f.ioFailures == 1 && f.pauses == 0, "preparing recovery cannot pause the active original collector");
            f.prepare(); f.cycle(); f.finish(); check(f.layout.current() != null, "interrupted original recovery copy resumes from durable intent: " + point); f.unchanged();
        }
        try (Fixture f = new Fixture(source)) {
            f.prepare(); f.failProbe = true; f.ioPoint = "copy-block"; f.ioPath = "/collector/collector.jar"; f.ioCrash = true; f.ioArmed = true;
            try { f.cycle(); throw new AssertionError("missing rollback copy crash"); } catch (Death expected) { checks++; }
            check("ROLLING_BACK".equals(AgentInstallLayout.read(f.layout.journal()).get("phase")), "interrupted restore remains durable");
            f.finish(); check(f.layout.current() == null && f.active && "1.5.12".equals(AgentUpdateWorker.jarVersion(new File(f.layout.root, "collector.jar"))), "recovery itself resumes and restores the original after copy interruption"); f.unchanged();
        }
        for (String point : new String[]{"after-write-before-sync", "after-sync-before-rename", "after-rename"}) try (Fixture f = new Fixture(source)) {
            f.prepare(); f.ioPoint = point; f.ioPath = "/current.json"; f.ioCrash = false; f.ioArmed = true;
            f.cycle(); check(f.ioFailures == 1 && f.reports.contains("ROLLED_BACK"), "pointer write/rename I/O failure restores original: " + point);
            check(f.layout.current() == null && f.active, "failed pointer never leaves half-installed running state"); f.unchanged();
        }
        try (Fixture f = new Fixture(source)) {
            f.noSpace = true;
            try { f.prepare(); throw new AssertionError("insufficient disk accepted"); }
            catch (AgentInstallLayout.Failure expected) { check("INSUFFICIENT_DISK".equals(expected.diagnostic), "disk preflight has precise diagnosis"); }
            check(f.pauses == 0 && f.layout.current() == null, "no disk space never pauses or removes recovery");
            f.noSpace = false; f.prepare(); f.cycle(); f.finish(); check(f.layout.current() != null, "space restored permits same durable operation to resume"); f.unchanged();
        }
        try (Fixture f = new Fixture(source)) {
            long expanded = 0;
            for (Object item : ((Map)AgentInstallLayout.read(f.asset("manifest")).get("files")).values()) expanded += AgentInstallLayout.number(((Map)item).get("bytes"));
            f.availableSpace = 3L * expanded + 32L * 1024 * 1024 - 1;
            try { f.prepare(); throw new AssertionError("insufficient peak disk budget accepted"); }
            catch (AgentInstallLayout.Failure expected) { check("INSUFFICIENT_DISK".equals(expected.diagnostic), "preflight reserves three expanded copies plus restoration margin"); }
            check(f.pauses == 0 && f.layout.current() == null, "peak budget is checked before pausing the collector");
            f.availableSpace++; f.prepare(); f.cycle(); f.finish();
            check(f.layout.current() != null, "exact conservative peak budget is accepted"); f.unchanged();
        }
    }
    static void durableCleanup(Path source) throws Exception {
        try (Fixture f = new Fixture(source)) {
            f.prepare(); Map prepared = AgentInstallLayout.read(f.layout.journal());
            String releaseId = text(prepared.get("releaseId")), managerId = text(prepared.get("managerId"));
            f.failManager = true; f.cycle();
            File queued = new File(f.layout.state, "cleanup/" + f.operation + ".json");
            check(queued.isFile() && new File(f.layout.state, "cleanup.next").isFile(), "failed artifact cleanup remains durable after journal ACK");
            Map cleanup = AgentInstallLayout.read(queued); long due = AgentInstallLayout.number(cleanup.get("notBefore"));
            check(due >= System.currentTimeMillis() + 6 * AgentOwnedCleanup.DAY, "latest failed candidate has bounded seven-day retention");
            AgentOwnedCleanup worker = new AgentOwnedCleanup(f.layout, f);
            worker.maintain(System.currentTimeMillis()); check(queued.isFile(), "failed artifact retained before its deadline");
            File code = new File(f.layout.releases, releaseId), manager = new File(f.layout.managers, managerId);
            f.livePaths.add(code.getPath()); f.livePaths.add(manager.getPath());
            worker.maintain(due + 1); check(code.isDirectory() && manager.isDirectory() && queued.isFile(), "live code/manager references prevent retirement");
            f.livePaths.clear();
            File unknown = new File(f.layout.transaction(f.operation), "operator-note.txt"); put(unknown, "preserve manual note\n");
            worker.maintain(due + 2 * AgentOwnedCleanup.DAY);
            check(!code.exists() && !manager.exists(), "expired unreferenced signed failed slots are removed");
            check(unknown.exists() && queued.exists(), "unknown transaction member is preserved and cleanup remains queued");
            Files.delete(unknown.toPath()); worker.maintain(due + 4 * AgentOwnedCleanup.DAY);
            check(!queued.exists() && !new File(f.layout.state, "cleanup.next").exists(), "resolved queue finishes idempotently and idle marker disappears"); f.unchanged();
        }
    }
    static void secondEvolution(Path source) throws Exception {
        try (Fixture f = new Fixture(source)) {
            check(f.metadata.get("futureBundle") instanceof String, "fixture includes a separately signed future complete bundle");
            f.prepare(); f.cycle(); f.finish();
            Map before = new LinkedHashMap(f.layout.current()); File oldCode = f.layout.code(before), oldManager = f.layout.manager(before);
            Map anchor = AgentInstallLayout.inventory(new File(f.layout.home, "bootstrap"));
            Map oldScripts = AgentInstallLayout.inventory(oldCode), oldLibraries = AgentInstallLayout.inventory(new File(oldCode, "lib"));
            f.operation = "00000000-0000-0000-0000-000000000004";
            f.version = text(f.metadata.get("futureVersion")); f.managerHash = text(f.metadata.get("futureManagerSha256")); f.expiresMillis = System.currentTimeMillis() + 600000L;
            f.metadata.put("manifest", f.metadata.get("futureManifest")); f.metadata.put("signature", f.metadata.get("futureSignature")); f.metadata.put("bundle", f.metadata.get("futureBundle"));
            f.bundleHash = text(AgentInstallLayout.read(f.asset("manifest")).get("sha256"));
            AgentInstallLayout.durable(f.layout.request(), f.action()); f.cycle();
            check("REMOTE_CONFIRMING".equals(AgentInstallLayout.read(f.layout.journal()).get("phase")), "existing V3 manager installs the next complete bundle without re-bootstrap");
            f.finish(); Map after = f.layout.current(); File code = f.layout.code(after), manager = f.layout.manager(after);
            check(!before.get("managerSha256").equals(after.get("managerSha256")), "future release replaces the manager itself");
            check(!oldScripts.get("launcher-common.sh").equals(AgentBundleManifest.hash(new File(code, "launcher-common.sh"))), "future release changes a real launcher script");
            check(!oldLibraries.equals(AgentInstallLayout.inventory(new File(code, "lib"))), "future release changes signed library inventory");
            check(anchor.equals(AgentInstallLayout.inventory(new File(f.layout.home, "bootstrap"))), "independent anchor, its libraries and receipts remain byte-for-byte unchanged");
            check(oldCode.isDirectory() && oldManager.isDirectory() && code.isDirectory() && manager.isDirectory(), "active and previous functional code/manager generations are retained");
            check(!f.layout.journal().exists() && !f.layout.transaction(f.operation).exists(), "future release finishes and cleans downloads/staging"); f.unchanged();
        }
    }
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0 ? "tmp/full-update/http-fixture" : args[0]);
        success(source); ackAndNetwork(source); failures(source); freshLocal(source); crashes(source); ioFaults(source); ownedCleanup(source); durableCleanup(source); secondEvolution(source);
        System.out.println("AgentBundleInstallerTest PASS " + checks + " checks: signed bundle, independent handoff, original rollback, 10 durable crash boundaries, ACK/network retry, preserved state, conservative cleanup");
        System.out.println("VALIDATION_LIMIT=JDK17 with object adapters for OS/manager processes/locks; Linux service, reboot, real Java6 and power-loss release gates remain open");
    }
}
