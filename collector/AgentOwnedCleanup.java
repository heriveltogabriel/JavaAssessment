package assessment;

import java.io.*;
import java.util.*;

/** Durable housekeeping queue; unknown or changed files are never unlinked. */
final class AgentOwnedCleanup {
    static final long DAY = 86400000L;
    final AgentInstallLayout layout; final AgentBundleInstaller.Platform platform;
    AgentOwnedCleanup(AgentInstallLayout layout, AgentBundleInstaller.Platform platform) { this.layout = layout; this.platform = platform; }
    File queue() throws Exception { File file = AgentInstallLayout.child(layout.state, "cleanup"); AgentInstallLayout.directory(file); return file; }
    void enqueue(Map journal, boolean failed) throws Exception {
        String op = AgentInstallLayout.text(journal.get("operationId")); AgentInstallLayout.require(AgentInstallLayout.uuid(op), "INVALID_OPERATION");
        File transaction = layout.transaction(op); Map files = new LinkedHashMap();
        if (journal.get("ownedTransactionFiles") instanceof Map) files.putAll((Map)journal.get("ownedTransactionFiles"));
        // Download paths are reserved before transfer; they never name a path
        // supplied by the server. Locally hash even a rejected download so a
        // later administrator change is preserved by the cleanup comparison.
        for (String name : new String[]{"manifest.json", "manifest.sig", "bundle.zip"}) {
            File file = AgentInstallLayout.child(transaction, name);
            if (file.isFile()) files.put(name, AgentBundleManifest.hash(file));
        }
        // Extraction was authorized by a verified manifest and a persisted
        // prepared-N intent. Register its known members even if a write was
        // interrupted; anything outside that signed path inventory is unknown.
        try {
            Map manifest = AgentBundleManifest.verifyHeader(AgentInstallLayout.child(transaction, "manifest.json"), AgentInstallLayout.child(transaction, "manifest.sig"),
                AgentInstallLayout.child(layout.root, "update-public.der"), AgentInstallLayout.text(journal.get("targetVersion")));
            long attempts = AgentInstallLayout.number(journal.get("prepareAttempt"));
            for (long attempt = 1; attempt <= attempts && attempt <= 8; attempt++) for (Object item : ((Map)manifest.get("files")).keySet()) {
                String name = "prepared-" + attempt + "/" + AgentInstallLayout.text(item); File file = AgentInstallLayout.child(transaction, name);
                if (file.isFile()) files.put(name, AgentBundleManifest.hash(file));
            }
        } catch (Exception unverified) { /* No trusted extraction inventory is available. */ }
        files.putAll(AgentV3UninstallInventory.inventoryForCleanup(layout, op));
        Map record = AssessmentScan.map("schema", new Long(1), "operationId", op, "failed", Boolean.valueOf(failed), "attempts", new Long(0),
            "notBefore", new Long(System.currentTimeMillis() + (failed ? 7 * DAY : DAY)), "files", files,
            "releaseId", journal.get("releaseId"), "managerId", journal.get("managerId"));
        if (failed) {
            File[] previous = queue().listFiles(); if (previous != null) for (int i = 0; i < previous.length; i++) {
                if (!previous[i].getName().matches("[a-f0-9-]{36}[.]json")) continue;
                Map older = AgentInstallLayout.read(previous[i]);
                if (Boolean.TRUE.equals(older.get("failed"))) { older.put("notBefore", new Long(System.currentTimeMillis())); layout.write(previous[i], older); }
            }
        }
        layout.write(AgentInstallLayout.child(queue(), op + ".json"), record); updateNext();
    }
    void updateNext() throws Exception {
        long next = Long.MAX_VALUE; File[] files = queue().listFiles();
        if (files != null) for (int i = 0; i < files.length; i++) if (files[i].getName().matches("[a-f0-9-]{36}[.]json")) {
            long time = AgentInstallLayout.number(AgentInstallLayout.read(files[i]).get("notBefore")); if (time >= 0 && time < next) next = time;
        }
        File marker = AgentInstallLayout.child(layout.state, "cleanup.next");
        if (next == Long.MAX_VALUE) { if (marker.exists()) marker.delete(); return; }
        File temporary = new File(marker.getPath() + ".tmp"); FileOutputStream output = new FileOutputStream(temporary);
        try { output.write((Long.toString(next / 1000L) + "\n").getBytes("US-ASCII")); output.getFD().sync(); } finally { output.close(); }
        AgentTool.ownerOnly(temporary); AgentInstallLayout.require(temporary.renameTo(marker), "LOCAL_CHECK_FAILED");
    }
    Set protectedIds(String kind) throws Exception {
        Set result = new HashSet(); Map pointer = layout.current();
        if (pointer != null) result.add(pointer.get(kind));
        File retained = AgentInstallLayout.child(layout.state, "retained.json");
        if (retained.isFile()) {
            Map record = AgentInstallLayout.read(retained);
            for (String name : new String[]{"current", "previous"}) if (record.get(name) instanceof Map) result.add(((Map)record.get(name)).get(kind));
        }
        return result;
    }
    Set retainedFailed(String kind, long now) throws Exception {
        Set result = new HashSet(); File[] records = queue().listFiles();
        if (records != null) for (int i = 0; i < records.length; i++) if (records[i].getName().matches("[a-f0-9-]{36}[.]json")) {
            Map record = AgentInstallLayout.read(records[i]);
            if (Boolean.TRUE.equals(record.get("failed")) && AgentInstallLayout.number(record.get("notBefore")) > now) result.add(record.get(kind));
        }
        return result;
    }
    void maintain(long now) throws Exception {
        if (layout.journal().isFile()) return; // Active transactions always win over housekeeping.
        Set releaseIds = protectedIds("releaseId"), managerIds = protectedIds("managerId");
        File[] records = queue().listFiles(); if (records == null) return;
        for (int i = 0; i < records.length; i++) {
            File file = records[i]; if (!file.getName().matches("[a-f0-9-]{36}[.]json")) continue;
            Map record = AgentInstallLayout.read(file); if (AgentInstallLayout.number(record.get("notBefore")) > now) continue;
            String op = AgentInstallLayout.text(record.get("operationId")); AgentInstallLayout.require(AgentInstallLayout.uuid(op), "LOCAL_CHECK_FAILED");
            boolean complete = true; File transaction = layout.transaction(op); Map owned = (Map)record.get("files");
            if (transaction.exists()) {
                for (Object item : owned.keySet()) {
                    File candidate = AgentInstallLayout.child(transaction, AgentInstallLayout.text(item)); if (!candidate.exists()) continue;
                    if (!candidate.isFile() || !owned.get(item).equals(AgentBundleManifest.hash(candidate)) || platform.processUses(candidate)) { complete = false; continue; }
                    if (!candidate.delete()) complete = false;
                }
                AgentInstallLayout.removeKnownEmpty(transaction, owned.keySet()); if (transaction.exists()) complete = false;
            }
            if (Boolean.TRUE.equals(record.get("failed"))) {
                complete &= removeSlot(layout.releases, AgentInstallLayout.text(record.get("releaseId")), releaseIds);
                complete &= removeSlot(layout.managers, AgentInstallLayout.text(record.get("managerId")), managerIds);
            }
            if (complete) AgentInstallLayout.require(file.delete(), "LOCAL_CHECK_FAILED");
            else { record.put("attempts", new Long(AgentInstallLayout.number(record.get("attempts")) + 1)); record.put("notBefore", new Long(now + DAY)); layout.write(file, record); }
        }
        updateNext();
    }
    boolean removeSlot(File parent, String id, Set protectedIds) throws Exception {
        if (id.equals("")) return true; AgentInstallLayout.require(AgentInstallLayout.identifier(id), "LOCAL_CHECK_FAILED");
        if (protectedIds.contains(id)) return true; // Reused by a later successful operation; retain it there.
        File directory = AgentInstallLayout.child(parent, id); if (!directory.exists()) return true;
        return !platform.processUses(directory) && AgentInstallLayout.deleteOwned(directory);
    }
}
