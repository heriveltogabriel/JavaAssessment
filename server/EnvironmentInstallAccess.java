package assessment;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static assessment.AssessmentServer.map;

/** Permanent environment-scoped download access; no bearer is stored in SQLite. */
final class EnvironmentInstallAccess {
    static final String PREFIX="environmentInstallAccess:";
    static final int DOWNLOADS_PER_HOUR=1200;
    static final class Failure extends RuntimeException { final int status; Failure(int status,String message){super(message);this.status=status;} }
    private final InventoryStore store;
    private final Path directory,keyFile;
    private final SecureRandom random=new SecureRandom();
    EnvironmentInstallAccess(InventoryStore store,Path dataDirectory){this.store=store;directory=dataDirectory.toAbsolutePath().normalize();keyFile=directory.resolve(".environment-install.key");}
    @SuppressWarnings("unchecked") private Map<String,Object> record(String id){Object value=store.metadata(PREFIX+id);return value instanceof Map?(Map<String,Object>)value:null;}
    private static String text(Object value){return value==null?"":String.valueOf(value);}
    private static long number(Object value){return value instanceof Number?((Number)value).longValue():0;}
    private Map<String,Object> environment(String id){if(id==null||id.isEmpty()||id.length()>100)throw new Failure(404,"Environment not found");Map<String,Object> env=store.get("environments",id);if(env==null)throw new Failure(404,"Environment not found");return env;}
    private void administrator(UserAuth.User actor){if(actor==null||!actor.administrator()||store.scalar("SELECT count(*) FROM human_users WHERE id=? AND enabled=1 AND role='ADMIN' AND must_change=0 AND revision=?",actor.id(),actor.revision())!=1)throw new Failure(403,"Administrator access required");}
    Map<String,Object> status(String id){return store.transaction(()->{environment(id);Map<String,Object> r=record(id);return map("environmentId",id,"configured",r!=null,"enabled",r!=null&&Boolean.TRUE.equals(r.get("enabled")),"createdAt",r==null?null:r.get("createdAt"),"updatedAt",r==null?null:r.get("updatedAt"),"expiresAt",null);});}
    void create(String id,UserAuth.User actor){store.transaction(()->{administrator(actor);Map<String,Object> env=environment(id);if(record(id)!=null)return null;byte[] key=key(true);Arrays.fill(key,(byte)0);String now=AssessmentServer.now();store.metadata(PREFIX+id,map("generation",generation(),"enabled",true,"createdAt",now,"updatedAt",now));audit(actor,"ENVIRONMENT_INSTALL_ACCESS_CREATED",id,text(env.get("name")));return null;});}
    void renew(String id,UserAuth.User actor){store.transaction(()->{administrator(actor);Map<String,Object> env=environment(id);byte[] key=key(true);Arrays.fill(key,(byte)0);Map<String,Object> r=record(id);String now=AssessmentServer.now();store.metadata(PREFIX+id,map("generation",generation(),"enabled",true,"createdAt",r==null?now:r.get("createdAt"),"updatedAt",now));invalidateEnrollments(id);audit(actor,"ENVIRONMENT_INSTALL_ACCESS_RENEWED",id,text(env.get("name")));return null;});}
    void revoke(String id,UserAuth.User actor){store.transaction(()->{administrator(actor);Map<String,Object> env=environment(id),r=record(id);if(r!=null&&Boolean.TRUE.equals(r.get("enabled"))){r.put("enabled",false);r.put("updatedAt",AssessmentServer.now());store.metadata(PREFIX+id,r);invalidateEnrollments(id);audit(actor,"ENVIRONMENT_INSTALL_ACCESS_REVOKED",id,text(env.get("name")));}return null;});}
    String credential(String id){return store.transaction(()->{environment(id);return bearer(id,enabled(id));});}
    Map<String,Object> issueDownload(String id,String token,EnrollmentRegistry registry,Set<String> environmentIds){return store.transaction(()->{
        Map<String,Object> env=environment(id),r=enabled(id);String expected=bearer(id,r);
        if(token==null||token.length()!=expected.length()||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII)))throw new Failure(403,"Invalid installation credential");
        long now=System.currentTimeMillis(),window=number(r.get("downloadWindow")),count=number(r.get("downloadCount"));
        if(now<window||now-window>=3600000L){window=now;count=0;}if(count>=DOWNLOADS_PER_HOUR)throw new Failure(429,"Installation download limit reached; try again later");
        Map<String,Object> issued=registry.issueInstallAccess(text(env.get("name")),id,text(r.get("generation")),environmentIds);
        r.put("downloadWindow",window);r.put("downloadCount",count+1);store.metadata(PREFIX+id,r);return issued;
    });}
    /** Checked inside the same registration transaction as identity creation. */
    static boolean enrollmentAllowed(InventoryStore store,Map<String,Object> enrollment){
        if(!enrollment.containsKey("installAccessGeneration"))return true;
        String id=text(enrollment.get("environmentId")),generation=text(enrollment.get("installAccessGeneration"));Object value=store.metadata(PREFIX+id);
        return !generation.isEmpty()&&store.get("environments",id)!=null&&value instanceof Map&&Boolean.TRUE.equals(((Map<?,?>)value).get("enabled"))&&generation.equals(((Map<?,?>)value).get("generation"));
    }
    private void invalidateEnrollments(String id){for(Map<String,Object> e:store.documents("SELECT payload FROM enrollments WHERE status='active' AND json_extract(payload,'$.environmentId')=? AND json_extract(payload,'$.installAccessGeneration') IS NOT NULL",id)){e.put("status","revoked");store.put("enrollments",e);}}
    private Map<String,Object> enabled(String id){Map<String,Object> r=record(id);if(r==null||!Boolean.TRUE.equals(r.get("enabled")))throw new Failure(403,"Installation access is disabled");return r;}
    private String generation(){byte[] bytes=new byte[32];random.nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    private String bearer(String id,Map<String,Object> record){byte[] secret=key(false);try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return "eia1_"+HexFormat.of().formatHex(mac.doFinal(("environment-install-v1\n"+id+"\n"+text(record.get("generation"))).getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException error){throw new Failure(503,"Installation access key is unavailable");}finally{Arrays.fill(secret,(byte)0);}}
    private byte[] key(boolean create){try{
        PosixFileAttributes parent=Files.readAttributes(directory,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!parent.isDirectory()||parent.permissions().contains(PosixFilePermission.GROUP_WRITE)||parent.permissions().contains(PosixFilePermission.OTHERS_WRITE))throw new java.io.IOException();
        if(!Files.exists(keyFile,LinkOption.NOFOLLOW_LINKS)){
            if(!create||store.scalar("SELECT count(*) FROM metadata WHERE key LIKE ?",PREFIX+"%")>0)throw new java.io.IOException();
            byte[] value=new byte[32];random.nextBytes(value);
            try(SeekableByteChannel channel=Files.newByteChannel(keyFile,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){ByteBuffer data=ByteBuffer.wrap(value);while(data.hasRemaining())channel.write(data);if(channel instanceof FileChannel)((FileChannel)channel).force(true);}finally{Arrays.fill(value,(byte)0);}
        }
        PosixFileAttributes attrs=Files.readAttributes(keyFile,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()||attrs.size()!=32||!attrs.owner().equals(parent.owner())||!attrs.permissions().equals(PosixFilePermissions.fromString("rw-------"))||((Number)Files.getAttribute(keyFile,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1)throw new java.io.IOException();
        byte[] value=new byte[32];try(SeekableByteChannel channel=Files.newByteChannel(keyFile,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){ByteBuffer data=ByteBuffer.wrap(value);while(data.hasRemaining()){if(channel.read(data)<0)throw new java.io.IOException();}if(channel.read(ByteBuffer.allocate(1))!=-1)throw new java.io.IOException();}return value;
    }catch(Exception error){throw new Failure(503,"Installation access key is unavailable or unsafe; restore the protected dashboard key");}}
    private void audit(UserAuth.User actor,String action,String id,String name){store.exec("INSERT INTO human_audit(at,actor_id,actor_name,action,target_id,target_name) VALUES(?,?,?,?,?,?)",AssessmentServer.now(),actor.id(),actor.name(),action,id,name);store.exec("DELETE FROM human_audit WHERE id NOT IN (SELECT id FROM human_audit ORDER BY id DESC LIMIT 10000)");}
}
