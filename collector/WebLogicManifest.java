package assessment;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Reads one manifest from a bounded classic ZIP; never opens or inflates application classes. */
final class WebLogicManifest {
    static final String NAME = "META-INF/MANIFEST.MF";

    /** Used for weblogic.jar and legacy rt.jar, whose many entries need no enumeration to find the manifest. */
    static byte[] read(ArchiveMetadata metadata, File file) throws IOException { return read(metadata, file, ArchiveMetadata.METADATA_LIMIT); }
    static byte[] read(ArchiveMetadata metadata, File file, int limit) throws IOException { return read(metadata, file, limit, null, null); }
    static byte[] read(ArchiveMetadata metadata, File file, int limit, Set<String> wanted, Set<String> foundClasses) throws IOException {
        if (file.length() > ArchiveMetadata.FILE_LIMIT) throw invalid("file size");
        byte[] central = metadata.central(file, ArchiveMetadata.ENTRY_LIMIT, ArchiveMetadata.CENTRAL_LIMIT);
        long centralStart = metadata.centralStart;
        int found = -1;
        for (int p = 0; p < central.length;) {
            metadata.ensureBudget();
            int nameLength = ArchiveMetadata.u16(central, p + 28);
            if (wanted != null) {
                String name = new String(central, p + 46, nameLength, "UTF-8");
                if (wanted.contains(name) && !foundClasses.add(name)) throw invalid("duplicate driver class");
            }
            if (manifestName(central, p + 46, nameLength)) {
                if (found >= 0) throw invalid("duplicate manifest");
                found = p;
            }
            p += 46 + nameLength + ArchiveMetadata.u16(central, p + 30) + ArchiveMetadata.u16(central, p + 32);
        }
        if (found < 0) return null;
        // Signed manifests may contain a large per-entry digest section. Only main attributes are needed.
        if (wanted != null && ArchiveMetadata.u32(central, found + 24) > limit) {
            ZipFile zip = new ZipFile(file);
            try { InputStream in = zip.getInputStream(zip.getEntry(NAME)); try { return JdbcDriverResolver.manifestHead(metadata, in); } finally { in.close(); } } finally { zip.close(); }
        }
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try { return metadata.entry(input, central, found, 0, centralStart, limit); }
        finally { input.close(); }
    }

    static boolean manifestName(byte[] bytes, int offset, int length) {
        if (length != NAME.length()) return false;
        for (int i = 0; i < length; i++) {
            int ch = bytes[offset + i] & 255;
            if (ch >= 'a' && ch <= 'z') ch -= 'a' - 'A';
            if (ch != NAME.charAt(i)) return false;
        }
        return true;
    }
    static IOException invalid(String reason) { return new ArchiveMetadata.Limit("Archive manifest rejected: " + reason); }
}
