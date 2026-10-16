package assessment;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;
public final class DashboardMaintenanceHttpTest {
 static final HttpClient client=HttpClient.newHttpClient();static String base,cookie,csrf;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static HttpResponse<String> call(String method,String path,byte[] body,String auth,String token,String origin)throws Exception{
  var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(15));
  if(auth!=null)b.header("Cookie",auth);if(token!=null)b.header("X-CSRF-Token",token);if(origin!=null)b.header("Origin",origin);
  return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 static byte[] json(Object value){return Json.stringify(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 @SuppressWarnings("unchecked") static Map<String,Object> obj(String text){return (Map<String,Object>)Json.parse(text);}
 public static void main(String[]args)throws Exception{
  Path root=Files.createTempDirectory("dashboard-maintenance-http-").toRealPath();Files.createDirectories(root.resolve("conf"));Path state=root.resolve("maintenance-state");Files.createDirectories(state.resolve("inbox"));
  Files.writeString(state.resolve("status.json"),Json.stringify(map("phase","IDLE")));
  Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));p.setProperty("hostId","unused-host");p.setProperty("agentToken","unused-agent");p.setProperty("seedLegacyHost","false");p.setProperty("publicUrl","https://fixture.invalid");
  try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"fixture");}
  var app=new AssessmentServer(root.toFile(),false,state);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);var pool=Executors.newFixedThreadPool(4);server.setExecutor(pool);server.createContext("/",app);server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
  try{
   check(call("GET","/api/admin/dashboard-update",null,null,null,null).statusCode()==401,"anonymous rejected");
   var login=TestAccounts.login(base);cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];csrf=(String)obj(login.body()).get("csrfToken");
   check(call("GET","/api/admin/dashboard-update",null,cookie,null,null).statusCode()==200,"admin status");
   check(call("POST","/api/admin/dashboard-update/upload",new byte[]{1,2},cookie,null,null).statusCode()==403,"csrf enforced before upload");
   check(call("POST","/api/admin/dashboard-update/upload",new byte[]{1,2},cookie,csrf,"https://wrong.invalid").statusCode()==403,"origin enforced");
   var upload=call("POST","/api/admin/dashboard-update/upload",new byte[]{1,2,3},cookie,csrf,null);check(upload.statusCode()==202,"bounded upload queued "+upload.body());
   String id=(String)obj(upload.body()).get("operationId");check(Arrays.equals(Files.readAllBytes(state.resolve("inbox/"+id+".zip")),new byte[]{1,2,3}),"bytes preserved");
   check(Files.exists(state.resolve("inbox/"+id+".json")),"request published last");
   check(call("POST","/api/admin/dashboard-update/action",json(map("action","confirm","operationId",id,"adminPassword","wrong")),cookie,csrf,null).statusCode()==403,"reauth enforced");
   check(call("POST","/api/admin/dashboard-update/action",json(map("action","confirm","operationId",id,"adminPassword",TestAccounts.PASSWORD)),cookie,csrf,null).statusCode()==202,"confirmation queued");
   check(call("POST","/api/admin/dashboard-update/action",json(map("action","shell","operationId",id,"adminPassword",TestAccounts.PASSWORD)),cookie,csrf,null).statusCode()==400,"arbitrary action rejected");
   var admin=app.auth.authenticate(TestAccounts.EMAIL,TestAccounts.PASSWORD);var reader=app.auth.create(admin,"Reader","reader@example.test","READER");var changed=app.auth.changePassword(reader.user().id(),reader.user().revision(),reader.password(),"reader-password-123");
   var rlogin=call("POST","/api/login",json(map("email","reader@example.test","password","reader-password-123")),null,null,null);String rc=rlogin.headers().firstValue("set-cookie").orElseThrow().split(";")[0];String rt=(String)obj(rlogin.body()).get("csrfToken");
   check(call("GET","/api/admin/dashboard-update",null,rc,null,null).statusCode()==403,"reader status rejected");check(call("POST","/api/admin/dashboard-update/upload",new byte[]{1},rc,rt,null).statusCode()==403,"reader upload rejected");
   Files.writeString(state.resolve("maintenance"),"maintenance");check(call("POST","/api/agent/heartbeat",json(map()),null,null,null).statusCode()==503,"agent mutation gated");check(call("GET","/api/agent/poll",null,null,null,null).statusCode()==503,"agent task gated");check(call("GET","/health",null,null,null,null).statusCode()==200,"health available");check(call("GET","/api/admin/dashboard-update",null,cookie,null,null).statusCode()==200,"status available during maintenance");
   System.out.println("DashboardMaintenanceHttpTest passed: authorization, CSRF, origin, reauthentication, queue, maintenance");
  }finally{server.stop(0);pool.shutdownNow();app.close();try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
 }
}
