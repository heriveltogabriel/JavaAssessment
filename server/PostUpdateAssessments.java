package assessment;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static assessment.AssessmentServer.map;

/** Durable post-update work. Mutation methods run inside the caller's store transaction. */
final class PostUpdateAssessments {
    static final long START_SPACING_MILLIS=10000L, INTENT_LIFETIME_MILLIS=86400000L;
    private static final Set<String> ACTIVE=Set.of("WAITING_FINAL","READY","QUEUED","RUNNING");
    private static final Set<String> JOB_TERMINAL=Set.of("SUCCEEDED","PARTIAL","FAILED","CANCELLED","EXPIRED");
    private static final Set<String> STOPPED=Set.of("CANCELLED","EXPIRED","SUPERSEDED");
    private final InventoryStore store;
    // queue() may invoke update maintenance again. Never recursively dispatch the same host.
    private final Set<String> dispatching=new HashSet<>();

    PostUpdateAssessments(InventoryStore store){this.store=Objects.requireNonNull(store);}
    private static String s(Object value){return value instanceof String?(String)value:"";}
    private static long n(Object value){return value instanceof Number?((Number)value).longValue():0;}
    private static String at(long now){return Instant.ofEpochMilli(now).toString();}
    private static boolean parent(Map<String,Object> operation){return operation!=null&&s(operation.get("parentOperationId")).isEmpty();}
    static boolean requested(Map<String,Object> operation){return parent(operation)&&Boolean.TRUE.equals(operation.get("runAssessmentAfterUpdate"));}
    private static String safe(Object value){List<String> notes=WorkRequests.observationNotes(List.of(s(value)),"");return notes.isEmpty()?"":notes.get(0);}

    private Map<String,Object> relation(String id){var rows=store.documents("SELECT payload FROM agent_update_assessments WHERE operation_id=?",id);return rows.isEmpty()?null:rows.get(0);}
    private List<Map<String,Object>> hostRelations(String host){return store.documents("SELECT payload FROM agent_update_assessments WHERE host_id=? ORDER BY ordinal,operation_id",host);}
    private void save(Map<String,Object> relation){store.exec("UPDATE agent_update_assessments SET state=?,job_id=?,not_before=?,expires_at=?,payload=? WHERE operation_id=?",s(relation.get("status")),s(relation.get("jobId")).isEmpty()?null:relation.get("jobId"),n(relation.get("notBeforeMillis")),n(relation.get("expiresMillis")),Json.stringify(relation),relation.get("operationId"));}
    private void state(Map<String,Object> relation,String status,String reason,long now){
        relation.put("status",status);relation.put("reason",reason);relation.put("updatedAt",at(now));
        if(!ACTIVE.contains(status))relation.put("finishedAt",at(now));save(relation);
    }

    void onCreated(Map<String,Object> operation,long now){
        if(!requested(operation))return;
        String id=s(operation.get("id")),host=s(operation.get("hostId")),batch=s(operation.get("batchId"));
        if(id.isEmpty()||host.isEmpty()||batch.isEmpty())throw new IllegalArgumentException("Post-update operation identity is required");
        Map<String,Object> intent=map("operationId",id,"hostId",host,"batchId",batch,"ordinal",n(operation.get("ordinal")),"targetVersion",s(operation.get("targetVersion")),"status","WAITING_FINAL","createdAt",at(now),"updatedAt",at(now),"notBeforeMillis",0L,"expiresMillis",0L,"actorName",safe(operation.get("actorName")),"reason","","summary","");
        store.exec("INSERT OR IGNORE INTO agent_update_assessments(operation_id,host_id,batch_id,ordinal,state,job_id,not_before,expires_at,payload) VALUES(?,?,?,?,?,NULL,0,0,?)",id,host,batch,n(operation.get("ordinal")),"WAITING_FINAL",Json.stringify(intent));
    }

