package assessment;
import com.sun.net.httpserver.*;
import java.net.*; import java.io.*; import java.util.*; import java.util.concurrent.*; import java.security.*; import java.lang.reflect.*;
/** Isolated service fixtures: never contact or seed the actual dashboard. */
public class ServerTest {
 static String base,cookie,csrf; static HttpServer http; static ExecutorService pool; static File root; static AssessmentServer app;
 static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
 static Map map(Object...v){return AssessmentServer.map(v);}
 static String hex(byte[] v){StringBuilder b=new StringBuilder();for(byte x:v)b.append(String.format("%02x",x&255));return b.toString();}
 static Object[] call(String method,String path,Object data,boolean admin,boolean agent)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(3000);c.setReadTimeout(3000);
  if(admin&&cookie!=null){c.setRequestProperty("Cookie",cookie);c.setRequestProperty("X-CSRF-Token",csrf);}if(agent)c.setRequestProperty("Authorization","Bearer fixture-agent-token");
  if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] raw=Json.stringify(data).getBytes("UTF-8");c.getOutputStream().write(raw);c.getOutputStream().close();}
  int status=c.getResponseCode();InputStream in=status<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(in!=null){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);in.close();}
  String set=c.getHeaderField("Set-Cookie");if(set!=null&&path.equals("/api/login")&&status==200)cookie=set.split(";",2)[0];
  Map body=(Map)Json.parse(new String(bytes.toByteArray(),"UTF-8"));c.disconnect();return new Object[]{Integer.valueOf(status),body};
 }
 static Map state()throws Exception{Field f=AssessmentServer.class.getDeclaredField("state");f.setAccessible(true);return (Map)f.get(app);}
 static void start()throws Exception{app=new AssessmentServer(root);http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);http.createContext("/",app);pool=Executors.newFixedThreadPool(4);http.setExecutor(pool);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();cookie=null;csrf=null;}
 static void stop(){http.stop(0);pool.shutdownNow();}
 static void login()throws Exception{Object[] r=call("POST","/api/login",map("password","fixture-password"),false,false);check(r[0].equals(200),"login");csrf=(String)((Map)r[1]).get("csrfToken");}
 static void erase(File f){if(f.isDirectory())for(File c:f.listFiles())erase(c);f.delete();}
 public static void main(String[] args)throws Exception{
  root=File.createTempFile("assessment-server-test-","");root.delete();new File(root,"conf").mkdirs();Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",hex(MessageDigest.getInstance("SHA-256").digest("fixture-saltfixture-password".getBytes("UTF-8"))));p.setProperty("agentToken","fixture-agent-token");p.setProperty("hostId","fixture-host");FileOutputStream out=new FileOutputStream(new File(root,"conf/server.properties"));p.store(out,"isolated fixtures");out.close();
  try{
   start();check(call("GET","/api/state",null,false,false)[0].equals(401),"anonymous blocked");login();
   Object[] job=call("POST","/api/jobs",map("hostId","fixture-host"),true,false);check(job[0].equals(202),"queue");String id=(String)((Map)job[1]).get("id");check(call("POST","/api/jobs",map("hostId","fixture-host"),true,false)[0].equals(409),"overlap");
   Object[] poll=call("GET","/api/agent/poll?hostId=fixture-host",null,false,true);check(id.equals(((Map)poll[1]).get("jobId")),"lease");
   stop();start();login();check("FAILED".equals(((Map)((List)state().get("jobs")).get(0)).get("status")),"restart records interrupted job");
   Map snap=map("schemaVersion","1.0","eventId","fixture-event-0001","jobId",id,"capturedAt",AssessmentServer.now(),"collectorJavaVersion","fixture","host",map("id","fixture-host","hostname","fixture"),"coverage",map("status","complete","warnings",new ArrayList(),"stats",map()));
   for(String k:new String[]{"runtimes","processes","servers","libraries","applications","datasources"})snap.put(k,new ArrayList());
   check(call("POST","/api/agent/results",snap,false,true)[0].equals(200),"late result after restart");Object[] duplicate=call("POST","/api/agent/results",snap,false,true);check(Boolean.TRUE.equals(((Map)duplicate[1]).get("duplicate")),"idempotency");
   check(Boolean.TRUE.equals(((Map)((List)state().get("jobs")).get(0)).get("lateResult")),"late result provenance");
   Map settings=(Map)state().get("settings");check(Boolean.FALSE.equals(settings.get("enabled")),"default recurrence off");
   check(call("PUT","/api/settings",map("includePaths",Arrays.asList("/")),true,false)[0].equals(400),"bounded paths");
   check(call("PUT","/api/settings",map("enabled",true,"frequency","daily","windowStart","00:00","windowEnd","00:00"),true,false)[0].equals(200),"schedule settings");
   Object[] first=call("GET","/api/agent/poll?hostId=fixture-host",null,false,true);check(((Map)first[1]).get("jobId")!=null,"schedule queues");check(((Map)call("GET","/api/agent/poll?hostId=fixture-host",null,false,true)[1]).get("jobId")==null,"schedule no overlap");
   check(((List)state().get("jobs")).size()==2,"one daily slot");
   System.out.println("SERVER_TESTS=PASS auth queue overlap persistence late-retry idempotency settings daily-schedule");
  } finally {if(http!=null)stop();erase(root);}
 }
}
