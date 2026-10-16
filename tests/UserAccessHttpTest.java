package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Real HTTP authorization regression. All credentials and inventory are disposable fixtures. */
public final class UserAccessHttpTest {
    private static final String LEGACY="legacy-fixture-secret",ADMIN_PASS="Admin42!",READER_PASS="Read42!x";
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static Path root;private static AssessmentServer app;private static HttpServer http;private static ExecutorService workers;private static String base;
    record Account(String cookie,String csrf,Map<String,Object> user){}
    record Response(int code,String text,String cookie,Map<String,Object> body){}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static Response request(String method,String path,Object body,Account auth,String origin,String bearer)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));
        if(auth!=null){b.header("Cookie",auth.cookie);if(auth.csrf!=null)b.header("X-CSRF-Token",auth.csrf);}
        if(origin!=null)b.header("Origin",origin);if(bearer!=null)b.header("Authorization","Bearer "+bearer);
        if(body!=null)b.header("Content-Type","application/json");
        var r=CLIENT.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
        String text=new String(r.body(),StandardCharsets.UTF_8);Map<String,Object> parsed=Map.of();
        if(r.headers().firstValue("content-type").orElse("").startsWith("application/json"))parsed=obj(Json.parse(text,32*1024*1024));
        return new Response(r.statusCode(),text,r.headers().firstValue("set-cookie").orElse(""),parsed);
    }
    private static Response call(String method,String path,Object body,Account auth)throws Exception{return request(method,path,body,auth,null,null);}
    private static Response expected(int code,String method,String path,Object body,Account auth)throws Exception{
        Response r=call(method,path,body,auth);check(r.code==code,method+" "+path+" expected "+code+" got "+r.code+" "+r.text);return r;
    }
    private static Account account(Response r){check(!r.cookie.isEmpty(),"session cookie issued");check(r.cookie.contains("HttpOnly")&&r.cookie.contains("SameSite=Strict"),"session cookie protection");return new Account(r.cookie.split(";",2)[0],(String)r.body.get("csrfToken"),obj(r.body.get("user")));}
    private static Account login(String email,String password)throws Exception{return account(expected(200,"POST","/api/login",map("email",email,"password",password),null));}
    private static Response create(Account admin,String name,String email,String role)throws Exception{
        Map<String,Object> body=map("name",name,"email",email,"adminPassword",ADMIN_PASS);if(role!=null)body.put("role",role);
        return expected(201,"POST","/api/admin/users",body,admin);
    }
    private static void start()throws Exception{
        app=new AssessmentServer(root.toFile());http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);workers=Executors.newFixedThreadPool(8);
        http.setExecutor(workers);http.createContext("/",app);http.start();base="http://127.0.0.1:"+http.getAddress().getPort();
    }
    private static void stop(){if(http!=null){http.stop(0);workers.shutdownNow();app.close();http=null;}}
    private static void noSecrets(String text,Collection<String> secrets){for(String secret:secrets)check(secret==null||secret.isEmpty()||!text.contains(secret),"response leaked fixture secret or private key: "+(secret.length()>12?secret.substring(0,5)+"…":secret));}
    private static void seedInventory(){app.store.transaction(()->{
        app.store.put("environments",map("id","env-fixture","name","Homologação","settings",map(),"revision","fixture-revision"));
        app.store.put("hosts",map("id","host-fixture","hostname","fixture-node","environmentId","env-fixture","settings",map(),"coverage",map("status","complete","warnings",List.of()),"lastSeen",AssessmentServer.now()));
        app.store.put("identities",map("hostId","host-fixture","agentToken","agent-fixture-credential-only","status","active"));
        app.store.putInventory("runtimes","host-fixture",new ArrayList<>(List.of(map("id","java-fixture","hostId","host-fixture","kind","JRE","inUse",true,"version","21.0.12","javaHome","/usr/lib/jvm/java-21","observedAt",AssessmentServer.now()))));
        return null;
    });}
    public static void main(String[] args)throws Exception{
        root=Files.createTempDirectory("user-access-http-");Files.createDirectories(root.resolve("conf"));
        Properties props=new Properties();props.setProperty("adminSalt","fixture-salt");props.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-salt"+LEGACY));props.setProperty("hostId","unused-host");props.setProperty("agentToken","unused-agent");props.setProperty("seedLegacyHost","false");props.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){props.store(out,"isolated test");}
        List<String> secrets=new ArrayList<>(List.of(LEGACY,ADMIN_PASS,READER_PASS,"agent-fixture-credential-only","passwordHash","password_hash","tokenHash","adminPasswordSha256"));
        try{
            start();seedInventory();long initialRows=app.store.scalar("SELECT count(*) FROM inventory");
            check(Boolean.TRUE.equals(expected(200,"GET","/api/auth/status",null,null).body.get("bootstrapRequired")),"existing install requires protected setup");
            expected(401,"GET","/api/state",null,null);
            expected(401,"POST","/api/login",map("password",LEGACY),null);
            Response cross=request("POST","/api/auth/bootstrap",map("currentPassword",LEGACY,"name","Admin","email","admin@example.com","password",ADMIN_PASS),null,"https://foreign.invalid",null);check(cross.code==403,"cross-origin setup refused");
            Response wrongLegacy=expected(401,"POST","/api/auth/bootstrap",map("currentPassword","wrong","name","Admin","email","admin@example.com","password",ADMIN_PASS),null);
            check("A senha administrativa atual está incorreta.".equals(wrongLegacy.body.get("error")),"bootstrap error identifies the legacy password");
            expected(400,"POST","/api/auth/bootstrap",map("currentPassword",LEGACY,"name","Admin","email","admin@example.com","password","Abc123!"),null);
            ExecutorService race=Executors.newFixedThreadPool(2);
            Response first,second;
            try{
                Callable<Response> bootstrap=()->call("POST","/api/auth/bootstrap",map("currentPassword",LEGACY,"name","Admin Fixture","email","admin@example.com","password",ADMIN_PASS),null);
                var futures=race.invokeAll(List.of(bootstrap,bootstrap));first=futures.get(0).get();second=futures.get(1).get();
            }finally{race.shutdownNow();}
            check((first.code==200||first.code==201)&&second.code==409||(second.code==200||second.code==201)&&first.code==409,"exactly one bootstrap winner");
            Account admin=account(first.code==409?second:first);check("ADMIN".equals(admin.user.get("role")),"bootstrap admin role");
            check(app.store.scalar("SELECT count(*) FROM human_users")==1,"one persisted human user");check(Boolean.TRUE.equals(app.store.metadata("humanAuthActivated")),"persistent downgrade boundary");
            check(app.store.scalar("SELECT count(*) FROM inventory")==initialRows,"bootstrap preserves inventory");
            check(Boolean.FALSE.equals(expected(200,"GET","/api/auth/status",null,null).body.get("bootstrapRequired")),"setup consumed");
            expected(401,"POST","/api/login",map("email","admin@example.com","password",LEGACY),null);
            expected(403,"POST","/api/admin/users",map("name","No CSRF","email","none@example.com","adminPassword",ADMIN_PASS),new Account(admin.cookie,null,admin.user));
            Response wrongReauth=call("POST","/api/admin/users",map("name","No reauth","email","none@example.com"),admin);check(wrongReauth.code==401||wrongReauth.code==403,"admin mutations require password reauthentication");
            // Log in anew so implementations that expire a failed reauthentication remain supported.
            admin=login("ADMIN@EXAMPLE.COM",ADMIN_PASS);
            Response issued=create(admin,"Reader Fixture","reader@example.com",null);Map<String,Object> readerUser=obj(issued.body.get("user"));String readerId=(String)readerUser.get("id"),temporary=(String)issued.body.get("temporaryPassword");
            secrets.add(temporary);check(temporary!=null&&temporary.length()>=20,"random temporary password");check(issued.body.get("expiresAt")!=null,"expiry shown once");check("READER".equals(readerUser.get("role")),"least-privilege default");
            Account firstAccess=login("reader@example.com",temporary);Response session=expected(200,"GET","/api/session",null,firstAccess);check(Boolean.TRUE.equals(session.body.get("mustChangePassword")),"mandatory first access");
            for(String path:List.of("/api/state","/api/item?kind=hosts&id=host-fixture&hostId=host-fixture","/api/export.csv","/api/export","/api/collector/info","/api/admin/users"))expected(403,"GET",path,null,firstAccess);
            expected(403,"POST","/api/jobs",map("hostId","host-fixture"),firstAccess);
            expected(400,"POST","/api/account/password",map("currentPassword",temporary,"newPassword","Abc123!"),firstAccess);
            Response changed=expected(200,"POST","/api/account/password",map("currentPassword",temporary,"newPassword",READER_PASS),firstAccess);Account reader=account(changed);
            check(!reader.cookie.equals(firstAccess.cookie),"password change rotates session");check("READER".equals(reader.user.get("role")),"password change retains role");check(!Boolean.TRUE.equals(changed.body.get("mustChangePassword")),"first-access restriction cleared");
            expected(401,"GET","/api/session",null,firstAccess);
            for(String path:List.of("/api/state?view=overview","/api/state?view=environments","/api/state?view=runtimes","/api/item?kind=hosts&id=host-fixture&hostId=host-fixture","/api/export","/api/export.csv")){
                Response r=expected(200,"GET",path,null,reader);noSecrets(r.text,secrets);
                if(path.startsWith("/api/state")){check(!r.body.containsKey("defaultSettings"),"reader omits defaults");Object codes=r.body.get("enrollments");check(codes==null||codes instanceof List<?> values&&values.isEmpty(),"reader sees no enrollment summaries");}
            }
            String[] adminGets={"/api/collector.zip","/api/collector/info","/api/admin/users","/api/admin/audit","/api/state?view=settings","/api/unknown-sensitive"};
            for(String path:adminGets)expected(403,"GET",path,null,reader);
            String[][] writes={{"POST","/api/jobs"},{"POST","/api/jobs/run-scope"},{"PUT","/api/settings"},{"POST","/api/enrollments"},{"POST","/api/enrollments/revoke"},{"POST","/api/hosts/revoke"},{"PUT","/api/hosts/settings"},{"POST","/api/environments"},{"PUT","/api/environments"},{"DELETE","/api/environments"},{"POST","/api/environments/package"},{"PUT","/api/hosts/environment"},{"POST","/api/hosts/uninstall"},{"POST","/api/hosts/uninstall/cancel"},{"POST","/api/admin/users"},{"PUT","/api/admin/users"},{"POST","/api/admin/users/reset"}};
            for(String[] endpoint:writes)expected(403,endpoint[0],endpoint[1],map(),reader);
            check(app.store.scalar("SELECT count(*) FROM jobs")==0,"denied reader writes have no side effects");
            expected(401,"GET","/api/agent/settings?hostId=host-fixture",null,reader);
            check(request("GET","/api/agent/settings?hostId=host-fixture",null,null,null,"agent-fixture-credential-only").code==200,"agent auth unaffected");
            check(request("GET","/api/admin/users",null,null,null,"agent-fixture-credential-only").code==401,"agent token never human administrator");
            Response duplicate=call("POST","/api/admin/users",map("name","Duplicate","email","READER@EXAMPLE.COM","adminPassword",ADMIN_PASS),admin);check(duplicate.code==409,"normalized email uniqueness");
            expected(409,"PUT","/api/admin/users",map("id",admin.user.get("id"),"name","Admin Fixture","email","admin@example.com","role","READER","enabled",true,"adminPassword",ADMIN_PASS),admin);
            expected(409,"PUT","/api/admin/users",map("id",admin.user.get("id"),"name","Admin Fixture","email","admin@example.com","role","ADMIN","enabled",false,"adminPassword",ADMIN_PASS),admin);
            expected(200,"PUT","/api/admin/users",map("id",readerId,"name","Reader Renamed","email","renamed@example.com","role","READER","enabled",true,"adminPassword",ADMIN_PASS),admin);
            expected(401,"GET","/api/state",null,reader);reader=login("renamed@example.com",READER_PASS);
            expected(200,"PUT","/api/admin/users",map("id",readerId,"name","Reader Renamed","email","renamed@example.com","role","READER","enabled",false,"adminPassword",ADMIN_PASS),admin);
            expected(401,"GET","/api/state",null,reader);
            Response disabled=expected(401,"POST","/api/login",map("email","renamed@example.com","password",READER_PASS),null);
            Response missing=expected(401,"POST","/api/login",map("email","missing@example.com","password",READER_PASS),null);check(disabled.text.equals(missing.text),"generic account login failure");
            expected(200,"PUT","/api/admin/users",map("id",readerId,"name","Reader Renamed","email","renamed@example.com","role","READER","enabled",true,"adminPassword",ADMIN_PASS),admin);
            reader=login("renamed@example.com",READER_PASS);
            Response reset=expected(200,"POST","/api/admin/users/reset",map("id",readerId,"adminPassword",ADMIN_PASS),admin);String replacement=(String)reset.body.get("temporaryPassword");secrets.add(replacement);check(!replacement.equals(temporary),"reset issues distinct credential");
            expected(401,"GET","/api/session",null,reader);expected(401,"POST","/api/login",map("email","renamed@example.com","password",READER_PASS),null);
            Account resetAccess=login("renamed@example.com",replacement);expected(403,"GET","/api/state",null,resetAccess);
            reader=account(expected(200,"POST","/api/account/password",map("currentPassword",replacement,"newPassword",READER_PASS),resetAccess));
            Response users=expected(200,"GET","/api/admin/users",null,admin);noSecrets(users.text,secrets);
            Response audit=expected(200,"GET","/api/admin/audit",null,admin);noSecrets(audit.text,secrets);check(audit.text.contains("Admin Fixture"),"audit attributes authenticated administrator");
            expected(200,"POST","/api/logout",map(),reader);expected(401,"GET","/api/session",null,reader);
            Account beforeRestart=admin;stop();start();expected(401,"GET","/api/session",null,beforeRestart);
            check(Boolean.FALSE.equals(expected(200,"GET","/api/auth/status",null,null).body.get("bootstrapRequired")),"restart does not reopen setup");
            admin=login("admin@example.com",ADMIN_PASS);reader=login("renamed@example.com",READER_PASS);
            expected(200,"GET","/api/state",null,reader);check(app.store.scalar("SELECT count(*) FROM inventory")==initialRows,"inventory retained across migration and restart");
            check(app.store.scalar("SELECT count(*) FROM human_users")==2,"user accounts persisted");
            expected(409,"POST","/api/auth/bootstrap",map("currentPassword",LEGACY,"name","Intruder","email","another@example.com","password",ADMIN_PASS),null);
            // Revocation is enforced by the server when privileges change, even for an already open tab.
            expected(200,"PUT","/api/admin/users",map("id",readerId,"name","Reader Renamed","email","renamed@example.com","role","ADMIN","enabled",true,"adminPassword",ADMIN_PASS),admin);
            expected(401,"GET","/api/session",null,reader);reader=login("renamed@example.com",READER_PASS);
            expected(200,"GET","/api/admin/users",null,reader);
            expected(200,"PUT","/api/admin/users",map("id",readerId,"name","Reader Renamed","email","renamed@example.com","role","READER","enabled",true,"adminPassword",ADMIN_PASS),admin);
            expected(401,"GET","/api/admin/users",null,reader);reader=login("renamed@example.com",READER_PASS);
            expected(403,"GET","/api/admin/users",null,reader);
            // Expire a disposable temporary account and confirm both login and its existing restricted session fail.
            Response expiring=create(admin,"Expiring Fixture","expiring@example.com","READER");
            String expiringId=(String)obj(expiring.body.get("user")).get("id"),expiringPassword=(String)expiring.body.get("temporaryPassword");
            Account expiringSession=login("expiring@example.com",expiringPassword);
            app.store.exec("UPDATE human_users SET temporary_expires=? WHERE id=?",System.currentTimeMillis()-1,expiringId);
            expected(401,"GET","/api/session",null,expiringSession);
            expected(401,"POST","/api/login",map("email","expiring@example.com","password",expiringPassword),null);
            // Exercise idle and absolute expiry without sleeping; this manipulates only disposable fixture memory.
            var sessionField=AssessmentServer.class.getDeclaredField("sessions");sessionField.setAccessible(true);
            Map<?,?> sessionMap=(Map<?,?>)sessionField.get(app);Object liveSession=sessionMap.get(reader.cookie.substring(reader.cookie.indexOf('=')+1));
            var lastSeen=liveSession.getClass().getDeclaredField("lastSeen");lastSeen.setAccessible(true);
            long idleBefore=System.currentTimeMillis()-10*60000L;lastSeen.setLong(liveSession,idleBefore);
            var background=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/state")).header("Cookie",reader.cookie).header("X-Assessment-Background","1").GET().build(),HttpResponse.BodyHandlers.discarding());
            check(background.statusCode()==200&&lastSeen.getLong(liveSession)==idleBefore,"background refresh never renews user inactivity");
            expected(200,"GET","/api/state",null,reader);check(lastSeen.getLong(liveSession)>idleBefore,"foreground request renews activity");
            lastSeen.setLong(liveSession,System.currentTimeMillis()-31*60000L);
            expected(401,"GET","/api/state",null,reader);reader=login("renamed@example.com",READER_PASS);
            liveSession=sessionMap.get(reader.cookie.substring(reader.cookie.indexOf('=')+1));var expires=liveSession.getClass().getDeclaredField("expires");expires.setAccessible(true);expires.setLong(liveSession,System.currentTimeMillis()-1);
            expected(401,"GET","/api/state",null,reader);
            Response finalAudit=expected(200,"GET","/api/admin/audit",null,admin);check(finalAudit.text.contains("LOGIN_FAILED"),"failed login audit survives transaction rollback");noSecrets(finalAudit.text,secrets);
            for(int attempt=0;attempt<5;attempt++)expected(401,"POST","/api/login",map("email","throttle@example.com","password","wrong"),null);
            expected(429,"POST","/api/login",map("email","throttle@example.com","password","wrong"),null);
            System.out.println("USER_ACCESS_HTTP_TESTS=PASS bootstrap-race migration inventory-retained role-matrix first-access csrf origin password-rotation immediate-revocation last-admin email-normalization reauth generic-errors agent-separation secret-redaction restart audit logout role-revocation temporary-expiry idle-expiry absolute-expiry background-no-renewal throttling");
        }finally{stop();try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