    /** Called only after the coordinator verified the FINAL bundle, manager and heartbeat. */
    void onSucceeded(Map<String,Object> operation,long now){
        if(!requested(operation)||!"SUCCEEDED".equals(operation.get("status")))return;
        onCreated(operation,now);Map<String,Object> intent=relation(s(operation.get("id")));
        if(!"WAITING_FINAL".equals(intent.get("status")))return;
        intent.put("confirmedAt",at(now));intent.put("confirmedMillis",now);intent.put("expiresMillis",now+INTENT_LIFETIME_MILLIS);intent.put("expiresAt",at(now+INTENT_LIFETIME_MILLIS));intent.put("notBeforeMillis",now);
        state(intent,"READY","",now);
    }

    void onStopped(Map<String,Object> operation,String reason,long now){
        if(!parent(operation))return;Map<String,Object> intent=relation(s(operation.get("id")));
        if(intent!=null&&ACTIVE.contains(s(intent.get("status"))))stop(intent,"CANCELLED",safe(reason),now);
    }

    private boolean authorized(String host){
        var h=store.get("hosts",host);return h!=null&&!"uninstalled".equals(h.get("agentLifecycle"))&&store.scalar("SELECT count(*) FROM identities WHERE id=? AND status='active'",host)>0;
    }
    private boolean updating(String host){return AgentUpdateRegistry.busy(store,host);}
    private boolean uninstalling(String host){return store.scalar("SELECT count(*) FROM uninstall_requests WHERE host_id=? AND status IN ('WAITING','UNINSTALLING','UNCONFIRMED')",host)>0;}
    private boolean atTarget(Map<String,Object> intent,Map<String,Object> host){String target=s(intent.get("targetVersion")),observed=s(host.get("collectorVersion"));return AgentUpdateCatalog.version(target)&&AgentUpdateCatalog.version(observed)&&AgentUpdateCatalog.compare(observed,target)>=0;}

    private void stop(Map<String,Object> intent,String status,String reason,long now){
        state(intent,status,reason,now);String jobId=s(intent.get("jobId"));if(jobId.isEmpty())return;
        var job=store.get("jobs",jobId);
        // Expiring an association must never cancel a separately requested manual job.
        if(job!=null&&"QUEUED".equals(job.get("status"))&&"post-update".equals(job.get("trigger"))&&store.scalar("SELECT count(*) FROM agent_update_assessments WHERE job_id=? AND state IN ('QUEUED','RUNNING')",jobId)==0){
            job.put("status","FAILED");job.put("error","Post-update assessment was cancelled before it started");job.put("finishedAt",at(now));store.put("jobs",job);
        }
    }

    private void reconcile(String host,long now){
        boolean allowed=authorized(host);
        for(var intent:hostRelations(host)){
            String status=s(intent.get("status"));if(!ACTIVE.contains(status))continue;
            if(!allowed){stop(intent,"CANCELLED","AUTHORIZATION_REVOKED",now);continue;}
            String jobId=s(intent.get("jobId"));Map<String,Object> job=jobId.isEmpty()?null:store.get("jobs",jobId);
            if(job!=null){jobChanged(job,now);intent=relation(s(intent.get("operationId")));status=s(intent.get("status"));}
            else if(!jobId.isEmpty()){state(intent,"FAILED","JOB_UNAVAILABLE",now);continue;}
            if((status.equals("READY")||status.equals("QUEUED"))&&n(intent.get("expiresMillis"))>0&&now>=n(intent.get("expiresMillis")))stop(intent,"EXPIRED","ASSESSMENT_EXPIRED",now);
        }
    }

    /** Reconcile offline hosts too, so expiry does not depend on another collector contact. */
    void maintain(long now){for(var row:store.rows("SELECT DISTINCT host_id FROM agent_update_assessments WHERE state IN ('WAITING_FINAL','READY','QUEUED','RUNNING')"))reconcile(s(row.get("host_id")),now);}

