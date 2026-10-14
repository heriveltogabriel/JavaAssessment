package assessment;

import java.io.*;
import java.util.*;

/** Independent complete-package transaction; no collector code is needed to roll back. */
public final class AgentBundleInstaller {
    interface Remote {
        Map request(String action, Map body) throws Exception;
        void download(String asset, File target, long limit, String operationId) throws Exception;
    }
    interface Platform {
        void local(String action, Map transaction) throws Exception;
        int probe(File code, String version, boolean heartbeat) throws Exception;
        boolean serviceEvidence(Map pointer) throws Exception;
        boolean processUses(File directory) throws Exception;
    }
    final AgentInstallLayout layout;
    final Platform platform;
    final Remote remote;
    final Properties config;
    Map journal;
    AgentBundleInstaller(AgentInstallLayout layout, Platform platform, Remote remote, Properties config) {
        this.layout = layout; this.platform = platform; this.remote = remote; this.config = config;
    }
    static String text(Object value) { return AgentInstallLayout.text(value); }
    static void require(boolean condition, String code) throws AgentInstallLayout.Failure { AgentInstallLayout.require(condition, code); }
    String operation() { return text(journal.get("operationId")); }
    File transaction() throws Exception { return layout.transaction(operation()); }
    void save(String phase) throws Exception {
        journal.put("phase", phase); journal.put("sequence", new Long(AgentInstallLayout.number(journal.get("sequence")) + 1));
        layout.write(layout.journal(), journal); platform.local("sync", journal);
    }
    void accept(Map action) throws Exception {
        require("UPDATE_AGENT_V3".equals(action.get("type")) && AgentInstallLayout.uuid(text(action.get("operationId")))
            && AgentUpdateWorker.version(text(action.get("targetVersion"))) && AgentInstallLayout.digest(text(action.get("signedPlanHash")))
            && AgentInstallLayout.number(action.get("expiresMillis")) > System.currentTimeMillis(), "INVALID_OPERATION");
        Map previous = layout.current();
        String from = previous == null ? AgentMigrationBootstrap.originVersion(layout) : text(previous.get("version"));
        require(AgentUpdateWorker.compare(text(action.get("targetVersion")), from) > 0, "DOWNGRADE_REFUSED");
        journal = AssessmentScan.map("schema", new Long(1), "sequence", new Long(0), "operationId", action.get("operationId"),
            "hostId", AssessmentCollector.required(config, "hostId"), "targetVersion", action.get("targetVersion"), "signedPlanHash", action.get("signedPlanHash"),
            "expiresMillis", action.get("expiresMillis"), "previousVersion", from, "previousPointer", previous);
        recordIdentity(previous == null);
        save("ACCEPTED");
    }
    void recordIdentity(boolean physicalOrigin) throws Exception {
        journal.put("configSha256", AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "conf/collector.properties")));
        journal.put("pinSha256", AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "update-public.der")));
        journal.put("javaPathSha256", AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "conf/java.path")));
        journal.put("caSha256", AgentBundleManifest.hash(new File(AssessmentCollector.required(config, "tlsCaFile"))));
        if (physicalOrigin) journal.put("bridgeSha256", AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "collector.jar")));
    }
    boolean localInstall() { return Boolean.TRUE.equals(journal.get("localInstall")); }
    Map claim() throws Exception {
        if (localInstall()) {
            require(AgentInstallLayout.number(journal.get("expiresMillis")) > System.currentTimeMillis(), "AUTHORIZATION_REVOKED"); return journal;
        }
        Map response = remote.request("claim", AssessmentScan.map("hostId", journal.get("hostId"), "operationId", operation()));
        require(operation().equals(response.get("operationId")) && Boolean.TRUE.equals(response.get("executable"))
            && journal.get("targetVersion").equals(response.get("targetVersion"))
            && journal.get("signedPlanHash").equals(response.get("signedPlanHash"))
            && AgentInstallLayout.number(response.get("expiresMillis")) > System.currentTimeMillis()
            && AgentInstallLayout.digest(text(response.get("bundleSha256"))) && AgentInstallLayout.digest(text(response.get("managerSha256"))), "AUTHORIZATION_REVOKED");
        if (journal.containsKey("bundleSha256")) require(journal.get("bundleSha256").equals(response.get("bundleSha256"))
            && journal.get("managerSha256").equals(response.get("managerSha256")), "ROUTE_CHANGED");
        else { journal.put("bundleSha256", response.get("bundleSha256")); journal.put("managerSha256", response.get("managerSha256")); }
        if (journal.containsKey("claimExpiresMillis")) require(journal.get("claimExpiresMillis").equals(response.get("expiresMillis")), "ROUTE_CHANGED");
        else journal.put("claimExpiresMillis", response.get("expiresMillis"));
        return response;
    }
    void report(String status, String diagnostic, String version) throws Exception {
        if (localInstall()) return; // Administrative local install is never a server work request.
        Map response = remote.request("report", AssessmentScan.map("hostId", journal.get("hostId"), "operationId", operation(), "status", status,
            "version", version, "bundleSha256", journal.get("bundleSha256"), "managerSha256", journal.get("managerSha256"), "diagnostic", diagnostic));
        if (!(Boolean.TRUE.equals(response.get("accepted")) && operation().equals(response.get("operationId")) && status.equals(response.get("status"))))
            throw new IOException("UNCONFIRMED_ACK");
    }
    File downloadAndVerify() throws Exception {
        claim(); save("DOWNLOADING"); report("PREPARING", "OK", text(journal.get("previousVersion")));
        File directory = transaction(); AgentInstallLayout.directory(directory);
        File manifest = AgentInstallLayout.child(directory, "manifest.json"), signature = AgentInstallLayout.child(directory, "manifest.sig");
        File bundle = AgentInstallLayout.child(directory, "bundle.zip");
        if (localInstall()) {
            layout.copyOwned(AgentInstallLayout.child(layout.root, "full-update/manifest.json"), manifest);
            layout.copyOwned(AgentInstallLayout.child(layout.root, "full-update/manifest.sig"), signature);
        } else { remote.download("manifest", manifest, 65536, operation()); remote.download("signature", signature, 1024, operation()); }
        Map header;
        try { header = AgentBundleManifest.verifyHeader(manifest, signature, AgentInstallLayout.child(layout.root, "update-public.der"), text(journal.get("targetVersion"))); }
        catch (Exception invalid) { throw new AgentInstallLayout.Failure("INVALID_BUNDLE"); }
        require(journal.get("bundleSha256").equals(header.get("sha256")), "ROUTE_CHANGED");
        if (localInstall()) layout.copyOwned(AgentInstallLayout.child(layout.root, "full-update/bundle.zip"), bundle);
        else remote.download("package", bundle, AgentInstallLayout.number(header.get("bytes")), operation());
        Map verified;
        try { verified = AgentBundleManifest.verify(manifest, signature, AgentInstallLayout.child(layout.root, "update-public.der"), bundle, text(journal.get("targetVersion"))); }
        catch (Exception invalid) { throw new AgentInstallLayout.Failure("INVALID_BUNDLE"); }
        Map files = (Map)verified.get("files"), manager = (Map)files.get("manager/helper.jar");
        require(manager != null && journal.get("managerSha256").equals(manager.get("sha256")), "ROUTE_CHANGED");
        journal.put("manifestSha256", AgentBundleManifest.hash(manifest)); save("VERIFIED"); return bundle;
    }
    void prepare() throws Exception {
        File bundle = downloadAndVerify(); File directory = transaction();
        Map manifest = AgentBundleManifest.verify(AgentInstallLayout.child(directory, "manifest.json"), AgentInstallLayout.child(directory, "manifest.sig"),
            AgentInstallLayout.child(layout.root, "update-public.der"), bundle, text(journal.get("targetVersion")));
        long expanded = 0;
        for (Object item : ((Map)manifest.get("files")).values()) expanded += AgentInstallLayout.number(((Map)item).get("bytes"));
        // Keep room for verified staging, installed slots/bootstrap, and an
        // independent origin/restore copy before pausing the existing collector.
        long requiredSpace = 3L * expanded + 32L * 1024 * 1024;
        require(layout.io.usableSpace(layout.root) >= requiredSpace && layout.io.usableSpace(layout.home) >= requiredSpace, "INSUFFICIENT_DISK");
        long attempt = AgentInstallLayout.number(journal.get("prepareAttempt")) + 1;
        require(attempt <= 8, "RECOVERY_REQUIRED"); journal.put("prepareAttempt", new Long(attempt));
        String name = "prepared-" + attempt; journal.put("preparedDirectory", name); save("VERIFIED");
        File prepared = AgentInstallLayout.child(directory, name);
        AgentBundleManifest.extractVerified(manifest, bundle, prepared);
        String releaseId = text(journal.get("targetVersion")) + "-" + text(journal.get("bundleSha256")).substring(0, 12);
        String managerId = "manager-" + text(journal.get("managerSha256")).substring(0, 24);
        journal.put("releaseId", releaseId); journal.put("managerId", managerId);
        installSlot(AgentInstallLayout.child(prepared, "code"), AgentInstallLayout.child(layout.releases, releaseId), "code");
        installSlot(AgentInstallLayout.child(prepared, "manager"), AgentInstallLayout.child(layout.managers, managerId), "manager");
        Map pointer = AssessmentScan.map("schema", new Long(1), "releaseId", releaseId, "managerId", managerId,
            "version", journal.get("targetVersion"), "bundleSha256", journal.get("bundleSha256"), "managerSha256", journal.get("managerSha256"));
        layout.validatePointer(pointer); journal.put("targetPointer", pointer);
        recordOwnedStaging(manifest, prepared);
        require(platform.probe(layout.code(pointer), text(journal.get("targetVersion")), false) == 0, "PROBE_FAILED");
        save("PREPARED");
    }
    void recordOwnedStaging(Map manifest, File prepared) throws Exception {
        Map owned = new LinkedHashMap();
        for (Object item : ((Map)manifest.get("files")).keySet()) {
            String name = text(item); Map descriptor = (Map)((Map)manifest.get("files")).get(item);
            File file = AgentInstallLayout.child(prepared, name);
            require(descriptor.get("sha256").equals(AgentBundleManifest.hash(file)), "INVALID_BUNDLE");
            owned.put(text(journal.get("preparedDirectory")) + "/" + name, descriptor.get("sha256"));
        }
        for (String file : new String[]{"manifest.json", "manifest.sig", "bundle.zip"}) owned.put(file, AgentBundleManifest.hash(AgentInstallLayout.child(transaction(), file)));
        journal.put("ownedTransactionFiles", owned);
    }
    void installSlot(File source, File target, String role) throws Exception {
        Map files = AgentInstallLayout.inventory(source);
        files.put("bundle-manifest.json", AgentBundleManifest.hash(AgentInstallLayout.child(transaction(), "manifest.json")));
        files.put("bundle-manifest.sig", AgentBundleManifest.hash(AgentInstallLayout.child(transaction(), "manifest.sig")));
        if (target.isDirectory()) {
            File installed = AgentInstallLayout.child(target, "installed.json");
            Map actual = AgentInstallLayout.inventory(target);
            if (installed.isFile()) {
                require(files.equals(AgentInstallLayout.read(installed).get("files")), "LOCAL_CHECK_FAILED");
                actual.remove("installed.json"); require(files.equals(actual), "LOCAL_CHECK_FAILED"); return;
            }
            // A durable copy intent proves ownership of an interrupted .copy;
            // other unexpected members are preserved and fail closed.
            for (Object item : actual.keySet()) {
                String name = text(item);
                if (name.endsWith(".copy")) {
                    String destination = name.substring(0, name.length() - 5); Map intent = journal.get("copyIntent") instanceof Map ? (Map)journal.get("copyIntent") : null;
                    require(files.containsKey(destination) && intent != null && AgentInstallLayout.child(target, destination).getPath().equals(intent.get("destination"))
                        && files.get(destination).equals(intent.get("sha256")), "LOCAL_CHECK_FAILED");
                } else require(files.containsKey(name) && files.get(name).equals(actual.get(name)), "LOCAL_CHECK_FAILED");
            }
        }
        AgentInstallLayout.directory(target);
        for (Object item : files.keySet()) {
            String name = text(item);
            File from = name.equals("bundle-manifest.json") ? AgentInstallLayout.child(transaction(), "manifest.json")
                : name.equals("bundle-manifest.sig") ? AgentInstallLayout.child(transaction(), "manifest.sig") : AgentInstallLayout.child(source, name);
            copySlotFile(from, AgentInstallLayout.child(target, name));
        }
        layout.write(AgentInstallLayout.child(target, "installed.json"), AssessmentScan.map("schema", new Long(1), "role", role,
            "version", journal.get("targetVersion"), "bundleSha256", journal.get("bundleSha256"), "manifestSha256", journal.get("manifestSha256"), "files", files));
        platform.local("sync", journal);
    }
    void copySlotFile(File source, File destination) throws Exception {
        copyIntentFile(source, destination, false);
    }
    void copyIntentFile(File source, File destination, boolean replaceOwned) throws Exception {
        String digest = AgentBundleManifest.hash(source); File temporary = new File(destination.getPath() + ".copy");
        if (temporary.exists()) {
            Map intent = journal.get("copyIntent") instanceof Map ? (Map)journal.get("copyIntent") : null;
            require(intent != null && destination.getPath().equals(intent.get("destination")) && digest.equals(intent.get("sha256"))
                && temporary.equals(temporary.getCanonicalFile()) && temporary.isFile(), "LOCAL_CHECK_FAILED");
            require(temporary.delete(), "LOCAL_CHECK_FAILED");
        }
        if (destination.isFile() && digest.equals(AgentBundleManifest.hash(destination))) {
            if (journal.get("copyIntent") instanceof Map && destination.getPath().equals(((Map)journal.get("copyIntent")).get("destination"))) {
                journal.remove("copyIntent"); save(text(journal.get("phase")));
            }
            return;
        }
        require(replaceOwned || !destination.exists(), "LOCAL_CHECK_FAILED");
        journal.put("copyIntent", AssessmentScan.map("destination", destination.getPath(), "sha256", digest)); save(text(journal.get("phase")));
        layout.copyOwned(source, destination); journal.remove("copyIntent"); save(text(journal.get("phase")));
    }
    /** Called by a bridge poll: staging only; it must exit before activation. */
    void prepareContinuation(Map action) throws Exception {
        platform.local("check", null); layout.initialize();
        require(!new File(layout.home, "journal.json").exists() && !new File(layout.root, "collector-spool/update-request.json").exists(), "LEGACY_UPDATE_ACTIVE");
        if (layout.journal().isFile()) journal = AgentInstallLayout.read(layout.journal()); else accept(action);
        validateJournal();
        String phase = text(journal.get("phase"));
        if ("ACCEPTED".equals(phase) || "DOWNLOADING".equals(phase) || "VERIFIED".equals(phase)) prepare();
        require("PREPARED".equals(journal.get("phase")), "RECOVERY_REQUIRED");
        platform.local("install-bootstrap", journal);
    }
    void initializeLocal() throws Exception {
        platform.local("check", null); layout.initialize(); if (layout.current() != null) return;
        require(!new File(layout.home, "journal.json").exists() && !new File(layout.root, "collector-spool/update-request.json").exists(), "LEGACY_UPDATE_ACTIVE");
        if (layout.journal().isFile()) {
            journal = AgentInstallLayout.read(layout.journal()); require(localInstall(), "UPDATE_ALREADY_ACTIVE"); validateJournal();
        } else {
            File source = AgentInstallLayout.child(layout.root, "full-update");
            Map manifest = AgentBundleManifest.verify(AgentInstallLayout.child(source, "manifest.json"), AgentInstallLayout.child(source, "manifest.sig"),
                AgentInstallLayout.child(layout.root, "update-public.der"), AgentInstallLayout.child(source, "bundle.zip"), AgentTransport.VERSION);
            Map files = (Map)manifest.get("files"); Map collector = (Map)files.get("code/collector.jar"), manager = (Map)files.get("manager/helper.jar");
            require(collector != null && manager != null && collector.get("sha256").equals(AgentBundleManifest.hash(AgentInstallLayout.child(layout.root, "collector.jar"))), "LOCAL_CHECK_FAILED");
            journal = AssessmentScan.map("schema", new Long(1), "sequence", new Long(0), "operationId", UUID.randomUUID().toString(),
                "hostId", AssessmentCollector.required(config, "hostId"), "localInstall", Boolean.TRUE, "targetVersion", AgentTransport.VERSION,
                "signedPlanHash", AgentBundleManifest.hash(AgentInstallLayout.child(source, "manifest.json")), "bundleSha256", manifest.get("sha256"),
                "managerSha256", manager.get("sha256"), "expiresMillis", new Long(System.currentTimeMillis() + 86400000L), "previousVersion", AgentTransport.VERSION, "previousPointer", null);
            recordIdentity(true); save("ACCEPTED");
        }
        String phase = text(journal.get("phase"));
        if ("ACCEPTED".equals(phase) || "DOWNLOADING".equals(phase) || "VERIFIED".equals(phase)) prepare();
        require("PREPARED".equals(journal.get("phase")), "RECOVERY_REQUIRED"); platform.local("install-bootstrap", journal);
    }
    void validateJournal() throws Exception {
        require(AgentInstallLayout.number(journal.get("schema")) == 1 && AgentInstallLayout.uuid(operation())
            && AssessmentCollector.required(config, "hostId").equals(journal.get("hostId"))
            && AgentUpdateWorker.version(text(journal.get("targetVersion"))) && AgentUpdateWorker.version(text(journal.get("previousVersion")))
            && AgentInstallLayout.number(journal.get("sequence")) >= 1, "RECOVERY_REQUIRED");
    }
    void cycle() throws Exception {
        platform.local("check", null); layout.initialize();
        if (layout.journal().isFile()) journal = AgentInstallLayout.read(layout.journal());
        else if (layout.request().isFile()) accept(AgentInstallLayout.read(layout.request())); else return;
        validateJournal(); String phase = text(journal.get("phase"));
        if ("ROLLING_BACK".equals(phase)) { new AgentBundleRecovery(layout, platform).restore(journal); save("ROLLED_BACK"); phase = "ROLLED_BACK"; }
        if ("ROLLED_BACK".equals(phase) || "FAILED".equals(phase)) { finishFailure(); return; }
        if ("COMMITTED".equals(phase) || "CLEANING".equals(phase)) { cleanup(); return; }
        if ("QUIESCING".equals(phase) || "ACTIVATING".equals(phase) || "LOCAL_VERIFYING".equals(phase)) {
            rollback("RECOVERY_REQUIRED"); finishFailure(); return;
        }
        if ("REMOTE_CONFIRMING".equals(phase)) { confirm(); return; }
        try {
            if ("ACCEPTED".equals(phase) || "DOWNLOADING".equals(phase) || "VERIFIED".equals(phase)) prepare();
            require("PREPARED".equals(journal.get("phase")), "RECOVERY_REQUIRED");
            AgentMigrationBootstrap.revalidateIdentity(layout, journal);
            claim(); report("INSTALLING", "OK", text(journal.get("previousVersion")));
            // The platform waits for the original collector cycle to exit and
            // snapshots owned service definitions before changing anything.
            platform.local("snapshot-services", journal); platform.local("snapshot-uninstall", journal);
            ((Map)journal.get("ownedTransactionFiles")).putAll(AgentV3UninstallInventory.inventoryForCleanup(layout, operation()));
            File savedServices = AgentInstallLayout.child(transaction(), "previous-services");
            if (savedServices.isDirectory()) {
                Map owned = (Map)journal.get("ownedTransactionFiles");
                Map services = AgentInstallLayout.inventory(savedServices);
                for (Object item : services.keySet()) {
                    String name = text(item); require(name.matches("collector[.](service|timer|init)"), "LOCAL_CHECK_FAILED");
                    owned.put("previous-services/" + name, services.get(item));
                }
            }
            save("QUIESCING"); platform.local("pause", journal);
            claim(); save("ACTIVATING"); layout.promote((Map)journal.get("targetPointer")); platform.local("activate-services", journal); platform.local("activate-uninstall", journal);
            save("LOCAL_VERIFYING"); Map pointer = layout.current();
            require(pointer != null && journal.get("bundleSha256").equals(pointer.get("bundleSha256")), "SERVICE_FAILED");
            require(platform.probe(layout.code(pointer), text(journal.get("targetVersion")), false) == 0, "PROBE_FAILED");
            platform.local("probe-manager", journal); platform.local("start", journal); save("REMOTE_CONFIRMING");
            // A fresh independent process must load the promoted manager before
            // it can confirm success. The current manager must not certify its
            // own replacement merely because the new file exists.
            return;
        } catch (AgentInstallLayout.Failure failure) {
            phase = text(journal.get("phase"));
            if ("QUIESCING".equals(phase) || "ACTIVATING".equals(phase) || "LOCAL_VERIFYING".equals(phase)) rollback(failure.diagnostic);
            else { journal.put("diagnostic", failure.diagnostic); save("FAILED"); }
            finishFailure(); return;
        } catch (Exception failure) {
            phase = text(journal.get("phase"));
            if ("QUIESCING".equals(phase) || "ACTIVATING".equals(phase) || "LOCAL_VERIFYING".equals(phase)) { rollback("LOCAL_INSTALL_FAILED"); finishFailure(); return; }
            throw failure; // Transport before activation is a retry, never a destructive rollback.
        }
    }
    void confirm() throws Exception {
        Map pointer = layout.current(); require(pointer != null && journal.get("bundleSha256").equals(pointer.get("bundleSha256")), "RECOVERY_REQUIRED");
        try {
            require(platform.serviceEvidence(pointer), "SERVICE_FAILED");
            require(platform.probe(layout.code(pointer), text(journal.get("targetVersion")), false) == 0, "PROBE_FAILED");
        } catch (AgentInstallLayout.Failure failure) { rollback(failure.diagnostic); finishFailure(); return; }
        report("VERIFYING", "OK", text(journal.get("targetVersion")));
        int heartbeat = platform.probe(layout.code(pointer), text(journal.get("targetVersion")), true);
        if (heartbeat == 10 || heartbeat == 11) throw new IOException("REMOTE_CONFIRMATION_PENDING");
        if (heartbeat != 0) { rollback("PROBE_FAILED"); finishFailure(); return; }
        report("SUCCEEDED", "OK", text(journal.get("targetVersion"))); save("COMMITTED");
        layout.write(AgentInstallLayout.child(layout.state, "last-good.json"), pointer); cleanup();
    }
    void rollback(String reason) throws Exception {
        journal.put("diagnostic", reason); save("ROLLING_BACK"); new AgentBundleRecovery(layout, platform).restore(journal); save("ROLLED_BACK");
    }
    void finishFailure() throws Exception {
        String phase = text(journal.get("phase")); report("ROLLED_BACK".equals(phase) ? "ROLLED_BACK" : "FAILED", text(journal.get("diagnostic")), text(journal.get("previousVersion")));
        new AgentOwnedCleanup(layout, platform).enqueue(journal, true);
        finishJournal();
    }
    void cleanup() throws Exception {
        save("CLEANING"); Map current = layout.current(), previous = (Map)journal.get("previousPointer");
        if (current != null) layout.write(AgentInstallLayout.child(layout.state, "last-good.json"), current);
        layout.write(AgentInstallLayout.child(layout.state, "retained.json"), AssessmentScan.map("schema", new Long(1), "current", current, "previous", previous));
        try {
            cleanupSlots(layout.releases, current == null ? "" : text(current.get("releaseId")), previous == null ? "" : text(previous.get("releaseId")));
            cleanupSlots(layout.managers, current == null ? "" : text(current.get("managerId")), previous == null ? "" : text(previous.get("managerId")));
            cleanupTransaction();
            if (localInstall()) cleanupLocalSource();
        } catch (Exception incomplete) { journal.put("cleanupPending", Boolean.TRUE); }
        if (Boolean.TRUE.equals(journal.get("cleanupPending"))) new AgentOwnedCleanup(layout, platform).enqueue(journal, false);
        finishJournal();
    }
    void cleanupLocalSource() throws Exception {
        File source = AgentInstallLayout.child(layout.root, "full-update");
        if (!source.isDirectory()) return;
        File manifest = AgentInstallLayout.child(source, "manifest.json"), signature = AgentInstallLayout.child(source, "manifest.sig"), bundle = AgentInstallLayout.child(source, "bundle.zip");
        if (bundle.isFile()) require(journal.get("bundleSha256").equals(AgentBundleManifest.hash(bundle)), "LOCAL_CHECK_FAILED");
        if (manifest.isFile()) require(journal.get("signedPlanHash").equals(AgentBundleManifest.hash(manifest)), "LOCAL_CHECK_FAILED");
        if (signature.isFile()) require(((Map)journal.get("ownedTransactionFiles")).get("manifest.sig").equals(AgentBundleManifest.hash(signature)), "LOCAL_CHECK_FAILED");
        if (bundle.exists()) require(bundle.delete(), "LOCAL_CHECK_FAILED"); if (manifest.exists()) require(manifest.delete(), "LOCAL_CHECK_FAILED");
        if (signature.exists()) require(signature.delete(), "LOCAL_CHECK_FAILED"); source.delete();
        if (source.exists()) journal.put("cleanupPending", Boolean.TRUE);
    }
    void cleanupTransaction() throws Exception {
        if (!(journal.get("ownedTransactionFiles") instanceof Map)) return;
        Map owned = (Map)journal.get("ownedTransactionFiles");
        for (Object item : owned.keySet()) {
            File file = AgentInstallLayout.child(transaction(), text(item));
            if (!file.exists()) continue;
            if (file.isFile() && owned.get(item).equals(AgentBundleManifest.hash(file)) && !platform.processUses(file)) require(file.delete(), "LOCAL_CHECK_FAILED");
            else journal.put("cleanupPending", Boolean.TRUE);
        }
        AgentInstallLayout.removeKnownEmpty(transaction(), owned.keySet());
        if (transaction().exists()) journal.put("cleanupPending", Boolean.TRUE);
    }
    void cleanupSlots(File parent, String active, String prior) throws Exception {
        File[] slots = parent.listFiles(); require(slots != null, "LOCAL_CHECK_FAILED");
        Set retainedFailed = new AgentOwnedCleanup(layout, platform).retainedFailed(parent.equals(layout.releases) ? "releaseId" : "managerId", System.currentTimeMillis());
        for (int i = 0; i < slots.length; i++) {
            File slot = slots[i];
            if (slot.getName().equals(active) || slot.getName().equals(prior) || retainedFailed.contains(slot.getName()) || !AgentInstallLayout.identifier(slot.getName()) || !slot.isDirectory()) continue;
            if (platform.processUses(slot) || !AgentInstallLayout.deleteOwned(slot)) journal.put("cleanupPending", Boolean.TRUE);
        }
    }
    void finishJournal() throws Exception {
        File history = AgentInstallLayout.child(layout.home, "history"); AgentInstallLayout.directory(history);
        layout.write(AgentInstallLayout.child(history, operation() + ".json"), AssessmentScan.map("schema", new Long(1), "operationId", operation(),
            "targetVersion", journal.get("targetVersion"), "phase", journal.get("phase"), "diagnostic", journal.get("diagnostic"), "cleanupPending", journal.get("cleanupPending")));
        File[] records = history.listFiles(); if (records != null && records.length > 10) {
            Arrays.sort(records, new Comparator() { public int compare(Object a, Object b) { long x = ((File)a).lastModified(), y = ((File)b).lastModified(); return x < y ? -1 : x == y ? 0 : 1; } });
            for (int i = 0; i < records.length - 10; i++) if (records[i].getName().matches("[a-f0-9-]{36}[.]json") && records[i].equals(records[i].getCanonicalFile())) records[i].delete();
        }
        if (layout.request().isFile() && operation().equals(AgentInstallLayout.read(layout.request()).get("operationId"))) require(layout.request().delete(), "LOCAL_CHECK_FAILED");
        // Journal is removed last. Repeating any preceding cleanup is safe.
        require(layout.journal().delete(), "LOCAL_CHECK_FAILED"); platform.local("sync", journal);
    }
}
