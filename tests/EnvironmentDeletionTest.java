package assessment;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;

/** Isolated HTTP tests for exact-name deletion and atomic enrollment invalidation. */
public final class EnvironmentDeletionTest {
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static Map map(Object... values){return AssessmentServer.map(values);}
    static Object[] call(String method,String path,Object body,boolean admin,String token)throws Exception{
        if(!"DELETE".equals(method))return EnrollmentTest.call(method,path,body,admin,token);
        // Java 6 HttpURLConnection rejects DELETE output; send the actual HTTP/1.1 JSON
        // request over a loopback socket so the server's DELETE body parser is exercised.
        URL url=new URL(ServerTest.base);byte[] payload=Json.stringify(body).getBytes("UTF-8");
        Socket socket=new Socket();socket.connect(new InetSocketAddress(url.getHost(),url.getPort()),3000);socket.setSoTimeout(5000);
        try{
            StringBuilder headers=new StringBuilder("DELETE ").append(path).append(" HTTP/1.1\r\nHost: ").append(url.getAuthority()).append("\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");
            if(admin)headers.append("Cookie: ").append(ServerTest.cookie).append("\r\nX-CSRF-Token: ").append(ServerTest.csrf).append("\r\n");
            if(token!=null)headers.append("Authorization: Bearer ").append(token).append("\r\n");
            OutputStream out=socket.getOutputStream();out.write(headers.append("\r\n").toString().getBytes("ISO-8859-1"));out.write(payload);out.flush();
            ByteArrayOutputStream response=new ByteArrayOutputStream();InputStream in=socket.getInputStream();byte[] buffer=new byte[2048];int count;
            while((count=in.read(buffer))!=-1){response.write(buffer,0,count);if(response.size()>1048576)throw new IOException("Fixture response exceeds limit");}
            byte[] bytes=response.toByteArray();int split=-1;for(int i=0;i+3<bytes.length;i++)if(bytes[i]==13&&bytes[i+1]==10&&bytes[i+2]==13&&bytes[i+3]==10){split=i;break;}
            if(split<0)throw new IOException("Incomplete fixture HTTP response");String head=new String(bytes,0,split,"ISO-8859-1");int status=Integer.parseInt(head.substring(0,head.indexOf("\r\n")).split(" ")[1]);
            return new Object[]{Integer.valueOf(status),Json.parse(new String(bytes,split+4,bytes.length-split-4,"UTF-8"))};
        }finally{socket.close();}
    }
    static Map ok(Object[] reply,int expected,String label){return EnrollmentTest.ok(reply,expected,label);}
    static Map view()throws Exception{return EnvironmentTest.view();}
    static Map environment(String id)throws Exception{for(Object value:(List)view().get("environments")){Map e=(Map)value;if(id.equals(e.get("id")))return e;}return null;}
    static Map deletion(String id,String name){return map("environmentId",id,"confirmationName",name);}
    static Object[] remove(String id,String name)throws Exception{return call("DELETE","/api/environments",deletion(id,name),true,null);}
    static String content(File f)throws Exception{return AssessmentServer.read(f,32*1024*1024);}
    static Map registration(String installation,Properties code)throws Exception{return EnvironmentTest.registered(installation,code,"");}
    static String codeStatus(String id)throws Exception{for(Object value:(List)view().get("enrollments")){Map e=(Map)value;if(id.equals(e.get("environmentId")))return (String)e.get("status");}return "missing";}
    public static void main(String[] args)throws Exception{
        File root=File.createTempFile("assessment-environment-delete-test-","");root.delete();new File(root,"conf").mkdirs();ServerTest.root=root;
        Properties config=new Properties();config.setProperty("adminSalt","fixture-salt");config.setProperty("adminPasswordSha256",ServerTest.hex(MessageDigest.getInstance("SHA-256").digest("fixture-saltfixture-password".getBytes("UTF-8"))));config.setProperty("agentToken","fixture-agent-token");config.setProperty("hostId","fixture-host");config.setProperty("publicUrl","https://fixture.invalid");FileOutputStream out=new FileOutputStream(new File(root,"conf/server.properties"));config.store(out,"isolated fixture");out.close();EnvironmentTest.fixturePackage(root);
        try{
            ServerTest.start();ServerTest.login();String name="Produção São Paulo";
            String id=(String)ok(call("POST","/api/environments",map("name",name,"settings",map("maxFiles",2200)),true,null),201,"create target").get("id");
            String other=(String)ok(call("POST","/api/environments",map("name","Keep","settings",map("maxFiles",2300)),true,null),201,"create other").get("id");
            Properties targetCode=EnvironmentTest.bootstrap(id,4,"assessment-producao-sao-paulo.zip"),otherCode=EnvironmentTest.bootstrap(other,4,"assessment-keep.zip");
            Map moved=registration("delete-fixture-install-01",targetCode),kept=registration("delete-fixture-install-02",otherCode);String movedId=(String)moved.get("hostId"),movedToken=(String)moved.get("agentToken"),keptId=(String)kept.get("hostId"),keptToken=(String)kept.get("agentToken");
            check(call("DELETE","/api/environments",deletion(id,name),false,null)[0].equals(401),"anonymous deletion denied");
            check(call("DELETE","/api/environments",deletion(id,name),false,movedToken)[0].equals(401),"collector deletion denied");
            String csrf=ServerTest.csrf;ServerTest.csrf="incorrect";check(remove(id,name)[0].equals(403),"CSRF deletion denied");ServerTest.csrf=csrf;
            for(Object invalid:new Object[]{null,Integer.valueOf(1),"",name.toLowerCase(Locale.ENGLISH),name+" "," "+name,"Producao Sao Paulo"})check(call("DELETE","/api/environments",map("environmentId",id,"confirmationName",invalid),true,null)[0].equals(400),"confirmation must match exact name");
            check(remove("env-does-not-exist",name)[0].equals(404),"unknown environment rejected");
            check(call("GET","/api/environments",null,true,null)[0].equals(405),"unsupported method rejected");
            check(remove(id,name)[0].equals(409),"populated environment cannot be deleted");check(environment(id)!=null&&"active".equals(codeStatus(id)),"blocked deletion preserves environment and enrollment");
            ok(call("PUT","/api/hosts/environment",map("hostId",movedId,"environmentId",other),true,null),200,"move host before delete");
            ok(call("PUT","/api/hosts/settings",map("hostId",movedId,"settings",map("batchSize",13)),true,null),200,"host overrides retained");
            ((List)ServerTest.state().get("runtimes")).add(map("id","delete-fixture-runtime","hostId",movedId,"version","1.6.0_45"));EnvironmentTest.save();
            String inventory=Json.stringify(view().get("runtimes")),movedHost=Json.stringify(EnrollmentTest.host(view(),movedId));
            // Renaming invalidates a stale dialog's confirmation immediately.
            ok(call("PUT","/api/environments",map("environmentId",id,"name",name+" 2"),true,null),200,"rename during confirmation");check(remove(id,name)[0].equals(400),"stale confirmation rejected");
            ok(call("PUT","/api/environments",map("environmentId",id,"name",name),true,null),200,"restore name");
            // Force an atomic state-write failure; there must be no separate registry mutation.
            File stateFile=new File(root,"data/state.json"),registryFile=new File(root,"data/auth-registry.json"),fault=new File(root,"data/state.json.tmp");String beforeState=content(stateFile),beforeRegistry=content(registryFile);check(fault.mkdir(),"create isolated state-write failure");
            check(remove(id,name)[0].equals(500),"persist failure returned");check(beforeState.equals(content(stateFile))&&beforeState.equals(Json.stringify(ServerTest.state())),"persist failure preserves disk and memory");check(beforeRegistry.equals(content(registryFile))&&"active".equals(codeStatus(id)),"persist failure leaves enrollment usable");check(fault.delete(),"remove isolated state-write failure");
            Map retry=registration("delete-fixture-install-01",targetCode);check(movedToken.equals(retry.get("agentToken")),"retry still allowed after failed delete");
            ServerTest.stop();ServerTest.start();ServerTest.login();check(environment(id)!=null,"failed deletion remains after restart");
            Map result=ok(remove(id,name),200,"delete empty environment");check(Boolean.TRUE.equals(result.get("deleted"))&&id.equals(result.get("environmentId")),"deletion response identifies environment");check(environment(id)==null&&"revoked".equals(codeStatus(id)),"environment removed and codes invalidated together");
            check(beforeRegistry.equals(content(registryFile)),"deletion needs only one persistent commit");check(inventory.equals(Json.stringify(view().get("runtimes")))&&movedHost.equals(Json.stringify(EnrollmentTest.host(view(),movedId))),"inventory host identity settings and assignment unchanged");
            check(call("POST","/api/agent/register",EnrollmentTest.registration("delete-fixture-install-01"),false,targetCode.getProperty("enrollmentToken"))[0].equals(403),"deleted package cannot recover moved host by idempotent retry");
            check(call("POST","/api/agent/register",EnrollmentTest.registration("delete-fixture-install-03"),false,targetCode.getProperty("enrollmentToken"))[0].equals(403),"deleted package cannot enroll new host");
            ok(call("GET","/api/agent/settings?hostId="+movedId,null,false,movedToken),200,"moved host operational token retained");ok(call("GET","/api/agent/settings?hostId="+keptId,null,false,keptToken),200,"other host operational token retained");registration("delete-fixture-install-02",otherCode);
            check(EnvironmentTest.download(map("environmentId",id),true)[0].equals(404),"deleted environment download blocked");check(remove(id,name)[0].equals(404),"repeat deletion clearly reports missing");
            String newId=(String)ok(call("POST","/api/environments",map("name",name),true,null),201,"recreate same name").get("id");check(!id.equals(newId),"same name receives fresh identity");
            check(call("POST","/api/agent/register",EnrollmentTest.registration("delete-fixture-install-03"),false,targetCode.getProperty("enrollmentToken"))[0].equals(403),"same-name environment does not reactivate old package");
            ServerTest.stop();ServerTest.start();ServerTest.login();check(environment(id)==null&&environment(newId)!=null&&"revoked".equals(codeStatus(id)),"deletion and revocation survive restart");
            check(call("POST","/api/agent/register",EnrollmentTest.registration("delete-fixture-install-01"),false,targetCode.getProperty("enrollmentToken"))[0].equals(403),"restart does not resurrect registration");ok(call("GET","/api/agent/settings?hostId="+movedId,null,false,movedToken),200,"operational credential survives restart");
            // Deleted enrollment hashes remain bounded and are eligible for pruning at capacity.
            Map registry=(Map)EnrollmentTest.privateField(EnrollmentTest.privateField(ServerTest.app,"registry"),"registry"),codes=(Map)registry.get("enrollments");for(int i=codes.size();i<EnrollmentRegistry.MAX_ENROLLMENTS;i++)codes.put("deleted-fixture-"+i,map("id","deleted-fixture-"+i,"environmentId",id,"status","active","expiresMillis",System.currentTimeMillis()+3600000,"usedCount",0,"maxUses",1));
            EnvironmentTest.bootstrap(other,1,"assessment-keep.zip");Map current=(Map)EnrollmentTest.privateField(EnrollmentTest.privateField(ServerTest.app,"registry"),"registry");check(((Map)current.get("enrollments")).size()<EnrollmentRegistry.MAX_ENROLLMENTS,"deleted enrollment history can be pruned");
            registration("delete-fixture-install-02",otherCode);ok(call("GET","/api/agent/settings?hostId="+movedId,null,false,movedToken),200,"pruning leaves operational tokens intact");
            System.out.println("ENVIRONMENT_DELETION_TESTS=PASS auth csrf exact-name unicode stale-name empty-only moved-host-credentials inventory-preservation atomic-persist-failure revoked-package new-and-retry-registration same-name-recreation restart bounded-history");
        }finally{if(ServerTest.http!=null)ServerTest.stop();ServerTest.erase(root);}
    }
}
