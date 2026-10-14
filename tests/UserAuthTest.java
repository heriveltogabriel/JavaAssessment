package assessment;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static assessment.AssessmentServer.map;

/** Account rules, concurrency and hash limits against isolated SQLite fixtures. */
public final class UserAuthTest {
    private static final String PASSWORD="A safe personal passphrase 42!";
    private static int checks;
    private static void check(boolean condition,String description){checks++;if(!condition)throw new AssertionError(description);}
    private static void fails(int code,Runnable operation){try{operation.run();throw new AssertionError("Expected failure "+code);}catch(UserAuth.Failure error){check(error.status==code,"status "+code);}}
    private static void loginFails(Runnable operation){try{operation.run();throw new AssertionError("Expected generic login failure");}catch(UserAuth.LoginFailure expected){checks++;}}
    private static Path fixture()throws Exception{
        Path root=Files.createTempDirectory("assessment-users-");Files.createDirectories(root.resolve("conf"));Properties p=new Properties();
        p.setProperty("hostId","fixture-host");p.setProperty("agentToken","fixture-token");p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltlegacy-password"));p.setProperty("seedLegacyHost","false");
        try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(out,"isolated test fixture");}return root;
    }
    private static void delete(Path root)throws Exception{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    private static final class MutableClock extends Clock {long value=Instant.parse("2026-10-13T12:00:00Z").toEpochMilli();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(value);}}
    public static void main(String[] args)throws Exception{
        long start=System.nanoTime();String hash=PasswordHash.hash(PASSWORD),hash2=PasswordHash.hash(PASSWORD);long hashMillis=(System.nanoTime()-start)/2000000;
        check(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"),"versioned minimum Argon2id parameters");check(!hash.equals(hash2),"independent salts");check(PasswordHash.verify(PASSWORD,hash),"valid hash");check(!PasswordHash.verify("wrong",hash),"wrong password");
        check(!PasswordHash.verify(PASSWORD,hash.replace("19456","2147483647")),"memory parameter capped");check(!PasswordHash.verify(PASSWORD,"x".repeat(201)),"encoded length capped");
        fails(400,()->PasswordHash.hash("1234567"));check(PasswordHash.verify("Abcd123!",PasswordHash.hash("Abcd123!")),"eight characters accepted");check(PasswordHash.verify("a".repeat(14),PasswordHash.hash("a".repeat(14))),"fourteen characters accepted");check(PasswordHash.verify("a".repeat(128),PasswordHash.hash("a".repeat(128))),"maximum length accepted");fails(400,()->PasswordHash.hash("a".repeat(129)));check(PasswordHash.verify(" words spaces and phrases ",PasswordHash.hash(" words spaces and phrases ")),"spaces retained");
        check(!UserAdminTool.passwordOwnerAllowed("root","unprivileged"),"root cannot consume another user's password file");
        check(UserAdminTool.passwordOwnerAllowed("dashboard-service","root")&&UserAdminTool.passwordOwnerAllowed("dashboard-service","dashboard-service"),"password owner is current user or root");
        Path cliRoot=fixture();
        try {
            Files.setPosixFilePermissions(cliRoot.resolve("conf/server.properties"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
            Path secret=cliRoot.resolve("temporary-password");Files.writeString(secret,PASSWORD);
            for(String permissions:List.of("rw-r-----","-w-------","rwx------")){
                Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
                try{UserAdminTool.main(new String[]{"--root",cliRoot.toString(),"--mode","provision","--name","Fixture","--email","fixture@example.com","--password-file",secret.toString()});throw new AssertionError("Password mode must be rejected");}catch(IllegalArgumentException expected){checks++;}
            }
            Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));
            java.io.PrintStream original=System.out;java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            try{System.setOut(new java.io.PrintStream(output));UserAdminTool.main(new String[]{"--root",cliRoot.toString(),"--mode","provision","--name","Fixture","--email","fixture@example.com","--password-file",secret.toString()});}finally{System.setOut(original);}
            check(output.toString().contains("FIRST_ACCESS")&&!output.toString().contains(PASSWORD),"private provisioning emits no secret and accepts0640 config");
            String beforeId;try(AssessmentServer before=new AssessmentServer(cliRoot.toFile())){beforeId=before.auth.users().get(0).get("id").toString();before.store.metadata("recoveryKeepsInventory",map("hostCount",7));}
            Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));Files.writeString(secret,"Abcd123!");output.reset();
            try{System.setOut(new java.io.PrintStream(output));UserAdminTool.main(new String[]{"--root",cliRoot.toString(),"--mode","recover","--email","fixture@example.com","--password-file",secret.toString()});}finally{System.setOut(original);}
            check(!output.toString().contains("Abcd123!"),"offline recovery does not print eight-character password");
            try(AssessmentServer after=new AssessmentServer(cliRoot.toFile())){
                UserAuth.User recovered=after.auth.authenticate("fixture@example.com","Abcd123!");check(recovered.id().equals(beforeId)&&recovered.mustChangePassword(),"offline recovery keeps identity and forces password change");
                check(((Map<?,?>)after.store.metadata("recoveryKeepsInventory")).get("hostCount").equals(7L),"offline recovery preserves existing metadata");loginFails(()->after.auth.authenticate("fixture@example.com",PASSWORD));
            }
        }finally{delete(cliRoot);}
        Path root=fixture();String adminId;
        try(AssessmentServer server=new AssessmentServer(root.toFile())){
            UserAuth auth=server.auth;check(auth.bootstrapRequired(),"legacy setup starts required");
            ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(1);List<Future<Boolean>> outcomes=new ArrayList<>();
            for(int i=0;i<2;i++){final int n=i;outcomes.add(pool.submit(()->{ready.await();try{auth.bootstrap("Admin "+n,"admin"+n+"@example.com",PASSWORD);return true;}catch(UserAuth.Failure error){if(error.status!=409)throw error;return false;}}));}
            ready.countDown();int won=0;for(var future:outcomes)if(future.get(30,TimeUnit.SECONDS))won++;pool.shutdownNow();check(won==1,"single bootstrap winner");
            check(!auth.bootstrapRequired()&&Boolean.TRUE.equals(server.store.metadata("humanAuthActivated")),"activation marker persistent");
            UserAuth.User admin=auth.find((String)auth.users().get(0).get("id"));adminId=admin.id();check(!admin.lastLoginAt().isEmpty()&&Instant.parse(admin.lastLoginAt()).isAfter(Instant.now().minusSeconds(30)),"bootstrap records first authenticated access");auth.requireAdministrator(admin,PASSWORD);
            fails(403,()->auth.requireAdministrator(admin,"incorrect"));fails(409,()->auth.bootstrap("Another","another@example.com",PASSWORD));
            fails(409,()->auth.update(admin,admin.id(),admin.name(),admin.email(),"READER",true));fails(409,()->auth.update(admin,admin.id(),admin.name(),admin.email(),"ADMIN",false));
            UserAuth.Temporary created=auth.create(admin,"Reader","  READER@example.com  ","");check(created.user().role().equals("READER")&&created.user().email().equals("reader@example.com"),"default role and email normalization");
            check(created.user().mustChangePassword()&&created.password().length()==32,"random first access secret");check(Instant.parse(created.expiresAt()).isAfter(Instant.now().plusSeconds(86390)),"24 hour lifetime");
            fails(409,()->auth.create(admin,"Duplicate","READER@example.com","READER"));fails(400,()->auth.create(admin,"Bad","bad@example.com","OWNER"));
            UserAuth.User first=auth.authenticate("READER@example.com",created.password());fails(403,()->auth.requireAdministrator(first,created.password()));
            fails(400,()->auth.changePassword(first.id(),first.revision(),created.password(),created.password()));
            UserAuth.User reader=auth.changePassword(first.id(),first.revision(),created.password(),"Reader personal passphrase 42!");check(!reader.mustChangePassword()&&reader.role().equals("READER"),"password change preserves reader role");fails(401,()->auth.current(first.id(),first.revision()));
            loginFails(()->auth.authenticate(reader.email(),created.password()));check(auth.authenticate(reader.email(),"Reader personal passphrase 42!").id().equals(reader.id()),"personal login");
            UserAuth.User disabled=auth.update(admin,reader.id(),reader.name(),reader.email(),reader.role(),false);fails(401,()->auth.current(reader.id(),reader.revision()));loginFails(()->auth.authenticate(reader.email(),"Reader personal passphrase 42!"));
            UserAuth.User enabled=auth.update(admin,disabled.id(),disabled.name(),disabled.email(),disabled.role(),true);UserAuth.Temporary reset=auth.reset(admin,enabled.id());fails(401,()->auth.current(enabled.id(),enabled.revision()));loginFails(()->auth.authenticate(reader.email(),"Reader personal passphrase 42!"));check(auth.authenticate(reader.email(),reset.password()).mustChangePassword(),"reset returns first access");
            server.store.exec("UPDATE human_users SET temporary_expires=? WHERE id=?",System.currentTimeMillis()-1,reader.id());loginFails(()->auth.authenticate(reader.email(),reset.password()));fails(401,()->auth.current(reset.user().id(),reset.user().revision()));
            UserAuth.Temporary secondAdmin=auth.create(admin,"Second Admin","second@example.com","ADMIN");UserAuth.User other=auth.changePassword(secondAdmin.user().id(),secondAdmin.user().revision(),secondAdmin.password(),"Second admin personal passphrase!");
            ExecutorService demotions=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
            for(UserAuth.User actor:List.of(admin,other))results.add(demotions.submit(()->{gate.await();try{auth.update(actor,actor.id(),actor.name(),actor.email(),"READER",true);return true;}catch(UserAuth.Failure error){if(error.status!=409)throw error;return false;}}));
            gate.countDown();int demoted=0;for(var result:results)if(result.get(30,TimeUnit.SECONDS))demoted++;demotions.shutdownNow();check(demoted==1&&server.store.scalar("SELECT count(*) FROM human_users WHERE enabled=1 AND role='ADMIN'")==1,"concurrent last-admin protection");
            String dto=Json.stringify(auth.users()),audit=Json.stringify(auth.events());check(!dto.contains("password")&&!dto.contains("revision")&&!dto.contains("hash")&&!dto.contains("temporary"),"safe user DTO");check(!audit.contains(PASSWORD)&&!audit.contains(created.password())&&!audit.contains(hash)&&!audit.contains("@example.com"),"audit has no passwords, hashes or emails");
            check(auth.events().stream().anyMatch(e->e.get("action").equals("LOGIN_FAILED")),"failed login audit persists");
            try{new AssessmentServer(root.toFile()).close();throw new AssertionError("Expected exclusive lock");}catch(java.io.IOException expected){checks++;}
            server.store.metadata("fixturePreserved",map("count",42));
        }
        try(AssessmentServer restarted=new AssessmentServer(root.toFile())){
            check(!restarted.auth.bootstrapRequired()&&restarted.auth.find(adminId)!=null,"restart retains accounts");check(((Map<?,?>)restarted.store.metadata("fixturePreserved")).get("count").equals(42L),"unrelated metadata preserved");
        }finally{delete(root);}
        Path expiryRoot=fixture();try(AssessmentServer server=new AssessmentServer(expiryRoot.toFile())){
            MutableClock clock=new MutableClock();UserAuth auth=new UserAuth(server.store,clock);UserAuth.User user=auth.provision("Provisioned","initial@example.com",PASSWORD);check(user.mustChangePassword(),"provisioned account requires password change");check(user.lastLoginAt().isEmpty(),"offline provisioning does not count as login");auth.authenticate(user.email(),PASSWORD);clock.value+=86400001;loginFails(()->auth.authenticate(user.email(),PASSWORD));UserAuth.User recovered=auth.recover(user.email(),"New offline recovery password!");check(recovered.enabled()&&recovered.mustChangePassword()&&recovered.revision()>user.revision(),"offline recovery rotates account");check(auth.authenticate(user.email(),"New offline recovery password!").id().equals(user.id()),"recovery retains identity");
        }finally{delete(expiryRoot);}
        System.out.println("UserAuthTest PASS: "+checks+" checks; Argon2id mean initial hash="+hashMillis+"ms; isolated SQLite/concurrency/restart/recovery.");
    }
}
