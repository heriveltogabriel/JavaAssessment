package assessment;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Original 1.5.12 worker -> real HTTP/SQLite -> full installer. OS is an adapter. */
public final class AgentFullUpdateEndToEndTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static String s(Object x){return x instanceof String?(String)x:"";}
    static Map<String,Object> api(int status,String method,String path,Object body)throws Exception{return AgentUpdateMigrationHttpTest.request(status,method,path,body,"fixture-host").json();}
    static void heartbeat(String version,String capability,Object bundle,Object manager)throws Exception{AgentUpdateMigrationHttpTest.heartbeat("fixture-host",version,capability,bundle,manager);}
    static void originalCycle(AgentBundleInstallerTest.Fixture f,Map<String,Object> action,Path original)throws Exception{
        Files.copy(original,f.layout.root.toPath().resolve("collector.jar"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(f.layout.root.toPath().resolve("collector-spool/update-request.json"),Json.stringify(action));
        String child=s(action.get("requestId"));List<URL> urls=new ArrayList<>();urls.add(original.toUri().toURL());
        try(var libraries=Files.list(f.layout.root.toPath().resolve("lib"))){for(var p:libraries.filter(p->p.toString().endsWith(".jar")).toList())urls.add(p.toUri().toURL());}
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())){
            Class<?> worker=Class.forName("assessment.AgentUpdateWorker",true,loader),platform=Class.forName("assessment.AgentUpdateWorker$Platform",true,loader),remote=Class.forName("assessment.AgentUpdateWorker$Remote",true,loader);
            check(worker.getProtectionDomain().getCodeSource().getLocation().toURI().equals(original.toUri()),"legacy worker loaded from original published binary");
            Object platformAdapter=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{platform},(proxy,method,args)->{
                if(method.getName().equals("local")){String command=(String)args[0];if(command.equals("pause"))f.active=false;if(command.equals("start"))f.active=true;return null;}
                File jar=(File)args[0];String version=(String)args[1];boolean hb=(Boolean)args[2];check(AgentUpdateWorker.jarVersion(jar).equals(version),"legacy probe version matches actual file");if(hb)heartbeat(version,"update-v1","","");return 0;
            });
            Object remoteAdapter=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{remote},(proxy,method,args)->{
                if(method.getName().equals("request"))return api(200,"POST","/api/agent/update/"+args[0],args[1]);
                String part=(String)args[0];File target=(File)args[1];long max=((Number)args[2]).longValue();var result=AgentUpdateMigrationHttpTest.request(200,"GET","/api/agent/update/"+part+"?hostId=fixture-host&requestId="+child,null,"fixture-host");check(result.bytes().length<=max,"bounded legacy transfer");Files.write(target.toPath(),result.bytes());return null;
            });
            Constructor<?> constructor=worker.getDeclaredConstructor(File.class,File.class,platform,remote);constructor.setAccessible(true);Object instance=constructor.newInstance(f.layout.root,f.layout.home,platformAdapter,remoteAdapter);Method cycle=worker.getDeclaredMethod("cycle");cycle.setAccessible(true);
            try{cycle.invoke(instance);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception failure)throw failure;throw e;}
        }
        check(!new File(f.layout.home,"journal.json").exists()&&!new File(f.layout.home,"backup.jar").exists(),"original ACK causes actual old cleanup");
        check(AgentUpdateWorker.jarVersion(new File(f.layout.root,"collector.jar")).equals(action.get("targetVersion")),"original worker installed actual production bridge JAR");
    }
    public static void main(String[] args)throws Exception{
        Path fixture=Path.of(args.length>0?args[0]:"tmp/full-update/http-fixture");Path webRoot=Files.createTempDirectory("full-update-http-");Files.createDirectories(webRoot.resolve("conf"));AgentUpdateRoutesTest.copy(fixture.resolve("dist"),webRoot.resolve("dist"));Files.createSymbolicLink(webRoot.resolve("web"),Path.of("web").toAbsolutePath());Properties settings=new Properties();settings.setProperty("adminSalt","fixture-salt");settings.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));settings.setProperty("seedLegacyHost","false");settings.setProperty("hostId","unused");settings.setProperty("agentToken","unused");try(var out=Files.newOutputStream(webRoot.resolve("conf/server.properties"))){settings.store(out,"Disposable E2E");}
        AgentUpdateMigrationHttpTest.root=webRoot;AgentUpdateMigrationHttpTest.start();
        try(AgentBundleInstallerTest.Fixture f=new AgentBundleInstallerTest.Fixture(fixture)){
            f.config.setProperty("serverUrl",AgentUpdateMigrationHttpTest.base);f.config.setProperty("agentToken","fixture-token-fixture-host");try(var out=new FileOutputStream(new File(f.layout.root,"conf/collector.properties"))){f.config.store(out,"isolated E2E identity");}f.preserved.put("collector/conf/collector.properties",AgentBundleManifest.hash(new File(f.layout.root,"conf/collector.properties")));
            var app=AgentUpdateMigrationHttpTest.app;app.store.transaction(()->{app.store.put("environments",map("id","env-test","name","E2E","settings",map()));AgentUpdateMigrationHttpTest.seed("fixture-host","1.5.12","update-v1");return null;});
            var created=AgentUpdateMigrationHttpTest.request(200,"POST","/api/agent-updates",map("hostIds",List.of("fixture-host"),"targetVersion",DashboardVersion.VERSION,"requestKey","full-update-end-to-end-fixture","runAssessmentAfterUpdate",true),null).json();String operation=s(AgentUpdateMigrationHttpTest.list(created.get("requests")).get(0).get("id"));
            var legacy=AgentUpdateMigrationHttpTest.obj(api(200,"GET","/api/agent/actions?hostId=fixture-host&updateCapability=update-v1",null).get("action"));Path original=f.source.resolve(s(f.metadata.get("legacyRoot"))).resolve("collector.jar");originalCycle(f,legacy,original);
            check(app.store.scalar("SELECT count(*) FROM jobs")==0,"real old completion does not queue assessment");check(!app.store.get("agent_update_requests",operation).get("status").equals("SUCCEEDED"),"logical operation survives old completion");
            Map<String,Object> action=AgentUpdateMigrationHttpTest.obj(api(200,"GET","/api/agent/update-v3/action?hostId=fixture-host&updateCapability=update-v1",null).get("action"));AgentBundleTransport transport=new AgentBundleTransport(f.config);
            AgentBundleInstaller.Platform platform=new AgentBundleInstaller.Platform(){
                public void local(String command,Map transaction)throws Exception{
                    if(command.equals("snapshot-services")){AgentBundleInstallerTest.put(new File(f.layout.transaction(operation),"previous-services/collector.service"),"owned fixture service\n");return;}
                    f.local(command,transaction);
                }
                public int probe(File code,String version,boolean hb)throws Exception{int result=f.probe(code,version,hb);if(hb&&result==0){Map pointer=f.layout.current();heartbeat(version,"update-v3",pointer.get("bundleSha256"),pointer.get("managerSha256"));}return result;}
                public boolean serviceEvidence(Map pointer)throws Exception{return f.serviceEvidence(pointer);}
                public boolean processUses(File path){return false;}
            };
            new AgentBundleInstaller(f.layout,platform,transport,f.config).prepareContinuation(action);check(f.bootstrapped,"independent recovery prepared after old cleanup");
            new AgentBundleInstaller(f.layout,platform,transport,f.config).cycle();check(AgentInstallLayout.read(f.layout.journal()).get("phase").equals("REMOTE_CONFIRMING"),"activation exits before new manager confirms");check(!app.store.get("agent_update_requests",operation).get("status").equals("SUCCEEDED"),"file presence does not certify new manager");
            f.loadedManager=true;new AgentBundleInstaller(f.layout,platform,transport,f.config).cycle();check(!f.layout.journal().exists(),"full journal committed and cleaned after real server ACK");check(app.store.get("agent_update_requests",operation).get("status").equals("SUCCEEDED"),"real API observes final full update");
            check(f.managerHash.equals(AgentBundleManifest.hash(new File(f.layout.manager(f.layout.current()),"helper.jar"))),"helper replaced by signed final manager");check(!f.managerHash.equals(AgentBundleManifest.hash(original.toFile())),"manager is not the old 1.5.12 helper");f.unchanged();
            var poll=api(200,"GET","/api/agent/poll?hostId=fixture-host&updateCapability=update-v3",null);check(!s(poll.get("jobId")).isEmpty(),"final full update delivers linked assessment");api(200,"GET","/api/agent/poll?hostId=fixture-host&updateCapability=update-v3",null);check(app.store.scalar("SELECT count(*) FROM jobs WHERE host_id='fixture-host'")==1,"new poll does not duplicate post-update job");
            System.out.println("AgentFullUpdateEndToEndTest PASS "+checks+" checks: ORIGINAL 1.5.12 binary -> actual production bridge -> signed complete package -> promoted manager -> real authenticated HTTP/SQLite confirmation -> one assessment; preserved identity/CA/Java/spool. OS service/probe execution remains an explicit test adapter.");
        }finally{AgentUpdateMigrationHttpTest.stop();try(var paths=Files.walk(webRoot)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
