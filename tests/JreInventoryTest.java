package assessment;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Isolated regression fixtures for JRE removal; no application or live dashboard access. */
public class JreInventoryTest extends ServerTest {
    static Map runtime(String id,String kind) {
        Map row=map("id",id,"hostId","fixture-host","javaHome","/opt/"+id,"version","1.6.0_45");
        if(kind!=null)row.put("kind",kind);
        return row;
    }
    static ArrayList rows(Object... values) {return new ArrayList(Arrays.asList(values));}
    static void writeState(Map value)throws Exception {
        FileOutputStream out=new FileOutputStream(new File(root,"data/state.json"));
        try {out.write(Json.stringify(value).getBytes("UTF-8"));}finally {out.close();}
    }
    static Map disk()throws Exception {return (Map)Json.parse(AssessmentServer.read(new File(root,"data/state.json"),32*1024*1024));}
    static void halt(){stop();http=null;}
    static Map snapshot(String jobId) {
        Map snap=map("schemaVersion","1.0","eventId","fixture-jre-event-0001","jobId",jobId,
            "capturedAt",AssessmentServer.now(),"collectorJavaVersion","fixture",
            "host",map("id","fixture-host","hostname","fixture"),
            "coverage",map("status","complete","warnings",new ArrayList(),"stats",map()));
        for(String key:new String[]{"runtimes","processes","servers","libraries","applications","datasources"})snap.put(key,new ArrayList());
        return snap;
    }
    public static void main(String[] args)throws Exception {
        root=File.createTempFile("assessment-jdk-inventory-test-","");root.delete();new File(root,"conf").mkdirs();
        Properties conf=new Properties();conf.setProperty("adminSalt","fixture-salt");
        conf.setProperty("adminPasswordSha256",hex(MessageDigest.getInstance("SHA-256").digest("fixture-saltfixture-password".getBytes("UTF-8"))));
        conf.setProperty("agentToken","fixture-agent-token");conf.setProperty("hostId","fixture-host");
        FileOutputStream out=new FileOutputStream(new File(root,"conf/server.properties"));conf.store(out,"isolated fixtures");out.close();
        try {
            start();Map saved=state();halt();
            Map jdk=runtime("fixture-jdk","JDK");jdk.put("vendor","Java(TM) SE Runtime Environment");
            Map unknown=runtime("fixture-unknown",null);
            saved.put("runtimes",rows(jdk,runtime("fixture-jre","JRE"),unknown,runtime("fixture-jre-lowercase"," jre ")));
            Map process=map("id","fixture-process","hostId","fixture-host","javaHome","/opt/fixture-jre","pid",Long.valueOf(123));
            Map application=map("id","fixture-app","hostId","fixture-host","javaHome","/opt/fixture-jre","type","Tomcat");
            saved.put("processes",rows(process));saved.put("applications",rows(application));
            saved.put("servers",rows(map("id","fixture-server","hostId","fixture-host","type","Tomcat")));
            saved.put("libraries",rows(map("id","fixture-jdbc","hostId","fixture-host","name","JDBC driver")));
            saved.put("datasources",rows(map("id","fixture-datasource","hostId","fixture-host","name","TestDS")));
            saved.put("environments",rows(map("id","fixture-env","name","Desenv","settings",map())));
            ((Map)((List)saved.get("hosts")).get(0)).put("environmentId","fixture-env");
            saved=(Map)Json.parse(Json.stringify(saved));
            writeState(saved);
            String registryBefore=AssessmentServer.read(new File(root,"data/auth-registry.json"),1024*1024);
            start();login();Map current=(Map)call("GET","/api/state",null,true,false)[1];
            check(((List)current.get("runtimes")).equals(rows(jdk,unknown)),"startup removes only explicitly identified JREs");
            check(((List)disk().get("runtimes")).equals(rows(jdk,unknown)),"startup removal persists");
            for(String key:new String[]{"hosts","environments","processes","servers","libraries","applications","datasources","settings","jobs","eventIds","hostSchedules"})
                check(saved.get(key).equals(state().get(key)),"migration preserves "+key);
            check(registryBefore.equals(AssessmentServer.read(new File(root,"data/auth-registry.json"),1024*1024)),"migration preserves credentials and enrollments");

            Object[] queued=call("POST","/api/jobs",map("hostId","fixture-host"),true,false);
            check(queued[0].equals(202),"queue old collector fixture");String jobId=(String)((Map)queued[1]).get("id");
            check(jobId.equals(((Map)call("GET","/api/agent/poll?hostId=fixture-host",null,false,true)[1]).get("jobId")),"lease old collector fixture");
            Map snap=snapshot(jobId);Map jre=runtime("fixture-jre","JRE");jre.put("hostId","foreign-host");
            snap.put("runtimes",rows(jre,jdk,unknown));snap.put("processes",rows(process));snap.put("applications",rows(application));
            check(call("POST","/api/agent/results",snap,false,true)[0].equals(400),"JRE filtering cannot bypass host ownership validation");
            jre.put("hostId","fixture-host");
            check(call("POST","/api/agent/results",snap,false,true)[0].equals(200),"accept old collector results");
            check(((List)disk().get("runtimes")).equals(rows(jdk,unknown)),"old collector JRE is discarded before storage");
            check(((List)state().get("processes")).equals(rows(process)),"JRE process evidence preserved");
            check(((List)state().get("applications")).equals(rows(application)),"JRE application evidence preserved");
            Map completed=(Map)((List)state().get("jobs")).get(0);
            check(String.valueOf(completed.get("summary")).indexOf("2 Java installation(s)")>=0,"job summary counts retained inventory");
            check(Boolean.TRUE.equals(((Map)call("POST","/api/agent/results",snap,false,true)[1]).get("duplicate")),"filtered snapshot retry remains idempotent");
            check(((List)((Map)call("GET","/api/export",null,true,false)[1]).get("runtimes")).equals(rows(jdk,unknown)),"export omits JREs");
            halt();

            Map beforeFailure=disk();beforeFailure.put("runtimes",rows(jdk,jre));writeState(beforeFailure);
            String committed=AssessmentServer.read(new File(root,"data/state.json"),32*1024*1024);
            File blocked=new File(root,"data/state.json.tmp");check(blocked.mkdir(),"prepare isolated atomic write failure");
            boolean failed=false;try {new AssessmentServer(root);}catch(IOException expected){failed=true;}
            check(failed,"failed migration write prevents serving uncommitted state");
            check(committed.equals(AssessmentServer.read(new File(root,"data/state.json"),32*1024*1024)),"failed migration preserves committed inventory");
            check(blocked.delete(),"remove isolated write failure");
            start();check(((List)disk().get("runtimes")).equals(rows(jdk)),"migration retries successfully after storage recovery");
            System.out.println("JRE_INVENTORY_TESTS=PASS startup-migration persisted old-collector ownership-validation preserved-evidence credentials export idempotency atomic-failure-recovery");
        } finally {if(http!=null)halt();erase(root);}
    }
}
