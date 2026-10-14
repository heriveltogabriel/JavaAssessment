package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Disposable SQLite/server fixture. No production configuration, remote hosts or OS workers. */
public final class WorkRequestsUiFixture {
    private static final String MAIN="env-main", OTHER="env-other";
    private static final Instant NOW=Instant.now();
    private static String at(long seconds){return NOW.minusSeconds(seconds).toString();}
    private static String envName(String env){return env.equals(MAIN)?"Homologação":"Produção";}
    private static void host(AssessmentServer app,String id,String env){
        app.store.put("hosts",map("id",id,"hostname",id,"environmentId",env,"distribution","Oracle Linux 9","architecture","aarch64","lastSeen",at(0),"lastAssessmentAt",at(30),"collectorVersion","1.5.9","collectorJavaVersion","21.0.12","updateCapability","update-v1","coverage",map("status","complete","warnings",List.of()),"settings",map()));
        app.store.put("identities",map("hostId",id,"installationId","fixture-install-"+id,"agentToken","fixture-token-"+id,"status","active"));
        app.store.putInventory("runtimes",id,List.of(map("id",id+"-runtime","hostId",id,"kind","JRE","inUse",true,"version","21.0.12","javaHome","/usr/lib/jvm/java-21","observedAt",at(30))));
    }
    private static Map<String,Object> event(String status,long seconds,String diagnostic){return map("at",at(seconds),"status",status,"diagnostic",diagnostic);}
    private static void batch(AssessmentServer app,String id,long age,List<Object> skipped,String[][] machines){
        List<String> selection=new ArrayList<>();int ordinal=0;
        for(String[] item:machines){
            String host=item[0],env=item[1],status=item[2],diagnostic=item.length>3?item[3]:"";selection.add(host);
            List<Object> events=new ArrayList<>();events.add(event("WAITING",age,""));
            if(!Set.of("WAITING","CANCELLED","EXPIRED").contains(status))events.add(event("DOWNLOADING",age-1,""));
            if(Set.of("VERIFYING","SUCCEEDED","ROLLED_BACK").contains(status))events.add(event("INSTALLING",age-2,""));
            if(Set.of("VERIFYING","SUCCEEDED").contains(status))events.add(event("VERIFYING",age-3,""));
            if(!status.equals("WAITING")&&!status.equals("VERIFYING"))events.add(event(status,age-4,diagnostic));
            var record=map("id",id+"-"+host,"hostId",host,"batchId",id,"ordinal",ordinal++,"targetVersion",DashboardVersion.VERSION,"fromVersion","1.5.9","status",status,"createdAt",at(age),"updatedAt",at(age-4),"updatedMillis",System.currentTimeMillis(),"claimedMillis",System.currentTimeMillis()-10000,"expiresMillis",System.currentTimeMillis()+86400000L,"expiresAt",Instant.now().plusSeconds(86400).toString(),"diagnostic",diagnostic,"actorId","fixture-admin","actorName","Fixture Administrator","hostname",host,"environmentId",env,"environmentName",envName(env),"events",events,"historyPartial",false);
            if(AgentUpdateRegistry.TERMINAL.contains(status))record.put("finishedAt",at(age-4));
            if(id.equals("batch-legacy")){record.remove("events");record.remove("historyPartial");}
            app.store.put("agent_update_requests",record);
        }
        app.store.exec("INSERT INTO agent_update_batches(id,request_key,target_version,selection,payload) VALUES(?,?,?,?,?)",id,"fixture-request-key-"+id,DashboardVersion.VERSION,Json.stringify(selection),Json.stringify(map("createdAt",at(age),"actorId","fixture-admin","actorName","Fixture Administrator","skipped",skipped)));
    }
    private static void seed(AssessmentServer app){
        app.store.transaction(()->{
            for(String env:List.of(MAIN,OTHER))app.store.put("environments",map("id",env,"name",envName(env),"settings",map(),"revision","fixture-revision"));
            for(String id:List.of("app-api","app-worker","app-batch","app-uncertain","app-legacy","app-rollback","app-failed","app-cancelled","app-expired","app-skipped"))host(app,id,MAIN);
            host(app,"app-external",OTHER);
            batch(app,"batch-active",60,List.of(map("hostId","app-skipped","hostname","app-skipped","environmentId",MAIN,"environmentName",envName(MAIN),"reason","CAPABILITY_REQUIRED")),new String[][]{{"app-batch",MAIN,"SUCCEEDED","OK"},{"app-api",MAIN,"VERIFYING","OK"},{"app-worker",MAIN,"WAITING"},{"app-external",OTHER,"WAITING"}});
            batch(app,"batch-uncertain",180,List.of(),new String[][]{{"app-uncertain",MAIN,"UNCONFIRMED","NETWORK_UNCONFIRMED"}});
            batch(app,"batch-success",360,List.of(),new String[][]{{"app-api",MAIN,"SUCCEEDED","OK"},{"app-worker",MAIN,"SUCCEEDED","OK"}});
            batch(app,"batch-failed",540,List.of(),new String[][]{{"app-rollback",MAIN,"ROLLED_BACK","ROLLED_BACK"},{"app-failed",MAIN,"FAILED","DOWNLOAD_FAILED"},{"app-cancelled",MAIN,"CANCELLED","BATCH_STOPPED"}});
            batch(app,"batch-cancelled",720,List.of(),new String[][]{{"app-cancelled",MAIN,"CANCELLED","CANCELLED"}});
            batch(app,"batch-expired",900,List.of(),new String[][]{{"app-expired",MAIN,"EXPIRED","EXPIRED"}});
            batch(app,"batch-legacy",1080,List.of(),new String[][]{{"app-legacy",MAIN,"SUCCEEDED","OK"}});
            batch(app,"batch-partial",1260,List.of(),new String[][]{{"app-legacy",MAIN,"SUCCEEDED","OK"},{"app-expired",MAIN,"EXPIRED","EXPIRED"}});
            // The snapshot survives removal; there is intentionally no live host or identity for this machine.
            batch(app,"batch-removed",1400,List.of(),new String[][]{{"app-removed",MAIN,"SUCCEEDED","OK"}});
            app.store.put("jobs",map("id","assessment-notes","hostId","app-api","hostname","app-api","environmentId",MAIN,"environmentName",envName(MAIN),"status","PARTIAL","createdAt",at(30),"startedAt",at(25),"capturedAt",at(20),"finishedAt",at(10),"summary","Assessment received — 4 Java installation(s), 0 server(s)","actorName","Fixture Administrator","coverage",map("status","partial","warnings",List.of("File count limit reached; remaining paths were not examined","Assessment deadline reached; remaining paths were not examined","Directory is not readable: /u01/protected","<img src=x onerror=\"window.coverageInjected=true\">"))));
            // A later collection has replaced live coverage; old requests must keep their own reasons.
            var latest=app.store.get("hosts","app-api");latest.put("lastAssessmentAt",at(0));latest.put("coverage",map("status","partial","lastAssessmentAt",at(0),"warnings",List.of("NEWER_COLLECTION_NOTE_SHOULD_NOT_APPEAR")));app.store.put("hosts",latest);
            app.store.put("jobs",map("id","assessment-legacy-unavailable","hostId","app-api","hostname","app-api","environmentId",MAIN,"environmentName",envName(MAIN),"status","PARTIAL","createdAt",at(1500),"startedAt",at(1490),"finishedAt",at(1450),"summary","Assessment received — 2 Java installation(s), 0 server(s)","actorName","Fixture Administrator"));
            return null;
        });
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("assessment-work-requests-ui-");Files.createDirectories(root.resolve("conf"));
        Files.createSymbolicLink(root.resolve("web"),Paths.get(args[0]).toAbsolutePath());
        Properties config=new Properties();config.setProperty("adminSalt","fixture-salt");config.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));config.setProperty("seedLegacyHost","false");config.setProperty("hostId","unused-fixture-host");config.setProperty("agentToken","unused-fixture-token");config.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){config.store(out,"isolated work requests browser fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            seed(app);HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(4);server.setExecutor(pool);
            Path requestLog=args.length>1?Paths.get(args[1]):null;
            server.createContext("/",exchange->{
                try{app.handle(exchange);}finally{
                    // Optional test-only audit contains routes/status, never credentials, cookies or bodies.
                    if(requestLog!=null)synchronized(WorkRequestsUiFixture.class){
                        Files.writeString(requestLog,Instant.now()+" "+exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath()+" "+exchange.getResponseCode()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    }
                }
            });server.start();
            try{
                String base="http://127.0.0.1:"+server.getAddress().getPort();TestAccounts.login(base);
                var admin=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(admin,"Fixture Reader","reader@example.com","READER");app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),"Reader42!");
                System.out.println("WORK_REQUESTS_UI_URL="+base);System.out.flush();System.in.read();
            }finally{server.stop(0);pool.shutdownNow();}
        }finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
