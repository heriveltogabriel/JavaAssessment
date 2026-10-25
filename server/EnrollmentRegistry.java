package assessment;

import java.io.IOException;
import java.security.*;
import java.util.*;

/** Per-identity SQLite records; credentials are never included in inventory responses. */
final class EnrollmentRegistry {
    /** Upper bounds that protect memory and SQLite; not machine limits. Exhausted codes do not count toward MAX_ENROLLMENTS. */
    static final int MAX_USES_PER_CODE=100000, MAX_ENROLLMENTS=10000, MAX_INSTALL_ACCESS_ENROLLMENTS=10000;
    static final class Failure extends RuntimeException { final int status; Failure(int s,String text){super(text);status=s;} }
    private final InventoryStore store;
    private final SecureRandom random=new SecureRandom();
    EnrollmentRegistry(InventoryStore store){this.store=store;}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
    private static String str(Object o){return o==null?"":String.valueOf(o);}
    private static long num(Object o){return o instanceof Number?((Number)o).longValue():0;}
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private static String stamp(long time){return java.time.Instant.ofEpochMilli(time).toString();}
    private static boolean activeEnvironment(Map<String,Object> e,Set<String> ids){String id=str(e.get("environmentId"));return id.isEmpty()||ids.contains(id);}
    private Map<String,Object> summary(Map<String,Object> e,Set<String> ids){
        String status=str(e.get("status"));if(!activeEnvironment(e,ids))status="revoked";else if("active".equals(status)&&num(e.get("expiresMillis"))<=System.currentTimeMillis())status="expired";else if("active".equals(status)&&num(e.get("usedCount"))>=num(e.get("maxUses")))status="exhausted";
        return AssessmentServer.map("id",e.get("id"),"label",e.get("label"),"createdAt",e.get("createdAt"),"expiresAt",e.get("expiresAt"),"maxUses",e.get("maxUses"),"usedCount",e.get("usedCount"),"status",status,"environmentId",str(e.get("environmentId")));
    }
    List<Object> summaries(Set<String> ids){List<Object> result=new ArrayList<>();for(Map<String,Object> e:store.documents("SELECT payload FROM enrollments WHERE json_extract(payload,'$.installAccessGeneration') IS NULL ORDER BY rowid DESC LIMIT 500"))result.add(summary(e,ids));return result;}
    Map<String,Object> page(Set<String> ids,String environmentId,int page){
        return store.readSnapshot(()->{
            String where="json_extract(payload,'$.installAccessGeneration') IS NULL";
            List<Object> args=new ArrayList<>();
            if(!environmentId.isEmpty()){where+=" AND coalesce(json_extract(payload,'$.environmentId'),'')=?";args.add(environmentId.equals("__unassigned__")?"":environmentId);}
            long total=store.scalar("SELECT count(*) FROM enrollments WHERE "+where,args.toArray());
            List<Object> values=new ArrayList<>();args.add(100);args.add((page-1)*100);
            for(Map<String,Object> e:store.documents("SELECT payload FROM enrollments WHERE "+where+" ORDER BY rowid DESC LIMIT ? OFFSET ?",args.toArray()))values.add(summary(e,ids));
            return AssessmentServer.map("enrollments",values,"total",total,"page",page,"pageSize",100);
        });
    }
    Map<String,Object> issue(String label,int minutes,int maxUses,Map<String,Object> settings,boolean autoCollect,String environmentId,Set<String> ids)throws IOException{
        return issue(label,minutes,maxUses,settings,autoCollect,environmentId,ids,null);
    }
    Map<String,Object> issueInstallAccess(String label,String environmentId,String generation,Set<String> ids)throws IOException{
        return issue(label,1440,MAX_USES_PER_CODE,AssessmentServer.map(),true,environmentId,ids,generation);
    }
    private Map<String,Object> issue(String label,int minutes,int maxUses,Map<String,Object> settings,boolean autoCollect,String environmentId,Set<String> ids,String generation)throws IOException{
        return store.transaction(()->{
            String category=generation==null?"json_extract(payload,'$.installAccessGeneration') IS NULL":"json_extract(payload,'$.installAccessGeneration') IS NOT NULL";
            // Preserve exhausted but unexpired codes for registration retries. Cleanup must
            // run independently of the active count, including after low-volume installations.
            store.exec("DELETE FROM enrollments WHERE status <> 'active' OR expires_millis <= ? OR (coalesce(json_extract(payload,'$.environmentId'),'') <> '' AND json_extract(payload,'$.environmentId') NOT IN (SELECT id FROM environments))",System.currentTimeMillis());
            String active=category+" AND json_extract(payload,'$.usedCount') < json_extract(payload,'$.maxUses')";
            int limit=generation==null?MAX_ENROLLMENTS:MAX_INSTALL_ACCESS_ENROLLMENTS;
            if(store.scalar("SELECT count(*) FROM enrollments WHERE "+active)>=limit)throw new Failure(409,"Enrollment history limit reached");
            String code=token(),id="enr-"+UUID.randomUUID();long expires=System.currentTimeMillis()+minutes*60000L;
            Map<String,Object> e=AssessmentServer.map("id",id,"label",label,"createdAt",AssessmentServer.now(),"expiresAt",stamp(expires),"expiresMillis",expires,"maxUses",maxUses,"usedCount",0,"status","active","tokenHash",InventoryStore.hash(code),"settings",settings,"autoCollect",autoCollect,"environmentId",environmentId);
            if(generation!=null)e.put("installAccessGeneration",generation);
            store.put("enrollments",e);return AssessmentServer.map("enrollment",summary(e,ids),"enrollmentToken",code);
        });
    }
    Map<String,Object> register(String code,String installationId,Map<String,Object> metadata,Set<String> ids)throws IOException{
        if(!code.matches("[a-f0-9]{64}"))throw new Failure(401,"Invalid enrollment credential");
        return store.transaction(()->{
            List<Map<String,Object>> es=store.documents("SELECT payload FROM enrollments WHERE token_hash=? LIMIT 1",InventoryStore.hash(code));
            if(es.isEmpty())throw new Failure(401,"Invalid enrollment credential");Map<String,Object> e=es.get(0);
            if(!activeEnvironment(e,ids)||!EnvironmentInstallAccess.enrollmentAllowed(store,e)||!"active".equals(e.get("status"))||num(e.get("expiresMillis"))<=System.currentTimeMillis())throw new Failure(403,"Enrollment has expired or was revoked");
            List<Map<String,Object>> prior=store.documents("SELECT payload FROM identities WHERE installation_id=? LIMIT 1",installationId);
            if(!prior.isEmpty()){
                Map<String,Object> h=prior.get(0);if(!e.get("id").equals(h.get("enrollmentId")))throw new Failure(409,"Installation already registered with a different code; administrator recovery is required when its operational credential is missing");
                if(!"active".equals(h.get("status")))throw new Failure(403,"Collector credential has been revoked");return h;
            }
            if(num(e.get("usedCount"))>=num(e.get("maxUses")))throw new Failure(409,"Enrollment usage limit reached");
            Map<String,Object> h=AssessmentServer.map("hostId","host-"+UUID.randomUUID(),"agentToken",token(),"status","active","installationId",installationId,"enrollmentId",e.get("id"),"createdAt",AssessmentServer.now(),"metadata",metadata,"settings",e.get("settings"),"autoCollect",e.get("autoCollect"),"environmentId",str(e.get("environmentId")));
            store.put("identities",h);e.put("usedCount",num(e.get("usedCount"))+1);store.put("enrollments",e);return h;
        });
    }
    String authenticate(String code){if(code.length()<4||code.length()>256)return null;List<Map<String,Object>> rows=store.rows("SELECT id FROM identities WHERE token_hash=? AND status='active' LIMIT 1",InventoryStore.hash(code));return rows.isEmpty()?null:str(rows.get(0).get("id"));}
    String credentialStatus(String id){List<Map<String,Object>> rows=store.rows("SELECT status FROM identities WHERE id=?",id);return rows.isEmpty()?"unknown":str(rows.get(0).get("status"));}
    /** Preserve only the information needed to reject old tokens and installation retries. */
    void retainDeletedIdentity(String id){
        List<Map<String,Object>> rows=store.rows("SELECT token_hash,installation_id,payload FROM identities WHERE id=?",id);
        if(rows.isEmpty())throw new Failure(409,"Machine identity is missing; administrator recovery is required before deletion");
        Map<String,Object> row=rows.get(0),previous=obj(Json.parse(str(row.get("payload"))));
        Map<String,Object> tombstone=AssessmentServer.map("hostId",id,"status","revoked","tokenHash",str(row.get("token_hash")),"installationId",str(row.get("installation_id")),"enrollmentId",str(previous.get("enrollmentId")),"deleted",true);
        store.put("identities",tombstone);
    }
    void revokeHost(String id)throws IOException{store.transaction(()->{Map<String,Object> h=store.get("identities",id);if(h==null)throw new Failure(404,"Host not found");h.put("status","revoked");h.put("revokedAt",AssessmentServer.now());store.put("identities",h);return null;});}
    void revokeEnrollment(String id)throws IOException{store.transaction(()->{Map<String,Object> e=store.get("enrollments",id);if(e==null)throw new Failure(404,"Enrollment not found");e.put("status","revoked");store.put("enrollments",e);return null;});}
}