    /** Host-scoped maintenance; it never creates a job until the final update is confirmed. */
    void dispatch(String host,long now,Function<String,Map<String,Object>> enqueue){
        if(!dispatching.add(host))return;
        try{
            reconcile(host,now);if(!authorized(host)||updating(host)||uninstalling(host))return;
            Map<String,Object> machine=store.get("hosts",host);List<Map<String,Object>> ready=new ArrayList<>();
            for(var intent:hostRelations(host))if("READY".equals(intent.get("status"))&&n(intent.get("confirmedMillis"))>0&&n(intent.get("notBeforeMillis"))<=now&&atTarget(intent,machine))ready.add(intent);
            if(ready.isEmpty()||store.scalar("SELECT count(*) FROM jobs WHERE host_id=? AND status='RUNNING'",host)>0)return;
            var queued=store.documents("SELECT payload FROM jobs WHERE host_id=? AND status='QUEUED' ORDER BY created_at,id LIMIT 1",host);Map<String,Object> job;boolean reused=!queued.isEmpty();
            if(reused){job=queued.get(0);if(!s(job.get("startedAt")).isEmpty())return;}
            else job=enqueue.apply(host);
            if(job==null||!host.equals(job.get("hostId"))||!"QUEUED".equals(job.get("status"))||s(job.get("id")).isEmpty())throw new IllegalStateException("Post-update queue returned an invalid job");
            String jobId=s(job.get("id")),required=s(job.get("requiredCollectorVersion"));
            for(var intent:ready)if(!AgentUpdateCatalog.version(required)||AgentUpdateCatalog.compare(s(intent.get("targetVersion")),required)>0)required=s(intent.get("targetVersion"));
            job.put("requiredCollectorVersion",required);
            if(!reused){var origin=ready.get(ready.size()-1);job.put("postUpdateOperationId",origin.get("operationId"));job.put("actorName",origin.get("actorName"));}
            store.put("jobs",job);
            for(var intent:ready){intent.put("jobId",jobId);intent.put("reused",reused);intent.put("queuedAt",at(now));state(intent,"QUEUED","",now);}
        }finally{dispatching.remove(host);}
    }

    private List<Map<String,Object>> jobRelations(String job){return store.documents("SELECT payload FROM agent_update_assessments WHERE job_id=? ORDER BY operation_id",job);}
    private void blocked(List<Map<String,Object>> intents,String reason,long notBefore,long now){for(var intent:intents){if(!"QUEUED".equals(intent.get("status")))continue;long next=Math.max(n(intent.get("notBeforeMillis")),notBefore);if(!reason.equals(intent.get("reason"))||next!=n(intent.get("notBeforeMillis"))){intent.put("reason",reason);intent.put("notBeforeMillis",next);intent.put("updatedAt",at(now));save(intent);}}}

    /** The caller changes the job to RUNNING and calls jobChanged in this SAME transaction. */
    boolean allowLease(String host,String jobId,long now){
        var associations=jobRelations(jobId);if(associations.isEmpty())return true;
        reconcile(host,now);var job=store.get("jobs",jobId);if(job==null||!host.equals(job.get("hostId")))return false;
        var intents=new ArrayList<Map<String,Object>>();for(var intent:jobRelations(jobId))if("QUEUED".equals(intent.get("status")))intents.add(intent);
        if(intents.isEmpty())return !"post-update".equals(job.get("trigger"));
        if(!"QUEUED".equals(job.get("status"))||!authorized(host)||updating(host)||uninstalling(host))return false;
        var machine=store.get("hosts",host);String environment=s(machine.get("environmentId"));
        for(var intent:intents)if(!host.equals(intent.get("hostId"))||!atTarget(intent,machine)||n(intent.get("confirmedMillis"))<=0||n(intent.get("notBeforeMillis"))>now)return false;
        long running=store.scalar("SELECT count(DISTINCT j.id) FROM jobs j JOIN agent_update_assessments a ON a.job_id=j.id WHERE j.status='RUNNING'");
        long environmentRunning=store.scalar("SELECT count(DISTINCT j.id) FROM jobs j JOIN agent_update_assessments a ON a.job_id=j.id WHERE j.status='RUNNING' AND coalesce(json_extract(a.payload,'$.leaseEnvironmentId'),'')=?",environment);
        if(running>=2||environmentRunning>=1){blocked(intents,"CAPACITY_LIMIT",now,now);return false;}
        long last=store.scalar("SELECT coalesce(max(CAST(json_extract(payload,'$.leaseStartedMillis') AS INTEGER)),0) FROM agent_update_assessments WHERE coalesce(json_extract(payload,'$.leaseEnvironmentId'),'')=?",environment);
        if(last>0&&now-last<START_SPACING_MILLIS){blocked(intents,"START_SPACING",last+START_SPACING_MILLIS,now);return false;}
        blocked(intents,"",now,now);return true;
    }

