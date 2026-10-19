package assessment;

import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Durable scheduler tests with an explicit clock; no sleeping or remote agents. */
public final class AgentUpdateStartTimeoutTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static long n(Object value){return value instanceof Number?((Number)value).longValue():0;}
    static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("agent-start-timeout-");
        InventoryStore db; AgentUpdateRegistry updates; long now=System.currentTimeMillis();
        Fixture()throws Exception{open();}
        void open(){db=new InventoryStore(root.resolve("data").toFile(),map(),"","");new UserAuth(db);updates=new AgentUpdateRegistry(db,new EnrollmentRegistry(db),new AgentUpdateCatalog(root.toFile()));}
        void restart(){db.close();open();}
        void tx(Runnable action){db.transaction(()->{action.run();return null;});}
        void tick(){tx(()->updates.maintain(now));}
        void add(String id,String batch,int ordinal,boolean managed){tx(()->{
            db.put("hosts",map("id",id,"hostname",id,"collectorVersion","2.1.4","updateCapability","update-v3"));
            db.put("identities",map("hostId",id,"status","active","agentToken","fixture-"+id));
            var row=map("id",id,"hostId",id,"batchId",batch,"ordinal",ordinal,"targetVersion","9.0.0","status","WAITING","createdAt",AssessmentServer.now(),"expiresMillis",now+3600000L,"updatedMillis",now,"events",new ArrayList<>(),"runAssessmentAfterUpdate",true);
            if(managed)row.put("updatePlan",map("schema",1));db.put("agent_update_requests",row);updates.assessments().onCreated(row,now);
        });}
        Map<String,Object> row(String id){return db.get("agent_update_requests",id);}
        void set(String id,String key,Object value){tx(()->{var r=row(id);r.put(key,value);db.put("agent_update_requests",r);});}
        @Override public void close()throws Exception{db.close();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    public static void main(String[] args)throws Exception{
        for(boolean full:List.of(false,true))try(var f=new Fixture()){
            f.add("offline","batch",0,full);f.add("online","batch",1,full);f.tick();
            long deadline=n(f.row("offline").get("startDeadlineMillis"));
            check(deadline==f.now+300000L,"first machine gets five minutes");
            check(n(f.row("online").get("startDeadlineMillis"))==0,"queued machine does not lose its turn budget");
            f.now+=200000;f.tick();f.restart();f.tick();
            check(n(f.row("offline").get("startDeadlineMillis"))==deadline,"polling and restart preserve deadline");
            f.now=deadline;f.tick(); // maintenance alone, no heartbeat or browser request
            check("EXPIRED".equals(f.row("offline").get("status")),"offline machine expires without heartbeat");
            check("AGENT_START_TIMEOUT".equals(f.row("offline").get("diagnostic")),"operator sees specific start timeout");
            check("WAITING".equals(f.row("online").get("status"))&&n(f.row("online").get("startDeadlineMillis"))==f.now+300000L,"next machine gets a fresh turn");
            check("CANCELLED".equals(f.updates.assessments().publicRelation("offline").get("status")),"expired update never schedules an assessment");
            if(full){boolean rejected=false;try{f.tx(()->f.updates.bundleClaim("offline","offline"));}catch(EnrollmentRegistry.Failure e){rejected=true;}check(rejected,"late full bundle claim rejected");}
            else check(Boolean.FALSE.equals(f.db.transaction(()->f.updates.claim("offline","offline")).get("executable")),"late legacy claim not executable");
            check(!AgentUpdateRegistry.busy(f.db,"offline"),"new request allowed after machine returns");
            f.restart();check("EXPIRED".equals(f.row("offline").get("status")),"terminal expiry survives restart");
            f.set("online","status","INSTALLING");f.set("online","claimedMillis",System.currentTimeMillis());
            f.now+=300001;f.tick();check("INSTALLING".equals(f.row("online").get("status")),"five-minute start budget never cancels installation");
        }
        try(var f=new Fixture()){
            f.add("parent","batch",0,true);f.add("next","batch",1,true);f.add("child","internal",0,false);
            f.set("child","parentOperationId","parent");f.tick();
            check(n(f.row("child").get("startDeadlineMillis"))==0,"bridge follows parent deadline");
            f.now+=300000;f.tick();
            check("CANCELLED".equals(f.row("child").get("status")),"unclaimed bridge cancelled with expired parent");
            check(Boolean.FALSE.equals(f.db.transaction(()->f.updates.claim("child","child")).get("executable")),"stale bridge cannot start after parent expires");
        }
        try(var f=new Fixture()){
            f.add("waiting","batch",0,true);f.tick();f.set("waiting","startDeadlineMillis",f.now-1);
            boolean rejected=false;try{f.tx(()->f.updates.bundleClaim("waiting","waiting"));}catch(EnrollmentRegistry.Failure e){rejected=true;}
            check(rejected,"full claim checks deadline before catalog or executable authorization");
            f.tick(); // prior failed transaction rolled back, maintenance still expires it
            check("EXPIRED".equals(f.row("waiting").get("status")),"maintenance commits expiry after failed claim transaction");
        }
        System.out.println("AgentUpdateStartTimeoutTest PASS: "+checks+" checks");
    }
}
