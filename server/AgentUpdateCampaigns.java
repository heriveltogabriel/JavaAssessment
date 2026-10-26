package assessment;

import java.util.*;
import java.time.Instant;
import static assessment.AssessmentServer.map;

/** Fleet selection and durable admission. Caller holds InventoryStore's writer transaction. */
final class AgentUpdateCampaigns {
    static final int CHUNK=200;
    static final String ROOT="coalesce(json_extract(r.payload,'$.parentOperationId'),'')=''";
    static final String ACTIVE="'DOWNLOADING','INSTALLING','VERIFYING','UNCONFIRMED'";
    final InventoryStore db;
    final AgentUpdateRegistry updates;
    AgentUpdateCampaigns(InventoryStore db,AgentUpdateRegistry updates){this.db=db;this.updates=updates;}
    static String s(Object value){return value instanceof String?(String)value:"";}
    static long n(Object value){return value instanceof Number?((Number)value).longValue():0;}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object value){return value instanceof Map?(Map<String,Object>)value:Map.of();}
    static void require(boolean ok,int status,String message){if(!ok)throw new EnrollmentRegistry.Failure(status,message);}
    static boolean campaign(Map<String,Object> r){return Boolean.TRUE.equals(r.get("campaign"));}
    static Map<String,Object> batch(InventoryStore db,String id){var rows=db.documents("SELECT payload FROM agent_update_batches WHERE id=?",id);require(!rows.isEmpty(),404,"Campanha não encontrada.");return rows.get(0);}
    void saveBatch(String id,Map<String,Object> b){b.put("controlUpdatedAt",AssessmentServer.now());db.exec("UPDATE agent_update_batches SET payload=? WHERE id=?",Json.stringify(b),id);}
    static int limit(InventoryStore db){Object value=obj(db.metadata("agent-update-scheduler")).get("concurrency");return value instanceof Number?(int)n(value):2;}
    static long occupied(InventoryStore db){return db.scalar("SELECT count(*) FROM agent_update_requests r WHERE "+ROOT+" AND (r.status IN ("+ACTIVE+") OR (r.status='WAITING' AND coalesce(json_extract(r.payload,'$.startDeadlineMillis'),0)>0 AND coalesce(json_extract(r.payload,'$.cancelRequested'),0)=0))");}
    static boolean allowed(InventoryStore db,Map<String,Object> r){
        if(AgentUpdateCoordinator.child(r)){var parent=db.get("agent_update_requests",s(r.get("parentOperationId")));return parent!=null&&!AgentUpdateRegistry.TERMINAL.contains(s(parent.get("status")))&&allowed(db,parent);}
        if(AgentUpdateRegistry.TERMINAL.contains(s(r.get("status"))))return false;
        // A pause or cancellation must never interrupt a transaction already claimed.
        if(n(r.get("claimedMillis"))>0)return true;
        if(Boolean.TRUE.equals(r.get("cancelRequested")))return false;
        var rows=db.documents("SELECT payload FROM agent_update_batches WHERE id=?",r.get("batchId"));var b=rows.isEmpty()?Map.<String,Object>of():rows.get(0);
        if(Set.of("PAUSED","CANCELLING","CANCELLED").contains(s(b.get("controlState"))))return false;
        if(campaign(r))return n(r.get("startDeadlineMillis"))>0&&"RUNNING".equals(b.get("controlState"));
        if(db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND ordinal<? AND status IN ("+AgentUpdateRegistry.BUSY_SQL+")",r.get("batchId"),r.get("ordinal"))>0)return false;
        return db.metadata("agent-update-scheduler")==null||n(r.get("startDeadlineMillis"))>0||occupied(db)<limit(db);
    }
    static boolean queued(InventoryStore db,Map<String,Object> r){
        if(AgentUpdateCoordinator.child(r)){var p=db.get("agent_update_requests",s(r.get("parentOperationId")));return p!=null&&campaign(p)&&n(p.get("startDeadlineMillis"))==0&&n(p.get("claimedMillis"))==0;}
        return campaign(r)&&n(r.get("startDeadlineMillis"))==0&&n(r.get("claimedMillis"))==0;
    }
    Map<String,Object> settings(){return map("concurrency",limit(db),"occupied",occupied(db));}
    Map<String,Object> settings(Map<String,Object> input,UserAuth.User actor){
        require(input.keySet().equals(Set.of("concurrency")),400,"Parâmetros inválidos.");long value=n(input.get("concurrency"));require(value>=1&&value<=10&&((Number)input.get("concurrency")).doubleValue()==value,400,"Concorrência deve ser de 1 a 10.");
        db.metadata("agent-update-scheduler",map("concurrency",value));audit("AGENT_CAMPAIGN_LIMIT","global",actor);return settings();
    }
    static String candidates(){return "EXISTS (SELECT 1 FROM identities idn WHERE idn.id=h.id AND idn.status='active') AND coalesce(json_extract(h.payload,'$.agentLifecycle'),'')<>'uninstalled' AND assessment_update_older(json_extract(h.payload,'$.collectorVersion'),'"+DashboardVersion.VERSION+"')=1 AND coalesce(json_extract(h.payload,'$.updateCapability'),'') IN ('update-v1','update-v2','update-v3') AND NOT EXISTS (SELECT 1 FROM agent_update_requests r WHERE r.host_id=h.id AND r.status IN ("+AgentUpdateRegistry.BUSY_SQL+")) AND NOT EXISTS (SELECT 1 FROM uninstall_requests u WHERE u.host_id=h.id AND u.status IN ('WAITING','UNINSTALLING','UNCONFIRMED')) AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.host_id=h.id AND j.status='RUNNING')";}
    static String recent(long now){return "julianday(h.last_seen)>julianday('"+Instant.ofEpochMilli(now-300000)+"') AND julianday(h.last_seen)<=julianday('"+Instant.ofEpochMilli(now+60000)+"')";}
    String filter(Map<String,Object> f,List<Object> args){
        require(Set.of("environmentId","hostId","q","version","presence","eligibility","retryBatchId").containsAll(f.keySet()),400,"Filtro inválido.");
        for(Object value:f.values())require(value instanceof String&&((String)value).length()<=200,400,"Valor de filtro inválido.");
        String w="1=1";if(!s(f.get("environmentId")).isEmpty()){w+=" AND h.environment_id=?";args.add(f.get("environmentId").equals("__unassigned__")?"":f.get("environmentId"));}
        if(!s(f.get("hostId")).isEmpty()){w+=" AND h.id=?";args.add(f.get("hostId"));}
        if(!s(f.get("q")).isEmpty()){w+=" AND (h.hostname || ' ' || coalesce(json_extract(h.payload,'$.fqdn'),'') || ' ' || coalesce(json_extract(h.payload,'$.ipAddresses'),'')) LIKE ? ESCAPE '\\'";args.add("%"+InventoryQueries.escapeLike(s(f.get("q")))+"%");}
        if(!s(f.get("version")).isEmpty()){w+=" AND json_extract(h.payload,'$.collectorVersion')=?";args.add(f.get("version"));}
        String presence=s(f.get("presence")),eligible=s(f.get("eligibility"));require(Set.of("","all","recent","silent").contains(presence)&&Set.of("","all","eligible").contains(eligible),400,"Filtro inválido.");
        if(presence.equals("recent"))w+=" AND ("+recent(System.currentTimeMillis())+")";
        if(presence.equals("silent"))w+=" AND NOT coalesce(("+recent(System.currentTimeMillis())+"),0)";
        if(eligible.equals("eligible"))w+=" AND ("+candidates()+")";
        if(!s(f.get("retryBatchId")).isEmpty()){w+=" AND h.id IN (SELECT host_id FROM agent_update_requests WHERE batch_id=? AND status IN ('FAILED','ROLLED_BACK','EXPIRED','CANCELLED'))";args.add(f.get("retryBatchId"));}
        return w;
    }
    Map<String,Object> preview(Map<String,Object> filter,int page,int size){
        require(page>=1&&size>=1&&size<=100,400,"Página inválida.");List<Object> args=new ArrayList<>();String w=filter(filter,args);long total=db.scalar("SELECT count(*) FROM hosts h WHERE "+w,args.toArray());long pages=Math.max(1,(total+size-1)/size);page=(int)Math.min(page,pages);
        var release=updates.release();List<Object> rows=new ArrayList<>();args.add(size);args.add((page-1)*size);
        for(var h:db.documents("SELECT h.payload FROM hosts h WHERE "+w+" ORDER BY lower(h.hostname),h.id LIMIT ? OFFSET ?",args.toArray())){
            String reason=updates.reason(h,Boolean.TRUE.equals(release.get("available"))?(int)n(release.get("minUpdater")):0,"");var env=db.get("environments",s(h.get("environmentId")));
            rows.add(map("id",h.get("id"),"hostname",h.get("hostname"),"environmentName",env==null?"Sem ambiente":env.get("name"),"collectorVersion",h.get("collectorVersion"),"lastSeen",h.get("lastSeen"),"eligible",reason.isEmpty(),"reason",reason));
        }
        return map("hosts",rows,"release",release,"pagination",map("page",page,"pages",pages,"total",total,"pageSize",size),"settings",settings(),"environments",db.rows("SELECT id,name FROM environments ORDER BY name"));
    }
    Map<String,Object> selection(Map<String,Object> input,UserAuth.User actor){
        require(Set.of("filter","hostIds").containsAll(input.keySet())&&input.containsKey("filter")!=input.containsKey("hostIds"),400,"Escolha IDs ou filtro.");
        List<Object> args=new ArrayList<>();String w;
        if(input.containsKey("hostIds")){require(input.get("hostIds") instanceof List<?> list&&!list.isEmpty(),400,"Selecione máquinas.");var ids=(List<?>)input.get("hostIds");for(Object id:ids)require(id instanceof String&&!s(id).isEmpty()&&s(id).length()<=100,400,"ID inválido.");w="h.id IN (SELECT value FROM json_each(?))";args.add(Json.stringify(ids));}
        else {require(input.get("filter") instanceof Map,400,"Filtro inválido.");w=filter(obj(input.get("filter")),args);}
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        db.exec("DELETE FROM agent_update_selections WHERE expires_at<?",now-86400000L);
        db.exec("INSERT INTO agent_update_selections VALUES(?,?,?,?,?)",id,actor.id(),DashboardVersion.VERSION,now+900000L,Json.stringify(input));
        List<Object> insert=new ArrayList<>();insert.add(id);insert.addAll(args);
        db.exec("INSERT INTO agent_update_selection_hosts SELECT ?,h.id,row_number() OVER(ORDER BY lower(h.hostname),h.id)-1 FROM hosts h WHERE "+w+" AND ("+candidates()+")",insert.toArray());
        long selected=db.scalar("SELECT count(*) FROM agent_update_selection_hosts WHERE selection_id=?",id);require(selected>0,409,"Nenhuma máquina candidata neste filtro; atualize a prévia.");
        long online=db.scalar("SELECT count(*) FROM hosts h JOIN agent_update_selection_hosts s ON h.id=s.host_id WHERE s.selection_id=? AND ("+recent(now)+")",id);
        return map("selectionId",id,"version",DashboardVersion.VERSION,"selected",selected,"recent",online,"withoutRecentContact",selected-online,"expiresAt",Instant.ofEpochMilli(now+900000).toString());
    }
    Map<String,Object> create(Map<String,Object> input,UserAuth.User actor){
        require(Set.of("selectionId","requestKey","targetVersion","batchSize","runAssessmentAfterUpdate").containsAll(input.keySet()),400,"Parâmetros inválidos.");
        String selection=s(input.get("selectionId")),key=s(input.get("requestKey")),version=s(input.get("targetVersion"));long group=n(input.getOrDefault("batchSize",10));boolean assessment=Boolean.TRUE.equals(input.get("runAssessmentAfterUpdate"));
        require(key.matches("[A-Za-z0-9_-]{16,100}")&&group>=1&&group<=100&&input.getOrDefault("batchSize",10) instanceof Number&&((Number)input.getOrDefault("batchSize",10)).doubleValue()==group&&input.getOrDefault("runAssessmentAfterUpdate",false) instanceof Boolean,400,"Pedido inválido.");
        var prior=db.documents("SELECT payload FROM agent_update_batches WHERE request_key=?",key);
        if(!prior.isEmpty()){var b=prior.get(0);require(selection.equals(b.get("selectionId"))&&actor.id().equals(b.get("actorId"))&&version.equals(b.get("targetVersion"))&&group==n(b.get("batchSize"))&&assessment==Boolean.TRUE.equals(b.get("runAssessmentAfterUpdate")),409,"Chave pertence a outro pedido.");return map("batchId",b.get("id"),"campaign",control(s(b.get("id"))));}
        var selections=db.rows("SELECT * FROM agent_update_selections WHERE id=?",selection);require(!selections.isEmpty(),404,"Seleção não encontrada.");var chosen=selections.get(0);require(actor.id().equals(chosen.get("actor_id")),403,"Seleção pertence a outro administrador.");require(n(chosen.get("expires_at"))>System.currentTimeMillis()&&version.equals(chosen.get("version"))&&DashboardVersion.VERSION.equals(version),409,"Seleção expirou ou versão mudou. Revise novamente.");
        var release=updates.release();require(Boolean.TRUE.equals(release.get("available")),503,"RELEASE_UNAVAILABLE");
        String id=UUID.randomUUID().toString(),at=AssessmentServer.now();var b=map("id",id,"campaign",true,"selectionId",selection,"targetVersion",version,"batchSize",group,"controlState","RUNNING","createdAt",at,"actorId",actor.id(),"actorName",actor.name(),"runAssessmentAfterUpdate",assessment,"failureBaseline",0,"skipped",List.of(),"catalogHash",updates.catalogHash());
        db.exec("INSERT INTO agent_update_batches VALUES(?,?,?,?,?)",id,key,version,"[]",Json.stringify(b));
        // Set-based snapshot: no full host payloads or histories are materialized in Java.
        String uuid="lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||substr(hex(randomblob(2)),2)||'-8'||substr(hex(randomblob(2)),2)||'-'||hex(randomblob(6)))";
        db.exec("INSERT INTO agent_update_requests(id,host_id,batch_id,ordinal,status,payload) SELECT rid,host_id,?,row_number() OVER(ORDER BY sel.ordinal)-1,'WAITING',json_object('id',rid,'hostId',host_id,'batchId',?,'ordinal',row_number() OVER(ORDER BY sel.ordinal)-1,'status','WAITING','campaign',json('true'),'targetVersion',?,'fromVersion',coalesce(json_extract(h.payload,'$.collectorVersion'),''),'hostname',h.hostname,'environmentId',h.environment_id,'environmentName',coalesce(e.name,'Sem ambiente'),'createdAt',?,'updatedAt',?,'updatedMillis',?,'expiresMillis',0,'diagnostic','','actorId',?,'actorName',?,'runAssessmentAfterUpdate',json(?),'events',json('[]')) FROM (SELECT "+uuid+" AS rid,host_id,ordinal FROM agent_update_selection_hosts WHERE selection_id=? LIMIT -1 OFFSET 0) sel JOIN hosts h ON h.id=sel.host_id LEFT JOIN environments e ON e.id=h.environment_id WHERE ("+candidates()+")",id,id,version,at,at,System.currentTimeMillis(),actor.id(),actor.name(),assessment?"true":"false",selection);
        require(db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=?",id)>0,409,"A seleção não contém mais máquinas disponíveis. Revise novamente.");
        if(db.metadata("agent-update-scheduler")==null)db.metadata("agent-update-scheduler",map("concurrency",2));audit("AGENT_CAMPAIGN_CREATED",id,actor);
        return map("batchId",id,"campaign",control(id));
    }
    Map<String,Object> control(String id){var b=batch(db,id);var counts=new LinkedHashMap<String,Object>();for(var row:db.rows("SELECT status,count(*) AS n FROM agent_update_requests r WHERE batch_id=? AND "+ROOT+" GROUP BY status",id))counts.put(s(row.get("status")),row.get("n"));return map("id",id,"campaign",b.getOrDefault("campaign",false),"state",b.getOrDefault("controlState","LEGACY"),"reason",b.getOrDefault("pauseReason",""),"batchSize",b.getOrDefault("batchSize",1),"counts",counts,"pendingCancellation",db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND status='WAITING' AND json_extract(payload,'$.cancelRequested')=1",id),"settings",settings());}
    Map<String,Object> command(Map<String,Object> input,UserAuth.User actor){
        require(Set.of("batchId","action","hostIds","environmentId","q").containsAll(input.keySet()),400,"Parâmetros inválidos.");String id=s(input.get("batchId")),action=s(input.get("action"));var b=batch(db,id);
        require(Set.of("pause","resume","cancel").contains(action),400,"Ação inválida.");
        if(action.equals("pause")){require(Boolean.TRUE.equals(b.get("campaign"))&&"RUNNING".equals(b.get("controlState")),409,"Campanha não está em execução.");pause(id,b,"OPERATOR");}
        else if(action.equals("resume")){
            require(Boolean.TRUE.equals(b.get("campaign"))&&"PAUSED".equals(b.get("controlState")),409,"Campanha não está pausada.");require(!"RELEASE_CHANGED".equals(b.get("pauseReason")),409,"O pacote mudou. Cancele a fila e revise uma nova campanha.");require(db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND status='UNCONFIRMED'",id)==0,409,"Resolva as atualizações sem confirmação antes de retomar.");b.put("failureBaseline",failures(id));b.put("pauseReason","");b.put("controlState","RUNNING");saveBatch(id,b);
        }else{
            String where="batch_id=? AND status='WAITING' AND coalesce(json_extract(payload,'$.parentOperationId'),'')=''";List<Object> args=new ArrayList<>(List.of(id));boolean all=!input.containsKey("hostIds")&&s(input.get("environmentId")).isEmpty()&&s(input.get("q")).isEmpty();
            if(input.containsKey("hostIds")){require(input.get("hostIds") instanceof List<?> list&&!list.isEmpty(),400,"Selecione máquinas.");for(Object h:(List<?>)input.get("hostIds"))require(h instanceof String&&s(h).length()<=100,400,"ID inválido.");where+=" AND host_id IN (SELECT value FROM json_each(?))";args.add(Json.stringify(input.get("hostIds")));}
            if(!s(input.get("environmentId")).isEmpty()){where+=" AND json_extract(payload,'$.environmentId')=?";args.add(input.get("environmentId").equals("__unassigned__")?"":input.get("environmentId"));}
            if(!s(input.get("q")).isEmpty()){require(s(input.get("q")).length()<=200,400,"Busca inválida.");where+=" AND json_extract(payload,'$.hostname') LIKE ? ESCAPE '\\'";args.add("%"+InventoryQueries.escapeLike(s(input.get("q")))+"%");}
            db.exec("UPDATE agent_update_requests SET payload=json_set(payload,'$.cancelRequested',json('true'),'$.cancelActorId',?,'$.cancelActorName',?) WHERE "+where,concat(List.of(actor.id(),actor.name()),args));
            if(all){b.put("controlState","CANCELLING");saveBatch(id,b);}
        }
        audit("AGENT_CAMPAIGN_"+action.toUpperCase(Locale.ROOT),id,actor);return control(id);
    }
    static Object[] concat(List<Object> a,List<Object> b){var all=new ArrayList<>(a);all.addAll(b);return all.toArray();}
    long failures(String id){return db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND status IN ('FAILED','ROLLED_BACK')",id);}
    void pause(String id,Map<String,Object> b,String reason){
        b.put("controlState","PAUSED");b.put("pauseReason",reason);saveBatch(id,b);
        // Only unclaimed admissions are withdrawn; child claims consult the parent gate.
        db.exec("UPDATE agent_update_requests SET payload=json_set(payload,'$.startDeadlineMillis',0,'$.expiresMillis',0,'$.startDeadlineAt','','$.expiresAt','') WHERE batch_id=? AND status='WAITING' AND coalesce(json_extract(payload,'$.claimedMillis'),0)=0",id);
    }
    void maintain(long now){
        for(var r:db.documents("SELECT payload FROM agent_update_requests WHERE status='WAITING' AND json_extract(payload,'$.cancelRequested')=1 ORDER BY rowid LIMIT "+CHUNK)){
            updates.status(r,"CANCELLED","CANCELLED");updates.assessments().onStopped(r,"CANCELLED",now);
        }
        int remaining=CHUNK;
        long cursor=n(obj(db.metadata("agent-campaign-cursor")).get("rowid"));
        var batches=db.rows("SELECT rowid AS cursor,id,payload FROM agent_update_batches WHERE rowid>? AND json_extract(payload,'$.controlState') IN ('RUNNING','PAUSED','CANCELLING') ORDER BY rowid LIMIT 100",cursor);
        if(batches.isEmpty())batches=db.rows("SELECT rowid AS cursor,id,payload FROM agent_update_batches WHERE json_extract(payload,'$.controlState') IN ('RUNNING','PAUSED','CANCELLING') ORDER BY rowid LIMIT 100");
        for(var row:batches){
            if(n(row.get("cursor"))!=cursor){cursor=n(row.get("cursor"));db.metadata("agent-campaign-cursor",map("rowid",cursor));}
            String id=s(row.get("id"));var b=obj(Json.parse(s(row.get("payload"))));long busy=db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND status IN ("+AgentUpdateRegistry.BUSY_SQL+")",id);
            if(busy==0){b.put("controlState","CANCELLING".equals(b.get("controlState"))?"CANCELLED":"FINISHED");saveBatch(id,b);continue;}
            if(!Boolean.TRUE.equals(b.get("campaign"))||!"RUNNING".equals(b.get("controlState")))continue;
            if(db.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=? AND status='UNCONFIRMED'",id)>0){pause(id,b,"UNCONFIRMED");continue;}
            if(failures(id)-n(b.get("failureBaseline"))>=3){pause(id,b,"FAILURE_THRESHOLD");continue;}
            if(!DashboardVersion.VERSION.equals(b.get("targetVersion"))||!updates.catalogHash().equals(b.get("catalogHash"))){pause(id,b,"RELEASE_CHANGED");continue;}
            long first=db.scalar("SELECT min(ordinal) FROM agent_update_requests WHERE batch_id=? AND status IN ("+AgentUpdateRegistry.BUSY_SQL+")",id),group=Math.max(1,n(b.get("batchSize"))),end=(first/group+1)*group;
            for(var r:db.documents("SELECT payload FROM agent_update_requests WHERE batch_id=? AND status='WAITING' AND ordinal<? AND coalesce(json_extract(payload,'$.startDeadlineMillis'),0)=0 AND coalesce(json_extract(payload,'$.cancelRequested'),0)=0 ORDER BY ordinal LIMIT "+CHUNK,id,end)){
                if(remaining--<=0)return;
                var host=db.get("hosts",s(r.get("hostId")));if(host==null){updates.status(r,"CANCELLED","HOST_NOT_FOUND");continue;}
                if(db.scalar("SELECT count(*) FROM hosts h WHERE h.id=? AND ("+recent(now)+")",host.get("id"))==0){updates.status(r,"EXPIRED","AGENT_OFFLINE");continue;}
                if(occupied(db)>=limit(db))break;
                String reason=updates.prepareCampaign(r,host);
                if(!reason.isEmpty()){updates.status(r,"CANCELLED",reason);continue;}
                r.put("startDeadlineMillis",now+AgentUpdateRegistry.WAIT_FOR_START_MILLIS);r.put("startDeadlineAt",Instant.ofEpochMilli(now+AgentUpdateRegistry.WAIT_FOR_START_MILLIS).toString());r.put("expiresMillis",now+AgentUpdateRegistry.WAIT_FOR_START_MILLIS);r.put("expiresAt",r.get("startDeadlineAt"));updates.save(r);updates.assessments().onCreated(r,now);
                db.exec("UPDATE agent_update_requests SET payload=json_set(payload,'$.expiresMillis',?,'$.expiresAt',?) WHERE status='WAITING' AND json_extract(payload,'$.parentOperationId')=?",r.get("expiresMillis"),r.get("expiresAt"),r.get("id"));
            }
        }
    }
    void audit(String action,String id,UserAuth.User actor){db.exec("INSERT INTO human_audit(at,actor_id,actor_name,action,target_id,target_name) VALUES(?,?,?,?,?,?)",AssessmentServer.now(),actor.id(),actor.name(),action,id,"Campanha de agentes");}
}
