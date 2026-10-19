package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;

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
        try {
            if (!file.isFile()) throw new IOException();
            // rt.jar has tens of thousands of entries; read only its central directory and manifest.
            byte[] content = WebLogicManifest.read(metadata, file, 32768);
            if (content == null) return result;
            Attributes attrs = new Manifest(new ByteArrayInputStream(content)).getMainAttributes();
            if (!"Java Runtime Environment".equals(attrs.getValue("Implementation-Title"))) return result;
            String version = attrs.getValue("Implementation-Version"), vendor = attrs.getValue("Implementation-Vendor");
            if (version == null || version.length() > 80 || !version.matches("[0-9]+(\\.[0-9]+){1,3}(_[0-9]+)?([-+][A-Za-z0-9._+\\-]+)?")) return result;
            result.put("version", version);
            if (vendor != null && vendor.length() <= 128 && vendor.matches("[A-Za-z0-9 .(),+&/_\\-]+")) result.put("vendor", vendor);
            result.put("runtimeMetadataSource", file.getPath() + "!/META-INF/MANIFEST.MF");
        } catch (Exception e) {
            scan.warn("Legacy Java runtime manifest could not be read safely within metadata limits: " + file.getPath());
        }
        return result;
    }
}
