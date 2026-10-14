package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Destructive operations are tested only on disposable loopback SQLite fixtures. */
public final class MachineActionsHttpTest {
    private static final String PASSWORD="Machine actions admin phrase 42!",READER_PASSWORD="Machine actions reader phrase 42!",LEGACY="machine-actions-legacy";
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static AssessmentServer app;private static HttpServer http;private static ExecutorService workers;private static String base;private static Path root;private static int checks;
    private record Session(String cookie,String csrf){}
    private record Response(int status,Map<String,Object> body,String cookie){}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    private static void check(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
    private static Response request(String method,String path,Object body,Session session,String bearer)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));
        if(session!=null){builder.header("Cookie",session.cookie);if(session.csrf!=null)builder.header("X-CSRF-Token",session.csrf);}
        if(bearer!=null)builder.header("Authorization","Bearer "+bearer);
        if(body!=null)builder.header("Content-Type","application/json");
        var response=CLIENT.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(),obj(Json.parse(response.body())),response.headers().firstValue("Set-Cookie").orElse("").split(";",2)[0]);
    }
    private static Response expected(int status,String method,String path,Object body,Session session)throws Exception {
        Response response=request(method,path,body,session,null);check(response.status==status,method+" "+path+" expected "+status+" got "+response.status+" "+response.body);return response;
    }
    private static Session session(Response response){return new Session(response.cookie,(String)response.body.get("csrfToken"));}
    private static Session login(String email,String password)throws Exception{return session(expected(200,"POST","/api/login",map("email",email,"password",password),null));}
    private static void start()throws Exception {
        app=new AssessmentServer(root.toFile());http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);workers=Executors.newFixedThreadPool(4);http.setExecutor(workers);http.createContext("/",app);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();
    }
    private static void stop(){if(http!=null){http.stop(0);workers.shutdownNow();app.close();http=null;}}
    private static String enrollment(String environment)throws Exception{return (String)new EnrollmentRegistry(app.store).issue("Machine fixture",1440,1000,map(),false,environment,Set.of("env-a","env-b")).get("enrollmentToken");}
    private static Response register(String code,String installation,String hostname)throws Exception{return request("POST","/api/agent/register",map("installationId",installation,"hostname",hostname,"collectorVersion",DashboardVersion.VERSION,"javaVersion","17","osName","Linux","architecture","aarch64"),null,code);}
    private static void histories(String host){app.store.transaction(()->{
        for(String kind:List.of("runtimes","processes","applications","servers","libraries","datasources"))app.store.putInventory(kind,host,new ArrayList<>(List.of(map("id",kind+"-"+host,"hostId",host,"kind","JDK","inUse",true,"name","Fixture","version","21"))));
        app.store.put("jobs",map("id","job-"+host,"hostId",host,"status","SUCCEEDED","createdAt",AssessmentServer.now(),"summary","Historical assessment"));
        app.store.exec("INSERT INTO receipts(host_id,job_id,event_id,received_at) VALUES(?,?,?,?)",host,"job-"+host,"event-"+host,AssessmentServer.now());
        app.store.exec("INSERT INTO schedules(host_id,revision,slot) VALUES(?,?,?)",host,"old-revision","historical-slot");
        app.store.put("uninstall_requests",map("id","uninstall-"+host,"hostId",host,"requestKey","historical-"+host,"status","SUCCEEDED","completionToken","fixture-completion-secret","metadata","private uninstall detail"));
        return null;
    });}
    private static String snapshot(String host){Map<String,Object> state=map("host",app.store.get("hosts",host),"identity",app.store.get("identities",host));for(String table:List.of("inventory","jobs","receipts","schedules","uninstall_requests"))state.put(table,app.store.rows("SELECT * FROM "+table+" WHERE host_id=? ORDER BY rowid",host));state.put("audit",app.store.rows("SELECT * FROM human_audit WHERE action='MACHINE_DELETED' AND target_id=?",host));return Json.stringify(state);}
    private static Map<String,Object> deleteBody(String host,String name){return map("hostId",host,"confirmationName",name);}
    private static void refused(int status,String host,String name,Session session)throws Exception{String before=snapshot(host);expected(status,"DELETE","/api/hosts",deleteBody(host,name),session);check(snapshot(host).equals(before),"refused deletion preserves all selected-machine data");}
    private static void noHostData(String host){check(app.store.get("hosts",host)==null,"selected host removed");for(String table:List.of("inventory","jobs","receipts","schedules","uninstall_requests"))check(app.store.scalar("SELECT count(*) FROM "+table+" WHERE host_id=?",host)==0,"selected "+table+" removed");}
    public static void main(String[] args)throws Exception {
        root=Files.createTempDirectory("machine-actions-http-");Files.createDirectories(root.resolve("conf"));Properties props=new Properties();props.setProperty("hostId","unused-fixture-host");props.setProperty("agentToken","unused-fixture-token");props.setProperty("adminSalt","fixture-salt");props.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-salt"+LEGACY));props.setProperty("seedLegacyHost","false");try(var output=Files.newOutputStream(root.resolve("conf/server.properties"))){props.store(output,"Isolated machine-action fixture");}
        try {
            start();Session admin=session(expected(200,"POST","/api/auth/bootstrap",map("currentPassword",LEGACY,"name","Fixture Admin","email","admin@example.com","password",PASSWORD),null));
            UserAuth.User administrator=app.auth.authenticate("admin@example.com",PASSWORD);UserAuth.Temporary temporary=app.auth.create(administrator,"Fixture Reader","reader@example.com","READER");app.auth.changePassword(temporary.user().id(),temporary.user().revision(),temporary.password(),READER_PASSWORD);Session reader=login("reader@example.com",READER_PASSWORD);
            app.store.transaction(()->{for(String id:List.of("env-a","env-b"))app.store.put("environments",map("id",id,"name",id,"settings",map(),"revision","fixture"));return null;});
            String codeA=enrollment("env-a"),codeB=enrollment("env-b"),installationA="machine-fixture-installation-a",installationB="machine-fixture-installation-b";
            Response registeredA=register(codeA,installationA,"fixture-node-a"),registeredB=register(codeB,installationB,"fixture-node-b");check(registeredA.status==200&&registeredB.status==200,"real agent registration fixtures");
            String hostA=(String)registeredA.body.get("hostId"),hostB=(String)registeredB.body.get("hostId"),tokenA=(String)registeredA.body.get("agentToken"),tokenB=(String)registeredB.body.get("agentToken");histories(hostA);histories(hostB);
            String otherBefore=snapshot(hostB);String inventoryBefore=Json.stringify(app.store.rows("SELECT * FROM inventory WHERE host_id=? ORDER BY rowid",hostA));String jobsBefore=Json.stringify(app.store.rows("SELECT * FROM jobs WHERE host_id=? ORDER BY rowid",hostA));
            expected(200,"PUT","/api/hosts/environment",map("hostId",hostA,"environmentId","env-b"),admin);check(app.store.get("hosts",hostA).get("environmentId").equals("env-b"),"move uses selected environment");
            expected(200,"PUT","/api/hosts/environment",map("hostId",hostA,"environmentId",""),admin);check(app.store.get("hosts",hostA).get("environmentId").equals(""),"detach retains unassigned host");
            check(inventoryBefore.equals(Json.stringify(app.store.rows("SELECT * FROM inventory WHERE host_id=? ORDER BY rowid",hostA)))&&jobsBefore.equals(Json.stringify(app.store.rows("SELECT * FROM jobs WHERE host_id=? ORDER BY rowid",hostA))),"move and detach preserve inventory and history");
            check(app.store.scalar("SELECT count(*) FROM receipts WHERE host_id=?",hostA)==1&&app.store.scalar("SELECT count(*) FROM uninstall_requests WHERE host_id=?",hostA)==1,"move/detach retain receipt and uninstall history");
            expected(200,"PUT","/api/hosts/environment",map("hostId",hostA,"environmentId","env-a"),admin);
            refused(401,hostA,"fixture-node-a",null);refused(403,hostA,"fixture-node-a",reader);refused(403,hostA,"fixture-node-a",new Session(admin.cookie,null));refused(403,hostA,"fixture-node-a",new Session(admin.cookie,"bad-token"));
            refused(400,hostA,"fixture-node-A",admin);refused(400,hostA,"fixture-node-a ",admin);refused(409,hostA,"fixture-node-a",admin);
            expected(409,"DELETE","/api/environments",map("environmentId","env-a","confirmationName","env-a"),admin);
            expected(200,"POST","/api/hosts/revoke",map("hostId",hostA),admin);
            for(String status:List.of("WAITING","UNINSTALLING","UNCONFIRMED")){
                app.store.transaction(()->{app.store.put("uninstall_requests",map("id","older-busy","hostId",hostA,"requestKey","older-busy-request","status",status));app.store.put("uninstall_requests",map("id","latest-terminal-"+status,"hostId",hostA,"requestKey","latest-terminal-"+status,"status","CANCELLED"));return null;});
                refused(409,hostA,"fixture-node-a",admin);app.store.exec("DELETE FROM uninstall_requests WHERE id='older-busy'");
            }
            // Fail the final audit insert, after every deletion, to prove the whole transaction rolls back.
            app.store.exec("CREATE TRIGGER fixture_delete_rollback BEFORE INSERT ON human_audit WHEN NEW.action='MACHINE_DELETED' BEGIN SELECT RAISE(ABORT,'fixture rollback'); END");
            refused(500,hostA,"fixture-node-a",admin);app.store.exec("DROP TRIGGER fixture_delete_rollback");
            Response deleted=expected(200,"DELETE","/api/hosts",deleteBody(hostA,"fixture-node-a"),admin);check(Boolean.TRUE.equals(deleted.body.get("deleted"))&&hostA.equals(deleted.body.get("hostId")),"deletion response exact identity");noHostData(hostA);check(snapshot(hostB).equals(otherBefore),"another host and all its history remain byte-identical");
            Map<String,Object> tombstone=app.store.get("identities",hostA);check(tombstone.keySet().equals(Set.of("hostId","status","tokenHash","installationId","enrollmentId","deleted")),"minimal revoked identity only");check(tombstone.get("status").equals("revoked")&&Boolean.TRUE.equals(tombstone.get("deleted"))&&tombstone.get("tokenHash").equals(InventoryStore.hash(tokenA))&&tombstone.get("installationId").equals(installationA),"tombstone preserves hashed-token and installation revocation");
            check(!Json.stringify(tombstone).contains(tokenA),"raw operational token removed");check(app.store.scalar("SELECT count(*) FROM human_audit WHERE action='MACHINE_DELETED' AND actor_id=? AND target_id=?",administrator.id(),hostA)==1,"named deletion audit committed once");
            expected(404,"DELETE","/api/hosts",deleteBody(hostA,"fixture-node-a"),admin);check(request("GET","/api/agent/settings?hostId="+hostA,null,null,tokenA).status==401,"old operational token rejected");check(register(codeA,installationA,"cannot-resurrect").status==403,"same installation and old enrollment cannot resurrect host");check(register(codeB,installationA,"cannot-resurrect").status==409,"same installation and another enrollment cannot resurrect host");
            check(request("GET","/api/agent/settings?hostId="+hostB,null,null,tokenB).status==200,"other agent remains operational");expected(200,"DELETE","/api/environments",map("environmentId","env-a","confirmationName","env-a"),admin);check(app.store.get("environments","env-b")!=null,"deletion frees selected environment without affecting other environment");
            // A completed uninstalled host is eligible even if a stale identity was still active.
            app.store.transaction(()->{app.store.put("hosts",map("id","fallback-host","hostname","","environmentId","","agentLifecycle","uninstalled"));app.store.put("identities",map("hostId","fallback-host","agentToken","fallback-fixture-token","status","active","installationId","fallback-fixture-installation","enrollmentId","historical"));return null;});
            refused(400,"fallback-host","",admin);
            ExecutorService deletionRace=Executors.newFixedThreadPool(2);Session raceAdmin=admin;
            try{Callable<Integer> remove=()->request("DELETE","/api/hosts",deleteBody("fallback-host","fallback-host"),raceAdmin,null).status;var responses=deletionRace.invokeAll(List.of(remove,remove));Set<Integer> statuses=Set.of(responses.get(0).get(),responses.get(1).get());check(statuses.equals(Set.of(200,404)),"concurrent deletion has exactly one winner");}finally{deletionRace.shutdownNow();}
            check(app.store.scalar("SELECT count(*) FROM human_audit WHERE action='MACHINE_DELETED' AND target_id='fallback-host'")==1,"concurrent deletion produces one audit event");check(new EnrollmentRegistry(app.store).authenticate("fallback-fixture-token")==null,"uninstalled branch revokes stale active token");
            app.store.put("hosts",map("id","missing-identity","hostname","broken-fixture","environmentId","","agentLifecycle","uninstalled"));refused(409,"missing-identity","broken-fixture",admin);app.store.delete("hosts","missing-identity");
            stop();start();admin=login("admin@example.com",PASSWORD);noHostData(hostA);check(request("GET","/api/agent/settings?hostId="+hostA,null,null,tokenA).status==401,"revoked token remains rejected after restart");check(register(codeB,installationA,"cannot-resurrect").status==409,"installation tombstone remains after restart");check(snapshot(hostB).equals(otherBefore),"remaining inventory survives restart unchanged");
            // More than 1,000 tombstones must not consume the limit for visible registered machines.
            app.store.transaction(()->{for(int i=0;i<1001;i++)app.store.put("identities",map("hostId","deleted-capacity-"+i,"status","revoked","tokenHash",InventoryStore.hash("discarded-"+i),"installationId","deleted-capacity-installation-"+i,"enrollmentId","historical","deleted",true));return null;});
            Response capacity=register(codeB,"new-capacity-installation","capacity-node");check(capacity.status==200,"deleted tombstones do not consume machine capacity");
            long used=app.store.scalar("SELECT count(*) FROM identities WHERE coalesce(json_extract(payload,'$.deleted'),0) <> 1");app.store.transaction(()->{for(long i=used;i<EnrollmentRegistry.MAX_HOSTS;i++)app.store.put("identities",map("hostId","actual-capacity-"+i,"status","active","tokenHash",InventoryStore.hash("capacity-token-"+i),"installationId","actual-capacity-installation-"+i));return null;});
            check(register(codeB,"over-capacity-installation","limit-node").status==409,"actual registered identities still enforce capacity");
            System.out.println("MachineActionsHttpTest PASS: "+checks+" checks; auth/CSRF/name/active/busy/rollback/isolation/move/detach/tombstone/restart/capacity/audit; loopback disposable fixtures only.");
        }finally{stop();try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
