package assessment;

import java.io.*;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.jar.*;

/** Independent, initially installed updater. Remote input can only replace collector.jar. */
public final class AgentUpdateWorker {
    static final File ROOT = new File("/opt/java-assessment-collector");
    static final File HOME = new File("/opt/java-assessment-update");
    static final long MAX_JAR = 32L * 1024 * 1024;
    static final String TYPE = "UPDATE_AGENT_V1";
    final File root, home;
    final Platform platform;
    final Remote remote;
    Map journal;
    Properties config;

    // Test adapters are package-private Java objects, never CLI/environment switches.
    interface Platform { void local(String action) throws Exception; int probe(File jar, String version, boolean heartbeat) throws Exception; }
    interface Remote { Map request(String action, Map body) throws Exception; void download(String asset, File file, long limit, Map state) throws Exception; }
    static final class Failure extends IOException {
        final String diagnostic;
        Failure(String diagnostic) { super(diagnostic); this.diagnostic = diagnostic; }
    }
    AgentUpdateWorker(File root, File home, Platform platform, Remote remote) {
        this.root = root; this.home = home; this.platform = platform; this.remote = remote;
    }
    static void require(boolean value, String diagnostic) throws Failure { if (!value) throw new Failure(diagnostic); }
    static long number(Object n) { return n instanceof Long || n instanceof Integer ? ((Number)n).longValue() : -1; }
    static String string(Object s) { return s instanceof String ? (String)s : ""; }
    static byte[] read(File file, long limit) throws Exception {
        require(file.isFile() && file.length() > 0 && file.length() <= limit, "INVALID_MANIFEST");
        InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { byte[] b = new byte[8192]; int n; long total = 0;
            while ((n = in.read(b)) != -1) { total += n; require(total <= limit, "INVALID_MANIFEST"); out.write(b, 0, n); }
        } finally { in.close(); }
        return out.toByteArray();
    }
    static Map readMap(File file) throws Exception {
        Object value = Json.parse(Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(read(file, 65536))).toString());
        require(value instanceof Map, "INVALID_MANIFEST"); return (Map)value;
    }
    static void durable(File file, byte[] bytes) throws Exception {
        File temp = new File(file.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try { out.write(bytes); out.getFD().sync(); } finally { out.close(); }
        require(temp.renameTo(file), "LOCAL_CHECK_FAILED");
    }
    static void copy(File from, File to) throws Exception {
        require(from.isFile() && from.length() > 0 && from.length() <= MAX_JAR, "LOCAL_CHECK_FAILED");
        InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to);
        try { byte[] b = new byte[16384]; int n; long total = 0;
            while ((n = in.read(b)) != -1) { total += n; require(total <= MAX_JAR, "LOCAL_CHECK_FAILED"); out.write(b, 0, n); }
            out.getFD().sync();
        } finally { try { in.close(); } finally { out.close(); } }
    }
    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); InputStream in = new FileInputStream(file);
        try { byte[] b = new byte[16384]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        finally { in.close(); }
        StringBuffer s = new StringBuffer(); byte[] bytes = digest.digest();
        for (int i = 0; i < bytes.length; i++) { int n = bytes[i] & 255; if (n < 16) s.append('0'); s.append(Integer.toHexString(n)); }
        return s.toString();
    }
    static boolean version(String value) { return value.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"); }
    static int compare(String a, String b) throws Exception {
        require(version(a) && version(b), "VERSION_MISMATCH"); String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < 3; i++) { long x = Long.parseLong(left[i]), y = Long.parseLong(right[i]); if (x != y) return x > y ? 1 : -1; }
        return 0;
    }
    static String jarVersion(File file) throws Exception {
        JarFile jar = new JarFile(file, false);
        try {
            JarEntry entry = jar.getJarEntry("META-INF/MANIFEST.MF");
            require(entry != null && entry.getSize() > 0 && entry.getSize() <= 65536, "VERSION_MISMATCH");
            InputStream in = jar.getInputStream(entry); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) { require(bytes.size() + n <= 65536, "VERSION_MISMATCH"); bytes.write(b, 0, n); } }
            finally { in.close(); }
            String value = new Manifest(new ByteArrayInputStream(bytes.toByteArray())).getMainAttributes().getValue("Implementation-Version");
            require(value != null && version(value), "VERSION_MISMATCH"); return value;
        } finally { jar.close(); }
    }
    static Set stableFiles(File root) throws Exception {
        Set names = new HashSet(); File[] files = root.listFiles(); require(files != null, "INCOMPATIBLE_ASSETS");
        for (int i = 0; i < files.length; i++) if (files[i].getName().endsWith(".sh")) names.add(files[i].getName());
        files = new File(root, "lib").listFiles(); require(files != null && files.length > 0, "INCOMPATIBLE_ASSETS");
        for (int i = 0; i < files.length; i++) if (files[i].getName().endsWith(".jar")) names.add("lib/" + files[i].getName());
        names.add("update-public.der"); return names;
    }
    static Map verify(File root, File manifest, File signature, File payload, String requested, String previous) throws Exception {
        try { return verifyContents(root, manifest, signature, payload, requested, previous); }
        catch (Failure e) { throw e; }
        catch (GeneralSecurityException e) { throw new Failure("INVALID_SIGNATURE"); }
        catch (Exception e) { throw new Failure("INVALID_MANIFEST"); }
    }
    static Map verifyContents(File root, File manifest, File signature, File payload, String requested, String previous) throws Exception {
        byte[] raw = read(manifest, 65536), sig = read(signature, 1024);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(read(new File(root, "update-public.der"), 8192)));
        require(key instanceof RSAPublicKey && ((RSAPublicKey)key).getModulus().bitLength() == 3072 && sig.length == 384, "INVALID_SIGNATURE");
        Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(key); verifier.update(raw);
        require(verifier.verify(sig), "INVALID_SIGNATURE");
        Map m = readMap(manifest);
        require(m.keySet().equals(new HashSet(Arrays.asList(new String[]{"type","schema","version","format","minUpdater","sha256","bytes","requiredFiles"})))
            && "ASSESSMENT_AGENT_UPDATE_V1".equals(m.get("type")) && number(m.get("schema")) == 1
            && "collector-jar-v1".equals(m.get("format")) && (number(m.get("minUpdater")) == 1 || number(m.get("minUpdater")) == 2), "INVALID_MANIFEST");
        require(version(requested) && requested.equals(m.get("version")), "VERSION_MISMATCH");
        require(compare(requested, previous) > 0, "DOWNGRADE_REFUSED");
        require(number(m.get("bytes")) > 0 && number(m.get("bytes")) <= MAX_JAR
            && string(m.get("sha256")).matches("[a-f0-9]{64}"), "INVALID_MANIFEST");
        require(m.get("requiredFiles") instanceof Map, "INCOMPATIBLE_ASSETS"); Map assets = (Map)m.get("requiredFiles");
        require(assets.size() > 0 && assets.size() <= 128 && assets.keySet().equals(stableFiles(root)), "INCOMPATIBLE_ASSETS");
        for (Object item : assets.entrySet()) {
            Map.Entry e = (Map.Entry)item; String name = string(e.getKey());
            require(name.matches("([a-zA-Z0-9_-]+[.]sh|lib/[a-zA-Z0-9_.-]+[.]jar|update-public[.]der)") && name.indexOf("..") < 0
                && string(e.getValue()).matches("[a-f0-9]{64}"), "INCOMPATIBLE_ASSETS");
            File asset = new File(root, name);
            require(asset.isFile() && asset.getCanonicalFile().equals(asset.getAbsoluteFile()) && e.getValue().equals(hash(asset)), "INCOMPATIBLE_ASSETS");
        }
        if (payload != null) {
            require(payload.length() == number(m.get("bytes")) && m.get("sha256").equals(hash(payload)), "HASH_MISMATCH");
            require(requested.equals(jarVersion(payload)), "VERSION_MISMATCH");
        }
        return m;
    }
    void save(String phase) throws Exception {
        journal.put("phase", phase); durable(new File(home, "journal.json"), Json.stringify(journal).getBytes("UTF-8")); platform.local("sync");
    }
    void report(String status, String diagnostic, String current) throws Exception {
        Map ack = remote.request("report", AssessmentScan.map("hostId", journal.get("hostId"), "requestId", journal.get("requestId"),
            "status", status, "version", current, "diagnostic", diagnostic));
        if (!(Boolean.TRUE.equals(ack.get("accepted")) && journal.get("requestId").equals(ack.get("requestId"))
            && status.equals(ack.get("status")))) throw new IOException("NETWORK_UNCONFIRMED");
    }
    boolean claim() throws Exception {
        Map ack = remote.request("claim", AssessmentScan.map("hostId", journal.get("hostId"), "requestId", journal.get("requestId")));
        if (!journal.get("requestId").equals(ack.get("requestId"))) throw new IOException("NETWORK_UNCONFIRMED");
        if (Boolean.FALSE.equals(ack.get("executable"))) return false;
        require(Boolean.TRUE.equals(ack.get("executable")) && journal.get("targetVersion").equals(ack.get("targetVersion"))
            && number(ack.get("expiresMillis")) > System.currentTimeMillis(), "INVALID_MANIFEST"); return true;
    }
    void cleanup() throws Exception {
        // Delete journal LAST. A crash during cleanup safely repeats its acknowledged result.
        String[] files = {"manifest.json","signature.bin","candidate.jar","backup.jar","backup.tmp","journal.json.tmp"};
        for (int i = 0; i < files.length; i++) { File f = new File(home, files[i]); require(!f.exists() || f.delete(), "LOCAL_CHECK_FAILED"); }
        File incoming = new File(root, "collector-spool/update-request.json");
        if (incoming.isFile() && journal.get("requestId").equals(readMap(incoming).get("requestId"))) require(incoming.delete(), "LOCAL_CHECK_FAILED");
        require(new File(home, "journal.json").delete(), "LOCAL_CHECK_FAILED"); platform.local("sync");
    }
    void result(String status, String diagnostic) throws Exception {
        journal.put("status", status); journal.put("diagnostic", diagnostic); save("RESULT");
    }
    void rollback(String reason) throws Exception {
        platform.local("pause"); File backup = new File(home, "backup.jar");
        if (backup.exists()) {
            require(journal.get("previousHash").equals(hash(backup)) && journal.get("previousVersion").equals(jarVersion(backup)), "RECOVERY_REQUIRED");
            File swap = new File(root, ".collector-update.jar"); copy(backup, swap);
            require(swap.renameTo(new File(root, "collector.jar")), "RECOVERY_REQUIRED"); platform.local("sync");
        } else require(journal.get("previousHash").equals(hash(new File(root, "collector.jar"))), "RECOVERY_REQUIRED");
        require(platform.probe(new File(root, "collector.jar"), string(journal.get("previousVersion")), false) == 0, "RECOVERY_REQUIRED");
        platform.local("start"); result(backup.exists() ? "ROLLED_BACK" : "FAILED", reason);
    }
    void finish() throws Exception {
        String current = jarVersion(new File(root, "collector.jar"));
        report(string(journal.get("status")), string(journal.get("diagnostic")), current); cleanup();
    }
    void confirm() throws Exception {
        File live = new File(root, "collector.jar");
        require(journal.get("targetVersion").equals(jarVersion(live)) && journal.get("targetHash").equals(hash(live)), "RECOVERY_REQUIRED");
        require(platform.probe(live, string(journal.get("targetVersion")), false) == 0, "PROBE_FAILED");
        platform.local("start");
        report("VERIFYING", "OK", string(journal.get("targetVersion")));
        int heartbeat = platform.probe(live, string(journal.get("targetVersion")), true);
        require(heartbeat == 0 || heartbeat == 10 || heartbeat == 11, "PROBE_FAILED");
        if (heartbeat != 0) throw new IOException(heartbeat == 11 ? "AUTHORIZATION_REVOKED" : "NETWORK_UNCONFIRMED");
        result("SUCCEEDED", "OK"); finish();
    }
    void cycle() throws Exception {
        platform.local("check"); config = AgentTool.load(new File(root, "conf/collector.properties"));
        File state = new File(home, "journal.json"), incoming = new File(root, "collector-spool/update-request.json");
        if (state.isFile()) journal = readMap(state);
        else {
            if (!incoming.isFile()) return; Map action = readMap(incoming);
            require(TYPE.equals(action.get("type")) && string(action.get("requestId")).matches("[a-f0-9-]{36}")
                && version(string(action.get("targetVersion"))), "INVALID_MANIFEST");
            journal = AssessmentScan.map("requestId", action.get("requestId"), "hostId", AssessmentCollector.required(config, "hostId"),
                "targetVersion", action.get("targetVersion"), "previousVersion", jarVersion(new File(root, "collector.jar")),
                "previousHash", hash(new File(root, "collector.jar")));
            save("DOWNLOADING");
        }
        require(AssessmentCollector.required(config, "hostId").equals(journal.get("hostId")), "LOCAL_CHECK_FAILED");
        String phase = string(journal.get("phase"));
        if ("RESULT".equals(phase)) { finish(); return; }
        if ("PAUSING".equals(phase) || "SWAPPING".equals(phase)) { rollback("RECOVERY_REQUIRED"); finish(); return; }
        if ("CONFIRMING".equals(phase)) {
            try { confirm(); } catch (Failure bad) { rollback(bad.diagnostic); finish(); } return;
        }
        require("DOWNLOADING".equals(phase), "RECOVERY_REQUIRED");
        try {
            if (!claim()) { cleanup(); return; }
            remote.download("manifest", new File(home, "manifest.json"), 65536, journal);
            remote.download("signature", new File(home, "signature.bin"), 1024, journal);
            Map manifest = verify(root, new File(home, "manifest.json"), new File(home, "signature.bin"), null,
                string(journal.get("targetVersion")), string(journal.get("previousVersion")));
            remote.download("package", new File(home, "candidate.jar"), number(manifest.get("bytes")), journal);
            verify(root, new File(home, "manifest.json"), new File(home, "signature.bin"), new File(home, "candidate.jar"),
                string(journal.get("targetVersion")), string(journal.get("previousVersion")));
            require(platform.probe(new File(home, "candidate.jar"), string(journal.get("targetVersion")), false) == 0, "PROBE_FAILED");
            report("INSTALLING", "OK", string(journal.get("previousVersion")));
            if (!claim()) { cleanup(); return; }
            journal.put("targetHash", manifest.get("sha256")); save("PAUSING");
            platform.local("pause"); platform.local("check");
            require(journal.get("previousHash").equals(hash(new File(root, "collector.jar"))), "LOCAL_CHECK_FAILED");
            verify(root, new File(home, "manifest.json"), new File(home, "signature.bin"), new File(home, "candidate.jar"),
                string(journal.get("targetVersion")), string(journal.get("previousVersion")));
            // Authorization is rechecked with the scheduler stopped, immediately before the swap.
            if (!claim()) { platform.local("start"); cleanup(); return; }
            copy(new File(root, "collector.jar"), new File(home, "backup.tmp"));
            require(new File(home, "backup.tmp").renameTo(new File(home, "backup.jar")), "LOCAL_CHECK_FAILED");
            save("SWAPPING"); File swap = new File(root, ".collector-update.jar"); copy(new File(home, "candidate.jar"), swap);
            require(swap.renameTo(new File(root, "collector.jar")), "LOCAL_CHECK_FAILED"); platform.local("sync");
            require(platform.probe(new File(root, "collector.jar"), string(journal.get("targetVersion")), false) == 0, "PROBE_FAILED");
            save("CONFIRMING");
        } catch (Failure bad) {
            phase = string(journal.get("phase"));
            if ("PAUSING".equals(phase) || "SWAPPING".equals(phase)) rollback(bad.diagnostic);
            else result("FAILED", bad.diagnostic);
            finish(); return;
        } catch (Exception error) {
            phase = string(journal.get("phase"));
            if ("PAUSING".equals(phase)) {
                // Authorization transport failed before any replacement. Restore polling,
                // keep the request durable and retry; an outage is not bad collector code.
                require(journal.get("previousHash").equals(hash(new File(root, "collector.jar"))), "RECOVERY_REQUIRED");
                platform.local("start"); save("DOWNLOADING"); throw error;
            }
            if ("SWAPPING".equals(phase)) { rollback("UPDATE_FAILED"); finish(); return; }
            throw error;
        }
        confirm();
    }
    static final class Production implements Platform {
        public void local(String action) throws Exception {
            require(Arrays.asList(new String[]{"check","pause","start","sync"}).contains(action), "LOCAL_CHECK_FAILED");
            require(process(new String[]{"/bin/sh", new File(HOME, "update-local.sh").getPath(), action}, 1600000L) == 0, "SERVICE_FAILED");
        }
        public int probe(File jar, String version, boolean heartbeat) throws Exception {
            String location = jar.equals(new File(HOME, "candidate.jar")) ? "candidate" : "installed";
            require(("candidate".equals(location) || jar.equals(new File(ROOT, "collector.jar"))) && version(version), "LOCAL_CHECK_FAILED");
            // Shell owns the precise child PID and can TERM/KILL/reap it on Java6.
            // Process.destroy alone cannot bound a JVM stuck in shutdown hooks.
            return probeResult(process(new String[]{"/bin/sh", new File(HOME, "update-service.sh").getPath(), "probe", location, version,
                heartbeat ? "heartbeat" : "probe"}, 90000L), heartbeat);
        }
        static int probeResult(int result, boolean heartbeat) {
            return heartbeat && (result == 30 || result == 31) ? 10 : result;
        }
        static int process(String[] args, long limit) throws Exception {
            final Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            final boolean[] excessive = new boolean[1];
            Thread drain = new Thread(new Runnable(){ public void run() { try { InputStream in = p.getInputStream(); byte[] b = new byte[1024]; int n, total = 0;
                try { while ((n = in.read(b)) != -1) { total += n; if (total > 8192) { excessive[0] = true; p.destroy(); break; } } } finally { in.close(); }
                } catch (IOException ignored) {} } }); drain.setDaemon(true); drain.start();
            long deadline = System.currentTimeMillis() + limit;
            while (true) {
                try { int status = p.exitValue(); drain.join(1000); return excessive[0] ? 31 : status; }
                catch (IllegalThreadStateException running) { if (System.currentTimeMillis() > deadline) { p.destroy(); return 30; } Thread.sleep(100); }
            }
        }
    }
    static String retryDiagnostic(Exception error) {
        return error instanceof Failure ? ((Failure)error).diagnostic : "NETWORK_UNCONFIRMED";
    }
    static String retryCode(Exception error) {
        return error instanceof Failure ? ((Failure)error).diagnostic : AgentTransport.safeError(error);
    }
    public static void main(String[] args) {
        AgentUpdateWorker worker = null;
        try {
            if (args.length != 0) throw new IllegalArgumentException("No arguments accepted");
            new Production().local("check");
            final Properties c = AgentTool.load(new File(ROOT, "conf/collector.properties")); final AgentTransport transport = new AgentTransport(c);
            final String token = AssessmentCollector.required(c, "agentToken");
            worker = new AgentUpdateWorker(ROOT, HOME, new Production(), new Remote() {
                public Map request(String action, Map body) throws Exception { return transport.request("POST", "/api/agent/update/" + action, Json.stringify(body), token); }
                public void download(String asset, File file, long limit, Map state) throws Exception {
                    String path = "/api/agent/update/" + asset + "?hostId=" + URLEncoder.encode(string(state.get("hostId")), "UTF-8")
                        + "&requestId=" + string(state.get("requestId")); transport.download(path, file, limit, token);
                }
            }); worker.cycle();
        } catch (Exception error) {
            if (worker != null && worker.journal != null) {
                try { worker.report("UNCONFIRMED", retryDiagnostic(error), jarVersion(new File(ROOT, "collector.jar"))); } catch (Exception ignored) {}
            }
            System.err.println("AGENT_UPDATE_RETRY=" + retryCode(error)); System.exit(1);
        }
    }
}
