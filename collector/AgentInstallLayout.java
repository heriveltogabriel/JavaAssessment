package assessment;

import java.io.*;
import java.util.*;

/** Fixed installation roots and durable, non-executable release pointers. */
final class AgentInstallLayout {
    static final File ROOT = new File("/opt/java-assessment-collector");
    static final File HOME = new File("/opt/java-assessment-update");
    final File root, home, state, transactions, releases, managers;
    final Io io;
    static class Io {
        void point(String action, File file) throws Exception {}
        long usableSpace(File file) { return file.getUsableSpace(); }
    }
    AgentInstallLayout(File root, File home) throws Exception {
        this(root, home, new Io());
    }
    AgentInstallLayout(File root, File home, Io io) throws Exception {
        this.io = io;
        this.root = root.getAbsoluteFile(); this.home = home.getAbsoluteFile();
        require(this.root.equals(this.root.getCanonicalFile()) && this.home.equals(this.home.getCanonicalFile()), "LOCAL_CHECK_FAILED");
        state = child(home, "state"); transactions = child(home, "transactions");
        releases = child(root, "releases"); managers = child(home, "managers");
    }
    static AgentInstallLayout production() throws Exception { return new AgentInstallLayout(ROOT, HOME); }
    static final class Failure extends IOException {
        final String diagnostic;
        Failure(String diagnostic) { super(diagnostic); this.diagnostic = diagnostic; }
    }
    static void require(boolean value, String diagnostic) throws Failure { if (!value) throw new Failure(diagnostic); }
    static String text(Object value) { return value instanceof String ? (String)value : ""; }
    static long number(Object value) { return value instanceof Number ? ((Number)value).longValue() : -1; }
    static boolean uuid(String value) { return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}") && value.indexOf("..") < 0; }
    static File child(File parent, String relative) throws Exception {
        require(relative.length() > 0 && relative.matches("[A-Za-z0-9_./-]+") && !relative.startsWith("/")
            && relative.indexOf("..") < 0 && relative.indexOf("//") < 0, "LOCAL_CHECK_FAILED");
        File file = new File(parent, relative).getAbsoluteFile();
        require(file.equals(file.getCanonicalFile()) && file.getPath().startsWith(parent.getAbsolutePath() + File.separator), "LOCAL_CHECK_FAILED");
        return file;
    }
    static void directory(File directory) throws Exception {
        require(directory.equals(directory.getCanonicalFile()), "LOCAL_CHECK_FAILED");
        require(directory.isDirectory() || directory.mkdirs(), "LOCAL_CHECK_FAILED");
        require(directory.setReadable(false, false) && directory.setWritable(false, false) && directory.setExecutable(false, false)
            && directory.setReadable(true, true) && directory.setWritable(true, true) && directory.setExecutable(true, true), "LOCAL_CHECK_FAILED");
    }
    static byte[] bytes(File file, long limit) throws Exception { return AgentUpdateWorker.read(file, limit); }
    static Map read(File file) throws Exception {
        String decoded = java.nio.charset.Charset.forName("UTF-8").newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes(file, 1024L * 1024))).toString();
        Object parsed = Json.parse(decoded, 1024 * 1024); require(parsed instanceof Map, "LOCAL_CHECK_FAILED"); return (Map)parsed;
    }
    static void durable(File file, Object value) throws Exception {
        durable(file, value, new Io());
    }
    void write(File file, Object value) throws Exception { durable(file, value, io); }
    private static void durable(File file, Object value, Io io) throws Exception {
        directory(file.getParentFile());
        File temporary = new File(file.getPath() + ".tmp");
        require(temporary.equals(temporary.getCanonicalFile()), "LOCAL_CHECK_FAILED");
        io.point("before-write", file); FileOutputStream output = new FileOutputStream(temporary);
        try { output.write(Json.stringify(value).getBytes("UTF-8")); io.point("after-write-before-sync", file); output.getFD().sync(); io.point("after-sync-before-rename", file); } finally { output.close(); }
        AgentTool.ownerOnly(temporary);
        require(temporary.renameTo(file), "LOCAL_CHECK_FAILED");
        io.point("after-rename", file);
    }
    static void copy(File source, File target) throws Exception {
        copy(source, target, new Io());
    }
    void copyOwned(File source, File target) throws Exception { copy(source, target, io); }
    private static void copy(File source, File target, Io io) throws Exception {
        require(source.isFile() && source.equals(source.getCanonicalFile()) && source.length() <= 128L * 1024 * 1024, "LOCAL_CHECK_FAILED");
        directory(target.getParentFile()); require(target.equals(target.getCanonicalFile()), "LOCAL_CHECK_FAILED");
        File temporary = new File(target.getPath() + ".copy");
        require(!temporary.exists(), "LOCAL_CHECK_FAILED");
        io.point("before-copy", target); FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary);
        try { byte[] block = new byte[16384]; int n; while ((n = input.read(block)) != -1) { output.write(block, 0, n); io.point("copy-block", target); }
            io.point("copy-before-sync", target); output.getFD().sync(); io.point("copy-before-rename", target); }
        finally { try { input.close(); } finally { output.close(); } }
        AgentTool.ownerOnly(temporary);
        if (target.getName().endsWith(".sh") || source.canExecute()) require(temporary.setExecutable(true, true), "LOCAL_CHECK_FAILED");
        require(temporary.renameTo(target), "LOCAL_CHECK_FAILED");
        io.point("copy-after-rename", target);
    }
    void initialize() throws Exception { directory(root); directory(home); directory(state); directory(transactions); directory(releases); directory(managers); }
    File journal() throws Exception { return child(state, "transaction.json"); }
    File request() throws Exception { return child(root, "collector-spool/update-v3-request.json"); }
    File transaction(String operation) throws Exception { require(uuid(operation), "INVALID_OPERATION"); return child(transactions, operation); }
    Map current() throws Exception {
        File file = child(root, "current.json"); if (!file.isFile()) return null;
        Map pointer = read(file); validatePointer(pointer); return pointer;
    }
    void validatePointer(Map pointer) throws Exception {
        require(number(pointer.get("schema")) == 1 && identifier(text(pointer.get("releaseId"))) && identifier(text(pointer.get("managerId")))
            && AgentUpdateWorker.version(text(pointer.get("version"))) && digest(text(pointer.get("bundleSha256")))
            && digest(text(pointer.get("managerSha256"))), "RECOVERY_REQUIRED");
        File code = child(releases, text(pointer.get("releaseId")));
        File manager = child(managers, text(pointer.get("managerId")));
        require(code.isDirectory() && manager.isDirectory(), "RECOVERY_REQUIRED");
        Map signed = AgentBundleManifest.verifyHeader(child(code, "bundle-manifest.json"), child(code, "bundle-manifest.sig"), child(root, "update-public.der"), text(pointer.get("version")));
        require(text(pointer.get("bundleSha256")).equals(signed.get("sha256")) && signed.get("files") instanceof Map, "RECOVERY_REQUIRED");
        Map collector = (Map)((Map)signed.get("files")).get("code/collector.jar"), helper = (Map)((Map)signed.get("files")).get("manager/helper.jar");
        require(text(pointer.get("version")).equals(AgentUpdateWorker.jarVersion(child(code, "collector.jar")))
            && collector != null && collector.get("sha256").equals(AgentBundleManifest.hash(child(code, "collector.jar")))
            && helper != null && pointer.get("managerSha256").equals(helper.get("sha256"))
            && text(pointer.get("managerSha256")).equals(AgentBundleManifest.hash(child(manager, "helper.jar"))), "RECOVERY_REQUIRED");
    }
    File code(Map pointer) throws Exception { validatePointer(pointer); return child(releases, text(pointer.get("releaseId"))); }
    File manager(Map pointer) throws Exception { validatePointer(pointer); return child(managers, text(pointer.get("managerId"))); }
    void promote(Map pointer) throws Exception {
        validatePointer(pointer);
        write(child(state, "current-manager.json"), AssessmentScan.map("schema", new Long(1), "managerId", pointer.get("managerId"), "managerSha256", pointer.get("managerSha256")));
        write(child(root, "current.json"), pointer);
    }
    static Map inventory(File directory) throws Exception {
        Map result = new LinkedHashMap(); collect(directory, directory, result); return result;
    }
    private static void collect(File base, File directory, Map result) throws Exception {
        File[] children = directory.listFiles(); require(children != null, "LOCAL_CHECK_FAILED");
        for (int i = 0; i < children.length; i++) {
            File file = children[i]; require(file.equals(file.getCanonicalFile()), "LOCAL_CHECK_FAILED");
            if (file.isDirectory()) collect(base, file, result);
            else { require(file.isFile() && result.size() < 1024, "LOCAL_CHECK_FAILED"); result.put(file.getPath().substring(base.getPath().length() + 1), AgentBundleManifest.hash(file)); }
        }
    }
    /** Refuse the whole cleanup when an unknown/changed member is found. */
    static boolean deleteOwned(File directory) throws Exception {
        File ownership = child(directory, "installed.json"); if (!ownership.isFile()) return false;
        Map record = read(ownership); if (!(record.get("files") instanceof Map)) return false;
        Map actual = inventory(directory), expected = new LinkedHashMap((Map)record.get("files"));
        actual.remove("installed.json"); if (!actual.equals(expected) || !onlyOwnedDirectories(directory, directory, ownedDirectories(expected.keySet()))) return false;
        for (Object name : expected.keySet()) require(child(directory, text(name)).delete(), "LOCAL_CHECK_FAILED");
        require(ownership.delete(), "LOCAL_CHECK_FAILED"); removeEmpty(directory); return !directory.exists();
    }
    static Set ownedDirectories(Collection files) {
        Set result = new HashSet(); result.add("");
        for (Object item : files) {
            String name = text(item); int slash = name.lastIndexOf('/');
            while (slash >= 0) { name = name.substring(0, slash); result.add(name); slash = name.lastIndexOf('/'); }
        }
        return result;
    }
    private static boolean onlyOwnedDirectories(File base, File directory, Set owned) throws Exception {
        File[] children = directory.listFiles(); require(children != null, "LOCAL_CHECK_FAILED");
        for (int i = 0; i < children.length; i++) if (children[i].isDirectory()) {
            File child = children[i]; require(child.equals(child.getCanonicalFile()), "LOCAL_CHECK_FAILED");
            String relative = child.getPath().substring(base.getPath().length() + 1);
            if (!owned.contains(relative) || !onlyOwnedDirectories(base, child, owned)) return false;
        }
        return true;
    }
    static void removeKnownEmpty(File directory, Collection ownedFiles) throws Exception { removeKnownEmpty(directory, directory, ownedDirectories(ownedFiles)); }
    private static void removeKnownEmpty(File base, File directory, Set owned) throws Exception {
        File[] children = directory.listFiles(); require(children != null, "LOCAL_CHECK_FAILED");
        for (int i = 0; i < children.length; i++) if (children[i].isDirectory() && children[i].equals(children[i].getCanonicalFile())) {
            String relative = children[i].getPath().substring(base.getPath().length() + 1);
            if (owned.contains(relative)) removeKnownEmpty(base, children[i], owned);
        }
        directory.delete();
    }
    static void removeEmpty(File directory) throws Exception {
        File[] files = directory.listFiles(); require(files != null, "LOCAL_CHECK_FAILED");
        for (int i = 0; i < files.length; i++) if (files[i].isDirectory() && files[i].equals(files[i].getCanonicalFile())) removeEmpty(files[i]);
        directory.delete(); // rmdir semantics deliberately preserve unexpected members.
    }
    static Map heartbeat(Properties config) {
        Map result = new LinkedHashMap();
        if (!"/opt/java-assessment-collector/collector-spool".equals(config.getProperty("spoolDirectory"))) return result;
        try {
            AgentInstallLayout layout = production(); Map pointer = layout.current();
            if (pointer != null) { result.put("bundleSha256", pointer.get("bundleSha256")); result.put("managerSha256", pointer.get("managerSha256")); }
        } catch (Exception ignored) { /* Invalid evidence must not claim update-v3. */ }
        return result;
    }
}
