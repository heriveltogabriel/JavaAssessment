package assessment;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static assessment.AssessmentServer.map;

/** Isolated SQLite lifecycle, enrollment invalidation and key protection regression. */
public final class EnvironmentInstallAccessTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Operation {void run()throws Exception;}
    static void failure(int status,Operation operation)throws Exception{try{operation.run();throw new AssertionError("Expected failure "+status);}catch(EnvironmentInstallAccess.Failure error){check(error.status==status,"install access failure "+status);}catch(EnrollmentRegistry.Failure error){check(error.status==status,"enrollment failure "+status);}}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    static String token(Map<String,Object> issued){return String.valueOf(issued.get("enrollmentToken"));}
    static void remove(Path path)throws Exception{try(var paths=Files.walk(path)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("install-access-test-");String savedCredential;UserAuth.User savedAdmin;
        try{
            try(InventoryStore store=new InventoryStore(root.toFile(),map(),"","")){
                UserAuth auth=new UserAuth(store);UserAuth.User admin=auth.bootstrap("Admin","admin@example.test","Testing-password-42");savedAdmin=admin;
                store.put("environments",map("id","env-a","name","Environment A"));store.put("environments",map("id","env-b","name","Environment B"));
                Set<String> ids=Set.of("env-a","env-b");EnrollmentRegistry registry=new EnrollmentRegistry(store);EnvironmentInstallAccess access=new EnvironmentInstallAccess(store,root);
                check(Boolean.FALSE.equals(access.status("env-a").get("configured")),"old environment not auto enabled by GET");
                failure(403,()->access.credential("env-a"));check(!Files.exists(root.resolve(".environment-install.key")),"GET does not create key");
                failure(403,()->access.create("env-a",null));access.create("env-a",admin);String first=access.credential("env-a");
                check(first.matches("eia1_[a-f0-9]{64}"),"opaque fixed length bearer");check(Files.getPosixFilePermissions(root.resolve(".environment-install.key")).equals(PosixFilePermissions.fromString("rw-------")),"key mode0600");
                check(access.status("env-a").get("expiresAt")==null,"permanent credential");access.create("env-a",admin);check(first.equals(access.credential("env-a")),"create idempotent");access.create("env-b",admin);
                check(!first.equals(access.credential("env-b")),"environment-specific HMAC");failure(403,()->access.issueDownload("env-b",first,registry,ids));failure(403,()->access.issueDownload("env-a","wrong",registry,ids));
                Map<String,Object> traditional=registry.issue("Manual download",1440,1000,map(),true,"env-a",ids);
                Map<String,Object> downloaded=access.issueDownload("env-a",first,registry,ids);Map<String,Object> enrollment=store.get("enrollments",String.valueOf(obj(downloaded.get("enrollment")).get("id")));
                check(enrollment.get("installAccessGeneration")!=null,"generation linked to enrollment");check(((Number)enrollment.get("maxUses")).intValue()==EnrollmentRegistry.MAX_USES_PER_CODE,"batch maximum");check(((Number)enrollment.get("expiresMillis")).longValue()>System.currentTimeMillis()+86390000L,"24h enrollment");
                Map<String,Object> host1=registry.register(token(downloaded),"install-one",map(),ids),host2=registry.register(token(downloaded),"install-two",map(),ids);check(!host1.get("hostId").equals(host2.get("hostId")),"batch enrolls separate identities");
                access.renew("env-a",admin);String second=access.credential("env-a");check(!first.equals(second),"renew changes credential");failure(403,()->access.issueDownload("env-a",first,registry,ids));failure(403,()->registry.register(token(downloaded),"install-three",map(),ids));
                check(host1.get("hostId").equals(registry.authenticate(String.valueOf(host1.get("agentToken")))),"renew preserves registered agents");check(registry.register(token(traditional),"manual-one",map(),ids)!=null,"traditional ZIP unaffected");
                Map<String,Object> next=access.issueDownload("env-a",second,registry,ids);access.revoke("env-a",admin);failure(403,()->access.credential("env-a"));failure(403,()->registry.register(token(next),"install-four",map(),ids));check(registry.authenticate(String.valueOf(host2.get("agentToken")))!=null,"revoke preserves agent auth");
                access.create("env-a",admin);check(Boolean.FALSE.equals(access.status("env-a").get("enabled")),"create never reactivates revoked access");access.renew("env-a",admin);
                String current=access.credential("env-a");
                // A batch can exceed the historic500-enrollment cap without consuming manual quota.
                store.transaction(()->{for(int i=0;i<501;i++)access.issueDownload("env-a",current,registry,ids);return null;});
                check(registry.issue("Manual after batch",1440,1,map(),true,"env-a",ids)!=null,"large batch preserves manual quota");
                check(registry.summaries(ids).size()==2,"generated enrollments do not crowd manual listing");
                check(((Number)store.get("enrollments",String.valueOf(obj(traditional.get("enrollment")).get("id"))).get("usedCount")).longValue()==1,"manual counter retained");
                Map<String,Object> atLimit=obj(store.metadata(EnvironmentInstallAccess.PREFIX+"env-a"));atLimit.put("downloadCount",EnvironmentInstallAccess.DOWNLOADS_PER_HOUR);store.metadata(EnvironmentInstallAccess.PREFIX+"env-a",atLimit);failure(429,()->access.issueDownload("env-a",current,registry,ids));
                Map<String,Object> rate=obj(store.metadata(EnvironmentInstallAccess.PREFIX+"env-a"));rate.put("downloadWindow",System.currentTimeMillis()-3600001L);store.metadata(EnvironmentInstallAccess.PREFIX+"env-a",rate);check(access.issueDownload("env-a",current,registry,ids)!=null,"rate window recovers");
                String exported=Json.stringify(access.status("env-a"))+Json.stringify(auth.events())+Json.stringify(store.rows("SELECT payload FROM metadata"));check(!exported.contains(first)&&!exported.contains(current)&&!exported.contains(token(downloaded)),"status audit metadata have no bearers or enrollment tokens");
                UserAuth.User stale=admin;store.exec("UPDATE human_users SET revision=revision+1 WHERE id=?",admin.id());failure(403,()->access.renew("env-a",stale));savedAdmin=auth.find(admin.id());
                // Both operations serialize. Whichever wins, the returned old-generation ZIP cannot register after revoke.
                access.renew("env-b",savedAdmin);String concurrent=access.credential("env-b");ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);UserAuth.User actor=savedAdmin;
                Future<Map<String,Object>> issuance=pool.submit(()->{gate.await();try{return access.issueDownload("env-b",concurrent,registry,ids);}catch(EnvironmentInstallAccess.Failure expected){check(expected.status==403,"concurrent revoke before issue");return null;}});Future<?> revocation=pool.submit(()->{gate.await();access.revoke("env-b",actor);return null;});gate.countDown();Map<String,Object> raced=issuance.get(10,TimeUnit.SECONDS);revocation.get(10,TimeUnit.SECONDS);pool.shutdownNow();if(raced!=null)failure(403,()->registry.register(token(raced),"race",map(),ids));
                savedCredential=current;
            }
            try(InventoryStore store=new InventoryStore(root.toFile(),map(),"","")){
                UserAuth auth=new UserAuth(store);EnvironmentInstallAccess access=new EnvironmentInstallAccess(store,root);EnrollmentRegistry registry=new EnrollmentRegistry(store);Set<String> ids=Set.of("env-a","env-b");check(savedCredential.equals(access.credential("env-a")),"restart same credential");
                Map<String,Object> issued=access.issueDownload("env-a",savedCredential,registry,ids);store.delete("environments","env-a");failure(404,()->access.credential("env-a"));failure(404,()->access.issueDownload("env-a",savedCredential,registry,ids));failure(403,()->registry.register(token(issued),"deleted",map(),ids));
                // Missing key must never silently rekey existing records.
                Path key=root.resolve(".environment-install.key"),backup=root.resolve("backup-key");Files.move(key,backup);UserAuth.User actor=auth.find(savedAdmin.id());failure(503,()->access.renew("env-b",actor));check(!Files.exists(key),"key not recreated after loss");Files.move(backup,key);access.renew("env-b",actor);
                Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r-----"));failure(503,()->access.credential("env-b"));Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));
                Files.createLink(backup,key);failure(503,()->access.credential("env-b"));Files.delete(backup);
                Files.move(key,backup);Files.createSymbolicLink(key,backup);failure(503,()->access.credential("env-b"));Files.delete(key);Files.move(backup,key);
                byte[] original=Files.readAllBytes(key);Files.write(key,new byte[1]);failure(503,()->access.credential("env-b"));Files.write(key,original);Arrays.fill(original,(byte)0);
                check(access.credential("env-b").startsWith("eia1_"),"protected key restored");
            }
        }finally{remove(root);}
        System.out.println("EnvironmentInstallAccessTest PASS: "+checks+" checks; lifecycle/batch/restart/revocation/key/race/traditional ZIP");
    }
}
