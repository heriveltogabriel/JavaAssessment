package assessment;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import static assessment.AssessmentServer.map;

/** Actual collector -> SQLite fixture for headless Java browser validation. */
public final class RuntimeUiFixture {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("assessment-runtime-ui-");Files.createDirectories(root.resolve("conf"));
        Files.createSymbolicLink(root.resolve("web"),Paths.get(args[0]).toAbsolutePath());
        Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));
        p.setProperty("agentToken","fixture-token");p.setProperty("hostId","fixture-host");p.setProperty("seedLegacyHost","false");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Isolated runtime UI fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())) {
            Map<String,Object> snap=(Map<String,Object>)RuntimeInventoryTest.snapshot("runtime-host","fixture-job",AssessmentServer.now());
            app.store.transaction(()->{
                app.store.put("environments",map("id","hm","name","Homologação","settings",map(),"revision",1));
                app.store.put("hosts",map("id","runtime-host","hostname","brunods-dev-vnic","environmentId","hm","distribution","Oracle Linux 9 fixture",
                    "architecture","aarch64","lastSeen",AssessmentServer.now(),"lastAssessmentAt",snap.get("capturedAt"),"collectorVersion",AgentTransport.VERSION,
                    "coverage",snap.get("coverage"),"settings",map()));
                app.store.put("identities",map("hostId","runtime-host","status","active","agentToken","fixture-only-runtime"));
                for(String kind:List.of("runtimes","processes","applications","servers","libraries","datasources"))app.store.putInventory(kind,"runtime-host",(List<Object>)snap.get(kind));
                return null;
            });
            HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(4);
            http.createContext("/",app);http.setExecutor(pool);http.start();TestAccounts.login("http://127.0.0.1:"+http.getAddress().getPort());
            System.out.println("RUNTIME_UI_URL=http://127.0.0.1:"+http.getAddress().getPort());System.out.flush();
            try{System.in.read();}finally{http.stop(0);pool.shutdownNow();}
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
