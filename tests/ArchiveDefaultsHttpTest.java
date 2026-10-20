package assessment;
import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;
import static assessment.BetaServerTest.*;

/** Archive defaults, inherited settings and persisted explicit exceptions through real HTTP. */
public final class ArchiveDefaultsHttpTest {
    static Map<String,Object> register(String env,String name)throws Exception {
        return ok("POST","/api/agent/register",registration(name),false,packageCode(env).getProperty("enrollmentToken"),200);
    }
    static boolean inspection(Map<String,Object> identity)throws Exception {
        return Boolean.TRUE.equals(obj(ok("GET","/api/agent/settings?hostId="+identity.get("hostId"),null,false,(String)identity.get("agentToken"),200).get("settings")).get("inspectArchives"));
    }
    public static void main(String[] args)throws Exception {
        root=Files.createTempDirectory("archive-defaults-");Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));
        Properties p=new Properties();p.setProperty("seedLegacyHost","false");p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("agentToken","fixture-unused");p.setProperty("hostId","fixture-unused");p.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"isolated fixture");}
        VersionedCsvHttpTest.packageZip(AgentTransport.VERSION);
        try {
            start();
            check(Boolean.TRUE.equals(obj(app.store.metadata("settings")).get("inspectArchives")),"new installation inspects archives by default");
            check(List.of("/usr/java","/usr/lib/jvm","/u01","/etc/config","/prd1").equals(obj(app.store.metadata("settings")).get("includePaths")),"fresh scan defaults");
            check(List.of("/proc","/sys","/dev","/tmp","/opt").equals(obj(app.store.metadata("settings")).get("excludePaths")),"fresh exclusions");
            ok("PUT","/api/settings",map("includePaths",List.of("/u01","/u01/")),true,null,400);
            ok("PUT","/api/settings",map("includePaths",List.of("/opt")),true,null,400);
            check(List.of("/etc/config").equals(obj(app.store.metadata("settings")).get("appConfigPaths")),"new app config default");
            ok("PUT","/api/settings",map("appConfigPaths",List.of("/")),true,null,400);
            String env=(String)ok("POST","/api/environments",map("name","Inherited"),true,null,201).get("id");
            String disabled=(String)ok("POST","/api/environments",map("name","Disabled","settings",map("inspectArchives",false,"appConfigPaths",List.of("/srv/config"))),true,null,201).get("id");
            Map<String,Object> inherited=register(env,"archive-inherited-01"), exception=register(env,"archive-exception-01"), envOff=register(disabled,"archive-envoff-001");
            ok("PUT","/api/hosts/settings",map("hostId",exception.get("hostId"),"settings",map("inspectArchives",false,"appConfigPaths",List.of())),true,null,200);
            check(inspection(inherited)&&!inspection(exception)&&!inspection(envOff),"new hosts inherit while explicit exceptions win");
            check(List.of("/srv/config").equals(obj(ok("GET","/api/agent/settings?hostId="+envOff.get("hostId"),null,false,(String)envOff.get("agentToken"),200).get("settings")).get("appConfigPaths")),"environment application paths inherited");
            check(List.of().equals(obj(ok("GET","/api/agent/settings?hostId="+exception.get("hostId"),null,false,(String)exception.get("agentToken"),200).get("settings")).get("appConfigPaths")),"empty machine application paths disable inherited discovery");
            Map<String,Object> prior=new LinkedHashMap<>(obj(app.store.metadata("settings")));prior.put("inspectArchives",false);prior.remove("appConfigPaths");prior.put("includePaths",List.of("/opt"));prior.put("excludePaths",List.of("/proc","/sys","/dev","/tmp"));prior.put("maxFiles",4321);
            app.store.metadata("settings",prior);app.store.metadata("migration.archiveInspectionDefault.v1",null);
            Object schedule=app.store.metadata("scheduleRevision");String envBefore=Json.stringify(app.store.get("environments",disabled)),hostBefore=Json.stringify(app.store.get("hosts",(String)exception.get("hostId")));
            stop();start();
            check(List.of("/opt").equals(obj(app.store.metadata("settings")).get("includePaths")),"saved paths preserved");
            check(List.of("/etc/config").equals(obj(ok("GET","/api/agent/settings?hostId="+inherited.get("hostId"),null,false,(String)inherited.get("agentToken"),200).get("settings")).get("appConfigPaths")),"old agent settings gain runtime fallback without migrating saved paths");
            check(inspection(inherited)&&!inspection(exception)&&!inspection(envOff),"existing inheritance migrates without rewriting explicit false");
            check(envBefore.equals(Json.stringify(app.store.get("environments",disabled)))&&hostBefore.equals(Json.stringify(app.store.get("hosts",(String)exception.get("hostId")))),"saved environment and host overrides unchanged");
            check(((Number)obj(app.store.metadata("settings")).get("maxFiles")).intValue()==4321&&schedule.equals(app.store.metadata("scheduleRevision")),"other settings and schedule revisions unchanged");
            check(app.store.scalar("SELECT count(*) FROM jobs")==0,"migration does not schedule collections");
            ok("PUT","/api/settings",map("inspectArchives",false),true,null,200);stop();start();
            check(!inspection(inherited),"later deliberate global false survives restart");
            ok("PUT","/api/settings",map("inspectArchives",true),true,null,200);
            ok("PUT","/api/hosts/settings",map("hostId",exception.get("hostId"),"settings",map(),"replaceOverrides",true),true,null,200);
            check(inspection(exception)&&!inspection(envOff),"reset resumes inheritance without altering another environment");
            System.out.println("ARCHIVE_DEFAULTS_HTTP=PASS fresh migration inheritance exceptions restart reset no-extra-jobs");
        } finally {stop();try(var paths=Files.walk(root)){for(Path f:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}
    }
}
