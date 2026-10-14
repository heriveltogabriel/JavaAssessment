package assessment;

import java.util.*;
import static assessment.AssessmentServer.map;

/** Dashboard-only machine removal; the caller holds the authoritative write transaction. */
final class MachineActions {
    private final InventoryStore store;
    private final EnrollmentRegistry credentials;
    MachineActions(InventoryStore store,EnrollmentRegistry credentials){this.store=store;this.credentials=credentials;}
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
        require(!AgentUpdateRegistry.busy(store,id),409,"Resolve the pending or unconfirmed agent update before deleting this machine");
        credentials.retainDeletedIdentity(id);
        store.exec("DELETE FROM inventory WHERE host_id=?",id);
        store.exec("DELETE FROM jobs WHERE host_id=?",id);
        store.exec("DELETE FROM receipts WHERE host_id=?",id);
        store.exec("DELETE FROM schedules WHERE host_id=?",id);
        store.exec("DELETE FROM uninstall_requests WHERE host_id=?",id);
        // Terminal update history belongs to its batch even after inventory removal.
        for(var request:store.documents("SELECT payload FROM agent_update_requests WHERE host_id=?",id)){
            Map<String,Object> historicalHost=new LinkedHashMap<>(host);if(request.containsKey("environmentId"))historicalHost.put("environmentId",request.get("environmentId"));
            Map<String,Object> snapshot=WorkRequests.snapshot(store,historicalHost,id);
            for(var field:snapshot.entrySet())request.putIfAbsent(field.getKey(),field.getValue());
            store.put("agent_update_requests",request);
        }
        store.delete("hosts",id);
        store.exec("INSERT INTO human_audit(at,actor_id,actor_name,action,target_id,target_name) VALUES(?,?,?,?,?,?)",AssessmentServer.now(),actor.id(),actor.name(),"MACHINE_DELETED",id,auditName(name));
        store.exec("DELETE FROM human_audit WHERE id NOT IN (SELECT id FROM human_audit ORDER BY id DESC LIMIT 10000)");
        return map("deleted",true,"hostId",id);
    }
}
