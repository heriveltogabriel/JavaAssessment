package assessment;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Files/journal are real; services and the protected namespace are explicit adapters. */
public final class AgentBootstrapHandoffTest {
    static int checks;
    static final String OP="00000000-0000-0000-0000-000000000003";
    static final String HELPER=AgentMigrationBootstrap.Production.LEGACY_SERVICE;
    static final String RECOVERY=AgentMigrationBootstrap.Production.RECOVERY_SERVICE;
    static final String UNIT="java-assessment-bootstrap-"+OP+".service";
    static final String FLAG=AgentMigrationBootstrap.Production.LEGACY_WAS_ACTIVE;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    interface Attempt { void run() throws Exception; }
    static void reject(Attempt call,String why)throws Exception{
        try{call.run();throw new AssertionError("Accepted: "+why);}catch(AgentInstallLayout.Failure expected){check(true,why);}
    }
    static AgentMigrationBootstrap.Production.ServiceState state(String load,String active,long main,long control,long job){
        return new AgentMigrationBootstrap.Production.ServiceState(load,active,active.equals("active")?"running":"dead",main,control,job);
    }
    static final class Fixture implements AutoCloseable {
        final Path temporary,script;
        final AgentInstallLayout layout;
        final Map transaction;
        Fixture()throws Exception{
            temporary=Files.createTempDirectory("bootstrap-handoff-").toRealPath();
            layout=new AgentInstallLayout(temporary.resolve("collector").toFile(),temporary.resolve("update").toFile());layout.initialize();
            Files.createDirectories(layout.root.toPath().resolve("conf"));Files.createDirectories(layout.root.toPath().resolve("collector-spool"));
            Files.writeString(layout.root.toPath().resolve("collector.jar"),"known signed bridge fixture\n");
            Files.writeString(layout.root.toPath().resolve("update-public.der"),"public pin fixture\n");
            Path ca=layout.root.toPath().resolve("conf/ca.pem");Files.writeString(ca,"public CA fixture\n");
            Files.writeString(layout.root.toPath().resolve("conf/java.path"),"/protected/runtime/bin/java\n");
            Files.writeString(layout.root.toPath().resolve("conf/collector.properties"),"hostId=host-fixture\ntlsCaFile="+ca+"\n");
            script=layout.home.toPath().resolve("transactions/"+OP+"/prepared-0/bootstrap/update-bootstrap.sh");
            Files.createDirectories(script.getParent());Files.writeString(script,"signed staging fixture\n");
            transaction=AssessmentScan.map("schema",1L,"sequence",1L,"operationId",OP,"phase","PREPARED","preparedDirectory","prepared-0",
                "hostId","host-fixture","previousVersion","1.5.12","targetVersion","2.0.11","previousPointer",null,
                "configSha256",hash("conf/collector.properties"),"javaPathSha256",hash("conf/java.path"),
                "pinSha256",hash("update-public.der"),"caSha256",AgentBundleManifest.hash(ca.toFile()),"bridgeSha256",hash("collector.jar"));
            layout.write(layout.journal(),transaction);
        }
        String hash(String name)throws Exception{return AgentBundleManifest.hash(layout.root.toPath().resolve(name).toFile());}
        void writeJournal()throws Exception{layout.write(layout.journal(),transaction);}
        public void close()throws Exception{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    static final class Boundary extends AgentMigrationBootstrap.Production.Commands {
        final Fixture fixture;
        boolean systemd=true,stopLeavesRunning,stopLeavesFailed,startLeavesInactive,unknownState;
        int queueResult,stopResult,startResult,directWrites,queues,starts,stops;
        IOException queueException;
        String markerMetadata="0:600:1:8180";
        Attempt afterQueue,afterStop;
        AgentMigrationBootstrap.Production.ServiceState helper=state("loaded","active",200,0,0),bootstrap=state("not-found","inactive",0,0,0),recovery=state("not-found","inactive",0,0,0);
        final List<String> events=new ArrayList<>();List<String> enqueued;
        Boundary(Fixture fixture){this.fixture=fixture;}
        @Override boolean systemd(AgentInstallLayout layout){return systemd;}
        @Override String executable(String name){return "/usr/bin/"+name;}
        @Override String fileMetadata(File marker){check(marker.equals(fixture.layout.home.toPath().resolve("stop").toFile()),"only the fixed operational stop marker is inspected");events.add("marker-stat");return markerMetadata;}
        @Override AgentMigrationBootstrap.Production.ServiceState status(String unit)throws Exception{
            events.add("status:"+unit);if(unknownState)throw new AgentInstallLayout.Failure("SERVICE_STATE_UNKNOWN");
            if(unit.equals(HELPER))return helper;if(unit.equals(RECOVERY))return recovery;
            check(unit.equals(UNIT),"state reads are restricted to this operation and owned helper/recovery");return bootstrap;
        }
        @Override int run(String[] arguments,long timeout)throws Exception{
            List<String> command=Arrays.asList(arguments);
            if(arguments[0].equals("/bin/sync")){events.add("sync");return 0;}
            if(command.equals(List.of("/bin/sh",fixture.layout.root.toPath().resolve("root-trust.sh").toString(),"private",fixture.layout.home.toPath().resolve("stop").toString()))){events.add("marker-trust");return 0;}
            if(arguments[0].equals("/usr/bin/systemd-run")){
                events.add("queue");queues++;enqueued=command;
                check(timeout==60000,"enqueue is bounded");check(helper.stopped(),"helper must be fully stopped before enqueue");
                if(afterQueue!=null)afterQueue.run();if(queueException!=null)throw queueException;return queueResult;
            }
            if(arguments[0].equals("/usr/bin/systemctl")){
                check(command.size()==3&&command.get(2).equals(HELPER),"only the owned legacy helper may be stopped/started");
                if(command.get(1).equals("stop")){
                    events.add("stop");stops++;check(timeout==60000,"stop is bounded");
                    check(AgentInstallLayout.read(fixture.layout.journal()).get(FLAG) instanceof Boolean,"original service state is durable before stop");
                    if(!stopLeavesRunning)helper=state("loaded",stopLeavesFailed?"failed":"inactive",0,0,0);
                    Path marker=fixture.layout.home.toPath().resolve("stop");Files.writeString(marker,"");AgentTool.ownerOnly(marker.toFile());
                    if(afterStop!=null)afterStop.run();return stopResult;
                }
                check(command.get(1).equals("start")&&timeout==30000,"no early disable/restart/reset or unrelated service action");
                check(!Files.exists(fixture.layout.home.toPath().resolve("stop")),"original loop can start only after its protected stop marker is cleared");
                events.add("start");starts++;if(!startLeavesInactive)helper=state("loaded","active",201,0,0);return startResult;
            }
            events.add("direct");directWrites++;return systemd?1:0;
        }
        void call()throws Exception{new AgentMigrationBootstrap.Production(fixture.layout,this).local("install-bootstrap",fixture.transaction);}
    }
    static void parserChecks()throws Exception{
        String valid="LoadState=loaded\nActiveState=inactive\nSubState=dead\nMainPID=0\nControlPID=0\nJob=0\n";
        check(AgentMigrationBootstrap.Production.ServiceState.parse(valid,0).stopped(),"systemd219 show --all Job=0 format");
        check(AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("Job=0", "Job="),0).stopped(),"explicit empty Job= is a confirmed absent job");
        check(AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("loaded","not-found").replace("Job=0","Job="),4).stopped(),"RC4 absent unit also accepts explicit empty Job=");
        reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("Job=0","Job= "),0),"whitespace job is unknown, not explicit empty");
        reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("MainPID=0","MainPID="),0),"empty MainPID is never treated as zero");
        reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("ControlPID=0","ControlPID="),0),"empty ControlPID is never treated as zero");
        check(!AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("Job=0","Job=12"),0).stopped(),"pending job is not stopped");
        check(!AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("ControlPID=0","ControlPID=12"),0).stopped(),"ExecStop process is not stopped");
        check(AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("loaded","not-found"),1).stopped(),"explicit absent unit may be confirmed with all zero counters");
        check(AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("loaded","not-found"),4).stopped(),"systemd239 absent-unit RC4 is accepted only with complete inactive/zero state");
        reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(valid,4),"RC4 cannot disguise a failed read of loaded unit");
        reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("loaded","not-found").replace("Job=0","Job=12"),4),"absent-unit RC4 with pending job is unknown");
        reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(valid.replace("loaded","not-found").replace("inactive","failed"),4),"absent-unit RC4 must explicitly be inactive");
        for(String bad:List.of(valid.replace("Job=0\n",""),valid+"Job=0\n",valid.replace("MainPID=0","MainPID=bad"),valid+"Unexpected=field\n", "x".repeat(8193)))
            reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(bad,0),"missing/duplicate/malformed/extra/oversized state refused");
        reject(()->AgentMigrationBootstrap.Production.ServiceState.parse(valid,30),"timeout is unknown, not inactive");
        try(var fixture=new Fixture()){
            Path executable=fixture.temporary.resolve("fake-systemctl");
            String assertArgs="test \"$1\" = show && test \"$2\" = --all && test \"$3\" = --no-pager || exit 3\n";
            Files.writeString(executable,"#!/bin/sh\n"+assertArgs+"printf '%s' '"+valid+"'\n");check(executable.toFile().setExecutable(true),"fake read executable");
            AgentMigrationBootstrap.Production.Commands reads=new AgentMigrationBootstrap.Production.Commands(){@Override String executable(String name){return executable.toString();}};
            check(reads.status(HELPER).stopped(),"bounded reader parses actual fake CLI bytes with supported219 flags");
            reject(()->reads.status("unrelated.service"),"unrelated service read refused");
            Files.writeString(executable,"#!/bin/sh\nprintf '%s' '"+"x".repeat(9000)+"'\n");reject(()->reads.status(HELPER),"reader stops excessive output");
        }
    }
    public static void main(String[] args)throws Exception{
        parserChecks();
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.call();
            check(boundary.queues==1&&boundary.directWrites==0,"protected collector delegates /etc writes to PID1");
            check(boundary.events.indexOf("sync")<boundary.events.indexOf("stop")&&boundary.events.indexOf("stop")<boundary.events.indexOf("queue"),"durable intent -> stop -> confirmed inactive -> queue");
            check(Boolean.TRUE.equals(fixture.transaction.get(FLAG))&&AgentInstallLayout.number(fixture.transaction.get("sequence"))==2,"caller map and durable journal share the original active state");
            check(AgentInstallLayout.read(fixture.layout.journal()).equals(fixture.transaction),"caller cannot overwrite handoff metadata with a stale journal map");
            check(boundary.enqueued.equals(List.of("/usr/bin/systemd-run","--unit="+UNIT,"--uid=root","--gid=root","--service-type=simple","/bin/sh",fixture.script.toString(),"install-bootstrap",OP)),"fixed signed staging/root service command");
            for(String unsupported:List.of("--scope","--wait","--collect","--no-block","--value","--property=Conflicts=", "--property=After="))check(boundary.enqueued.stream().noneMatch(value->value.startsWith(unsupported)),"no unsupported219 flag or outer-lock execution");
            Boundary replay=new Boundary(fixture);replay.helper=state("loaded","inactive",0,0,0);replay.queueResult=1;replay.bootstrap=state("loaded","active",300,0,0);replay.call();
            check(Boolean.TRUE.equals(fixture.transaction.get(FLAG))&&AgentInstallLayout.number(fixture.transaction.get("sequence"))==2,"replay never recaptures stopped state over original true");
            check(!replay.events.contains("sync")&&replay.starts==0,"active independent bootstrap replay needs no second state snapshot or restore");
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.queueResult=1;reject(boundary::call,"known failed enqueue is explicit");
            check(boundary.starts==1&&boundary.helper.running(),"safe known failed enqueue restores previously active helper");
            check(boundary.events.indexOf("queue")<boundary.events.indexOf("status:"+RECOVERY)&&boundary.events.indexOf("status:"+RECOVERY)<boundary.events.indexOf("start"),"restore waits for definitive bootstrap/recovery/helper state");
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.helper=state("loaded","inactive",0,0,0);boundary.queueResult=1;
            reject(boundary::call,"failed enqueue from initially inactive helper");check(boundary.starts==0&&Boolean.FALSE.equals(fixture.transaction.get(FLAG)),"previously inactive helper is never started");
        }
        for(int result:List.of(30,31))try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.queueResult=result;reject(boundary::call,"unknown/timeout queue result");check(boundary.starts==0,"timeout cannot restore while PID1 may be starting bootstrap");
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.queueException=new IOException("unknown enqueue result");
            try{boundary.call();throw new AssertionError("unknown queue accepted");}catch(IOException expected){check(boundary.starts==0,"enqueue exception never restores helper");}
        }
        for(String unsafe:List.of("bootstrap-job","bootstrap-control","recovery-active","recovery-job","boot-present","changed-identity","changed-phase","legacy-request"))try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.queueResult=1;
            if(unsafe.equals("bootstrap-job"))boundary.bootstrap=state("loaded","activating",0,0,12);
            if(unsafe.equals("bootstrap-control"))boundary.bootstrap=state("loaded","failed",0,321,0);
            if(unsafe.equals("recovery-active"))boundary.recovery=state("loaded","active",400,0,0);
            if(unsafe.equals("recovery-job"))boundary.recovery=state("loaded","inactive",0,0,12);
            if(unsafe.equals("boot-present"))boundary.afterQueue=()->Files.createDirectory(fixture.layout.home.toPath().resolve("bootstrap"));
            if(unsafe.equals("changed-identity"))boundary.afterQueue=()->Files.writeString(fixture.layout.root.toPath().resolve("conf/java.path"),"changed\n");
            if(unsafe.equals("changed-phase"))boundary.afterQueue=()->{Map next=AgentInstallLayout.read(fixture.layout.journal());next.put("phase","REMOTE_CONFIRMING");fixture.layout.write(fixture.layout.journal(),next);};
            if(unsafe.equals("legacy-request"))boundary.afterQueue=()->Files.writeString(fixture.layout.root.toPath().resolve("collector-spool/update-request.json"),"{}\n");
            reject(boundary::call,"failed queue with "+unsafe);check(boundary.starts==0,"unsafe restore refused: "+unsafe);
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.stopLeavesRunning=true;reject(boundary::call,"stop not confirmed");check(boundary.queues==0&&boundary.starts==0,"no queue or automatic restore after unresolved stop");
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.stopLeavesFailed=true;boundary.call();
            check(boundary.queues==1&&boundary.starts==0,"OL7 failed/143 quiescent helper permits handoff without restarting it");
        }
        for(String pending:List.of("main","control","job","deactivating","missing"))try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.afterStop=()->boundary.helper=state(
                pending.equals("missing")?"not-found":"loaded",pending.equals("deactivating")?"deactivating":"failed",
                pending.equals("main")?200:0,pending.equals("control")?201:0,pending.equals("job")?202:0);
            reject(boundary::call,"failed stop with unresolved "+pending);
            check(boundary.queues==0&&boundary.starts==0,"never hand off or restart with unresolved "+pending);
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.stopResult=30;reject(boundary::call,"stop timeout is not evidence that helper stopped");check(boundary.queues==0&&boundary.starts==0,"stop timeout cannot queue or trigger speculative restoration");
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.afterStop=()->{throw new IOException("unknown stop result");};
            try{boundary.call();throw new AssertionError("unknown stop accepted");}catch(IOException expected){check(boundary.queues==0&&boundary.starts==0,"stop exception cannot queue or restore helper");}
        }
        for(boolean failedStart:List.of(false,true))try(var fixture=new Fixture()){
            fixture.transaction.put(FLAG,Boolean.TRUE);fixture.writeJournal();
            Boundary boundary=new Boundary(fixture);boundary.helper=state("loaded","inactive",0,0,0);
            boundary.startResult=failedStart?1:0;boundary.startLeavesInactive=!failedStart;
            boolean restored=new AgentMigrationBootstrap.Production(fixture.layout,boundary).restoreLegacyAfterQueueFailure(fixture.transaction,boundary.bootstrap);
            check(!restored&&boundary.starts==1,"nonzero start or unconfirmed active state never claims restoration");
            if(!failedStart)check(boundary.events.get(boundary.events.size()-1).equals("status:"+HELPER),"successful start command still requires read-back service evidence");
        }
        for(String metadata:List.of("1000:600:1:8180","0:660:1:81b0","0:600:2:8180","0:600:1:a180","0:600:1:8000"))try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.queueResult=1;boundary.markerMetadata=metadata;reject(boundary::call,"enqueue failure with untrusted stop marker");
            check(boundary.starts==0&&Files.exists(fixture.layout.home.toPath().resolve("stop")),"unsafe owner/mode/hardlink/type marker is preserved and helper is not started");
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.queueResult=1;Path target=fixture.temporary.resolve("unrelated");Files.writeString(target,"preserve\n");
            boundary.afterQueue=()->{Path marker=fixture.layout.home.toPath().resolve("stop");Files.delete(marker);Files.createSymbolicLink(marker,target);};
            reject(boundary::call,"stop marker symlink is refused before unlink");check(boundary.starts==0&&Files.readString(target).equals("preserve\n"),"symlink target remains untouched");
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.queueResult=1;
            boundary.afterQueue=()->Files.writeString(fixture.layout.home.toPath().resolve("stop"),"unexpected content\n");
            reject(boundary::call,"nonempty stop marker is preserved");
            check(boundary.starts==0&&Files.readString(fixture.layout.home.toPath().resolve("stop")).equals("unexpected content\n"),"only the original empty operational marker can be cleared");
        }
        for(String unsafe:List.of("identity","legacy-journal","legacy-request","phase","unknown-service","invalid-record"))try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);
            if(unsafe.equals("identity"))Files.writeString(fixture.layout.root.toPath().resolve("conf/java.path"),"changed\n");
            if(unsafe.equals("legacy-journal"))Files.writeString(fixture.layout.home.toPath().resolve("journal.json"),"{}\n");
            if(unsafe.equals("legacy-request"))Files.writeString(fixture.layout.root.toPath().resolve("collector-spool/update-request.json"),"{}\n");
            if(unsafe.equals("phase")){fixture.transaction.put("phase","ACTIVATING");fixture.writeJournal();}
            if(unsafe.equals("unknown-service"))boundary.unknownState=true;
            if(unsafe.equals("invalid-record")){fixture.transaction.put(FLAG,"true");fixture.writeJournal();}
            reject(boundary::call,"pre-stop guard "+unsafe);check(boundary.stops==0&&boundary.queues==0,"guard fails before service mutations: "+unsafe);
        }
        try(var fixture=new Fixture()){
            Boundary boundary=new Boundary(fixture);boundary.systemd=false;boundary.call();check(boundary.directWrites==1&&boundary.queues==0&&boundary.stops==0,"SysV entry remains synchronous and unchanged");
        }
        check(Files.readString(Path.of("collector/root-system-install.sh")).contains("ProtectSystem=full"),"collector protection is retained");
        System.out.println("AgentBootstrapHandoffTest PASS "+checks+" checks; durable original state, stop/confirm/queue, safe restore, replay, bounded typed status, SysV; native Linux proof remains pending.");
    }
}
