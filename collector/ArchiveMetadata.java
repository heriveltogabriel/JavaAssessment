package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;

/** Reads bounded archive metadata without extracting files or loading application classes. */
public final class ArchiveMetadata {
    static final long READ_LIMIT = 8L * 1024 * 1024;
    static final long FILE_LIMIT = 128L * 1024 * 1024;
    static final int METADATA_LIMIT = 65536, ENTRY_LIMIT = 5000, CENTRAL_LIMIT = 2 * 1024 * 1024;
    static final Pattern FILE_VERSION = Pattern.compile("^(?:ojdbc[0-9]*|classes12|derby(?:client|net|shared|tools|run)?|mysql-connector-(?:java|j)|postgresql|mssql-jdbc|sqljdbc[0-9]*|jtds|db2jcc[0-9]*|mariadb-java-client|h2|hsqldb|tomcat-embed-core)[-_]([0-9][A-Za-z0-9._+\\-]*)\\.jar$", Pattern.CASE_INSENSITIVE);
    final AssessmentScan scan;
    final Map<String, Map> cache = new LinkedHashMap<String, Map>();
    int libraryRows;

    public ArchiveMetadata(AssessmentScan scan) { this.scan = scan; }

    public static boolean isKnownCandidate(String filename) {
        String name = base(filename).toLowerCase(Locale.ENGLISH);
        return isJdbc(name) || isDerby(name) || isTomcat(name) || name.equals("weblogic.jar")
            || name.equals("jboss-modules.jar") || name.startsWith("jboss-as-server") || name.startsWith("jboss-as-version")
            || name.startsWith("jboss-as-product") || name.startsWith("wildfly-server") || name.startsWith("wildfly-version")
            || name.startsWith("wildfly-product");
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
                long central = preflight(file);
                charge(central); // ZipFile reads its central directory; count that bounded work too.
                if (!scan.available()) throw new Limit("Assessment deadline reached during archive discovery");
                archive = new ZipFile(file);
                readOuter(document, archive);
            } catch (Exception e) { report(e, path); }
            finally { if (archive != null) try { archive.close(); } catch (IOException ignored) { } }
        }
        Map result = result(document);
        if (cache.size() < 512) cache.put(path, result);
        else scan.note("Archive metadata cache limit reached; completed archive reads remain bounded");
        return result;
    }

    boolean claimArchive(String path) {
        if (!scan.available()) return false;
        if (scan.archivesInspected >= 40) { scan.warn("Archive inspection count limit reached"); return false; }
        if (scan.bytesRead >= READ_LIMIT) { scan.warn("Metadata read budget of 8 MiB reached"); return false; }
        scan.archivesInspected++;
        return true;
    }

    void readOuter(Document doc, ZipFile archive) throws IOException {
        List<ZipEntry> nested = new ArrayList<ZipEntry>(), metadataEntries = new ArrayList<ZipEntry>();
        Map<String, ZipEntry> springEntries = new LinkedHashMap<String, ZipEntry>();
        Set<String> duplicateSpringEntries = new HashSet<String>(); boolean profileConfiguration = false;
        Enumeration<? extends ZipEntry> entries = archive.entries(); int count = 0;
        while (entries.hasMoreElements()) {
            if (!scan.available()) throw new Limit("Assessment deadline reached during archive discovery");
            if (++count > ENTRY_LIMIT) throw new Limit("Archive entry count limit reached");
            ZipEntry entry = entries.nextElement(); String name = entry.getName();
            if (entry.isDirectory() || !safeName(name)) continue;
            if (metadata(name)) metadataEntries.add(entry);
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
        boolean boot = bootMain(doc.main) || doc.boot != null && doc.start != null;
        if (boot) {
            if (profileConfiguration) scan.note("Profile-specific Spring configuration was not resolved: " + doc.path);
            if (springEntries.size() > 1) scan.note("Multiple base Spring configuration formats found; properties preferred when present; effective precedence and overrides were not evaluated: " + doc.path);
            for (String name : SpringDataSource.BASE_FILES) if (springEntries.containsKey(name)) {
                if (duplicateSpringEntries.contains(name)) { scan.warn("Duplicate base Spring configuration entries were skipped: " + doc.path); break; }
                ZipEntry entry = springEntries.get(name);
                try {
                    if (entry.getSize() > METADATA_LIMIT) throw new Limit("Spring configuration entry exceeds 64 KiB");
                    InputStream input = archive.getInputStream(entry); byte[] content;
                    try { content = read(input, METADATA_LIMIT); } finally { input.close(); }
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
        for (ZipEntry entry : nested) {
            String path = doc.path + "!/" + entry.getName();
            Document child = new Document(base(entry.getName()), path);
            if (claimArchive(path)) {
                try {
                    long remaining = READ_LIMIT - scan.bytesRead;
                    if (entry.getSize() > remaining) throw new Limit("Nested archive exceeds remaining metadata read budget");
                    InputStream input = archive.getInputStream(entry); byte[] content;
                    try { content = read(input, (int) Math.min(READ_LIMIT, remaining)); }
                    finally { input.close(); }
                    preflight(content, content.length, 0);
                    readNested(child, content);
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
            if (entry.getSize() > METADATA_LIMIT) throw new Limit("Archive metadata entry exceeds 64 KiB");
            InputStream input = archive.getInputStream(entry);
            try { accept(doc, entry.getName(), read(input, METADATA_LIMIT)); }
            finally { input.close(); }
        } catch (Exception e) { report(e, doc.path + "!/" + entry.getName()); }
    }

    void readNested(Document doc, byte[] content) throws IOException {
        ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content));
        try {
            ZipEntry entry; int count = 0;
            while ((entry = input.getNextEntry()) != null) {
                if (!scan.available()) throw new Limit("Assessment deadline reached during nested archive discovery");
                if (++count > ENTRY_LIMIT) throw new Limit("Nested archive entry count limit reached");
                String name = entry.getName();
                if (!entry.isDirectory() && safeName(name) && metadata(name)) {
                    if (entry.getSize() > METADATA_LIMIT) throw new Limit("Nested archive metadata entry exceeds 64 KiB");
                    accept(doc, name, read(input, METADATA_LIMIT));
                    // Known dependency + its version is enough: do not inflate application classes to seek redundant metadata.
                    if (doc.version != null && isKnownCandidate(doc.filename) || doc.jdbcPomVersion != null) return;
                } else {
                    // Fully consume with the same budget. getNextEntry/closeEntry must never drain unchecked data.
                    drain(input);
                }
            }
        } finally { input.close(); }
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

    void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        while (true) {
            ensureBudget();
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, READ_LIMIT - scan.bytesRead));
            if (count < 0) return;
            scan.bytesRead += count;
        }
    }

    void ensureBudget() throws IOException {
        if (!scan.available()) throw new Limit("Assessment deadline reached during archive discovery");
        if (scan.bytesRead >= READ_LIMIT) throw new Limit("Metadata read budget of 8 MiB reached");
    }
    void charge(long count) throws IOException {
        ensureBudget();
        if (count < 0 || count > READ_LIMIT - scan.bytesRead) throw new Limit("Metadata read budget of 8 MiB reached");
        scan.bytesRead += count;
    }
    void report(Exception e, String path) { scan.warn(e instanceof Limit ? e.getMessage() + ": " + path : "Archive metadata could not be read: " + path); }

    long preflight(File file) throws IOException {
        return preflight(file, ENTRY_LIMIT, CENTRAL_LIMIT);
    }
    long preflight(File file, int entryLimit, int centralLimit) throws IOException {
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            long length = input.length(); int size = (int) Math.min(length, 65557L); byte[] tail = new byte[size];
            charge(size); input.seek(length - size); input.readFully(tail);
            long[] directory = directory(tail, length, length - size, entryLimit, centralLimit);
            charge(directory[0]); byte[] central = new byte[(int) directory[0]];
            input.seek(directory[1]); input.readFully(central);
            validateCentral(central, 0, central.length, (int) directory[2], entryLimit);
            return directory[0];
        } finally { input.close(); }
    }
    static long preflight(byte[] bytes, long totalLength, long offset) throws IOException {
        long[] directory = directory(bytes, totalLength, offset);
        if (offset == 0) validateCentral(bytes, (int) directory[1], (int) directory[0], (int) directory[2]);
        return directory[0];
    }
    static long[] directory(byte[] bytes, long totalLength, long offset) throws IOException {
        return directory(bytes, totalLength, offset, ENTRY_LIMIT, CENTRAL_LIMIT);
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
    static void validateCentral(byte[] content, int start, int size, int expected) throws IOException {
        validateCentral(content, start, size, expected, ENTRY_LIMIT);
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
            || name.equals("hsqldb.jar") || name.startsWith("hsqldb-"));
    }
    static boolean jdbcArtifact(String group, String artifact) {
        if (artifact == null) return false;
        if (isJdbc(artifact.toLowerCase(Locale.ENGLISH) + ".jar")) return true;
        return "com.oracle.database.jdbc".equals(group) && artifact.startsWith("ojdbc");
    }
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
            if (name.equals("org/apache/catalina/util/ServerInfo.properties")) {
                doc.tomcatInfo = value(p.getProperty("server.info")); doc.tomcatNumber = value(p.getProperty("server.number"));
            } else {
                String artifact = value(p.getProperty("artifactId")), group = value(p.getProperty("groupId"));
                if (jdbcArtifact(group, artifact)) { doc.jdbcPomTitle = artifact; doc.jdbcPomVersion = value(p.getProperty("version")); }
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
        boolean boot = bootMain(doc.main) || doc.boot != null && doc.start != null;
        if (doc.title == null && doc.version == null && doc.main == null && doc.start == null && !boot && serverType == null && rows.isEmpty()) return result;
        result.put("path", doc.path); result.put("title", first(doc.title, doc.filename)); result.put("version", doc.version);
        result.put("mainClass", doc.main); result.put("startClass", doc.start);
        result.put("applicationType", boot ? "Spring Boot" : null); result.put("frameworkVersion", boot ? doc.boot : null);
        result.put("serverType", serverType); result.put("serverVersion", serverVersion);
        result.put("evidence", evidence(doc) + (boot ? "; Spring Boot launcher/framework manifest evidence" : ""));
        result.put("libraries", rows);
        if (boot && doc.springDataSource != null) {
            SpringDataSource.matchPackagedDriver(doc.springDataSource, rows);
            result.put("configuredDataSource", doc.springDataSource);
        }
        return result;
    }

    List libraries(Document doc) {
        List result = new ArrayList(); String lower = doc.filename.toLowerCase(Locale.ENGLISH);
        boolean jdbc = isJdbc(lower) || doc.driverProvider || doc.jdbcPomTitle != null;
        if (!(jdbc || isDerby(lower) || lower.startsWith("tomcat-embed-core") && lower.endsWith(".jar"))) return result;
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
        result.add(AssessmentScan.map("name", title, "version", version, "path", doc.path, "category", jdbc ? "JDBC" : "Java library",
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
        boolean manifest, driverProvider;
        Document(String filename, String path) { this.filename = filename; this.path = path; }
    }
    static final class Limit extends IOException { Limit(String message) { super(message); } }
}
