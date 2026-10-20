package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;

/** Reads bounded archive metadata without extracting files or loading application classes. */
public final class ArchiveMetadata {
    static final long READ_LIMIT = 32L * 1024 * 1024;
    static final long FILE_LIMIT = 1024L * 1024 * 1024; // File size is independent from the bounded metadata/central-directory budget.
    // 65535 is the classic ZIP maximum; only the bounded central directory is read, never every entry body.
    static final int METADATA_LIMIT = 65536, ENTRY_LIMIT = 65535, CENTRAL_LIMIT = 8 * 1024 * 1024;
    // Outer archives (including rt.jar and classpath archives) and nested libraries have separate count budgets.
    static final int ARCHIVE_LIMIT = 200, NESTED_LIMIT = 2000;
    // A compressed nested JAR must be inflated to find its metadata; cap that work per nested archive.
    static final int NESTED_INFLATE_LIMIT = 4 * 1024 * 1024;
    static final Pattern BOOT_CORE = Pattern.compile("BOOT-INF/lib/spring-boot-([0-9][A-Za-z0-9._+\\-]*)\\.jar");
    static final Pattern FILE_VERSION = Pattern.compile("^(?:ojdbc[0-9]*|classes12|derby(?:client|net|shared|tools|run)?|mysql-connector-(?:java|j)|postgresql|mssql-jdbc|sqljdbc[0-9]*|jtds|db2jcc[0-9]*|jcc|mariadb-java-client|h2|hsqldb|jconn[0-9]*|terajdbc[0-9]*|ngdbc|snowflake-jdbc|sqlite-jdbc|jaybird(?:-full|-jdk[0-9]+)?|ucp[0-9]*|hikaricp(?:-java[0-9]+)?|c3p0|commons-dbcp2?|tomcat-jdbc|tomcat-embed-core|jetty-server|undertow-core)[-_]([0-9][A-Za-z0-9._+\\-]*)\\.jar$", Pattern.CASE_INSENSITIVE);
    // Drivers whose filename alone is specific enough; generic names such as jdbc-4.50.jar need Maven or SPI evidence.
    static final Pattern JDBC_NAME = Pattern.compile("(?:jcc-[0-9].*|jconn[0-9]*(?:-[0-9].*)?|ifxjdbc.*|terajdbc[0-9]*(?:-[0-9].*)?|ngdbc(?:-[0-9].*)?|snowflake-jdbc-[0-9].*|sqlite-jdbc-[0-9].*|jaybird(?:-full|-jdk[0-9]+)?(?:-[0-9].*)?)\\.jar");
    static final Pattern POOL_NAME = Pattern.compile("(?:ucp[0-9]*|hikaricp(?:-java[0-9]+)?|c3p0|commons-dbcp2?|tomcat-jdbc)(?:-[0-9].*)?\\.jar");
    final AssessmentScan scan;
    final Map<String, Map> cache = new LinkedHashMap<String, Map>();
    int libraryRows;
    // Configuration is retained before large nested dependencies can consume the read budget.
    // At most 2 MiB of transient raw bytes, never included in inventory results.
    final Map<String, ConfigurationArchive> configurations = new LinkedHashMap<String, ConfigurationArchive>();
    static final class ConfigurationArchive {
        final Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        final Set<String> duplicates = new HashSet<String>();
        final Set<String> classEntries = new HashSet<String>();
        String prefix; boolean partial; int bytes;
    }
    ConfigurationArchive configuration(File file) throws IOException { return configurations.get(file.getCanonicalPath()); }
    ConfigurationArchive classpathConfiguration(File file) throws IOException {
        String key = "classpath:" + file.getCanonicalPath();
        if (configurations.containsKey(key)) return configurations.get(key);
        if (!scan.visitLinked(file) || !file.isFile() || file.length() > FILE_LIMIT || !claimArchive(file.getPath())) throw new Limit("Classpath archive inspection unavailable");
        preflight(file); // validates and charges the bounded central directory read
        ConfigurationArchive result = new ConfigurationArchive(); result.prefix = "";
        ZipFile archive = new ZipFile(file);
        try {
            List<ZipEntry> candidates = new ArrayList<ZipEntry>(); Set<String> names = new HashSet<String>();
            Enumeration<? extends ZipEntry> entries = archive.entries(); int count = 0;
            while (entries.hasMoreElements()) {
                if (!scan.available() || ++count > ENTRY_LIMIT) throw new Limit("Classpath archive entry limit reached");
                ZipEntry entry = entries.nextElement(); String name = entry.getName();
                if (entry.isDirectory() || !safeName(name)) continue;
                if (name.endsWith(".class")) result.classEntries.add(name);
                if (name.endsWith(".properties") || name.endsWith(".yaml") || name.endsWith(".yml") || name.equals("META-INF/MANIFEST.MF")) {
                    if (!names.add(name)) result.duplicates.add(name);
                    if (candidates.size() < 64) candidates.add(entry); else result.partial = true;
                }
            }
            Collections.sort(candidates, new Comparator<ZipEntry>() {
                public int compare(ZipEntry a, ZipEntry b) {
                    boolean first = base(a.getName()).startsWith("application"), second = base(b.getName()).startsWith("application");
                    return first == second ? a.getName().compareTo(b.getName()) : first ? -1 : 1;
                }
            });
            for (ZipEntry entry : candidates) {
                if (result.duplicates.contains(entry.getName())) continue;
                boolean mf = isManifest(entry.getName());
                if (result.entries.size() >= 32 || entry.getSize() < 0 || !mf && (entry.getSize() > METADATA_LIMIT || entry.getSize() > 262144 - result.bytes)) { result.partial = true; continue; }
                InputStream input = archive.getInputStream(entry); byte[] content;
                try { content = mf ? JdbcDriverResolver.manifestHead(this, input) : read(input, Math.min(METADATA_LIMIT, 262144 - result.bytes)); } finally { input.close(); }
                result.bytes += content.length; result.entries.put(entry.getName(), content);
                if (entry.getName().equals("META-INF/MANIFEST.MF") && new Manifest(new ByteArrayInputStream(content)).getMainAttributes().getValue("Class-Path") != null) result.partial = true;
            }
        } finally { archive.close(); }
        if (configurations.size() >= 8) configurations.remove(configurations.keySet().iterator().next());
        configurations.put(key, result); return result;
    }



