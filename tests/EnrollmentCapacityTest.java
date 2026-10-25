package assessment;

import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Boundary, retry, retention and paging checks against disposable SQLite. */
public final class EnrollmentCapacityTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    static String token(Map<String,Object> issued){return (String)issued.get("enrollmentToken");}
    static void full(EnrollmentRegistry registry,Set<String> envs)throws Exception{
        try{registry.issue("overflow",60,1,map(),false,"",envs);throw new AssertionError("active cap bypassed");}
        catch(EnrollmentRegistry.Failure e){check(e.status==409,"active cap enforced");}
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("enrollment-capacity-");
        try(InventoryStore store=new InventoryStore(root.toFile(),map(),"","")){
            EnrollmentRegistry registry=new EnrollmentRegistry(store);Set<String> envs=Set.of("env-a","env-b");
            for(String id:envs)store.put("environments",map("id",id,"name",id));
            check(EnrollmentRegistry.MAX_USES_PER_CODE==100000,"100,000 uses allowed");
            check(AssessmentServer.registrationLimit("600")==600&&AssessmentServer.registrationLimit("bad")==600,"rate default");
            check(AssessmentServer.registrationLimit("0")==1&&AssessmentServer.registrationLimit("100001")==100000,"rate bounds");
            Map<String,Object> issued=registry.issue("retry",60,1,map(),false,"env-a",envs);
            Map<String,Object> identity=registry.register(token(issued),"capacity-install-retry",map(),envs);
            String retryId=(String)obj(issued.get("enrollment")).get("id");
            long future=System.currentTimeMillis()+3600000;
            store.transaction(()->{
                for(int i=0;i<10000;i++)store.put("enrollments",map("id","fixture-"+i,"tokenHash",InventoryStore.hash("fixture-code-"+i),"label","Code "+i,"status","active","expiresMillis",future,"maxUses",1,"usedCount",0,"environmentId",i%2==0?"env-a":"env-b"));
                return null;
            });
            full(registry,envs);
            Map<String,Object> used=store.get("enrollments","fixture-0");used.put("usedCount",1);store.put("enrollments",used);
            registry.issue("slot after exhaustion",60,1,map(),false,"env-a",envs);
            full(registry,envs);
            check(identity.get("agentToken").equals(registry.register(token(issued),"capacity-install-retry",map(),envs).get("agentToken")),"exhausted unexpired retry preserved at cap");
            Map<String,Object> page=registry.page(envs,"env-a",1),second=registry.page(envs,"env-a",2);
            check(((Number)page.get("total")).intValue()==5002,"scope applied before pagination");
            check(((List<?>)page.get("enrollments")).size()==100,"bounded page");
            check(!page.get("enrollments").equals(second.get("enrollments")),"second page differs");
            Set<String> seen=new HashSet<>();
            for(int n=1;n<=51;n++)for(Object value:(List<?>)registry.page(envs,"env-a",n).get("enrollments")){
                Map<String,Object> e=obj(value);check("env-a".equals(e.get("environmentId")),"scope isolated");check(seen.add((String)e.get("id")),"no duplicate pages");
            }
            check(seen.size()==5002&&seen.contains(retryId),"all records reachable beyond 500");
            check(!Json.stringify(page).contains("tokenHash")&&!Json.stringify(page).contains("enrollmentToken"),"no credentials in listing");
            check(((List<?>)registry.page(envs,"__unassigned__",1).get("enrollments")).isEmpty(),"unassigned isolated");
            check(((List<?>)registry.page(envs,"",1000000).get("enrollments")).isEmpty(),"out of range empty");
            // Expired exhausted records must be cleaned even far below the capacity threshold.
            store.exec("DELETE FROM enrollments WHERE id LIKE 'fixture-%'");
            Map<String,Object> expired=store.get("enrollments",retryId);expired.put("expiresMillis",1);store.put("enrollments",expired);
            Map<String,Object> revoked=registry.issue("revoked",60,1,map(),false,"",envs);String revokedId=(String)obj(revoked.get("enrollment")).get("id");registry.revokeEnrollment(revokedId);
            Map<String,Object> orphan=registry.issue("orphan",60,1,map(),false,"env-b",envs);String orphanId=(String)obj(orphan.get("enrollment")).get("id");store.exec("DELETE FROM environments WHERE id='env-b'");
            String code=token(registry.issue("mass",60,100000,map(),false,"env-a",Set.of("env-a")));
            check(store.get("enrollments",retryId)==null&&store.get("enrollments",revokedId)==null&&store.get("enrollments",orphanId)==null,"ineligible codes cleaned below cap");
            check(store.get("identities",(String)identity.get("hostId"))!=null,"cleanup preserves registered identity");
            for(int i=0;i<2000;i++)registry.register(code,"capacity-install-"+i,map(),Set.of("env-a"));
            check(store.scalar("SELECT count(*) FROM identities")==2001,"more than 1,000 identities supported");
            System.out.println("EnrollmentCapacityTest PASS: "+checks+" checks; capacity, retry, retention, scoped paging, redaction and 2,000 registrations.");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
