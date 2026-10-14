package assessment;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Reproducible 1,000-host load test. Uses a disposable local database and loopback HTTP only. */
public final class BetaLoadTest {
    private static final int HOSTS=1000, DURATION_SECONDS=60, HEARTBEATS_PER_SECOND=17;
    private static final String[] KINDS={"runtimes","processes","servers","applications","libraries","datasources"};
    private static final String[] JAVA_VERSIONS={"1.6.0_45","1.8.0_452","17.0.20","21.0.12"};
    private static final String FIXTURE_TIME="2026-10-01T12:00:00.000Z";
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
    private String base,cookie,csrf;
    private record Reply(int status,Map<String,Object> body,int bytes,double millis,HttpHeaders headers){}
    private record QueryCase(String label,String path,String collection,int maximumRows){}
    private static final class Samples {
        final List<Double> millis=new ArrayList<>();int minBytes=Integer.MAX_VALUE,maxBytes;
        synchronized void add(Reply reply){millis.add(reply.millis);minBytes=Math.min(minBytes,reply.bytes);maxBytes=Math.max(maxBytes,reply.bytes);}
        synchronized Map<String,Object> report(){List<Double> sorted=new ArrayList<>(millis);Collections.sort(sorted);return map("requests",sorted.size(),"p50Ms",percentile(sorted,.50),"p95Ms",percentile(sorted,.95),"maxMs",percentile(sorted,1),"minResponseBytes",minBytes==Integer.MAX_VALUE?0:minBytes,"maxResponseBytes",maxBytes);}
    }
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    private static double percentile(List<Double> sorted,double percentile){return sorted.isEmpty()?0:Math.round(sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*percentile)-1))*100.0)/100.0;}
    private static String id(int number){return String.format(Locale.ROOT,"load-host-%04d",number);}
    private static String token(int number){return "load-fixture-token-"+number+"-local-only";}
    private static long heap(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    private static long settledHeap()throws InterruptedException {System.gc();Thread.sleep(200);return heap();}
    private static Long rssKiB(){
        try {Process process=new ProcessBuilder("ps","-o","rss=","-p",Long.toString(ProcessHandle.current().pid())).start();String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();return process.waitFor()==0?Long.valueOf(text):null;}
        catch(Exception ignored){return null;}
    }
    private static Map<String,Object> coverage(){return map("status","complete","lastAssessmentAt",FIXTURE_TIME,"warnings",List.of(),"stats",map("filesExamined",850,"durationMillis",2200));}
    private static Map<String,Object> host(int n){return map("id",id(n),"hostname","assessment-node-"+n,"environmentId","load-env-"+(n%10),"status","Collected","osName","Linux","distribution","Oracle Linux 8","architecture","amd64","logicalCpus",4,"memoryTotalBytes",8589934592L,"memoryAvailableBytes",4294967296L,"lastSeen",FIXTURE_TIME,"lastAssessmentAt",FIXTURE_TIME,"collectorJavaVersion","1.6.0_45","collectorVersion","1.0-beta","settings",map(),"coverage",coverage());}
    private static Map<String,Object> row(int n,String kind,int index){
        Map<String,Object> result=map("id",id(n)+"-"+kind+"-"+index,"hostId",id(n),"name",kind+" component "+index,"observedAt",FIXTURE_TIME,"evidence","Local inventory fixture; observed file or live Java process");
        switch(kind){
            case "runtimes":result.putAll(map("version",JAVA_VERSIONS[index],"kind","JDK","vendor","Oracle/OpenJDK","javaHome","/opt/java/"+JAVA_VERSIONS[index],"architecture","amd64","inUse",index%2==0));break;
            case "processes":result.putAll(map("pid",1000+index,"javaHome","/opt/java/"+JAVA_VERSIONS[index],"mainClass","com.example.Service"+index,"applicationId",id(n)+"-applications-"+index));break;
            case "servers":result.putAll(map("type",index==0?"WebLogic":"Tomcat","version",index==0?"12.2.1.4":"9.0.99","path","/opt/servers/"+index,"state","PROCESS_RUNNING"));break;
            case "applications":result.putAll(map("type",index%2==0?"Spring Boot":"WAR","version","2.5."+index,"path","/opt/apps/service-"+index+".jar","javaHome","/opt/java/"+JAVA_VERSIONS[index],"state","PROCESS_RUNNING"));break;
            case "libraries":result.putAll(map("name",index%2==0?"ojdbc":"spring-core","version",index%2==0?"19.22.0.0":"5.3.39","category",index%2==0?"JDBC":"Spring","path","/opt/apps/lib/library-"+index+".jar","usage","detected"));break;
            case "datasources":result.putAll(map("name","jdbc/Service"+index,"type","JDBC","driverClass","oracle.jdbc.OracleDriver","version","19.22.0.0","target","fixture-db-"+index,"state",null));break;
            default:throw new IllegalArgumentException(kind);
        }
        return result;
    }
    private static List<Object> rows(int n,String kind){
        int count=switch(kind){case "runtimes"->4;case "processes","applications"->3;case "servers","datasources"->2;case "libraries"->20;default->0;};
        List<Object> rows=new ArrayList<>(count);for(int i=0;i<count;i++)rows.add(row(n,kind,i));return rows;
    }
    private static void seed(InventoryStore store){
        store.transaction(()->{
            for(int i=0;i<10;i++)store.put("environments",map("id","load-env-"+i,"name","Environment "+i,"description","Local load fixture","settings",map(),"revision","fixture-revision","createdAt",FIXTURE_TIME,"updatedAt",FIXTURE_TIME));
            return null;
        });
        for(int n=0;n<HOSTS;n++){
            final int index=n;
            store.transaction(()->{
                store.put("hosts",host(index));
                store.put("identities",map("hostId",id(index),"installationId","load-installation-"+index,"agentToken",token(index),"status","active","createdAt",FIXTURE_TIME));
                for(String kind:KINDS)store.putInventory(kind,id(index),rows(index,kind));
                store.put("jobs",map("id","load-completed-job-"+index,"hostId",id(index),"status","SUCCEEDED","createdAt",FIXTURE_TIME,"finishedAt",FIXTURE_TIME,"summary","Fixture assessment completed"));
                return null;
            });
        }
    }
    private Reply request(String method,String path,Object data,boolean admin,String agentToken)throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(admin){builder.header("Cookie",cookie);builder.header("X-CSRF-Token",csrf);}
        if(agentToken!=null)builder.header("Authorization","Bearer "+agentToken);
        if(data==null)builder.method(method,HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(Json.stringify(data)));
        long start=System.nanoTime();HttpResponse<byte[]> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        double elapsed=(System.nanoTime()-start)/1_000_000.0;
        return new Reply(response.statusCode(),obj(Json.parse(new String(response.body(),StandardCharsets.UTF_8),32*1024*1024)),response.body().length,elapsed,response.headers());
    }
    private void login()throws Exception {
        var reply=TestAccounts.login(base,"load-fixture-password");
        cookie=reply.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];csrf=String.valueOf(obj(Json.parse(reply.body())).get("csrfToken"));
    }
    private Reply query(QueryCase query)throws Exception {
        Reply reply=request("GET",query.path,null,true,null);check(reply.status==200,query.label+" returned HTTP "+reply.status);
        if(query.collection!=null){Object value=reply.body.get(query.collection);check(value instanceof List,query.label+" provides "+query.collection);check(((List<?>)value).size()<=query.maximumRows,query.label+" pagination bounds");}
        if(query.collection!=null&&query.collection.equals("libraries"))check(((List<?>)reply.body.get("libraries")).size()+((List<?>)reply.body.get("datasources")).size()<=query.maximumRows,"combined libraries page bounds");
        long expectedTotal=switch(query.label){case "hosts-page-10"->1000;case "environment"->100;case "java-8"->1000;case "applications"->2000;case "libraries"->22000;case "environment-libraries"->1000;default->-1;};
        if(expectedTotal>=0)check(((Number)obj(reply.body.get("pagination")).get("total")).longValue()==expectedTotal,query.label+" filtered total: "+obj(reply.body.get("pagination")).get("total"));
        if(query.label.equals("hosts-page-10"))check(((Number)obj(reply.body.get("pagination")).get("page")).intValue()==10,"requested page retained");
        if(query.label.equals("environment"))for(Object value:(List<?>)reply.body.get("hosts"))check(obj(value).get("environmentId").equals("load-env-3"),"environment membership filtered");
        if(query.label.equals("java-8"))for(Object value:(List<?>)reply.body.get("runtimes"))check(obj(value).get("version").equals("1.8.0_452"),"Java family filtered");
        if(query.label.equals("host-details"))check(((List<?>)reply.body.get("hosts")).size()==1&&obj(((List<?>)reply.body.get("hosts")).get(0)).get("id").equals("load-host-0003"),"machine scope preserved");
        check(reply.bytes<512*1024,query.label+" bounded response size");
        String serialized=Json.stringify(reply.body);for(String secret:List.of("agentToken","tokenHash","installationId","load-fixture-token-"))check(!serialized.contains(secret),"public response excludes private identity fields");
        return reply;
    }
    private Reply heartbeat(int number)throws Exception {
        Reply reply=request("POST","/api/agent/heartbeat",map("hostId",id(number),"hostname","assessment-node-"+number,"collectorVersion","1.0-beta","javaVersion","1.6.0_45"),false,token(number));
        check(reply.status==200,"heartbeat returned HTTP "+reply.status);return reply;
    }
    private void result(int number,int ordinal)throws Exception {
        Reply queued=request("POST","/api/jobs",map("hostId",id(number)),true,null);check(queued.status==202,"load job queue HTTP "+queued.status);String jobId=String.valueOf(queued.body.get("id"));
        Reply leased=request("GET","/api/agent/poll?hostId="+id(number),null,false,token(number));check(leased.status==200&&jobId.equals(leased.body.get("jobId")),"collector obtains queued lease");
        Map<String,Object> snapshot=map("schemaVersion","1.0","eventId","load-event-"+ordinal,"jobId",jobId,"capturedAt",Instant.now().toString(),"collectorJavaVersion","1.6.0_45","host",map("id",id(number),"hostname","assessment-node-"+number),"coverage",coverage());
        for(String kind:KINDS)snapshot.put(kind,rows(number,kind));
        Reply accepted=request("POST","/api/agent/results",snapshot,false,token(number));check(accepted.status==200&&Boolean.TRUE.equals(accepted.body.get("accepted")),"assessment accepted under load");
        Reply duplicate=request("POST","/api/agent/results",snapshot,false,token(number));check(duplicate.status==200&&Boolean.TRUE.equals(duplicate.body.get("duplicate")),"assessment retry remains idempotent under load");
    }
    private static void failAsync(AtomicReference<Throwable> failure,Throwable error){failure.compareAndSet(null,error);}
    private static void rethrowAsync(AtomicReference<Throwable> failure){Throwable error=failure.get();if(error!=null)throw new AssertionError("Concurrent collector request failed",error);}
    private Map<String,Object> measure(AssessmentServer app)throws Exception {
        QueryCase[] queries={
            new QueryCase("fleet","/api/state?view=overview&page=1&pageSize=50","hosts",50),
            new QueryCase("hosts-page-10","/api/state?view=hosts&page=10&pageSize=50","hosts",50),
            new QueryCase("environment","/api/state?view=hosts&environmentId=load-env-3&page=1&pageSize=50","hosts",50),
            new QueryCase("java-8","/api/state?view=runtimes&version=8&versionMode=family&page=1&pageSize=50","runtimes",50),
            new QueryCase("applications","/api/state?view=applications&q=Spring%20Boot&page=1&pageSize=50","applications",50),
            new QueryCase("libraries","/api/state?view=libraries&page=5&pageSize=50","libraries",50),
            new QueryCase("host-details","/api/state?view=machine&hostId=load-host-0003&page=1&pageSize=50","hosts",1),
            new QueryCase("environment-libraries","/api/state?view=libraries&environmentId=load-env-7&q=ojdbc&page=1&pageSize=50","libraries",50)
        };
        for(int warm=0;warm<5;warm++)for(QueryCase query:queries)query(query);
        long heapBefore=settledHeap();Long rssBefore=rssKiB();AtomicLong heapPeak=new AtomicLong(heapBefore);
        ScheduledExecutorService collectors=Executors.newScheduledThreadPool(4);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger beats=new AtomicInteger(),results=new AtomicInteger();
        Samples heartbeatSamples=new Samples();Map<String,Samples> byCase=new LinkedHashMap<>();for(QueryCase query:queries)byCase.put(query.label,new Samples());Samples all=new Samples();
        long started=System.nanoTime(),deadline=started+Duration.ofSeconds(DURATION_SECONDS).toNanos();
        ScheduledFuture<?> heartbeatTask=collectors.scheduleAtFixedRate(()->{
            try{int n=beats.getAndIncrement();heartbeatSamples.add(heartbeat(n%HOSTS));}catch(Throwable e){failAsync(failure,e);}
        },0,1_000_000_000L/HEARTBEATS_PER_SECOND,TimeUnit.NANOSECONDS);
        ScheduledFuture<?> resultTask=collectors.scheduleAtFixedRate(()->{
            try{int n=results.getAndIncrement();result((900+n)%HOSTS,n);}catch(Throwable e){failAsync(failure,e);}
        },1,5,TimeUnit.SECONDS);
        ScheduledFuture<?> heapTask=collectors.scheduleAtFixedRate(()->heapPeak.accumulateAndGet(heap(),Math::max),0,100,TimeUnit.MILLISECONDS);
        try {
            int index=0;
            while(System.nanoTime()<deadline){
                rethrowAsync(failure);QueryCase query=queries[index++%queries.length];Reply reply=query(query);byCase.get(query.label).add(reply);all.add(reply);
                long pause=200-(long)reply.millis;if(pause>0)Thread.sleep(pause);
            }
        } finally {
            heartbeatTask.cancel(false);resultTask.cancel(false);heapTask.cancel(false);collectors.shutdown();check(collectors.awaitTermination(15,TimeUnit.SECONDS),"collector load workers finish");
        }
        rethrowAsync(failure);long elapsed=(System.nanoTime()-started)/1_000_000;long heapAfter=settledHeap();Long rssAfter=rssKiB();
        check(beats.get()>=HEARTBEATS_PER_SECOND*DURATION_SECONDS*.90,"sustained heartbeat rate >= 90% of target");check(results.get()>=10,"repeated full assessments during load");
        Map<String,Object> queryMetrics=new LinkedHashMap<>();for(Map.Entry<String,Samples> entry:byCase.entrySet()){
            Map<String,Object> report=entry.getValue().report();queryMetrics.put(entry.getKey(),report);
            check(((Number)report.get("requests")).intValue()>=20,"enough samples for "+entry.getKey());check(((Number)report.get("p95Ms")).doubleValue()<500,"query p95 under 500ms: "+entry.getKey()+" "+report.get("p95Ms"));
        }
        check(heapAfter-heapBefore<48L*1024*1024,"retained heap growth below 48MiB after 60-second run");
        check(app.store.scalar("SELECT COUNT(*) FROM hosts")==HOSTS&&app.store.scalar("SELECT COUNT(*) FROM inventory")==34000,"concurrent results preserve fleet size");
        Map<String,Object> report=map("status","PASS","hosts",HOSTS,"inventoryRows",34000,"environments",10,"durationMillis",elapsed,"targetHeartbeatsPerSecond",HEARTBEATS_PER_SECOND,"heartbeats",beats.get(),"fullAssessments",results.get(),"dashboardQueries",all.report(),"queryCases",queryMetrics,"heartbeatLatency",heartbeatSamples.report(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),"heapBeforeBytes",heapBefore,"heapAfterBytes",heapAfter,"heapPeakBytes",heapPeak.get(),"retainedHeapGrowthBytes",heapAfter-heapBefore,"rssBeforeKiB",rssBefore,"rssAfterKiB",rssAfter,"platform",System.getProperty("os.name")+" "+System.getProperty("os.arch"),"javaVersion",System.getProperty("java.version"),"scope","Loopback HTTP with local SQLite; browser rendering and target VM hardware are measured separately");
        return report;
    }
    private Map<String,Object> run()throws Exception {
        check(Runtime.getRuntime().maxMemory()<=300L*1024*1024,"Run load test with -Xmx256m");
        Path root=Files.createTempDirectory("assessment-beta-load-");HttpServer http=null;ExecutorService requests=Executors.newFixedThreadPool(12);AssessmentServer app=null;
        try {
            Files.createDirectory(root.resolve("conf"));Properties properties=new Properties();properties.setProperty("seedLegacyHost","false");properties.setProperty("adminSalt","load-fixture-salt");properties.setProperty("adminPasswordSha256",InventoryStore.hash("load-fixture-saltload-fixture-password"));properties.setProperty("agentToken","unused-fixture-token");properties.setProperty("hostId","unused-fixture-host");
            try(OutputStream out=Files.newOutputStream(root.resolve("conf/server.properties"))){properties.store(out,"Local load test fixtures");}
            app=new AssessmentServer(root.toFile());long seedStart=System.nanoTime();seed(app.store);long seedMillis=(System.nanoTime()-seedStart)/1_000_000;
            http=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),64);http.createContext("/",app);http.setExecutor(requests);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();login();
            Map<String,Object> report=measure(app);report.put("seedMillis",seedMillis);report.put("databaseBytes",Files.size(root.resolve("data/inventory.sqlite")));
            http.stop(0);http=null;requests.shutdown();check(requests.awaitTermination(10,TimeUnit.SECONDS),"HTTP requests finish");app.close();app=null;
            try(AssessmentServer reopened=new AssessmentServer(root.toFile())){check(reopened.store.scalar("SELECT COUNT(*) FROM hosts")==HOSTS&&reopened.store.scalar("SELECT COUNT(*) FROM inventory")==34000,"full load persists across process store reopen");}
            report.put("reopenPersistence","PASS");return report;
        } finally {
            if(http!=null)http.stop(0);requests.shutdownNow();if(app!=null)app.close();
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    public static void main(String[] args)throws Exception {
        Map<String,Object> report=new BetaLoadTest().run();String json=Json.stringify(report);
        if(args.length==1)Files.writeString(Path.of(args[0]),json+"\n",StandardCharsets.UTF_8);
        System.out.println("BETA_LOAD_TEST="+json);
    }
}
