package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Signed catalog, real SQLite/API and disposable accounts; never reads production conf/data. */
public final class AgentUpdateUiFixture {
    private static void copyTree(Path from,Path to)throws Exception {
        try(var files=Files.walk(from)){for(Path file:files.toList()){Path target=to.resolve(from.relativize(file));if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target);}}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("assessment-update-ui-");Files.createDirectories(root.resolve("conf"));
        Files.createSymbolicLink(root.resolve("web"),Paths.get(args[0]).toAbsolutePath());
        Path fixture=Paths.get(args[1]).toAbsolutePath();copyTree(fixture.resolve("dist"),root.resolve("dist"));Files.copy(fixture.resolve("assessment-collector.zip"),root.resolve("dist/assessment-collector.zip"));
        Properties config=new Properties();config.setProperty("adminSalt","fixture-salt");config.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));config.setProperty("seedLegacyHost","false");config.setProperty("hostId","unused-fixture-host");config.setProperty("agentToken","unused-fixture-token");config.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){config.store(out,"isolated agent update browser fixture");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            app.store.transaction(()->{
                for(String[] env:new String[][]{{"env-main","Homologação"},{"env-other","Outro ambiente"}})app.store.put("environments",map("id",env[0],"name",env[1],"settings",map(),"revision","fixture-revision"));
                for(String id:List.of("host-first","host-second","host-legacy","host-current","host-other")){
                    app.store.put("hosts",map("id",id,"hostname",id,"environmentId",id.equals("host-other")?"env-other":"env-main","distribution","Oracle Linux 9","architecture","aarch64","lastSeen",AssessmentServer.now(),"lastAssessmentAt",AssessmentServer.now(),"collectorVersion",id.equals("host-current")?DashboardVersion.VERSION:"0.0.1","collectorJavaVersion","21.0.12","updateCapability",id.equals("host-legacy")?"":"update-v2","coverage",map("status","complete","warnings",List.of()),"settings",map()));
                    app.store.put("identities",map("hostId",id,"installationId","fixture-install-"+id,"agentToken","fixture-token-"+id,"status","active"));
                    app.store.putInventory("runtimes",id,List.of(map("id",id+"-runtime","hostId",id,"kind","JRE","inUse",true,"version","21.0.12","javaHome","/usr/lib/jvm/java-21","observedAt",AssessmentServer.now())));
                }return null;
            });
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(4);server.setExecutor(pool);server.createContext("/",app);server.start();
            try{
                String base="http://127.0.0.1:"+server.getAddress().getPort();TestAccounts.login(base);
                var admin=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(admin,"Fixture Reader","reader@example.com","READER");app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),"Reader42!");
                if(!Boolean.TRUE.equals(new AgentUpdateCatalog(root.toFile()).summary().get("available")))throw new IllegalStateException("Signed fixture version does not match the dashboard; rebuild test fixtures");
                System.out.println("AGENT_UPDATE_UI_URL="+base);System.out.flush();System.in.read();
            }finally{server.stop(0);pool.shutdownNow();}
        }finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
