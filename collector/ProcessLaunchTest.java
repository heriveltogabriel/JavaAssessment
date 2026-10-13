package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/** Isolated launcher/proc/archive fixtures. No application is started or attached. */
public final class ProcessLaunchTest {
    static void check(boolean test, String message) { if (!test) throw new AssertionError(message); }
    static void write(File file, byte[] value) throws Exception {
        file.getParentFile().mkdirs(); OutputStream out = new FileOutputStream(file); try { out.write(value); } finally { out.close(); }
    }
    static void text(File file, String value) throws Exception { write(file, value.getBytes("UTF-8")); }
    static byte[] jar(Map<String,String> attributes, Map<String,byte[]> entries) throws Exception {
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        for (Map.Entry<String,String> entry : attributes.entrySet()) manifest.getMainAttributes().putValue(entry.getKey(), entry.getValue());
        ByteArrayOutputStream out = new ByteArrayOutputStream(); JarOutputStream jar = new JarOutputStream(out, manifest);
        for (Map.Entry<String,byte[]> entry : entries.entrySet()) { jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue()); jar.closeEntry(); }
        jar.close(); return out.toByteArray();
    }
    static Map<String,String> attrs(String... pairs) { Map<String,String> map = new LinkedHashMap<String,String>(); for(int i=0;i<pairs.length;i+=2)map.put(pairs[i],pairs[i+1]); return map; }
    static Map<String,byte[]> entries() { return new LinkedHashMap<String,byte[]>(); }
    static AssessmentScan scanner() { AssessmentScan scan = new AssessmentScan(new Properties(), "fixture-host", "fixture-host", AssessmentScan.map("inspectArchives", Boolean.TRUE, "maxFiles", 1000, "maxDurationSeconds", 120, "pauseMillis", 0)); scan.excluded.clear(); return scan; }
    static void process(File proc, String pid, String... args) throws Exception {
        StringBuilder command = new StringBuilder(); for (String arg : args) command.append(arg).append('\u0000');
        File dir = new File(proc,pid); text(new File(dir,"cmdline"), command.toString()); text(new File(dir,"comm"), "java\n"); text(new File(dir,"status"), "Name:\tjava\nUid:\t985\t985\t985\t985\n");
        // Deliberately no readable exe/cwd: matches a collector inspecting another service user's process.
    }
    static void parser() throws Exception {
        ProcessLaunch jar = ProcessLaunch.parse(new String[]{"java","-Dpassword=PRIVATE_CANARY","-jar","/opt/app.jar","--password","PRIVATE_CANARY","org.jboss.as.standalone"},null);
        check(jar.mainClass==null && jar.serverType==null && "/opt/app.jar".equals(jar.jarPath),"Application arguments must never become a main class/server");
        ProcessLaunch main = ProcessLaunch.parse(new String[]{"java","--add-opens","java.base/java.lang=ALL-UNNAMED","--class-path=/opt/lib/a.jar:/opt/lib/b.jar","com.example.Main","weblogic.Server"},null);
        check("com.example.Main".equals(main.mainClass) && main.classpath.size()==2 && main.serverType==null,"Java 21 launcher flags and main boundary");
        ProcessLaunch tomcat = ProcessLaunch.parse(new String[]{"java","-Dcatalina.base=/srv/tomcat-one","-Dcatalina.home=/opt/tomcat","-cp","/opt/tomcat/bin/bootstrap.jar","org.apache.catalina.startup.Bootstrap","start"},null);
        check("Tomcat".equals(tomcat.serverType) && "/srv/tomcat-one".equals(tomcat.serverBase),"Tomcat home/base recognized independently");
        ProcessLaunch jboss = ProcessLaunch.parse(new String[]{"java","-jar","/opt/wildfly/jboss-modules.jar","-mp","/opt/wildfly/modules","org.jboss.as.standalone","-Djboss.home.dir=/opt/wildfly","-Djboss.server.base.dir=/srv/wildfly","--password","PRIVATE_CANARY"},null);
        check("JBoss".equals(jboss.serverType) && "org.jboss.as.standalone".equals(jboss.moduleName) && jboss.modulePaths.size()==1 && "/srv/wildfly".equals(jboss.serverBase),"JBoss module launcher and allowlisted identification after module");
        check(ProcessLaunch.parse(new String[]{"java","-jar","/opt/jboss-modules.jar","com.example.module"},null).serverType==null,"Modules library alone is not a JBoss server");
        for(String launch:new String[]{"org.springframework.boot.loader.JarLauncher","org.springframework.boot.loader.launch.JarLauncher","org.springframework.boot.loader.PropertiesLauncher"})check("Spring Boot".equals(ProcessLaunch.parse(new String[]{"java",launch,"PRIVATE_CANARY"},null).applicationType),"Boot launcher recognized");
        check(ProcessLaunch.parse(new String[]{"java","-jar","app.jar"},null).jarPath==null,"Unknown process cwd must not use collector cwd");
        check("/srv/app/app.jar".equals(ProcessLaunch.parse(new String[]{"java","-jar","app.jar"},new File("/srv/app")).jarPath),"Relative launch jar resolved against process cwd");
        check(ProcessLaunch.parse(new String[]{"java","@secret.options","PRIVATE_CANARY"},null).mainClass==null,"Argument files remain opaque");
    }
    static void discovery() throws Exception {
        File root=CollectorTest.temp("launch-fixtures");
        try {
            File boot=new File(root,"spring/app.jar"),proc=new File(root,"proc");
            Map<String,byte[]> nested=entries(); nested.put("BOOT-INF/lib/postgresql-42.7.4.jar",jar(attrs("Implementation-Title","PostgreSQL JDBC Driver","Implementation-Version","42.7.4"),entries()));
            write(boot,jar(attrs("Main-Class","org.springframework.boot.loader.launch.JarLauncher","Start-Class","com.example.Demo","Implementation-Title","springdemo","Implementation-Version","1.0.0","Spring-Boot-Version","3.3.4"),nested));
            process(proc,"98765001","java","-jar",boot.getCanonicalPath(),"--password","PRIVATE_CANARY");
            process(proc,"98765002","java","-jar",boot.getCanonicalPath(),"--password","OTHER_PRIVATE_CANARY");
            AssessmentScan scan=scanner(); scan.discoverProcesses(proc);
            check(scan.processes.size()==2 && scan.applications.size()==2,"Readable cmdline retained despite inaccessible exe/cwd");
            Map app=(Map)scan.applications.get(0),first=(Map)scan.processes.get(0);
            check("Spring Boot".equals(app.get("applicationType")) && "PROCESS_RUNNING".equals(app.get("state")) && "3.3.4".equals(app.get("frameworkVersion")),"Boot application metadata joined to live process");
            check("com.example.Demo".equals(app.get("mainClass")) && first.get("javaHome")==null && Long.valueOf(985).equals(first.get("uid")),"Main class and uid safe; inaccessible Java home stays unknown");
            check(scan.libraries.size()==1 && scan.datasources.isEmpty(),"Nested JDBC is deduplicated library evidence, never a datasource");
            Map library=(Map)scan.libraries.get(0);
            check("found".equals(library.get("usage")) && ((List)library.get("associations")).size()==2 && !library.containsKey("applicationId"),"Shared archive retains two associations without claiming one owner");
            check(scan.partial && scan.warnings.size()<5,"Process permission warnings summarized");
            String saved=Json.stringify(AssessmentScan.map("processes",scan.processes,"applications",scan.applications,"libraries",scan.libraries,"warnings",scan.warnings));
            check(saved.indexOf("PRIVATE_CANARY")<0 && saved.indexOf("--password")<0,"Secrets and application arguments must not enter inventory");
            AssessmentScan excluded=scanner(); excluded.excluded.add(root.getCanonicalPath()+"/spring"); excluded.discoverProcesses(proc);
            check(excluded.libraries.isEmpty() && ((Map)excluded.applications.get(0)).get("applicationType")==null,"Explicit exclusions override process-linked artifact inspection");
        } finally { CollectorTest.remove(root); }
    }
    static void serverAndDeployment() throws Exception {
        File root=CollectorTest.temp("server-fixtures");
        try {
            File home=new File(root,"tomcat"),proc=new File(root,"proc");
            Map<String,byte[]> info=entries(); info.put("org/apache/catalina/util/ServerInfo.properties","server.info=Apache Tomcat/9.0.122\nserver.number=9.0.122.0\n".getBytes("UTF-8"));
            write(new File(home,"lib/catalina.jar"),jar(attrs("Implementation-Title","Apache Tomcat","Implementation-Version","9.0.122"),info));
            write(new File(home,"webapps/demo.war"),jar(attrs("Implementation-Title","Demo WAR","Implementation-Version","1.2"),entries()));
            process(proc,"98765003","java","-Dcatalina.home="+home.getCanonicalPath(),"-Dcatalina.base="+home.getCanonicalPath(),"org.apache.catalina.startup.Bootstrap","start");
            AssessmentScan scan=scanner(); scan.discoverProcesses(proc);
            Map server=(Map)scan.servers.values().iterator().next();
            check("Tomcat".equals(server.get("type")) && String.valueOf(server.get("version")).startsWith("9.0.122") && "PROCESS_RUNNING".equals(server.get("state")),"Tomcat runtime enriched from associated installation");
            Map war=null;for(Object object:scan.applications){Map row=(Map)object;if("WAR".equals(row.get("type")))war=row;}
            check(war!=null && war.get("state")==null && server.get("name").equals(war.get("serverName")),"WAR file is own application row, not falsely running/deployed");
            int prior=scan.servers.size();scan.walk(home,0);check(scan.servers.size()==prior,"Process-directed artifact is not duplicated as a second server during broad walk");
        } finally { CollectorTest.remove(root); }
    }
    static void limits() throws Exception {
        File root=CollectorTest.temp("runtime-skip");
        try {
            text(new File(root,"bin/java"),"not executed");text(new File(root,"bin/javac"),"not executed");text(new File(root,"release"),"JAVA_VERSION=\"17.0.1\"\n");
            for(int i=0;i<150;i++)text(new File(root,"legal/entry"+i),"metadata");
            AssessmentScan scan=scanner();scan.walk(root,0);
            check(scan.runtimes.size()==1 && scan.filesVisited==1,"Known Java home should not exhaust file budget on JDK internals");
            for(int i=0;i<40;i++)scan.note("informational "+i);scan.warn("IMPORTANT_BUDGET_LIMIT");check(scan.warnings.contains("IMPORTANT_BUDGET_LIMIT"),"Critical coverage cause survives informational warning saturation");
        } finally { CollectorTest.remove(root); }
    }
    static void archiveFileEvidenceAtBudget() throws Exception {
        File root=CollectorTest.temp("archive-budget");
        try {
            for(int i=0;i<260;i++)new File(root,String.format("a-%03d.jar",Integer.valueOf(i))).createNewFile();
            File war=new File(root,"z-demo.war"),ear=new File(root,"z-suite.ear");
            write(war,jar(attrs("Implementation-Version","9.9"),entries()));
            write(ear,jar(attrs("Implementation-Version","8.8"),entries()));
            AssessmentScan scan=scanner();scan.archivesInspected=40;scan.walk(root,0);
            check(scan.deferredArchives.size()==256 && scan.partial,"Fixture exhausts generic archive candidate budget");
            check(scan.applications.size()==2,"WAR/EAR file findings survive exhausted candidate and inspection budgets");
            for(Object value:scan.applications){Map app=(Map)value;check(app.get("state")==null && app.get("version")==null,"File evidence must not claim deployed state or an uninspected version");}
            check(scan.bytesRead==0 && scan.archivesInspected==40,"File evidence does not consume archive inspection or metadata bytes");
            scan.inspectJar(war,"z-demo.war");scan.inspectJar(ear,"z-suite.ear");
            check(scan.applications.size()==2 && scan.archivesInspected==40,"Optional enrichment deduplicates existing file findings and keeps archive cap");
        } finally { CollectorTest.remove(root); }
    }
    public static void main(String[] args) throws Exception {
        parser();discovery();serverAndDeployment();limits();archiveFileEvidenceAtBudget();
        System.out.println("PROCESS_LAUNCH_TESTS=PASS parser secrets proc-fallback Boot JDBC associations Tomcat WAR exclusions JDK-budget archive-file-evidence");
    }
}
