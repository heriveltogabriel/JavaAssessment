package assessment;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import static assessment.AssessmentServer.map;
/** Local-only real SQLite/HTTP fixture for browser checks. */
public final class BetaUiFixture {
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory("assessment-beta-ui-");Files.createDirectories(root.resolve("conf"));Files.createSymbolicLink(root.resolve("web"),Paths.get(args[0]).toAbsolutePath());
  Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("agentToken","fixture-token");p.setProperty("hostId","fixture-host");p.setProperty("seedLegacyHost","false");try(OutputStream out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"Isolated beta UI fixture");}
  try(AssessmentServer app=new AssessmentServer(root.toFile())){
   app.store.transaction(()->{for(String id:List.of("a","b"))app.store.put("environments",map("id",id,"name",id.equals("a")?"Production":"Test","settings",map("inspectArchives",true,"maxFiles",id.equals("a")?1700:1900),"revision",1));
    for(String id:List.of("a","b"))app.store.put("enrollments",map("id","enr-"+id,"tokenHash",InventoryStore.hash("fixture-enrollment-"+id),"status","active","expiresMillis",System.currentTimeMillis()+3600000,"environmentId",id,"label",id.equals("a")?"Production bootstrap":"Test bootstrap","maxUses",100,"usedCount",0));
    for(int i=0;i<1000;i++){String id="h"+i,now=AssessmentServer.now();app.store.put("hosts",map("id",id,"hostname",String.format("machine-%04d",i),"environmentId",i<600?"a":"b","distribution","Oracle Linux 9","lastSeen",now,"lastAssessmentAt",now,"settings",map(),"coverage",map("status","complete","lastAssessmentAt",now,"stats",map())));app.store.put("identities",map("hostId",id,"status","active","agentToken","fixture-only-"+id));
     List<Object> runtimes=new ArrayList<>();for(int j=0;j<3;j++)runtimes.add(map("id","r"+j,"hostId",id,"version",j==2?(i==999?null:"21.0."+i):"21.0.8","javaHome","/opt/java/"+j,"observedAt",now));app.store.putInventory("runtimes",id,runtimes);
     app.store.putInventory("applications",id,List.of(map("id","app","hostId",id,"name","Example app","type","Spring Boot","state","PROCESS_RUNNING","observedAt",now)));
     app.store.putInventory("servers",id,List.of(map("id","server","hostId",id,"name","Tomcat","type","Tomcat","state","RUNNING","observedAt",now)));
     app.store.putInventory("libraries",id,List.of(map("id","library","hostId",id,"name","JDBC library","category","JDBC","usage","found","applicationId","app","applicationName","Example app","observedAt",now)));
     app.store.putInventory("datasources",id,List.of(map("id","datasource","hostId",id,"name","Orders datasource","applicationId","app","applicationName","Example app","state","CONFIGURED","evidenceLevel","configuration","observedAt",now)));
    }return null;});
   HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(4);http.createContext("/",app);http.setExecutor(pool);http.start();System.out.println("BETA_UI_URL=http://127.0.0.1:"+http.getAddress().getPort());System.out.flush();try{System.in.read();}finally{http.stop(0);pool.shutdownNow();}
  }finally{try(var paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(file->{try{Files.delete(file);}catch(IOException ignored){}});}}
 }
}
