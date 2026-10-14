package assessment;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Original 1.5.12 bytes, actual signed profiles and real HTTP/SQLite. OS is an adapter. */
public final class AgentUpdateProfileFallbackTest {
    static int checks;
    static Path fixture;
    static Map<String,Object> metadata;
    static final List<Map<String,Object>> evidence=new ArrayList<>();
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static String s(Object value){return value instanceof String?(String)value:"";}
    static Map<String,Object> obj(Object value){return AgentUpdateMigrationHttpTest.obj(value);}
    static List<Map<String,Object>> list(Object value){return AgentUpdateMigrationHttpTest.list(value);}
    static void copy(Path source,Path destination)throws Exception{AgentUpdateRoutesTest.copy(source,destination);}
    static void remove(Path root)throws Exception{if(Files.exists(root))try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    static Map<String,Object> api(String host,String method,String path,Object body)throws Exception{return AgentUpdateMigrationHttpTest.request(200,method,path,body,host).json();}
    static Map<String,Object> action(String host)throws Exception{return obj(api(host,"GET","/api/agent/actions?hostId="+host+"&updateCapability=update-v1",null).get("action"));}

    static final class Original implements AutoCloseable {
        final Path baseline;
        final URLClassLoader loader;
        final Class<?> worker,platform,remote;
        final Constructor<?> constructor,failure;
        final Method cycle;
        Original()throws Exception{
            baseline=fixture.resolve(s(metadata.get("baselineJar"))).toRealPath();
            check(AgentBundleManifest.hash(baseline.toFile()).equals(metadata.get("fromCollectorSha256")),"baseline hash is original 1.5.12");
            check("1.5.12".equals(AgentUpdateWorker.jarVersion(baseline.toFile())),"baseline declares original version");
            loader=new URLClassLoader(new URL[]{baseline.toUri().toURL()},ClassLoader.getPlatformClassLoader());
            worker=Class.forName("assessment.AgentUpdateWorker",true,loader);
            platform=Class.forName("assessment.AgentUpdateWorker$Platform",true,loader);remote=Class.forName("assessment.AgentUpdateWorker$Remote",true,loader);
            failure=Class.forName("assessment.AgentUpdateWorker$Failure",true,loader).getDeclaredConstructor(String.class);failure.setAccessible(true);
            constructor=worker.getDeclaredConstructor(File.class,File.class,platform,remote);constructor.setAccessible(true);
            cycle=worker.getDeclaredMethod("cycle");cycle.setAccessible(true);
            check(worker.getClassLoader()==loader&&worker.getProtectionDomain().getCodeSource().getLocation().toURI().equals(baseline.toUri()),"actual worker class comes exclusively from original published binary");
            try(var jar=new java.util.jar.JarFile(baseline.toFile());var in=jar.getInputStream(jar.getJarEntry("assessment/AgentUpdateWorker.class"))){byte[] prefix=in.readNBytes(8);check(prefix.length==8&&prefix[6]==0&&prefix[7]==50,"original worker major50");}
        }
        public void close()throws Exception{loader.close();}
    }

    static final class Scenario implements AutoCloseable {
        final Original original;
        final String host,kind,operation;
        final Path base,root,home;
        final Map<String,String> preserved=new LinkedHashMap<>();
        final List<String> reports=new ArrayList<>();
        int pauses,starts,probes,downloads;
        boolean serviceFailure=true;
        Scenario(Original original,String host,String kind,boolean alternate)throws Exception{
            this.original=original;this.host=host;this.kind=kind;
            base=Files.createTempDirectory("profile-fallback-").toRealPath();root=base.resolve("collector");home=base.resolve("updater");
            copy(fixture.resolve(s(metadata.get(alternate?"alternateRoot":"legacyRoot"))),root);copy(fixture.resolve("home"),home);
            Files.writeString(root.resolve("conf/collector.properties"),"hostId="+host+"\nagentToken=fixture-token-"+host+"\nserverUrl="+AgentUpdateMigrationHttpTest.base+"\nspoolDirectory="+root.resolve("collector-spool")+"\n");
            Files.writeString(root.resolve("conf/installation-id"),"preserved-"+host+"\n");Files.writeString(root.resolve("collector-spool/latest-snapshot.json"),"{\"preserved\":true}\n");
            for(String name:new String[]{"conf/collector.properties","conf/installation-id","conf/java.path","poll.sh","collector-spool/latest-snapshot.json"})preserved.put(name,AgentBundleManifest.hash(root.resolve(name).toFile()));
            AgentUpdateMigrationHttpTest.app.store.transaction(()->{AgentUpdateMigrationHttpTest.seed(host,"1.5.12","update-v1");return null;});
            var created=AgentUpdateMigrationHttpTest.request(200,"POST","/api/agent-updates",map("hostIds",List.of(host),"targetVersion",DashboardVersion.VERSION,"requestKey","profile-fallback-"+host,"runAssessmentAfterUpdate",false),null).json();
            operation=s(list(created.get("requests")).get(0).get("id"));
            Map<String,Object> plan=obj(AgentUpdateMigrationHttpTest.app.store.get("agent_update_requests",operation).get("updatePlan"));
            check(list(plan.get("profiles")).size()==2&&list(plan.get("profiles")).get(0).get("id").equals(list(metadata.get("profiles")).get(0).get("id")),"two signed profiles are pinned in catalog order");
        }
        Object local(String command)throws Exception{
            if(command.equals("pause")){
                pauses++;
                if(kind.equals("service")&&serviceFailure){serviceFailure=false;throw (Exception)original.failure.newInstance("SERVICE_FAILED");}
            }
            if(command.equals("start"))starts++;
            return null;
        }
        Object probe(File jar,String version,boolean heartbeat)throws Exception{
            probes++;check(version.equals(AgentUpdateWorker.jarVersion(jar)),"probe adapter reads actual JAR version");
            if(kind.equals("probe")&&jar.getName().equals("candidate.jar"))return 20;
            if(kind.equals("live-probe")&&jar.toPath().equals(root.resolve("collector.jar"))&&version.equals(metadata.get("bridgeVersion")))return 20;
            if(heartbeat)AgentUpdateMigrationHttpTest.heartbeat(host,version,"update-v1","","");
            return 0;
        }
        void cycle(Map<String,Object> action)throws Exception{
            check(action!=null&&action.get("type").equals("UPDATE_AGENT_V1"),"original worker receives exact legacy action");
            String child=s(action.get("requestId"));Files.writeString(root.resolve("collector-spool/update-request.json"),Json.stringify(action));
            Object platform=java.lang.reflect.Proxy.newProxyInstance(original.loader,new Class<?>[]{original.platform},(proxy,method,args)->switch(method.getName()){
                case "local"->local((String)args[0]);case "probe"->probe((File)args[0],(String)args[1],(Boolean)args[2]);default->throw new AssertionError("Unexpected platform method");});
            Object remote=java.lang.reflect.Proxy.newProxyInstance(original.loader,new Class<?>[]{original.remote},(proxy,method,args)->{
                if(method.getName().equals("request")){
                    if(args[0].equals("report"))reports.add(s(((Map<?,?>)args[1]).get("status"))+":"+s(((Map<?,?>)args[1]).get("diagnostic")));
                    return api(host,"POST","/api/agent/update/"+args[0],args[1]);
                }
                String part=(String)args[0];File target=(File)args[1];long max=((Number)args[2]).longValue();
                byte[] bytes=AgentUpdateMigrationHttpTest.request(200,"GET","/api/agent/update/"+part+"?hostId="+host+"&requestId="+child,null,host).bytes();
                if(kind.equals("signature")&&part.equals("signature")){bytes=bytes.clone();bytes[0]^=1;}
                check(bytes.length<=max,"original bounded transfer remains enforced");Files.write(target.toPath(),bytes);downloads++;return null;
            });
            Object worker=original.constructor.newInstance(root.toFile(),home.toFile(),platform,remote);
            try{original.cycle.invoke(worker);}catch(InvocationTargetException wrapped){if(wrapped.getCause() instanceof Exception failure)throw failure;throw wrapped;}
            for(var entry:preserved.entrySet())check(entry.getValue().equals(AgentBundleManifest.hash(root.resolve(entry.getKey()).toFile())),"configuration/identity/Java/support/spool preserved: "+entry.getKey());
            check(AgentBundleManifest.hash(home.resolve("helper.jar").toFile()).equals(metadata.get("fromCollectorSha256")),"legacy helper remains separate and original");
        }
        Map<String,Object> parent(){return AgentUpdateMigrationHttpTest.app.store.get("agent_update_requests",operation);}
        Map<String,Object> child(Map<String,Object> action){return AgentUpdateMigrationHttpTest.app.store.get("agent_update_requests",s(action.get("requestId")));}
        void record(String result){evidence.add(map("scenario",kind,"result",result,"reports",new ArrayList<>(reports),"pauses",pauses,"starts",starts,"probes",probes,"downloads",downloads,"profileIndex",obj(parent().get("updatePlan")).get("profileIndex")));}
        public void close()throws Exception{remove(base);}
    }

    public static void main(String[] args)throws Exception{
        fixture=Path.of(args.length>0?args[0]:"tmp/full-update/profile-fixture").toRealPath();metadata=obj(Json.parse(Files.readString(fixture.resolve("fixture.json"))));
        check(Boolean.TRUE.equals(metadata.get("testOnly"))&&Boolean.TRUE.equals(metadata.get("profileFallbackOnly")),"dedicated test fixture required");
        Path webRoot=Files.createTempDirectory("profile-fallback-http-");Files.createDirectories(webRoot.resolve("conf"));copy(fixture.resolve("dist"),webRoot.resolve("dist"));Files.createSymbolicLink(webRoot.resolve("web"),Path.of("web").toAbsolutePath());
        Properties settings=new Properties();settings.setProperty("adminSalt","fixture-salt");settings.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));settings.setProperty("seedLegacyHost","false");settings.setProperty("hostId","unused");settings.setProperty("agentToken","unused");try(var out=Files.newOutputStream(webRoot.resolve("conf/server.properties"))){settings.store(out,"Isolated profile fallback test");}
        AgentUpdateMigrationHttpTest.root=webRoot;AgentUpdateMigrationHttpTest.start();
        try(Original original=new Original()){
            AgentUpdateMigrationHttpTest.app.store.transaction(()->{AgentUpdateMigrationHttpTest.app.store.put("environments",map("id","env-test","name","Profile fallback","settings",map()));return null;});
            try(Scenario scenario=new Scenario(original,"fallback-success","support",false)){
                var first=action(scenario.host);scenario.cycle(first);
                check(scenario.child(first).get("diagnostic").equals("INCOMPATIBLE_ASSETS"),"first signed known profile rejected with exact local diagnosis");
                check(AgentBundleManifest.hash(scenario.root.resolve("collector.jar").toFile()).equals(metadata.get("fromCollectorSha256"))&&scenario.pauses==0&&scenario.probes==0,"incompatible assets rejected before any swap, service pause or probe");
                check(obj(scenario.parent().get("updatePlan")).get("profileIndex").equals(1L)&&scenario.parent().get("migrationStage").equals("PROFILE_PENDING"),"real SQLite advances only to second pinned signed profile");
                var second=action(scenario.host);check(!first.get("requestId").equals(second.get("requestId")),"fallback uses a new durable legacy child");
                check(scenario.child(second).get("profileId").equals(list(metadata.get("profiles")).get(1).get("id")),"next child selects original support profile");
                scenario.cycle(second);check(scenario.child(second).get("status").equals("SUCCEEDED"),"original worker completes second signed bridge through HTTP");
                check(AgentUpdateWorker.jarVersion(scenario.root.resolve("collector.jar").toFile()).equals(metadata.get("bridgeVersion")),"actual installed JAR is bridge version");
                check(scenario.parent().get("migrationStage").equals("BUNDLE_READY")&&!scenario.parent().get("status").equals("SUCCEEDED"),"bridge success leaves final full operation pending");
                check(!Files.exists(scenario.home.resolve("journal.json"))&&!Files.exists(scenario.home.resolve("backup.jar"))&&!Files.exists(scenario.root.resolve("collector-spool/update-request.json")),"original terminal ACK performs real journal/request/backup cleanup");
                check(AgentUpdateMigrationHttpTest.app.store.scalar("SELECT count(*) FROM agent_update_steps WHERE operation_id=?",scenario.operation)==2,"two and only two durable profile attempts");scenario.record("SECOND_PROFILE_BRIDGE_SUCCEEDED");
            }
            for(String kind:List.of("signature","probe","service","live-probe"))try(Scenario scenario=new Scenario(original,"no-fallback-"+kind,kind,true)){
                var first=action(scenario.host);scenario.cycle(first);String expected=kind.equals("signature")?"INVALID_SIGNATURE":kind.equals("service")?"SERVICE_FAILED":"PROBE_FAILED";
                check(scenario.child(first).get("diagnostic").equals(expected),kind+" failure retains its original diagnosis");
                check(obj(scenario.parent().get("updatePlan")).get("profileIndex").equals(0L),kind+" failure never increments support profile");
                check(AgentUpdateRegistry.TERMINAL.contains(s(scenario.parent().get("status")))&&action(scenario.host)==null,kind+" failure stops the operation without another profile");
                check(AgentUpdateMigrationHttpTest.app.store.scalar("SELECT count(*) FROM agent_update_steps WHERE operation_id=?",scenario.operation)==1,"no hidden second attempt for "+kind);
                check(AgentBundleManifest.hash(scenario.root.resolve("collector.jar").toFile()).equals(metadata.get("fromCollectorSha256")),kind+" failure preserves or restores original collector bytes");scenario.record("STOPPED_WITHOUT_PROFILE_FALLBACK");
            }
            check(AgentUpdateMigrationHttpTest.app.store.scalar("SELECT count(*) FROM jobs")==0,"profile testing and bridge success never trigger assessment");
            Files.writeString(fixture.resolve("profile-fallback-evidence.json"),Json.stringify(map("schema",1,"testOnly",true,"originalWorkerSha256",metadata.get("fromCollectorSha256"),"originalWorkerCodeSource",original.baseline.toString(),"realHttp",true,"realSqlite",true,"realLegacyWorker",true,"java6RuntimeTested",false,"osServicesAndProbes","explicit test adapters","checks",checks,"scenarios",evidence))+"\n");
            System.out.println("AgentUpdateProfileFallbackTest PASS "+checks+" checks: original 1.5.12 worker + two signed support profiles + real HTTP/SQLite; incompatible support advances before swap; signature/candidate probe/service/live probe failures never advance. OS services/probes are adapters; Java6 runtime is a separate gate.");
        }finally{AgentUpdateMigrationHttpTest.stop();remove(webRoot);}
    }
}
