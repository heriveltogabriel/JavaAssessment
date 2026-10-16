package assessment;
import java.io.*;
import java.nio.file.*;
public final class AgentRecoveryServiceTest {
    static void check(boolean b) { if(!b)throw new AssertionError(); }
    static final class Fake extends AgentRecoveryService.Host {
        int reload;String dropins="DropInPaths=";
        void trusted(File p)throws Exception {check(p.exists()&&p.equals(p.getCanonicalFile()));}
        String run(String[] a) {if(a[1].equals("daemon-reload")){reload++;return "";}return dropins;}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("export")){
            Files.writeString(Path.of(args[1]+".conf"),AgentRecoveryService.CONTENT);
            Files.writeString(Path.of(args[1]+".sh"),AgentRecoveryService.PREPARE+"\n");return;
        }
        Path tmp=Files.createTempDirectory("recovery-start-").toRealPath();
        AgentInstallLayout layout=new AgentInstallLayout(tmp.resolve("collector").toFile(),tmp.resolve("update").toFile());layout.initialize();
        Path sys=Files.createDirectory(tmp.resolve("systemd"));Path boot=Files.createDirectory(layout.home.toPath().resolve("bootstrap"));
        Files.writeString(layout.home.toPath().resolve("scheduler"),"systemd\n");
        Files.writeString(boot.resolve("recovery.service"),"owned original");Files.writeString(sys.resolve(AgentRecoveryService.UNIT),"owned original");
        Fake host=new Fake();AgentRecoveryService.install(layout,sys.toFile(),host);
        Path dir=sys.resolve(AgentRecoveryService.UNIT+".d"),file=dir.resolve(AgentRecoveryService.NAME);
        check(Files.readString(file).equals(AgentRecoveryService.CONTENT));check(host.reload==1);
        host.dropins="DropInPaths="+file;AgentRecoveryService.install(layout,sys.toFile(),host);check(host.reload==2);
        Files.writeString(file,"custom");
        try{AgentRecoveryService.install(layout,sys.toFile(),host);throw new AssertionError();}catch(AgentInstallLayout.Failure expected){}
        check(Files.readString(file).equals("custom"));
        Files.writeString(file,AgentRecoveryService.CONTENT);host.dropins="DropInPaths=/custom.conf";
        try{AgentRecoveryService.install(layout,sys.toFile(),host);throw new AssertionError();}catch(AgentInstallLayout.Failure expected){}
        check(host.reload==2);
        Files.writeString(layout.home.toPath().resolve("scheduler"),"sysv\n");AgentRecoveryService.install(layout,sys.toFile(),host);check(host.reload==2);
        Path shell=Path.of("collector/uninstall-local.sh");
        check(Files.readString(shell).contains(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(AgentRecoveryService.CONTENT.getBytes("UTF-8")))));
        System.out.println("Recovery startup ownership, idempotence, customization refusal and SysV checks passed");
    }
}
