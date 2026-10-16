package assessment;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Presence uses the entire fleet and the same boundaries in counts and filtered pages. */
public final class EnvironmentPresenceTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static long n(Object value){return ((Number)value).longValue();}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    static void host(InventoryStore db,String id,String env,String time,String lifecycle,String credential){
        db.put("hosts",map("id",id,"hostname",id,"environmentId",env,"lastSeen",time,"agentLifecycle",lifecycle,"coverage",map("status","partial","warnings",List.of("Fixture coverage note"))));
        db.put("identities",map("hostId",id,"installationId",id,"agentToken","fixture-"+id,"status",credential));
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("environment-presence-");
        try(InventoryStore db=new InventoryStore(root.toFile(),map(),"","")){
            db.put("environments",map("id","dev","name","Desenvolvimento","settings",map()));
            db.put("environments",map("id","prod","name","Produção","settings",map()));
            InventoryQueries queries=new InventoryQueries(db);InventoryQueries.Scope f=new InventoryQueries.Scope();
            f.now=Instant.parse("2026-10-15T12:00:00Z");f.environment="dev";f.view="hosts";f.size=20;
            for(int i=0;i<105;i++)host(db,"silent-"+i,"dev",f.now.minusSeconds(600).toString(),"","active");
            host(db,"recent","dev",f.now.minusSeconds(149).toString(),"","active");
            host(db,"boundary150","dev",f.now.minusSeconds(150).toString(),"","active");
            host(db,"boundary300","dev",f.now.minusSeconds(300).toString(),"","active");
            host(db,"boundary299","dev",f.now.minusSeconds(299).toString(),"","active");
            host(db,"future60","dev",f.now.plusSeconds(60).toString(),"","active");
            host(db,"future61","dev",f.now.plusSeconds(61).toString(),"","active");
            host(db,"invalid","dev","bad-time","","active");
            host(db,"awaiting","dev","","","active");
            host(db,"revoked","dev",f.now.minusSeconds(600).toString(),"","revoked");
            host(db,"removed","dev",f.now.minusSeconds(600).toString(),"uninstalled","revoked");
            host(db,"other-environment","prod",f.now.minusSeconds(600).toString(),"","active");
            Map<String,Object> expected=map("recent",2,"delayed",2,"silent",106,"unknown",2,"awaiting",1,"revoked",1,"uninstalled",1);
            Map<String,Object> presence=queries.presence(f);
            for(String state:expected.keySet()){
                check(n(presence.get(state))==n(expected.get(state)),state+" aggregate count");f.subset=state;
                check(n(obj(queries.page(f,"hosts").get("pagination")).get("total"))==n(expected.get(state)),state+" filter agrees with aggregate");
            }
            f.subset="silent";f.page=6;Map<String,Object> page=queries.page(f,"hosts");check(list(page.get("items")).size()==6,"last filtered page beyond 100 hosts");
            check(list(page.get("items")).stream().allMatch(h->"dev".equals(h.get("environmentId"))),"environment isolation");
            f.q="boundary300";check(n(obj(queries.page(f,"hosts").get("pagination")).get("total"))==1,"search combines with presence");f.q="";
            f.view="environments";Map<String,Object> view=queries.view(f);
            Map<String,Object> dev=list(view.get("environments")).stream().filter(e->e.get("id").equals("dev")).findFirst().orElseThrow();
            check(n(obj(dev.get("presence")).get("silent"))==106,"environment aggregate independent of absent host page");
            check(view.get("serverTime").equals(f.now.toString()),"same authoritative timestamp");
            Map<String,Object> h=db.get("hosts","silent-0");h.put("lastSeen",f.now.toString());db.put("hosts",h);
            check(n(queries.presence(f).get("silent"))==105&&n(queries.presence(f).get("recent"))==3,"heartbeat returns host to connected without assessment");
            check(n(obj(queries.page(f,"hosts").get("pagination")).get("total"))==105,"returned host leaves filtered list");
            check(n(queries.coverage(f).get("partialHosts"))==115,"coverage preserved separately");
            f.environment="missing";check(queries.presence(f).values().stream().allMatch(v->n(v)==0),"empty environment");
            System.out.println("EnvironmentPresenceTest PASS: full counts, thresholds, credential states, paging, search, scopes and heartbeat recovery.");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
