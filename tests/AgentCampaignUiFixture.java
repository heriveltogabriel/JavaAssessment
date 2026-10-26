package assessment;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable 5000-host dashboard for UI/API validation. */
public final class AgentCampaignUiFixture {
    public static void main(String[] args)throws Exception {
        try(var fixture=new AgentUpdateCampaignsTest.Fixture(5000)){
            Path root=AgentUpdateCampaignsTest.root;
            fixture.db.metadata("settings",assessment.AssessmentServer.map());fixture.db.close();Files.createDirectories(root.resolve("conf"));Files.createSymbolicLink(root.resolve("web"),Paths.get(args[0]).toAbsolutePath());
            var config=new Properties();config.setProperty("adminSalt","fixture-salt");config.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));config.setProperty("seedLegacyHost","false");config.setProperty("hostId","unused");config.setProperty("agentToken","unused");
            try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){config.store(out,"Disposable campaign test");}
            try(var app=new AssessmentServer(root.toFile())){
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);var pool=Executors.newFixedThreadPool(4);server.setExecutor(pool);server.createContext("/",app);server.start();
                try{String base="http://127.0.0.1:"+server.getAddress().getPort();TestAccounts.login(base);var admin=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(admin,"Reader","reader@example.com","READER");app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),"Reader42!");System.out.println("CAMPAIGN_UI_URL="+base);System.out.flush();System.in.read();}
                finally{server.stop(0);pool.shutdownNow();}
            }
        }
    }
}
