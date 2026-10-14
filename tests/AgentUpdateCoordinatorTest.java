package assessment;

import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Real SQLite persistence, authorization expiry and fail-closed route selection. */
public final class AgentUpdateCoordinatorTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static String s(Object x){return x instanceof String?(String)x:"";}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object x){return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object x){return (List<Map<String,Object>>)x;}
    static final UserAuth.User ADMIN=new UserAuth.User("test-admin","Fixture","admin@example.invalid","ADMIN",true,false,1,0,"","");
    static final class Fixture implements AutoCloseable {
        final Path root; InventoryStore db; AgentUpdateRegistry updates;
        Fixture(Path assets)throws Exception{root=Files.createTempDirectory("update-coordinator-");AgentUpdateRoutesTest.copy(assets.resolve("dist"),root.resolve("dist"));open();}
        void open(){db=new InventoryStore(root.resolve("data").toFile(),map(),"","");new UserAuth(db);updates=new AgentUpdateRegistry(db,new EnrollmentRegistry(db),new AgentUpdateCatalog(root.toFile()));}
        void tx(Runnable task){db.transaction(()->{task.run();return null;});}
        void host(String id,String version,String cap){tx(()->{db.put("hosts",map("id",id,"hostname",id,"collectorVersion",version,"updateCapability",cap));db.put("identities",map("hostId",id,"agentToken","fixture-"+id,"installationId","fixture-install-"+id,"status","active"));});}
        Map<String,Object> create(String host,String key){return db.transaction(()->updates.create(map("hostIds",List.of(host),"targetVersion",DashboardVersion.VERSION,"requestKey",key,"runAssessmentAfterUpdate",true),ADMIN));}
        String id(Map<String,Object> batch){return s(rows(batch.get("requests")).get(0).get("id"));}
        public void close()throws Exception{db.close();try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    public static void main(String[] args)throws Exception{
        Path assets=Path.of(args.length>0?args[0]:"tmp/full-update/http-fixture-v2");
        try(Fixture f=new Fixture(assets)){
            f.host("legacy","1.5.12","update-v1");String op=f.id(f.create("legacy","coordinator-restart-001"));
            Map<String,Object> action=f.db.transaction(()->f.updates.delivery("legacy","update-v1"));String child=s(action.get("requestId"));
            f.db.close();f.open();check(op.equals(f.id(f.create("legacy","coordinator-restart-001"))),"idempotency survives restart");
            check(child.equals(f.db.transaction(()->f.updates.delivery("legacy","update-v1")).get("requestId")),"same legacy child survives restart");
            f.tx(()->f.updates.claim("legacy",child));
            f.tx(()->f.updates.report("legacy",map("hostId","legacy","requestId",child,"status","FAILED","version","1.5.12","diagnostic","INCOMPATIBLE_ASSETS")));
            f.tx(()->f.updates.maintain());check(f.db.get("agent_update_requests",op).get("status").equals("FAILED"),"exhausted signed profiles stop parent");
            check(f.db.get("agent_update_requests",op).get("diagnostic").equals("MIGRATION_PROFILE_REQUIRED"),"unknown support requires explicit repair");
            check(f.db.scalar("SELECT count(*) FROM jobs")==0,"failed migration never dispatches assessment");
            check(f.db.scalar("SELECT count(*) FROM agent_update_operations WHERE status='FAILED'")==1,"logical operation retains final state");
            f.host("cancel","2.0.4","update-v3");String cancel=f.id(f.create("cancel","coordinator-cancel-0001"));f.tx(()->f.updates.cancel("cancel",cancel,ADMIN));
            check(f.db.scalar("SELECT count(*) FROM agent_update_operations WHERE id=? AND status='CANCELLED'",cancel)==1,"cancellation synchronizes publication guard");
            f.host("expire","2.0.4","update-v3");String expire=f.id(f.create("expire","coordinator-expire-0001"));f.tx(()->{var p=f.db.get("agent_update_requests",expire);p.put("expiresMillis",0L);f.db.put("agent_update_requests",p);f.updates.maintain();});
            check(f.db.scalar("SELECT count(*) FROM agent_update_operations WHERE id=? AND status='EXPIRED'",expire)==1,"expiry synchronizes publication guard");
            f.host("revoked","2.0.4","update-v3");String revoked=f.id(f.create("revoked","coordinator-revoke-0001"));f.tx(()->{var identity=f.db.get("identities","revoked");identity.put("status","revoked");f.db.put("identities",identity);f.updates.maintain();});
            check(f.db.get("agent_update_requests",revoked).get("status").equals("CANCELLED"),"revocation cancels only unstarted operation");
            f.host("broken","2.0.4","update-v3");Path route=f.root.resolve("dist/agent-update-routes/"+DashboardVersion.VERSION+"/routes.json");Files.writeString(route,"{}");
            f.db.close();f.open();var preview=f.db.transaction(()->f.updates.preview("broken",""));check(Boolean.FALSE.equals(obj(preview.get("release")).get("available")),"invalid full catalog cannot fall back to classic release");
            check(rows(preview.get("hosts")).get(0).get("reason").equals("RELEASE_UNAVAILABLE"),"invalid catalog displays unavailable reason");
            try{f.create("broken","coordinator-broken-0001");throw new AssertionError("invalid route accepted");}catch(EnrollmentRegistry.Failure expected){check(expected.status==503,"create fails closed on invalid catalog");}
        }
        System.out.println("AgentUpdateCoordinatorTest PASS "+checks+" checks: restart/replay, exhausted profiles, no early assessment, cancel/revoke/expiry publication state and invalid-catalog fail-closed.");
    }
}
