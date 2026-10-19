package assessment;

import java.io.*;
import java.util.*;

/** Bounded synthetic configuration fixtures only: no application launch, JDBC or network. */
public final class SpringDataSourceTest {
    static void check(boolean value, String message) { CollectorTest.check(value, message); }
    static byte[] utf(String value) throws Exception { return value.getBytes("UTF-8"); }
    static Map<String,byte[]> entries() { return new LinkedHashMap<String,byte[]>(); }
    static void config(Map<String,byte[]> entries, String filename, String value) throws Exception { entries.put(SpringDataSource.PREFIX + filename, utf(value)); }
    static void h2(Map<String,byte[]> entries, String version) throws Exception {
        entries.put("BOOT-INF/lib/h2-" + version + ".jar", ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Implementation-Title", "H2 Database Engine", "Implementation-Version", version), entries()));
    }
    static File boot(File root, String name, Map<String,byte[]> entries) throws Exception {
        File file = new File(root, name);
        ProcessLaunchTest.write(file, ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Main-Class", "org.springframework.boot.loader.launch.JarLauncher", "Start-Class", "example.Demo", "Implementation-Title", "springdemo", "Spring-Boot-Version", "4.1.1"), entries));
        return file.getCanonicalFile();
    }
    static AssessmentScan scan(File root, File jar, boolean enabled, int processes) throws Exception {
        File proc = new File(root, "proc-" + jar.getName());
        for (int i = 0; i < processes; i++) ProcessLaunchTest.process(proc, String.valueOf(98768000 + i), "java", "-jar", jar.getPath());
        AssessmentScan scan = new AssessmentScan(new Properties(), "spring-test", "spring-test", AssessmentScan.map("inspectArchives", Boolean.valueOf(enabled), "pauseMillis", 0));
        scan.excluded.clear(); scan.discoverProcesses(proc); return scan;
    }
    static void propertiesAndPrivacy(File root) throws Exception {
        Map<String,byte[]> entries = entries(); h2(entries, "2.4.240");
        config(entries, "application.properties", "spring.datasource.ur\\u006c=jdbc\\:h2\\:mem\\:\\\n  demo;PASSWORD=URL_SECRET_CANARY\n" +
            "spring.datasource.driver-class-name=org.h2.Driver\nspring.datasource.username=USER_SECRET_CANARY\nspring.datasource.password=PASSWORD_SECRET_CANARY\n" +
            "spring.datasource.hikari.maximum-pool-size=12\nspring.datasource.hikari.pool-name=POOL_SECRET_CANARY\n");
        File jar = boot(root, "configured.jar", entries); AssessmentScan scan = scan(root, jar, true, 1);
        check(scan.datasources.size() == 1, "Base datasource configuration must create exactly one configuration finding");
        Map row = (Map)scan.datasources.get(0);
        check("configuration".equals(row.get("evidenceLevel")) && "CONFIGURED".equals(row.get("state")), "Configuration presence must not be reported as runtime connection health");
        check("H2".equals(row.get("databaseType")) && "org.h2.Driver".equals(row.get("driverName")) && "2.4.240".equals(row.get("driverVersion")), "Properties escapes and continuation preserve only allowlisted JDBC metadata");
        check(Integer.valueOf(12).equals(row.get("configuredMaxPoolSize")) && row.get("poolCapacity") == null && row.get("activeConnections") == null && row.get("poolAvailable") == null,
            "Configured pool maximum is separate from absent runtime pool metrics");
        check(row.get("applicationId") != null && row.get("pid") != null && "springdemo datasource".equals(row.get("name")) && String.valueOf(row.get("configurationSource")).endsWith("!/BOOT-INF/classes/application.properties"), "Application association and source path must be retained");
        check("Matching packaged JDBC library; runtime loading not verified".equals(row.get("driverVersionEvidence")), "Packaged driver version must carry limited evidence");
        check(String.valueOf(row.get("driverArtifact")).endsWith("!/BOOT-INF/lib/h2-2.4.240.jar"), "Datasource keeps the unique nested driver JAR path");
        check(row.get("databaseVersion") == null, "Packaged JAR never implies database server version");
        String json = Json.stringify(AssessmentScan.map("datasources", scan.datasources, "libraries", scan.libraries, "applications", scan.applications, "warnings", scan.warnings, "cache", scan.archiveMetadata.cache));
        for (String secret : new String[]{"URL_SECRET_CANARY", "USER_SECRET_CANARY", "PASSWORD_SECRET_CANARY", "POOL_SECRET_CANARY", "jdbc:h2:mem:demo", "spring.datasource.password"})
            check(json.indexOf(secret) < 0, "No raw URL, credentials or arbitrary config values may enter results or cache");
        check("found".equals(((Map)scan.libraries.get(0)).get("usage")), "Configured datasource must not promote a packaged driver to confirmed runtime usage");
        long bytes = scan.bytesRead; int archives = scan.archivesInspected;
        scan.inspectJar(jar, jar.getName());
        check(scan.datasources.size() == 1 && scan.bytesRead == bytes && scan.archivesInspected == archives, "Repeated metadata inspection uses cache and does not duplicate datasource");
    }
    static void discoveryScope(File root) throws Exception {
        Map<String,byte[]> onlyDriver = entries(); h2(onlyDriver, "2.4.240");
        check(scan(root, boot(root, "driver-only.jar", onlyDriver), true, 1).datasources.isEmpty(), "A packaged JDBC driver alone must not create a datasource");
        Map<String,byte[]> pool = entries(); config(pool, "application.properties", "spring.datasource.hikari.maximum-pool-size=10\n");
        check(scan(root, boot(root, "pool-only.jar", pool), true, 1).datasources.isEmpty(), "Pool configuration alone is insufficient datasource evidence");
        Map<String,byte[]> profile = entries(); h2(profile, "2.4.240"); config(profile, "application-prod.properties", "spring.datasource.url=jdbc:h2:mem:profile\n");
        check(scan(root, boot(root, "profile-only.jar", profile), true, 1).datasources.isEmpty(), "Profile-specific files must not invent an active datasource");
        Map<String,byte[]> base = entries(); h2(base, "2.4.240"); config(base, "application.properties", "spring.datasource.url=jdbc:h2:mem:base\n");
        config(base, "application-prod.properties", "spring.datasource.url=jdbc:postgresql://PROFILE_SECRET_CANARY/database\n");
        config(base, "application.yaml", "spring:\n  datasource:\n    url: jdbc:mysql://YAML_SECRET_CANARY/database\n");
        File jar = boot(root, "base-and-profile.jar", base); AssessmentScan multiple = scan(root, jar, true, 2);
        check(multiple.datasources.size() == 2 && "H2".equals(((Map)multiple.datasources.get(0)).get("databaseType")), "One datasource per process; multiple formats and inactive profile siblings do not add rows");
        Map row = (Map)multiple.datasources.get(0);
        check(((List)row.get("associations")).size() == 1 && row.get("pid") != null && row.get("applicationId") != null, "Datasource candidates keep their process owner even when the packaged artifact is shared");
        check(multiple.archivesInspected == 2, "Two processes sharing an artifact must reuse the same outer/nested archive reads");
        check(scan(root, jar, false, 1).datasources.isEmpty(), "inspectArchives=false must suppress configuration discovery");
        AssessmentScan disabled = ArchiveMetadataTest.scan(false); disabled.archiveMetadata.inspect(jar);
        check(disabled.archivesInspected == 0 && disabled.bytesRead == 0, "Disabled archive inspection performs no config I/O");
        Map<String,byte[]> driverConfig = entries(); config(driverConfig, "application.properties", "spring.datasource.driver-class-name=org.h2.Driver\n");
        check(scan(root, boot(root, "explicit-driver.jar", driverConfig), true, 1).datasources.size() == 1, "An explicit datasource driver setting is configuration evidence without a JDBC URL");
    }
    static void yamlAndConditional(File root) throws Exception {
        Map<String,byte[]> yaml = entries(); h2(yaml, "2.4.240");
        config(yaml, "application.yml", "spring:\n  datasource:\n    url: 'jdbc:h2:mem:demo;PASSWORD=YAML_URL_SECRET_CANARY'\n    driver-class-name: org.h2.Driver\n    username: YAML_USER_SECRET_CANARY\n    password: YAML_PASSWORD_SECRET_CANARY\n    hikari:\n      maximum-pool-size: 16 # configured ceiling\n");
        AssessmentScan scan = scan(root, boot(root, "yaml.jar", yaml), true, 1);
        check(scan.datasources.size() == 1 && Integer.valueOf(16).equals(((Map)scan.datasources.get(0)).get("configuredMaxPoolSize")), "Simple nested scalar YAML configuration is supported");
        check(Json.stringify(scan.datasources).indexOf("SECRET_CANARY") < 0, "YAML secrets and raw URL must not enter datasource rows");
        String[] rejected = {
            "spring.datasource.url=jdbc:h2:mem:demo\nspring.config.activate.on-profile=prod\n",
            "spring.datasource.url=jdbc:h2:mem:demo\nspring.config.activate.on-cloud-platform=kubernetes\n",
            "spring.datasource.url=jdbc:h2:mem:demo\n#---\nspring.config.activate.on-profile=prod\n",
            "spring.datasource.url=${DATABASE_URL}\nspring.datasource.driver-class-name=${DATABASE_DRIVER}\n",
            "spring.datasource.url=jdbc:h2:mem:demo\nspring.datasource.password=\\uNOTHEX\n"
        };
        for (int i = 0; i < rejected.length; i++) {
            Map<String,byte[]> entries = entries(); config(entries, "application.properties", rejected[i]);
            check(scan(root, boot(root, "rejected-properties-" + i + ".jar", entries), true, 1).datasources.isEmpty(), "Unresolved, conditional, multi-document or malformed properties must not invent effective configuration");
        }
        String[] complex = {
            "spring:\n  datasource: &shared\n    url: jdbc:h2:mem:demo\n",
            "spring:\n  datasource:\n    url: jdbc:h2:mem:demo\n---\nspring:\n  config:\n    activate:\n      on-profile: prod\n",
            "spring:\n  datasource:\n    url: jdbc:h2:mem:demo\n    url: jdbc:h2:mem:other\n",
            "spring:\n  datasource:\n    url: ${DATABASE_URL}\n",
            "spring:\n  datasource:\n    url: [jdbc:h2:mem:demo]\n",
            "spring:\n  datasource:\n    url: jdbc:h2:mem:demo\n   driver-class-name: org.h2.Driver\n",
            "spring:\n  datasource:\n    url: 'jdbc:h2:mem:demo' 'extra'\n"
        };
        for (int i = 0; i < complex.length; i++) {
            Map<String,byte[]> entries = entries(); config(entries, "application.yaml", complex[i]);
            check(scan(root, boot(root, "rejected-yaml-" + i + ".jar", entries), true, 1).datasources.isEmpty(), "Complex, unresolved or ambiguous YAML must be rejected conservatively");
        }
    }
    static void boundsAndDriverEvidence(File root) throws Exception {
        Map<String,byte[]> oversized = entries(); byte[] content = new byte[ArchiveMetadata.METADATA_LIMIT + 1]; Arrays.fill(content, (byte)'A');
        oversized.put(SpringDataSource.PREFIX + "application.properties", content);
        AssessmentScan tooLarge = scan(root, boot(root, "oversized-config.jar", oversized), true, 1);
        check(tooLarge.datasources.isEmpty() && tooLarge.partial && tooLarge.bytesRead <= ArchiveMetadata.READ_LIMIT, "Config size limit must remain bounded and visible in coverage");
        Map<String,byte[]> contents = entries(); h2(contents, "2.4.240"); config(contents, "application.properties", "spring.datasource.url=jdbc:h2:mem:demo\nspring.datasource.hikari.maximum-pool-size=999999\n");
        File jar = boot(root, "bounded.jar", contents);
        AssessmentScan maxArchives = ArchiveMetadataTest.scan(true); maxArchives.archivesInspected = ArchiveMetadata.ARCHIVE_LIMIT; maxArchives.archiveMetadata.inspect(jar);
        check(maxArchives.archivesInspected == ArchiveMetadata.ARCHIVE_LIMIT && maxArchives.bytesRead == 0 && maxArchives.partial, "Exhausted archive-count budget prevents config reads");
        AssessmentScan maxBytes = ArchiveMetadataTest.scan(true); maxBytes.bytesRead = ArchiveMetadata.READ_LIMIT; maxBytes.archiveMetadata.inspect(jar);
        check(maxBytes.bytesRead == ArchiveMetadata.READ_LIMIT && maxBytes.partial, "Exhausted metadata budget prevents config reads");
        AssessmentScan priority = ArchiveMetadataTest.scan(true); priority.archivesInspected = ArchiveMetadata.ARCHIVE_LIMIT - 1;
        Map metadata = priority.archiveMetadata.inspect(jar);
        check(metadata.get("configuredDataSource") instanceof Map && priority.archivesInspected == ArchiveMetadata.ARCHIVE_LIMIT + 1, "Base config and nested libraries are read when only one outer archive slot remains");
        check(((Map)metadata.get("configuredDataSource")).get("configuredMaxPoolSize") == null, "Out-of-range pool setting must be omitted");
        h2(contents, "2.3.232");
        AssessmentScan ambiguous = scan(root, boot(root, "ambiguous-driver.jar", contents), true, 1);
        check(ambiguous.datasources.size() == 1 && ((Map)ambiguous.datasources.get(0)).get("driverVersion") == null, "Multiple matching packaged drivers must not imply one selected driver version");
        check(((Map)ambiguous.datasources.get(0)).get("driverArtifact") == null, "Ambiguous packaged drivers must not select one JAR");
        Map<String,byte[]> nonBoot = entries(); config(nonBoot, "application.properties", "spring.datasource.url=jdbc:h2:mem:demo\n");
        File ordinary = new File(root, "ordinary.jar"); ProcessLaunchTest.write(ordinary, ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Main-Class", "example.Main"), nonBoot));
        check(scan(root, ordinary, true, 1).datasources.isEmpty(), "A non-Boot archive must not be classified from similarly named configuration alone");
    }
    public static void main(String[] args) throws Exception {
        File root = CollectorTest.temp("spring-datasource").getCanonicalFile();
        try { propertiesAndPrivacy(root); discoveryScope(root); yamlAndConditional(root); boundsAndDriverEvidence(root); CollectorTest.manifestAndSnapshot(); }
        finally { CollectorTest.remove(root); }
        System.out.println("SPRING_DATASOURCE_TESTS=PASS configuration privacy properties YAML profiles shared-artifact-associations bounds driver-evidence existing-WebLogic");
    }
}
