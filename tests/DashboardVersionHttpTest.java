package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Real loopback HTTP/SQLite release checks. Never accesses a configured dashboard. */
public final class DashboardVersionHttpTest {
    static final HttpClient CLIENT=HttpClient.newHttpClient();
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static Map<String,Object> parsed(String body){return (Map<String,Object>)Json.parse(body);}
    static Map<String,Object> state(String base,String cookie,String query)throws Exception{
        var response=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/state?"+query)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==200,"state HTTP success");return parsed(response.body());
    }
    @SuppressWarnings("unchecked") static void notice(Map<String,Object> state,long count){
        var notice=(Map<String,Object>)state.get("agentUpdateNotice");
        check(notice!=null&&DashboardVersion.VERSION.equals(notice.get("version")),"notice targets running release");
        check(((Number)notice.get("outdatedCount")).longValue()==count,"global outdated count expected "+count+", got "+notice);
    }
    static void seedNotice(AssessmentServer app){
        app.store.put("environments",map("id","notice-env","name","Notice fixture","settings",map()));
        String[] parts=DashboardVersion.VERSION.split("\\.");String future=parts[0]+"."+parts[1]+"."+(Integer.parseInt(parts[2])+10);
        for(String[] item:new String[][]{{"old-9","1.5.9"},{"old-10","1.5.10"},{"old-13","1.5.13"},{"old-major","2.0.0"},{"current",DashboardVersion.VERSION},{"future",future},{"unknown",""},{"invalid","2.0.3<script>"},{"revoked","1.5.10"},{"removed","1.5.10"},{"no-identity","1.5.10"}}){
            String id=item[0];app.store.put("hosts",map("id",id,"hostname",id,"environmentId",id.equals("old-major")?"notice-env":"","collectorVersion",item[1],"agentLifecycle",id.equals("removed")?"uninstalled":"installed","lastSeen","2020-01-01T00:00:00Z"));
            if(!id.equals("no-identity"))app.store.put("identities",map("hostId",id,"installationId","notice-"+id,"agentToken","notice-token-"+id,"status",id.equals("revoked")?"revoked":"active"));
        }
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("assessment-release-http-");Files.createDirectories(root.resolve("conf"));
        Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));
        p.setProperty("agentToken","fixture-token");p.setProperty("hostId","fixture-host");p.setProperty("seedLegacyHost","false");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Isolated version test");}
        try {
            check(DashboardVersion.VERSION.matches("[0-9]+\\.[0-9]+\\.[0-9]+"),"complete semantic version without beta");
            if(args.length>0)check(DashboardVersion.VERSION.equals(args[0]),"compiled server must match reserved package version");
            java.time.OffsetDateTime.parse(DashboardVersion.BUILT_AT);
            for(int restart=0;restart<2;restart++)try(AssessmentServer app=new AssessmentServer(root.toFile())) {
                HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(2);
                http.createContext("/",app);http.setExecutor(pool);http.start();String base="http://127.0.0.1:"+http.getAddress().getPort();
                try {
                    var health=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString());
                    var data=parsed(health.body());check(health.statusCode()==200&&"ok".equals(data.get("status")),"healthy server");
                    check(DashboardVersion.VERSION.equals(data.get("version")),"health exact version on restart "+restart);
                    check(DashboardVersion.BUILT_AT.equals(data.get("builtAt")),"health exact build date");
                    check("no-store".equals(health.headers().firstValue("Cache-Control").orElse("")),"version cannot be cached");
                    var login=TestAccounts.login(base);
                    check(login.statusCode()==200,"login");String cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
                    var export=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/export")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
                    check(export.statusCode()==200&&DashboardVersion.VERSION.equals(parsed(export.body()).get("version")),"JSON export matches release");
                    var state=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/state?view=overview")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
                    check(state.statusCode()==200&&DashboardVersion.VERSION.equals(parsed(state.body()).get("version")),"inventory state matches release");
                    seedNotice(app);
                    notice(state(base,cookie,"view=overview"),4);
                    notice(state(base,cookie,"view=hosts&pageSize=1&q=future"),4);
                    notice(state(base,cookie,"view=hosts&environmentId=notice-env"),4);
                    var heartbeat=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/agent/heartbeat")).header("Authorization","Bearer notice-token-old-9").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("hostId","old-9","collectorVersion",DashboardVersion.VERSION)))).build(),HttpResponse.BodyHandlers.ofString());
                    check(heartbeat.statusCode()==200,"new version reported through authenticated heartbeat");notice(state(base,cookie,"view=overview"),3);
                    for(String id:List.of("old-10","old-13","old-major")){var host=app.store.get("hosts",id);host.put("collectorVersion",DashboardVersion.VERSION);app.store.put("hosts",host);}
                    notice(state(base,cookie,"view=overview"),0);
                    check(app.store.scalar("SELECT count(*) FROM agent_update_requests")==0,"notice never queues updates");
                    if(restart==0){var administrator=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(administrator,"Notice reader","notice-reader@example.com","READER");app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),TestAccounts.PASSWORD);}
                    var readerLogin=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("email","notice-reader@example.com","password",TestAccounts.PASSWORD)))).build(),HttpResponse.BodyHandlers.ofString());
                    check(readerLogin.statusCode()==200,"reader login");String readerCookie=readerLogin.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
                    check(!state(base,readerCookie,"view=overview").containsKey("agentUpdateNotice"),"reader does not receive administrative notice");
                }finally{http.stop(0);pool.shutdownNow();}
            }
            System.out.println("DashboardVersionHttpTest PASS: "+DashboardVersion.VERSION+", HTTP/JSON/build date/no-cache/restart/global notice/filters/pagination/heartbeat/reader/no mutation.");
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
