package assessment;
import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;
/** Campaign admission and cancellation against the real signed migration catalog. No agent OS execution. */
public final class AgentCampaignMigrationTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        try(var f=new AgentUpdateCoordinatorTest.Fixture(Path.of(args[0]))){
            var c=f.updates.campaigns();
            for(String id:List.of("legacy","full","waiting")){f.host(id,id.equals("legacy")?"1.5.12":"2.1.20",id.equals("legacy")?"update-v1":"update-v3");f.tx(()->{var h=f.db.get("hosts",id);h.put("lastSeen",AssessmentServer.now());f.db.put("hosts",h);});}
            var snap=f.db.transaction(()->c.selection(map("filter",map()),AgentUpdateCoordinatorTest.ADMIN));
            var batch=f.db.transaction(()->c.create(map("selectionId",snap.get("selectionId"),"requestKey","migration-campaign-000001","targetVersion",DashboardVersion.VERSION,"batchSize",10,"runAssessmentAfterUpdate",false),AgentUpdateCoordinatorTest.ADMIN));String id=(String)batch.get("batchId");f.tx(()->f.updates.maintain());
            var legacy=f.db.documents("SELECT payload FROM agent_update_requests WHERE host_id='legacy'").get(0);var full=f.db.documents("SELECT payload FROM agent_update_requests WHERE host_id='full'").get(0);
            var action=f.db.transaction(()->f.updates.delivery("legacy","update-v1"));String child=(String)action.get("requestId");check(child!=null,"bridge created on admission");check(AgentUpdateCampaigns.occupied(f.db)==2,"bridge counts in parent slot");
            f.tx(()->c.command(map("batchId",id,"action","pause"),AgentUpdateCoordinatorTest.ADMIN));
            boolean denied=false;try{f.db.transaction(()->f.updates.claim("legacy",child));}catch(EnrollmentRegistry.Failure expected){denied=true;}check(denied,"delivered bridge cannot claim after pause");
            f.tx(()->c.command(map("batchId",id,"action","resume"),AgentUpdateCoordinatorTest.ADMIN));f.tx(()->f.updates.maintain());
            check(Boolean.TRUE.equals(f.db.transaction(()->f.updates.claim("legacy",child)).get("executable")),"same bridge may claim after resume");
            check(Boolean.TRUE.equals(f.db.transaction(()->f.updates.bundleClaim("full",(String)full.get("id"))).get("executable")),"direct V3 claim");
            f.tx(()->c.command(map("batchId",id,"action","cancel"),AgentUpdateCoordinatorTest.ADMIN));f.tx(()->f.updates.maintain());
            check(f.db.get("agent_update_requests",(String)legacy.get("id")).get("status").equals("DOWNLOADING"),"claimed bridge protects parent from cancel");
            check(f.db.get("agent_update_requests",(String)full.get("id")).get("status").equals("DOWNLOADING"),"full installation survives cancel");
            check(AgentUpdateCampaigns.occupied(f.db)==2,"both initiated slots retained after cancellation");
            f.db.transaction(()->f.updates.download("legacy",child));
            f.db.transaction(()->{try{return f.updates.bundleDownload("full",(String)full.get("id"),"manifest");}catch(Exception e){throw new RuntimeException(e);}});
            check(f.db.scalar("SELECT count(*) FROM agent_update_requests WHERE host_id='waiting' AND status='CANCELLED'")==1,"pending host cancelled");
            System.out.println("AgentCampaignMigrationTest PASS: bridge and direct V3 share global capacity; pause fences claim; resume preserves bridge; cancellation retains active downloads.");
        }
    }
}
