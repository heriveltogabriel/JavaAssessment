package assessment;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static assessment.AssessmentServer.map;
import static assessment.BetaServerTest.*;

/** Real collector fixture -> HTTP ingestion -> SQLite summary and CSV/JSON. No VM. */
public final class RuntimeInventoryHttpTest {
    public static void main(String[] args)throws Exception {
        root=Files.createTempDirectory("runtime-inventory-http-");
        Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));
        Properties config=new Properties();config.setProperty("seedLegacyHost","false");
        config.setProperty("adminSalt","fixture-salt");config.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));
        config.setProperty("agentToken","unused-fixture-token");config.setProperty("hostId","unused-fixture-host");
        config.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){config.store(out,"isolated runtime fixture");}
        VersionedCsvHttpTest.packageZip(AgentTransport.VERSION);
        try {
            start();
            String env=(String)ok("POST","/api/environments",map("name","Homologação"),true,null,201).get("id");
            String code=packageCode(env).getProperty("enrollmentToken");
            Map<String,Object> registration=registration("runtime-installation-0001");registration.put("hostname","brunods-dev-vnic");registration.put("collectorVersion",AgentTransport.VERSION);
            Map<String,Object> identity=ok("POST","/api/agent/register",registration,false,code,200);
            String h=(String)identity.get("hostId"),token=(String)identity.get("agentToken");
            String job=(String)ok("POST","/api/jobs",map("hostId",h),true,null,202).get("id");
            check(job.equals(ok("GET","/api/agent/poll?hostId="+h,null,false,token,200).get("jobId")),"job leased to actual collector fixture");
            Map<String,Object> snapshot=obj(RuntimeInventoryTest.snapshot(h,job,Instant.now().toString()));
            List<Object> runtimes=new ArrayList<>((List<Object>)snapshot.get("runtimes"));
            String home=(String)obj(runtimes.get(0)).get("javaHome");
            // Foreign inactive JRE must not bypass ownership checks before the acceptance rule.
            runtimes.add(map("id","foreign-runtime","hostId","other-machine","kind","JRE","inUse",false));snapshot.put("runtimes",runtimes);
            check(call("POST","/api/agent/results",snapshot,false,token).code()==400,"foreign runtime rejected before filtering");
            check(app.store.scalar("SELECT count(*) FROM inventory WHERE host_id=?",h)==0,"failed result is atomic");
            runtimes.remove(runtimes.size()-1);
            runtimes.add(map("id","unused-runtime","hostId",h,"kind","JRE","inUse",false,"version","21.0.12","javaHome","/unused"));
            snapshot.put("runtimes",runtimes);
            ok("POST","/api/agent/results",snapshot,false,token,200);
            check(String.valueOf(app.store.get("jobs",job).get("summary")).contains("1 Java installation(s)"),"job summary counts active runtime, omits unused runtime");
            Map<String,Object> view=ok("GET","/api/state?view=runtimes&environmentId="+env,null,true,null,200);
            check(((Number)obj(view.get("summary")).get("runtimes")).intValue()==1,"dashboard metric includes runtime without compiler");
            check(((Number)obj(view.get("summary")).get("distinctVersions")).intValue()==1,"one exact Java version");
            check(list(view.get("runtimes")).size()==1&&((Number)obj(view.get("pagination")).get("total")).intValue()==1,"bounded inventory returns one installation");
            Map<String,Object> row=obj(list(view.get("runtimes")).get(0));
            check("JRE".equals(row.get("kind"))&&Boolean.TRUE.equals(row.get("inUse"))&&"21.0.12".equals(row.get("version")),"runtime classified and version preserved");
            check(home.equals(row.get("javaHome")),"canonical application Java home retained");
            check("Java 21".equals(obj(list(view.get("javaGroups")).get(0)).get("name")),"Java 21 version group includes runtime");
            view=ok("GET","/api/state?view=applications&hostId="+h,null,true,null,200);
            check(list(view.get("applications")).size()==2&&list(view.get("applications")).stream().allMatch(v->home.equals(obj(v).get("javaHome"))),"both application JVMs linked to one runtime");
            Map<String,Object> json=ok("GET","/api/export?environmentId="+env,null,true,null,200);
            check(list(json.get("runtimes")).size()==1&&list(json.get("applications")).size()==2,"JSON exports observed runtime and applications");
            List<Map<String,String>> csv=VersionedCsvHttpTest.exported("?environmentId="+env);
            check(VersionedCsvHttpTest.count(csv,"runtimes")==1&&VersionedCsvHttpTest.count(csv,"applications")==2,"CSV includes runtime without compiler");
            check(csv.stream().anyMatch(v->"runtimes".equals(v.get("record_type"))&&"JRE".equals(v.get("type"))&&"21.0.12".equals(v.get("version"))&&home.equals(v.get("java_home"))),"CSV runtime type/version/path");
            check(!Json.stringify(json).contains(token)&&!Json.stringify(csv).contains(token),"exports omit agent credential");
            check(Boolean.TRUE.equals(ok("POST","/api/agent/results",snapshot,false,token,200).get("duplicate")),"result retry is idempotent");
            stop();start();
            view=ok("GET","/api/state?view=runtimes&hostId="+h,null,true,null,200);
            check(((Number)obj(view.get("summary")).get("runtimes")).intValue()==1&&"JRE".equals(obj(list(view.get("runtimes")).get(0)).get("kind")),"runtime survives server restart");
            check(AgentTransport.VERSION.equals(ok("GET","/api/collector/info",null,true,null,200).get("version")),"download version matches actual agent");
            System.out.println("RUNTIME_INVENTORY_HTTP_TESTS=PASS real-collector-fixture OpenJDK21 no-javac two-JVMs one-installation HTTP SQLite summary groups CSV JSON restart idempotency ownership credentials");
        } finally {
            stop();try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
