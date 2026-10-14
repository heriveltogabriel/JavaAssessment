package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Runtime identity metadata only: no class loading or execution of discovered Java. */
final class LegacyJavaMetadata {
    static Map read(AssessmentScan scan, File home) {
        Map result = new LinkedHashMap();
        File selected = new File(home, "jre/lib/rt.jar");
        if (!selected.exists()) selected = new File(home, "lib/rt.jar");
        if (!selected.exists()) return result;
        File file = scan.linkedPath(selected);
        if (file == null || !scan.visitLinked(file)) return result;
        if (!AssessmentScan.under(file.getPath(), home.getPath())) { scan.warn("Legacy Java runtime metadata resolves outside the Java home"); return result; }
        ArchiveMetadata metadata = scan.archiveMetadata;
        if (!metadata.claimArchive(file.getPath())) return result;
        ZipFile zip = null;
        try {
            if (!file.isFile() || file.length() > ArchiveMetadata.FILE_LIMIT) throw new IOException();
            // rt.jar legitimately has more entries than an application archive.
            metadata.charge(metadata.preflight(file, 32768, 3 * 1024 * 1024));
            zip = new ZipFile(file);
            ZipEntry manifest = null;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                metadata.ensureBudget(); ZipEntry entry = entries.nextElement();
                if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    if (manifest != null) throw new IOException();
                    manifest = entry;
                }
            }
            if (manifest == null) return result;
            if (manifest.getSize() > 32768) throw new IOException();
            InputStream input = zip.getInputStream(manifest); byte[] content;
            try { content = metadata.read(input, 32768); } finally { input.close(); }
            Attributes attrs = new Manifest(new ByteArrayInputStream(content)).getMainAttributes();
            if (!"Java Runtime Environment".equals(attrs.getValue("Implementation-Title"))) return result;
            String version = attrs.getValue("Implementation-Version"), vendor = attrs.getValue("Implementation-Vendor");
            if (version == null || version.length() > 80 || !version.matches("[0-9]+(\\.[0-9]+){1,3}(_[0-9]+)?([-+][A-Za-z0-9._+\\-]+)?")) return result;
            result.put("version", version);
            if (vendor != null && vendor.length() <= 128 && vendor.matches("[A-Za-z0-9 .(),+&/_\\-]+")) result.put("vendor", vendor);
            result.put("runtimeMetadataSource", file.getPath() + "!/META-INF/MANIFEST.MF");
        } catch (Exception e) {
            scan.warn("Legacy Java runtime manifest could not be read safely within metadata limits: " + file.getPath());
        } finally { if (zip != null) try { zip.close(); } catch (IOException ignored) { } }
        return result;
    }
}
