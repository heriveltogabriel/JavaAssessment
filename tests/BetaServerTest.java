package assessment;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

/** Real isolated HTTP regressions for SQLite Beta. Never accesses a configured VM. */
public final class BetaServerTest {
    static AssessmentServer app;static HttpServer http;static ExecutorService pool;static Path root;
    static String base,cookie,csrf;static final HttpClient client=HttpClient.newHttpClient();
    record Response(int code,Map<String,Object> body){}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    static List<?> list(Object v){return (List<?>)v;}
    static void check(boolean condition,String what){if(!condition)throw new AssertionError(what);}
    static HttpResponse<byte[]> raw(String method,String path,Object body,boolean admin,String token)throws Exception{
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));if(admin){b.header("Cookie",cookie);b.header("X-CSRF-Token",csrf);}if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json");
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static Response call(String method,String path,Object body,boolean admin,String token)throws Exception{var r=raw(method,path,body,admin,token);return new Response(r.statusCode(),obj(Json.parse(new String(r.body(),java.nio.charset.StandardCharsets.UTF_8),32*1024*1024)));}
    static Map<String,Object> ok(String method,String path,Object body,boolean admin,String token,int code)throws Exception{Response r=call(method,path,body,admin,token);check(r.code==code,path+" expected"+code+" got"+r);return r.body;}
    static void start()throws Exception{
        app=new AssessmentServer(root.toFile());http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);pool=Executors.newFixedThreadPool(8);http.setExecutor(pool);http.createContext("/",app);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();
        var response=raw("POST","/api/login",map("password","fixture-password"),false,null);cookie=response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];csrf=(String)obj(Json.parse(new String(response.body()))).get("csrfToken");
    }
    static void stop(){if(http!=null){http.stop(0);pool.shutdownNow();app.close();http=null;}}
    static Map<String,Object> registration(String installation){return map("installationId",installation,"hostname",installation,"collectorVersion","fixture","javaVersion","1.6.0_45","osName","Linux","architecture","x86_64");}
    static Map<String,Object> snapshot(String host,String job,String event,String time){Map<String,Object> snap=map("schemaVersion","1.0","eventId",event,"jobId",job,"capturedAt",time,"collectorJavaVersion","1.6.0_45","host",map("id",host,"hostname","node-"+host),"coverage",map("status","partial","warnings",List.of("Configured path missing"),"stats",map("filesVisited",100)));
        for(String kind:List.of("runtimes","servers","applications","libraries","datasources","processes"))snap.put(kind,new ArrayList<>());
        snap.put("runtimes",List.of(map("id","same-item","hostId",host,"kind","JDK","version","1.6.0_45","observedAt",time),map("id","omit-jre","hostId",host,"kind","JRE","version","1.6.0_45","observedAt",time)));
        snap.put("libraries",List.of(map("id","same-item","hostId",host,"name","Driver","version","1","category","JDBC","applicationId","app","observedAt",time)));
        snap.put("applications",List.of(map("id","app","hostId",host,"name","App","type","Spring Boot","serverName","Tomcat","observedAt",time)));
        snap.put("datasources",List.of(map("id","ds","hostId",host,"name","jdbc/example","state","CONFIGURED","applicationId","app","observedAt",time)));
        snap.put("servers",List.of(map("id","server","hostId",host,"name","Tomcat","type","Tomcat","state","PROCESS_RUNNING","observedAt",time)));return snap;
    }
    static String queue(String id)throws Exception{return (String)ok("POST","/api/jobs",map("hostId",id),true,null,202).get("id");}
    static void poll(String id,String token,String job)throws Exception{check(job.equals(ok("GET","/api/agent/poll?hostId="+id,null,false,token,200).get("jobId")),"correct job lease");}
    static Properties packageCode(String env)throws Exception{var r=raw("POST","/api/environments/package",map("environmentId",env,"maxUses",10,"autoCollect",false),true,null);check(r.statusCode()==200,"configured package download");Properties props=new Properties();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(r.body()))){ZipEntry e;while((e=z.getNextEntry())!=null)if(e.getName().equals("conf/bootstrap.properties"))props.load(new ByteArrayInputStream(z.readAllBytes()));}check("https://fixture.invalid".equals(props.getProperty("serverUrl")),"configured dashboard URL");return props;}
    public static void main(String[] args)throws Exception{
        root=Files.createTempDirectory("assessment-beta-functional-");Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));
        Properties c=new Properties();c.setProperty("seedLegacyHost","false");c.setProperty("adminSalt","fixture-salt");c.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));c.setProperty("hostId","unused-fixture");c.setProperty("agentToken","unused-fixture");c.setProperty("publicUrl","https://fixture.invalid");try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){c.store(out,"isolated");}
        try(var zip=new ZipOutputStream(Files.newOutputStream(root.resolve("dist/assessment-collector.zip")))){for(String name:List.of("install.sh","system-install.sh","bootstrap-config.sh","collector.jar","conf/ca.pem")){zip.putNextEntry(new ZipEntry(name));zip.write("fixture".getBytes());zip.closeEntry();}}
        try{
            start();check(call("GET","/api/state",null,false,null).code==401,"anonymous denied");String valid=csrf;csrf="wrong";check(call("POST","/api/environments",map("name","No"),true,null).code==403,"CSRF denied");csrf=valid;
            String env=(String)ok("POST","/api/environments",map("name","Desenv","settings",map("maxFiles",2500)),true,null,201).get("id"),other=(String)ok("POST","/api/environments",map("name","Homologa"),true,null,201).get("id");
            check(call("POST","/api/environments",map("name","desenv"),true,null).code==409,"duplicate name");Properties code=packageCode(env),otherCode=packageCode(other);
            Map<String,Object> first=ok("POST","/api/agent/register",registration("fixture-install-0001"),false,code.getProperty("enrollmentToken"),200);String h=(String)first.get("hostId"),token=(String)first.get("agentToken");
            check(((Number)obj(first.get("settings")).get("maxFiles")).intValue()==2500,"env settings inherit");Map<String,Object> retry=ok("POST","/api/agent/register",registration("fixture-install-0001"),false,code.getProperty("enrollmentToken"),200);check(token.equals(retry.get("agentToken")),"registration retry same token");
            var second=ok("POST","/api/agent/register",registration("fixture-install-0002"),false,otherCode.getProperty("enrollmentToken"),200);String h2=(String)second.get("hostId"),t2=(String)second.get("agentToken");
            check(call("POST","/api/agent/heartbeat",map("hostId",h2),false,token).code==403,"host impersonation blocked");ok("POST","/api/agent/heartbeat",map("hostId",h,"javaVersion","1.6.0_45"),false,token,200);
            ok("PUT","/api/hosts/settings",map("hostId",h,"settings",map("batchSize",17)),true,null,200);var props=ok("GET","/api/state?view=settings&hostId="+h,null,true,null,200);check(((Number)obj(props.get("settings")).get("batchSize")).intValue()==17,"machine effective settings");
            String j=queue(h);check(call("POST","/api/jobs",map("hostId",h),true,null).code==409,"overlap denied");poll(h,token,j);stop();start();check("FAILED".equals(app.store.get("jobs",j).get("status")),"restart marks interrupted");
            String timestamp=AssessmentServer.now();Map<String,Object> snap=snapshot(h,j,"fixture-event-00001",timestamp);ok("POST","/api/agent/results",snap,false,token,200);check(Boolean.TRUE.equals(ok("POST","/api/agent/results",snap,false,token,200).get("duplicate")),"result idempotency");check(Boolean.TRUE.equals(app.store.get("jobs",j).get("lateResult")),"late result preserved");
            check(String.valueOf(app.store.get("jobs",j).get("summary")).contains("1 Java installation(s)"),"job summary excludes JRE");
            String j2=queue(h2);poll(h2,t2,j2);ok("POST","/api/agent/results",snapshot(h2,j2,"fixture-event-00002",timestamp),false,t2,200);
            check(list(ok("GET","/api/state?view=jobs&q=Completed",null,true,null,200).get("jobs")).size()==2,"search visible job status");
            Map<String,Object> view=ok("GET","/api/state?view=runtimes&environmentId="+env,null,true,null,200);check(list(view.get("runtimes")).size()==1&&((Number)obj(view.get("pagination")).get("total")).intValue()==1,"scope and JRE omission");check("Java 6".equals(obj(list(view.get("javaGroups")).get(0)).get("name")),"major label");
            check(call("GET","/api/state?view=machine&hostId="+h2+"&environmentId="+env,null,true,null).code==404,"scope mismatch denied");
            check(list(ok("GET","/api/state?view=versions&version=Java+6",null,true,null,200).get("runtimes")).size()==2,"major version filter");
            view=ok("GET","/api/state?view=libraries&environmentId="+env+"&pageSize=1",null,true,null,200);check(((Number)obj(view.get("pagination")).get("total")).intValue()==2&&list(view.get("libraries")).size()+list(view.get("datasources")).size()==1,"combined library pagination");
            view=ok("GET","/api/state?view=runtimes&q=node-"+h,null,true,null,200);check(((Number)obj(view.get("pagination")).get("total")).intValue()==1,"hostname search across inventory");
            Map<String,Object> detail=ok("GET","/api/item?kind=libraries&id=same-item&hostId="+h,null,true,null,200);check(h.equals(obj(detail.get("item")).get("hostId")),"same item ID on different machines scoped");check(list(obj(detail.get("related")).get("applications")).size()==1,"related application");
            view=ok("GET","/api/state?view=environments&environmentId="+env,null,true,null,200);for(Object value:list(view.get("enrollments")))check(env.equals(obj(value).get("environmentId")),"enrollment summaries scoped");
            check(((Number)obj(view.get("defaultSettings")).get("maxFiles")).intValue()==10000,"default settings separate from effective scope");
            check(list(ok("GET","/api/state?hostId="+h,null,true,null,200).get("enrollments")).isEmpty(),"machine has no environment codes");
            check(call("GET","/api/state?view=anything",null,true,null).code==400,"invalid view");check(call("GET","/api/state?pageSize=100000",null,true,null).code==400,"bounded page");check(call("GET","/api/state?from=not-a-date",null,true,null).code==400,"invalid date");
            Map<String,Object> exported=ok("GET","/api/export?environmentId="+env,null,true,null,200);check(list(exported.get("hosts")).size()==1&&list(exported.get("runtimes")).size()==1,"export full selected scope");String serialized=Json.stringify(exported);for(String forbidden:List.of(token,t2,"agentToken","tokenHash","installationId"))check(!serialized.contains(forbidden),"export excludes credentials");
            Map<String,Object> bulk=ok("POST","/api/jobs/run-scope",map("environmentId",env),true,null,202);check(((Number)bulk.get("queued")).intValue()==1,"bulk scope");
            String pending=(String)app.store.documents("SELECT payload FROM jobs WHERE host_id=? AND status='QUEUED'",h).get(0).get("id");poll(h,token,pending);
            Map<String,Object> invalidHost=snapshot(h,pending,"fixture-event-shape",timestamp);obj(invalidHost.get("host")).put("disks","not an array");check(call("POST","/api/agent/results",invalidHost,false,token).code==400,"malformed host rejected");
            Map<String,Object> oversized=snapshot(h,pending,"fixture-event-large",timestamp);oversized.put("libraries",List.of(map("id","large","hostId",h,"name","x".repeat(17000))));check(call("POST","/api/agent/results",oversized,false,token).code==400,"per-record memory limit");
            Map<String,Object> bad=snapshot(h,pending,"fixture-event-bad01",timestamp);bad.put("libraries",List.of(map("id","bad","hostId",h2)));check(call("POST","/api/agent/results",bad,false,token).code==400,"cross-host snapshot rejected atomically");check(app.store.scalar("SELECT count(*) FROM inventory WHERE host_id=?",h)==5,"invalid result preserves previous inventory");
            // Simulate a write failure partway through replacing a host, verify atomic rollback.
            app.store.exec("CREATE TRIGGER fixture_failure BEFORE INSERT ON inventory WHEN NEW.kind='libraries' BEGIN SELECT RAISE(ABORT,'fixture failure'); END");check(call("POST","/api/agent/results",snapshot(h,pending,"fixture-event-fail1",timestamp),false,token).code==500,"write failure reported");check(app.store.scalar("SELECT count(*) FROM receipts WHERE event_id='fixture-event-fail1'")==0,"failed transaction not acknowledged");check("RUNNING".equals(app.store.get("jobs",pending).get("status")),"lease preserved on failure");app.store.exec("DROP TRIGGER fixture_failure");ok("POST","/api/agent/results",snapshot(h,pending,"fixture-event-fail1",timestamp),false,token,200);
            check(call("DELETE","/api/environments",map("environmentId",env,"confirmationName","desenv"),true,null).code==400,"exact name confirm");check(call("DELETE","/api/environments",map("environmentId",env,"confirmationName","Desenv"),true,null).code==409,"nonempty deletion denied");ok("PUT","/api/hosts/environment",map("hostId",h,"environmentId",other),true,null,200);ok("DELETE","/api/environments",map("environmentId",env,"confirmationName","Desenv"),true,null,200);
            check(call("POST","/api/agent/register",registration("fixture-install-0001"),false,code.getProperty("enrollmentToken")).code==403,"deleted environment package cannot recover host");ok("GET","/api/agent/settings?hostId="+h,null,false,token,200);
            ok("PUT","/api/settings",map("enabled",true,"frequency","daily","windowStart","00:00","windowEnd","00:00"),true,null,200);check(ok("GET","/api/agent/poll?hostId="+h,null,false,token,200).get("jobId")!=null,"daily job scheduled");check(ok("GET","/api/agent/poll?hostId="+h,null,false,token,200).get("jobId")==null,"no scheduled overlap");
            ok("POST","/api/hosts/revoke",map("hostId",h),true,null,200);check(call("POST","/api/agent/heartbeat",map("hostId",h),false,token).code==401,"revoked token blocked");stop();start();check(call("POST","/api/agent/heartbeat",map("hostId",h),false,token).code==401,"revocation persists");check(app.store.scalar("SELECT count(*) FROM inventory")==10,"inventory survives restart");check(!Files.exists(root.resolve("data/state.json")),"no fleet JSON persistence");
            System.out.println("BETA_SERVER_TESTS=PASS auth csrf package registration retry inheritance jobs restart late-result idempotency JRE scoped-pages union-libraries search detail export redaction bulk atomic-failure deletion revocation schedule persistence");
        }finally{stop();try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
