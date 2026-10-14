package assessment;

import java.util.*;
import java.security.SecureRandom;
import static assessment.AssessmentServer.map;

/** Typed individual action. All callers run inside the store transaction/snapshot. */
final class UninstallRegistry {
    static final String TYPE="UNINSTALL_AGENT_V1";
    private final InventoryStore db;
    private final EnrollmentRegistry credentials;
    UninstallRegistry(InventoryStore db,EnrollmentRegistry credentials){this.db=db;this.credentials=credentials;}
    private static String s(Object o){return o==null?"":String.valueOf(o);}
    private static long n(Object o){return o instanceof Number?((Number)o).longValue():0;}
    private static void require(boolean test,int code,String reason){if(!test)throw new EnrollmentRegistry.Failure(code,reason);}
    static boolean busy(Map<String,Object> r){return r!=null&&Set.of("WAITING","UNINSTALLING","UNCONFIRMED").contains(s(r.get("status")));}
    Map<String,Object> latest(String host){var rows=db.documents("SELECT payload FROM uninstall_requests WHERE host_id=? ORDER BY rowid DESC LIMIT 1",host);return rows.isEmpty()?null:rows.get(0);}
    Map<String,Object> publicRecord(Map<String,Object> r){if(r==null)return null;var result=new LinkedHashMap<>(r);result.remove("completionToken");result.remove("completionHash");result.remove("requestKey");return result;}
    private void save(Map<String,Object> r){db.put("uninstall_requests",r);}
    @SuppressWarnings("unchecked") private void event(Map<String,Object> r,String status){r.put("status",status);r.put("updatedAt",AssessmentServer.now());var events=(List<Object>)r.get("audit");if(events.size()<20)events.add(map("time",AssessmentServer.now(),"status",status));save(r);}
    void maintain(){long now=System.currentTimeMillis();for(var r:db.documents("SELECT payload FROM uninstall_requests WHERE status IN ('WAITING','UNINSTALLING') LIMIT 1000")){
        if(s(r.get("status")).equals("WAITING")&&n(r.get("expiresMillis"))<=now)event(r,"EXPIRED");
        else if(s(r.get("status")).equals("UNINSTALLING")&&n(r.get("claimedMillis"))+1800000L<=now)event(r,"UNCONFIRMED");
    }
        db.exec("UPDATE uninstall_requests SET payload=json_remove(payload,'$.completionToken','$.completionHash') WHERE json_extract(payload,'$.confirmUntil')<=? AND json_extract(payload,'$.completionToken') IS NOT NULL",now);
    }
    Map<String,Object> create(Map<String,Object> b,String actor){
        require(Set.of("hostId","confirmationName","requestKey","expiresInMinutes").containsAll(b.keySet()),400,"Unknown uninstall field");
        String id=s(b.get("hostId")),key=s(b.get("requestKey"));require(key.matches("[A-Za-z0-9_-]{16,100}"),400,"Invalid requestKey");
        var h=db.get("hosts",id);require(h!=null,404,"Host not found");require(s(h.get("hostname")).equals(b.get("confirmationName")),400,"Type the machine name exactly");
        var prior=db.documents("SELECT payload FROM uninstall_requests WHERE host_id=? AND request_key=?",id,key);if(!prior.isEmpty())return publicRecord(prior.get(0));
        require("active".equals(credentials.credentialStatus(id)),403,"Collector credential is not active");
        require("uninstall-v1".equals(h.get("uninstallCapability")),409,"Agent requires a root service update; cron/manual agents are incompatible");
        require(!AgentUpdateRegistry.busy(db,id),409,"Agent update is pending or in progress");
        maintain();require(!busy(latest(id)),409,"An uninstall request already exists");
        db.exec("DELETE FROM uninstall_requests WHERE host_id=? AND status NOT IN ('WAITING','UNINSTALLING','UNCONFIRMED') AND coalesce(json_extract(payload,'$.confirmUntil'),0)<=? AND id NOT IN (SELECT id FROM uninstall_requests WHERE host_id=? ORDER BY rowid DESC LIMIT 99)",id,System.currentTimeMillis(),id);
        require(db.scalar("SELECT count(*) FROM uninstall_requests WHERE host_id=?",id)<100,409,"Uninstall audit limit reached; wait for completion receipt expiry");
        Object minutes=b.getOrDefault("expiresInMinutes",60);require(minutes instanceof Number,400,"Invalid validity");long m=n(minutes);require(((Number)minutes).doubleValue()==m&&m>=5&&m<=1440,400,"Validity must be 5–1440 minutes");long expires=System.currentTimeMillis()+m*60000L;
        var r=map("id",UUID.randomUUID().toString(),"hostId",id,"type",TYPE,"requestKey",key,"status","WAITING","createdAt",AssessmentServer.now(),"requestedBy",actor,"expiresMillis",expires,"expiresAt",java.time.Instant.ofEpochMilli(expires).toString(),"audit",new ArrayList<>());event(r,"WAITING");

        return publicRecord(r);
    }
    Map<String,Object> cancel(String host,String request){maintain();var r=db.get("uninstall_requests",request);require(r!=null&&host.equals(r.get("hostId")),404,"Request not found");if("CANCELLED".equals(r.get("status")))return publicRecord(r);require("WAITING".equals(r.get("status")),409,"Only a pending request can be cancelled");event(r,"CANCELLED");return publicRecord(r);}
    Map<String,Object> delivery(String host,String capability){maintain();var r=latest(host);if(!busy(r))return null;
        // Block assessments throughout removal, including polls from old agents.
        if(!"uninstall-v1".equals(capability)||!"WAITING".equals(r.get("status")))return map("blocked",true);
        if(db.scalar("SELECT count(*) FROM jobs WHERE host_id=? AND status='RUNNING'",host)>0)return map("blocked",true);
        return map("type",TYPE,"requestId",r.get("id"),"expiresMillis",r.get("expiresMillis"));
    }
    Map<String,Object> claim(String host,String request){maintain();var r=db.get("uninstall_requests",request);require(r!=null&&host.equals(r.get("hostId")),404,"Request not found");if(Set.of("CANCELLED","EXPIRED").contains(s(r.get("status"))))return map("requestId",request,"executable",false);require(Set.of("WAITING","UNINSTALLING","UNCONFIRMED").contains(s(r.get("status"))),409,"Request no longer executable");
        if("WAITING".equals(r.get("status"))){require(db.scalar("SELECT count(*) FROM jobs WHERE host_id=? AND status='RUNNING'",host)==0,409,"Assessment still running");byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);String token=HexFormat.of().formatHex(secret);long now=System.currentTimeMillis();r.put("completionToken",token);r.put("completionHash",InventoryStore.hash(token));r.put("claimedMillis",now);r.put("confirmUntil",now+86400000L);event(r,"UNINSTALLING");
            for(var j:db.documents("SELECT payload FROM jobs WHERE host_id=? AND status='QUEUED'",host)){j.put("status","FAILED");j.put("error","Cancelled by agent uninstall");j.put("finishedAt",AssessmentServer.now());db.put("jobs",j);}
        }
        require(n(r.get("confirmUntil"))>System.currentTimeMillis(),410,"Completion window expired");return map("requestId",request,"completionToken",r.get("completionToken"),"confirmUntil",r.get("confirmUntil"));
    }
    Map<String,Object> complete(Map<String,Object> b,String token)throws java.io.IOException{
        require(Set.of("hostId","requestId","outcome","verification").containsAll(b.keySet()),400,"Unknown completion field");
        var r=db.get("uninstall_requests",s(b.get("requestId")));require(r!=null&&s(b.get("hostId")).equals(r.get("hostId")),401,"Invalid completion identity");
        require(token.matches("[a-f0-9]{64}")&&InventoryStore.hash(token).equals(r.get("completionHash"))&&n(r.get("confirmUntil"))>System.currentTimeMillis(),401,"Invalid or expired completion credential");String status=s(b.get("outcome"));require(Set.of("SUCCEEDED","FAILED").contains(status),400,"Invalid outcome");
        if(Set.of("SUCCEEDED","FAILED").contains(s(r.get("status")))){require(status.equals(r.get("status")),409,"Conflicting terminal outcome");return map("accepted",true,"requestId",r.get("id"),"status",status);}
        require(Set.of("UNINSTALLING","UNCONFIRMED").contains(s(r.get("status"))),409,"Request was not claimed");
        require(!status.equals("SUCCEEDED")||"OWN_FILES_AND_SCHEDULER_ABSENT_V1".equals(b.get("verification")),400,"Explicit local verification required");
        r.put("verification",status.equals("SUCCEEDED")?"OWN_FILES_AND_SCHEDULER_ABSENT_V1":"LOCAL_VERIFICATION_FAILED_V1");
        event(r,status);if(status.equals("FAILED")){var h=db.get("hosts",s(r.get("hostId")));h.put("uninstallCapability","");db.put("hosts",h);}if(status.equals("SUCCEEDED")){String host=s(r.get("hostId"));credentials.revokeHost(host);var h=db.get("hosts",host);h.put("agentLifecycle","uninstalled");h.put("uninstalledAt",AssessmentServer.now());h.put("uninstallCapability","");db.put("hosts",h);}
        return map("accepted",true,"requestId",r.get("id"),"status",status);
    }
}
