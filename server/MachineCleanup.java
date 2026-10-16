package assessment;

import java.time.Instant;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Explicit administrative cleanup; API caller holds a SQLite write transaction. */
final class MachineCleanup {
    static final Set<Integer> PERIODS=Set.of(300,86400,604800,2592000);
    private final InventoryStore db;
    private final EnrollmentRegistry credentials;
    MachineCleanup(InventoryStore db,EnrollmentRegistry credentials){this.db=db;this.credentials=credentials;}
    static String text(Object v){return v instanceof String?(String)v:"";}
    static void require(boolean ok,int status,String message){if(!ok)throw new EnrollmentRegistry.Failure(status,message);}
    static int period(Object value){require(value instanceof Number n&&PERIODS.contains(n.intValue())&&n.doubleValue()==n.intValue(),400,"Selecione um período válido de ausência de comunicação.");return ((Number)value).intValue();}
    static String name(Map<String,Object> h){String name=text(h.get("hostname"));return name.isEmpty()?text(h.get("id")):name;}
    static Map<String,Object> target(Map<String,Object> h){return map("hostId",h.get("id"),"name",name(h),"environmentId",text(h.get("environmentId")),"lastSeen",text(h.get("lastSeen")));}
    private String scope(Map<String,Object> input,UserAuth.User actor){
        require(actor!=null&&actor.administrator(),403,"Administrator access required");
        require(input.get("environmentId") instanceof String,400,"Informe o ambiente da seleção.");
        String env=text(input.get("environmentId"));require(env.isEmpty()||env.equals("__unassigned__")||db.get("environments",env)!=null,404,"Ambiente não encontrado.");return env;
    }
    private String reason(Map<String,Object> host,String environment,int seconds,Instant now){
        if(host==null)return "Cadastro já excluído ou indisponível.";
        if(!environment.isEmpty()&&!text(host.get("environmentId")).equals(environment.equals("__unassigned__")?"":environment))return "A máquina não pertence mais ao ambiente selecionado.";
        Instant seen;try{seen=Instant.parse(text(host.get("lastSeen")));}catch(Exception e){return "Último contato indisponível; não é possível confirmar o período.";}
        if(seen.isAfter(now.minusSeconds(seconds)))return "A máquina voltou a comunicar ou não atende mais ao período selecionado.";
        String id=text(host.get("id"));
        if(db.get("identities",id)==null)return "Cadastro sem identidade do agente; é necessária revisão administrativa.";
        if(AgentUpdateRegistry.busy(db,id)||db.scalar("SELECT count(*) FROM agent_update_operations WHERE host_id=? AND status NOT IN ('SUCCEEDED','FAILED','ROLLED_BACK','CANCELLED','EXPIRED')",id)>0)return "Há atualização do agente pendente ou sem confirmação.";
        if(db.scalar("SELECT count(*) FROM uninstall_requests WHERE host_id=? AND status IN ('WAITING','UNINSTALLING','UNCONFIRMED')",id)>0)return "Há desinstalação pendente ou sem confirmação.";
        if(db.scalar("SELECT count(*) FROM jobs WHERE host_id=? AND status='RUNNING' AND lease_expires>?",id,now.toEpochMilli())>0)return "Há uma coleta em execução dentro do prazo.";
        return "";
    }
    Map<String,Object> preview(Map<String,Object> input,UserAuth.User actor){
        String env=scope(input,actor);int seconds=period(input.get("minimumSeconds"));
        require(Set.of("environmentId","minimumSeconds","hostIds").containsAll(input.keySet()),400,"Campo de prévia desconhecido.");
        require(input.get("hostIds") instanceof List<?> ids&&!ids.isEmpty()&&ids.size()<=100,400,"Selecione de 1 a 100 máquinas explicitamente.");
        List<?> ids=(List<?>)input.get("hostIds");Set<String> unique=new HashSet<>();for(Object id:ids)require(id instanceof String&&!text(id).isEmpty()&&text(id).length()<=100&&unique.add(text(id)),400,"Seleção inválida ou duplicada.");
        Instant now=Instant.now();List<Object> items=new ArrayList<>();
        for(Object id:ids){Map<String,Object> h=db.get("hosts",text(id));String reason=reason(h,env,seconds,now);
            Map<String,Object> item=h==null?map("hostId",id,"name",id):target(h);
            item.put("eligible",reason.isEmpty());item.put("reason",reason);
            if(h!=null){item.put("ipAddresses",h.get("ipAddresses"));Map<String,Object> e=db.get("environments",text(h.get("environmentId")));item.put("environmentName",e==null?"Sem ambiente":e.get("name"));}
            items.add(item);
        }
        return map("items",items,"minimumSeconds",seconds,"environmentId",env,"serverTime",now.toString());
    }
    Map<String,Object> execute(Map<String,Object> input,UserAuth.User actor){
        String env=scope(input,actor);int seconds=period(input.get("minimumSeconds"));
        require(Set.of("environmentId","minimumSeconds","targets","confirmation").containsAll(input.keySet()),400,"Campo de exclusão desconhecido.");
        require("EXCLUIR".equals(input.get("confirmation")),400,"Digite EXCLUIR para confirmar.");
        require(input.get("targets") instanceof List<?> targets&&!targets.isEmpty()&&targets.size()<=100,400,"Selecione de 1 a 100 máquinas explicitamente.");
        List<?> targets=(List<?>)input.get("targets");Set<String> ids=new HashSet<>();
        for(Object value:targets){require(value instanceof Map<?,?>,400,"Seleção inválida.");Map<?,?> t=(Map<?,?>)value;
            require(t.keySet().equals(Set.of("hostId","name","environmentId","lastSeen"))&&t.values().stream().allMatch(v->v instanceof String),400,"Seleção incompleta.");
            require(!text(t.get("hostId")).isEmpty()&&text(t.get("hostId")).length()<=100&&ids.add(text(t.get("hostId"))),400,"Seleção inválida ou duplicada.");
        }
        Instant now=Instant.now();List<Object> deleted=new ArrayList<>(),skipped=new ArrayList<>();MachineActions actions=new MachineActions(db,credentials);
        for(Object value:targets){Map<?,?> t=(Map<?,?>)value;String id=text(t.get("hostId"));Map<String,Object> h=db.get("hosts",id);String why=reason(h,env,seconds,now);
            if(why.isEmpty()&&!text(h.get("lastSeen")).equals(t.get("lastSeen")))why="Um novo contato foi recebido depois da prévia; máquina preservada.";
            if(why.isEmpty()&&(!name(h).equals(t.get("name"))||!text(h.get("environmentId")).equals(t.get("environmentId"))))why="Nome ou ambiente mudou depois da prévia; revise a seleção.";
            if(!why.isEmpty()){skipped.add(map("hostId",id,"name",t.get("name"),"reason",why));continue;}
            actions.purge(h,actor);deleted.add(map("hostId",id,"name",t.get("name")));
        }
        return map("deleted",deleted,"skipped",skipped,"serverTime",now.toString());
    }
}
