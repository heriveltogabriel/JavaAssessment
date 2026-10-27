package assessment;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.Duration;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static assessment.AssessmentServer.map;
/** Manual load check, not part of ops/test-source.sh. Usage: AgentFleetLoadBench <agents> <seconds> <operatorTabs> [commitLatencyMs] [uploads]
 *  Disposable loopback fixture only. 1150 simulated agents (60 s cycle: heartbeat, actions, poll) + operator tabs against one real server and an active 10-at-a-time campaign. */
public final class AgentFleetLoadBench {
  static final HttpClient C=HttpClient.newBuilder().executor(Executors.newFixedThreadPool(64)).connectTimeout(Duration.ofSeconds(10)).build();
  static final ConcurrentHashMap<String,ConcurrentLinkedQueue<Long>> lat=new ConcurrentHashMap<>();
  static final ConcurrentHashMap<String,AtomicInteger> err=new ConcurrentHashMap<>();
  static void rec(String k,long ms,int status){lat.computeIfAbsent(k,x->new ConcurrentLinkedQueue<>()).add(ms);if(status!=200)err.computeIfAbsent(k+" HTTP "+status,x->new AtomicInteger()).incrementAndGet();}
  static CompletableFuture<Integer> callStatus(String key,HttpRequest.Builder b){long t=System.nanoTime();
    return C.sendAsync(b.timeout(Duration.ofSeconds(30)).build(),HttpResponse.BodyHandlers.discarding()).handle((r,e)->{int st=e==null?r.statusCode():-1;rec(key,(System.nanoTime()-t)/1000000,st);return st;});}
  static CompletableFuture<Void> call(String key,HttpRequest.Builder b){long t=System.nanoTime();
    return C.sendAsync(b.timeout(Duration.ofSeconds(30)).build(),HttpResponse.BodyHandlers.discarding()).handle((r,e)->{rec(key,(System.nanoTime()-t)/1000000,e==null?r.statusCode():-1);return null;});}
  static Map<String,Object> result(String id){
    String pad="x".repeat(520);var r=map("schemaVersion","1.0","eventId",UUID.randomUUID().toString(),"jobId","job-"+id,"capturedAt",AssessmentServer.now(),"collectorJavaVersion","17.0.20","host",map("id",id,"hostname",id),"coverage",map("status","complete","warnings",List.of(),"stats",map()));
    Map<String,Object> sizes=map("runtimes",6,"processes",60,"servers",4,"libraries",600,"applications",150,"datasources",10);
    for(var e:sizes.entrySet()){List<Object> rows=new ArrayList<>();for(int i=0;i<(Integer)e.getValue();i++)rows.add(map("id",e.getKey()+"-"+i,"hostId",id,"name",e.getKey()+i,"path","/opt/app/"+e.getKey()+"/"+i,"version","1."+i,"detail",pad));r.put(e.getKey(),rows);}
    return r;}
  public static void main(String[] a)throws Exception{
    int n=Integer.parseInt(a[0]),seconds=Integer.parseInt(a[1]),tabs=Integer.parseInt(a[2]);final long commitMs=a.length>3?Long.parseLong(a[3]):0;final boolean uploads=a.length>4&&a[4].equals("uploads");
    try(var fixture=new AgentUpdateCampaignsTest.Fixture(n)){
      Path root=AgentUpdateCampaignsTest.root;fixture.db.metadata("settings",AssessmentServer.map());fixture.db.close();Files.createDirectories(root.resolve("conf"));
      var cfg=new Properties();cfg.setProperty("adminSalt","fixture-salt");cfg.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));cfg.setProperty("seedLegacyHost","false");cfg.setProperty("hostId","unused");cfg.setProperty("agentToken","unused");
      try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){cfg.store(out,"load");}
      try(var app=new AssessmentServer(root.toFile())){
        if(commitMs>0){var f=InventoryStore.class.getDeclaredField("writer");f.setAccessible(true);((org.sqlite.SQLiteConnection)f.get(app.store)).addCommitListener(new org.sqlite.SQLiteCommitListener(){public void onCommit(){try{Thread.sleep(commitMs);}catch(InterruptedException e){}}public void onRollback(){}});}
        Method w=AssessmentServer.class.getDeclaredMethod("workers");w.setAccessible(true);var pool=(ExecutorService)w.invoke(null);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),64);server.setExecutor(pool);server.createContext("/",app);server.start();
        var maint=Executors.newSingleThreadScheduledExecutor();maint.scheduleWithFixedDelay(()->{try{app.maintainOperations();}catch(Exception e){System.err.println("maint "+e);}},5,5,TimeUnit.SECONDS);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        var login=TestAccounts.login(base);String cookie=login.headers().firstValue("Set-Cookie").orElse("").split(";",2)[0];
        // real campaign over the whole fleet, 10 machines at a time
        var reg=new AgentUpdateRegistry(app.store,new EnrollmentRegistry(app.store),new AgentUpdateCatalog(root.toFile()));
        var sel=app.store.transaction(()->reg.campaigns().selection(map("filter",map()),AgentUpdateCampaignsTest.ADMIN));
        var batch=app.store.transaction(()->reg.campaigns().create(map("selectionId",sel.get("selectionId"),"requestKey",UUID.randomUUID().toString(),"targetVersion",DashboardVersion.VERSION,"batchSize",10,"runAssessmentAfterUpdate",false),AgentUpdateCampaignsTest.ADMIN));
        if(uploads)app.store.transaction(()->{for(int i=0;i<n;i++){String id=String.format("host-%05d",i);app.store.put("jobs",map("id","job-"+id,"hostId",id,"status","RUNNING","createdAt",AssessmentServer.now(),"startedAt",AssessmentServer.now(),"leaseExpires",System.currentTimeMillis()+7200000L,"hostname",id,"environmentId","env"));}return null;});
        String batchId=(String)batch.get("batchId");Thread.sleep(6000);
        {for(int i=0;i<n;i+=6){var wave=new ArrayList<CompletableFuture<Void>>();for(int k=i;k<Math.min(n,i+6);k++){String id=String.format("host-%05d",k);var req=HttpRequest.newBuilder(URI.create(base+"/api/agent/heartbeat")).header("Authorization","Bearer fixture-"+id).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("hostId",id,"hostname",id,"collectorVersion","2.1.20","javaVersion","17","updateCapability","update-v2","uninstallCapability","uninstall-v1"))));wave.add(callStatus("warmup",req).thenApply(x->null));}
          CompletableFuture.allOf(wave.toArray(new CompletableFuture[0])).join();}
         System.out.println("warmup errors "+err);lat.clear();err.clear();Thread.sleep(6000);}
        var sched=Executors.newScheduledThreadPool(2);long end=System.currentTimeMillis()+seconds*1000L;Random rnd=new Random(7);
        for(int i=0;i<n;i++){String id=String.format("host-%05d",i),tok="Bearer fixture-"+id;
          final AtomicBoolean up=new AtomicBoolean();Runnable cycle=new Runnable(){public void run(){if(System.currentTimeMillis()>end)return;
            var hb=HttpRequest.newBuilder(URI.create(base+"/api/agent/heartbeat")).header("Authorization",tok).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("hostId",id,"hostname",id,"collectorVersion","2.1.20","javaVersion","17","updateCapability","update-v2","uninstallCapability","uninstall-v1"))));
            call("agent heartbeat",hb).thenCompose(x->call("agent actions",HttpRequest.newBuilder(URI.create(base+"/api/agent/actions?hostId="+id+"&updateCapability=update-v2")).header("Authorization",tok).GET()))
             .thenCompose(x->call("agent poll",HttpRequest.newBuilder(URI.create(base+"/api/agent/poll?hostId="+id+"&updateCapability=update-v2")).header("Authorization",tok).GET()))
             .thenCompose(x->{if(!uploads||up.get())return CompletableFuture.<Void>completedFuture(null);
               return callStatus("agent results",HttpRequest.newBuilder(URI.create(base+"/api/agent/results")).header("Authorization",tok).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(result(id))))).thenAccept(st->{if(st==200)up.set(true);});})
             .whenComplete((x,e)->sched.schedule(this,60,TimeUnit.SECONDS));}};
          sched.schedule(cycle,rnd.nextInt(60000),TimeUnit.MILLISECONDS);}
        for(int t=0;t<tabs;t++){Runnable tab=new Runnable(){public void run(){if(System.currentTimeMillis()>end)return;
            String q="environmentId=&hostId=&type=ALL&status=ALL&q=&from=&to=&page=1&pageSize=20";
            call("operator list",HttpRequest.newBuilder(URI.create(base+"/api/work-requests?"+q)).header("Cookie",cookie).header("X-Assessment-Background","1").GET())
             .thenCompose(x->call("operator detail",HttpRequest.newBuilder(URI.create(base+"/api/work-requests/detail?environmentId=&hostId=&id="+batchId+"&type=AGENT_UPDATE&compact=true&pageSize=50&page=1")).header("Cookie",cookie).header("X-Assessment-Background","1").GET()))
             .whenComplete((x,e)->sched.schedule(this,10,TimeUnit.SECONDS));}};
          sched.schedule(tab,t*2000L,TimeUnit.MILLISECONDS);}
        Thread.sleep(seconds*1000L+5000);sched.shutdownNow();
        System.out.println("simulated commit latency="+commitMs+"ms agents="+n+" duration="+seconds+"s operatorTabs="+tabs+" (list+detail each 10 s)");
        for(var e:new TreeMap<>(lat).entrySet()){var l=new ArrayList<>(e.getValue());Collections.sort(l);int m=l.size();
          System.out.printf("%-17s n=%-5d p50=%5dms p95=%5dms p99=%5dms max=%5dms%n",e.getKey(),m,l.get(m/2),l.get((int)(m*0.95)),l.get((int)Math.min(m-1,m*0.99)),l.get(m-1));}
        err.forEach((k,v)->System.out.println("  errors: "+k+" = "+v));
        maint.shutdownNow();server.stop(0);pool.shutdownNow();
      }
    }
    System.exit(0);
  }
}
