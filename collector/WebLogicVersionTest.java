package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Real legacy archive shape, isolated processes and hostile metadata. No server execution. */
public final class WebLogicVersionTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static File product(File home, String version, int count, boolean duplicate) throws Exception {
        File file = new File(home, "lib/weblogic.jar"); file.getParentFile().mkdirs();
        Manifest manifest = new Manifest(); Attributes attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0"); attrs.putValue("Implementation-Title", "WebLogic Server " + version);
        if (version != null) attrs.putValue("Implementation-Version", version);
        JarOutputStream out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(file)), manifest);
        try {
            if (duplicate) { out.putNextEntry(new JarEntry("meta-inf/manifest.mf")); manifest.write(out); out.closeEntry(); }
            for (int i = 1; i < count; i++) {
                out.putNextEntry(new JarEntry("weblogic/fixture/package/classes/Component" + i + ".class"));
                out.write(new byte[]{0, 1, 2, 3}); out.closeEntry();
            }
        } finally { out.close(); }
        return file;
    }
    static Map server(AssessmentScan scan, String name) { for (Map row : scan.servers.values()) if ("WebLogic".equals(row.get("type")) && name.equals(row.get("name"))) return row; return null; }
    static void process(File proc, String pid, File home, String name, String property) throws Exception {
        ProcessLaunchTest.process(proc, pid, "java", property + home.getCanonicalPath(),
            "-Dweblogic.Name=" + name, "weblogic.Server", "--password=PRIVATE_CANARY");
    }
    static void legacy(File root) throws Exception {
        File home = new File(root, "wlserver_10.3/server"), proc = new File(root, "proc");
        File jar = product(home, "10.3.6.0", 23553, false);
        process(proc, "98666901", home, "AdminServer", "-Dweblogic.home=");
        process(proc, "98666902", home, "ManagedServer", "-Dwls.home=");
        AssessmentScan scan = ProcessLaunchTest.scanner(); scan.discoverProcesses(proc);
        check("10.3.6.0".equals(server(scan, "AdminServer").get("version")), "Legacy 23,553-entry WebLogic version must be found from weblogic.home without -cp");
        check("10.3.6.0".equals(server(scan, "ManagedServer").get("version")), "wls.home must identify the same installation");
        check(scan.archivesInspected == 1, "Repeated installation uses the canonical archive cache");
        check(scan.bytesRead < 3 * 1024 * 1024, "Read only bounded directory and manifest, not classes");
        check(server(scan, "AdminServer").get("path") == null, "Installation directory is not a domain directory");
        check(String.valueOf(server(scan, "AdminServer").get("evidence")).contains(jar.getCanonicalPath()), "Version source retained");
        for (Object obj : scan.applications) check("10.3.6.0".equals(((Map)obj).get("frameworkVersion")), "Application framework version propagated");
        check(!Json.stringify(new ArrayList(scan.servers.values())).contains("PRIVATE_CANARY"), "Application arguments not retained");
        AssessmentScan disabled = ProcessLaunchTest.scanner(); disabled.settings.put("inspectArchives", Boolean.FALSE); disabled.discoverProcesses(proc);
        check(server(disabled, "AdminServer").get("version") == null && disabled.archivesInspected == 0, "Disabled archive inspection respected");
        AssessmentScan excluded = ProcessLaunchTest.scanner(); excluded.excluded.add(home.getCanonicalPath()); excluded.discoverProcesses(proc);
        check(server(excluded, "AdminServer").get("version") == null && excluded.archivesInspected == 0, "Explicit exclusions respected");
        AssessmentScan budget = ProcessLaunchTest.scanner(); budget.bytesRead = ArchiveMetadata.READ_LIMIT - 80000;
        Map result = budget.archiveMetadata.inspect(jar);
        check(result.get("serverVersion") == null && budget.partial && budget.bytesRead <= ArchiveMetadata.READ_LIMIT, "Shared byte budget enforced");
        AssessmentScan count = ProcessLaunchTest.scanner(); count.archivesInspected = ArchiveMetadata.ARCHIVE_LIMIT;
        check(count.archiveMetadata.inspect(jar).get("serverVersion") == null && count.bytesRead == 0, "Archive count enforced");
        AssessmentScan generic = ProcessLaunchTest.scanner(); File other = new File(home, "lib/application.jar");
        ProcessLaunchTest.write(other, bytes(jar));
        // Its ~2.3 MiB central directory is charged once, by the bounded preflight read.
        check("10.3.6.0".equals(generic.archiveMetadata.inspect(other).get("version")) && !generic.partial && generic.bytesRead < 6 * 1024 * 1024, "Large generic archives are inspected from the bounded central directory");
    }
    static byte[] bytes(File file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); InputStream in = new FileInputStream(file);
        try { byte[] b = new byte[4096]; for (int n; (n = in.read(b)) >= 0;) out.write(b, 0, n); }
        finally { in.close(); }
        return out.toByteArray();
    }
    static void modernAndUnknown(File root) throws Exception {
        File first = new File(root, "modern/server"), second = new File(root, "unknown/server"), third = new File(root, "another/server"), proc = new File(root, "proc");
        File jar = product(first, "14.1.2.0.0", 3, false); product(second, null, 3, false);
        product(third, "12.2.1.4.0", 3, false);
        ProcessLaunchTest.process(proc, "98666903", "java", "-classpath", jar.getCanonicalPath(), "-Dweblogic.Name=Modern", "weblogic.Server");
        process(proc, "98666904", second, "Unknown", "-Dweblogic.home=");
        process(proc, "98666905", third, "OtherVersion", "-Dwls.home=");
        AssessmentScan scan = ProcessLaunchTest.scanner(); scan.discoverProcesses(proc);
        check("14.1.2.0.0".equals(server(scan, "Modern").get("version")), "Modern explicit classpath retained");
        check(server(scan, "Unknown").get("version") == null, "Missing manifest version not borrowed from another installation");
        check("12.2.1.4.0".equals(server(scan, "OtherVersion").get("version")), "Two installed product versions remain associated with their own processes");
        ProcessLaunch unrelated = ProcessLaunch.parse(new String[]{"java", "-Dweblogic.home=/opt/other/server", "com.example.Main"}, null);
        check(unrelated.serverHome == null && unrelated.serverType == null, "WebLogic properties do not classify unrelated launchers");
        ProcessLaunch domain = ProcessLaunch.parse(new String[]{"java", "-Dwls.home=/opt/wls/server", "-Dweblogic.RootDirectory=/srv/domain", "weblogic.Server"}, null);
        check("/srv/domain".equals(domain.serverBase) && "/opt/wls/server".equals(domain.serverHome), "Domain and installation remain distinct");
    }
    static void rejected(File root) throws Exception {
        File duplicate = product(new File(root, "duplicate"), "10.3.6.0", 2, true);
        AssessmentScan dup = ProcessLaunchTest.scanner();
        check(dup.archiveMetadata.inspect(duplicate).get("serverVersion") == null && dup.partial, "Duplicate manifest rejected");
        File corrupt = product(new File(root, "crc"), "10.3.6.0", 2, false);
        byte[] data = bytes(corrupt);
        for (int i = 0; i < data.length - 46; i++) if (ArchiveMetadata.u32(data, i) == 0x02014b50L) { data[i + 16] ^= 1; break; }
        ProcessLaunchTest.write(corrupt, data); AssessmentScan crc = ProcessLaunchTest.scanner();
        check(crc.archiveMetadata.inspect(corrupt).get("serverVersion") == null && crc.partial, "Manifest CRC mismatch rejected");
        File bomb = new File(root, "bomb/lib/weblogic.jar"); bomb.getParentFile().mkdirs();
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(bomb));
        try { out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); out.write(new byte[65537]); out.closeEntry(); } finally { out.close(); }
        AssessmentScan size = ProcessLaunchTest.scanner();
        check(size.archiveMetadata.inspect(bomb).get("serverVersion") == null && size.partial, "Oversized manifest rejected");
        File missing = new File(root, "missing/lib/weblogic.jar"); missing.getParentFile().mkdirs();
        out = new ZipOutputStream(new FileOutputStream(missing)); out.close();
        check(ProcessLaunchTest.scanner().archiveMetadata.inspect(missing).get("serverVersion") == null, "Missing manifest remains unknown");
        File local = product(new File(root, "local"), "10.3.6.0", 2, false); data = bytes(local); data[0] = 0;
        ProcessLaunchTest.write(local, data); AssessmentScan header = ProcessLaunchTest.scanner();
        check(header.archiveMetadata.inspect(local).get("serverVersion") == null && header.partial, "Invalid local header rejected");
        File many = product(new File(root, "count"), "10.3.6.0", ArchiveMetadata.ENTRY_LIMIT + 1, false); AssessmentScan entries = ProcessLaunchTest.scanner();
        check(entries.archiveMetadata.inspect(many).get("serverVersion") == null && entries.partial, "Classic ZIP entry cap enforced; ZIP64 archives are rejected");
        File stored = new File(root, "stored/lib/weblogic.jar"); stored.getParentFile().mkdirs();
        byte[] content = "Manifest-Version: 1.0\nImplementation-Version: 12.2.1.4.0\n\n".getBytes("UTF-8");
        CRC32 checksum = new CRC32(); checksum.update(content); ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
        entry.setMethod(ZipEntry.STORED); entry.setSize(content.length); entry.setCompressedSize(content.length); entry.setCrc(checksum.getValue());
        out = new ZipOutputStream(new FileOutputStream(stored));
        try { out.putNextEntry(entry); out.write(content); out.closeEntry(); } finally { out.close(); }
        check("12.2.1.4.0".equals(ProcessLaunchTest.scanner().archiveMetadata.inspect(stored).get("serverVersion")), "Stored manifests supported");
    }
    public static void main(String[] args) throws Exception {
        File root = CollectorTest.temp("weblogic-version");
        try { legacy(new File(root, "legacy")); modernAndUnknown(new File(root, "other")); rejected(new File(root, "invalid")); }
        finally { CollectorTest.remove(root); }
        System.out.println("WEBLOGIC_VERSION_TESTS=PASS legacy23553 home classpath modern isolation cache bounds CRC privacy");
    }
}
