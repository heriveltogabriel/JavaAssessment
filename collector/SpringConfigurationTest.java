package assessment;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Synthetic process and archive fixtures: no application launch or network. */
public final class SpringConfigurationTest {
    static void check(boolean value, String message) { CollectorTest.check(value, message); }
    static Map<String,byte[]> content() { return new LinkedHashMap<String,byte[]>(); }
    static void text(Map<String,byte[]> map, String path, String value) throws Exception { map.put(path, value.getBytes("UTF-8")); }
    static File app(File root, String name, Map<String,byte[]> map) throws Exception {
        File jar = new File(root, name);
        ProcessLaunchTest.write(jar, ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Main-Class", "org.springframework.boot.loader.launch.JarLauncher", "Start-Class", "example.Billing", "Spring-Boot-Version", "3.5.0", "Implementation-Title", "Billing"), map));
        return jar.getCanonicalFile();
    }
    static AssessmentScan scan(File root, Properties config, String[]... commands) throws Exception {
        File proc = new File(root, "proc"); int i = 0;
        for (String[] command : commands) ProcessLaunchTest.process(proc, String.valueOf(98770000 + ++i), command);
        AssessmentScan scan = new AssessmentScan(config, "spring-config", "spring-config", AssessmentScan.map("inspectArchives", true, "pauseMillis", 0));
        scan.excluded.clear(); scan.discoverProcesses(proc); return scan;
    }
    static void externalAndProfiles(File root) throws Exception {
        Map<String,byte[]> map = content();
        text(map,"BOOT-INF/classes/application.properties", "spring.datasource.url=jdbc:h2:mem:base\n");
        text(map,"BOOT-INF/classes/application-prod.properties", "spring.datasource.url=jdbc:postgresql://base/db\nspring.datasource.name=Orders\n");
        text(map,"BOOT-INF/classes/META-INF/build-info.properties", "build.name=Billing API\nbuild.version=7.2.1\nbuild.password=BUILD_SECRET_CANARY\n");
        File jar = app(root,"app.jar",map), external = new File(root,"external.properties");
        ProcessLaunchTest.text(external, "spring.datasource.url=jdbc:mysql://db/production?password=URL_SECRET_CANARY\nspring.datasource.password=PASSWORD_SECRET_CANARY\n");
        AssessmentScan scan = scan(root,new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.profiles.active=prod","--spring.config.additional-location="+external.getPath()});
        check(scan.datasources.size()==1,"Only resolved local configuration candidate retained");
        Map ds=(Map)scan.datasources.get(0), application=(Map)scan.applications.get(0);
        check("MySQL".equals(ds.get("databaseType")),"External base overrides packaged profile");
        check("Orders".equals(ds.get("name")),"Configured datasource name retained");
        check("7.2.1".equals(application.get("version")) && "Billing API".equals(application.get("name")),"Build info enriches application");
        check(((List)application.get("activeProfiles")).contains("prod"),"Process profile captured");
        check(!Json.stringify(scan.datasources).contains("CANARY") && !Json.stringify(scan.applications).contains("CANARY"),"No credentials or raw URLs in output");
    }
    static void processIsolation(File root) throws Exception {
        Map<String,byte[]> map=content();
        text(map,"BOOT-INF/classes/application-one.properties","spring.datasource.url=jdbc:postgresql://a/db\n");
        text(map,"BOOT-INF/classes/application-two.properties","spring.datasource.url=jdbc:mysql://b/db\n");
        File jar=app(root,"shared.jar",map);
        AssessmentScan scan=scan(root,new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.profiles.active=one"},new String[]{"java","-jar",jar.getPath(),"--spring.profiles.active=two"});
        check(scan.datasources.size()==2,"Different profiles on same JAR must not share datasource");
        Set products=new HashSet(); for(Object raw:scan.datasources){Map ds=(Map)raw;products.add(ds.get("databaseType"));check(ds.get("pid")!=null,"Each different configuration remains tied to process");}
        check(products.contains("PostgreSQL")&&products.contains("MySQL"),"Both profile products retained");
    }
    static void environmentAndCustom(File root) throws Exception {
        Map<String,byte[]> map=content();
        text(map,"BOOT-INF/classes/application.properties","app.datasource.audit.url=jdbc:oracle:thin:@db:1521/test\napp.datasource.audit.name=Audit\n");
        text(map,"BOOT-INF/classes/application.yml","spring:\n  datasource:\n    url: ${DATABASE_URL}\n");
        File jar=app(root,"custom.jar",map); File proc=new File(root,"proc");
        ProcessLaunchTest.process(proc,"98770001","java","-jar",jar.getPath());
        ProcessLaunchTest.text(new File(proc,"98770001/environ"),"DATABASE_URL=jdbc:postgresql://u:SECRET_CANARY@host/db\u0000UNRELATED_TOKEN=SECRET_CANARY\u0000");
        Properties config=new Properties();config.setProperty("spring.datasourcePrefixes","app.datasource.audit");
        AssessmentScan scan=new AssessmentScan(config,"x","x",AssessmentScan.map("inspectArchives",true,"pauseMillis",0));scan.excluded.clear();scan.discoverProcesses(proc);
        check(scan.datasources.size()==2,"Environment placeholder plus explicit custom datasource prefix");
        check(!Json.stringify(scan.datasources).contains("SECRET_CANARY"),"Environment and URL secrets excluded");
        boolean audit=false;for(Object raw:scan.datasources)if("Audit".equals(((Map)raw).get("name")))audit=true;
        check(audit,"Custom datasource name retained");
    }
    static void uncertainty(File root) throws Exception {
        Map<String,byte[]> map=content();text(map,"BOOT-INF/classes/application.properties","spring.datasource.url=jdbc:h2:mem:x\nspring.config.import=configserver:https://PRIVATE_CANARY\n");
        File jar=app(root,"partial.jar",map);
        AssessmentScan scan=scan(root,new Properties(),new String[]{"java","-jar",jar.getPath()});
        check("partial".equals(((Map)scan.datasources.get(0)).get("configurationResolution")),"Remote imports stay partial; no fetch");
        check(!Json.stringify(scan.datasources).contains("PRIVATE_CANARY"),"Import credentials never serialized");
        Map<String,byte[]> unresolved=content();text(unresolved,"BOOT-INF/classes/application.properties","spring.datasource.url=${UNKNOWN}\n");
        File unknown=app(root,"unknown.jar",unresolved);
        File sub=new File(root,"sub");sub.mkdirs();scan=scan(sub,new Properties(),new String[]{"java","-jar",unknown.getPath()});
        check(scan.datasources.isEmpty(),"Unresolved URL must not create datasource");
    }
    static void exploded(File root) throws Exception {
        File classes=new File(root,"classes");
        ProcessLaunchTest.text(new File(classes,"application.properties"),"spring.datasource.url=jdbc:h2:mem:exploded\n");
        ProcessLaunchTest.text(new File(classes,"META-INF/build-info.properties"),"build.name=Exploded\nbuild.version=2.3.4\n");
        AssessmentScan scan=scan(root,new Properties(),new String[]{"java","-cp",classes.getPath(),"org.springframework.boot.loader.launch.JarLauncher"});
        check(scan.datasources.size()==1 && "2.3.4".equals(((Map)scan.applications.get(0)).get("version")),"Exploded launcher classpath configuration and build metadata");
    }
    static void largeArchive(File root) throws Exception {
        // Sparse leading padding is skipped by metadata reads; valid ZIP offsets are adjusted.
        Map<String,byte[]> map=content();text(map,"BOOT-INF/classes/application.properties","spring.datasource.url=jdbc:h2:mem:large\n");
        byte[] bytes=ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Main-Class","org.springframework.boot.loader.JarLauncher","Spring-Boot-Version","2.7.0"),map);
        int eocd=bytes.length-22; long central=ArchiveMetadata.u32(bytes,eocd+16); int offset=(int)central;
        final int padding=129*1024*1024;
        while(offset<eocd){long local=ArchiveMetadata.u32(bytes,offset+42);put32(bytes,offset+42,local+padding);offset+=46+ArchiveMetadata.u16(bytes,offset+28)+ArchiveMetadata.u16(bytes,offset+30)+ArchiveMetadata.u16(bytes,offset+32);}
        put32(bytes,eocd+16,central+padding);
        File file=new File(root,"large.jar");RandomAccessFile out=new RandomAccessFile(file,"rw");try{out.seek(padding);out.write(bytes);}finally{out.close();}
        AssessmentScan scan=scan(root,new Properties(),new String[]{"java","-jar",file.getPath()});
        check(scan.datasources.size()==1,"Archives above former 128MiB limit inspected selectively");
        check(scan.bytesRead<1024*1024,"Large file does not consume full content read budget");
    }
    static void mixedAndUnknown(File root) throws Exception {
        Map<String,byte[]> map = content();
        text(map,"BOOT-INF/classes/application.properties","spring.application.name=Mixed\n");
        text(map,"BOOT-INF/classes/application.yml","spring:\n  datasource:\n    url: jdbc:sqlite:/data/file.db\n");
        File jar = app(root,"mixed.jar",map);
        AssessmentScan scan=scan(root,new Properties(),new String[]{"java","-jar",jar.getPath()});
        check(scan.datasources.size()==1 && ((Map)scan.datasources.get(0)).get("databaseType")==null,"Mixed property formats retain unknown JDBC product as configuration evidence");
    }
    static void put32(byte[] bytes,int at,long v){for(int i=0;i<4;i++)bytes[at+i]=(byte)(v>>>(8*i));}
    public static void main(String[] args)throws Exception{
        File root=CollectorTest.temp("spring-configuration");
        try{String[] names={"external","processes","environment","uncertainty","exploded","large","mixed"};for(String name:names)new File(root,name).mkdirs();externalAndProfiles(new File(root,names[0]));processIsolation(new File(root,names[1]));environmentAndCustom(new File(root,names[2]));uncertainty(new File(root,names[3]));exploded(new File(root,names[4]));largeArchive(new File(root,names[5]));mixedAndUnknown(new File(root,names[6]));System.out.println("Spring configuration checks passed");}finally{CollectorTest.remove(root);}
    }
}
