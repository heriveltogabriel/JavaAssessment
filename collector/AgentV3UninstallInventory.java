package assessment;

import java.io.*;
import java.util.*;

/** Fixed-root uninstall ownership, migration snapshots and resumable deletion. Never deletes a JVM. */
public final class AgentV3UninstallInventory {
    static final File UNINSTALL = new File("/opt/java-assessment-uninstall");
    static final String[] ROOT_STATIC = {"install.sh","system-install.sh","root-system-install.sh","root-migrate-input.sh","root-service.sh","root-trust.sh","root-java-discovery.sh","launcher-common.sh","bootstrap-config.sh","run-once.sh","agent-tool.sh","poll.sh","collector.jar","uninstall-install.sh","uninstall-service.sh","uninstall-local.sh","lifecycle-lock.sh","update-install.sh","update-local.sh","update-service.sh","update-bootstrap.sh","update-public.der","README.md","INSTALL.md","DEPENDENCIES.json","SHA256SUMS","BOUNCY-CASTLE-LICENSE.txt","conf/collector.properties.example"};
    static final String[] HELPER_STATIC = {"collector.jar","root-trust.sh","lifecycle-lock.sh","uninstall-service.sh","uninstall-local.sh","java.path","scheduler","owned-files","collector.service","collector.timer","collector.init","helper.service","helper.init","v3-inventory.json","v3-source-manifest.json","v3-source-manifest.sig","v3-bootstrap-manifest.json","v3-bootstrap-manifest.sig","v3-public.der"};
    final AgentInstallLayout layout;
    final File helper, systemd, init;
    final Services services;
    String activeOperation;
    interface Services { void stop(String mode) throws Exception; void start(String mode) throws Exception; void install(String mode,File code) throws Exception; }
    AgentV3UninstallInventory(AgentInstallLayout layout,File helper,File systemd,File init,Services services) throws Exception {
        this.layout=layout;this.helper=helper.getCanonicalFile();this.systemd=systemd.getCanonicalFile();this.init=init.getCanonicalFile();this.services=services;
        need(this.helper.equals(helper.getAbsoluteFile()));
    }
    static void need(boolean value) throws Exception { AgentInstallLayout.require(value,"UNINSTALL_INVENTORY_REQUIRED"); }
    static String text(Object value) { return AgentInstallLayout.text(value); }
    static boolean known(String name,String[] values) { return Arrays.asList(values).contains(name); }
    static File at(File base,String name) throws Exception { return AgentInstallLayout.child(base,name); }
    static Map tree(File base) throws Exception { Map out=new LinkedHashMap();if(base.exists())walk(base,base,out);return out; }
    static void walk(File base,File folder,Map files) throws Exception {
        need(folder.isDirectory()&&folder.getCanonicalFile().equals(folder.getAbsoluteFile()));File[] children=folder.listFiles();need(children!=null);
        for(int i=0;i<children.length;i++){File f=children[i];need(f.getCanonicalFile().equals(f.getAbsoluteFile()));if(f.isDirectory())walk(base,f,files);else{need(f.isFile()&&files.size()<4096);files.put(f.getPath().substring(base.getPath().length()+1),AgentBundleManifest.hash(f));}}
    }
    static void directories(File base,File folder,Map files,String empty) throws Exception {
        File[] children=folder.listFiles();need(children!=null);
        for(File f:children)if(f.isDirectory()){
            String name=f.getPath().substring(base.getPath().length()+1);boolean allowed=("|"+empty+"|").indexOf("|"+name+"|")>=0;
            for(Object item:files.keySet())if(text(item).startsWith(name+"/"))allowed=true;
            need(allowed);directories(base,f,files,empty);
        }
    }
    String mode() throws Exception { File source=at(helper,"scheduler");if(!source.isFile())source=at(layout.home,"scheduler");String m=new String(AgentInstallLayout.bytes(source,32),"US-ASCII").trim();need(m.equals("systemd")||m.equals("sysv"));return m; }
    File unit(String mode) throws Exception { return at(mode.equals("systemd")?systemd:init,mode.equals("systemd")?"java-assessment-uninstall.service":"java-assessment-uninstall"); }
    void idle() throws Exception {
        for(File f:new File[]{at(helper,"request.json"),at(helper,"claim.json"),at(layout.root,"collector-spool/uninstall-request.json")})need(!f.exists());
    }
    static boolean helperStatic(String name) { return known(name,HELPER_STATIC)||name.matches("lib/[A-Za-z0-9_.-]+[.]jar"); }
    static boolean helperCode(String name) { return name.equals("collector.jar")||name.matches("(root-trust|lifecycle-lock|uninstall-service|uninstall-local)[.]sh|lib/[A-Za-z0-9_.-]+[.]jar"); }
    Map signed(File manifest,File signature,File pin) throws Exception {
        Map raw=AgentInstallLayout.read(manifest);
        return AgentBundleManifest.verifyHeader(manifest,signature,pin,text(raw.get("version")));
    }
    Map helperSource(Map owner) throws Exception {
        File pin=at(helper,"v3-public.der");
        if(at(layout.root,"update-public.der").isFile())need(AgentBundleManifest.hash(pin).equals(AgentBundleManifest.hash(at(layout.root,"update-public.der"))));
        Map source=signed(at(helper,"v3-source-manifest.json"),at(helper,"v3-source-manifest.sig"),pin),inventory=(Map)source.get("files");
        Map bootstrap=null;
        if(Boolean.TRUE.equals(owner.get("legacyAfterRollback")))bootstrap=(Map)signed(at(helper,"v3-bootstrap-manifest.json"),at(helper,"v3-bootstrap-manifest.sig"),pin).get("files");
        Map expected=new LinkedHashMap();
        for(Object item:tree(helper).keySet()){
            String name=text(item);if(!helperCode(name))continue;
            String origin="code/"+name;Map reference=inventory;
            if(bootstrap!=null&&(name.equals("collector.jar")||name.startsWith("lib/"))){reference=bootstrap;origin=name.equals("collector.jar")?"bootstrap/anchor.jar":"bootstrap/"+name;}
            Object descriptor=reference.get(origin);need(descriptor instanceof Map);String hash=text(((Map)descriptor).get("sha256"));
            need(hash.equals(AgentBundleManifest.hash(at(helper,name))));expected.put(name,hash);
        }
        need(expected.containsKey("collector.jar"));
        for(String name:new String[]{"root-trust.sh","lifecycle-lock.sh","uninstall-service.sh","uninstall-local.sh"})need(expected.containsKey(name));
        // Every signed runtime library is required, including obsolete names that
        // cannot silently disappear while a previous helper is still running.
        Map libraries=bootstrap==null?inventory:bootstrap;String prefix=bootstrap==null?"code/lib/":"bootstrap/lib/";
        for(Object item:libraries.keySet())if(text(item).startsWith(prefix))need(expected.containsKey("lib/"+text(item).substring(prefix.length())));
        return expected;
    }
    void checkHelperOwned() throws Exception {
        File marker=at(helper,"v3-inventory.json");
        if(marker.isFile()){
            Map owner=AgentInstallLayout.read(marker);need(AgentInstallLayout.number(owner.get("schema"))==1&&"AGENT_V3_UNINSTALL_V1".equals(owner.get("type")));
            need(owner.get("rootBase") instanceof Map);for(Object item:((Map)owner.get("rootBase")).keySet())need((known(text(item),ROOT_STATIC)||text(item).matches("lib/[A-Za-z0-9_.-]+[.]jar"))&&AgentInstallLayout.digest(text(((Map)owner.get("rootBase")).get(item))));
            need(helperSource(owner).equals(owner.get("helperCode")));return;
        }
        File originFile=at(layout.state,"origin/origin.json");need(originFile.isFile());Map origin=AgentInstallLayout.read(originFile),owned=(Map)origin.get("files");need(owned!=null);
        for(Object item:tree(helper).keySet()){
            String name=text(item);if(!helperCode(name))continue;String hash=AgentBundleManifest.hash(at(helper,name));
            need(hash.equals(owned.get(name))||(name.equals("collector.jar")&&hash.equals(origin.get("helperSha256"))));
        }
    }
    Map staticHelper() throws Exception {
        Map files=tree(helper),selected=new LinkedHashMap();
        for(Object item:files.keySet()){String name=text(item);if(helperStatic(name))selected.put(name,files.get(item));
            else need(name.matches("(diagnostic[.](tmp|txt)|supervisor[.]pid|worker[.]lock|stop)"));}
        return selected;
    }
    Map snapshot(String operation) throws Exception {
        need(AgentInstallLayout.uuid(operation));idle();File dir=at(layout.transaction(operation),"uninstall-before"),record=at(dir,"snapshot.json");
        if(record.isFile()){Map prior=AgentInstallLayout.read(record);validateSnapshot(dir,prior);return prior;}
        if(helper.isDirectory())checkHelperOwned();Map files=staticHelper();String mode=mode();File unit=unit(mode);boolean present=helper.isDirectory();
        if(unit.isFile()){File expected=at(helper,mode.equals("systemd")?"helper.service":"helper.init");need(expected.isFile()&&AgentBundleManifest.hash(expected).equals(AgentBundleManifest.hash(unit)));}
        AgentInstallLayout.directory(dir);
        for(Object item:files.keySet())AgentInstallLayout.copy(at(helper,text(item)),at(dir,"files/"+text(item)));
        Map state=AssessmentScan.map("schema",new Long(1),"operationId",operation,"present",Boolean.valueOf(present),"mode",mode,"files",files,"unitPresent",Boolean.valueOf(unit.isFile()));
        if(unit.isFile()){AgentInstallLayout.copy(unit,at(dir,"unit"));state.put("unitSha256",AgentBundleManifest.hash(unit));}
        File capability=at(layout.root,"conf/uninstall-capability");state.put("capabilityPresent",Boolean.valueOf(capability.isFile()));
        if(capability.isFile()){AgentInstallLayout.copy(capability,at(dir,"capability"));state.put("capabilitySha256",AgentBundleManifest.hash(capability));}
        AgentInstallLayout.durable(record,state);validateSnapshot(dir,state);return state;
    }
    static void validateSnapshot(File directory,Map state) throws Exception {
        need(AgentInstallLayout.number(state.get("schema"))==1&&state.get("files") instanceof Map);Map expected=new LinkedHashMap();
        for(Object item:((Map)state.get("files")).keySet()){String name=text(item);need(helperStatic(name));expected.put("files/"+name,((Map)state.get("files")).get(item));}
        if(Boolean.TRUE.equals(state.get("unitPresent")))expected.put("unit",state.get("unitSha256"));
        if(Boolean.TRUE.equals(state.get("capabilityPresent")))expected.put("capability",state.get("capabilitySha256"));
        Map actual=tree(directory);actual.remove("snapshot.json");need(actual.equals(expected));
    }
    static Map inventoryForCleanup(AgentInstallLayout layout,String operation) throws Exception {
        File dir=at(layout.transaction(operation),"uninstall-before");Map result=new LinkedHashMap();if(!dir.exists())return result;
        Map state=AgentInstallLayout.read(at(dir,"snapshot.json"));validateSnapshot(dir,state);Map files=tree(dir);
        for(Object name:files.keySet())result.put("uninstall-before/"+name,files.get(name));
        for(String name:new String[]{"uninstall-owner.json","uninstall-write.json"}){File f=at(layout.transaction(operation),name);if(f.isFile())result.put(name,AgentBundleManifest.hash(f));}
        return result;
    }
    void activate(String operation) throws Exception {
        need(AgentInstallLayout.uuid(operation));activeOperation=operation;idle();Map current=layout.current();need(current!=null);verifySlot(layout.code(current),"code");verifySlot(layout.manager(current),"manager");
        snapshot(operation);services.install(mode(),layout.code(current));seal();
    }
    void restore(String operation) throws Exception {
        need(AgentInstallLayout.uuid(operation));activeOperation=operation;File dir=at(layout.transaction(operation),"uninstall-before"),record=at(dir,"snapshot.json");if(!record.isFile())return;
        Map state=AgentInstallLayout.read(record);validateSnapshot(dir,state);String m=text(state.get("mode"));
        recoverCopy(operation);
        // With no previous V3 pointer the original collector is restored, but
        // the immutable recovery service remains until diagnostics expire.
        // Keep a wire-compatible uninstall helper that understands those trees.
        boolean legacy=layout.current()==null;File shimSource=null;
        if(legacy){
            verifyOriginRestored();verifyBootstrap();Map transaction=AgentInstallLayout.read(layout.journal());String id=text(transaction.get("releaseId"));need(AgentInstallLayout.identifier(id));
            shimSource=at(layout.releases,id);verifySlot(shimSource,"code");
        }
        validateRestoreCode(state);services.stop(m);
        Map existing=staticHelper();for(Object item:existing.keySet())need(at(helper,text(item)).delete());
        if(Boolean.TRUE.equals(state.get("present"))){AgentInstallLayout.directory(helper);Map files=(Map)state.get("files");for(Object item:files.keySet())copyHelper(at(dir,"files/"+item),text(item));}
        File target=unit(m);if(Boolean.TRUE.equals(state.get("unitPresent"))){AgentInstallLayout.copy(at(dir,"unit"),target);target.setReadable(true,false);if(m.equals("sysv"))target.setExecutable(true,false);}else need(!target.exists()||target.delete());
        File capability=at(layout.root,"conf/uninstall-capability");if(Boolean.TRUE.equals(state.get("capabilityPresent")))AgentInstallLayout.copy(at(dir,"capability"),capability);else need(!capability.exists()||capability.delete());
        if(legacy&&Boolean.TRUE.equals(state.get("present")))installRollbackShim(shimSource);
        File stop=at(helper,"stop");need(!stop.exists()||stop.delete());
        if(Boolean.TRUE.equals(state.get("present")))services.start(m);
    }
    void verifyOriginRestored() throws Exception {
        Map origin=AgentInstallLayout.read(at(layout.state,"origin/origin.json"));need(origin.get("files") instanceof Map);
        for(Object item:((Map)origin.get("files")).keySet()){
            String name=text(item),hash=text(((Map)origin.get("files")).get(item));
            need(hash.equals(AgentBundleManifest.hash(at(layout.state,"origin/files/"+name)))&&hash.equals(AgentBundleManifest.hash(at(layout.root,name))));
        }
        need(origin.get("version").equals(AgentUpdateWorker.jarVersion(at(layout.root,"collector.jar"))));
    }
    void validateRestoreCode(Map state) throws Exception {
        Map before=(Map)state.get("files"),actual=staticHelper();List sources=new ArrayList();
        if(layout.releases.isDirectory())for(File slot:layout.releases.listFiles()){verifySlot(slot,"code");sources.add(slot);}
        Map boot=null;if(at(layout.home,"bootstrap/anchor.jar").isFile()){verifyBootstrap();boot=(Map)signed(at(layout.home,"bootstrap/bundle-manifest.json"),at(layout.home,"bootstrap/bundle-manifest.sig"),at(layout.root,"update-public.der")).get("files");}
        for(Object item:actual.keySet()){
            String name=text(item);if(!helperCode(name)||actual.get(item).equals(before.get(item)))continue;boolean found=false;
            for(Object source:sources){File candidate=at((File)source,name);if(candidate.isFile()&&actual.get(item).equals(AgentBundleManifest.hash(candidate)))found=true;}
            if(boot!=null){Object d=boot.get(name.equals("collector.jar")?"bootstrap/anchor.jar":"bootstrap/"+name);if(d instanceof Map&&actual.get(item).equals(((Map)d).get("sha256")))found=true;}
            need(found);
        }
    }
    void installRollbackShim(File source) throws Exception {
        File boot=at(layout.home,"bootstrap");
        // Only proven runtime libraries are removed; no unknown customer file
        // is swept up when the anchor and collector use different libraries.
        checkHelperOwned();for(Object item:tree(helper).keySet())if(text(item).startsWith("lib/"))need(at(helper,text(item)).delete());
        copyHelper(at(boot,"anchor.jar"),"collector.jar");
        for(Object item:tree(at(boot,"lib")).keySet())copyHelper(at(boot,"lib/"+item),"lib/"+item);
        for(String name:new String[]{"root-trust.sh","lifecycle-lock.sh","uninstall-service.sh","uninstall-local.sh"})copyHelper(at(source,name),name);
        sealFrom(source,true);
    }
    String operation() throws Exception {
        if(AgentInstallLayout.uuid(activeOperation))return activeOperation;
        Map journal=AgentInstallLayout.read(layout.journal());String op=text(journal.get("operationId"));need(AgentInstallLayout.uuid(op));return op;
    }
    boolean trustedCopySource(File source,String relative,String op) throws Exception {
        if(source.equals(at(layout.root,"update-public.der")))return relative.equals("v3-public.der");
        File snapshot=at(layout.transaction(op),"uninstall-before");File record=at(snapshot,"snapshot.json");
        if(record.isFile()){
            Map state=AgentInstallLayout.read(record);validateSnapshot(snapshot,state);
            if(source.equals(at(snapshot,"files/"+relative)))return AgentBundleManifest.hash(source).equals(((Map)state.get("files")).get(relative));
        }
        File owner=at(layout.transaction(op),"uninstall-owner.json");
        if(source.equals(owner)&&relative.equals("v3-inventory.json")){
            Map value=AgentInstallLayout.read(owner);need("AGENT_V3_UNINSTALL_V1".equals(value.get("type"))&&helperSource(value).equals(value.get("helperCode")));return true;
        }
        if(layout.releases.isDirectory())for(File slot:layout.releases.listFiles()){
            String name=relative.equals("v3-source-manifest.json")?"bundle-manifest.json":relative.equals("v3-source-manifest.sig")?"bundle-manifest.sig":relative;
            if(source.equals(at(slot,name))){verifySlot(slot,"code");return source.isFile();}
        }
        File boot=at(layout.home,"bootstrap");
        String name=relative.equals("collector.jar")?"anchor.jar":relative.equals("v3-bootstrap-manifest.json")?"bundle-manifest.json":relative.equals("v3-bootstrap-manifest.sig")?"bundle-manifest.sig":relative;
        if(source.equals(at(boot,name))){verifyBootstrap();return source.isFile();}
        return false;
    }
    void copyHelper(File source,String relative) throws Exception {
        String op=operation();need(helperStatic(relative)&&trustedCopySource(source,relative,op));
        File intent=at(layout.transaction(op),"uninstall-write.json");need(!intent.exists());File target=at(helper,relative),temporary=new File(target.getPath()+".copy");need(!temporary.exists());
        AgentInstallLayout.durable(intent,AssessmentScan.map("schema",new Long(1),"operationId",op,"relative",relative,"source",source.getPath(),"sha256",AgentBundleManifest.hash(source)));
        AgentInstallLayout.copy(source,target);need(AgentBundleManifest.hash(source).equals(AgentBundleManifest.hash(target)));need(intent.delete());
    }
    void recoverCopy(String op) throws Exception {
        File intent=at(layout.transaction(op),"uninstall-write.json");if(!intent.isFile())return;Map state=AgentInstallLayout.read(intent);
        String relative=text(state.get("relative"));File source=new File(text(state.get("source"))).getAbsoluteFile();
        need(AgentInstallLayout.number(state.get("schema"))==1&&op.equals(state.get("operationId"))&&helperStatic(relative)
            &&source.equals(source.getCanonicalFile())&&trustedCopySource(source,relative,op)&&state.get("sha256").equals(AgentBundleManifest.hash(source)));
        File temporary=new File(at(helper,relative).getPath()+".copy");
        if(temporary.exists()){
            need(temporary.isFile()&&temporary.equals(temporary.getCanonicalFile())&&temporary.length()<=source.length());
            InputStream a=new FileInputStream(source),b=new FileInputStream(temporary);try{int value;while((value=b.read())!=-1)need(value==a.read());}finally{try{a.close();}finally{b.close();}}
            need(temporary.delete());
        }
        need(intent.delete());
    }
    void installHelperCode() throws Exception {
        Map current=layout.current();need(current!=null);File source=layout.code(current);verifySlot(source,"code");checkHelperOwned();
        for(Object item:tree(helper).keySet())if(text(item).startsWith("lib/")&&!at(source,text(item)).isFile())need(at(helper,text(item)).delete());
        for(String name:new String[]{"collector.jar","root-trust.sh","lifecycle-lock.sh","uninstall-service.sh","uninstall-local.sh"})copyHelper(at(source,name),name);
        for(Object item:tree(at(source,"lib")).keySet())copyHelper(at(source,"lib/"+item),"lib/"+item);
    }
    void seal() throws Exception {
        Map current=layout.current();need(current!=null);verifySlot(layout.code(current),"code");verifySlot(layout.manager(current),"manager");
        need(AgentBundleManifest.hash(at(helper,"collector.jar")).equals(AgentBundleManifest.hash(at(layout.code(current),"collector.jar"))));
        sealFrom(layout.code(current),false);
    }
    void sealFrom(File source,boolean legacy) throws Exception {
        verifySlot(source,"code");
        copyHelper(at(source,"bundle-manifest.json"),"v3-source-manifest.json");copyHelper(at(source,"bundle-manifest.sig"),"v3-source-manifest.sig");
        copyHelper(at(layout.root,"update-public.der"),"v3-public.der");
        if(legacy){copyHelper(at(layout.home,"bootstrap/bundle-manifest.json"),"v3-bootstrap-manifest.json");copyHelper(at(layout.home,"bootstrap/bundle-manifest.sig"),"v3-bootstrap-manifest.sig");}
        else for(String name:new String[]{"v3-bootstrap-manifest.json","v3-bootstrap-manifest.sig"})need(!at(helper,name).exists()||at(helper,name).delete());
        Map rootBase=new LinkedHashMap();for(String name:ROOT_STATIC){File f=at(layout.root,name);if(f.isFile())rootBase.put(name,AgentBundleManifest.hash(f));}
        File libs=at(layout.root,"lib");if(libs.isDirectory())for(Object item:tree(libs).keySet()){String name=text(item);need(name.matches("[A-Za-z0-9_.-]+[.]jar"));rootBase.put("lib/"+name,AgentBundleManifest.hash(at(libs,name)));}
        Map owner=AssessmentScan.map("schema",new Long(1),"type","AGENT_V3_UNINSTALL_V1","rootBase",rootBase,"legacyAfterRollback",Boolean.valueOf(legacy));
        owner.put("helperCode",helperSource(owner));File stored=at(layout.transaction(operation()),"uninstall-owner.json");AgentInstallLayout.durable(stored,owner);copyHelper(stored,"v3-inventory.json");
    }
    Map verifySlot(File slot,String role) throws Exception {
        Map record=AgentInstallLayout.read(at(slot,"installed.json"));Map header=AgentBundleManifest.verifyHeader(at(slot,"bundle-manifest.json"),at(slot,"bundle-manifest.sig"),at(layout.root,"update-public.der"),text(record.get("version")));
        Map expected=new LinkedHashMap(),inventory=(Map)header.get("files");
        for(Object item:inventory.keySet()){String name=text(item);if(name.startsWith(role+"/"))expected.put(name.substring(role.length()+1),((Map)inventory.get(item)).get("sha256"));}
        expected.put("bundle-manifest.json",AgentBundleManifest.hash(at(slot,"bundle-manifest.json")));expected.put("bundle-manifest.sig",AgentBundleManifest.hash(at(slot,"bundle-manifest.sig")));
        Map actual=tree(slot);actual.remove("installed.json");need(actual.equals(expected)&&expected.equals(record.get("files")));return expected;
    }
    void verifySlots(File parent,String role) throws Exception {if(!parent.exists())return;File[] slots=parent.listFiles();need(slots!=null);for(File slot:slots){need(slot.isDirectory()&&AgentInstallLayout.identifier(slot.getName()));verifySlot(slot,role);}}
    void verifyBootstrap() throws Exception {
        File boot=at(layout.home,"bootstrap");Map seal=AgentInstallLayout.read(at(boot,"bootstrap-owned.json"));
        File raw=at(boot,"bundle-manifest.json");Map unverified=AgentInstallLayout.read(raw);Map header=AgentBundleManifest.verifyHeader(raw,at(boot,"bundle-manifest.sig"),at(layout.root,"update-public.der"),text(unverified.get("version")));
        Map expected=new LinkedHashMap();for(Object item:((Map)header.get("files")).keySet()){String name=text(item);if(name.startsWith("bootstrap/"))expected.put(name.substring(10),((Map)((Map)header.get("files")).get(item)).get("sha256"));}
        expected.put("bundle-manifest.json",AgentBundleManifest.hash(raw));expected.put("bundle-manifest.sig",AgentBundleManifest.hash(at(boot,"bundle-manifest.sig")));
        Map actual=tree(boot);actual.remove("bootstrap-owned.json");actual.remove("recovery.service");actual.remove("recovery.init");need(actual.equals(expected)&&expected.equals(seal.get("files")));
    }
    static boolean rootData(String name) {
        return name.matches("conf/(collector[.]properties|java[.]path|installation-id|root-service-mode|uninstall-capability|update-capability|ca[.]pem)")||name.equals("trust/ca.pem")||name.equals("current.json")
            ||name.matches("logs/(collector[.]log([.][123])?|service[.]log([.][123])?|install[.]log|first-cycle[.]tmp|install-events-[A-Za-z0-9_.-]+[.]json([.]sent)?)")
            ||name.matches("collector-spool/(pending-[A-Za-z0-9_.-]+[.]json|latest-snapshot[.]json|last-ack[.]json|[.]collector[.]lock|uninstall-request[.]json|[.]poll-lock/pid)")
            ||name.matches("run/(service[.]pid|service[.]stop)");
    }
    Map removalInventory() throws Exception {
        need(!layout.journal().exists()&&!layout.request().exists()&&!at(layout.home,"journal.json").exists()&&!at(layout.root,"collector-spool/update-request.json").exists());
        Map ownership=AgentInstallLayout.read(at(helper,"v3-inventory.json"));need(AgentInstallLayout.number(ownership.get("schema"))==1&&"AGENT_V3_UNINSTALL_V1".equals(ownership.get("type")));
        checkHelperOwned();
        if(layout.current()==null){need(Boolean.TRUE.equals(ownership.get("legacyAfterRollback")));verifyOriginRestored();}
        verifySlots(layout.releases,"code");verifySlots(layout.managers,"manager");verifyBootstrap();
        File identity=at(helper,"identity.properties"),configuration=at(layout.root,"conf/collector.properties");
        if(identity.isFile()&&configuration.isFile())need(AssessmentCollector.required(AgentTool.load(identity),"hostId").equals(AssessmentCollector.required(AgentTool.load(configuration),"hostId")));
        Map root=tree(layout.root);for(Object item:root.keySet()){String name=text(item);if(name.startsWith("releases/"))continue;if(rootData(name))continue;
            need(((Map)ownership.get("rootBase")).containsKey(name)&&root.get(item).equals(((Map)ownership.get("rootBase")).get(item)));}
        Map update=tree(layout.home),transactions=queuedTransactions();Map origin=null;File originRecord=at(layout.state,"origin/origin.json");if(originRecord.isFile())origin=AgentInstallLayout.read(originRecord);
        for(Object item:update.keySet()){String name=text(item);if(name.startsWith("managers/")||name.startsWith("bootstrap/"))continue;
            if(name.startsWith("transactions/")){need(update.get(item).equals(transactions.get(name)));continue;}
            if(name.startsWith("state/origin/")){need(origin!=null);if(name.equals("state/origin/origin.json"))continue;
                if(name.equals("state/origin/helper.jar"))need(update.get(item).equals(origin.get("helperSha256")));
                else{need(name.startsWith("state/origin/files/"));need(update.get(item).equals(((Map)origin.get("files")).get(name.substring(19))));}continue;}
            if(name.matches("state/cleanup/[a-f0-9-]{36}[.]json")){need(transactions.containsKey(name));continue;}
            if(name.matches("state/cleanup[.]next([.]tmp)?")){String value=new String(AgentInstallLayout.bytes(at(layout.home,name),64),"US-ASCII").trim();need(value.matches("[0-9]{1,16}"));continue;}
            if(name.equals("state/retained.json")){Map retained=AgentInstallLayout.read(at(layout.home,name));need(AgentInstallLayout.number(retained.get("schema"))==1);for(String key:new String[]{"current","previous"})if(retained.get(key)!=null){need(retained.get(key) instanceof Map);layout.validatePointer((Map)retained.get(key));}continue;}
            if(name.matches("state/(current-manager[.]json|last-good[.]json|recovery[.](pid|stop)|diagnostic[.](tmp|txt))|history/[a-f0-9-]{36}[.]json|scheduler|java[.]path|supervisor[.]pid|stop|diagnostic[.](tmp|txt)|collector[.](service|timer|init)|helper[.](service|init)"))continue;
            if(name.equals("helper.jar")){need(origin!=null&&update.get(item).equals(origin.get("helperSha256")));continue;}
            if(name.matches("(root-trust|lifecycle-lock|update-local|update-service)[.]sh|update-public[.]der|lib/[A-Za-z0-9_.-]+[.]jar")){need(origin!=null&&update.get(item).equals(((Map)origin.get("files")).get(name)));continue;}
            throw new IOException("Unexpected updater file; uninstall refused");
        }
        directories(layout.root,layout.root,root,"conf|trust|logs|run|collector-spool|collector-spool/.poll-lock|backups|lib|releases");
        directories(layout.home,layout.home,update,"state|state/cleanup|transactions|managers|history|lib");
        return AssessmentScan.map("schema",new Long(1),"type","AGENT_V3_REMOVAL_V1","rootFiles",root,"updateFiles",update);
    }
    Map queuedTransactions() throws Exception {
        Map result=new LinkedHashMap();File queue=at(layout.state,"cleanup");if(!queue.exists())return result;File[] records=queue.listFiles();need(records!=null);
        for(File file:records){
            need(file.isFile()&&file.getName().matches("[a-f0-9-]{36}[.]json"));Map record=AgentInstallLayout.read(file);String op=text(record.get("operationId"));
            need(record.keySet().equals(new HashSet(Arrays.asList(new String[]{"schema","operationId","failed","attempts","notBefore","files","releaseId","managerId"})))
                &&AgentInstallLayout.number(record.get("schema"))==1&&record.get("failed") instanceof Boolean&&AgentInstallLayout.uuid(op)&&file.getName().equals(op+".json")&&record.get("files") instanceof Map
                &&AgentInstallLayout.number(record.get("notBefore"))>=0&&AgentInstallLayout.number(record.get("attempts"))>=0);
            for(String key:new String[]{"releaseId","managerId"})need(record.get(key)==null||AgentInstallLayout.identifier(text(record.get(key))));
            Map expected=(Map)record.get("files");for(Object item:expected.keySet()){
                String relative=text(item);need(AgentInstallLayout.digest(text(expected.get(item)))&&(
                    relative.matches("manifest[.](json|sig)|bundle[.]zip|uninstall-(owner|write)[.]json|previous-services/collector[.](service|timer|init)|prepared-[1-8]/[A-Za-z0-9_./-]+")
                    ||relative.equals("uninstall-before/snapshot.json")||relative.equals("uninstall-before/unit")||relative.equals("uninstall-before/capability")
                    ||relative.startsWith("uninstall-before/files/")&&helperStatic(relative.substring(23))));File owned=at(layout.transaction(op),relative);
                if(owned.exists())need(owned.isFile()&&expected.get(item).equals(AgentBundleManifest.hash(owned)));
                result.put("transactions/"+op+"/"+relative,expected.get(item));
            }
            result.put("state/cleanup/"+file.getName(),AgentBundleManifest.hash(file));
        }
        return result;
    }
    void checkRemoval() throws Exception {File plan=at(helper,"v3-removal.json");if(plan.isFile())remaining(AgentInstallLayout.read(plan));else removalInventory();}
    void remaining(Map plan) throws Exception {
        need(AgentInstallLayout.number(plan.get("schema"))==1&&"AGENT_V3_REMOVAL_V1".equals(plan.get("type")));
        for(int i=0;i<2;i++){File base=i==0?layout.root:layout.home;Map expected=(Map)plan.get(i==0?"rootFiles":"updateFiles"),actual=tree(base);need(expected!=null);for(Object item:actual.keySet())need(actual.get(item).equals(expected.get(item)));
            if(base.exists())directories(base,base,expected,i==0?"conf|trust|logs|run|collector-spool|collector-spool/.poll-lock|backups|lib|releases":"state|state/cleanup|transactions|managers|history|lib");}
    }
    void remove() throws Exception {
        File planFile=at(helper,"v3-removal.json");Map plan;if(planFile.isFile())plan=AgentInstallLayout.read(planFile);else{plan=removalInventory();need(Json.stringify(plan).getBytes("UTF-8").length<=65536);AgentInstallLayout.durable(planFile,plan);}remaining(plan);
        for(int i=0;i<2;i++){File base=i==0?layout.root:layout.home;Map files=(Map)plan.get(i==0?"rootFiles":"updateFiles");for(Object item:files.keySet()){File f=at(base,text(item));need(!f.exists()||f.delete());}if(base.exists())AgentInstallLayout.removeEmpty(base);need(!base.exists());}
    }
    void checkHelperCleanup() throws Exception {
        Map owner=AgentInstallLayout.read(at(helper,"v3-inventory.json")),code=(Map)owner.get("helperCode");need(code!=null);
        need(helperSource(owner).equals(code));
        Map actual=tree(helper);
        for(Object item:actual.keySet()){String name=text(item);
            if(code.containsKey(name)){need(code.get(name).equals(actual.get(item)));continue;}
            need(known(name,HELPER_STATIC)&&!name.equals("collector.jar")
                ||name.matches("v3-removal[.]json|request[.]json|claim[.]json|result[.]json|ack[.]json|identity[.]properties|ca[.]pem|removal-started|files[.]tmp|accounts[.]tmp|cron[.](tmp|err)|diagnostic[.](tmp|txt)|supervisor[.]pid|worker[.]lock|stop"));
        }
        directories(helper,helper,actual,"lib");
    }
    static final class Production implements Services {
        final AgentInstallLayout layout;
        Production(AgentInstallLayout layout){this.layout=layout;}
        void command(String[] args)throws Exception {need(AgentUpdateWorker.Production.process(args,1800000)==0);}
        public void stop(String mode)throws Exception {if(!UNINSTALL.exists())return;if(mode.equals("systemd"))command(new String[]{"/bin/systemctl","stop","java-assessment-uninstall.service"});else command(new String[]{"/bin/sh",new File(UNINSTALL,"uninstall-service.sh").getPath(),"stop"});}
        public void start(String mode)throws Exception {if(mode.equals("systemd"))command(new String[]{"/bin/systemctl","daemon-reload"});if(mode.equals("systemd"))command(new String[]{"/bin/systemctl","start","java-assessment-uninstall.service"});else command(new String[]{"/bin/sh",new File(UNINSTALL,"uninstall-service.sh").getPath(),"start"});}
        public void install(String mode,File code)throws Exception {command(new String[]{"/bin/sh",new File(code,"uninstall-install.sh").getPath(),mode});}
    }
    public static void main(String[] args) {
        try {
            AgentInstallLayout layout=AgentInstallLayout.production();AgentV3UninstallInventory tool=new AgentV3UninstallInventory(layout,UNINSTALL,new File("/etc/systemd/system"),new File("/etc/init.d").getCanonicalFile(),new Production(layout));
            if(UNINSTALL.exists())need(AgentUpdateWorker.Production.process(new String[]{"/bin/sh",new File(UNINSTALL,"root-trust.sh").getPath(),"package",UNINSTALL.getPath()},30000)==0);
            if(args.length==2&&AgentInstallLayout.uuid(args[1])){if(args[0].equals("snapshot-uninstall"))tool.snapshot(args[1]);else if(args[0].equals("activate-uninstall"))tool.activate(args[1]);else if(args[0].equals("restore-uninstall"))tool.restore(args[1]);else throw new IOException("Unknown uninstall hook");}
            else if(args.length==1){if(args[0].equals("seal"))tool.seal();else if(args[0].equals("install-code"))tool.installHelperCode();else if(args[0].equals("helper-owned"))tool.checkHelperOwned();else if(args[0].equals("check"))tool.checkRemoval();else if(args[0].equals("remove"))tool.remove();else if(args[0].equals("check-helper"))tool.checkHelperCleanup();else throw new IOException("Unknown uninstall operation");}
            else throw new IOException("Invalid uninstall operation");
        }catch(Exception failure){System.err.println("UNINSTALL_V3_INVENTORY_FAILED");System.exit(1);}
    }
}
