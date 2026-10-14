package assessment;

import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Real signed migration routes/API/SQLite. Never starts a worker, VM or product service. */
public final class AgentMigrationUiFixture {
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Supply web directory and signed fixture directory");
        Path root=Files.createTempDirectory("agent-migration-ui-").toRealPath();Files.createDirectories(root.resolve("conf"));
        AgentUpdateRoutesTest.copy(Path.of(args[1]).toAbsolutePath().resolve("dist"),root.resolve("dist"));
        Files.createSymbolicLink(root.resolve("web"),Path.of(args[0]).toAbsolutePath());
        var properties=new Properties();properties.setProperty("seedLegacyHost","false");properties.setProperty("hostId","unused-fixture-host");properties.setProperty("agentToken","unused-fixture-token");properties.setProperty("adminSalt","fixture-salt");properties.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){properties.store(out,"Isolated full-update UI fixture");}
        AgentUpdateMigrationHttpTest.root=root;boolean started=false;
        try{
            if(new AgentUpdateRoutes(root.toFile()).current()==null)throw new IllegalStateException("Signed V3 fixture does not match dashboard release");
            AgentUpdateMigrationHttpTest.start();started=true;var app=AgentUpdateMigrationHttpTest.app;
            app.store.transaction(()->{
                app.store.put("environments",map("id","env-test","name","Homologação","settings",map("enabled",false,"includePaths",List.of("/opt/fixture-app"),"maxDurationSeconds",25),"revision","migration-ui-fixture"));
                AgentUpdateMigrationHttpTest.seed("legacy-a","1.5.12","update-v1");AgentUpdateMigrationHttpTest.seed("legacy-b","1.5.12","update-v1");AgentUpdateMigrationHttpTest.seed("direct","2.0.4","update-v3");AgentUpdateMigrationHttpTest.seed("too-old","1.5.11","update-v1");return null;
            });
            var administrator=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(administrator,"Fixture reader","reader@example.com","READER");app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),"Reader42!");
            System.out.println("AGENT_MIGRATION_UI_URL="+AgentUpdateMigrationHttpTest.base);System.out.flush();System.in.read();
        }finally{
            if(started)AgentUpdateMigrationHttpTest.stop();try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
