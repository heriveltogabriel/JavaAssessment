package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Support filters, risk counts and badges over HTTP; only uses lines already out of support so it stays date-stable. */
public final class LifecycleHttpTest {
    static final HttpClient CLIENT=HttpClient.newHttpClient();
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    static long number(Object value){return ((Number)value).longValue();}
    static HttpResponse<String> get(String base,String cookie,String path)throws Exception{
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base+path)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    static Map<String,Object> state(String base,String cookie,String query)throws Exception{
        var response=get(base,cookie,"/api/state?"+query);check(response.statusCode()==200,"state HTTP "+response.statusCode()+" "+query);return obj(Json.parse(response.body()));
    }
    static String cookie(HttpResponse<String> login){return login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];}
    static void seed(AssessmentServer app){
        app.store.put("environments",map("id","prd","name","Produção","settings",map()));
        app.store.put("environments",map("id","hml","name","Homologação","settings",map()));
        app.store.transaction(()->{
            for(int i=0;i<6;i++){
                String id="host-"+i,env=i<4?"prd":"hml",at=AssessmentServer.now();
                app.store.put("hosts",map("id",id,"hostname",id+".example.invalid","environmentId",env,"lastSeen",at,"coverage",map("status",i%2==0?"partial":"complete","warnings",List.of())));
                app.store.putInventory("runtimes",id,List.of(map("id","old","hostId",id,"version",i<3?"1.6.0_45":"1.8.0_381","javaHome","/opt/old","observedAt",at,"runtimeReplacedOnDisk",i==0),map("id","new","hostId",id,"version","21.0.4","javaHome","/opt/new","observedAt",at)));
                app.store.putInventory("servers",id,List.of(map("id","srv","hostId",id,"type","WebLogic","name","AdminServer","version",i%2==0?"10.3.6.0":"12.2.1.3.0","state","PROCESS_RUNNING","observedAt",at)));
                app.store.putInventory("applications",id,List.of(map("id","app","hostId",id,"name","pedidos","type","Spring Boot","frameworkVersion",i==5?"3.0.13":"9.9.9","observedAt",at)));
                app.store.putInventory("libraries",id,List.of(map("id","lib","hostId",id,"name","ojdbc8","version",i==1?"12.2.0.1":"99.1","category","JDBC","observedAt",at)));
                app.store.putInventory("datasources",id,List.of(map("id","ds","hostId",id,"name","jdbc/Pedidos","driverVersion",i==2?null:"19.3.0.0","driverResolution",i==3?"AMBIGUOUS":"IDENTIFIED","state","CONFIGURED","evidenceLevel","configuration","observedAt",at)));
            }return null;
        });
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Paths.get("tmp/java-tmp").toAbsolutePath(),"lifecycle-http-");Files.createDirectories(root.resolve("conf"));Files.createSymbolicLink(root.resolve("web"),Paths.get("web").toAbsolutePath());
        Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("seedLegacyHost","false");p.setProperty("hostId","unused");p.setProperty("agentToken","unused");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Disposable lifecycle fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            seed(app);HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService workers=Executors.newFixedThreadPool(3);http.createContext("/",app);http.setExecutor(workers);http.start();
            try{
                String base="http://127.0.0.1:"+http.getAddress().getPort(),admin=cookie(TestAccounts.login(base));
                var java=state(base,admin,"view=runtimes&support=fora");
                check(number(obj(java.get("pagination")).get("total"))==3,"three Java 6 installations");
                for(var row:list(java.get("runtimes"))){check(obj(row.get("support")).get("status").equals("fora")&&obj(row.get("support")).get("linha").equals("Java 6"),"Java 6 badge");}
                check(number(obj(state(base,admin,"view=runtimes&support=fora&environmentId=hml").get("pagination")).get("total"))==0,"support filter keeps environment scope");
                check(get(base,admin,"/api/state?view=runtimes&support=talvez").statusCode()==400,"invalid support status rejected");
                var servers=state(base,admin,"view=servers&support=fora");check(number(obj(servers.get("pagination")).get("total"))==3,"WebLogic 10.3.6 out of support");
                check(obj(list(state(base,admin,"view=servers&support=desconhecido").get("servers")).get(0).get("support")).get("status").equals("desconhecido"),"12.2.1.3 not in catalog stays unknown");
                check(number(obj(state(base,admin,"view=applications&support=fora").get("pagination")).get("total"))==1,"Spring Boot 3.0 out of support");
                var libraries=state(base,admin,"view=libraries&subset=jdbc&support=fora");check(list(libraries.get("libraries")).size()==1&&obj(list(libraries.get("libraries")).get(0).get("support")).get("linha").equals("Oracle JDBC 18c e anteriores"),"ojdbc 12.2 badge and filter");
                check(number(obj(state(base,admin,"view=libraries&subset=driver-unresolved").get("pagination")).get("total"))==2,"missing and ambiguous drivers");
                check(number(obj(state(base,admin,"view=runtimes&subset=replaced").get("pagination")).get("total"))==1,"replaced Java on disk");
                check(number(obj(state(base,admin,"view=hosts&subset=coverage-partial").get("pagination")).get("total"))==3,"partial coverage machines");

                var overview=state(base,admin,"view=overview");var risks=obj(overview.get("risks"));
                check(number(risks.get("javaHostsOut"))==3&&number(risks.get("serversOut"))==3&&number(risks.get("springOut"))==1&&number(risks.get("driversUnresolved"))==2&&number(risks.get("partialHosts"))==3&&number(risks.get("runtimesReplaced"))==1,"overview risks: "+risks);
                check("2026-10-19".equals(risks.get("dataReferencia")),"catalog date exposed");
                var out=list(overview.get("javaSupport")).stream().filter(g->g.get("status").equals("fora")).findFirst().orElseThrow();
                check(number(out.get("count"))==3&&Json.stringify(out.get("versions")).contains("Java 6"),"out-of-support Java is never folded into others");
                var scoped=obj(state(base,admin,"view=overview&environmentId=hml").get("risks"));
                check(number(scoped.get("javaHostsOut"))==0&&number(scoped.get("serversOut"))==1&&number(scoped.get("springOut"))==1&&number(scoped.get("partialHosts"))==1,"risks respect environment: "+scoped);
                check(Json.stringify(overview.get("serverGroups")).contains("\"fora\""),"server groups carry support");
                var versions=state(base,admin,"view=versions");check(Json.stringify(versions.get("javaGroups")).contains("Java 6")&&Json.stringify(versions.get("javaGroups")).contains("\"fora\""),"version groups carry support");
                var item=obj(Json.parse(get(base,admin,"/api/item?kind=servers&id=srv&hostId=host-0").body()));check(obj(obj(item.get("item")).get("support")).get("linha").equals("WebLogic 10.3.6 (11g)"),"item detail support");

                var administrator=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var temporary=app.auth.create(administrator,"Lifecycle reader","reader@example.com","READER");app.auth.changePassword(temporary.user().id(),temporary.user().revision(),temporary.password(),"Lifecycle reader 42!");
                var login=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("email","reader@example.com","password","Lifecycle reader 42!")))).build(),HttpResponse.BodyHandlers.ofString());
                check(login.statusCode()==200,"reader login");String reader=cookie(login);
                check(number(obj(state(base,reader,"view=overview").get("risks")).get("javaHostsOut"))==3,"reader sees risks");
                check(number(obj(state(base,reader,"view=servers&support=fora").get("pagination")).get("total"))==3,"reader can filter");
            }finally{http.stop(0);workers.shutdownNow();}
        }
        System.out.println("Lifecycle HTTP checks passed");
    }
}
