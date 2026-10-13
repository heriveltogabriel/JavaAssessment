package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;

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
        check(((List)result.get("libraries")).size()==2,"Connection pools must not be classified as JDBC drivers");
        check("found".equals(h2.get("usage")) && result.get("serverType")==null,"Packaged libraries do not prove a running server or datasource");
        check(scan.libraries.isEmpty() && scan.applications.isEmpty() && scan.servers.isEmpty() && scan.datasources.isEmpty(),"Metadata helper must not mutate inventory rows");
        check(!Json.stringify(result).contains("do-not-collect-this-secret"),"Application configuration contents must not be collected");
        long bytes=scan.bytesRead;int inspected=scan.archivesInspected;
        check(helper.inspect(new File(root,"./app.jar"))==result && scan.bytesRead==bytes && scan.archivesInspected==inspected,"Canonical archive cache prevents repeated I/O and budget consumption");
        check(inspected==3,"Only the application and supported dependency archives should consume archive count");
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
        AssessmentScan count=scan(true);count.archivesInspected=40;new ArchiveMetadata(count).inspect(new File(root,"catalina.jar"));
        check(count.partial && count.archivesInspected==40 && count.bytesRead==0,"Archive-count budget must stop I/O");
        Map<String,byte[]> many=new LinkedHashMap<String,byte[]>();for(int i=0;i<5001;i++)many.put("entry-"+i,new byte[0]);
        byte[] bad=jar(null,many);int end=bad.length-22;
        // Lie in EOCD: validating the actual bounded central directory must still reject >5000 entries.
        bad[end+8]=1;bad[end+9]=0;bad[end+10]=1;bad[end+11]=0;
        AssessmentScan entries=scan(true);new ArchiveMetadata(entries).inspect(write(root,"many.jar",bad));
        check(entries.partial && entries.bytesRead<ArchiveMetadata.READ_LIMIT,"Misreported central entry counts must be rejected before ZipFile opens");
        byte[] zip64=jar(manifest(),empty());zip64[zip64.length-22+10]=(byte)255;zip64[zip64.length-22+11]=(byte)255;
        AssessmentScan unsupported=scan(true);new ArchiveMetadata(unsupported).inspect(write(root,"zip64.jar",zip64));
        check(unsupported.partial,"Unsupported ZIP64 marker must be rejected");
        Map<String,byte[]> bombEntries=entries("classes/large.bin",new byte[9*1024*1024]);
        bombEntries.put("META-INF/MANIFEST.MF",utf("Manifest-Version: 1.0\nImplementation-Version: 2.4.240\n\n"));
        byte[] bomb=jar(null,bombEntries);
        File outer=write(root,"bomb-app.jar",jar(manifest("Main-Class","org.springframework.boot.loader.JarLauncher","Spring-Boot-Version","2.7.0"),entries("BOOT-INF/lib/h2-2.4.240.jar",bomb)));
        AssessmentScan limited=scan(true);Map result=new ArchiveMetadata(limited).inspect(outer);
        check(limited.partial && limited.bytesRead<=ArchiveMetadata.READ_LIMIT,"Nested inflated bytes must stop at the shared 8MiB budget");
        check(String.valueOf(library(result,"h2-2.4.240.jar").get("evidence")).contains("inferred"),"Unverified nested version must retain filename-inference evidence");
        check(limited.datasources.isEmpty(),"A rejected archive cannot create datasource observations");
    }
    static void remove(File path) { File[] files=path.listFiles();if(files!=null)for(File file:files)remove(file);path.delete(); }
    public static void main(String[] args) throws Exception {
        File root=File.createTempFile("archive-metadata", "-fixtures");if(!root.delete() || !root.mkdir())throw new IOException("Cannot create fixture directory");
        try { bootAndLibraries(root);productsAndFallback(root);boundedArchives(root); }
        finally { remove(root); }
        System.out.println("ARCHIVE_METADATA_TESTS=PASS Boot nestedJDBC embeddedTomcat productVersions cache bounds privacy");
    }
}
