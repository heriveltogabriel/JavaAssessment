package assessment;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.io.*;
import static assessment.AssessmentServer.map;

public final class EnvironmentInstallHttpTest {
    static int checks; static AssessmentServer app;static HttpServer server;static ExecutorService pool;static String base;static Path root;
    static HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    record Account(String cookie,String csrf){}
    record Result(int status,byte[] data,String cookie,String cache){Map<String,Object> json(){return obj(Json.parse(new String(data,StandardCharsets.UTF_8)));}}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
    static Result request(String method,String path,Object body,Account account,String token)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path));if(account!=null){b.header("Cookie",account.cookie);if(account.csrf!=null)b.header("X-CSRF-Token",account.csrf);}if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.header("Content-Type","application/json");var r=client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),HttpResponse.BodyHandlers.ofByteArray());
        return new Result(r.statusCode(),r.body(),r.headers().firstValue("set-cookie").orElse(""),r.headers().firstValue("cache-control").orElse(""));
    }
    static Result expect(int code,String method,String path,Object body,Account account,String token)throws Exception{Result r=request(method,path,body,account,token);check(r.status==code,method+" "+path+": "+r.status+" "+(r.status==200?"":new String(r.data)));return r;}
    static Account login(String email,String pass)throws Exception{var r=expect(200,"POST","/api/login",map("email",email,"password",pass),null,null);return new Account(r.cookie.split(";",2)[0],(String)r.json().get("csrfToken"));}
    static String bearer(Map<String,Object> state){String script=(String)obj(state.get("commands")).get("curl");var matcher=java.util.regex.Pattern.compile("Bearer (eia1_[a-f0-9]{64})").matcher(script);check(matcher.find(),"scoped credential in command");return matcher.group(1);}
    static String enrollment(byte[] zip)throws Exception{try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(zip))){ZipEntry e;while((e=z.getNextEntry())!=null)if(e.getName().equals("conf/bootstrap.properties")){Properties p=new Properties();p.load(new ByteArrayInputStream(z.readAllBytes()));return p.getProperty("enrollmentToken");}}throw new AssertionError("missing bootstrap");}
    static void start()throws Exception{app=new AssessmentServer(root.toFile());server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);pool=Executors.newFixedThreadPool(4);server.setExecutor(pool);server.createContext("/",app);server.start();base="http://127.0.0.1:"+server.getAddress().getPort();}
    static void stop(){server.stop(0);pool.shutdownNow();app.close();}
    static Map<String,Object> registration(String id){return map("installationId",id,"hostname","test-machine","collectorVersion","2.0.23","javaVersion","17","osName","Linux","architecture","x86_64");}
    public static void main(String[] args)throws Exception{
        root=Files.createTempDirectory("environment-install-http-");Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));
        Properties props=new Properties();props.setProperty("adminSalt","fixture");props.setProperty("adminPasswordSha256",InventoryStore.hash("fixtureold-secret"));props.setProperty("hostId","unused-host");props.setProperty("agentToken","unused-agent");props.setProperty("seedLegacyHost","false");props.setProperty("publicUrl","https://fixture.invalid");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){props.store(out,"test");}
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(root.resolve("dist/assessment-collector.zip")))){
            var entries=Map.of("install.sh","#!/bin/sh\n".getBytes(),"system-install.sh",new byte[0],"bootstrap-config.sh",new byte[0],"collector.jar",new byte[0],"conf/ca.pem",Files.readAllBytes(Path.of("collector/public/ca.pem")),"DEPENDENCIES.json","{\"version\":\"2.0.23\"}".getBytes());
            for(var e:entries.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}
        }
        // Release gates pass the final ZIP, after the signed full-update payload is embedded.
        if(args.length==1)Files.copy(Path.of(args[0]),root.resolve("dist/assessment-collector.zip"),StandardCopyOption.REPLACE_EXISTING);
        start();try{
            app.auth.bootstrap("Admin","admin@example.com","Admin42!");Account admin=login("admin@example.com","Admin42!");
            var reader=app.auth.create(app.auth.authenticate("admin@example.com","Admin42!"),"Reader","reader@example.com","READER");
            Account readerAccount=login("reader@example.com",reader.password());
            var changed=expect(200,"POST","/api/account/password",map("currentPassword",reader.password(),"newPassword","Reader42!"),readerAccount,null);
            readerAccount=new Account(changed.cookie.split(";",2)[0],(String)changed.json().get("csrfToken"));
            String id=(String)expect(201,"POST","/api/environments",map("name","Production","settings",map()),admin,null).json().get("id");
            String adminPath="/api/environments/install-access",path=adminPath+"?environmentId="+id,download="/api/install/package?environmentId="+id;
            expect(401,"GET",path,null,null,null);expect(403,"GET",path,null,readerAccount,null);
            var state=expect(200,"GET",path,null,admin,null);check(state.cache.equals("no-store"),"sensitive command not cached");check(Boolean.TRUE.equals(state.json().get("enabled"))&&state.json().get("expiresAt")==null,"permanent access created with environment");
            String token=bearer(state.json());
            expect(403,"GET",download,null,null,null);expect(403,"GET",download,null,admin,null);expect(403,"GET",download,null,null,"wrong");
            expect(400,"GET",download+"&token="+token,null,null,token);expect(401,"GET","/api/state",null,null,token);
            expect(401,"POST","/api/agent/register",registration("installation-first-test"),null,token);
            long beforeEnrollments=app.store.scalar("SELECT count(*) FROM enrollments");Path artifact=root.resolve("dist/assessment-collector.zip"),saved=root.resolve("dist/saved.zip");Files.move(artifact,saved);
            expect(503,"GET",download,null,null,token);check(app.store.scalar("SELECT count(*) FROM enrollments")==beforeEnrollments,"package validation failure rolls back enrollment");Files.move(saved,artifact);
            String code=enrollment(expect(200,"GET",download,null,null,token).data);
            var registered=expect(200,"POST","/api/agent/register",registration("installation-first-test"),null,code).json();String hostId=(String)registered.get("hostId"),agentToken=(String)registered.get("agentToken");
            expect(200,"POST","/api/agent/register",registration("installation-second-test"),null,code);
            expect(403,"POST",adminPath,map("environmentId",id,"action","renew"),new Account(admin.cookie,null),null);
            expect(403,"POST",adminPath,map("environmentId",id,"action","revoke"),readerAccount,null);
            String manual=enrollment(expect(200,"POST","/api/environments/package",map("environmentId",id),admin,null).data);
            String renewed=bearer(expect(200,"POST",adminPath,map("environmentId",id,"action","renew"),admin,null).json());check(!renewed.equals(token),"rotation changes bearer");
            expect(403,"GET",download,null,null,token);expect(403,"POST","/api/agent/register",registration("installation-third-test"),null,code);
            expect(200,"POST","/api/agent/register",registration("installation-manual-test"),null,manual);
            expect(200,"POST","/api/agent/heartbeat",map("hostId",hostId,"collectorVersion","2.0.23"),null,agentToken);
            var revoked=expect(200,"POST",adminPath,map("environmentId",id,"action","revoke"),admin,null).json();check(!revoked.containsKey("commands")&&!Boolean.TRUE.equals(revoked.get("enabled")),"revocation hides command");
            expect(403,"GET",download,null,null,renewed);expect(200,"POST","/api/agent/heartbeat",map("hostId",hostId),null,agentToken);
            expect(200,"GET",path,null,admin,null); // Reading does not reactivate.
            expect(403,"GET",download,null,null,renewed);
            for(String route:List.of("/api/state","/api/export","/api/admin/audit")){String text=new String(expect(200,"GET",route,null,admin,null).data);check(!text.contains(token)&&!text.contains(renewed)&&!text.contains("environmentInstallAccess:"),"no credential in inventory/export/audit");}
            app.store.put("environments",map("id","env-existing","name","Existing","settings",map()));
            check(!Boolean.TRUE.equals(expect(200,"GET",adminPath+"?environmentId=env-existing",null,admin,null).json().get("configured")),"existing env not silently activated");
            var existing=expect(200,"POST",adminPath,map("environmentId","env-existing","action","enable"),admin,null).json();String existingToken=bearer(existing);
            expect(403,"GET",download,null,null,existingToken);
            expect(200,"DELETE","/api/environments",map("environmentId","env-existing","confirmationName","Existing"),admin,null);
            expect(404,"GET","/api/install/package?environmentId=env-existing",null,null,existingToken);
            stop();start();admin=login("admin@example.com","Admin42!");check(!Boolean.TRUE.equals(expect(200,"GET",path,null,admin,null).json().get("enabled")),"revocation survives restart");
            System.out.println("EnvironmentInstallHttpTest PASS "+checks+" checks: admin/CSRF isolation, ZIP, batch, permanent command, rotation, live agents, traditional download, secret exclusion, restart.");
        }finally{stop();}
    }
}