    public ArchiveMetadata(AssessmentScan scan) { this.scan = scan; }

    public static boolean isKnownCandidate(String filename) {
        String name = base(filename).toLowerCase(Locale.ENGLISH);
        return name.matches("spring-(?:boot|core)-[0-9][A-Za-z0-9._+\\-]*\\.jar") || isJdbc(name) || isPool(name) || genericJdbc(name) || isDerby(name) || isTomcat(name) || name.equals("weblogic.jar")
            || name.equals("jboss-modules.jar") || name.startsWith("jboss-as-server") || name.startsWith("jboss-as-version")
            || name.startsWith("jboss-as-product") || name.startsWith("wildfly-server") || name.startsWith("wildfly-version")
            || name.startsWith("wildfly-product") || name.startsWith("jetty-server-") || name.startsWith("undertow-core-");
    }

    public Map inspect(File file) {
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        if (!(name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear"))) return new LinkedHashMap();
        String path;
        try { path = file.getCanonicalPath(); }
        catch (IOException e) { scan.warn("Cannot resolve archive metadata path"); return new LinkedHashMap(); }
        if (cache.containsKey(path)) return cache.get(path);
        Document document = new Document(file.getName(), path);
        if (Boolean.TRUE.equals(scan.settings.get("inspectArchives")) && claimArchive(path)) {
            ZipFile archive = null;
            try {
                if (!file.isFile() || file.length() > FILE_LIMIT) throw new Limit("Large or unavailable archive skipped: " + path);
                if (name.equals("weblogic.jar")) {
                    // Legacy WebLogic contains >23,000 entries; read only its identity manifest.
                    byte[] manifest = WebLogicManifest.read(this, file);
                    if (manifest != null) accept(document, "META-INF/MANIFEST.MF", manifest);
                } else {
                    byte[] central = central(file, ENTRY_LIMIT, CENTRAL_LIMIT); long start = centralStart;
                    if (!scan.available()) throw new Limit("Assessment deadline reached during archive discovery");
                    archive = new ZipFile(file);
                    readOuter(document, archive, file, central, start);
                }
            } catch (Exception e) { report(e, path); }
            finally { if (archive != null) try { archive.close(); } catch (IOException ignored) { } }
        }
        Map result = result(document);
        if (cache.size() < 512) cache.put(path, result);
        else scan.note("Archive metadata cache limit reached; completed archive reads remain bounded");
        return result;
    }

    int nestedClaims;
    boolean claimArchive(String path) {
        // scan.archivesInspected stays the total reported count; nested claims are excluded from the outer budget.
        if (!scan.available()) return false;
        if (scan.archivesInspected - nestedClaims >= ARCHIVE_LIMIT) { scan.warn("Archive inspection count limit reached (" + ARCHIVE_LIMIT + " archives per collection)"); return false; }
        if (scan.bytesRead >= READ_LIMIT) { scan.warn("Metadata read budget of 32 MiB reached"); return false; }
        scan.archivesInspected++;
        return true;
    }
    boolean claimNested(String path) {
        if (!scan.available()) return false;
        if (nestedClaims >= NESTED_LIMIT) { scan.warn("Nested library inspection count limit reached (" + NESTED_LIMIT + " per collection)"); return false; }
        if (scan.bytesRead >= READ_LIMIT) { scan.warn("Metadata read budget of 32 MiB reached"); return false; }
        scan.archivesInspected++; nestedClaims++;
        return true;
    }

    void readOuter(Document doc, ZipFile archive, File file, byte[] central, long centralStart) throws IOException {
        List<ZipEntry> nested = new ArrayList<ZipEntry>(), metadataEntries = new ArrayList<ZipEntry>();
        Map<String, ZipEntry> springEntries = new LinkedHashMap<String, ZipEntry>();
        Set<String> duplicateSpringEntries = new HashSet<String>(); boolean profileConfiguration = false;
        boolean bootClasses = false, bootLoader = false, springApplication = false; String layoutBootVersion = null;
        List<ZipEntry> configurationEntries = new ArrayList<ZipEntry>();
        Set<String> configurationNames = new HashSet<String>(), duplicateConfigurations = new HashSet<String>();
        boolean configurationTruncated = false;
        Enumeration<? extends ZipEntry> entries = archive.entries(); int count = 0;
        while (entries.hasMoreElements()) {
            if (!scan.available()) throw new Limit("Assessment deadline reached during archive discovery");
            if (++count > ENTRY_LIMIT) throw new Limit("Archive entry count limit reached");
            ZipEntry entry = entries.nextElement(); String name = entry.getName();
            if (entry.isDirectory() || !safeName(name)) continue;
            if (metadata(name)) metadataEntries.add(entry);
            if (name.startsWith("BOOT-INF/classes/")) bootClasses = true;
            if (name.equals("org/springframework/boot/loader/JarLauncher.class") || name.equals("org/springframework/boot/loader/launch/JarLauncher.class")) bootLoader = true;
            if (name.equals("org/springframework/boot/SpringApplication.class")) springApplication = true;
            Matcher bootCore = BOOT_CORE.matcher(name);
            if (bootCore.matches()) layoutBootVersion = bootCore.group(1);
            if ((name.startsWith("BOOT-INF/classes/") || name.startsWith("WEB-INF/classes/"))
                && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"))) {
                if (!configurationNames.add(name)) duplicateConfigurations.add(name);
                if (configurationEntries.size() < 64) configurationEntries.add(entry);
                else configurationTruncated = true;
            }
            if (Arrays.asList(SpringDataSource.BASE_FILES).contains(name)) {
                if (springEntries.containsKey(name)) duplicateSpringEntries.add(name);
                springEntries.put(name, entry);
            }
            if (SpringDataSource.profileFile(name)) profileConfiguration = true;
            if ((name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/")) && isKnownCandidate(name)) {
                if (nested.size() < 1000) nested.add(entry); else scan.warn("Nested library candidate limit reached");
            }
        }
        // The manifest and base datasource settings precede dependencies and
        // arbitrary Maven metadata, using this same bounded ZipFile opening.
        for (ZipEntry entry : metadataEntries) if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) readOuterMetadata(doc, archive, entry);
        doc.bootLibrary = springApplication && "Spring Boot".equals(doc.title);
        doc.bootLayout = bootClasses && bootLoader && layoutBootVersion != null;
        if (doc.boot == null && doc.bootLayout) doc.boot = layoutBootVersion;
        boolean boot = bootMain(doc.main) || doc.boot != null && doc.start != null || doc.bootLayout;
        if (boot) {
            ConfigurationArchive configuration = new ConfigurationArchive();
            configuration.partial = configurationTruncated;
            configuration.duplicates.addAll(duplicateConfigurations);
            if (configurationTruncated) scan.note("Packaged Spring configuration candidate limit reached: " + doc.path);
            configuration.prefix = bootClasses ? "BOOT-INF/classes/" : "WEB-INF/classes/";
            Collections.sort(configurationEntries, new Comparator<ZipEntry>() {
                public int compare(ZipEntry a, ZipEntry b) {
                    boolean first = base(a.getName()).startsWith("application"), second = base(b.getName()).startsWith("application");
                    return first == second ? a.getName().compareTo(b.getName()) : first ? -1 : 1;
                }
            });
            for (ZipEntry entry : configurationEntries) {
                String name = entry.getName();
                if (!name.startsWith(configuration.prefix)) continue;
                if (configuration.entries.containsKey(name)) { configuration.duplicates.add(name); continue; }
                if (configuration.entries.size() >= 32 || entry.getSize() < 0 || entry.getSize() > METADATA_LIMIT || entry.getSize() > 262144 - configuration.bytes) { configuration.partial = true; continue; }
                try {
                    InputStream input = archive.getInputStream(entry); byte[] content;
                    try { content = read(input, Math.min(METADATA_LIMIT, 262144 - configuration.bytes)); } finally { input.close(); }
                    configuration.bytes += content.length; configuration.entries.put(name, content);
                } catch (Exception e) { configuration.partial = true; report(e, doc.path + "!/" + name); }
            }
            if (configurations.size() >= 8) configurations.remove(configurations.keySet().iterator().next());
            configurations.put(doc.path, configuration);
            // Profile resolution is performed separately for each live process.

            for (String name : SpringDataSource.BASE_FILES) if (springEntries.containsKey(name)) {
                if (duplicateSpringEntries.contains(name)) { scan.warn("Duplicate base Spring configuration entries were skipped: " + doc.path); break; }
                ZipEntry entry = springEntries.get(name);
                try {
                    if (entry.getSize() > METADATA_LIMIT) throw new Limit("Spring configuration entry exceeds 64 KiB");
                    byte[] content = configuration.entries.get(name);
                    if (content == null || configuration.duplicates.contains(name)) continue;
                    SpringDataSource.Parsed parsed = SpringDataSource.parse(name, content);
                    if (parsed.notice != null) scan.note(parsed.notice + ": " + doc.path);
                    if (!parsed.fields.isEmpty()) {
                        doc.springDataSource = parsed.fields;
                        doc.springDataSource.put("configurationSource", doc.path + "!/" + name);
                    }
                } catch (Exception e) { report(e, doc.path + "!/" + name); }
                break;
            }
        }
        for (ZipEntry entry : metadataEntries) if (!entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) readOuterMetadata(doc, archive, entry);
        Map<String, Integer> records = nested.isEmpty() ? new HashMap<String, Integer>() : records(central);
        for (ZipEntry entry : nested) {
            String path = doc.path + "!/" + entry.getName();
            Document child = new Document(base(entry.getName()), path);
            if (claimNested(path)) {
                try {
                    Integer record = records.get(entry.getName());
                    if (record == null) throw new Limit("Nested archive entry is duplicated or missing from the central directory");
                    if (u16(central, record.intValue() + 10) == ZipEntry.STORED) readStored(child, file, central, centralStart, record.intValue());
                    else {
                        InputStream input = archive.getInputStream(entry);
                        try { readNested(child, input); } finally { input.close(); }
                    }
                } catch (Exception e) { report(e, path); }
            }
            List libraries = libraries(child);
            for (Object library : libraries) {
                Map row = (Map) library;
                row.put("evidence", row.get("evidence") + "; packaged inside " + doc.path + "; runtime loading and datasource usage not verified");
                doc.children.add(row);
            }
        }
    }

    void readOuterMetadata(Document doc, ZipFile archive, ZipEntry entry) {
        try {
            boolean mf = isManifest(entry.getName());
            // Signed JARs carry one digest per class after the main section; only the main section is needed.
            if (!mf && entry.getSize() > METADATA_LIMIT) throw new Limit("Archive metadata entry exceeds 64 KiB");
            InputStream input = archive.getInputStream(entry);
            try { accept(doc, entry.getName(), mf ? JdbcDriverResolver.manifestHead(this, input) : read(input, METADATA_LIMIT)); }
            finally { input.close(); }
        } catch (Exception e) { report(e, doc.path + "!/" + entry.getName()); }
    }

    static boolean isManifest(String name) { return name.equalsIgnoreCase("META-INF/MANIFEST.MF"); }
    /** Streams a compressed nested JAR: nothing is buffered whole, and reading stops once its identity is known. */
    void readNested(Document doc, InputStream packed) throws IOException {
        ZipInputStream input = new ZipInputStream(packed);
        long limit = scan.bytesRead + NESTED_INFLATE_LIMIT;
        try {
            ZipEntry entry; int count = 0;
            while ((entry = input.getNextEntry()) != null) {
                if (!scan.available()) throw new Limit("Assessment deadline reached during nested archive discovery");
                if (++count > ENTRY_LIMIT) throw new Limit("Nested archive entry count limit reached");
                String name = entry.getName();
                if (!entry.isDirectory() && safeName(name) && metadata(name)) {
                    boolean mf = isManifest(name);
                    if (!mf && entry.getSize() > METADATA_LIMIT) throw new Limit("Nested archive metadata entry exceeds 64 KiB");
                    accept(doc, name, mf ? JdbcDriverResolver.manifestHead(this, input) : read(input, METADATA_LIMIT));
                    if (mf) drain(input, limit);
                    if (identified(doc)) return;
                } else {
                    // Fully consume with the same budget. getNextEntry/closeEntry must never drain unchecked data.
                    drain(input, limit);
                }
            }
        } finally { input.close(); }
    }
    // Known dependency + its version is enough: do not read application classes to seek redundant metadata.
    static boolean identified(Document doc) {
        return doc.jdbcPomVersion != null || doc.version != null && isKnownCandidate(doc.filename) && !genericJdbc(doc.filename.toLowerCase(Locale.ENGLISH));
    }

    /** Central directory positions of nested libraries; duplicated names are left out so they are never guessed. */
    static Map<String, Integer> records(byte[] central) throws IOException {
        Map<String, Integer> result = new HashMap<String, Integer>(); Set<String> duplicates = new HashSet<String>();
        for (int p = 0; p < central.length; p += 46 + u16(central, p + 28) + u16(central, p + 30) + u16(central, p + 32)) {
            String name = new String(central, p + 46, u16(central, p + 28), "UTF-8");
            if (!(name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/")) || !name.endsWith(".jar")) continue;
            if (duplicates.contains(name) || result.put(name, Integer.valueOf(p)) != null) { duplicates.add(name); result.remove(name); }
        }
        return result;
    }

    /**
     * Spring Boot keeps nested JARs uncompressed. Locate the nested bytes inside the outer file and read only
     * their end record, central directory and allowlisted metadata entries; class bodies are never read.
     */
    void readStored(Document doc, File file, byte[] outer, long outerCentral, int record) throws IOException {
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            long[] span = local(input, outer, record, 0, outerCentral);
            if (span[2] != 0 || span[1] != u32(outer, record + 24)) throw new Limit("Nested archive is not stored uncompressed");
            long start = span[0];
            byte[] central = central(input, start, span[1], ENTRY_LIMIT, CENTRAL_LIMIT); long nestedCentral = centralStart;
            int candidates = 0;
            for (int p = 0; p < central.length; p += 46 + u16(central, p + 28) + u16(central, p + 30) + u16(central, p + 32)) {
                ensureBudget();
                String name = new String(central, p + 46, u16(central, p + 28), "UTF-8");
                if (name.endsWith("/") || !safeName(name) || !metadata(name)) continue;
                if (++candidates > 32) throw new Limit("Nested archive metadata entry limit reached");
                accept(doc, name, entry(input, central, p, start, nestedCentral, METADATA_LIMIT));
                if (identified(doc)) return;
            }
        } finally { input.close(); }
    }

    /** Validates the local header of central record p; returns {absolute payload offset, compressed size, method}. */
    long[] local(RandomAccessFile input, byte[] central, int p, long base, long centralStart) throws IOException {
        int flags = u16(central, p + 8), method = u16(central, p + 10), nameLength = u16(central, p + 28);
        long compressed = u32(central, p + 20), offset = u32(central, p + 42);
        if ((flags & ~0x080e) != 0 || method != 0 && method != 8) throw new Limit("Archive entry rejected: compression or encryption");
        if (u16(central, p + 34) != 0 || offset + 30 > centralStart) throw new Limit("Archive entry rejected: offset");
        byte[] header = readAt(input, base + offset, 30);
        if (u32(header, 0) != 0x04034b50L || u16(header, 6) != flags || u16(header, 8) != method) throw new Limit("Archive entry rejected: local header");
        if ((flags & 8) == 0 && (u32(header, 14) != u32(central, p + 16) || u32(header, 18) != compressed || u32(header, 22) != u32(central, p + 24)))
            throw new Limit("Archive entry rejected: local sizes or checksum");
        long payload = offset + 30 + nameLength + u16(header, 28);
        if (u16(header, 26) != nameLength || payload + compressed > centralStart) throw new Limit("Archive entry rejected: local name or bounds");
        byte[] name = readAt(input, base + offset + 30, nameLength);
        for (int i = 0; i < nameLength; i++) if (name[i] != central[p + 46 + i]) throw new Limit("Archive entry rejected: name mismatch");
        return new long[] {base + payload, compressed, method};
    }

    /** Reads one small entry by random access, inflating within limit and verifying size and CRC. */
    byte[] entry(RandomAccessFile input, byte[] central, int p, long base, long centralStart, int limit) throws IOException {
        long crc = u32(central, p + 16), compressed = u32(central, p + 20), expanded = u32(central, p + 24);
        if (expanded > limit || compressed > 2L * limit + 64 || u16(central, p + 10) == 0 && compressed != expanded) throw new Limit("Archive entry rejected: size");
        long[] span = local(input, central, p, base, centralStart);
        byte[] packed = readAt(input, span[0], (int) compressed), result;
        if (span[2] == 0) result = packed;
        else {
            Inflater inflater = new Inflater(true);
            InflaterInputStream stream = new InflaterInputStream(new ByteArrayInputStream(packed), inflater);
            try {
                result = read(stream, limit);
                if (!inflater.finished() || inflater.getBytesRead() != compressed) throw new Limit("Archive entry rejected: deflate stream");
            } finally { try { stream.close(); } finally { inflater.end(); } }
        }
        CRC32 actual = new CRC32(); actual.update(result);
        if (result.length != expanded || actual.getValue() != crc) throw new Limit("Archive entry rejected: checksum");
        return result;
    }
    byte[] readAt(RandomAccessFile input, long offset, int size) throws IOException {
        charge(size);
        byte[] result = new byte[size]; input.seek(offset); input.readFully(result);
        return result;
    }

    byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 4096)); byte[] buffer = new byte[4096];
        while (true) {
            ensureBudget();
            int allowed = (int) Math.min(buffer.length, READ_LIMIT - scan.bytesRead);
            allowed = Math.min(allowed, limit - output.size() + 1);
            int count = input.read(buffer, 0, Math.max(1, allowed));
            if (count < 0) return output.toByteArray();
            scan.bytesRead += count;
            if (output.size() + count > limit) throw new Limit("Archive entry exceeds bounded read limit");
            output.write(buffer, 0, count);
        }
    }

