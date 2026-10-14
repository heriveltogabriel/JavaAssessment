package assessment;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Executes the ORIGINAL 1.5.12 worker in an isolated loader, never the compiled
 * current source. The filesystem/journal/ACK/cleanup are real disposable files.
 * Platform probes, scheduler operations and the outer lifecycle lock are object
 * adapters: this suite is not evidence of Java 6, systemd, SysV or power-loss
 * behaviour. The separate handoff suite supplies those additional boundaries.
 */
public final class LegacyAgentMigrationTest {
    private static int checks;
    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000012";
    private static final String HOST_ID = "migration-fixture-host";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] block = new byte[16384];
            for (int n; (n = input.read(block)) >= 0;) if (n != 0) digest.update(block, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                check(!Files.isSymbolicLink(item), "fixture contains no symbolic links");
                Path target = destination.resolve(source.relativize(item));
                if (Files.isDirectory(item)) Files.createDirectories(target);
                else Files.copy(item, target);
            }
        }
    }

    private static void removeTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(item);
        }
    }

    private static void put(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    private static String version(Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            return archive.getManifest().getMainAttributes().getValue("Implementation-Version");
        }
    }

    private static final class Original implements AutoCloseable {
        final Path fixture, baseline;
        final URLClassLoader loader;
        final Class<?> worker, platform, remote;
        final Constructor<?> constructor;
        final Method cycle, parse, stringify;
        final Map<String,Object> metadata;
        final String fromVersion, bridgeVersion, baselineHash, recoveryResource;

        Original(Path directory) throws Exception {
            fixture = directory.toRealPath();
            // Only JDK classes may be inherited. A classpath containing today's
            // assessment.AgentUpdateWorker cannot satisfy this loader.
            baseline = fixture.resolve("baseline/collector.jar").toRealPath();
            loader = new URLClassLoader(new URL[]{baseline.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            worker = Class.forName("assessment.AgentUpdateWorker", true, loader);
            platform = Class.forName("assessment.AgentUpdateWorker$Platform", true, loader);
            remote = Class.forName("assessment.AgentUpdateWorker$Remote", true, loader);
            Class<?> json = Class.forName("assessment.Json", true, loader);
            parse = json.getDeclaredMethod("parse", String.class); parse.setAccessible(true);
            stringify = json.getDeclaredMethod("stringify", Object.class); stringify.setAccessible(true);
            constructor = worker.getDeclaredConstructor(java.io.File.class, java.io.File.class, platform, remote);
            constructor.setAccessible(true);
            cycle = worker.getDeclaredMethod("cycle"); cycle.setAccessible(true);
            metadata = readMap(fixture.resolve("fixture.json"));
            check(Boolean.TRUE.equals(metadata.get("testOnly")), "fixture explicitly marked test-only");
            check(Long.valueOf(1).equals(metadata.get("schema")), "known fixture schema");
            fromVersion = (String) metadata.get("fromVersion");
            bridgeVersion = (String) metadata.get("bridgeVersion");
            baselineHash = (String) metadata.get("baselineSha256");
            recoveryResource = (String) metadata.get("recoveryResource");
            check("1.5.12".equals(fromVersion), "WF0 baseline is exactly 1.5.12");
            check(fromVersion.equals(version(baseline)), "original JAR declares 1.5.12");
            check(baselineHash.equals(hash(baseline)), "original binary matches recorded baseline hash");
            check(worker.getClassLoader() == loader, "worker uses child loader");
            check(worker.getProtectionDomain().getCodeSource().getLocation().toURI().equals(baseline.toUri()),
                "worker code source is original binary, not rebuilt product classes");
            check(Class.forName("assessment.Json", false, loader).getClassLoader() == loader,
                "worker dependencies are isolated from current product classes");
            System.out.println("ORIGINAL_WORKER_CLASSLOADER=" + worker.getClassLoader().getClass().getName());
            System.out.println("ORIGINAL_WORKER_CODESOURCE=" + worker.getProtectionDomain().getCodeSource().getLocation());
            System.out.println("ORIGINAL_WORKER_SHA256=" + baselineHash);
        }

        @SuppressWarnings("unchecked")
        Map<String,Object> readMap(Path file) throws Exception {
            return (Map<String,Object>) parse.invoke(null, Files.readString(file, StandardCharsets.UTF_8));
        }

        String json(Object value) throws Exception { return (String) stringify.invoke(null, value); }
        Path asset(String name) { return fixture.resolve((String) metadata.get(name)); }
        @Override public void close() throws IOException { loader.close(); }
    }

    private static final class SimulatedCrash extends Error {
        SimulatedCrash() { super("simulated worker process death after swap"); }
    }

    private static final class Scenario implements AutoCloseable {
        final Original original;
        final Path base, root, home;
        final Map<String,String> preserved = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<String> reports = new ArrayList<>();
        final Map<String,Object> manifest;
        byte[] signature;
        boolean running = true, lifecycleLockHeld, loseSuccessAck, rejectSuccessAck, crashAfterSwap, failLiveProbe;
        int downloads, pauses, starts, heartbeats, bootstrapStarts;
        String lastDiagnostic;

        Scenario(Original original) throws Exception {
            this.original = original;
            base = Files.createTempDirectory("legacy-agent-migration-").toRealPath();
            root = base.resolve("collector"); home = base.resolve("helper");
            copyTree(original.asset("root"), root);
            if (Files.isDirectory(original.asset("home"))) copyTree(original.asset("home"), home);
            else Files.createDirectories(home);
            check(hash(root.resolve("collector.jar")).equals(original.baselineHash), "scenario starts from exact original collector");
            // Deliberate local state absent from every downloadable artifact.
            put(root.resolve("conf/collector.properties"), "hostId=" + HOST_ID
                + "\nagentToken=private-fixture-token\nserverUrl=http://127.0.0.1:1"
                + "\ntlsCaFile=" + root.resolve("conf/ca.pem")
                + "\nspoolDirectory=" + root.resolve("collector-spool") + "\n");
            put(root.resolve("conf/installation-id"), "installation-preserved\n");
            put(root.resolve("conf/ca.pem"), "LOCAL-CA-MUST-SURVIVE\n");
            put(root.resolve("conf/java.path"), "/protected/private-java/bin/java\n");
            put(root.resolve("collector-spool/pending-assessment.json"), "{\"pending\":true}\n");
            put(root.resolve("collector-spool/latest-snapshot.json"), "{\"snapshot\":\"preserve\"}\n");
            put(root.resolve("logs/prior-install.log"), "prior diagnostic\n");
            put(root.resolve("applications/customer-file.txt"), "customer application must survive\n");
            for (String file : new String[]{"conf/collector.properties", "conf/installation-id", "conf/ca.pem", "conf/java.path",
                    "collector-spool/pending-assessment.json", "collector-spool/latest-snapshot.json", "logs/prior-install.log", "applications/customer-file.txt"})
                preserved.put(file, hash(root.resolve(file)));
            put(root.resolve("collector-spool/update-request.json"), original.json(map("type", "UPDATE_AGENT_V1",
                "requestId", REQUEST_ID, "targetVersion", original.bridgeVersion, "expiresMillis", System.currentTimeMillis() + 600000L)));
            manifest = original.readMap(original.asset("manifest"));
            signature = Files.readAllBytes(original.asset("signature"));
        }

        void unchanged() throws Exception {
            for (var file : preserved.entrySet())
                check(file.getValue().equals(hash(root.resolve(file.getKey()))), "preserved byte-for-byte: " + file.getKey());
        }

        boolean bootstrapEligible() throws Exception {
            return !lifecycleLockHeld && !Files.exists(home.resolve("journal.json"))
                && !Files.exists(root.resolve("collector-spool/update-request.json"))
                && original.bridgeVersion.equals(version(root.resolve("collector.jar")));
        }

        void assertNoBootstrapDuringCycle() throws Exception {
            check(lifecycleLockHeld, "adapter called while outer lifecycle lock is held");
            check(!bootstrapEligible(), "bootstrap cannot start from worker probe/report/local action");
            check(bootstrapStarts == 0, "no bootstrap was started during original cycle");
        }

        Object local(String action) throws Exception {
            events.add(action); assertNoBootstrapDuringCycle();
            if ("pause".equals(action)) { running = false; pauses++; }
            if ("start".equals(action)) { running = true; starts++; }
            if ("sync".equals(action) && crashAfterSwap && original.bridgeVersion.equals(version(root.resolve("collector.jar")))) {
                crashAfterSwap = false;
                check("SWAPPING".equals(original.readMap(home.resolve("journal.json")).get("phase")), "crash is after persisted SWAPPING");
                throw new SimulatedCrash();
            }
            return null;
        }

        Object probe(java.io.File file, String expectedVersion, boolean heartbeat) throws Exception {
            assertNoBootstrapDuringCycle();
            check(expectedVersion.equals(version(file.toPath())), "probe adapter checks actual JAR version");
            unchanged();
            if (heartbeat) { heartbeats++; events.add("heartbeat:" + expectedVersion); }
            else events.add("probe:" + file.getName() + ":" + expectedVersion);
            if (failLiveProbe && file.toPath().equals(root.resolve("collector.jar")) && expectedVersion.equals(original.bridgeVersion)) return 20;
            return 0;
        }

        Object request(String action, Map<?,?> body) throws Exception {
            assertNoBootstrapDuringCycle();
            check(REQUEST_ID.equals(body.get("requestId")) && HOST_ID.equals(body.get("hostId")), "request remains pinned to host and operation");
            if ("claim".equals(action)) return map("requestId", REQUEST_ID, "executable", true,
                "targetVersion", original.bridgeVersion, "expiresMillis", System.currentTimeMillis() + 600000L);
            check("report".equals(action), "only fixed legacy actions are called");
            String status = (String) body.get("status");
            reports.add(status); events.add("report:" + status); lastDiagnostic = (String) body.get("diagnostic");
            if ("SUCCEEDED".equals(status)) {
                check(heartbeats > 0, "success follows bridge heartbeat");
                check(Files.exists(home.resolve("backup.jar")), "original backup is present before terminal ACK");
                check(hash(home.resolve("backup.jar")).equals(original.baselineHash), "original backup is exact baseline");
                if (loseSuccessAck) { loseSuccessAck = false; throw new IOException("simulated terminal ACK lost"); }
                if (rejectSuccessAck) return map("accepted", true, "requestId", "00000000-0000-0000-0000-000000000099", "status", status);
            }
            return map("accepted", true, "requestId", REQUEST_ID, "status", status);
        }

        Object download(String asset, java.io.File file, long limit, Map<?,?> state) throws Exception {
            assertNoBootstrapDuringCycle();
            check(REQUEST_ID.equals(state.get("requestId")), "download is bound to original request");
            byte[] bytes = switch (asset) {
                case "manifest" -> Files.readAllBytes(original.asset("manifest"));
                case "signature" -> signature;
                case "package" -> Files.readAllBytes(original.asset("bridgeJar"));
                default -> throw new AssertionError("unexpected asset: " + asset);
            };
            check(bytes.length <= limit, "legacy bounded download: " + asset);
            Files.write(file.toPath(), bytes); downloads++; events.add("download:" + asset); return null;
        }

        Object proxy(Class<?> contract, InvocationHandler handler) {
            return Proxy.newProxyInstance(original.loader, new Class<?>[]{contract}, handler);
        }

        void cycle() throws Exception {
            check(!lifecycleLockHeld, "no overlapping original cycle");
            lifecycleLockHeld = true;
            try {
                Object platform = proxy(original.platform, (p, m, a) -> switch (m.getName()) {
                    case "local" -> local((String) a[0]);
                    case "probe" -> probe((java.io.File) a[0], (String) a[1], (Boolean) a[2]);
                    default -> throw new AssertionError("unexpected platform method: " + m);
                });
                Object remote = proxy(original.remote, (p, m, a) -> switch (m.getName()) {
                    case "request" -> request((String) a[0], (Map<?,?>) a[1]);
                    case "download" -> download((String) a[0], (java.io.File) a[1], ((Number) a[2]).longValue(), (Map<?,?>) a[3]);
                    default -> throw new AssertionError("unexpected remote method: " + m);
                });
                Object worker = original.constructor.newInstance(root.toFile(), home.toFile(), platform, remote);
                try { original.cycle.invoke(worker); }
                catch (InvocationTargetException wrapped) {
                    Throwable cause = wrapped.getCause();
                    if (cause instanceof Exception e) throw e;
                    if (cause instanceof Error e) throw e;
                    throw new AssertionError(cause);
                }
            } finally { lifecycleLockHeld = false; }
        }

        void cleaned() throws Exception {
            for (String file : new String[]{"journal.json", "backup.jar", "candidate.jar", "manifest.json", "signature.bin"})
                check(!Files.exists(home.resolve(file)), "terminal ACK really cleans " + file);
            check(!Files.exists(root.resolve("collector-spool/update-request.json")), "terminal ACK cleans matching incoming request");
            unchanged();
        }

        @SuppressWarnings("unchecked")
        Path materializeRecovery() throws Exception {
            check(bootstrapEligible(), "continuation allowed only after ACK, cleanup and lock release");
            bootstrapStarts++;
            Path recovery = base.resolve("prepared-recovery"); Files.createDirectories(recovery);
            Path origin = recovery.resolve("collector.jar");
            try (JarFile bridge = new JarFile(root.resolve("collector.jar").toFile())) {
                JarEntry resource = bridge.getJarEntry(original.recoveryResource);
                check(resource != null && resource.getSize() == Files.size(original.baseline), "embedded original survives legacy cleanup");
                try (InputStream input = bridge.getInputStream(resource)) { Files.copy(input, origin); }
            }
            check(hash(origin).equals(original.baselineHash), "materialized original matches pinned baseline");
            check(original.fromVersion.equals(version(origin)), "materialized recovery returns to origin, not bridge");
            Map<String,Object> support = (Map<String,Object>) manifest.get("requiredFiles");
            for (var entry : support.entrySet()) {
                Path source = root.resolve(entry.getKey());
                check(entry.getValue().equals(hash(source)), "support still matches signed source profile: " + entry.getKey());
                Path destination = recovery.resolve("support").resolve(entry.getKey());
                Files.createDirectories(destination.getParent()); Files.copy(source, destination);
                check(entry.getValue().equals(hash(destination)), "materialized support remains exact: " + entry.getKey());
            }
            if (Files.exists(home.resolve("helper.jar"))) {
                check(hash(home.resolve("helper.jar")).equals(original.baselineHash), "installed old helper is original baseline");
                Files.copy(home.resolve("helper.jar"), recovery.resolve("helper.jar"));
            }
            unchanged(); return recovery;
        }

        @Override public void close() throws IOException { removeTree(base); }
    }

    private static void successful(Original original) throws Exception {
        try (Scenario test = new Scenario(original)) {
            test.cycle();
            check(test.reports.get(test.reports.size() - 1).equals("SUCCEEDED"), "original worker completes full legacy cycle");
            check(test.running && test.pauses == 1 && test.starts >= 1, "collector is paused and restarted once through adapter");
            check(original.bridgeVersion.equals(version(test.root.resolve("collector.jar"))), "actual installed JAR is bridge");
            check(test.downloads == 3, "all three original asset transfers occurred");
            check(test.events.indexOf("probe:candidate.jar:" + original.bridgeVersion) < test.events.indexOf("pause"), "candidate probe precedes any pause");
            check(test.events.indexOf("heartbeat:" + original.bridgeVersion) < test.events.indexOf("report:SUCCEEDED"), "heartbeat precedes terminal report");
            test.cleaned();
            // Even with a fully cleaned journal, handoff may not run until the
            // enclosing process releases its lifecycle lock.
            test.lifecycleLockHeld = true;
            check(!test.bootstrapEligible(), "clean journal does not bypass still-held lifecycle lock");
            test.lifecycleLockHeld = false;
            Path recovery = test.materializeRecovery();
            Files.copy(recovery.resolve("collector.jar"), test.root.resolve("collector.jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            check(hash(test.root.resolve("collector.jar")).equals(original.baselineHash), "post-cleanup recovery restores actual original bytes");
            test.unchanged();
        }
    }

    private static void ackRetry(Original original, boolean wrongAck) throws Exception {
        try (Scenario test = new Scenario(original)) {
            test.loseSuccessAck = !wrongAck; test.rejectSuccessAck = wrongAck;
            try { test.cycle(); throw new AssertionError("unconfirmed terminal ACK was accepted"); }
            catch (IOException expected) { checks++; }
            check("RESULT".equals(original.readMap(test.home.resolve("journal.json")).get("phase")), "unconfirmed ACK retains terminal journal");
            check(Files.exists(test.home.resolve("backup.jar")), "unconfirmed ACK retains original backup");
            check(!test.bootstrapEligible(), "no bootstrap while terminal journal awaits ACK");
            check(original.bridgeVersion.equals(version(test.root.resolve("collector.jar"))) && test.running, "lost ACK keeps healthy bridge running");
            int transfers = test.downloads, heartbeats = test.heartbeats, pauses = test.pauses;
            test.rejectSuccessAck = false; test.cycle();
            check(test.downloads == transfers && test.heartbeats == heartbeats && test.pauses == pauses, "terminal replay does not download, reprobe or reinstall");
            check(test.reports.stream().filter("SUCCEEDED"::equals).count() == 2, "exact same terminal result is reported again");
            test.cleaned(); test.materializeRecovery();
        }
    }

    private static void interruptedSwap(Original original) throws Exception {
        try (Scenario test = new Scenario(original)) {
            test.crashAfterSwap = true;
            try { test.cycle(); throw new AssertionError("simulated swap crash did not occur"); }
            catch (SimulatedCrash expected) { checks++; }
            check(original.bridgeVersion.equals(version(test.root.resolve("collector.jar"))), "crash occurred after actual JAR replacement");
            check(Files.exists(test.home.resolve("backup.jar")), "crash retains original backup");
            check(!test.bootstrapEligible(), "crash journal prevents handoff");
            test.cycle();
            check("ROLLED_BACK".equals(test.reports.get(test.reports.size() - 1)), "new original worker instance resumes into rollback");
            check(hash(test.root.resolve("collector.jar")).equals(original.baselineHash) && test.running, "crash recovery restores baseline and scheduler");
            test.cleaned(); check(!test.bootstrapEligible(), "rolled-back origin cannot start bridge bootstrap");
        }
    }

    private static void failedLiveProbe(Original original) throws Exception {
        try (Scenario test = new Scenario(original)) {
            test.failLiveProbe = true; test.cycle();
            check("ROLLED_BACK".equals(test.reports.get(test.reports.size() - 1)), "bad bridge live probe rolls back");
            check("PROBE_FAILED".equals(test.lastDiagnostic), "bad live probe has explicit diagnosis");
            check(hash(test.root.resolve("collector.jar")).equals(original.baselineHash) && test.running, "failed live code restores origin");
            test.cleaned(); check(test.bootstrapStarts == 0, "failed candidate never bootstraps");
        }
    }

    @SuppressWarnings("unchecked")
    private static void rejection(Original original, boolean badSignature) throws Exception {
        try (Scenario test = new Scenario(original)) {
            String expected;
            if (badSignature) { test.signature[0] ^= 1; expected = "INVALID_SIGNATURE"; }
            else {
                Map<String,Object> assets = (Map<String,Object>) test.manifest.get("requiredFiles");
                String script = assets.keySet().stream().filter(name -> name.endsWith(".sh")).findFirst().orElseThrow();
                put(test.root.resolve(script), "modified support must be rejected\n"); expected = "INCOMPATIBLE_ASSETS";
            }
            test.cycle();
            check(test.reports.size() == 1 && "FAILED".equals(test.reports.get(0)), "invalid bridge attempt is terminal before replacement");
            check(expected.equals(test.lastDiagnostic), "legacy worker rejects with " + expected);
            check(test.pauses == 0 && test.heartbeats == 0, "invalid candidate never stops or probes installed collector");
            check(hash(test.root.resolve("collector.jar")).equals(original.baselineHash), "invalid attempt preserves original collector");
            check(test.bootstrapStarts == 0 && !test.bootstrapEligible(), "invalid attempt never enables bootstrap");
            test.cleaned();
        }
    }

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args.length == 0 ? "tmp/agent-migration/fixture" : args[0]);
        try (Original original = new Original(fixture)) {
            successful(original);
            ackRetry(original, false);
            ackRetry(original, true);
            interruptedSwap(original);
            failedLiveProbe(original);
            rejection(original, false);
            rejection(original, true);
        }
        System.out.println("LegacyAgentMigrationTest PASS " + checks + " checks: original binary cycle, exact ACK/retry, actual cleanup, embedded origin, crash rollback, invalid support/signature, handoff ordering");
        System.out.println("VALIDATION_LIMIT=JDK17 with object adapters for OS probes/services/lifecycle lock; no claim of Java6 execution, real bootstrap, flock, systemd/SysV, reboot or power-loss validation");
    }
}
