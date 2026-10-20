package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Synthetic archives only; no application binaries, network calls, or class loading. */
public final class ArchiveMetadataTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static AssessmentScan scan(boolean enabled) { return new AssessmentScan(new Properties(), "archive-test", "archive-test", AssessmentScan.map("inspectArchives", Boolean.valueOf(enabled), "pauseMillis", 0)); }
    static Manifest manifest(String... pairs) {
        Manifest m = new Manifest(); m.getMainAttributes().putValue("Manifest-Version", "1.0");
        for (int i=0;i<pairs.length;i+=2) m.getMainAttributes().putValue(pairs[i],pairs[i+1]); return m;
    }
    static byte[] utf(String value) throws Exception { return value.getBytes("UTF-8"); }
    static byte[] jar(Manifest manifest, Map<String,byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JarOutputStream out = manifest == null ? new JarOutputStream(bytes) : new JarOutputStream(bytes,manifest);
        try { for (Map.Entry<String,byte[]> entry : entries.entrySet()) { out.putNextEntry(new JarEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry(); } }
        finally { out.close(); } return bytes.toByteArray();
    }
    /** Like jar(), but nested .jar entries are STORED as the Spring Boot loader requires. */
    static byte[] boot(Manifest manifest, Map<String,byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); JarOutputStream out = new JarOutputStream(bytes,manifest);
        try {
            for (Map.Entry<String,byte[]> entry : entries.entrySet()) {
                JarEntry e = new JarEntry(entry.getKey());
                if (entry.getKey().endsWith(".jar")) { CRC32 crc=new CRC32();crc.update(entry.getValue());e.setMethod(ZipEntry.STORED);e.setSize(entry.getValue().length);e.setCompressedSize(entry.getValue().length);e.setCrc(crc.getValue()); }
                out.putNextEntry(e);out.write(entry.getValue());out.closeEntry();
            }
        } finally { out.close(); } return bytes.toByteArray();
    }
    static Manifest bootManifest() { return manifest("Main-Class","org.springframework.boot.loader.launch.JarLauncher","Start-Class","example.Demo","Spring-Boot-Version","3.2.5"); }
    static byte[] noise(int size) { byte[] b=new byte[size];new Random(7).nextBytes(b);return b; }
    static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i=0;i+needle.length<=haystack.length;i++) { for (int j=0;j<needle.length;j++) if (haystack[i+j]!=needle[j]) continue outer; return i; }
        return -1;
    }
    static File write(File root, String name, byte[] content) throws Exception {
        File file = new File(root,name); file.getParentFile().mkdirs(); FileOutputStream out = new FileOutputStream(file);
        try { out.write(content); } finally { out.close(); } return file;
    }
    static Map<String,byte[]> entries(String name, byte[] data) { Map<String,byte[]> result=new LinkedHashMap<String,byte[]>();result.put(name,data);return result; }
    static Map<String,byte[]> empty() { return new LinkedHashMap<String,byte[]>(); }
    static Map library(Map metadata, String pathSuffix) {
        for (Object item : (List)metadata.get("libraries")) if (String.valueOf(((Map)item).get("path")).endsWith(pathSuffix)) return (Map)item;
        throw new AssertionError("Expected library missing: "+pathSuffix);
    }
    static void bootAndLibraries(File root) throws Exception {
        Manifest tomcat=manifest("Bundle-SymbolicName","org.apache.tomcat-embed-core","Bundle-Version","11.0.24");
        Attributes servlet=new Attributes();servlet.putValue("Implementation-Title","jakarta.servlet");servlet.putValue("Implementation-Version","6.1");tomcat.getEntries().put("jakarta/servlet",servlet);
        Map<String,byte[]> contents=new LinkedHashMap<String,byte[]>();
        contents.put("BOOT-INF/lib/h2-2.4.240.jar",jar(manifest("Implementation-Title","H2 Database Engine","Implementation-Version","2.4.240"),entries("org/h2/Driver.class",new byte[]{0,1,2,3})));
        contents.put("BOOT-INF/lib/tomcat-embed-core-11.0.24.jar",jar(tomcat,empty()));
        contents.put("BOOT-INF/lib/HikariCP-7.0.2.jar",jar(manifest("Bundle-Version","7.0.2"),empty()));
        contents.put("application.properties",utf("spring.datasource.password=do-not-collect-this-secret"));
        File boot=write(root,"app.jar",jar(manifest("Implementation-Title","springdemo","Implementation-Version","1.0.0","Main-Class","org.springframework.boot.loader.launch.JarLauncher","Start-Class","br.com.demo.DemoApplication","Spring-Boot-Version","4.1.1"),contents));
        AssessmentScan scan=scan(true);ArchiveMetadata helper=new ArchiveMetadata(scan);Map result=helper.inspect(boot);
        check("Spring Boot".equals(result.get("applicationType")),"Boot launcher should identify the application");
        check("1.0.0".equals(result.get("version")) && "4.1.1".equals(result.get("frameworkVersion")),"Application and framework versions must remain separate");
        check("br.com.demo.DemoApplication".equals(result.get("startClass")),"Start class should be reported");
        Map h2=library(result,"!/BOOT-INF/lib/h2-2.4.240.jar"), embedded=library(result,"!/BOOT-INF/lib/tomcat-embed-core-11.0.24.jar");
        check("2.4.240".equals(h2.get("version")) && "JDBC".equals(h2.get("category")),"H2 manifest driver metadata must be identified");
        check("11.0.24".equals(embedded.get("version")),"Embedded Tomcat main Bundle-Version must win over servlet section version");
        check("Java library".equals(embedded.get("category")),"Embedded server library is not a JDBC driver");
        check(((List)result.get("libraries")).size()==3 && "JDBC pool".equals(library(result,"HikariCP-7.0.2.jar").get("category")),"Connection pools are reported as pools, never as JDBC drivers");
        check("found".equals(h2.get("usage")) && result.get("serverType")==null,"Packaged libraries do not prove a running server or datasource");
        check(scan.libraries.isEmpty() && scan.applications.isEmpty() && scan.servers.isEmpty() && scan.datasources.isEmpty(),"Metadata helper must not mutate inventory rows");
        check(!Json.stringify(result).contains("do-not-collect-this-secret"),"Application configuration contents must not be collected");
        long bytes=scan.bytesRead;int inspected=scan.archivesInspected;
        check(helper.inspect(new File(root,"./app.jar"))==result && scan.bytesRead==bytes && scan.archivesInspected==inspected,"Canonical archive cache prevents repeated I/O and budget consumption");
        check(inspected==4,"Only the application and supported dependency archives should consume archive count");
        check(!scan.partial,"Small valid metadata fixtures should finish without partial coverage");
        File embeddedFile=write(root,"tomcat-embed-core-11.0.24.jar",contents.get("BOOT-INF/lib/tomcat-embed-core-11.0.24.jar"));
        check(new ArchiveMetadata(scan(true)).inspect(embeddedFile).get("serverType")==null,"Embedded Tomcat library alone must not become a standalone server installation");
    }
    static void productsAndFallback(File root) throws Exception {
        File tomcat=write(root,"catalina.jar",jar(manifest("Implementation-Title","Apache Tomcat","Implementation-Version","9.0.122"),entries("org/apache/catalina/util/ServerInfo.properties",utf("server.info=Apache Tomcat/9.0.122\nserver.number=9.0.122.0\n"))));
        Map result=new ArchiveMetadata(scan(true)).inspect(tomcat);
        check("Tomcat".equals(result.get("serverType")) && "9.0.122".equals(result.get("serverVersion")),"Standalone Tomcat version should use product metadata");
        File product=write(root,"wildfly-server.jar",jar(manifest("JBoss-Product-Release-Name","WildFly Full","JBoss-Product-Release-Version","26.1.3.Final","Implementation-Version","not-the-product-version"),empty()));
        result=new ArchiveMetadata(scan(true)).inspect(product);
        check("WildFly".equals(result.get("serverType")) && "26.1.3.Final".equals(result.get("serverVersion")),"WildFly product release must take priority over module version");
        File core=write(root,"wildfly-server-18.1.jar",jar(manifest("Implementation-Title","WildFly: Server","Implementation-Version","18.1"),empty()));
        check(new ArchiveMetadata(scan(true)).inspect(core).get("serverVersion")==null,"WildFly core/component version must not replace product release version");
        File modules=write(root,"jboss-modules.jar",jar(manifest("Implementation-Title","JBoss Modules","Implementation-Version","2.0.2.Final"),empty()));
        result=new ArchiveMetadata(scan(true)).inspect(modules);
        check(result.get("serverType")==null && result.get("serverVersion")==null,"Modules version is not the JBoss/WildFly product release");
        File oracle=write(root,"ojdbc8-19.3.0.0.jar",jar(manifest("Implementation-Version","different-manifest-version"),empty()));
        AssessmentScan disabled=scan(false);result=new ArchiveMetadata(disabled).inspect(oracle);
        Map library=library(result,"ojdbc8-19.3.0.0.jar");
        check("19.3.0.0".equals(library.get("version")) && String.valueOf(library.get("evidence")).contains("inferred"),"Filename-derived version requires explicit inference evidence");
        check(disabled.archivesInspected==0 && disabled.bytesRead==0,"inspectArchives=false must prevent archive I/O");
        check(new ArchiveMetadata(scan(false)).inspect(new File(root,"app.jar")).isEmpty(),"Disabled inspection must not invent Boot metadata");
        File jdbcApi=write(root,"ojdbc8.jar",jar(manifest("Specification-Version","4.2"),empty()));
        library=library(new ArchiveMetadata(scan(true)).inspect(jdbcApi),"ojdbc8.jar");
        check(library.get("version")==null,"JDBC specification and Java compatibility numbers are not driver versions");
        Map<String,byte[]> driverEntries=entries("META-INF/maven/org.postgresql/postgresql/pom.properties",utf("groupId=org.postgresql\nartifactId=postgresql\nversion=42.7.8\n"));
        driverEntries.put("META-INF/services/java.sql.Driver",utf("# provider\norg.postgresql.Driver\n"));
        File renamed=write(root,"renamed-driver.jar",jar(manifest(),driverEntries));
        library=library(new ArchiveMetadata(scan(true)).inspect(renamed),"renamed-driver.jar");
        check("42.7.8".equals(library.get("version")) && "JDBC".equals(library.get("category")),"Maven metadata and Driver SPI identify renamed JDBC archives");
        check(ArchiveMetadata.isKnownCandidate("hsqldb-2.7.3.jar") && ArchiveMetadata.isKnownCandidate("h2-2.4.240.jar"),"Additional JDBC candidates should be recognized");
        check(new ArchiveMetadata(scan(true)).inspect(write(root,"config.xml",utf("secret"))).isEmpty(),"Nonarchive configuration must not be inspected");
    }
    static void boundedArchives(File root) throws Exception {
        byte[] oversized=new byte[ArchiveMetadata.METADATA_LIMIT+1];Arrays.fill(oversized,(byte)'A');
        File largeManifest=write(root,"derby.jar",jar(null,entries("META-INF/MANIFEST.MF",oversized)));
        AssessmentScan metadata=scan(true);new ArchiveMetadata(metadata).inspect(largeManifest);
        check(metadata.partial && metadata.bytesRead<ArchiveMetadata.READ_LIMIT,"Oversized manifest must be rejected within budget");
        AssessmentScan count=scan(true);count.archivesInspected=ArchiveMetadata.ARCHIVE_LIMIT;new ArchiveMetadata(count).inspect(new File(root,"catalina.jar"));
        check(count.partial && count.archivesInspected==ArchiveMetadata.ARCHIVE_LIMIT && count.bytesRead==0,"Archive-count budget must stop I/O");
        Map<String,byte[]> many=new LinkedHashMap<String,byte[]>();for(int i=0;i<5001;i++)many.put("entry-"+i,new byte[0]);
        byte[] bad=jar(null,many);int end=bad.length-22;
        // Lie in EOCD: validating the actual bounded central directory must reject the mismatched entry count.
        bad[end+8]=1;bad[end+9]=0;bad[end+10]=1;bad[end+11]=0;
        AssessmentScan entries=scan(true);new ArchiveMetadata(entries).inspect(write(root,"many.jar",bad));
        check(entries.partial && entries.bytesRead<ArchiveMetadata.READ_LIMIT,"Misreported central entry counts must be rejected before ZipFile opens");
        byte[] zip64=jar(manifest(),empty());zip64[zip64.length-22+10]=(byte)255;zip64[zip64.length-22+11]=(byte)255;
        AssessmentScan unsupported=scan(true);new ArchiveMetadata(unsupported).inspect(write(root,"zip64.jar",zip64));
        check(unsupported.partial,"Unsupported ZIP64 marker must be rejected");
        Map<String,byte[]> bombEntries=entries("classes/large.bin",new byte[(int)ArchiveMetadata.READ_LIMIT + 1024*1024]);
        bombEntries.put("META-INF/MANIFEST.MF",utf("Manifest-Version: 1.0\nImplementation-Version: 2.4.240\n\n"));
        byte[] bomb=jar(null,bombEntries);
        File outer=write(root,"bomb-app.jar",jar(manifest("Main-Class","org.springframework.boot.loader.JarLauncher","Spring-Boot-Version","2.7.0"),entries("BOOT-INF/lib/h2-2.4.240.jar",bomb)));
        AssessmentScan limited=scan(true);Map result=new ArchiveMetadata(limited).inspect(outer);
        check(limited.partial && limited.bytesRead<=ArchiveMetadata.READ_LIMIT,"Nested inflated bytes must stop at the shared metadata budget");
        check(String.valueOf(library(result,"h2-2.4.240.jar").get("evidence")).contains("inferred"),"Unverified nested version must retain filename-inference evidence");
        check(limited.datasources.isEmpty(),"A rejected archive cannot create datasource observations");
    }
    static void nestedArchives(File root) throws Exception {
        // STORED nested driver: only end record, central directory and metadata entries are read, never the class bodies.
        Map<String,byte[]> driver=entries("oracle/jdbc/Big.class",noise(2*1024*1024));
        driver.put("META-INF/maven/com.oracle.database.jdbc/ojdbc8/pom.properties",utf("groupId=com.oracle.database.jdbc\nartifactId=ojdbc8\nversion=19.3.0.0\n"));
        byte[] ojdbc=jar(manifest(),driver);
        Map<String,byte[]> contents=entries("BOOT-INF/classes/application.properties",utf("spring.datasource.url=jdbc:oracle:thin:@//db.example:1521/ORCL\n"));
        contents.put("BOOT-INF/lib/ojdbc8-19.3.0.0.jar",ojdbc);
        byte[] fat=boot(bootManifest(),contents);
        AssessmentScan scan=scan(true);Map result=new ArchiveMetadata(scan).inspect(write(root,"stored.jar",fat));
        Map row=library(result,"ojdbc8-19.3.0.0.jar");
        check("19.3.0.0".equals(row.get("version")) && String.valueOf(row.get("evidence")).contains("pom.properties"),"Stored nested JAR metadata is read by random access");
        check(!scan.partial && scan.bytesRead<64*1024,"Stored nested JAR must not be read whole: "+scan.bytesRead);
        check("3.2.5".equals(result.get("frameworkVersion")) && result.get("configuredDataSource") instanceof Map,"Boot version and datasource survive");
        // A corrupted local header of the nested JAR is rejected; the filename evidence remains, marked as inferred.
        byte[] corrupt=fat.clone();int at=indexOf(corrupt,utf("BOOT-INF/lib/ojdbc8-19.3.0.0.jar"));corrupt[at-30+8]=8;
        AssessmentScan rejected=scan(true);row=library(new ArchiveMetadata(rejected).inspect(write(root,"stored-bad.jar",corrupt)),"ojdbc8-19.3.0.0.jar");
        check(rejected.partial && String.valueOf(row.get("evidence")).contains("inferred"),"Malformed stored nested header is rejected");
        // A nested central directory pointing outside the nested JAR is rejected.
        byte[] escape=fat.clone();int eocd=indexOf(escape,new byte[]{0x50,0x4b,0x05,0x06});escape[eocd+16]=(byte)0xff;escape[eocd+17]=(byte)0xff;escape[eocd+18]=(byte)0x7f;
        AssessmentScan invalid=scan(true);new ArchiveMetadata(invalid).inspect(write(root,"stored-escape.jar",escape));
        check(invalid.partial,"Nested central directory offsets are bounded to the nested JAR");
        // DEFLATED nested JAR (plain WAR): streamed, and reading stops once the manifest identifies a known driver.
        Map<String,byte[]> war=new LinkedHashMap<String,byte[]>();
        war.put("WEB-INF/lib/postgresql-42.7.8.jar",jar(manifest("Implementation-Version","42.7.8"),entries("org/postgresql/Big.class",noise(2*1024*1024))));
        war.put("WEB-INF/lib/c3p0-0.9.5.5.jar",jar(manifest(),entries("META-INF/maven/com.mchange/c3p0/pom.properties",utf("groupId=com.mchange\nartifactId=c3p0\nversion=0.9.5.5\n"))));
        AssessmentScan deflated=scan(true);result=new ArchiveMetadata(deflated).inspect(write(root,"plain.war",jar(manifest(),war)));
        check("42.7.8".equals(library(result,"postgresql-42.7.8.jar").get("version")) && !deflated.partial && deflated.bytesRead<64*1024,"Deflated nested JAR stops after identifying metadata: "+deflated.bytesRead);
        row=library(result,"c3p0-0.9.5.5.jar");
        check("JDBC pool".equals(row.get("category")) && "0.9.5.5".equals(row.get("version")),"Pool version from nested Maven metadata");
        // A deflated nested JAR without early metadata is capped per archive and falls back to the filename.
        Map<String,byte[]> noisy=entries("org/h2/A.class",noise(3*1024*1024));noisy.put("org/h2/B.class",noise(3*1024*1024));
        AssessmentScan capped=scan(true);result=new ArchiveMetadata(capped).inspect(write(root,"capped.war",jar(manifest(),entries("WEB-INF/lib/h2-2.4.240.jar",jar(null,noisy)))));
        check(capped.partial && capped.bytesRead<ArchiveMetadata.NESTED_INFLATE_LIMIT+256*1024 && String.valueOf(library(result,"h2-2.4.240.jar").get("evidence")).contains("inferred"),"Per-nested inflate cap applies");
        // More than 5000 entries: accepted because only the bounded central directory is read.
        Map<String,byte[]> large=new LinkedHashMap<String,byte[]>();for(int i=0;i<6000;i++)large.put("BOOT-INF/classes/C"+i+".class",new byte[8]);
        large.put("BOOT-INF/lib/ojdbc8-19.3.0.0.jar",ojdbc);
        AssessmentScan big=scan(true);result=new ArchiveMetadata(big).inspect(write(root,"large.jar",boot(bootManifest(),large)));
        check("3.2.5".equals(result.get("frameworkVersion")) && "19.3.0.0".equals(library(result,"ojdbc8-19.3.0.0.jar").get("version")) && !big.partial,"Archives above 5000 entries are inspected");
        // Nested libraries have their own count budget and do not consume the outer archive budget.
        AssessmentScan budget=scan(true);budget.archivesInspected=ArchiveMetadata.ARCHIVE_LIMIT-1;
        result=budget.archiveMetadata.inspect(new File(root,"stored.jar"));
        check(budget.archivesInspected==ArchiveMetadata.ARCHIVE_LIMIT+1 && "19.3.0.0".equals(library(result,"ojdbc8-19.3.0.0.jar").get("version")),"Nested libraries use a separate budget");
        check(budget.archiveMetadata.inspect(new File(root,"plain.war")).isEmpty() && budget.partial,"Outer archive budget still stops new archives");
    }
    static void catalog(File root) throws Exception {
        String[][] drivers={{"jcc-11.5.9.0.jar","DB2"},{"jconn4.jar","Sybase"},{"jconn3-6.05.jar","Sybase"},{"ifxjdbc.jar","Informix"},{"terajdbc4.jar","Teradata"},{"terajdbc-20.00.00.10.jar","Teradata"},
            {"ngdbc-2.20.11.jar","SAP HANA"},{"snowflake-jdbc-3.16.1.jar","Snowflake"},{"sqlite-jdbc-3.45.1.0.jar","SQLite"},{"jaybird-5.0.4.java11.jar","Firebird"},{"jaybird-full-2.2.15.jar","Firebird"}};
        for (String[] d : drivers) {
            check(ArchiveMetadata.isJdbc(d[0]) && ArchiveMetadata.isKnownCandidate(d[0]) && !ArchiveMetadata.isPool(d[0]),"JDBC driver filename: "+d[0]);
            check(d[1].equals(SpringDataSource.libraryDatabase("/app.jar!/BOOT-INF/lib/"+d[0])),"Database for "+d[0]);
        }
        check("11.5.9.0".equals(library(new ArchiveMetadata(scan(false)).inspect(new File(root,"jcc-11.5.9.0.jar")),"jcc-11.5.9.0.jar").get("version")),"DB2 Maven filename version inferred");
        for (String pool : new String[]{"HikariCP-5.1.0.jar","HikariCP-java7-2.4.13.jar","c3p0-0.9.5.5.jar","commons-dbcp-1.4.jar","commons-dbcp2-2.12.0.jar","tomcat-jdbc-9.0.80.jar","ucp.jar","ucp11-23.4.0.24.05.jar"}) {
            String lower=pool.toLowerCase(Locale.ENGLISH);
            check(ArchiveMetadata.isPool(lower) && !ArchiveMetadata.isJdbc(lower) && ArchiveMetadata.isKnownCandidate(lower),"Pool filename: "+pool);
            Map row=library(new ArchiveMetadata(scan(false)).inspect(new File(root,pool)),pool);
            check("JDBC pool".equals(row.get("category")),"Pool category: "+pool);
        }
        for (String generic : new String[]{"jdbc-4.50.10.jar","jcc.jar","jdbc.jar","commons-dbcp-utils.jar","ucpx.jar","hikari.jar"})
            check(!ArchiveMetadata.isJdbc(generic) && !ArchiveMetadata.isPool(generic) && SpringDataSource.libraryDatabase(generic)==null,"No false positive for "+generic);
        // Generic Informix Maven name: JDBC only with groupId evidence.
        File informix=write(root,"jdbc-4.50.10.jar",jar(manifest("Implementation-Version","4.50.10"),entries("META-INF/maven/com.ibm.informix/jdbc/pom.properties",utf("groupId=com.ibm.informix\nartifactId=jdbc\nversion=4.50.10\n"))));
        Map row=library(new ArchiveMetadata(scan(true)).inspect(informix),"jdbc-4.50.10.jar");
        check("JDBC".equals(row.get("category")) && "4.50.10".equals(row.get("version")),"Informix Maven artifact is identified by groupId");
        File unrelated=write(root,"other/jdbc-4.50.10.jar",jar(manifest("Implementation-Version","4.50.10"),entries("META-INF/maven/com.example/jdbc/pom.properties",utf("groupId=com.example\nartifactId=jdbc\nversion=4.50.10\n"))));
        List none=(List)new ArchiveMetadata(scan(true)).inspect(unrelated).get("libraries");
        check(none==null || none.isEmpty(),"Generic jdbc-*.jar without driver evidence is not a driver");
        File db2=write(root,"renamed-db2.jar",jar(manifest(),entries("META-INF/maven/com.ibm.db2/jcc/pom.properties",utf("groupId=com.ibm.db2\nartifactId=jcc\nversion=11.5.9.0\n"))));
        row=library(new ArchiveMetadata(scan(true)).inspect(db2),"renamed-db2.jar");
        check("JDBC".equals(row.get("category")) && "11.5.9.0".equals(row.get("version")),"DB2 Maven groupId identifies the driver");
        String[][] urls={{"jdbc:sybase:Tds:db:5000/x","Sybase"},{"jdbc:jtds:sqlserver://db/x","SQL Server"},{"jdbc:jtds:sybase://db/x","Sybase"},{"jdbc:informix-sqli://db:9088/x","Informix"},
            {"jdbc:teradata://db/DATABASE=x","Teradata"},{"jdbc:sap://db:30015","SAP HANA"},{"jdbc:snowflake://acct.snowflakecomputing.com","Snowflake"},{"jdbc:sqlite:/data/x.db","SQLite"},{"jdbc:firebirdsql://db/x","Firebird"}};
        for (String[] u : urls) check(u[1].equals(SpringDataSource.databaseType(u[0])),"URL database: "+u[0]);
        String[][] classes={{"com.sybase.jdbc4.jdbc.SybDriver","Sybase"},{"com.informix.jdbc.IfxDriver","Informix"},{"com.teradata.jdbc.TeraDriver","Teradata"},{"com.sap.db.jdbc.Driver","SAP HANA"},
            {"net.snowflake.client.jdbc.SnowflakeDriver","Snowflake"},{"org.sqlite.JDBC","SQLite"},{"org.firebirdsql.jdbc.FBDriver","Firebird"}};
        for (String[] c : classes) check(c[1].equals(SpringDataSource.driverDatabase(c[0])),"Driver database: "+c[0]);
        check(SpringDataSource.driverDatabase("net.sourceforge.jtds.jdbc.Driver")==null,"jTDS driver class alone does not name the database");
        Map fields=AssessmentScan.map("driverName","org.sqlite.JDBC");List libraries=new ArrayList();
        libraries.add(AssessmentScan.map("category","JDBC","path","/a.jar!/BOOT-INF/lib/sqlite-jdbc-3.45.1.0.jar","version","3.45.1.0"));
        libraries.add(AssessmentScan.map("category","JDBC pool","path","/a.jar!/BOOT-INF/lib/HikariCP-5.1.0.jar","version","5.1.0"));
        SpringDataSource.matchPackagedDriver(fields,libraries);
        check("3.45.1.0".equals(fields.get("driverVersion")),"New driver matches its packaged library; pools are ignored");
    }
    static void remove(File path) { File[] files=path.listFiles();if(files!=null)for(File file:files)remove(file);path.delete(); }
    /** The bounded central-directory read is charged once, not again for the ZipFile that follows. */
    static void centralCharge(File root) throws Exception {
        Map<String,byte[]> entries=new LinkedHashMap<String,byte[]>();
        for (int i=0;i<3000;i++) entries.put("app/resources/file-"+i+".txt",new byte[0]);
        entries.put("META-INF/context.xml",utf("<Context/>"));
        byte[] bytes=jar(manifest("Implementation-Title","Fixture"),entries);File file=write(root,"many-entries.war",bytes);
        int end=bytes.length-22;long central=(bytes[end+12]&255)|(bytes[end+13]&255)<<8|(bytes[end+14]&255)<<16|(long)(bytes[end+15]&255)<<24;
        AssessmentScan scan=scan(true);scan.archiveMetadata.inspect(file);
        check(scan.bytesRead>=central && scan.bytesRead<central*3/2,"Archive central directory charged once: central="+central+" read="+scan.bytesRead);
        AssessmentScan config=scan(true);check(config.serverDataSources.entry(file,"META-INF/context.xml",ServerDataSources.CONFIG_LIMIT)!=null,"Archive entry read");
        check(config.bytesRead>=central && config.bytesRead<central*3/2,"Datasource archive entry charges the central directory once: central="+central+" read="+config.bytesRead);
    }
    /** Signed JARs keep a digest per class after the main section; the version must still be read. */
    static void signedManifest(File root) throws Exception {
        StringBuilder mf=new StringBuilder("Manifest-Version: 1.0\r\nImplementation-Title: Oracle JDBC Driver\r\nImplementation-Version: 19.3.0.0.0\r\n\r\n");
        for(int i=0;mf.length()<300*1024;i++)mf.append("Name: oracle/jdbc/driver/Class"+i+".class\r\nSHA-256-Digest: 0123456789abcdefghijklmnopqrstuvwxyzABCDEFGH=\r\n\r\n");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes);
        zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));zip.write(mf.toString().getBytes("UTF-8"));zip.closeEntry();
        zip.putNextEntry(new java.util.zip.ZipEntry("oracle/jdbc/OracleDriver.class"));zip.write("fixture".getBytes("UTF-8"));zip.closeEntry();zip.close();
        File signed=write(root,"signed/ojdbc8.jar",bytes.toByteArray());
        AssessmentScan scan=scan(true);Map result=new ArchiveMetadata(scan).inspect(signed);
        check("19.3.0.0.0".equals(library(result,"ojdbc8.jar").get("version")),"Signed JAR manifest larger than 64 KiB keeps its version: "+Json.stringify(result));
        for(Object w:scan.warnings)check(!String.valueOf(w).contains("exceeds 64 KiB"),"No oversize warning for a signed manifest");
        check(scan.bytesRead<64*1024,"Only the main manifest section is read: "+scan.bytesRead);
    }
    public static void main(String[] args) throws Exception {
        File root=File.createTempFile("archive-metadata", "-fixtures");if(!root.delete() || !root.mkdir())throw new IOException("Cannot create fixture directory");
        try { bootAndLibraries(root);productsAndFallback(root);boundedArchives(root);nestedArchives(root);catalog(root);centralCharge(root);signedManifest(root); }
        finally { remove(root); }
        System.out.println("ARCHIVE_METADATA_TESTS=PASS Boot nestedJDBC storedNested deflatedNested largeArchives driverCatalog pools embeddedTomcat productVersions cache bounds privacy");
    }
}
