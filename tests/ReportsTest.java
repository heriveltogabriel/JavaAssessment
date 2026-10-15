package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Isolated report query/HTTP fixture. No agent or external machine is contacted. */
public final class ReportsTest {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> r){return (List<Map<String,Object>>)r.get("rows");}
    static long count(Map<String,Object> r,String k){return ((Number)obj(r.get("summary")).get(k)).longValue();}
    static Map<String,Object> get(AssessmentServer a,Map<String,String> filters){return a.store.readSnapshot(()->new Reports(a.store).query(filters,false));}
    static void seed(AssessmentServer a){
        a.store.transaction(()->{
            a.store.put("environments",map("id","prod","name","Produção","settings",map()));a.store.put("environments",map("id","dev","name","Desenvolvimento","settings",map()));a.store.put("environments",map("id","empty","name","Ambiente vazio","settings",map()));
            for(int i=0;i<32;i++){
                String id="host-"+i;
                a.store.put("hosts",map("id",id,"hostname",i==31?"=SUM(1,2)":"app-"+String.format("%02d",i),"environmentId",i<20?"prod":"dev","distribution","Red Hat Enterprise Linux 8.10","osName","Linux","architecture","x86_64","ipAddresses",List.of("127.0.0.1","fe80::1%eth0","10.0.0."+(i+1)),"memoryTotalBytes",8589934592L,"logicalCpus",4,"coverage",map("status","complete","warnings",List.of()),"lastSeen","2026-10-15T00:00:00Z"));
                a.store.putInventory("runtimes",id,List.of(map("id",id+"-java","hostId",id,"version","17.0.12","javaHome","/opt/jdk17","vendor","OpenJDK")));
                a.store.putInventory("servers",id,List.of(map("id",id+"-wls","hostId",id,"type","WebLogic","name","AdminServer","version",i==0?"10.3.6.0":"14.1.2.0","state","PROCESS_RUNNING"),map("id",id+"-tomcat","hostId",id,"type","Tomcat","name","tomcat","version",i==1?"10.3.6.0":"9.0.1","state","PROCESS_RUNNING")));
                var ds=new ArrayList<Object>();ds.add(map("id",id+"-orders","hostId",id,"name",i==0?"Pedidos, \"São Paulo\"":"OrdersDS","driverName","PostgreSQL","driverClass","org.postgresql.Driver","driverVersion",i==0?"42.7":"42.8","serverName","AdminServer","password","never-export-secret","url","jdbc:secret"));
                if(i==0)ds.add(map("id","billing","hostId",id,"name","BillingDS","driverName","MySQL","driverVersion","8.0"));
                a.store.putInventory("datasources",id,ds);
            }
            return null;
        });
    }
    static final HttpClient CLIENT=HttpClient.newHttpClient();
    static HttpResponse<byte[]> http(String base,String path,String cookie)throws Exception{
        var req=HttpRequest.newBuilder(URI.create(base+path)).GET();if(cookie!=null)req.header("Cookie",cookie);
        return CLIENT.send(req.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("assessment-reports-");Files.createDirectories(root.resolve("conf"));Files.createSymbolicLink(root.resolve("web"),Paths.get("web").toAbsolutePath());
        var p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("seedLegacyHost","false");p.setProperty("hostId","unused");p.setProperty("agentToken","unused");try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"isolated reports");}
        try(var app=new AssessmentServer(root.toFile())){
            seed(app);
            var machines=get(app,Map.of());check(count(machines,"machines")==32&&rows(machines).size()==20,"unique machines + paging");
            check(!Json.stringify(machines).contains("never-export-secret")&&!Json.stringify(machines).contains("jdbc:secret"),"allowlist excludes credentials and URL");
            var filter=Map.of("serverType","WebLogic","serverVersion","10.3.6.0");var match=get(app,filter);check(count(match,"machines")==1,"product+version must match same server");check(rows(match).get(0).get("machine").equals("app-00"),"matched machine");
            var servers=get(app,Map.of("model","servers","serverType","WebLogic","serverVersion","10.3.6.0"));check(rows(servers).size()==1&&rows(servers).get(0).get("product").equals("WebLogic"),"resource row matches filters too");
            check(count(get(app,Map.of("model","servers")),"machines")==32&&count(get(app,Map.of("model","servers")),"records")==64,"distinct host count for multiple servers");
            check(count(get(app,Map.of("datasource","BillingDS","driver","PostgreSQL")),"machines")==0,"JDBC pair must match same datasource");
            var jdbc=get(app,Map.of("model","jdbc","driver","PostgreSQL","driverVersion","42.7"));check(rows(jdbc).size()==1&&rows(jdbc).get(0).get("driverClass").equals("org.postgresql.Driver"),"JDBC driver/class/version");
            check(count(get(app,Map.of("javaVersion","17")),"machines")==32,"Java major version");check(count(get(app,Map.of("javaVersion","17.0.12")),"machines")==32,"Java exact version");
            check(count(get(app,Map.of("environmentId","dev","os","linux")),"machines")==12,"environment and OS combined");
            check(count(get(app,Map.of("machine","app-%")),"machines")==0,"LIKE wildcard escaped");check(count(get(app,Map.of("machine","' OR 1=1 --")),"machines")==0,"SQL injection treated as data");
            check(count(get(app,Map.of("machine","10.0.0.32")),"machines")==1,"IP address search");
            check(Reports.networkIps("[\"127.0.0.1\",\"::1\",\"fe80::1%ens3\",\"10.0.0.1\"]").equals("10.0.0.1"),"network IPs");
            var env=get(app,Map.of("model","environments"));check(rows(env).size()==3,"empty environment retained");check(rows(env).stream().mapToLong(r->Long.parseLong(r.get("machines").toString())).sum()==32,"group counts");
            var allInput=new LinkedHashMap<String,String>();allInput.put("revision",machines.get("revision").toString());var all=app.store.readSnapshot(()->new Reports(app.store).query(allInput,true));check(rows(all).size()==32,"exports all pages");
            var writer=new StringWriter();Reports.csv(writer,all);check(writer.toString().startsWith("\ufeff"),"BOM");check(writer.toString().contains("'=SUM(1,2)"),"formula neutralized");check(writer.toString().contains("Pedidos, \"\"São Paulo\"\""),"CSV quotes/accents");
            for(var invalid:List.of(Map.of("model","not-a-model"),Map.of("columns","password"),Map.of("pageSize","10001"),Map.of("unknown","x"))){try{get(app,invalid);throw new AssertionError("invalid input accepted");}catch(Reports.Failure f){check(f.status==400,"invalid filter/column/page");}}
            var nested=map("id","nested","hostId","host-31","name","APLICAÇÃO","driverName",map("password","nested-secret"),"driverVersion",map("token","nested-token"));
            app.store.putInventory("datasources","host-31",List.of(nested));
            var unicode=get(app,Map.of("model","jdbc","datasource","aplicação"));check(count(unicode,"records")==1,"Unicode text matching");check(!Json.stringify(unicode).contains("nested-secret")&&!Json.stringify(unicode).contains("nested-token"),"structured scalar fields excluded");
            var giant=new ArrayList<Object>();for(int i=0;i<400;i++)giant.add(map("id","large-"+i,"hostId","host-31","name","large-datasource-"+i+"-"+"a".repeat(100),"driverName","Driver"));
            app.store.putInventory("datasources","host-31",giant);
            check(count(get(app,Map.of("model","servers","columns","machine","machine","=SUM")),"records")==2,"narrow projection does not aggregate unused large JDBC lists");
            try{get(app,Map.of("model","machines","machine","=SUM"));throw new AssertionError("oversized cell accepted");}catch(Reports.Failure failure){check(failure.status==413,"oversized cell refused before full materialization");}
            giant.clear();for(int i=0;i<10001;i++)giant.add(map("id","row-"+i,"hostId","host-31","name","DS"+i));
            app.store.putInventory("datasources","host-31",giant);
            try{get(app,Map.of("model","jdbc","columns","datasource","machine","=SUM"));throw new AssertionError("oversized row count accepted");}catch(Reports.Failure failure){check(failure.status==413,"more than 10000 rows refused");}
            app.store.putInventory("datasources","host-31",List.of(map("id","host-31-orders","hostId","host-31","name","OrdersDS","driverName","PostgreSQL","driverClass","org.postgresql.Driver","driverVersion","42.8","serverName","AdminServer")));
            HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);var pool=Executors.newFixedThreadPool(3);http.createContext("/",app);http.setExecutor(pool);http.start();
            try{
                String base="http://127.0.0.1:"+http.getAddress().getPort(),cookie=TestAccounts.login(base).headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
                check(http(base,"/api/reports",null).statusCode()==401,"requires session");check(http(base,"/api/reports?columns=password",cookie).statusCode()==400,"HTTP rejects columns");
                check(http(base,"/api/reports?model=jdbc&model=machines",cookie).statusCode()==400,"duplicate parameters rejected");
                var response=http(base,"/api/reports",cookie);check(response.statusCode()==200,"HTTP report");String rev=obj(Json.parse(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8))).get("revision").toString();
                var csv=http(base,"/api/reports/export.csv?revision="+rev,cookie);check(csv.statusCode()==200&&new String(csv.body(),java.nio.charset.StandardCharsets.UTF_8).contains("app-30"),"HTTP full CSV");
                var pdf=http(base,"/api/reports/export.pdf?revision="+rev,cookie);check(pdf.statusCode()==200&&new String(pdf.body(),0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"),"real PDF download");
                check(http(base,"/api/reports/export.csv?revision=stale",cookie).statusCode()==409,"stale revision conflict");
                var admin=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var temp=app.auth.create(admin,"Reports Reader","reports-reader@example.com","READER");app.auth.changePassword(temp.user().id(),temp.user().revision(),temp.password(),TestAccounts.PASSWORD);
                var readerLogin=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("email","reports-reader@example.com","password",TestAccounts.PASSWORD)))).build(),HttpResponse.BodyHandlers.ofString());String readerCookie=readerLogin.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];
                check(http(base,"/api/reports/options",readerCookie).statusCode()==200&&http(base,"/api/reports",readerCookie).statusCode()==200,"reader report access");check(http(base,"/api/reports/export.pdf?revision="+rev,readerCookie).statusCode()==200,"reader PDF access");
                check(http(base,"/api/admin/users",readerCookie).statusCode()==403,"reader still cannot administer");
                var host=app.store.get("hosts","host-0");host.put("hostname","changed-name");app.store.put("hosts",host);check(http(base,"/api/reports/export.csv?revision="+rev,cookie).statusCode()==409,"actual changed inventory rejected");host.put("hostname","app-00");app.store.put("hosts",host);
                System.out.println("ReportsTest PASS "+checks+" checks: SQL, JDBC, distinct hosts, revision, CSV/PDF and access roles.");
                if(args.length>0&&args[0].equals("--serve")){System.out.println("REPORTS_UI_URL="+base);System.out.flush();System.in.read();}
            }finally{http.stop(0);pool.shutdownNow();}
        }finally{try(var walk=Files.walk(root)){for(Path file:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
