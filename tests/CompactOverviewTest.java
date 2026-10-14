package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** 100 disposable hosts; doubles as a real HTTP/SQLite browser fixture. */
public final class CompactOverviewTest {
    static final HttpClient CLIENT=HttpClient.newHttpClient();
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    static long number(Object value){return ((Number)value).longValue();}
    static Map<String,Object> state(String base,String cookie,String query)throws Exception{
        var r=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/state?view=overview&"+query)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
        check(r.statusCode()==200,"overview HTTP "+r.statusCode());return obj(Json.parse(r.body()));
    }
    static void groups(Map<String,Object> view,String field,long expected){var rows=list(view.get(field));check(rows.size()<=4,"bounded groups");check(rows.stream().mapToLong(g->number(g.get("count"))).sum()==expected,"full scoped total: "+field);}
    static void seed(AssessmentServer app){
        for(String[] env:new String[][]{{"env-a","Produção"},{"env-b","Homologação"}})app.store.put("environments",map("id",env[0],"name",env[1],"settings",map()));
        String[][] servers={{"Tomcat","9.0.122"},{"WebLogic","10.3.6.0"},{"WildFly","26.1.3.Final"},{"WebLogic","14.1.2.0"},{"JBoss",null}};
        String[][] jdbc={{"OrdersDS","42.7.8"},{"BillingDS","8.0.33"},{"InventoryDS",null},{"OrdersDS","42.7.9"},{"AnalyticsDS","23.7"}};
        app.store.transaction(()->{
            for(int i=0;i<100;i++){
                String id=String.format("machine-%03d",i),env=i<80?"env-a":"env-b",at=java.time.Instant.now().minusSeconds(i%9==0?600:20).toString();String[] server=servers[i%5],ds=jdbc[i%5];
                app.store.put("hosts",map("id",id,"hostname",id+".example.invalid","environmentId",env,"distribution",new String[]{"Red Hat Enterprise Linux 8.10 (Ootpa)","Oracle Linux Server 9.6","Ubuntu 24.04 LTS","Red Hat Enterprise Linux Server 7.9 (Maipo)"}[i%4],"architecture","x86_64","collectorVersion",DashboardVersion.VERSION,"lastSeen",at,"ipAddresses",List.of("192.0.2."+(i+1)),"coverage",map("status","complete","warnings",List.of())));
                app.store.put("identities",map("hostId",id,"installationId","overview-"+id,"agentToken","fixture-"+id,"status","active"));
                app.store.putInventory("runtimes",id,List.of(map("id",id+"-java","hostId",id,"version",new String[]{"1.8.0_451","11.0.27","17.0.15","21.0.7"}[i%4],"javaHome","/opt/jvm","observedAt",at)));
                app.store.putInventory("servers",id,List.of(map("id",id+"-server","hostId",id,"type",server[0],"name","Server-"+i,"version",server[1],"state","PROCESS_RUNNING","observedAt",at),map("id",id+"-files","hostId",id,"type","FilesOnly","version","1.0","observedAt",at)));
                app.store.putInventory("datasources",id,List.of(map("id",id+"-jdbc","hostId",id,"name",ds[0],"driverVersion",ds[1],"driverName","Fixture driver","state","CONFIGURED","evidenceLevel","configuration","observedAt",at)));
                app.store.putInventory("libraries",id,List.of(map("id",id+"-library","hostId",id,"name","Unrelated JDBC package","version","99.9","category","JDBC","observedAt",at)));
            }return null;
        });
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("compact-overview-");Files.createDirectories(root.resolve("conf"));Files.createSymbolicLink(root.resolve("web"),Paths.get("web").toAbsolutePath());
        Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("seedLegacyHost","false");p.setProperty("hostId","unused");p.setProperty("agentToken","unused");try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Disposable overview fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            seed(app);HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService workers=Executors.newFixedThreadPool(3);http.createContext("/",app);http.setExecutor(workers);http.start();
            try{
                String base="http://127.0.0.1:"+http.getAddress().getPort(),cookie=TestAccounts.login(base).headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
                Map<String,Object> first=state(base,cookie,"");check(list(first.get("hosts")).size()==10,"ten rows");check(number(obj(first.get("pagination")).get("pages"))==10,"ten pages for 100 machines");groups(first,"serverGroups",100);groups(first,"jdbcGroups",100);
                check(!Json.stringify(first.get("serverGroups")).contains("FilesOnly"),"server evidence matches metric");check(!Json.stringify(first.get("jdbcGroups")).contains("Unrelated JDBC package"),"JDBC groups use datasource name and driver version");check(Json.stringify(first.get("jdbcGroups")).contains("InventoryDS"),"datasource with missing driver version retained");
                var last=state(base,cookie,"page=999");check(number(obj(last.get("pagination")).get("page"))==10,"out of range page clamped");check(list(last.get("hosts")).get(0).get("id").equals("machine-090"),"last page");
                var search=state(base,cookie,"q=machine-099");check(list(search.get("hosts")).size()==1,"search full inventory, not first page");check(number(obj(search.get("summary")).get("hosts"))==100,"search preserves metrics");groups(search,"jdbcGroups",100);
                var ip=state(base,cookie,"q=192.0.2.100");check(list(ip.get("hosts")).get(0).get("id").equals("machine-099"),"IP search");
                var empty=state(base,cookie,"q=no-match&page=10");check(list(empty.get("hosts")).isEmpty()&&number(obj(empty.get("pagination")).get("page"))==1,"empty search resets page");
                var env=state(base,cookie,"environmentId=env-b");check(number(obj(env.get("summary")).get("hosts"))==20,"environment total");groups(env,"serverGroups",20);groups(env,"jdbcGroups",20);check(list(env.get("hosts")).stream().allMatch(h->h.get("environmentId").equals("env-b")),"no cross-environment hosts");
                var detail=state(base,cookie,"hostId=machine-099");check(list(detail.get("hosts")).size()==1&&list(detail.get("datasources")).size()==1,"machine detail preserved");
                System.out.println("CompactOverviewTest PASS: 100 hosts, bounded groups, scoped totals, datasource driver versions, paging/search/IP/empty state and machine detail.");
                if(args.length>0&&args[0].equals("--serve")){System.out.println("OVERVIEW_UI_URL="+base);System.out.flush();System.in.read();}
            }finally{http.stop(0);workers.shutdownNow();}
        }finally{try(var paths=Files.walk(root)){for(Path pth:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(pth);}}
    }
}
