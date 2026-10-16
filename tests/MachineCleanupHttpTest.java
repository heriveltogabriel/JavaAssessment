package assessment;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Deletes only disposable loopback fixtures, never a deployed dashboard. */
public final class MachineCleanupHttpTest {
    static final HttpClient CLIENT=HttpClient.newHttpClient();static AssessmentServer app;static String base,cookie,csrf;static int checks;
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object v){return (List<Map<String,Object>>)v;}
    static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static Map<String,Object> request(int expected,String method,String path,Object input,String session,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+"/api/"+path));if(session!=null)b.header("Cookie",session);if(token!=null)b.header("X-CSRF-Token",token);if(input!=null)b.header("Content-Type","application/json");
        var r=CLIENT.send(b.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(input))).build(),HttpResponse.BodyHandlers.ofString());check(r.statusCode()==expected,path+" expected "+expected+" got "+r.statusCode()+" "+r.body());return obj(Json.parse(r.body()));
    }
    static Map<String,Object> call(int expected,String method,String path,Object input)throws Exception{return request(expected,method,path,input,cookie,csrf);}
    static Map<String,Object> preview(String env,int seconds,String... ids)throws Exception{return call(200,"POST","hosts/cleanup/preview",map("environmentId",env,"minimumSeconds",seconds,"hostIds",List.of(ids)));}
    static Map<String,Object> deletion(Map<String,Object> preview){return map("environmentId",preview.get("environmentId"),"minimumSeconds",preview.get("minimumSeconds"),"targets",list(preview.get("items")).stream().filter(i->Boolean.TRUE.equals(i.get("eligible"))).map(i->map("hostId",i.get("hostId"),"name",i.get("name"),"environmentId",i.get("environmentId"),"lastSeen",i.get("lastSeen"))).toList(),"confirmation","EXCLUIR");}
    static void host(String id,String env,long age,String credential){EnvironmentPresenceTest.host(app.store,id,env,Instant.now().minusSeconds(age).toString(),"",credential);}
    static void touch(String id,String field,Object value){var h=app.store.get("hosts",id);h.put(field,value);app.store.put("hosts",h);}
    static void seed(){
        app.store.put("environments",map("id","prod","name","Produção","settings",map()));app.store.put("environments",map("id","dev","name","Desenvolvimento","settings",map()));
        for(String id:List.of("delete","return","move","rename","rollback-a","rollback-b","busy","other","old-ol07","new-ol07"))host(id,"prod",900,"active");
        touch("old-ol07","hostname","ol07");touch("new-ol07","hostname","ol07");touch("old-ol07","environmentId","");app.store.put("identities",map("hostId","old-ol07","installationId","old-ol07","agentToken","fixture-old-ol07","status","revoked"));touch("new-ol07","lastSeen",Instant.now().toString());
        for(int i=0;i<105;i++)host(String.format("offline-%03d",i),"dev",i<60?900:i<80?90000:i<100?700000:2700000,"active");
        host("unknown","dev",900,"active");touch("unknown","lastSeen","invalid");host("awaiting","dev",900,"active");touch("awaiting","lastSeen","");
    }
    static void histories(){
        for(String id:List.of("delete","other")){
            for(String kind:List.of("runtimes","applications","servers","libraries","datasources","processes"))app.store.putInventory(kind,id,List.of(map("id",kind+"-"+id,"hostId",id,"name",id,"version","17")));
            app.store.put("jobs",map("id","job-"+id,"hostId",id,"status","SUCCEEDED","createdAt",AssessmentServer.now()));
            app.store.exec("INSERT INTO receipts(host_id,job_id,event_id,received_at) VALUES(?,?,?,?)",id,"job-"+id,"event-"+id,AssessmentServer.now());
            app.store.exec("INSERT INTO schedules(host_id,revision,slot) VALUES(?,?,?)",id,"rev","slot");
            app.store.put("uninstall_requests",map("id","uninstall-"+id,"hostId",id,"requestKey","uninstall-key-"+id,"status","CANCELLED"));
            app.store.put("agent_update_requests",map("id","request-"+id,"hostId",id,"batchId","shared","ordinal",0,"status","SUCCEEDED"));
            app.store.exec("INSERT INTO agent_update_operations VALUES(?,?,?,?,?,?)","operation-"+id,id,"shared",0,"SUCCEEDED",Json.stringify(map("id","operation-"+id,"hostId",id)));
            app.store.exec("INSERT INTO agent_update_steps VALUES(?,?,?,?,?,?,?)","step-"+id,"operation-"+id,0,"FULL","request-"+id,"SUCCEEDED","{}");
            app.store.exec("INSERT INTO agent_update_assessments VALUES(?,?,?,?,?,?,?,?,?)","operation-"+id,id,"shared",0,"SUCCEEDED","job-"+id,0,0,"{}");
        }
        app.store.exec("INSERT INTO agent_update_batches VALUES(?,?,?,?,?)","shared","shared-request-key","2.0.51",Json.stringify(List.of("delete","other")),Json.stringify(map("skipped",List.of(map("hostId","delete","hostname","delete"),map("hostId","other","hostname","other")))));
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("machine-cleanup-");Files.createDirectories(root.resolve("conf"));Files.createSymbolicLink(root.resolve("web"),Paths.get("web").toAbsolutePath());Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("seedLegacyHost","false");p.setProperty("hostId","unused");p.setProperty("agentToken","unused");try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Disposable cleanup fixture");}
        try(AssessmentServer server=new AssessmentServer(root.toFile())){app=server;seed();HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);var workers=Executors.newFixedThreadPool(4);http.createContext("/",app);http.setExecutor(workers);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();
            try{var login=TestAccounts.login(base);cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];csrf=(String)obj(Json.parse(login.body())).get("csrfToken");var admin=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(admin,"Reader","reader@example.com","READER");app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),TestAccounts.PASSWORD);
                if(args.length>0&&args[0].equals("--serve")){System.out.println("CLEANUP_UI_URL="+base);System.out.flush();System.in.read();return;}
                var input=map("environmentId","prod","minimumSeconds",300,"hostIds",List.of("delete"));request(401,"POST","hosts/cleanup/preview",input,null,null);request(403,"POST","hosts/cleanup/preview",input,cookie,"wrong");
                var lr=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("email","reader@example.com","password",TestAccounts.PASSWORD)))).build(),HttpResponse.BodyHandlers.ofString());String rc=lr.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0],rt=(String)obj(Json.parse(lr.body())).get("csrfToken");
                request(403,"POST","hosts/cleanup/preview",input,rc,rt);request(403,"POST","hosts/cleanup",map(),rc,rt);request(403,"POST","hosts/cleanup",map(),cookie,"bad");
                for(Object ids:List.of(List.of(),List.of("delete","delete"),Collections.nCopies(101,"delete"),List.of(8)))call(400,"POST","hosts/cleanup/preview",map("environmentId","prod","minimumSeconds",300,"hostIds",ids));
                call(400,"POST","hosts/cleanup/preview",map("environmentId","prod","minimumSeconds",301,"hostIds",List.of("delete")));
                for(int[] threshold:new int[][]{{300,105},{86400,45},{604800,25},{2592000,5}}){var view=call(200,"GET","state?view=hosts&environmentId=dev&subset=disconnected&minimumSeconds="+threshold[0]+"&pageSize=20&page=999",null);check(((Number)obj(view.get("pagination")).get("total")).intValue()==threshold[1],"full count and unknown timestamps excluded");check(list(view.get("hosts")).size()<=20,"bounded page");}
                var before=preview("prod",300,"return","move","rename","delete");touch("return","lastSeen",Instant.now().toString());touch("move","environmentId","dev");touch("rename","hostname","renamed");histories();String other=Json.stringify(app.store.get("hosts","other"));
                var body=deletion(before);var invalid=new LinkedHashMap<>(body);invalid.put("confirmation","no");call(400,"POST","hosts/cleanup",invalid);check(app.store.get("hosts","delete")!=null,"wrong confirmation preserves machine");
                var result=call(200,"POST","hosts/cleanup",body);check(list(result.get("deleted")).size()==1&&list(result.get("skipped")).size()==3,"recontact/move/rename preserved; only eligible removed");
                for(String table:List.of("inventory","jobs","receipts","schedules","uninstall_requests","agent_update_requests","agent_update_operations","agent_update_assessments")){check(app.store.scalar("SELECT count(*) FROM "+table+" WHERE host_id='delete'")==0,"purged "+table);check(app.store.scalar("SELECT count(*) FROM "+table+" WHERE host_id='other'")>0,"other retained "+table);}
                check(app.store.scalar("SELECT count(*) FROM agent_update_steps WHERE id='step-delete'")==0&&app.store.scalar("SELECT count(*) FROM agent_update_steps WHERE id='step-other'")==1,"steps isolated");check(!Json.stringify(app.store.rows("SELECT * FROM agent_update_batches")).contains("delete"),"shared selection/skipped erased");check(other.equals(Json.stringify(app.store.get("hosts","other"))),"other host unchanged");
                var identity=app.store.get("identities","delete");check(Boolean.TRUE.equals(identity.get("deleted"))&&!identity.containsKey("agentToken"),"minimal blocked identity");check(new EnrollmentRegistry(app.store).authenticate("fixture-delete")==null,"old token blocked");var again=call(200,"POST","hosts/cleanup",body);check(list(again.get("deleted")).isEmpty(),"repeated request never deletes new machine");
                for(String view:List.of("hosts","runtimes","applications","servers","libraries","datasources","jobs")){var v=call(200,"GET","state?view="+view+"&q=delete",null);check(list(v.getOrDefault(view,List.of())).isEmpty(),"deleted record absent from view "+view);}
                host("changed-old-contact","prod",90000,"active");var stale=deletion(preview("prod",300,"changed-old-contact"));touch("changed-old-contact","lastSeen",Instant.now().minusSeconds(70000).toString());check(list(call(200,"POST","hosts/cleanup",stale).get("deleted")).isEmpty(),"new contact still offline invalidates preview");
                var lateBusy=deletion(preview("prod",300,"busy"));app.store.put("uninstall_requests",map("id","late-uninstall","hostId","busy","requestKey","late-uninstall-key","status","WAITING"));check(list(call(200,"POST","hosts/cleanup",lateBusy).get("deleted")).isEmpty(),"new lifecycle operation after preview blocks deletion");app.store.delete("uninstall_requests","late-uninstall");
                var old=preview("",300,"old-ol07","new-ol07");check(list(old.get("items")).stream().filter(i->Boolean.TRUE.equals(i.get("eligible"))).count()==1,"same name different identity preview");call(200,"POST","hosts/cleanup",deletion(old));check(app.store.get("hosts","old-ol07")==null&&app.store.get("hosts","new-ol07")!=null,"new installation preserved");
                app.store.put("jobs",map("id","running","hostId","busy","status","RUNNING","leaseExpires",System.currentTimeMillis()+60000));check(Boolean.FALSE.equals(list(preview("prod",300,"busy").get("items")).get(0).get("eligible")),"live collection lease blocked");app.store.delete("jobs","running");
                for(String status:List.of("WAITING","UNCONFIRMED","INSTALLING")){app.store.put("agent_update_requests",map("id","pending","hostId","busy","batchId","pending-batch","ordinal",0,"status",status));check(Boolean.FALSE.equals(list(preview("prod",300,"busy").get("items")).get(0).get("eligible")),"pending update blocked "+status);}app.store.delete("agent_update_requests","pending");
                for(String status:List.of("WAITING","UNINSTALLING","UNCONFIRMED")){app.store.put("uninstall_requests",map("id","pending-uninstall","hostId","busy","requestKey","pending-uninstall-key","status",status));check(Boolean.FALSE.equals(list(preview("prod",300,"busy").get("items")).get(0).get("eligible")),"pending uninstall blocked "+status);}app.store.delete("uninstall_requests","pending-uninstall");
                var rollback=deletion(preview("prod",300,"rollback-a","rollback-b"));app.store.exec("CREATE TRIGGER cleanup_rollback BEFORE INSERT ON human_audit WHEN NEW.target_id='rollback-b' BEGIN SELECT RAISE(ABORT,'fixture rollback'); END");call(500,"POST","hosts/cleanup",rollback);app.store.exec("DROP TRIGGER cleanup_rollback");check(app.store.get("hosts","rollback-a")!=null&&new EnrollmentRegistry(app.store).authenticate("fixture-rollback-a")!=null,"whole batch rollback preserves first machine and token");
                System.out.println("MachineCleanupHttpTest PASS: "+checks+" checks; real HTTP/SQLite, authorization, periods, races, identity isolation, full purge, shared batches, rollback and retry.");
            }finally{http.stop(0);workers.shutdownNow();}
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