    /** Invoke for lease/result/timeout/revoke and BEFORE trimming a linked job from history. */
    void jobChanged(Map<String,Object> job,long now){
        String status=s(job.get("status"));if(!Set.of("QUEUED","RUNNING","SUCCEEDED","PARTIAL","FAILED","CANCELLED","EXPIRED").contains(status))return;
        for(var intent:jobRelations(s(job.get("id")))){
            String previous=s(intent.get("status"));if(STOPPED.contains(previous)||!s(job.get("hostId")).equals(intent.get("hostId")))continue;
            if(status.equals("QUEUED"))continue;
            if(status.equals("RUNNING")){
                long started;try{started=Instant.parse(s(job.get("startedAt"))).toEpochMilli();}catch(Exception invalid){started=now;}
                if(started<n(intent.get("confirmedMillis"))){intent.remove("jobId");intent.remove("queuedAt");intent.remove("reused");state(intent,"READY","PREVIOUS_ASSESSMENT",now);continue;}
                if(!intent.containsKey("leaseStartedMillis")){intent.put("leaseStartedMillis",started);intent.put("startedAt",at(started));var host=store.get("hosts",s(job.get("hostId")));intent.put("leaseEnvironmentId",host==null?"":s(host.get("environmentId")));}
                if(!previous.equals(status))state(intent,status,"",now);else save(intent);
            }else if(JOB_TERMINAL.contains(status)){
                intent.put("summary",safe(job.getOrDefault("error",job.get("summary"))));intent.put("jobFinishedAt",s(job.get("finishedAt")));
                List<String> notes=List.of();if(job.get("coverage") instanceof Map<?,?> coverage)notes=WorkRequests.observationNotes(coverage.get("warnings"),"");intent.put("observations",notes);
                if(!previous.equals(status)||!Objects.equals(intent.get("finishedAt"),job.get("finishedAt"))){state(intent,status,"",now);if(!s(job.get("finishedAt")).isEmpty()){intent.put("finishedAt",job.get("finishedAt"));save(intent);}}else save(intent);
            }
        }
    }

    Map<String,Object> publicRelation(String operationId){
        var intent=relation(operationId);if(intent==null)return null;Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("operationId","hostId","batchId","status","jobId","targetVersion","createdAt","updatedAt","confirmedAt","expiresAt","startedAt","finishedAt","summary","reason","reused","observations"))if(intent.containsKey(key))result.put(key,intent.get(key));
        String jobId=s(intent.get("jobId"));var job=jobId.isEmpty()?null:store.get("jobs",jobId);result.put("detailsAvailable",job!=null&&intent.get("hostId").equals(job.get("hostId")));long notBefore=n(intent.get("notBeforeMillis"));if(notBefore>0)result.put("notBefore",at(notBefore));return result;
    }
    List<Object> publicOrigins(String jobId){List<Object> result=new ArrayList<>();for(var intent:jobRelations(jobId))result.add(publicRelation(s(intent.get("operationId"))));return result;}
    boolean settled(String operationId){var intent=relation(operationId);return intent==null||!ACTIVE.contains(s(intent.get("status")));}
}
