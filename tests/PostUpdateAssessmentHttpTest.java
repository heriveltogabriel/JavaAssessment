package assessment;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

/** Production API/poll/results hooks, real SQLite and generated signatures; loopback only. */
public final class PostUpdateAssessmentHttpTest {
    private static final String PASSWORD="Fixture assessment password 42!",OLD="2.0.2";
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static Path root;private static AssessmentServer app;private static HttpServer http;private static ExecutorService workers;private static String base;private static int checks;
    private record Session(String cookie,String csrf){}
    private record Response(int status,Map<String,Object> json,String cookie){}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> array(Object value){return (List<Object>)value;}
    private static String s(Object value){return value instanceof String?(String)value:"";}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Map<String,Object> relation(String id){return new PostUpdateAssessments(app.store).publicRelation(id);}
    private static Response request(String method,String path,Object body,Session session,String bearer)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));if(session!=null){builder.header("Cookie",session.cookie);if(session.csrf!=null)builder.header("X-CSRF-Token",session.csrf);}if(bearer!=null)builder.header("Authorization","Bearer "+bearer);if(body!=null)builder.header("Content-Type","application/json");
        var response=CLIENT.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofString());
        Map<String,Object> parsed=response.headers().firstValue("Content-Type").orElse("").startsWith("application/json")?obj(Json.parse(response.body())):Map.of();return new Response(response.statusCode(),parsed,response.headers().firstValue("Set-Cookie").orElse("").split(";",2)[0]);
    }
    private static Map<String,Object> human(int expected,String method,String path,Object body,Session session)throws Exception{var response=request(method,path,body,session,null);check(response.status==expected,path+" expected "+expected+" got "+response.status+" "+response.json);return response.json;}
    private static Map<String,Object> agent(int expected,String method,String path,Object body,String host)throws Exception{var response=request(method,path,body,null,"token-"+host);check(response.status==expected,path+" expected "+expected+" got "+response.status+" "+response.json);return response.json;}
    private static Session login(String email,String password)throws Exception{var response=request("POST","/api/login",map("email",email,"password",password),null,null);check(response.status==200,"fixture login");return new Session(response.cookie,s(response.json.get("csrfToken")));}
    private static void start()throws Exception{app=new AssessmentServer(root.toFile());http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);workers=Executors.newFixedThreadPool(4);http.createContext("/",app);http.setExecutor(workers);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();}
    private static void stop(){if(http!=null){http.stop(0);workers.shutdownNow();app.close();http=null;}}
    private static void host(String id,String environment){app.store.transaction(()->{app.store.put("hosts",map("id",id,"hostname",id,"environmentId",environment,"collectorVersion",OLD,"updateCapability","update-v2","lastSeen",AssessmentServer.now(),"coverage",map("status","complete")));app.store.put("identities",map("hostId",id,"status","active","agentToken","token-"+id,"installationId","fixture-installation-"+id));return null;});}
    private static Map<String,Object> order(String host,String key,Object automatic){Map<String,Object> body=map("hostIds",List.of(host),"targetVersion",DashboardVersion.VERSION,"requestKey",key,"expiresInMinutes",60);if(automatic!=null)body.put("runAssessmentAfterUpdate",automatic);return body;}
    private static String operation(Map<String,Object> created){return s(obj(array(created.get("requests")).get(0)).get("id"));}
    private static Map<String,Object> poll(String host)throws Exception{return agent(200,"GET","/api/agent/poll?hostId="+host+"&updateCapability=update-v2",null,host);}
    private static void claim(String host,String id)throws Exception{agent(200,"POST","/api/agent/update/claim",map("hostId",host,"requestId",id),host);}
    private static void report(String host,String id,String status,String version,String diagnostic)throws Exception{agent(200,"POST","/api/agent/update/report",map("hostId",host,"requestId",id,"status",status,"version",version,"diagnostic",diagnostic),host);}
    private static void heartbeat(String host)throws Exception{agent(200,"POST","/api/agent/heartbeat",map("hostId",host,"collectorVersion",DashboardVersion.VERSION,"updateCapability","update-v2"),host);}
    private static void completeUpdate(String host,String id)throws Exception{claim(host,id);report(host,id,"VERIFYING",DashboardVersion.VERSION,"OK");heartbeat(host);report(host,id,"SUCCEEDED",DashboardVersion.VERSION,"OK");}
    private static Map<String,Object> snapshot(String host,String job,String event){var result=map("schemaVersion","1.0","eventId",event,"jobId",job,"capturedAt",AssessmentServer.now(),"collectorJavaVersion","17.0.20","host",map("id",host,"hostname",host),"coverage",map("status","complete","warnings",List.of(),"stats",map()));for(String kind:List.of("runtimes","servers","applications","libraries","datasources","processes"))result.put(kind,List.of());return result;}

    private static void release()throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var key=generator.generateKeyPair();Path artifacts=root.resolve("dist/agent-updates/"+DashboardVersion.VERSION);Files.createDirectories(artifacts);byte[] pin=key.getPublic().getEncoded();Files.write(root.resolve("dist/agent-update-public.der"),pin);
        var metadata=new Manifest();metadata.getMainAttributes().putValue("Manifest-Version","1.0");metadata.getMainAttributes().putValue("Implementation-Version",DashboardVersion.VERSION);var stream=new ByteArrayOutputStream();try(var jar=new JarOutputStream(stream,metadata)){jar.putNextEntry(new JarEntry("fixture.txt"));jar.write("harmless fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();}byte[] bytes=stream.toByteArray();Files.write(artifacts.resolve("collector.jar"),bytes);
        Map<String,byte[]> assets=new LinkedHashMap<>();assets.put("update-public.der",pin);assets.put("update-service.sh","#!/bin/sh\nexit 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));assets.put("lib/fixture.jar","harmless support fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));var required=map();
        try(var zip=new ZipOutputStream(Files.newOutputStream(root.resolve("dist/assessment-collector.zip")))){for(var entry:assets.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();required.put(entry.getKey(),digest(entry.getValue()));}}
        byte[] manifest=Json.stringify(map("schema",1,"type","ASSESSMENT_AGENT_UPDATE_V1","version",DashboardVersion.VERSION,"format","collector-jar-v1","minUpdater",2,"sha256",digest(bytes),"bytes",bytes.length,"requiredFiles",required)).getBytes(java.nio.charset.StandardCharsets.UTF_8);var signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update(manifest);Files.write(artifacts.resolve("manifest.json"),manifest);Files.write(artifacts.resolve("manifest.sig"),signer.sign());
    }

    public static void main(String[] args)throws Exception{
        root=Files.createTempDirectory("post-update-assessment-http-");Files.createDirectories(root.resolve("conf"));var properties=new Properties();properties.setProperty("hostId","unused-fixture-host");properties.setProperty("agentToken","unused-fixture-token");properties.setProperty("seedLegacyHost","false");properties.setProperty("adminSalt","fixture-salt");properties.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltlegacy-fixture"));properties.setProperty("jobHistoryPerHost","10");try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){properties.store(out,"Loopback assessment fixture");}
        try{
            release();start();var boot=request("POST","/api/auth/bootstrap",map("currentPassword","legacy-fixture","name","Fixture administrator","email","admin@example.com","password",PASSWORD),null,null);check(boot.status==200,"bootstrap isolated administrator");Session admin=new Session(boot.cookie,s(boot.json.get("csrfToken")));
            var administrator=app.auth.authenticate("admin@example.com",PASSWORD);var temporary=app.auth.create(administrator,"Fixture reader","reader@example.com","READER");app.auth.changePassword(temporary.user().id(),temporary.user().revision(),temporary.password(),PASSWORD);Session reader=login("reader@example.com",PASSWORD);
            app.store.transaction(()->{for(String env:List.of("env-a","env-b","env-c"))app.store.put("environments",map("id",env,"name",env,"revision","fixture","settings",map("enabled",false,"includePaths",List.of("/opt/original"),"maxDurationSeconds",22)));return null;});
            for(String host:List.of("auto","disabled","omitted","invalid"))host(host,"env-a");host("reuse","env-b");host("timeout","env-c");
            human(403,"POST","/api/agent-updates",order("auto","reader-order-fixture",true),reader);human(403,"POST","/api/agent-updates",order("auto","csrf-order-fixture",true),new Session(admin.cookie,null));human(400,"POST","/api/agent-updates",order("invalid","invalid-flag-fixture","true"),admin);
            var created=human(200,"POST","/api/agent-updates",order("auto","automatic-order-fixture",true),admin);String id=operation(created),batch=s(created.get("batchId"));check(relation(id)!=null&&relation(id).get("status").equals("WAITING_FINAL"),"HTTP creation persists final-only intent");
            check(operation(human(200,"POST","/api/agent-updates",order("auto","automatic-order-fixture",true),admin)).equals(id),"HTTP requestKey replay retains operation");human(409,"POST","/api/agent-updates",order("auto","automatic-order-fixture",false),admin);
            claim("auto",id);report("auto",id,"VERIFYING",DashboardVersion.VERSION,"OK");heartbeat("auto");check(s(poll("auto").get("jobId")).isEmpty()&&app.store.scalar("SELECT count(*) FROM jobs WHERE host_id='auto'")==0,"target heartbeat without final report cannot lease assessment");
            human(200,"PUT","/api/environments",map("environmentId","env-a","settings",map("enabled",false,"includePaths",List.of("/opt/new-scope"),"maxDurationSeconds",27,"maxFiles",321)),admin);
            report("auto",id,"SUCCEEDED",DashboardVersion.VERSION,"OK");report("auto",id,"SUCCEEDED",DashboardVersion.VERSION,"OK");var lease=poll("auto");String job=s(lease.get("jobId"));check(!job.isEmpty()&&job.equals(relation(id).get("jobId")),"final report enables one linked lease");
            var settings=obj(lease.get("settings"));check(Boolean.FALSE.equals(settings.get("enabled"))&&array(settings.get("includePaths")).contains("/opt/new-scope")&&((Number)settings.get("maxFiles")).longValue()==321,"lease uses current scope/limits with automatic schedule disabled");
            check(app.store.scalar("SELECT count(*) FROM jobs WHERE host_id='auto'")==1&&s(poll("auto").get("jobId")).isEmpty(),"replayed report and poll never duplicate running collection");
            var updateDetail=human(200,"GET","/api/work-requests/detail?id="+batch+"&type=AGENT_UPDATE",null,reader);check(Json.stringify(updateDetail).contains(job),"reader sees assessment link from update detail");
            var jobDetail=human(200,"GET","/api/work-requests/detail?id="+job+"&type=ASSESSMENT",null,reader);check(Json.stringify(jobDetail).contains(id),"assessment detail links to update origin");
            var result=snapshot("auto",job,"post-update-event-0001");agent(200,"POST","/api/agent/results",result,"auto");check(Boolean.TRUE.equals(agent(200,"POST","/api/agent/results",result,"auto").get("duplicate")),"result receipt remains idempotent");
            check(relation(id).get("status").equals("SUCCEEDED")&&app.store.get("agent_update_requests",id).get("status").equals("SUCCEEDED"),"result completes relation without changing final update");
            for(String name:List.of("disabled","omitted")){var order=human(200,"POST","/api/agent-updates",order(name,name+"-order-fixture",name.equals("disabled")?false:null),admin);String operation=operation(order);completeUpdate(name,operation);check(relation(operation)==null&&s(poll(name).get("jobId")).isEmpty(),"false/old-client omission never creates assessment: "+name);}
            String pending=s(human(202,"POST","/api/jobs",map("hostId","reuse"),admin).get("id"));var reused=human(200,"POST","/api/agent-updates",order("reuse","reuse-order-fixture",true),admin);String reusedId=operation(reused);completeUpdate("reuse",reusedId);check(pending.equals(poll("reuse").get("jobId"))&&Boolean.TRUE.equals(relation(reusedId).get("reused")),"HTTP flow reuses manual QUEUED job after final update");agent(200,"POST","/api/agent/results",snapshot("reuse",pending,"post-update-event-0002"),"reuse");
            var failed=human(200,"POST","/api/agent-updates",order("timeout","timeout-order-fixture",true),admin);String failedId=operation(failed);completeUpdate("timeout",failedId);String timedJob=s(poll("timeout").get("jobId"));check(!timedJob.isEmpty(),"timeout fixture receives a real lease");app.store.transaction(()->{var record=app.store.get("jobs",timedJob);record.put("leaseExpires",System.currentTimeMillis()-1);app.store.put("jobs",record);return null;});app.maintainOperations();check(s(poll("timeout").get("jobId")).isEmpty()&&relation(failedId).get("status").equals("FAILED")&&app.store.get("agent_update_requests",failedId).get("status").equals("SUCCEEDED"),"expired collection lease leaves update successful and does not retry");
            app.store.transaction(()->{long now=System.currentTimeMillis();for(int i=0;i<12;i++)app.store.put("jobs",map("id","history-fixture-"+i,"hostId","auto","status","SUCCEEDED","createdAt",java.time.Instant.ofEpochMilli(now+1000+i).toString(),"finishedAt",AssessmentServer.now(),"summary","Fixture later history"));return null;});human(202,"POST","/api/jobs",map("hostId","auto"),admin);
            check(app.store.get("jobs",job)==null&&Boolean.FALSE.equals(relation(id).get("detailsAvailable"))&&relation(id).get("status").equals("SUCCEEDED")&&!s(relation(id).get("summary")).isEmpty(),"real trimJobs preserves result summary and safe unavailable-details marker");
            check(!Json.stringify(human(200,"GET","/api/work-requests/detail?id="+batch+"&type=AGENT_UPDATE",null,admin)).contains("token-auto"),"linked public history exposes no collector credentials");
            System.out.println("PostUpdateAssessmentHttpTest PASS: "+checks+" checks; signed update API/final-only/flag replay/real poll/current settings/reuse/receipt/lease expiry/history trimming/auth; loopback only.");
        }finally{stop();try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
