package assessment;

import java.io.*;
import java.time.Instant;
import java.util.*;
import static assessment.AssessmentServer.map;

/** One visible operation spans the legacy bridge and the final complete bundle. */
final class AgentUpdateCoordinator {
    private final InventoryStore db;
    private final AgentUpdateRoutes routes;
    AgentUpdateCoordinator(InventoryStore db,AgentUpdateRoutes routes){this.db=db;this.routes=routes;}
    private static String s(Object x){return x instanceof String?(String)x:"";}
    private static long n(Object x){return x instanceof Number?((Number)x).longValue():0;}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object x){return x instanceof Map?(Map<String,Object>)x:Map.of();}
    private static void require(boolean ok,int status,String reason){if(!ok)throw new EnrollmentRegistry.Failure(status,reason);}
    static boolean managed(Map<String,Object> request){return request!=null&&request.get("updatePlan") instanceof Map;}
    static boolean child(Map<String,Object> request){return request!=null&&!s(request.get("parentOperationId")).isEmpty();}
    AgentUpdateRoutes.Catalog current(){return routes.current();}
    boolean configured(){return routes.configured();}
    String reason(Map<String,Object> host,AgentUpdateRoutes.Catalog catalog){
        if(catalog==null)return "RELEASE_UNAVAILABLE";String version=s(host.get("collectorVersion"));if(!AgentUpdateCatalog.version(version))return "VERSION_UNKNOWN";
        if(AgentUpdateCatalog.compare(version,"1.5.12")<0)return "MINIMUM_AGENT_VERSION";
        if(AgentUpdateCatalog.compare(catalog.version(),version)<=0)return "NOT_NEWER";
        if(AgentUpdateRegistry.updaterGeneration(host.get("updateCapability"))>=3)return "";
        return routes.candidates(catalog,host).isEmpty()?"MIGRATION_PROFILE_REQUIRED":"";
    }
    void prepare(Map<String,Object> parent,Map<String,Object> host,AgentUpdateRoutes.Catalog catalog){
        List<AgentUpdateRoutes.Bridge> candidates=routes.candidates(catalog,host);boolean direct=AgentUpdateRegistry.updaterGeneration(host.get("updateCapability"))>=3;
        require(direct||!candidates.isEmpty(),409,"MIGRATION_PROFILE_REQUIRED");
        List<Object> profiles=new ArrayList<>();for(var b:candidates)profiles.add(map("id",b.id(),"bridgeVersion",b.version()));
        parent.put("updatePlan",map("schema",1,"catalogHash",catalog.hash(),"targetVersion",catalog.version(),"bundleSha256",catalog.bundle().sha256(),"managerSha256",catalog.bundle().managerHash(),"bundleManifestSha256",catalog.bundle().manifestHash(),"profiles",profiles,"profileIndex",0));
        parent.put("updateMode",direct?"FULL":"MIGRATION");parent.put("migrationStage",direct?"BUNDLE_READY":"PROFILE_PENDING");
        record(parent);
    }
    private AgentUpdateRoutes.Catalog pinned(Map<String,Object> parent){
        try{var c=routes.load(s(parent.get("targetVersion")));require(c.hash().equals(obj(parent.get("updatePlan")).get("catalogHash")),409,"UPDATE_PLAN_CHANGED");return c;}
        catch(IOException unavailable){throw new EnrollmentRegistry.Failure(503,"RELEASE_UNAVAILABLE");}
    }
    private AgentUpdateRoutes.Bridge bridgeFor(Map<String,Object> parent,int index){
        var c=pinned(parent);var plan=obj(parent.get("updatePlan"));Object profiles=plan.get("profiles");require(profiles instanceof List<?> list&&index>=0&&index<list.size(),409,"MIGRATION_PROFILE_REQUIRED");String id=s(obj(((List<?>)profiles).get(index)).get("id"));return c.bridges().stream().filter(b->b.id().equals(id)).findFirst().orElseThrow(()->new EnrollmentRegistry.Failure(503,"RELEASE_UNAVAILABLE"));
    }
    private Map<String,Object> latestChild(Map<String,Object> parent){var rows=db.documents("SELECT r.payload FROM agent_update_steps st JOIN agent_update_requests r ON r.id=st.request_id WHERE st.operation_id=? AND st.kind='LEGACY_BRIDGE' ORDER BY st.ordinal DESC LIMIT 1",parent.get("id"));return rows.isEmpty()?null:rows.get(0);}
    private boolean next(Map<String,Object> parent){return AgentUpdateCampaigns.allowed(db,parent);}
    private boolean conflict(String host){return db.scalar("SELECT count(*) FROM jobs WHERE host_id=? AND status='RUNNING'",host)>0||db.scalar("SELECT count(*) FROM uninstall_requests WHERE host_id=? AND status IN ('WAITING','UNINSTALLING','UNCONFIRMED')",host)>0;}
    Map<String,Object> delivery(Map<String,Object> parent,String capability){
        if(!AgentUpdateRegistry.BUSY.contains(s(parent.get("status")))||!next(parent)||conflict(s(parent.get("hostId"))))return map("blocked",true);
        var child=latestChild(parent);
        if("MIGRATION".equals(parent.get("updateMode"))&&child==null){
            var plan=obj(parent.get("updatePlan"));int index=(int)n(plan.get("profileIndex"));var bridge=bridgeFor(parent,index);if(AgentUpdateRegistry.updaterGeneration(capability)<bridge.minUpdater())return map("blocked",true);
            String id=UUID.randomUUID().toString(),batch=UUID.randomUUID().toString(),at=AssessmentServer.now();long expires=n(parent.get("expiresMillis"));
            child=map("id",id,"hostId",parent.get("hostId"),"parentOperationId",parent.get("id"),"batchId",batch,"ordinal",0,"targetVersion",bridge.version(),"fromVersion",parent.get("fromVersion"),"status","WAITING","createdAt",at,"updatedAt",at,"updatedMillis",System.currentTimeMillis(),"expiresMillis",expires,"expiresAt",Instant.ofEpochMilli(expires).toString(),"diagnostic","","actorId",parent.get("actorId"),"actorName",parent.get("actorName"),"profileId",bridge.id(),"profileIndex",index,"events",new ArrayList<>());
            db.put("agent_update_requests",child);db.exec("INSERT INTO agent_update_batches(id,request_key,target_version,selection,payload) VALUES(?,?,?,?,?)",batch,"internal-"+id,bridge.version(),Json.stringify(List.of(parent.get("hostId"))),Json.stringify(map("createdAt",at,"internal",true,"parentOperationId",parent.get("id"),"skipped",List.of())));
            db.exec("INSERT INTO agent_update_steps(id,operation_id,ordinal,kind,request_id,status,payload) VALUES(?,?,?,?,?,?,?)",id,parent.get("id"),index,"LEGACY_BRIDGE",id,"WAITING",Json.stringify(map("profileId",bridge.id(),"bridgeVersion",bridge.version())));
            parent.put("migrationStage","BRIDGE_WAITING");record(parent);
        }
        if(child!=null&&!"SUCCEEDED".equals(child.get("status"))){if(!"WAITING".equals(child.get("status")))return map("blocked",true);return map("type",AgentUpdateRegistry.TYPE,"requestId",child.get("id"),"targetVersion",child.get("targetVersion"),"expiresMillis",child.get("expiresMillis"));}
        if("MIGRATION".equals(parent.get("updateMode"))&&child==null)return map("blocked",true);
        // Bridge has the new client code even though its legacy capability marker is intact.
        if(child==null&&AgentUpdateRegistry.updaterGeneration(capability)<3)return map("blocked",true);
        return action(parent);
    }
    Map<String,Object> action(Map<String,Object> parent){return map("type","UPDATE_AGENT_V3","operationId",parent.get("id"),"targetVersion",parent.get("targetVersion"),"expiresMillis",parent.get("expiresMillis"),"signedPlanHash",obj(parent.get("updatePlan")).get("catalogHash"));}
    AgentUpdateCatalog.Release legacyRelease(Map<String,Object> child){var parent=db.get("agent_update_requests",s(child.get("parentOperationId")));require(managed(parent),409,"UPDATE_PLAN_REQUIRED");var b=bridgeFor(parent,(int)n(child.get("profileIndex")));require(b.id().equals(child.get("profileId")),409,"UPDATE_PLAN_CHANGED");return b.legacy();}
    void childChanged(Map<String,Object> child){
        var parent=db.get("agent_update_requests",s(child.get("parentOperationId")));if(!managed(parent)||AgentUpdateRegistry.TERMINAL.contains(s(parent.get("status"))))return;String state=s(child.get("status")),diagnostic=s(child.get("diagnostic"));
        if(state.equals(child.get("parentAppliedStatus")))return;
        child.put("parentAppliedStatus",state);db.put("agent_update_requests",child);
        db.exec("UPDATE agent_update_steps SET status=? WHERE id=?",state,child.get("id"));
        if(!"WAITING".equals(state)&&n(parent.get("claimedMillis"))==0){long now=System.currentTimeMillis();parent.put("claimedMillis",now);parent.put("expiresMillis",now+86400000L);parent.put("expiresAt",Instant.ofEpochMilli(now+86400000L).toString());}
        if(state.equals("SUCCEEDED")){parent.put("migrationStage","BUNDLE_READY");status(parent,"INSTALLING","");}
        else if(state.equals("FAILED")&&diagnostic.equals("INCOMPATIBLE_ASSETS")){
            var plan=obj(parent.get("updatePlan"));int next=(int)n(child.get("profileIndex"))+1;
            if(next<((List<?>)plan.get("profiles")).size()){plan.put("profileIndex",next);parent.put("migrationStage","PROFILE_PENDING");db.exec("UPDATE agent_update_steps SET kind='PROFILE_REJECTED' WHERE id=?",child.get("id"));status(parent,"DOWNLOADING","INCOMPATIBLE_ASSETS");}
            else status(parent,"FAILED","MIGRATION_PROFILE_REQUIRED");
        }else if(AgentUpdateRegistry.TERMINAL.contains(state)){parent.put("migrationStage","STOPPED");status(parent,state,diagnostic);}
        else {parent.put("migrationStage",state.equals("VERIFYING")?"BRIDGE_VERIFYING":"BRIDGE_INSTALLING");status(parent,state,diagnostic);}
    }
    Map<String,Object> claim(Map<String,Object> parent){
        require(managed(parent)&&!AgentUpdateRegistry.TERMINAL.contains(s(parent.get("status"))),409,"UPDATE_NOT_EXECUTABLE");require(next(parent)&&!conflict(s(parent.get("hostId"))),409,"UPDATE_CONFLICT");
        var child=latestChild(parent);require(!"MIGRATION".equals(parent.get("updateMode"))||child!=null&&"SUCCEEDED".equals(child.get("status")),409,"BRIDGE_NOT_CONFIRMED");
        long now=System.currentTimeMillis();require(n(parent.get("expiresMillis"))>now,410,"Update authorization expired");var catalog=pinned(parent);
        if(n(parent.get("claimedMillis"))==0){parent.put("claimedMillis",now);parent.put("expiresMillis",now+86400000L);parent.put("expiresAt",Instant.ofEpochMilli(now+86400000L).toString());}
        if(n(parent.get("bundleClaimedMillis"))==0){parent.put("bundleClaimedMillis",now);parent.put("bundleStageOrder",1);parent.put("migrationStage","BUNDLE_DOWNLOADING");status(parent,"DOWNLOADING","");}
        return map("operationId",parent.get("id"),"executable",true,"targetVersion",parent.get("targetVersion"),"expiresMillis",parent.get("expiresMillis"),"signedPlanHash",catalog.hash(),"bundleSha256",catalog.bundle().sha256(),"managerSha256",catalog.bundle().managerHash());
    }
    byte[] download(Map<String,Object> parent,String part)throws IOException{require(managed(parent)&&n(parent.get("bundleClaimedMillis"))>0&&n(parent.get("expiresMillis"))>System.currentTimeMillis()&&AgentUpdateRegistry.BUSY.contains(s(parent.get("status")))&&next(parent)&&!conflict(s(parent.get("hostId"))),409,"UPDATE_NOT_EXECUTABLE");return routes.asset(pinned(parent).bundle(),part);}
    Map<String,Object> report(Map<String,Object> parent,Map<String,Object> input){
        require(Set.of("hostId","operationId","status","version","bundleSha256","managerSha256","diagnostic").containsAll(input.keySet()),400,"Unknown bundle report field");String state=s(input.get("status")),version=s(input.get("version")),diagnostic=s(input.get("diagnostic"));require(Set.of("PREPARING","INSTALLING","VERIFYING","SUCCEEDED","ROLLED_BACK","FAILED","UNCONFIRMED").contains(state)&&AgentUpdateRegistry.DIAGNOSTICS.contains(diagnostic)&&AgentUpdateCatalog.version(version),400,"Invalid bundle report");
        String publicState=state.equals("PREPARING")?"DOWNLOADING":state;var plan=obj(parent.get("updatePlan"));require(managed(parent)&&n(parent.get("bundleClaimedMillis"))>0,409,"Claim bundle update first");require(plan.get("bundleSha256").equals(input.get("bundleSha256"))&&plan.get("managerSha256").equals(input.get("managerSha256")),409,"UPDATE_PLAN_CHANGED");
        if(AgentUpdateRegistry.TERMINAL.contains(s(parent.get("status")))){require(publicState.equals(parent.get("status"))&&version.equals(parent.get("reportedVersion"))&&diagnostic.equals(parent.get("diagnostic")),409,"Conflicting final report");return map("accepted",true,"operationId",parent.get("id"),"status",state);}
        require(n(parent.get("expiresMillis"))>System.currentTimeMillis(),410,"Update authorization expired");
        if(state.equals("SUCCEEDED")){
            var host=db.get("hosts",s(parent.get("hostId")));require(diagnostic.equals("OK")&&version.equals(parent.get("targetVersion"))&&version.equals(parent.get("observedVersion"))&&n(parent.get("observedMillis"))>=n(parent.get("bundleClaimedMillis"))&&host!=null&&version.equals(host.get("collectorVersion"))&&"update-v3".equals(host.get("updateCapability"))&&plan.get("bundleSha256").equals(input.get("bundleSha256"))&&plan.get("managerSha256").equals(input.get("managerSha256"))&&plan.get("bundleSha256").equals(host.get("bundleSha256"))&&plan.get("managerSha256").equals(host.get("managerSha256")),409,"Final bundle and updater have not been confirmed");
        }else if(state.equals("ROLLED_BACK"))require(version.equals(parent.get("fromVersion")),409,"Rollback must restore original version");
        else require(version.equals(parent.get("fromVersion"))||version.equals(parent.get("targetVersion"))||"MIGRATION".equals(parent.get("updateMode"))&&latestChild(parent)!=null&&version.equals(latestChild(parent).get("targetVersion")),409,"Version outside operation");
        int order=switch(state){case "PREPARING"->1;case "INSTALLING"->2;case "VERIFYING"->3;default->0;};if(order>0){require(order>=n(parent.get("bundleStageOrder")),409,"Update cannot move backwards");parent.put("bundleStageOrder",order);}
        parent.put("reportedVersion",version);parent.put("migrationStage",AgentUpdateRegistry.TERMINAL.contains(publicState)?publicState:state);status(parent,publicState,diagnostic);
        return map("accepted",true,"operationId",parent.get("id"),"status",state);
    }
    void maintain(){
        for(var child:db.documents("SELECT c.payload FROM agent_update_requests c JOIN agent_update_requests p ON p.id=json_extract(c.payload,'$.parentOperationId') WHERE p.status IN ('WAITING','DOWNLOADING','INSTALLING','VERIFYING','UNCONFIRMED') AND c.status IN ('SUCCEEDED','FAILED','ROLLED_BACK','CANCELLED','EXPIRED') AND coalesce(json_extract(c.payload,'$.parentAppliedStatus'),'')<>c.status")){if(AgentUpdateRegistry.TERMINAL.contains(s(child.get("status"))))childChanged(child);}
        for(var parent:db.documents("SELECT payload FROM agent_update_requests WHERE json_type(payload,'$.updatePlan')='object' AND status IN ('FAILED','ROLLED_BACK','CANCELLED','EXPIRED') AND id IN (SELECT json_extract(payload,'$.parentOperationId') FROM agent_update_requests WHERE status='WAITING')")){
            for(var child:db.documents("SELECT payload FROM agent_update_requests WHERE json_extract(payload,'$.parentOperationId')=? AND status='WAITING'",parent.get("id"))){child.put("status","CANCELLED");child.put("diagnostic","AUTHORIZATION_REVOKED");db.put("agent_update_requests",child);}
        }
    }
    private void status(Map<String,Object> parent,String status,String diagnostic){
        String at=AssessmentServer.now();List<Object> events=WorkRequests.updateEvents(parent);if(!status.equals(parent.get("status"))||!diagnostic.equals(parent.get("diagnostic"))||!Objects.equals(parent.get("lastMigrationStage"),parent.get("migrationStage")))events.add(map("at",at,"status",status,"diagnostic",diagnostic,"stage",parent.get("migrationStage")));
        if(events.size()>100)events=new ArrayList<>(events.subList(events.size()-100,events.size()));parent.put("events",events);parent.put("status",status);parent.put("diagnostic",diagnostic);parent.put("updatedAt",at);parent.put("updatedMillis",System.currentTimeMillis());parent.put("lastMigrationStage",parent.get("migrationStage"));if(AgentUpdateRegistry.TERMINAL.contains(status))parent.put("finishedAt",at);record(parent);
    }
    private void record(Map<String,Object> parent){db.put("agent_update_requests",parent);db.exec("INSERT INTO agent_update_operations(id,host_id,batch_id,ordinal,status,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,payload=excluded.payload",parent.get("id"),parent.get("hostId"),parent.get("batchId"),parent.get("ordinal"),parent.get("status"),Json.stringify(parent));}
}
