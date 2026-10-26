package assessment;

import java.util.*;
import static assessment.AssessmentServer.map;

/** Public operation history. SQL aggregates and pages before any detailed payload is read. */
final class WorkRequests {
    private static final List<String> STATES=List.of("SUCCEEDED","WAITING","DOWNLOADING","INSTALLING","VERIFYING","UNCONFIRMED","FAILED","ROLLED_BACK","CANCELLED","EXPIRED","QUEUED","RUNNING","PARTIAL");
    private static final Set<String> FILTERS=Set.of("ALL","ACTIVE","SUCCEEDED","FAILED","UNCONFIRMED","CANCELLED","EXPIRED","PARTIAL");
    private final InventoryStore db;
    WorkRequests(InventoryStore db){this.db=db;}
    private static String s(Object value){return value instanceof String?(String)value:"";}
    private static long n(Object value){return value instanceof Number?((Number)value).longValue():0;}
    private static void require(boolean ok,int status,String message){if(!ok)throw new EnrollmentRegistry.Failure(status,message);}
    private static String clean(Object value){String result=s(value).replaceAll("[\\x00-\\x1f\\x7f]"," ");return result.length()>500?result.substring(0,500):result;}
    private static String diagnostic(Object value){String result=s(value);return AgentUpdateRegistry.DIAGNOSTICS.contains(result)?result:"";}
    /** Copy only human-readable warning strings, never arbitrary coverage/configuration fields. */
    static List<String> observationNotes(Object source,String knownSecret){
        List<String> result=new ArrayList<>();if(!(source instanceof List<?> notes))return result;
        for(Object value:notes){if(!(value instanceof String note))continue;
            if(!knownSecret.isEmpty())note=note.replace(knownSecret,"[redacted]");
            note=note.replaceAll("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/-]+=*","$1[redacted]");
            note=note.replaceAll("(?i)(\\b[\\w.-]*(?:password|passwd|token|secret|api[_-]?key|credential|authorization)[\\w.-]*\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)","$1[redacted]");
            note=note.replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@","$1[redacted]@");
            note=clean(note).trim();if(!note.isEmpty()&&!result.contains(note))result.add(note);if(result.size()>=50)break;
        }return result;
    }
    private static boolean sameInstant(Object first,Object second){try{return java.time.Instant.parse(s(first)).equals(java.time.Instant.parse(s(second)));}catch(Exception ignored){return false;}}
    private Map<String,Object> observations(Map<String,Object> job){
        if(job.get("coverage") instanceof Map<?,?> coverage&&coverage.get("warnings") instanceof List<?>)return map("observations",observationNotes(coverage.get("warnings"),""),"observationsAvailable",true,"observationSource","request");
        // An old request may use the latest host coverage only with matching recorded evidence.
        Map<String,Object> host=db.get("hosts",s(job.get("hostId")));String expected=s(job.get("status")).equals("PARTIAL")?"partial":s(job.get("status")).equals("SUCCEEDED")?"complete":"";
        if(!expected.isEmpty()&&host!=null&&host.get("coverage") instanceof Map<?,?> coverage&&expected.equals(coverage.get("status"))&&coverage.get("warnings") instanceof List<?>){
            Object at=job.getOrDefault("capturedAt",job.get("finishedAt")),latest=host.get("lastAssessmentAt");
            if(sameInstant(at,latest)&&(!coverage.containsKey("lastAssessmentAt")||sameInstant(at,coverage.get("lastAssessmentAt"))))return map("observations",observationNotes(coverage.get("warnings"),""),"observationsAvailable",true,"observationSource","latest-matching");
        }
        return map("observations",List.of(),"observationsAvailable",false,"observationSource","unavailable");
    }
    static Map<String,Object> snapshot(InventoryStore db,Map<String,Object> host,String id){
        String environment=host==null?"":s(host.get("environmentId"));Map<String,Object> env=environment.isEmpty()?null:db.get("environments",environment);
        return map("hostname",host==null?id:host.getOrDefault("hostname",id),"environmentId",environment,"environmentName",env==null?"Sem ambiente":env.get("name"));
    }
    static final class Filter {
        String environment="",host="",type="ALL",status="ALL",q="",from="",to="";
        int page=1,size=20; boolean compact=false; String machineStatus="",machineQuery="";
        void validate(){
            require(Set.of("ALL","ASSESSMENT","AGENT_UPDATE").contains(type)&&FILTERS.contains(status),400,"Invalid work request filter");
            require(environment.length()<=100&&host.length()<=100&&q.length()<=200&&page>=1&&page<=1000000&&size>=1&&size<=100,400,"Invalid work request scope or pagination");
            require(machineQuery.length()<=200&&(machineStatus.isEmpty()||STATES.contains(machineStatus)),400,"Invalid machine filter");
            from=InventoryQueries.time(from,false);to=InventoryQueries.time(to,true);
            require(from.isEmpty()||to.isEmpty()||java.time.Instant.parse(from).isBefore(java.time.Instant.parse(to)),400,"Invalid date range");
        }
        String scope(List<Object> args){String sql="1=1";if(!environment.isEmpty()){sql+=" AND environmentId=?";args.add(environment.equals("__unassigned__")?"":environment);}if(!host.isEmpty()){sql+=" AND hostId=?";args.add(host);}return sql;}
    }
    private static String field(String payload,String name,String fallback){return "coalesce(json_extract("+payload+",'$."+name+"'),"+fallback+")";}
    private static String labels(String payload){
        String environment=field(payload,"environmentId","coalesce(h.environment_id,'')");
        return field(payload,"hostname","coalesce(h.hostname,'')")+" AS hostname,"+environment+" AS environmentId,"+field(payload,"environmentName","coalesce((SELECT name FROM environments WHERE id="+environment+"),'Sem ambiente')")+" AS environmentName";
    }
    private String cte(Filter f,List<Object> args){
        String request="SELECT r.id AS rowId,r.batch_id AS batchId,r.host_id AS hostId,"+labels("r.payload")+",r.status,"+field("b.payload","createdAt",field("r.payload","createdAt","''"))+" AS createdAt,"+field("r.payload","updatedAt",field("r.payload","createdAt","''"))+" AS updatedAt,"+field("r.payload","finishedAt",field("r.payload","updatedAt","''"))+" AS finishedAt,"+field("r.payload","actorName",field("b.payload","actorName","''"))+" AS requestedBy,coalesce(b.target_version,json_extract(r.payload,'$.targetVersion'),'') AS targetVersion,1 AS isRequest FROM agent_update_requests r LEFT JOIN agent_update_batches b ON b.id=r.batch_id LEFT JOIN hosts h ON h.id=r.host_id WHERE coalesce(json_extract(r.payload,'$.parentOperationId'),'')=''";
        String skipped="SELECT '' AS rowId,b.id AS batchId,"+field("sk.value","hostId","''")+" AS hostId,"+labels("sk.value")+",'SKIPPED' AS status,"+field("b.payload","createdAt","''")+" AS createdAt,"+field("b.payload","createdAt","''")+" AS updatedAt,"+field("b.payload","createdAt","''")+" AS finishedAt,"+field("b.payload","actorName","''")+" AS requestedBy,b.target_version AS targetVersion,0 AS isRequest FROM agent_update_batches b,json_each(b.payload,'$.skipped') sk LEFT JOIN hosts h ON h.id=json_extract(sk.value,'$.hostId')";
        // Poll only batches that can still be active; retain their terminal siblings for counts.
        if(f.type.equals("AGENT_UPDATE")&&f.status.equals("ACTIVE")){String active="SELECT batch_id FROM agent_update_requests WHERE status IN ("+AgentUpdateRegistry.BUSY_SQL+") UNION SELECT batch_id FROM agent_update_assessments WHERE state IN ('READY','QUEUED','RUNNING')";request+=" AND r.batch_id IN ("+active+")";skipped+=" WHERE b.id IN ("+active+")";}
        else if(f.type.equals("ASSESSMENT")){request+=" AND 0";skipped+=" WHERE 0";}
        StringBuilder counts=new StringBuilder();for(String state:STATES)counts.append(",sum(CASE WHEN status='").append(state).append("' THEN 1 ELSE 0 END) AS ").append(state);
        String grouped="SELECT batchId AS id,'AGENT_UPDATE' AS type,min(createdAt) AS createdAt,max(updatedAt) AS updatedAt,max(finishedAt) AS finishedAt,max(requestedBy) AS requestedBy,max(targetVersion) AS targetVersion,CASE WHEN count(DISTINCT environmentId)=1 THEN min(environmentId) ELSE '' END AS environmentId,CASE WHEN count(DISTINCT environmentId)=1 THEN min(environmentName) ELSE 'Vários ambientes' END AS environmentName,sum(isRequest) AS total,sum(CASE WHEN rowId IN (SELECT operation_id FROM agent_update_assessments WHERE state IN ('READY','QUEUED','RUNNING')) THEN 1 ELSE 0 END) AS pendingAssessments"+counts+",group_concat(hostname,' ') AS searchNames FROM update_rows WHERE "+f.scope(args)+" GROUP BY batchId";
        String summary="SELECT *,CASE WHEN UNCONFIRMED>0 THEN 'UNCONFIRMED' WHEN DOWNLOADING+INSTALLING+VERIFYING>0 THEN 'RUNNING' WHEN WAITING>0 THEN 'WAITING' WHEN FAILED+ROLLED_BACK>0 THEN 'FAILED' WHEN total>0 AND SUCCEEDED=total THEN 'SUCCEEDED' WHEN total>0 AND CANCELLED=total THEN 'CANCELLED' WHEN total>0 AND EXPIRED=total THEN 'EXPIRED' ELSE 'PARTIAL' END AS status FROM update_groups";
        StringBuilder jobCounts=new StringBuilder();for(String state:STATES)jobCounts.append(",CASE WHEN status='").append(state).append("' THEN 1 ELSE 0 END AS ").append(state);
        String jobs="SELECT j.id,j.host_id AS hostId,"+labels("j.payload")+",j.status,j.created_at AS createdAt,"+field("j.payload","finishedAt",field("j.payload","startedAt","j.created_at"))+" AS updatedAt,"+field("j.payload","finishedAt","''")+" AS finishedAt,"+field("j.payload","actorName","CASE WHEN json_extract(j.payload,'$.trigger')='scheduled' THEN 'Agendamento' ELSE '' END")+" AS requestedBy,CASE WHEN json_type(j.payload,'$.coverage.warnings[0]')='text' THEN json_extract(j.payload,'$.coverage.warnings[0]') ELSE '' END AS observationSummary FROM jobs j JOIN hosts h ON h.id=j.host_id";
        if(f.type.equals("AGENT_UPDATE"))jobs+=" WHERE 0";
        return "WITH update_rows AS ("+request+" UNION ALL "+skipped+"),update_groups AS ("+grouped+"),update_summary AS ("+summary+"),job_rows AS ("+jobs+"),operations AS (SELECT id,type,createdAt,updatedAt,finishedAt,requestedBy,targetVersion,environmentId,environmentName,total,"+String.join(",",STATES)+",searchNames,status,'' AS observationSummary,pendingAssessments FROM update_summary UNION ALL SELECT id,'ASSESSMENT',createdAt,updatedAt,finishedAt,requestedBy,'' AS targetVersion,environmentId,environmentName,1 AS total"+jobCounts+",hostname AS searchNames,status,observationSummary,0 AS pendingAssessments FROM job_rows WHERE "+f.scope(args)+") ";
    }
    private String conditions(Filter f,List<Object> args){
        String where="1=1";
        if(!f.type.equals("ALL")){where+=" AND type=?";args.add(f.type);}
        if(f.status.equals("ACTIVE"))where+=" AND (status IN ('QUEUED','WAITING','RUNNING','UNCONFIRMED') OR pendingAssessments>0)";
        else if(!f.status.equals("ALL")){where+=" AND status=?";args.add(f.status);}
        if(!f.q.isEmpty()){where+=" AND (id || ' ' || targetVersion || ' ' || requestedBy || ' ' || environmentName || ' ' || coalesce(searchNames,'')) LIKE ? ESCAPE '\\'";args.add("%"+InventoryQueries.escapeLike(f.q)+"%");}
        if(!f.from.isEmpty()){where+=" AND julianday(createdAt)>=julianday(?)";args.add(f.from);}
        if(!f.to.isEmpty()){where+=" AND julianday(createdAt)<julianday(?)";args.add(f.to);}
        return where;
    }
    private Map<String,Object> item(Map<String,Object> row){
        Map<String,Object> counts=map("total",n(row.get("total")));for(String state:STATES)counts.put(state,n(row.get(state)));
        String type=s(row.get("type")),id=s(row.get("id")),status=s(row.get("status"));
        Map<String,Object> result=map("id",id,"type",type,"title",type.equals("AGENT_UPDATE")?"Atualização de agentes para v"+s(row.get("targetVersion")):"Assessment "+id.substring(0,Math.min(8,id.length())),"status",status,"createdAt",s(row.get("createdAt")),"updatedAt",s(row.get("updatedAt")),"requestedBy",clean(row.get("requestedBy")),"environmentId",s(row.get("environmentId")),"environmentName",clean(row.get("environmentName")),"targetVersion",s(row.get("targetVersion")),"counts",counts);
        if(!Set.of("QUEUED","WAITING","RUNNING","UNCONFIRMED").contains(status)&&!s(row.get("finishedAt")).isEmpty())result.put("finishedAt",row.get("finishedAt"));List<String> notes=observationNotes(List.of(s(row.get("observationSummary"))),"");if(!notes.isEmpty())result.put("observationSummary",notes.get(0));result.put("pendingAssessments",n(row.get("pendingAssessments")));if(type.equals("AGENT_UPDATE")){var batches=db.documents("SELECT payload FROM agent_update_batches WHERE id=?",id);var control=batches.isEmpty()?Map.<String,Object>of():batches.get(0);result.put("campaign",map("enabled",control.getOrDefault("campaign",false),"state",control.getOrDefault("controlState","LEGACY"),"reason",control.getOrDefault("pauseReason",""),"batchSize",control.getOrDefault("batchSize",1),"concurrency",AgentUpdateCampaigns.limit(db)));}return result;
    }
    Map<String,Object> list(Filter f){
        f.validate();List<Object> args=new ArrayList<>();String cte=cte(f,args),where=conditions(f,args);long total=db.scalar(cte+"SELECT count(*) FROM operations WHERE "+where,args.toArray());long pages=Math.max(1,(total+f.size-1)/f.size);int page=(int)Math.min(f.page,pages);args.add(f.size);args.add((long)(page-1)*f.size);
        List<Object> items=new ArrayList<>();for(var row:db.rows(cte+"SELECT * FROM operations WHERE "+where+" ORDER BY julianday(createdAt) DESC,type,id DESC LIMIT ? OFFSET ?",args.toArray()))items.add(item(row));
        return map("items",items,"pagination",map("page",page,"pageSize",f.size,"total",total,"pages",pages));
    }
    Map<String,Object> active(Filter f){f.type="AGENT_UPDATE";f.status="ACTIVE";f.page=1;f.size=5;Map<String,Object> page=list(f);return map("items",page.get("items"),"total",((Map<?,?>)page.get("pagination")).get("total"));}
    @SuppressWarnings("unchecked") static List<Object> updateEvents(Map<String,Object> request){
        List<Object> events=new ArrayList<>();Object source=request.get("events");
        if(source instanceof List<?> entries){for(Object entry:entries){if(!(entry instanceof Map<?,?> event))continue;String status=s(event.get("status")),at=s(event.get("at"));if(!STATES.contains(status)||at.isEmpty())continue;Map<String,Object> safe=map("at",at,"status",status,"diagnostic",diagnostic(event.get("diagnostic")));if(event.get("stage") instanceof String stage&&Set.of("PROFILE_PENDING","BRIDGE_WAITING","BRIDGE_INSTALLING","BRIDGE_VERIFYING","BUNDLE_READY","BUNDLE_DOWNLOADING","PREPARING","INSTALLING","VERIFYING","SUCCEEDED","FAILED","ROLLED_BACK","STOPPED").contains(stage))safe.put("stage",stage);if(events.isEmpty()||!events.get(events.size()-1).equals(safe))events.add(safe);}if(events.size()>100)return new ArrayList<>(events.subList(events.size()-100,events.size()));return events;}
        String created=s(request.get("createdAt")),updated=s(request.get("updatedAt")),status=s(request.get("status"));
        if(!created.isEmpty())events.add(map("at",created,"status","WAITING","diagnostic",""));
        if(!updated.isEmpty()&&(!status.equals("WAITING")||!updated.equals(created)))events.add(map("at",updated,"status",status,"diagnostic",diagnostic(request.get("diagnostic"))));return events;
    }
    private Map<String,Object> machine(Map<String,Object> payload,Map<String,Object> row,boolean update){
        String status=s(payload.get("status"));List<Object> events;
        if(update)events=updateEvents(payload);else{events=new ArrayList<>();if(!s(payload.get("createdAt")).isEmpty())events.add(map("at",payload.get("createdAt"),"status","QUEUED","diagnostic",""));if(!s(payload.get("startedAt")).isEmpty())events.add(map("at",payload.get("startedAt"),"status","RUNNING","diagnostic",""));if(!s(payload.get("finishedAt")).isEmpty())events.add(map("at",payload.get("finishedAt"),"status",status,"diagnostic",clean(payload.getOrDefault("error",payload.get("summary")))));}
        Map<String,Object> result=map("id",payload.get("id"),"hostId",payload.get("hostId"),"hostname",clean(row.get("hostname")),"environmentId",s(row.get("environmentId")),"environmentName",clean(row.get("environmentName")),"fromVersion",s(payload.get("fromVersion")),"targetVersion",s(payload.get("targetVersion")),"status",status,"createdAt",s(payload.get("createdAt")),"updatedAt",s(payload.getOrDefault("updatedAt",payload.getOrDefault("finishedAt",payload.getOrDefault("startedAt",payload.get("createdAt"))))),"diagnostic",update?diagnostic(payload.get("diagnostic")):clean(payload.getOrDefault("error",payload.get("summary"))),"events",events,"historyPartial",update?!(payload.get("events") instanceof List<?>)||Boolean.TRUE.equals(payload.get("historyPartial")):false);
        if((update?AgentUpdateRegistry.TERMINAL.contains(status):!Set.of("QUEUED","RUNNING").contains(status))&&!s(row.get("finishedAt")).isEmpty())result.put("finishedAt",row.get("finishedAt"));if(!update){result.putAll(observations(payload));result.put("updateOrigins",new PostUpdateAssessments(db).publicOrigins(s(payload.get("id"))));}else{result.put("assessment",new PostUpdateAssessments(db).publicRelation(s(payload.get("id"))));result.put("updateMode",payload.get("updateMode"));result.put("migrationStage",payload.get("migrationStage"));result.put("runAssessmentAfterUpdate",payload.get("runAssessmentAfterUpdate"));result.put("waitingForSlot",Boolean.TRUE.equals(payload.get("campaign"))&&status.equals("WAITING")&&n(payload.get("startDeadlineMillis"))==0);result.put("cancelRequested",Boolean.TRUE.equals(payload.get("cancelRequested")));result.put("startDeadlineAt",payload.get("startDeadlineAt"));}return result;
    }
    @SuppressWarnings("unchecked") Map<String,Object> detail(Filter f,String id,String type){
        f.validate();require(!id.isEmpty()&&id.length()<=100&&Set.of("ASSESSMENT","AGENT_UPDATE").contains(type),400,"Select a work request");
        List<Object> args=new ArrayList<>();String prefix=cte(f,args);args.add(id);args.add(type);var rows=db.rows(prefix+"SELECT * FROM operations WHERE id=? AND type=? LIMIT 1",args.toArray());require(!rows.isEmpty(),404,"Work request not found in this scope");
        List<Object> machines=new ArrayList<>(),skipped=new ArrayList<>();Map<String,Object> machinePagination=map("page",1,"pages",1,"pageSize",1,"total",1);
        args=new ArrayList<>();prefix=cte(f,args);args.add(id);String where=" AND "+f.scope(args);
        if(type.equals("AGENT_UPDATE")){
            if(!f.machineStatus.isEmpty()){where+=" AND u.status=?";args.add(f.machineStatus);}
            if(!f.machineQuery.isEmpty()){where+=" AND u.hostname LIKE ? ESCAPE '\\'";args.add("%"+InventoryQueries.escapeLike(f.machineQuery)+"%");}
            long machineTotal=db.scalar(prefix+"SELECT count(*) FROM update_rows u JOIN agent_update_requests r ON r.id=u.rowId WHERE u.batchId=?"+where,args.toArray());
            int size=f.compact?f.size:1000;long pages=Math.max(1,(machineTotal+size-1)/size),page=Math.min(f.page,pages);args.add(size);args.add((page-1)*size);
            for(var row:db.rows(prefix+"SELECT u.*,r.payload FROM update_rows u JOIN agent_update_requests r ON r.id=u.rowId WHERE u.batchId=?"+where+" ORDER BY r.ordinal LIMIT ? OFFSET ?",args.toArray())){
                var value=machine((Map<String,Object>)Json.parse(s(row.get("payload"))),row,true);
                if(f.compact){value.remove("events");value.put("eventsDeferred",true);}machines.add(value);
            }
            machinePagination=map("page",page,"pages",pages,"pageSize",size,"total",machineTotal);
            List<Object> skipArgs=new ArrayList<>();String skipPrefix=cte(f,skipArgs);skipArgs.add(id);String skipScope=f.scope(skipArgs);
            if(!f.compact)for(var row:db.rows(skipPrefix+"SELECT u.hostId,u.hostname,coalesce(json_extract(sk.value,'$.reason'),'') AS reason FROM update_rows u JOIN agent_update_batches b ON b.id=u.batchId JOIN json_each(b.payload,'$.skipped') sk ON json_extract(sk.value,'$.hostId')=u.hostId WHERE u.isRequest=0 AND u.batchId=? AND "+skipScope+" ORDER BY u.hostId LIMIT 1000",skipArgs.toArray()))skipped.add(map("hostId",s(row.get("hostId")),"hostname",clean(row.get("hostname")),"reason",s(row.get("reason"))));
        }else{
            for(var row:db.rows(prefix+"SELECT j.*,source.payload FROM job_rows j JOIN jobs source ON source.id=j.id WHERE j.id=?"+where+" LIMIT 1",args.toArray()))machines.add(machine((Map<String,Object>)Json.parse(s(row.get("payload"))),row,false));
        }
        var summary=item(rows.get(0));
        if(type.equals("AGENT_UPDATE")){
            var campaign=(Map<String,Object>)summary.get("campaign");
            campaign.put("globalWaiting",db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND status='WAITING'",id));
            if(Boolean.TRUE.equals(campaign.get("enabled"))){
                long group=Math.max(1,n(campaign.get("batchSize"))),total=db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=?",id);
                long pending=db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND status IN ("+AgentUpdateRegistry.BUSY_SQL+")",id);
                campaign.put("groups",(total+group-1)/group);
                campaign.put("currentGroup",pending==0?0:db.scalar("SELECT min(ordinal) FROM agent_update_requests WHERE batch_id=? AND status IN ("+AgentUpdateRegistry.BUSY_SQL+")",id)/group+1);
            }
        }
        return map("request",summary,"machines",machines,"skipped",f.compact?List.of():skipped,"machinePagination",machinePagination);
    }
}
