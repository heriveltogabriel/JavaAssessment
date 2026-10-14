package assessment;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;
import static assessment.BetaServerTest.*;

/** Actual collector fixture rows -> authenticated HTTP -> SQLite -> overview contracts. */
public final class JdbcOverviewHttpTest {
    private static int checks;
    private static void verify(boolean condition,String message){checks++;check(condition,message);}
    private static long number(Object value){return value instanceof Number?((Number)value).longValue():0;}
    private static Map<String,Object> view(String scope)throws Exception{return ok("GET","/api/state?view=overview"+scope,null,true,null,200);}
    private static void counts(Map<String,Object> data,int expected,String label){
        verify(number(obj(data.get("summary")).get("datasources"))==expected,label+" datasource count");
        verify(number(obj(data.get("datasourceSummary")).get("configured"))==expected,label+" configuration count");
        verify(number(obj(data.get("datasourceSummary")).get("runtime"))==0,label+" no runtime connection inference");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> snapshot(Map<String,Object> fixture,String host,String job,String event,String time){
        Map<String,Object> snap=obj(Json.parse(Json.stringify(fixture)));
        snap.putAll(map("schemaVersion","1.0","eventId",event,"jobId",job,"capturedAt",time,"collectorJavaVersion","17-fixture","host",map("id",host,"hostname","jdbc-"+host)));
        snap.putIfAbsent("coverage",map("status","complete","warnings",List.of(),"stats",map()));
        for(String kind:List.of("runtimes","servers","applications","libraries","datasources","processes")){
            snap.putIfAbsent(kind,new ArrayList<>());
            for(Object item:list(snap.get(kind))){Map<String,Object> row=obj(item);row.put("hostId",host);row.put("observedAt",time);}
        }
        return snap;
    }
    private static void send(String host,String token,Map<String,Object> fixture,String event,String time)throws Exception{
        String job=queue(host);poll(host,token,job);
        Map<String,Object> snap=snapshot(fixture,host,job,event,time);
        verify(call("POST","/api/agent/results",snap,false,null).code()==401,"unauthenticated collector snapshot rejected");
        ok("POST","/api/agent/results",snap,false,token,200);
        verify(Boolean.TRUE.equals(ok("POST","/api/agent/results",snap,false,token,200).get("duplicate")),"snapshot retry is idempotent");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Expected sanitized collector fixture JSON and output directory");
        Map<String,Object> fixtures=obj(Json.parse(Files.readString(Path.of(args[0]))));
        Map<String,Object> tomcat=obj(fixtures.get("tomcat")),jboss=obj(fixtures.get("jboss"));
        int tomcatCount=list(tomcat.get("datasources")).size(),jbossCount=list(jboss.get("datasources")).size();
        verify(tomcatCount>0&&jbossCount>0,"real parser produced datasources for both servers");
        Path evidence=Path.of(args[1]);Files.createDirectories(evidence);
        root=Files.createTempDirectory("assessment-jdbc-http-");Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));
        Properties config=new Properties();config.setProperty("seedLegacyHost","false");config.setProperty("adminSalt","fixture-salt");config.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));config.setProperty("publicUrl","https://fixture.invalid");config.setProperty("agentToken","unused-fixture-token");config.setProperty("hostId","unused-fixture-host");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){config.store(out,"isolated JDBC fixture");}
        try(var zip=new ZipOutputStream(Files.newOutputStream(root.resolve("dist/assessment-collector.zip")))){
            for(String name:List.of("install.sh","system-install.sh","bootstrap-config.sh","collector.jar","conf/ca.pem","DEPENDENCIES.json")){
                zip.putNextEntry(new ZipEntry(name));zip.write((name.equals("DEPENDENCIES.json")?Json.stringify(map("version",DashboardVersion.VERSION)):"fixture").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        try{
            start();counts(view(""),0,"before collection");
            String env=(String)ok("POST","/api/environments",map("name","Desenvolvimento"),true,null,201).get("id");
            String other=(String)ok("POST","/api/environments",map("name","Outro ambiente"),true,null,201).get("id");
            Properties bootstrap=packageCode(env),otherBootstrap=packageCode(other);
            Map<String,Object> first=ok("POST","/api/agent/register",registration("fixture-jdbc-tomcat"),false,bootstrap.getProperty("enrollmentToken"),200);
            Map<String,Object> second=ok("POST","/api/agent/register",registration("fixture-jdbc-jboss"),false,bootstrap.getProperty("enrollmentToken"),200);
            Map<String,Object> outside=ok("POST","/api/agent/register",registration("fixture-jdbc-outside"),false,otherBootstrap.getProperty("enrollmentToken"),200);
            String h1=(String)first.get("hostId"),h2=(String)second.get("hostId"),token1=(String)first.get("agentToken"),token2=(String)second.get("agentToken");
            String t0=Instant.now().minusSeconds(30).toString();
            send(h1,token1,tomcat,"jdbc-tomcat-event-0001",t0);send(h2,token2,jboss,"jdbc-jboss-event-0001",t0);
            Map<String,Object> global=view(""),environment=view("&environmentId="+env),machine1=view("&environmentId="+env+"&hostId="+h1),machine2=view("&environmentId="+env+"&hostId="+h2);
            counts(global,tomcatCount+jbossCount,"global");counts(environment,tomcatCount+jbossCount,"development");counts(machine1,tomcatCount,"Tomcat machine");counts(machine2,jbossCount,"JBoss machine");counts(view("&environmentId="+other),0,"other environment");
            for(Object value:list(tomcat.get("runtimes"))){Map<String,Object> expected=obj(value);if(expected.get("version")!=null){
                Map<String,Object> actual=null;for(Object candidate:list(machine1.get("runtimes"))){Map<String,Object> row=obj(candidate);if(Objects.equals(expected.get("javaHome"),row.get("javaHome")))actual=row;}
                verify(actual!=null&&Objects.equals(expected.get("version"),actual.get("version")),"exact runtime version survives authenticated ingestion");
                verify(Objects.equals(expected.get("vendor"),actual.get("vendor")),"runtime vendor survives authenticated ingestion");
            }}
            for(Object host:list(environment.get("hosts"))){Map<String,Object> row=obj(host);int expected=h1.equals(row.get("id"))?tomcatCount:jbossCount;verify(number(obj(row.get("counts")).get("datasources"))==expected,"machine cards include correct JDBC count");}
            Map<String,Object> page=ok("GET","/api/state?view=libraries&subset=datasources&environmentId="+env+"&pageSize=1",null,true,null,200);
            verify(number(obj(page.get("pagination")).get("total"))==tomcatCount+jbossCount,"JDBC card drilldown counts datasource records");verify(list(page.get("datasources")).size()==1,"JDBC drilldown remains paginated");verify(list(page.get("libraries")).isEmpty(),"driver library rows do not become datasources");
            verify(call("GET","/api/state?view=machine&hostId="+outside.get("hostId")+"&environmentId="+env,null,true,null).code()==404,"foreign environment machine rejected");
            Map<String,Object> one=obj(list(machine1.get("datasources")).get(0));
            Map<String,Object> item=ok("GET","/api/item?kind=datasources&id="+java.net.URLEncoder.encode((String)one.get("id"),java.nio.charset.StandardCharsets.UTF_8)+"&hostId="+h1,null,true,null,200);
            verify("configuration".equals(obj(item.get("item")).get("evidenceLevel")),"datasource detail retains configuration provenance");
            String serialized=Json.stringify(map("global",global,"environment",environment,"tomcat",machine1,"jboss",machine2,"item",item));
            for(String forbidden:List.of("PRIVATE_","jdbc:postgresql:","jdbc:h2:","jdbc:mysql:","jdbc:oracle:"))verify(!serialized.contains(forbidden),"no credential/connection URL in inventory: "+forbidden);
            Files.writeString(evidence.resolve("overview-fixtures.json"),serialized+"\n");
            stop();start();counts(view("&environmentId="+env),tomcatCount+jbossCount,"after database restart");
            // Newer empty evidence is authoritative; an old delayed report must not replace it.
            Map<String,Object> empty=obj(Json.parse(Json.stringify(tomcat)));empty.put("datasources",List.of());
            send(h1,token1,empty,"jdbc-tomcat-event-0002",Instant.now().minusSeconds(10).toString());counts(view("&hostId="+h1),0,"new empty snapshot");counts(view("&environmentId="+env),jbossCount,"remaining JBoss inventory");
            send(h1,token1,tomcat,"jdbc-tomcat-event-0003",t0);counts(view("&hostId="+h1),0,"older delayed snapshot cannot restore stale data");
            send(h1,token1,tomcat,"jdbc-tomcat-event-0004",Instant.now().toString());counts(view("&environmentId="+env),tomcatCount+jbossCount,"fresh rescan restores observed rows");
            Files.writeString(evidence.resolve("jdbc-overview-http.json"),Json.stringify(map("status","PASS","checks",checks,"version",DashboardVersion.VERSION,"tomcatDatasources",tomcatCount,"jbossDatasources",jbossCount,"authenticatedHttp",true,"realSqlite",true,"collectorGeneratedRows",true,"remoteOperationsExecuted",false))+"\n");
            System.out.println("JdbcOverviewHttpTest PASS: "+checks+" checks; actual collector rows, authenticated HTTP/SQLite, all scopes, restart, fresh/empty/stale snapshots and safe details.");
        }finally{stop();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
