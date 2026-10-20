package assessment;

import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;
import static assessment.BetaServerTest.*;

/** Saved pre-upgrade path lists remain readable; new writes validate effective scope. */
public final class SettingsPathsHttpTest {
    public static void main(String[] args)throws Exception {
        root=Files.createTempDirectory("settings-paths-");
        Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));
        Properties p=new Properties();
        p.setProperty("seedLegacyHost","false");p.setProperty("adminSalt","fixture-salt");
        p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));
        p.setProperty("agentToken","fixture-unused");p.setProperty("hostId","fixture-unused");
        p.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"isolated fixture");}
        VersionedCsvHttpTest.packageZip(AgentTransport.VERSION);
        try {
            start();
            Map<String,Object> fresh=new LinkedHashMap<>(obj(app.store.metadata("settings")));
            check(List.of("/usr/java","/usr/lib/jvm","/u01","/etc/config","/prd1").equals(fresh.get("includePaths")),"fresh include paths");
            String env=(String)ok("POST","/api/environments",map("name","Paths"),true,null,201).get("id");
            Map<String,Object> agent=ArchiveDefaultsHttpTest.register(env,"paths-fixture-001");
            String host=(String)agent.get("hostId"),token=(String)agent.get("agentToken");
            Map<String,Object> saved=new LinkedHashMap<>(fresh);
            saved.put("includePaths",List.of("/opt","/opt/"));saved.put("excludePaths",List.of("/opt"));
            saved.remove("appConfigPaths");app.store.metadata("settings",saved);
            String before=Json.stringify(saved);
            for(String path:List.of("/api/state?view=hosts","/api/state?view=settings&environmentId="+env,"/api/state?view=settings&hostId="+host))
                ok("GET",path,null,true,null,200);
            Map<String,Object> effective=obj(ok("GET","/api/agent/settings?hostId="+host,null,false,token,200).get("settings"));
            check(List.of("/opt","/opt/").equals(effective.get("includePaths")),"read preserves saved duplicate paths");
            check(List.of("/etc/config").equals(effective.get("appConfigPaths")),"read adds fallback without migration");
            check(before.equals(Json.stringify(app.store.metadata("settings"))),"GET never rewrites saved paths");
            app.store.metadata("settings",fresh);
            ok("PUT","/api/settings",map("includePaths",List.of("/u01","/u01/")),true,null,400);
            ok("PUT","/api/settings",map("includePaths",List.of("/opt")),true,null,400);
            ok("POST","/api/environments",map("name","Invalid duplicate","settings",map("includePaths",List.of("/u01","/u01/"))),true,null,400);
            ok("PUT","/api/hosts/settings",map("hostId",host,"settings",map("includePaths",List.of("/opt"))),true,null,400);
            ok("PUT","/api/hosts/settings",map("hostId",host,"settings",map("includePaths",List.of("/srv/apps"),"excludePaths",List.of("/proc","/tmp"))),true,null,200);
            check(List.of("/srv/apps").equals(obj(ok("GET","/api/agent/settings?hostId="+host,null,false,token,200).get("settings")).get("includePaths")),"valid host override wins");
            System.out.println("SETTINGS_PATHS_HTTP=PASS fresh legacy-read no-migration duplicate conflict host-effective");
        } finally {
            stop();try(var paths=Files.walk(root)){for(Path f:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}
        }
    }
}
