package assessment;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** "Bancos de dados": destination groups aggregated in SQLite, scoped, paginated, drill-down and READER access; plus the JDBC report cell text. */
public final class DatabaseGroupsHttpTest {
    static final HttpClient CLIENT=HttpClient.newHttpClient();
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    static long number(Object value){return ((Number)value).longValue();}
    static HttpResponse<String> get(String base,String cookie,String query)throws Exception{
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/state?"+query)).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    static Map<String,Object> state(String base,String cookie,String query)throws Exception{
        var response=get(base,cookie,query);check(response.statusCode()==200,"HTTP "+response.statusCode()+" for "+query+": "+response.body());return obj(Json.parse(response.body()));
    }
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    static Map<String,Object> ds(String id,String host,String name,Object... pairs){
        var row=map("id",id,"hostId",host,"name",name,"state","CONFIGURED","evidenceLevel","configuration","observedAt","2026-10-01T10:00:00Z");
        for(int i=0;i<pairs.length;i+=2)row.put((String)pairs[i],pairs[i+1]);return row;
    }
    static void seed(AssessmentServer app){
        app.store.put("environments",map("id","prod","name","Produção","settings",map()));
        app.store.put("environments",map("id","hml","name","Homologação","settings",map()));
        for(String[] h:new String[][]{{"h1","app-01","prod"},{"h2","app-02","prod"},{"h3","batch-01","prod"},{"h4","hml-01","hml"}})
            app.store.put("hosts",map("id",h[0],"hostname",h[1],"environmentId",h[2],"lastSeen","2026-10-01T10:00:00Z"));
        app.store.putInventory("datasources","h1",List.of(
            ds("a","h1","jdbc/Pedidos","databaseType","Oracle","databaseHost","DB1.corp.local","databasePort",1521,"databaseName","PEDIDOS","driverName","oracle.jdbc.OracleDriver","driverVersion","19.3.0.0","driverResolution","IDENTIFIED","serverName","AdminServer","applicationName","pedidos-web"),
            ds("b","h1","jdbc/PedidosRO","databaseType","Oracle","databaseHost","db1.corp.local","databasePort","1521","databaseName","pedidos","driverName","oracle.jdbc.OracleDriver","driverResolution","NOT_FOUND","associations",List.of(map("pid",10,"serverName","ManagedServer1","applicationName","relatorios")))));
        app.store.putInventory("datasources","h2",List.of(
            ds("c","h2","jdbc/Pedidos","databaseType","Oracle","databaseEndpoints",List.of(map("host","db1.corp.local","port",1521)),"databaseName","Pedidos","linkedContexts",List.of(map("context","context:/loja","linkName","jdbc/Pedidos"))),
            ds("d","h2","BillingDS","databaseType","PostgreSQL","databaseHost","pg.corp.local","databasePort",5432,"databaseName","billing")));
        app.store.putInventory("datasources","h3",List.of(ds("e","h3","jdbc/Batch","databaseName","${DB_SERVICE}","databaseEndpointEvidence","configured")));
        app.store.putInventory("datasources","h4",List.of(ds("f","h4","jdbc/Pedidos","databaseHost","db1.corp.local","databasePort",1521,"databaseName","PEDIDOS")));
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("database-groups-");Files.createDirectories(root.resolve("conf"));Files.createSymbolicLink(root.resolve("web"),Paths.get("web").toAbsolutePath());
        Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("seedLegacyHost","false");p.setProperty("hostId","unused");p.setProperty("agentToken","unused");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Disposable database groups fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            seed(app);
            HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService workers=Executors.newFixedThreadPool(3);http.createContext("/",app);http.setExecutor(workers);http.start();
            try{
                String base="http://127.0.0.1:"+http.getAddress().getPort();
                TestAccounts.login(base);
                var admin=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(admin,"Reader","reader@example.test","READER");app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),"reader-password-123");
                var login=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("email","reader@example.test","password","reader-password-123")))).build(),HttpResponse.BodyHandlers.ofString());
                check(login.statusCode()==200,"reader login");String cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];

                var prod=state(base,cookie,"view=databases&environmentId=prod");
                var groups=list(prod.get("databaseGroups"));
                check(number(obj(prod.get("pagination")).get("total"))==3&&groups.size()==3,"three destinations in prod (case-normalized): "+groups);
                var oracle=groups.get(0);
                check(oracle.get("key").equals("db1.corp.local:1521/pedidos"),"normalized key, scalar and endpoint forms merge: "+oracle.get("key"));
                check(number(oracle.get("datasources"))==3&&number(oracle.get("machineCount"))==2,"three datasources on two machines, hml excluded");
                check(Json.stringify(oracle.get("machines")).contains("app-01")&&Json.stringify(oracle.get("machines")).contains("app-02")&&!Json.stringify(oracle).contains("hml-01"),"machine names scoped");
                check(list(oracle.get("servers")).containsAll(List.of("AdminServer","ManagedServer1"))&&number(oracle.get("serverCount"))==2,"servers from field and associations");
                check(list(oracle.get("applications")).containsAll(List.of("pedidos-web","relatorios","context:/loja")),"applications from field, associations and linked contexts");
                check(list(oracle.get("databaseTypes")).equals(List.of("Oracle")),"database types");
                var last=groups.get(2);
                check(last.get("key").equals(DatabaseGroups.UNIDENTIFIED)&&Boolean.FALSE.equals(last.get("identified"))&&number(last.get("datasources"))==1,"unresolved destination grouped last");

                var hml=list(state(base,cookie,"view=databases&environmentId=hml").get("databaseGroups"));
                check(hml.size()==1&&number(hml.get(0).get("datasources"))==1&&hml.get(0).get("key").equals("db1.corp.local:1521/pedidos"),"environment scope");
                var all=list(state(base,cookie,"view=databases").get("databaseGroups"));
                check(number(all.get(0).get("datasources"))==4&&number(all.get(0).get("machineCount"))==3,"all environments");
                var machine=list(state(base,cookie,"view=databases&hostId=h2").get("databaseGroups"));
                check(machine.size()==2,"machine scope");

                var paged=state(base,cookie,"view=databases&environmentId=prod&pageSize=1&page=2");
                check(list(paged.get("databaseGroups")).size()==1&&list(paged.get("databaseGroups")).get(0).get("key").equals("pg.corp.local:5432/billing"),"second page");
                check(number(obj(paged.get("pagination")).get("pages"))==3,"pages");
                check(number(obj(state(base,cookie,"view=databases&environmentId=prod&pageSize=1&page=99").get("pagination")).get("page"))==3,"page clamped");
                check(list(state(base,cookie,"view=databases&environmentId=prod&q=billing").get("databaseGroups")).size()==1,"search applies to grouped datasources");

                var drill=state(base,cookie,"view=datasources&environmentId=prod&subset="+encode("db:db1.corp.local:1521/pedidos"));
                check(list(drill.get("datasources")).size()==3&&number(obj(drill.get("pagination")).get("total"))==3,"drill-down lists the destination's datasources");
                var none=state(base,cookie,"view=datasources&environmentId=prod&subset="+encode("db:"+DatabaseGroups.UNIDENTIFIED));
                check(list(none.get("datasources")).size()==1&&list(none.get("datasources")).get(0).get("id").equals("e"),"drill-down to unidentified");
                check(list(state(base,cookie,"view=datasources&environmentId=prod&subset="+encode("db:' OR 1=1 --")).get("datasources")).isEmpty(),"subset is a bound parameter");
                check(get(base,cookie,"view=databases&subset="+"x".repeat(1001)).statusCode()==400,"subset length bounded");
                check(list(state(base,cookie,"view=datasources&environmentId=prod").get("datasources")).size()==5,"datasources tab lists the environment");

                // Report cells: one datasource per line, no empty " · " segments.
                Map<String,String> input=new LinkedHashMap<>();input.put("model","machines");input.put("machine","app-01");input.put("columns","machine,jdbc");
                String cell=String.valueOf(ReportsTest.rows(ReportsTest.get(app,input)).get(0).get("jdbc"));
                check(cell.split("\n").length==2,"one datasource per line: "+cell);
                check(!cell.contains(" ·  · ")&&!cell.contains(" · · ")&&!cell.contains("Arquivos: \n")&&!cell.endsWith("Arquivos: ")&&!cell.contains(" ·  "),"no empty segments: "+cell);
                check(cell.contains("Destino configurado: DB1.corp.local:1521 / PEDIDOS")&&cell.contains("Driver: Driver não localizado"),"cell keeps destination and driver identification");
                input.put("machine","batch-01");String hostless=String.valueOf(ReportsTest.rows(ReportsTest.get(app,input)).get(0).get("jdbc"));
                check(hostless.contains("Destino configurado: Não identificado (serviço ${DB_SERVICE})")&&!hostless.contains(" / ${"),"hostless destination is not shown as a database: "+hostless);
                System.out.println("DatabaseGroupsHttpTest PASS: "+checks+" checks (normalized destinations, scope, READER, pagination, drill-down, report cell)");
            }finally{http.stop(0);workers.shutdownNow();}
        }finally{try(var paths=Files.walk(root)){for(Path pth:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(pth);}}
    }
}
