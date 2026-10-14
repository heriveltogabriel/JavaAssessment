package assessment;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

/** Real HTTP/SQLite/signatures/JAR processes; only the OS service adapter is simulated. */
public final class AgentUpdateEndToEndTest {
    static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static Path fixtures,temporary,serverRoot,collector,helper;static AssessmentServer app;static HttpServer http;static ExecutorService pool;
    static String base,cookie,csrf,previousVersion;static int checks;static boolean running=true,lostAck=false,tamper=false;static final String HOST="e2e-agent",TOKEN="e2e-disposable-agent-token";
    static void check(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    static HttpResponse<byte[]> request(String method,String path,Map<String,Object> body,boolean human)throws Exception{
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30));
        if(human){if(cookie!=null)b.header("Cookie",cookie);if(csrf!=null)b.header("X-CSRF-Token",csrf);}else b.header("Authorization","Bearer "+TOKEN);
        if(body!=null)b.header("Content-Type","application/json");
        return CLIENT.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static Map<String,Object> json(String method,String path,Map<String,Object> body,boolean human)throws Exception{
        var r=request(method,path,body,human);check(r.statusCode()==200||r.statusCode()==202,"HTTP "+path+" "+r.statusCode()+" "+new String(r.body()));return object(Json.parse(new String(r.body(),java.nio.charset.StandardCharsets.UTF_8)));
    }
    static void tree(Path from,Path to)throws Exception{try(var files=Files.walk(from)){for(Path file:files.toList()){Path dest=to.resolve(from.relativize(file));if(Files.isDirectory(file))Files.createDirectories(dest);else Files.copy(file,dest,StandardCopyOption.REPLACE_EXISTING);}}}
    static void catalog(String name)throws Exception{tree(fixtures.resolve(name+"/dist"),serverRoot.resolve("dist"));Files.copy(fixtures.resolve(name+"/assessment-collector.zip"),serverRoot.resolve("dist/assessment-collector.zip"),StandardCopyOption.REPLACE_EXISTING);}
    static void seed()throws Exception{
        app.store.transaction(()->{app.store.put("environments",map("id","test","name","Teste de atualização","settings",map(),"revision","fixture"));app.store.put("hosts",map("id",HOST,"hostname","e2e-machine","environmentId","test","collectorVersion",previousVersion,"updateCapability","update-v2","lastSeen",AssessmentServer.now(),"settings",map()));app.store.put("identities",map("hostId",HOST,"status","active","agentToken",TOKEN,"installationId","e2e-stable-installation"));app.store.putInventory("runtimes",HOST,List.of(map("id","fixture-runtime","hostId",HOST,"version","21","javaHome","/opt/fixture/java")));return null;});
    }
    static void resetAgent()throws Exception{
        Files.copy(fixtures.resolve("old/collector.jar"),collector.resolve("collector.jar"),StandardCopyOption.REPLACE_EXISTING);
        app.store.transaction(()->{var h=app.store.get("hosts",HOST);h.put("collectorVersion",previousVersion);h.put("updateCapability","update-v2");h.put("lastSeen",AssessmentServer.now());app.store.put("hosts",h);return null;});running=true;
    }
    static String schedule()throws Exception{
        var response=json("POST","/api/agent-updates",map("hostIds",List.of(HOST),"targetVersion",DashboardVersion.VERSION,"requestKey",UUID.randomUUID().toString(),"expiresInMinutes",60),true);
        var requests=(List<?>)response.get("requests");check(requests.size()==1,"one eligible request");String id=(String)object(requests.get(0)).get("id");
        var action=object(json("GET","/api/agent/actions?hostId="+HOST+"&updateCapability=update-v2",null,false).get("action"));check(id.equals(action.get("requestId")),"real poll delivers selected update");
        Files.writeString(collector.resolve("collector-spool/update-request.json"),Json.stringify(action));return id;
    }
    static String status(String id){return (String)app.store.get("agent_update_requests",id).get("status");}
    static AgentUpdateWorker worker(){return new AgentUpdateWorker(collector.toFile(),helper.toFile(),new AgentUpdateWorker.Platform(){
        public void local(String action)throws Exception{if(action.equals("pause"))running=false;else if(action.equals("start"))running=true;else if(!Set.of("check","sync").contains(action))throw new IOException("Unexpected local action");}
        public int probe(File jar,String version,boolean heartbeat)throws Exception{
            List<String> cmd=new ArrayList<>(List.of(System.getProperty("java.home")+"/bin/java","-Xmx96m","-cp",jar.getAbsolutePath()+":"+collector.resolve("lib/*"),"assessment.AgentUpdateProbe",heartbeat?"heartbeat":"probe",version));if(heartbeat)cmd.add(collector.resolve("conf/collector.properties").toString());
            Process process=new ProcessBuilder(cmd).redirectErrorStream(true).start();var diagnostic=CompletableFuture.supplyAsync(()->{try{return process.getInputStream().readNBytes(8193);}catch(IOException error){return new byte[0];}});if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Fixture probe timeout");}if(process.exitValue()!=0)System.out.println("Fixture probe diagnostic: "+new String(diagnostic.get(5,TimeUnit.SECONDS)));return process.exitValue();
        }
    },new AgentUpdateWorker.Remote(){
        public Map request(String action,Map body)throws Exception{
            var ack=json("POST","/api/agent/update/"+action,object(body),false);
            if(lostAck&&action.equals("report")&&"SUCCEEDED".equals(body.get("status"))){lostAck=false;throw new IOException("Fixture lost terminal acknowledgement");}return ack;
        }
        public void download(String asset,File file,long limit,Map state)throws Exception{
            var result=AgentUpdateEndToEndTest.request("GET","/api/agent/update/"+asset+"?hostId="+HOST+"&requestId="+state.get("requestId"),null,false);check(result.statusCode()==200,"authenticated "+asset+" download");byte[] data=result.body();check(data.length<=limit,"bounded "+asset);if(tamper&&asset.equals("package"))data[data.length-1]^=1;Files.write(file.toPath(),data);
        }
    });}
    public static void main(String[] args)throws Exception{
        fixtures=Paths.get(args[0]).toAbsolutePath();previousVersion=(String)object(Json.parse(Files.readString(fixtures.resolve("fixture.json")))).get("previousVersion");temporary=Files.createTempDirectory("agent-update-e2e-").toRealPath();serverRoot=temporary.resolve("dashboard");collector=temporary.resolve("collector");helper=temporary.resolve("helper");Files.createDirectories(serverRoot.resolve("conf"));Files.createDirectories(collector);Files.createDirectories(helper);
        Properties config=new Properties();config.setProperty("seedLegacyHost","false");config.setProperty("hostId","unused");config.setProperty("agentToken","unused");config.setProperty("adminSalt","fixture-salt");config.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltlegacy-fixture-secret"));try(var out=Files.newOutputStream(serverRoot.resolve("conf/server.properties"))){config.store(out,"isolated");}
        try{
            catalog("good");try(ZipFile zip=new ZipFile(fixtures.resolve("good/assessment-collector.zip").toFile())){var entries=zip.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.getName().endsWith(".sh")||entry.getName().startsWith("lib/")||entry.getName().equals("update-public.der")){Path path=collector.resolve(entry.getName());Files.createDirectories(path.getParent());Files.write(path,zip.getInputStream(entry).readAllBytes());}}}
            Files.createDirectories(collector.resolve("conf"));Files.createDirectories(collector.resolve("collector-spool"));app=new AssessmentServer(serverRoot.toFile());seed();
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);pool=Executors.newFixedThreadPool(4);http.setExecutor(pool);http.createContext("/",app);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();
            var login=request("POST","/api/auth/bootstrap",map("currentPassword","legacy-fixture-secret","name","Fixture Admin","email","fixture@example.com","password","Admin42!"),true);check(login.statusCode()==200,"bootstrap");cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];csrf=(String)object(Json.parse(new String(login.body()))).get("csrfToken");
            Properties agent=new Properties();agent.setProperty("serverUrl",base);agent.setProperty("agentToken",TOKEN);agent.setProperty("hostId",HOST);agent.setProperty("spoolDirectory",collector.resolve("collector-spool").toString());try(var out=Files.newOutputStream(collector.resolve("conf/collector.properties"))){agent.store(out,"disposable identity");}byte[] identity=Files.readAllBytes(collector.resolve("conf/collector.properties"));
            resetAgent();String good=schedule();worker().cycle();check(status(good).equals("SUCCEEDED"),"signed good payload completed: "+Json.stringify(app.store.get("agent_update_requests",good)));check(AgentUpdateWorker.jarVersion(collector.resolve("collector.jar").toFile()).equals(DashboardVersion.VERSION),"new JAR actually installed");check(DashboardVersion.VERSION.equals(app.store.get("hosts",HOST).get("collectorVersion")),"actual new JVM heartbeat observed");check(running&&!Files.exists(helper.resolve("journal.json")),"scheduler restored and acknowledged journal cleaned");
            resetAgent();String ack=schedule();lostAck=true;try{worker().cycle();throw new AssertionError("expected lost acknowledgement");}catch(IOException expected){}check(status(ack).equals("SUCCEEDED")&&Files.exists(helper.resolve("journal.json")),"lost ACK leaves durable result");worker().cycle();check(!Files.exists(helper.resolve("journal.json"))&&running,"retry converges without reapplying");
            resetAgent();String corrupt=schedule();tamper=true;worker().cycle();tamper=false;check(status(corrupt).equals("FAILED"),"tampered payload rejected");check(AgentUpdateWorker.jarVersion(collector.resolve("collector.jar").toFile()).equals(previousVersion)&&running,"tamper leaves previous agent operational");
            if(Files.isDirectory(fixtures.resolve("bad"))){resetAgent();catalog("bad");String bad=schedule();worker().cycle();check(status(bad).equals("ROLLED_BACK"),"signed faulty installed probe triggers rollback");check(AgentUpdateWorker.jarVersion(collector.resolve("collector.jar").toFile()).equals(previousVersion)&&running,"rollback restores old actual JAR and scheduler");}
            check(Arrays.equals(identity,Files.readAllBytes(collector.resolve("conf/collector.properties"))),"identity/configuration byte-identical");check(app.store.scalar("SELECT count(*) FROM inventory WHERE host_id=?",HOST)==1&&"test".equals(app.store.get("hosts",HOST).get("environmentId")),"inventory and environment retained");
            System.out.println("AgentUpdateEndToEndTest PASS "+checks+" checks; "+previousVersion+" -> "+DashboardVersion.VERSION+"; real signed payloads/HTTP/SQLite/JAR execution/heartbeat, lost ACK, tamper rejection; rollbackTest="+Files.isDirectory(fixtures.resolve("bad"))+"; OS service adapter simulated; no VM access.");
        }finally{if(http!=null)http.stop(0);if(pool!=null)pool.shutdownNow();if(app!=null)app.close();try(var files=Files.walk(temporary)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
