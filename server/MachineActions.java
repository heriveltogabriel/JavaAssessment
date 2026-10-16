package assessment;

import java.util.*;
import static assessment.AssessmentServer.map;

/** Dashboard-only machine removal; the caller holds the authoritative write transaction. */
final class MachineActions {
    private final InventoryStore store;
    private final EnrollmentRegistry credentials;
    MachineActions(InventoryStore store,EnrollmentRegistry credentials){this.store=store;this.credentials=credentials;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List<?>?(List<Object>)value:List.of();}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    private static String text(Object value){return value instanceof String?(String)value:"";}
    private static String auditName(String name){String clean=name.replaceAll("[\\x00-\\x1f\\x7f]"," ");return clean.length()>200?clean.substring(0,200):clean;}
    private static void require(boolean condition,int status,String message){if(!condition)throw new EnrollmentRegistry.Failure(status,message);}
    Map<String,Object> delete(Map<String,Object> input,UserAuth.User actor){
        require(actor!=null&&actor.administrator(),403,"Administrator access required");
        require(Set.of("hostId","confirmationName").containsAll(input.keySet()),400,"Unknown machine deletion field");
        String id=text(input.get("hostId")),confirmation=text(input.get("confirmationName"));
        require(!id.isEmpty()&&id.length()<=100&&input.get("confirmationName") instanceof String,400,"Select a machine and confirm its name");
        Map<String,Object> host=store.get("hosts",id);require(host!=null,404,"Host not found");
        String name=text(host.get("hostname"));if(name.isBlank())name=id;
        require(name.equals(confirmation),400,"Type the machine name exactly to confirm deletion");
        require(store.get("identities",id)!=null,409,"Machine identity is missing; administrator recovery is required before deletion");
        require("revoked".equals(credentials.credentialStatus(id))||"uninstalled".equals(host.get("agentLifecycle")),409,"Revoke or uninstall the agent before deleting this machine");
        require(store.scalar("SELECT count(*) FROM uninstall_requests WHERE host_id=? AND status IN ('WAITING','UNINSTALLING','UNCONFIRMED')",id)==0,409,"Resolve the pending or unconfirmed uninstall before deleting this machine");
        require(!AgentUpdateRegistry.busy(store,id)&&store.scalar("SELECT count(*) FROM agent_update_operations WHERE host_id=? AND status NOT IN ('SUCCEEDED','FAILED','ROLLED_BACK','CANCELLED','EXPIRED')",id)==0,409,"Resolve the pending or unconfirmed agent update before deleting this machine");
        return purge(host,actor);
    }
    /** Caller validates eligibility and holds the write transaction. */
    Map<String,Object> purge(Map<String,Object> host,UserAuth.User actor){
        String id=text(host.get("id")),name=text(host.get("hostname"));
        credentials.retainDeletedIdentity(id);
        store.exec("DELETE FROM agent_update_steps WHERE operation_id IN (SELECT id FROM agent_update_operations WHERE host_id=? UNION SELECT id FROM agent_update_requests WHERE host_id=?) OR request_id IN (SELECT id FROM agent_update_requests WHERE host_id=?)",id,id,id);
        for(String table:List.of("inventory","jobs","receipts","schedules","uninstall_requests","agent_update_assessments","agent_update_operations","agent_update_requests"))store.exec("DELETE FROM "+table+" WHERE host_id=?",id);
        for(var row:store.rows("SELECT id,selection,payload FROM agent_update_batches b WHERE EXISTS(SELECT 1 FROM json_each(b.selection) WHERE value=?) OR EXISTS(SELECT 1 FROM json_each(b.payload,'$.skipped') WHERE json_extract(value,'$.hostId')=?)",id,id)){
            List<Object> selection=new ArrayList<>(list(Json.parse(text(row.get("selection")))));selection.removeIf(id::equals);
            Map<String,Object> payload=obj(Json.parse(text(row.get("payload"))));
            List<Object> skipped=new ArrayList<>(list(payload.get("skipped")));skipped.removeIf(v->v instanceof Map<?,?> entry&&id.equals(entry.get("hostId")));payload.put("skipped",skipped);
            if(selection.isEmpty()&&skipped.isEmpty()&&store.scalar("SELECT count(*) FROM agent_update_requests WHERE batch_id=?",row.get("id"))==0)store.exec("DELETE FROM agent_update_batches WHERE id=?",row.get("id"));
            else store.exec("UPDATE agent_update_batches SET selection=?,payload=? WHERE id=?",Json.stringify(selection),Json.stringify(payload),row.get("id"));
        }
        store.delete("hosts",id);
        store.exec("INSERT INTO human_audit(at,actor_id,actor_name,action,target_id,target_name) VALUES(?,?,?,?,?,?)",AssessmentServer.now(),actor.id(),actor.name(),"MACHINE_DELETED",id,auditName(name));
        store.exec("DELETE FROM human_audit WHERE id NOT IN (SELECT id FROM human_audit ORDER BY id DESC LIMIT 10000)");
        return map("deleted",true,"hostId",id);
    }
}
