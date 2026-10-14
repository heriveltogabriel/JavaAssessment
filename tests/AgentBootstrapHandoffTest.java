package assessment;

import java.nio.file.*;
import java.util.*;

/** Reproduces a read-only collector namespace at the process boundary, not a Linux proof. */
public final class AgentBootstrapHandoffTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static final String OP="00000000-0000-0000-0000-000000000003";
    static final class Boundary extends AgentMigrationBootstrap.Production.Commands {
        boolean systemd=true, queued, enqueueFailed, alreadyActive;
        int directWrites, queues, statusQueries;
        List<String> enqueued;
        @Override boolean systemd(AgentInstallLayout layout){return systemd;}
        @Override String executable(String name){return "/usr/bin/"+name;}
        @Override int run(String[] arguments,long timeout){
            List<String> command=Arrays.asList(arguments);
            if(arguments[0].equals("/usr/bin/systemd-run")){
                queues++;enqueued=command;
                check(timeout==60000,"enqueue is bounded, never waits for recovery activation");
                if(enqueueFailed)return 1;
                check(!command.contains("--scope"),"service must not inherit collector namespace");
                queued=true;return 0;
            }
            if(arguments[0].equals("/usr/bin/systemctl")){
                statusQueries++;
                check(command.equals(List.of("/usr/bin/systemctl","is-active","--quiet","java-assessment-bootstrap-"+OP+".service")),"only the same operation's service may satisfy replay");
                return alreadyActive?0:3;
            }
            if(systemd){directWrites++;return 1;} // /etc is read-only in this collector.
            return 0; // SysV keeps the original protected synchronous entry.
        }
    }
    public static void main(String[] args)throws Exception{
        Path temporary=Files.createTempDirectory("bootstrap-handoff-").toRealPath();
        try{
            AgentInstallLayout layout=new AgentInstallLayout(temporary.resolve("collector").toFile(),temporary.resolve("update").toFile());
            Path script=temporary.resolve("update/transactions/"+OP+"/prepared-0/bootstrap/update-bootstrap.sh");Files.createDirectories(script.getParent());Files.writeString(script,"signed staging fixture\n");
            Map transaction=AssessmentScan.map("operationId",OP,"preparedDirectory","prepared-0");
            Boundary boundary=new Boundary();new AgentMigrationBootstrap.Production(layout,boundary).local("install-bootstrap",transaction);
            check(boundary.queued&&boundary.queues==1&&boundary.directWrites==0,"protected collector delegates the only /etc write to PID 1");
            check(boundary.enqueued.equals(List.of("/usr/bin/systemd-run","--unit=java-assessment-bootstrap-"+OP+".service","--uid=root","--gid=root","--service-type=simple","/bin/sh",script.toString(),"install-bootstrap",OP)),"fixed signed staging and root service contract");
            for(String incompatible:List.of("--scope","--wait","--collect","--no-block","--property=UMask=0077","--property=NoNewPrivileges=true"))check(!boundary.enqueued.contains(incompatible),"systemd219 needs no unsupported option "+incompatible);
            boundary=new Boundary();boundary.enqueueFailed=true;boundary.alreadyActive=true;
            new AgentMigrationBootstrap.Production(layout,boundary).local("install-bootstrap",transaction);
            check(boundary.queues==1&&boundary.statusQueries==1&&boundary.directWrites==0,"replayed active bootstrap remains independent");
            boundary=new Boundary();boundary.enqueueFailed=true;
            try{new AgentMigrationBootstrap.Production(layout,boundary).local("install-bootstrap",transaction);throw new AssertionError("failed service accepted");}
            catch(AgentInstallLayout.Failure expected){check(expected.diagnostic.equals("SERVICE_FAILED"),"failed enqueue is explicit, no unsafe direct fallback");}
            check(boundary.directWrites==0,"failure does not retry /etc writes inside collector");
            boundary=new Boundary();boundary.systemd=false;
            new AgentMigrationBootstrap.Production(layout,boundary).local("install-bootstrap",transaction);
            check(boundary.queues==0&&boundary.statusQueries==0,"SysV path remains synchronous without systemd dependency");
            boundary=new Boundary();
            try{new AgentMigrationBootstrap.Production(layout,boundary).local("install-bootstrap",AssessmentScan.map("operationId",OP+";bad","preparedDirectory","prepared-0"));throw new AssertionError("unsafe operation accepted");}
            catch(Exception expected){check(boundary.queues==0,"operation cannot inject unit names or staging paths");}
            check(Files.readString(Path.of("collector/root-system-install.sh")).contains("ProtectSystem=full"),"collector protection is retained");
            System.out.println("AgentBootstrapHandoffTest PASS "+checks+" checks; read-only namespace boundary model, independent PID1 service, systemd219 options, replay/failure/SysV. Native Linux proof remains pending.");
        }finally{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
