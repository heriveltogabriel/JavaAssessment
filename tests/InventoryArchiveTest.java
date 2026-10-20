package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

public final class InventoryArchiveTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    static Map<String,String> entries(byte[] archive)throws Exception{
        Map<String,String> result=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)){
            ZipEntry e;while((e=zip.getNextEntry())!=null){check(result.put(e.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8))==null,"unique ZIP entries");}
        }return result;
    }
    static void seed(InventoryStore store){store.transaction(()->{
        store.put("environments",map("id","env-a","name","Produção","description","ENV_DESCRIPTION_SECRET","settings",map("password","ENV_PASSWORD")));
        store.put("hosts",map("id","host-a","environmentId","env-a","hostname","ol07","distribution","Oracle Linux 7","ipAddresses",List.of("10.0.0.2"),"agentToken","HOST_TOKEN","collectorVersion","2.1.14","lastSeen","2026-10-20T10:00:00Z","kernelVersion","3.10","coverage",map("status","partial","warnings",List.of("Metadados limitados; password=NOTE_SECRET"),"stats",map("filesVisited",50000,"durationMillis",12345,"secret","STATS_SECRET")),"disks",List.of(map("mount","/u01","totalBytes",123456,"password","DISK_SECRET")),"settings",map("secret","HOST_SETTING_SECRET")));
        store.put("identities",map("hostId","host-a","agentToken","IDENTITY_SECRET","status","active"));
        store.put("jobs",map("id","job-a","hostId","host-a","status","SUCCEEDED","summary","JOB_SECRET"));
        var candidate=map("path","/domain/lib/ojdbc6.jar","version","11.2.0.3.0","versionSource","Manifesto","family","Oracle","open",true,"onClasspath",true,"loaded",false,"password","CANDIDATE_PASSWORD");
        var decision=map("driverResolution","INCOMPLETE","driverCandidates",List.of(candidate),"driverResolutionReason","Classpath truncado","token","DECISION_TOKEN");
        for(String kind:InventoryArchive.COLLECTIONS.subList(2,8)){
            var row=map("id",kind+"-a","hostId","host-a","name","JDBC de Produção","version","17.0.1","path","/opt/java","driverName","oracle.jdbc.OracleDriver","driverCandidates",List.of(candidate),"driverResolution","INCOMPLETE","databaseHost","db.internal","databaseEndpoints",List.of(map("host","db.internal","port",1521,"service","sales","password","DB_PASSWORD")),"associations",List.of(map("applicationId","applications-a","serverId","servers-a","pid",42,"driverResult",decision,"secret","ASSOC_SECRET")),"commandLine","COMMAND_SECRET","environment",map("PASSWORD","ENV_SECRET"),"jdbcUrl","jdbc:oracle:thin:user/URL_SECRET@db:1521/sales","evidence","configuration; password=TEXT_SECRET; token=TEXT_TOKEN; https://user:URL_AUTH@db/path", "unknownFutureSecret","FUTURE_SECRET");
            store.putInventory(kind,"host-a",new ArrayList<>(List.of(row)));
        }return null;
    });}
    static byte[] archive(InventoryStore store)throws Exception{var out=new ByteArrayOutputStream();InventoryArchive.write(out,store,"Cliente A");return out.toByteArray();}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("inventory-archive-");
        try(InventoryStore store=new InventoryStore(root.toFile(),map(),"","")){
            var empty=entries(archive(store));check(empty.size()==10,"eight collections plus manifest and guide");check(empty.get("hosts.json").trim().equals("[]"),"empty fleet supported");
            seed(store);var files=entries(archive(store));String all=String.join("\n",files.values());
            for(String secret:List.of("ENV_DESCRIPTION_SECRET","ENV_PASSWORD","HOST_TOKEN","HOST_SETTING_SECRET","IDENTITY_SECRET","JOB_SECRET","CANDIDATE_PASSWORD","DECISION_TOKEN","DB_PASSWORD","ASSOC_SECRET","COMMAND_SECRET","ENV_SECRET","URL_SECRET","TEXT_SECRET","TEXT_TOKEN","URL_AUTH","FUTURE_SECRET","NOTE_SECRET","STATS_SECRET","DISK_SECRET"))check(!all.contains(secret),"omitted "+secret);
            var manifest=obj(Json.parse(files.get("manifest.json")));check(manifest.get("formatVersion").equals(1L),"format is versioned");check(manifest.get("label").equals("Cliente A"),"source label");
            var counts=obj(manifest.get("counts"));for(String kind:InventoryArchive.COLLECTIONS)check(counts.get(kind).equals(1L),"complete count "+kind);
            var ds=obj(((List<?>)Json.parse(files.get("datasources.json"))).get(0));
            check(ds.get("hostId").equals("host-a"),"host relationship");var association=obj(((List<?>)ds.get("associations")).get(0));check(association.get("applicationId").equals("applications-a"),"app relationship");
            var decision=obj(association.get("driverResult"));check(decision.get("driverResolution").equals("INCOMPLETE"),"per-PID uncertainty retained");var candidate=obj(((List<?>)decision.get("driverCandidates")).get(0));check(candidate.get("version").equals("11.2.0.3.0")&&candidate.get("path").equals("/domain/lib/ojdbc6.jar"),"driver jar and version retained");check(candidate.get("onClasspath").equals(true),"candidate evidence retained");
            check(all.contains("filesVisited")&&all.contains("50000")&&all.contains("Metadados limitados")&&all.contains("totalBytes"),"coverage and hardware retained");
            check(all.contains("db.internal")&&all.contains("Oracle Linux 7"),"technical infrastructure retained");
            for(long[] limits:List.of(new long[]{50,100},new long[]{999999,2})){
                try{InventoryArchive.write(new ByteArrayOutputStream(),store,"",limits[0],limits[1]);throw new AssertionError("limit accepted");}catch(InventoryArchive.Failure expected){}
            }
            for(String label:List.of("x".repeat(121),"line\nbreak")){try{InventoryArchive.label(label);throw new AssertionError("invalid label");}catch(InventoryArchive.Failure expected){}}
            for(String secretText:List.of("{\"password\":\"QUOTED_SECRET\"}","Authorization: Basic QUOTED_SECRET","-----BEGIN PRIVATE KEY-----\nQUOTED_SECRET\n-----END PRIVATE KEY-----","username=QUOTED_SECRET"))check(!InventoryArchive.scrub(secretText).contains("QUOTED_SECRET"),"credential text redacted");
            check(!InventoryArchive.scrub("jdbc:oracle:thin:user/ORACLE_SECRET@db:1521/sales").contains("ORACLE_SECRET"),"Oracle URL redacted");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("InventoryArchiveTest passed");
    }
}
