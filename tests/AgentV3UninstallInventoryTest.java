package assessment;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Signed slots and real temporary files; service operations are explicit adapters. */
public final class AgentV3UninstallInventoryTest {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    static void put(File file,String value)throws Exception{file.getParentFile().mkdirs();Files.writeString(file.toPath(),value);}
    static void copy(File from,File to)throws Exception{try(var paths=Files.walk(from.toPath())){for(Path p:paths.toList()){check(!Files.isSymbolicLink(p),"no links in fixture copy");Path target=to.toPath().resolve(from.toPath().relativize(p));if(Files.isDirectory(p))Files.createDirectories(target);else Files.copy(p,target,StandardCopyOption.REPLACE_EXISTING);}}}
    static void delete(File directory)throws Exception{if(!directory.exists())return;try(var paths=Files.walk(directory.toPath())){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    interface Checked { void run()throws Exception; }
    static void refused(Checked operation,String label)throws Exception{try{operation.run();throw new AssertionError("accepted "+label);}catch(AssertionError bad){throw bad;}catch(Exception expected){checks++;}}
    static final String OP="00000000-0000-0000-0000-000000000099";
    static final class Fixture implements AgentV3UninstallInventory.Services,AgentBundleInstaller.Platform,AutoCloseable {
        final File base,helper,systemd,init,prepared;final AgentInstallLayout layout;final AgentV3UninstallInventory tool;final Map meta,manifest;
        final String mode;int stops,starts,installs;String outside;
        Fixture(File source,String mode)throws Exception {
            this.mode=mode;meta=AgentInstallLayout.read(new File(source,"fixture.json"));base=Files.createTempDirectory("v3-uninstall-").toRealPath().toFile();
            layout=new AgentInstallLayout(new File(base,"collector"),new File(base,"updater"));copy(asset(source,"root"),layout.root);copy(asset(source,"home"),layout.home);layout.initialize();
            helper=new File(base,"uninstall");systemd=new File(base,"systemd");init=new File(base,"init");systemd.mkdir();init.mkdir();helper.mkdir();
            put(new File(layout.home,"scheduler"),mode+"\n");put(new File(helper,"scheduler"),mode+"\n");
            for(String name:new String[]{"collector.jar","root-trust.sh","lifecycle-lock.sh","uninstall-service.sh","uninstall-local.sh"}) {
                File from=name.equals("collector.jar")?new File(source,"home/helper.jar"):new File(layout.root,name);AgentInstallLayout.copy(from,new File(helper,name));
            }
            copy(new File(layout.root,"lib"),new File(helper,"lib"));put(new File(helper,"java.path"),"/private-java/bin/java\n");put(new File(helper,"owned-files"),"collector.jar\n");
            put(unit(),"owned original uninstall service\n");AgentInstallLayout.copy(unit(),new File(helper,mode.equals("systemd")?"helper.service":"helper.init"));
            put(new File(layout.root,"conf/uninstall-capability"),"uninstall-v1\n");put(new File(layout.root,"conf/collector.properties"),"hostId=fixture-host\ntlsCaFile="+new File(base,"outside/ca.pem")+"\n");
            put(new File(base,"outside/ca.pem"),"EXTERNAL CA MUST SURVIVE\n");put(new File(base,"outside/java/bin/java"),"JVM MUST SURVIVE\n");put(new File(base,"outside/customer.jar"),"APPLICATION MUST SURVIVE\n");outside=AgentBundleManifest.hash(new File(base,"outside/java/bin/java"));
            AgentInstallLayout.durable(layout.journal(),AssessmentScan.map("schema",new Long(1),"sequence",new Long(1),"operationId",OP,"hostId","fixture-host","targetVersion",meta.get("targetVersion"),"previousVersion",meta.get("fromVersion"),"phase","PREPARED"));
            AgentMigrationBootstrap.captureOrigin(layout,this);check(layout.journal().delete(),"origin capture journal adapter completed");
            File raw=asset(source,"manifest"),sig=asset(source,"signature"),pin=asset(source,"pin"),bundle=asset(source,"bundle");
            manifest=AgentBundleManifest.verify(raw,sig,pin,bundle,AgentInstallLayout.text(meta.get("targetVersion")));prepared=new File(base,"prepared");AgentBundleManifest.extractVerified(manifest,bundle,prepared);
            File code=new File(layout.releases,"test-code"),manager=new File(layout.managers,"test-manager");slot(new File(prepared,"code"),code,"code",raw,sig);slot(new File(prepared,"manager"),manager,"manager",raw,sig);
            File boot=new File(layout.home,"bootstrap");copy(new File(prepared,"bootstrap"),boot);AgentInstallLayout.copy(raw,new File(boot,"bundle-manifest.json"));AgentInstallLayout.copy(sig,new File(boot,"bundle-manifest.sig"));
            AgentInstallLayout.durable(new File(boot,"bootstrap-owned.json"),AssessmentScan.map("schema",new Long(1),"files",AgentV3UninstallInventory.tree(boot)));
            put(new File(boot,mode.equals("systemd")?"recovery.service":"recovery.init"),"owned recovery service\n");
            Map pointer=AssessmentScan.map("schema",new Long(1),"releaseId","test-code","managerId","test-manager","version",meta.get("targetVersion"),"bundleSha256",manifest.get("sha256"),"managerSha256",meta.get("managerSha256"));layout.promote(pointer);
            tool=new AgentV3UninstallInventory(layout,helper,systemd,init,this);
        }
        File asset(File source,String name){return new File(source,AgentInstallLayout.text(meta.get(name)));}
        File unit(){return new File(mode.equals("systemd")?systemd:init,mode.equals("systemd")?"java-assessment-uninstall.service":"java-assessment-uninstall");}
        void slot(File source,File target,String role,File raw,File sig)throws Exception {
            copy(source,target);AgentInstallLayout.copy(raw,new File(target,"bundle-manifest.json"));AgentInstallLayout.copy(sig,new File(target,"bundle-manifest.sig"));
            AgentInstallLayout.durable(new File(target,"installed.json"),AssessmentScan.map("schema",new Long(1),"role",role,"version",meta.get("targetVersion"),"bundleSha256",manifest.get("sha256"),"manifestSha256",AgentBundleManifest.hash(raw),"files",AgentV3UninstallInventory.tree(target)));
        }
        public void stop(String mode){stops++;}
        public void start(String mode){starts++;}
        public void local(String action,Map journal){}
        public int probe(File code,String version,boolean heartbeat){return 0;}
        public boolean serviceEvidence(Map pointer){return true;}
        public boolean processUses(File directory){return false;}
        public void install(String mode,File code)throws Exception {
            installs++;stop(mode);
            tool.installHelperCode();put(unit(),"owned current uninstall service\n");AgentInstallLayout.copy(unit(),new File(helper,mode.equals("systemd")?"helper.service":"helper.init"));start(mode);
        }
        void cleanSnapshot()throws Exception {Map owned=AgentV3UninstallInventory.inventoryForCleanup(layout,OP);check(!owned.isEmpty(),"snapshot publishes exact cleanup inventory");for(Object name:owned.keySet())check(new File(layout.transaction(OP),name.toString()).delete(),"owned snapshot cleanup");AgentInstallLayout.removeEmpty(layout.transaction(OP));}
        void active()throws Exception{tool.snapshot(OP);tool.activate(OP);check(new File(helper,"v3-inventory.json").isFile(),"current helper sealed");check(installs==1,"independent helper replaced once");cleanSnapshot();}
        void external()throws Exception{check(outside.equals(AgentBundleManifest.hash(new File(base,"outside/java/bin/java"))),"private JVM preserved");check(new File(base,"outside/ca.pem").isFile()&&new File(base,"outside/customer.jar").isFile(),"external CA/application preserved");}
        public void close()throws Exception{delete(base);}
    }
    public static void main(String[] args)throws Exception {
        File source=new File(args.length==0?"tmp/full-update/http-fixture-v2":args[0]).getCanonicalFile();
        for(String mode:new String[]{"systemd","sysv"}){
            try(Fixture f=new Fixture(source,mode)){
                String previous=AgentBundleManifest.hash(new File(f.helper,"collector.jar")),unit=AgentBundleManifest.hash(f.unit());
                f.tool.snapshot(OP);f.tool.activate(OP);f.tool.restore(OP);check(previous.equals(AgentBundleManifest.hash(new File(f.helper,"collector.jar"))),"rollback restores previous helper");check(unit.equals(AgentBundleManifest.hash(f.unit())),"rollback restores previous unit");check(f.starts>0&&f.stops>0,"service boundary adapter exercised");f.external();
            }
            try(Fixture f=new Fixture(source,mode)){
                String original=AgentBundleManifest.hash(new File(f.helper,"collector.jar"));
                f.tool.snapshot(OP);Map snapshotBefore=AgentV3UninstallInventory.tree(new File(f.layout.transaction(OP),"uninstall-before"));f.tool.activate(OP);
                Map origin=AgentInstallLayout.read(new File(f.layout.state,"origin/origin.json"));
                for(Object name:((Map)origin.get("files")).keySet())AgentInstallLayout.copy(new File(f.layout.state,"origin/files/"+name),new File(f.layout.root,name.toString()));
                check(new File(f.layout.root,"current.json").delete(),"initial rollback removes active pointer");
                File managerPointer=new File(f.layout.state,"current-manager.json");if(managerPointer.exists())check(managerPointer.delete(),"initial rollback clears manager pointer");
                AgentInstallLayout.durable(f.layout.journal(),AssessmentScan.map("releaseId","test-code"));
                f.tool.restore(OP);Map owner=AgentInstallLayout.read(new File(f.helper,"v3-inventory.json"));
                check(Boolean.TRUE.equals(owner.get("legacyAfterRollback")),"legacy rollback explicitly records independent uninstall shim");
                check(original.equals(AgentBundleManifest.hash(new File(f.layout.root,"collector.jar"))),"legacy collector restored exactly");
                check(AgentBundleManifest.hash(new File(f.layout.home,"bootstrap/anchor.jar")).equals(AgentBundleManifest.hash(new File(f.helper,"collector.jar"))),"shim executes immutable anchor, not old helper");
                check(snapshotBefore.equals(AgentV3UninstallInventory.tree(new File(f.layout.transaction(OP),"uninstall-before"))),"original uninstall snapshot remains unchanged");
                check(f.layout.journal().delete(),"finished recovery journal cleared");f.cleanSnapshot();
                // The 7-day cleanup can delete failed slots without removing
                // the shim's independently retained signature evidence.
                delete(f.layout.releases);delete(f.layout.managers);f.tool.checkHelperOwned();f.tool.checkRemoval();f.tool.remove();f.tool.checkHelperCleanup();f.external();
            }
            try(Fixture f=new Fixture(source,mode)){
                f.active();
                Map localOrigin=AgentInstallLayout.read(new File(f.layout.state,"origin/origin.json"));
                localOrigin.put("localInstall",Boolean.TRUE);((Map)localOrigin.get("files")).remove("update-public.der");
                Files.deleteIfExists(new File(f.layout.state,"origin/files/update-public.der").toPath());
                AgentInstallLayout.durable(new File(f.layout.state,"origin/origin.json"),localOrigin);
                put(new File(f.layout.root,"conf/installation-id.lock"),"");
                put(new File(f.layout.root,"logs/permission-check.json"),"{\"readablePaths\":2,\"inaccessiblePaths\":0}\n");
                f.tool.checkRemoval();f.tool.remove();check(!f.layout.root.exists()&&!f.layout.home.exists(),"signed code/update trees and installer diagnostic/identity lock removed");check(f.helper.isDirectory(),"independent completion helper survives removal");f.tool.remove();f.tool.checkHelperCleanup();f.external();
                put(new File(f.helper,"unexpected.jar"),"preserve unknown helper data");refused(()->f.tool.checkHelperCleanup(),"unexpected helper cleanup file");
            }
        }
        try(Fixture f=new Fixture(source,"systemd")){
            put(new File(f.helper,"lib/customer.jar"),"customer file is not an obsolete runtime library");
            refused(()->f.tool.snapshot(OP),"unknown legacy helper library");check(new File(f.helper,"lib/customer.jar").isFile(),"unknown helper library preserved");
        }
        try(Fixture f=new Fixture(source,"systemd")){
            f.active();put(new File(f.helper,"lib/customer.jar"),"unknown executable library");refused(()->f.tool.checkHelperOwned(),"unknown V3 helper library");
        }
        for(String kind:new String[]{"owned-partial","unknown-partial","changed-partial","untrusted-source"}){
            try(Fixture f=new Fixture(source,"systemd")){
                f.tool.snapshot(OP);File origin=new File(f.layout.releases,"test-code/collector.jar"),partial=new File(f.helper,"collector.jar.copy");
                Files.write(partial.toPath(),Arrays.copyOf(Files.readAllBytes(origin.toPath()),128));
                if(kind.equals("changed-partial"))put(partial,"changed after interrupted copy");
                if(!kind.equals("unknown-partial")){
                    File declared=kind.equals("untrusted-source")?new File(f.base,"outside/customer.jar"):origin;
                    AgentInstallLayout.durable(new File(f.layout.transaction(OP),"uninstall-write.json"),AssessmentScan.map("schema",new Long(1),"operationId",OP,"relative","collector.jar","source",declared.getPath(),"sha256",AgentBundleManifest.hash(declared)));
                }
                if(kind.equals("owned-partial")){f.tool.restore(OP);check(!partial.exists()&&!new File(f.layout.transaction(OP),"uninstall-write.json").exists(),"durable intent recovers interrupted atomic helper copy");}
                else{refused(()->f.tool.restore(OP),kind);check(partial.isFile(),"unproven partial copy preserved "+kind);}
                f.external();
            }
        }
        try(Fixture f=new Fixture(source,"systemd")){
            f.active();f.tool.snapshot(OP);Map files=AgentV3UninstallInventory.inventoryForCleanup(f.layout,OP);
            put(new File(f.layout.transaction(OP),"prepared-0/code/collector.jar"),"completed initial extraction fixture");
            files.put("prepared-0/code/collector.jar",AgentBundleManifest.hash(new File(f.layout.transaction(OP),"prepared-0/code/collector.jar")));
            // Initial immutable bootstrap did not include the separately sealed owner.
            AgentInstallLayout.copy(new File(f.helper,"v3-inventory.json"),new File(f.layout.transaction(OP),"uninstall-owner.json"));
            files.remove("uninstall-owner.json");
            Map queued=AssessmentScan.map("schema",new Long(1),"operationId",OP,"failed",Boolean.FALSE,"attempts",new Long(0),"notBefore",new Long(System.currentTimeMillis()+86400000L),"files",files,"releaseId","test-code","managerId","test-manager");
            AgentInstallLayout.durable(new File(f.layout.state,"cleanup/"+OP+".json"),queued);
            put(new File(f.layout.state,"cleanup.next"),Long.toString(System.currentTimeMillis()/1000L+86400L)+"\n");
            AgentInstallLayout.durable(new File(f.layout.state,"retained.json"),AssessmentScan.map("schema",new Long(1),"current",f.layout.current(),"previous",null));
            f.tool.checkRemoval();
            put(new File(f.layout.transaction(OP),"uninstall-owner.json"),"modified owner must survive");
            refused(()->f.tool.checkRemoval(),"unqueued owner must exactly equal verified helper seal");
            AgentInstallLayout.copy(new File(f.helper,"v3-inventory.json"),new File(f.layout.transaction(OP),"uninstall-owner.json"));
            f.tool.checkRemoval();put(new File(f.layout.transaction(OP),"customer.jar"),"not in exact cleanup queue");refused(()->f.tool.checkRemoval(),"unknown queued transaction member");
            check(new File(f.layout.transaction(OP),"customer.jar").delete(),"remove test-owned unknown file");f.tool.remove();f.tool.checkHelperCleanup();f.external();
        }
        for(String kind:new String[]{"root-file","empty-dir","slot-file","slot-change","bootstrap-change","updater-file","active-update","pending-request","helper-code","identity","snapshot-drift"}){
            try(Fixture f=new Fixture(source,"systemd")){
                f.active();String rootHash=AgentBundleManifest.hash(new File(f.layout.root,"collector.jar"));
                if(kind.equals("root-file"))put(new File(f.layout.root,"customer.jar"),"preserve unknown");
                if(kind.equals("empty-dir"))new File(f.layout.root,"unknown-empty-directory").mkdir();
                if(kind.equals("slot-file"))put(new File(f.layout.releases,"test-code/customer.jar"),"preserve unknown");
                if(kind.equals("slot-change"))put(new File(f.layout.releases,"test-code/collector.jar"),"changed signed code");
                if(kind.equals("bootstrap-change"))put(new File(f.layout.home,"bootstrap/anchor.jar"),"changed anchor");
                if(kind.equals("updater-file"))put(new File(f.layout.home,"customer.jar"),"preserve unknown");
                if(kind.equals("active-update"))put(f.layout.journal(),"{}");
                if(kind.equals("pending-request"))put(f.layout.request(),"{}");
                if(kind.equals("helper-code"))put(new File(f.helper,"collector.jar"),"changed helper");
                if(kind.equals("identity"))put(new File(f.helper,"identity.properties"),"hostId=another-host\n");
                if(kind.equals("snapshot-drift")){f.tool.snapshot(OP);put(new File(f.layout.transaction(OP),"uninstall-before/files/collector.jar"),"changed snapshot");refused(()->f.tool.restore(OP),"changed recovery snapshot");}
                else refused(()->f.tool.checkRemoval(),kind);
                check(rootHash.equals(AgentBundleManifest.hash(new File(f.layout.root,"collector.jar"))),"failed preflight preserves root "+kind);f.external();
            }
        }
        try(Fixture f=new Fixture(source,"systemd")){
            f.active();Map plan=f.tool.removalInventory();AgentInstallLayout.durable(new File(f.helper,"v3-removal.json"),plan);
            check(new File(f.layout.root,"update-public.der").delete(),"simulate crash after first deletions");f.tool.checkRemoval();f.tool.remove();check(!f.layout.root.exists()&&!f.layout.home.exists(),"durable ownership resumes without deleted pin/collector");f.external();
        }
        System.out.println("AgentV3UninstallInventoryTest PASS "+checks+" checks; signed ownership/snapshot/restore/resume; OS service actions simulated");
    }
}
