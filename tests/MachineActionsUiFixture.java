package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Disposable loopback-only fixture: no production configuration or credentials. */
public final class MachineActionsUiFixture {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("assessment-machine-actions-ui-");Files.createDirectories(root.resolve("conf"));
        Files.createSymbolicLink(root.resolve("web"),Paths.get(args[0]).toAbsolutePath());
        Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltlegacy-fixture-secret"));
        p.setProperty("agentToken","unused-fixture-token");p.setProperty("hostId","unused-fixture-host");p.setProperty("seedLegacyHost","false");p.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"isolated machine actions fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            UserAuth.User admin=app.auth.bootstrap("Fixture Admin","admin@example.com","Admin42!");
            UserAuth.Temporary reader=app.auth.create(admin,"Fixture Reader","reader@example.com","READER");
            app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),"Read42!x");
            app.store.transaction(()->{
                for(String[] env:new String[][]{{"env-main","Principal"},{"env-other","Secundário"}})
                    app.store.put("environments",map("id",env[0],"name",env[1],"settings",map(),"revision","fixture-revision"));
                for(String id:List.of("host-revoked","host-active","host-uninstalled","host-pending")){
                    app.store.put("hosts",map("id",id,"hostname",id,"environmentId","env-main","distribution","Oracle Linux 9","architecture","aarch64","lastSeen",AssessmentServer.now(),"lastAssessmentAt",AssessmentServer.now(),"collectorVersion",DashboardVersion.VERSION,"agentLifecycle",id.equals("host-uninstalled")?"uninstalled":"","coverage",map("status","complete","warnings",List.of()),"settings",map()));
                    app.store.put("identities",map("hostId",id,"installationId","install-"+id,"enrollmentId","fixture-enrollment","agentToken","fixture-token-"+id,"status",id.equals("host-active")?"active":"revoked"));
                    app.store.putInventory("runtimes",id,new ArrayList<>(List.of(map("id",id+"-java","hostId",id,"kind","JRE","inUse",true,"version","21.0.12","javaHome","/usr/lib/jvm/java-21","observedAt",AssessmentServer.now()))));
                    app.store.putInventory("applications",id,new ArrayList<>(List.of(map("id",id+"-app","hostId",id,"name","App","type","Java","javaHome","/usr/lib/jvm/java-21","observedAt",AssessmentServer.now()))));
                }
                app.store.put("uninstall_requests",map("id","pending-removal","hostId","host-pending","requestKey","fixture-pending-removal","status","WAITING","expiresMillis",System.currentTimeMillis()+3600000L,"createdAt",AssessmentServer.now(),"audit",new ArrayList<>()));
                return null;
            });
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(4);server.setExecutor(pool);server.createContext("/",app);server.start();
            System.out.println("MACHINE_ACTIONS_UI_URL=http://127.0.0.1:"+server.getAddress().getPort());System.out.flush();
            try{System.in.read();}finally{server.stop(0);pool.shutdownNow();}
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
