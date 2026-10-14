package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Real HTTP/SQLite operation coordination. OS execution is tested separately. */
public final class AgentUpdateMigrationHttpTest {
    static final HttpClient CLIENT=HttpClient.newHttpClient();static int checks;
    static Path root;static AssessmentServer app;static HttpServer server;static ExecutorService pool;static String base,cookie,csrf;
    record Response(int status,Map<String,Object> json,byte[] bytes){}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object x){return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object x){return (List<Map<String,Object>>)x;}
    static String s(Object x){return x instanceof String?(String)x:"";}
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Response request(int expected,String method,String path,Object body,String host)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path));if(host!=null)b.header("Authorization","Bearer fixture-token-"+host);else{b.header("Cookie",cookie);if(!method.equals("GET"))b.header("X-CSRF-Token",csrf);}var response=CLIENT.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
        var json=response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")?obj(Json.parse(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8))):Map.<String,Object>of();check(response.statusCode()==expected,path+" expected "+expected+" got "+response.statusCode()+" "+json);return new Response(response.statusCode(),json,response.body());
    }
    static void start()throws Exception{app=new AssessmentServer(root.toFile());server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);pool=Executors.newFixedThreadPool(3);server.createContext("/",app);server.setExecutor(pool);server.start();base="http://127.0.0.1:"+server.getAddress().getPort();var auth=TestAccounts.login(base);check(auth.statusCode()==200,"fixture personal admin login");cookie=auth.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];csrf=s(obj(Json.parse(auth.body())).get("csrfToken"));}
    static void stop(){server.stop(0);pool.shutdownNow();app.close();}
    static void seed(String id,String version,String capability){app.store.put("hosts",map("id",id,"hostname",id+".example.invalid","environmentId","env-test","collectorVersion",version,"updateCapability",capability,"lastSeen",AssessmentServer.now(),"coverage",map("status","complete")));app.store.put("identities",map("hostId",id,"installationId","migration-fixture-"+id,"status","active","agentToken","fixture-token-"+id));}
    static void heartbeat(String host,String version,String capability,Object bundle,Object manager)throws Exception{request(200,"POST","/api/agent/heartbeat",map("hostId",host,"collectorVersion",version,"javaVersion","17-fixture","hostname",host,"updateCapability",capability,"bundleSha256",bundle,"managerSha256",manager),host);}
    static Map<String,Object> report(String host,String op,String status,String version,Map<String,Object> claim)throws Exception{return request(200,"POST","/api/agent/update-v3/report",map("hostId",host,"operationId",op,"status",status,"version",version,"bundleSha256",claim.get("bundleSha256"),"managerSha256",claim.get("managerSha256"),"diagnostic","OK"),host).json;}
    public static void main(String[] args)throws Exception{
        Path fixture=Path.of(args.length>0?args[0]:"tmp/full-update/http-fixture");root=Files.createTempDirectory("migration-http-");Files.createDirectories(root.resolve("conf"));AgentUpdateRoutesTest.copy(fixture.resolve("dist"),root.resolve("dist"));Files.createSymbolicLink(root.resolve("web"),Path.of("web").toAbsolutePath());Properties p=new Properties();p.setProperty("seedLegacyHost","false");p.setProperty("hostId","unused");p.setProperty("agentToken","unused");p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Disposable signed migration fixture");}
        start();try{
            app.store.transaction(()->{app.store.put("environments",map("id","env-test","name","Migration test","settings",map()));seed("legacy-a","1.5.12","update-v1");seed("legacy-b","1.5.12","update-v1");seed("too-old","1.5.11","update-v1");seed("direct","2.0.4","update-v3");return null;});
            var preview=request(200,"GET","/api/agent-updates?environmentId=env-test",null,null).json;check(list(preview.get("hosts")).stream().filter(h->s(h.get("id")).startsWith("legacy")).allMatch(h->Boolean.TRUE.equals(h.get("eligible"))&&Boolean.TRUE.equals(h.get("verificationPending"))),"legacy preview states local verification pending");check(list(preview.get("hosts")).stream().filter(h->h.get("id").equals("too-old")).allMatch(h->"MINIMUM_AGENT_VERSION".equals(h.get("reason"))),"minimum 1.5.12 enforced");
            var order=map("hostIds",List.of("legacy-a","legacy-b"),"targetVersion",DashboardVersion.VERSION,"requestKey","migration-request-fixture-01","runAssessmentAfterUpdate",true);var created=request(200,"POST","/api/agent-updates",order,null).json;String batch=s(created.get("batchId")),operation=s(list(created.get("requests")).get(0).get("id"));
            var action=obj(request(200,"GET","/api/agent/actions?hostId=legacy-a&updateCapability=update-v1",null,"legacy-a").json.get("action"));String child=s(action.get("requestId")),bridge=s(action.get("targetVersion"));check(action.get("type").equals("UPDATE_AGENT_V1")&&!bridge.equals(DashboardVersion.VERSION),"old agent receives old wire protocol and internal bridge version");
            request(409,"POST","/api/agent/update/claim",map("hostId","legacy-a","requestId",operation),"legacy-a");
            request(409,"POST","/api/agent/update/report",map("hostId","legacy-a","requestId",operation,"status","SUCCEEDED","version",DashboardVersion.VERSION,"diagnostic","OK"),"legacy-a");
            request(409,"POST","/api/agent/update-v3/claim",map("hostId","legacy-a","operationId",operation),"legacy-a");request(403,"POST","/api/agent/update/claim",map("hostId","legacy-b","requestId",child),"legacy-a");
            request(200,"POST","/api/agent/update/claim",map("hostId","legacy-a","requestId",child),"legacy-a");var legacyManifest=request(200,"GET","/api/agent/update/manifest?hostId=legacy-a&requestId="+child,null,"legacy-a").json;check(legacyManifest.size()==8&&legacyManifest.get("minUpdater").equals(1L),"strict original eight-field manifest");
            var pinned=obj(app.store.get("agent_update_requests",operation).get("updatePlan"));
            var early=map("hostId","legacy-a","operationId",operation,"status","INSTALLING","version",bridge,"bundleSha256",pinned.get("bundleSha256"),"managerSha256",pinned.get("managerSha256"),"diagnostic","OK");
            request(409,"GET","/api/agent/update-v3/package?hostId=legacy-a&operationId="+operation,null,"legacy-a");
            request(409,"POST","/api/agent/update-v3/report",early,"legacy-a");
            heartbeat("legacy-a",bridge,"update-v1","","");for(String state:List.of("INSTALLING","VERIFYING","SUCCEEDED"))request(200,"POST","/api/agent/update/report",map("hostId","legacy-a","requestId",child,"status",state,"version",bridge,"diagnostic","OK"),"legacy-a");
            check(!app.store.get("agent_update_requests",operation).get("status").equals("SUCCEEDED"),"bridge success never completes parent");check(app.store.scalar("SELECT count(*) FROM jobs")==0,"bridge never schedules assessment");var work=request(200,"GET","/api/work-requests/detail?type=AGENT_UPDATE&id="+batch,null,null).json;check(list(work.get("machines")).size()==2,"internal child hidden from batch machine counts");
            check(request(200,"GET","/api/agent/actions?hostId=legacy-b&updateCapability=update-v1",null,"legacy-b").json.get("action")==null,"next machine waits for full parent, not bridge");
            request(409,"GET","/api/agent/update-v3/package?hostId=legacy-a&operationId="+operation,null,"legacy-a");
            request(409,"POST","/api/agent/update-v3/report",early,"legacy-a");
            var fullAction=obj(request(200,"GET","/api/agent/update-v3/action?hostId=legacy-a&updateCapability=update-v1",null,"legacy-a").json.get("action"));check(operation.equals(fullAction.get("operationId")),"bridge continuation keeps parent identity");var claim=request(200,"POST","/api/agent/update-v3/claim",map("hostId","legacy-a","operationId",operation),"legacy-a").json;
            var bundle=request(200,"GET","/api/agent/update-v3/package?hostId=legacy-a&operationId="+operation,null,"legacy-a");check(AgentUpdateRoutes.hash(bundle.bytes).equals(claim.get("bundleSha256")),"full payload pinned by claim");
            var success=map("hostId","legacy-a","operationId",operation,"status","SUCCEEDED","version",DashboardVersion.VERSION,"bundleSha256",claim.get("bundleSha256"),"managerSha256",claim.get("managerSha256"),"diagnostic","OK");request(409,"POST","/api/agent/update-v3/report",success,"legacy-a");
            report("legacy-a",operation,"INSTALLING",DashboardVersion.VERSION,claim);heartbeat("legacy-a",DashboardVersion.VERSION,"update-v2",claim.get("bundleSha256"),claim.get("managerSha256"));request(409,"POST","/api/agent/update-v3/report",success,"legacy-a");
            heartbeat("legacy-a",DashboardVersion.VERSION,"update-v3",claim.get("bundleSha256"),"0".repeat(64));request(409,"POST","/api/agent/update-v3/report",success,"legacy-a");heartbeat("legacy-a",DashboardVersion.VERSION,"update-v3",claim.get("bundleSha256"),claim.get("managerSha256"));report("legacy-a",operation,"VERIFYING",DashboardVersion.VERSION,claim);
            request(409,"POST","/api/agent/update-v3/report",early,"legacy-a");
            report("legacy-a",operation,"UNCONFIRMED",DashboardVersion.VERSION,claim);
            request(409,"POST","/api/agent/update-v3/report",early,"legacy-a");
            report("legacy-a",operation,"VERIFYING",DashboardVersion.VERSION,claim);report("legacy-a",operation,"SUCCEEDED",DashboardVersion.VERSION,claim);report("legacy-a",operation,"SUCCEEDED",DashboardVersion.VERSION,claim);
            var altered=new LinkedHashMap<String,Object>(success);altered.put("managerSha256","0".repeat(64));request(409,"POST","/api/agent/update-v3/report",altered,"legacy-a");
            check(app.store.get("agent_update_requests",operation).get("status").equals("SUCCEEDED"),"parent completes only after final bundle manager heartbeat");var poll=request(200,"GET","/api/agent/poll?hostId=legacy-a&updateCapability=update-v3",null,"legacy-a").json;check(!s(poll.get("jobId")).isEmpty()&&app.store.scalar("SELECT count(*) FROM jobs WHERE host_id='legacy-a'")==1,"one assessment after final complete update");
            check(request(200,"GET","/api/agent/actions?hostId=legacy-b&updateCapability=update-v1",null,"legacy-b").json.get("action") instanceof Map,"next update may progress while previous assessment runs");
            var continuation=request(200,"GET","/api/agent/update-v3/action?hostId=legacy-b&updateCapability=update-v1",null,"legacy-b").json;
            check(continuation.get("action")==null&&"RUNNING".equals(app.store.get("jobs",s(poll.get("jobId"))).get("status"))&&app.store.scalar("SELECT count(*) FROM jobs WHERE host_id='legacy-a'")==1,"V3 action runs linked assessment maintenance in a write transaction without advancing an unconfirmed bridge or duplicating collection");
            var direct=request(200,"POST","/api/agent-updates",map("hostIds",List.of("direct"),"targetVersion",DashboardVersion.VERSION,"requestKey","direct-request-fixture-0001","runAssessmentAfterUpdate",false),null).json;String directId=s(list(direct.get("requests")).get(0).get("id"));var directAction=obj(request(200,"GET","/api/agent/actions?hostId=direct&updateCapability=update-v3",null,"direct").json.get("action"));check(directId.equals(directAction.get("operationId"))&&directAction.get("type").equals("UPDATE_AGENT_V3"),"new updater gets full bundle directly");
            System.out.println("AgentUpdateMigrationHttpTest PASS "+checks+" checks: real signed catalog/API/SQLite, original bridge wire shape, parent gating, final manager evidence, per-host auth, linked single assessment, direct path and sequential updates.");
        }finally{stop();try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
