package assessment;

import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;
import static assessment.BetaServerTest.*;

public final class InventoryArchiveHttpTest {
    static final String ENDPOINT="/api/admin/inventory-export";
    static void cleanTemps()throws Exception{try(var files=Files.list(root.resolve("data"))){check(files.noneMatch(p->p.getFileName().toString().startsWith("inventory-archive-")),"temporary archive removed");}}
    public static void main(String[] args)throws Exception{
        root=Files.createTempDirectory("inventory-archive-http-");Files.createDirectories(root.resolve("conf"));
        Properties c=new Properties();c.setProperty("seedLegacyHost","false");c.setProperty("adminSalt","fixture-salt");c.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));c.setProperty("hostId","unused-fixture");c.setProperty("agentToken","unused-fixture");c.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){c.store(out,"fixture");}
        try{
            start();InventoryArchiveTest.seed(app.store);
            check(raw("GET",ENDPOINT,null,false,null).statusCode()==401,"anonymous blocked");
            check(raw("POST",ENDPOINT,map(),true,null).statusCode()==405,"GET only");
            var response=raw("GET",ENDPOINT+"?label=Cliente+A&environmentId=not-a-real-filter",null,true,null);
            check(response.statusCode()==200,"admin download");check(response.headers().firstValue("Content-Type").orElse("").equals("application/zip"),"ZIP response");
            check(response.headers().firstValue("Content-Disposition").orElse("").matches("attachment; filename=\"java-assessment-inventario-[0-9-]+\\.zip\""),"safe filename");
            check(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),"no cache");
            var entries=InventoryArchiveTest.entries(response.body());check(entries.get("hosts.json").contains("ol07"),"whole inventory, not current filters");check(!String.join("",entries.values()).contains("HOST_TOKEN"),"no agent credentials");cleanTemps();
            check(raw("GET",ENDPOINT+"?label="+"x".repeat(121),null,true,null).statusCode()==400,"bounded label");cleanTemps();
            var field=AssessmentServer.class.getDeclaredField("exportSlot");field.setAccessible(true);var slot=(java.util.concurrent.Semaphore)field.get(app);slot.acquire();
            try{check(raw("GET",ENDPOINT,null,true,null).statusCode()==429,"shared export concurrency bound");}finally{slot.release();}
            Map<String,Object> nested=map("applicationId","applications-a");for(int i=0;i<15;i++)nested=map("associations",List.of(nested));
            Map<String,Object> invalid=map("id","invalid","hostId","host-a","associations",List.of(nested));app.store.putInventory("datasources","host-a",new ArrayList<>(List.of(invalid)));
            check(raw("GET",ENDPOINT,null,true,null).statusCode()==400,"failed generation does not download partial ZIP");cleanTemps();
            InventoryArchiveTest.seed(app.store);check(raw("GET",ENDPOINT,null,true,null).statusCode()==200,"slot released after generation failure");cleanTemps();
            String adminCookie=cookie,adminCsrf=csrf;
            var reader=ok("POST","/api/admin/users",map("name","Reader Fixture","email","reader@example.com","role","READER","adminPassword",TestAccounts.PASSWORD),true,null,201);
            var user=obj(reader.get("user"));app.auth.changePassword((String)user.get("id"),app.auth.find((String)user.get("id")).revision(),(String)reader.get("temporaryPassword"),"reader-personal-password-1234");
            var login=raw("POST","/api/login",map("email","reader@example.com","password","reader-personal-password-1234"),false,null);check(login.statusCode()==200,"reader login");cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            check(raw("GET",ENDPOINT,null,true,null).statusCode()==403,"reader blocked");cleanTemps();cookie=adminCookie;csrf=adminCsrf;
        }finally{stop();try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("InventoryArchiveHttpTest passed");
    }
}
