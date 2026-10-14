package assessment;

import java.io.*;
import java.util.zip.*;

/** Reads one manifest from a bounded classic ZIP; never opens or inflates application classes. */
final class WebLogicManifest {
    static final int ENTRY_LIMIT = 32768, CENTRAL_LIMIT = 4 * 1024 * 1024;
    static final String NAME = "META-INF/MANIFEST.MF";

    static byte[] read(ArchiveMetadata metadata, File file) throws IOException {
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            long length = input.length();
            if (length > ArchiveMetadata.FILE_LIMIT) throw invalid("file size");
            int tailSize = (int) Math.min(length, 65557L);
            byte[] tail = readAt(metadata, input, length - tailSize, tailSize);
            long[] directory = ArchiveMetadata.directory(tail, length, length - tailSize, ENTRY_LIMIT, CENTRAL_LIMIT);
            byte[] central = readAt(metadata, input, directory[1], (int) directory[0]);
            ArchiveMetadata.validateCentral(central, 0, central.length, (int) directory[2], ENTRY_LIMIT);
            int found = -1;
            for (int p = 0; p < central.length;) {
                metadata.ensureBudget();
                int nameLength = ArchiveMetadata.u16(central, p + 28);
                if (manifestName(central, p + 46, nameLength)) {
                    if (found >= 0) throw invalid("duplicate manifest");
                    found = p;
                }
                p += 46 + nameLength + ArchiveMetadata.u16(central, p + 30) + ArchiveMetadata.u16(central, p + 32);
            }
            if (found < 0) return null;
            int flags = ArchiveMetadata.u16(central, found + 8), method = ArchiveMetadata.u16(central, found + 10);
            long crc = ArchiveMetadata.u32(central, found + 16), compressed = ArchiveMetadata.u32(central, found + 20);
            long expanded = ArchiveMetadata.u32(central, found + 24), offset = ArchiveMetadata.u32(central, found + 42);
            if ((flags & ~0x080e) != 0 || (method != 0 && method != 8)) throw invalid("compression or encryption");
            if (compressed > 128 * 1024 || expanded > ArchiveMetadata.METADATA_LIMIT || method == 0 && compressed != expanded)
                throw invalid("manifest size");
            if (ArchiveMetadata.u16(central, found + 34) != 0 || offset + 30 > directory[1]) throw invalid("manifest offset");
            byte[] header = readAt(metadata, input, offset, 30);
            if (ArchiveMetadata.u32(header, 0) != 0x04034b50L || ArchiveMetadata.u16(header, 6) != flags || ArchiveMetadata.u16(header, 8) != method)
                throw invalid("local header");
            if ((flags & 8) == 0 && (ArchiveMetadata.u32(header, 14) != crc || ArchiveMetadata.u32(header, 18) != compressed || ArchiveMetadata.u32(header, 22) != expanded))
                throw invalid("local sizes or checksum");
            int nameLength = ArchiveMetadata.u16(header, 26), extraLength = ArchiveMetadata.u16(header, 28);
            long payloadOffset = offset + 30 + nameLength + extraLength;
            if (nameLength != NAME.length() || payloadOffset + compressed > directory[1]) throw invalid("local name or bounds");
            byte[] localName = readAt(metadata, input, offset + 30, nameLength);
            for (int i = 0; i < nameLength; i++) if (localName[i] != central[found + 46 + i]) throw invalid("manifest name mismatch");
            byte[] packed = readAt(metadata, input, payloadOffset, (int) compressed);
            byte[] result;
            if (method == 0) result = packed;
            else {
                Inflater inflater = new Inflater(true);
                InflaterInputStream stream = new InflaterInputStream(new ByteArrayInputStream(packed), inflater);
                try {
                    result = metadata.read(stream, ArchiveMetadata.METADATA_LIMIT);
                    if (!inflater.finished() || inflater.getBytesRead() != compressed) throw invalid("deflate stream");
                } finally { try { stream.close(); } finally { inflater.end(); } }
            }
            CRC32 actual = new CRC32(); actual.update(result);
            if (result.length != expanded || actual.getValue() != crc) throw invalid("manifest checksum");
            return result;
        } finally { input.close(); }
    }

    static byte[] readAt(ArchiveMetadata metadata, RandomAccessFile input, long offset, int size) throws IOException {
        metadata.charge(size);
        byte[] result = new byte[size]; input.seek(offset); input.readFully(result);
        return result;
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
    static IOException invalid(String reason) { return new ArchiveMetadata.Limit("WebLogic manifest rejected: " + reason); }
}
