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
                }finally{http.stop(0);pool.shutdownNow();}
            }
            System.out.println("DashboardVersionHttpTest PASS: "+DashboardVersion.VERSION+", HTTP/JSON/build date/no-cache/restart.");
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