    void drain(InputStream input, long limit) throws IOException {
        byte[] buffer = new byte[4096];
        while (true) {
            ensureBudget();
            if (scan.bytesRead >= limit) throw new Limit("Compressed nested archive exceeds 4 MiB inspection limit");
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, Math.min(READ_LIMIT, limit) - scan.bytesRead));
            if (count < 0) return;
            scan.bytesRead += count;
        }
    }

    void ensureBudget() throws IOException {
        if (!scan.available()) throw new Limit("Assessment deadline reached during archive discovery");
        if (scan.bytesRead >= READ_LIMIT) throw new Limit("Metadata read budget of 32 MiB reached");
    }
    void charge(long count) throws IOException {
        ensureBudget();
        if (count < 0 || count > READ_LIMIT - scan.bytesRead) throw new Limit("Metadata read budget of 32 MiB reached");
        scan.bytesRead += count;
    }
    void report(Exception e, String path) { scan.warn(e instanceof Limit ? e.getMessage() + ": " + path : "Archive metadata could not be read: " + path); }

    long preflight(File file) throws IOException {
        return preflight(file, ENTRY_LIMIT, CENTRAL_LIMIT);
    }
    long preflight(File file, int entryLimit, int centralLimit) throws IOException {
        return central(file, entryLimit, centralLimit).length;
    }
    long centralStart; // offset of the central directory returned by the last central() call
    byte[] central(File file, int entryLimit, int centralLimit) throws IOException {
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try { return central(input, 0, input.length(), entryLimit, centralLimit); }
        finally { input.close(); }
    }
    /** Validated central directory of the ZIP occupying [base, base + length) of input; offsets stay relative to base. */
    byte[] central(RandomAccessFile input, long base, long length, int entryLimit, int centralLimit) throws IOException {
        int size = (int) Math.min(length, 42L);
        byte[] tail = readAt(input, base + length - size, size);
        if (size < 22 || u32(tail,size-22) != 0x06054b50L || u16(tail,size-2) != 0) {
            size = (int) Math.min(length, 65557L);
            tail = readAt(input, base + length - size, size);
        }
        long[] directory = directory(tail, length, length - size, entryLimit, centralLimit);
        byte[] central = readAt(input, base + directory[1], (int) directory[0]);
        validateCentral(central, 0, central.length, (int) directory[2], entryLimit);
        centralStart = directory[1];
        return central;
    }
    static long[] directory(byte[] bytes, long totalLength, long offset, int entryLimit, int centralLimit) throws IOException {
        for (int i = bytes.length - 22; i >= 0; i--) {
            if (u32(bytes, i) != 0x06054b50L || i + 22 + u16(bytes, i + 20) != bytes.length) continue;
            int entries = u16(bytes, i + 10); long central = u32(bytes, i + 12), position = u32(bytes, i + 16);
            if (u16(bytes, i + 4) != 0 || u16(bytes, i + 6) != 0 || u16(bytes, i + 8) != entries)
                throw new Limit("Multidisk archives are not inspected");
            if (entries == 65535 || central == 0xffffffffL || position == 0xffffffffL || i >= 20 && u32(bytes, i - 20) == 0x07064b50L)
                throw new Limit("ZIP64 archives are not inspected");
            if (entries > entryLimit || central > centralLimit) throw new Limit("Archive central directory exceeds bounded inspection limits");
            if (position + central > offset + i || position > totalLength || central > totalLength)
                throw new Limit("Invalid archive central directory");
            return new long[] {central, position, entries};
        }
        throw new Limit("Archive end record is missing or unsupported");
    }
    static void validateCentral(byte[] content, int start, int size, int expected, int entryLimit) throws IOException {
        int end = start + size, count = 0;
        if (start < 0 || end < start || end > content.length) throw new Limit("Invalid archive central directory bounds");
        while (start < end) {
            if (end - start < 46 || u32(content,start) != 0x02014b50L) throw new Limit("Invalid archive central directory entry");
            if (++count > entryLimit) throw new Limit("Archive entry count limit reached");
            if (u32(content,start + 20) == 0xffffffffL || u32(content,start + 24) == 0xffffffffL || u32(content,start + 42) == 0xffffffffL)
                throw new Limit("ZIP64 archive entries are not inspected");
            int length = 46 + u16(content,start + 28) + u16(content,start + 30) + u16(content,start + 32);
            if (length > end - start) throw new Limit("Invalid archive central directory entry length");
            start += length;
        }
        if (count != expected) throw new Limit("Archive central directory entry count mismatch");
    }
    static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    static long u32(byte[] b, int p) { return (long) u16(b,p) | ((long) u16(b,p + 2) << 16); }

    static boolean metadata(String name) {
        return name.equalsIgnoreCase("META-INF/MANIFEST.MF") || name.equals("META-INF/services/java.sql.Driver")
            || name.equals("META-INF/build-info.properties") || name.equals("BOOT-INF/classes/META-INF/build-info.properties") || name.equals("WEB-INF/classes/META-INF/build-info.properties")
            || name.equals("org/apache/catalina/util/ServerInfo.properties")
            || name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties");
    }
    static boolean safeName(String name) { return !name.startsWith("/") && name.indexOf('\\') < 0 && !name.equals("..") && !name.startsWith("../") && name.indexOf("/../") < 0; }
    static String base(String path) { int at = path.lastIndexOf('/'); return at >= 0 ? path.substring(at + 1) : path; }
    static String value(String text) { String result = AssessmentScan.clean(text); return result == null || result.length() == 0 ? null : result; }
    static String first(String a, String b) { return a != null ? a : b; }
    static boolean isDerby(String name) { return name.startsWith("derby") && name.endsWith(".jar"); }
    static boolean isTomcat(String name) { return name.equals("catalina.jar") || name.startsWith("tomcat-embed-core") && name.endsWith(".jar"); }
    static boolean isJdbc(String name) {
        return name.endsWith(".jar") && (name.startsWith("ojdbc") || name.equals("classes12.jar") || name.equals("derby.jar")
            || name.equals("derbyclient.jar") || name.startsWith("derby-") || name.startsWith("derbyclient-")
            || name.startsWith("mysql-connector") || name.startsWith("postgresql") || name.startsWith("mssql-jdbc")
            || name.startsWith("sqljdbc") || name.startsWith("jtds") || name.startsWith("db2jcc")
            || name.startsWith("mariadb-java-client") || name.equals("h2.jar") || name.startsWith("h2-")
            || name.equals("hsqldb.jar") || name.startsWith("hsqldb-") || JDBC_NAME.matcher(name).matches());
    }
    static boolean isPool(String name) { return POOL_NAME.matcher(name).matches(); }
    // Inspected as a candidate only; classified as JDBC solely by Maven groupId or java.sql.Driver evidence.
    static boolean genericJdbc(String name) { return name.matches("jdbc-[0-9][A-Za-z0-9._+\\-]*\\.jar"); }
    static boolean jdbcArtifact(String group, String artifact) {
        if (artifact == null) return false;
        if (isJdbc(artifact.toLowerCase(Locale.ENGLISH) + ".jar")) return true;
        return "com.oracle.database.jdbc".equals(group) && artifact.startsWith("ojdbc") || "com.ibm.db2".equals(group) && artifact.equals("jcc")
            || "com.ibm.informix".equals(group) && artifact.equals("jdbc") || "net.snowflake".equals(group) && artifact.equals("snowflake-jdbc")
            || "org.xerial".equals(group) && artifact.equals("sqlite-jdbc") || "org.firebirdsql.jdbc".equals(group) && artifact.startsWith("jaybird")
            || "com.teradata.jdbc".equals(group) && artifact.startsWith("terajdbc");
    }
    static boolean poolArtifact(String artifact) { return artifact != null && isPool(artifact.toLowerCase(Locale.ENGLISH) + ".jar"); }
    static boolean bootMain(String main) { return main != null && (main.equals("org.springframework.boot.loader.JarLauncher")
        || main.equals("org.springframework.boot.loader.WarLauncher") || main.equals("org.springframework.boot.loader.PropertiesLauncher")
        || main.equals("org.springframework.boot.loader.launch.JarLauncher") || main.equals("org.springframework.boot.loader.launch.WarLauncher")
        || main.equals("org.springframework.boot.loader.launch.PropertiesLauncher")); }

    void accept(Document doc, String name, byte[] content) throws IOException {
        if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
            Attributes a = new Manifest(new ByteArrayInputStream(content)).getMainAttributes(); doc.manifest = true;
            doc.title = value(a.getValue("Implementation-Title"));
            doc.version = first(value(a.getValue("Implementation-Version")), value(a.getValue("Bundle-Version")));
            doc.main = value(a.getValue("Main-Class")); doc.start = value(a.getValue("Start-Class"));
            doc.boot = value(a.getValue("Spring-Boot-Version"));
            doc.productName = value(a.getValue("JBoss-Product-Release-Name"));
            doc.productVersion = value(a.getValue("JBoss-Product-Release-Version"));
            if (doc.productName == null || doc.productVersion == null) {
                doc.productName = value(a.getValue("JBoss-Project-Release-Name"));
                doc.productVersion = value(a.getValue("JBoss-Project-Release-Version"));
            }
        } else if (name.equals("META-INF/services/java.sql.Driver")) {
            for (String line : new String(content, "UTF-8").split("\\r?\\n")) {
                line = line.split("#", 2)[0].trim();
                if (line.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) { doc.driverProvider = true; break; }
            }
        } else {
            Properties p = new Properties(); p.load(new ByteArrayInputStream(content));
            if (name.endsWith("META-INF/build-info.properties")) {
                String title = ProcessLaunch.label(p.getProperty("build.name")), buildVersion = p.getProperty("build.version");
                if (title != null) doc.title = title;
                if (buildVersion != null && buildVersion.matches("[0-9][A-Za-z0-9._+\\-]{0,127}")) doc.version = buildVersion;
            } else if (name.equals("org/apache/catalina/util/ServerInfo.properties")) {
                doc.tomcatInfo = value(p.getProperty("server.info")); doc.tomcatNumber = value(p.getProperty("server.number"));
            } else {
                String artifact = value(p.getProperty("artifactId")), group = value(p.getProperty("groupId"));
                boolean pool = !jdbcArtifact(group, artifact) && poolArtifact(artifact);
                if (pool || jdbcArtifact(group, artifact)) { doc.jdbcPomTitle = artifact; doc.jdbcPomVersion = value(p.getProperty("version")); doc.pomPool = pool; }
            }
        }
    }

    Map result(Document doc) {
        Map result = new LinkedHashMap(); List rows = libraries(doc); rows.addAll(doc.children);
        String lower = doc.filename.toLowerCase(Locale.ENGLISH), serverType = null, serverVersion = null;
        if (lower.equals("weblogic.jar")) { serverType = "WebLogic"; serverVersion = doc.version; }
        if (lower.equals("catalina.jar")) { serverType = "Tomcat"; serverVersion = first(tomcatVersion(doc), doc.version); }
        if (doc.productName != null && doc.productVersion != null) {
            String normalized = doc.productName.toLowerCase(Locale.ENGLISH);
            if (normalized.indexOf("wildfly") >= 0 || normalized.indexOf("jboss eap") >= 0 || normalized.indexOf("jboss application server") >= 0 || normalized.equals("jboss as")) {
                serverType = normalized.indexOf("wildfly") >= 0 ? "WildFly" : "JBoss";
                serverVersion = doc.productVersion;
            }
        }
        // The modules launcher version is not the JBoss/WildFly product release.
        if (lower.equals("jboss-modules.jar")) { serverType = null; serverVersion = null; }
        boolean boot = bootMain(doc.main) || doc.boot != null && doc.start != null || doc.bootLayout;
        if (doc.title == null && doc.version == null && doc.main == null && doc.start == null && !boot && serverType == null && rows.isEmpty() && !doc.bootLibrary) return result;
        result.put("springBootLibrary", Boolean.valueOf(doc.bootLibrary));
        result.put("path", doc.path); result.put("title", first(doc.title, doc.filename)); result.put("version", doc.version);
        result.put("mainClass", doc.main); result.put("startClass", doc.start);
        result.put("applicationType", boot ? "Spring Boot" : null); result.put("frameworkVersion", boot ? doc.boot : null);
        result.put("serverType", serverType); result.put("serverVersion", serverVersion);
        result.put("evidence", evidence(doc) + (boot ? "; Spring Boot launcher/framework or packaged layout evidence" + (doc.bootLayout ? "; framework version inferred from packaged Spring Boot core filename" : "") : ""));
        result.put("libraries", rows);
        if (boot) {
            List embedded = new ArrayList();
            for (Object raw : rows) {
                Map library = (Map)raw; String filename = base(String.valueOf(library.get("path")));
                String product = filename.startsWith("tomcat-embed-core") ? "Tomcat" : filename.startsWith("jetty-server-") ? "Jetty" : filename.startsWith("undertow-core-") ? "Undertow" : null;
                if (product != null) embedded.add(AssessmentScan.map("type", product, "version", library.get("version"), "evidence", "Packaged embedded server library; runtime activation not verified"));
            }
            if (!embedded.isEmpty()) result.put("embeddedServers", embedded);
        }
        if (boot && doc.springDataSource != null) {
            SpringDataSource.matchPackagedDriver(doc.springDataSource, rows);
            result.put("configuredDataSource", doc.springDataSource);
        }
        return result;
    }

    List libraries(Document doc) {
        List result = new ArrayList(); String lower = doc.filename.toLowerCase(Locale.ENGLISH);
        boolean pool = !doc.driverProvider && (doc.jdbcPomTitle != null ? doc.pomPool : isPool(lower));
        boolean jdbc = !pool && (isJdbc(lower) || doc.driverProvider || doc.jdbcPomTitle != null);
        if (!(jdbc || pool || isDerby(lower) || (lower.startsWith("spring-core-") || lower.startsWith("tomcat-embed-core") || lower.startsWith("jetty-server-") || lower.startsWith("undertow-core-")) && lower.endsWith(".jar"))) return result;
        if (libraryRows >= 1000) { scan.warn("Library metadata row limit reached"); return result; }
        String title = first(doc.title, first(doc.jdbcPomTitle, doc.filename));
        String version = first(doc.version, doc.jdbcPomVersion), source = doc.version != null ? "manifest version" : doc.jdbcPomVersion != null ? "Maven pom.properties version" : null;
        if (isTomcat(lower)) version = first(tomcatVersion(doc), version);
        if (version == null) {
            Matcher matcher = FILE_VERSION.matcher(doc.filename);
            if (matcher.matches()) { version = matcher.group(1); source = "version inferred from archive filename, not verified by metadata"; }
        }
        String evidence = evidence(doc) + (source == null ? "; version not reported" : "; " + source);
        if (doc.driverProvider) evidence += "; java.sql.Driver service provider declaration found";
        if (pool) evidence += "; JDBC connection pool library, not a database driver";
        result.add(AssessmentScan.map("name", title, "version", version, "path", doc.path, "category", jdbc ? "JDBC" : pool ? "JDBC pool" : "Java library",
            "usage", "found", "evidence", evidence + "; archive presence does not establish datasource use"));
        libraryRows++;
        return result;
    }
    static String tomcatVersion(Document doc) {
        if (doc.tomcatInfo != null && doc.tomcatInfo.startsWith("Apache Tomcat/")) return value(doc.tomcatInfo.substring(14));
        return doc.tomcatNumber;
    }
    static String evidence(Document doc) { return "Archive metadata at " + doc.path + (doc.manifest ? "; manifest inspected without loading classes" : "; filename evidence; archive contents not verified") + "; no code was executed"; }
    static final class Document {
        final String filename, path; final List children = new ArrayList();
        String title, version, main, start, boot, productName, productVersion, tomcatInfo, tomcatNumber, jdbcPomTitle, jdbcPomVersion;
        Map springDataSource;
        boolean manifest, driverProvider, bootLibrary, bootLayout, pomPool;
        Document(String filename, String path) { this.filename = filename; this.path = path; }
    }
    static final class Limit extends IOException { Limit(String message) { super(message); } }
}
