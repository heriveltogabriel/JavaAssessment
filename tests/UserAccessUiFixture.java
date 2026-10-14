package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Fresh legacy-to-individual-account UI fixture, never reads local production conf/data. */
public final class UserAccessUiFixture {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("assessment-accounts-ui-");Files.createDirectories(root.resolve("conf"));
        Files.createSymbolicLink(root.resolve("web"),Paths.get(args[0]).toAbsolutePath());
        Properties p=new Properties();p.setProperty("adminSalt","ui-fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("ui-fixture-saltlegacy-fixture-secret"));
        p.setProperty("agentToken","ui-fixture-unused");p.setProperty("hostId","ui-fixture-host");p.setProperty("seedLegacyHost","false");p.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"isolated UI fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            app.store.transaction(()->{
                app.store.put("environments",map("id","hm","name","Homologação","settings",map(),"revision","ui-revision"));
                app.store.put("hosts",map("id","ui-host","hostname","fixture-app-server","environmentId","hm","distribution","Oracle Linux 9","architecture","aarch64","lastSeen",AssessmentServer.now(),"lastAssessmentAt",AssessmentServer.now(),"collectorVersion",DashboardVersion.VERSION,"coverage",map("status","complete","warnings",List.of()),"settings",map()));
                app.store.put("identities",map("hostId","ui-host","agentToken","ui-agent-only-secret","status","active"));
                app.store.putInventory("runtimes","ui-host",new ArrayList<>(List.of(map("id","ui-java","hostId","ui-host","kind","JRE","inUse",true,"version","21.0.12","javaHome","/usr/lib/jvm/java-21","observedAt",AssessmentServer.now()))));
                app.store.putInventory("applications","ui-host",new ArrayList<>(List.of(map("id","ui-app","hostId","ui-host","name","App","type","Java","javaHome","/usr/lib/jvm/java-21","observedAt",AssessmentServer.now()))));
                return null;
            });
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(4);server.setExecutor(pool);server.createContext("/",app);server.start();
            System.out.println("USER_ACCESS_UI_URL=http://127.0.0.1:"+server.getAddress().getPort());System.out.flush();
            try{System.in.read();}finally{server.stop(0);pool.shutdownNow();}
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
