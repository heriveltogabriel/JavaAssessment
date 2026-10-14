package assessment;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Human accounts are independent of collector identities and credentials. */
public final class UserAuth {
    public static final class Failure extends RuntimeException {public final int status;Failure(int status,String message){super(message);this.status=status;}}
    public record User(String id,String name,String email,String role,boolean enabled,boolean mustChangePassword,long revision,long temporaryExpires,String lastLoginAt,String passwordHash) {
        public Map<String,Object> dto(){return map("id",id,"name",name,"email",email,"role",role,"status",!enabled?"DISABLED":mustChangePassword?"FIRST_ACCESS":"ACTIVE","mustChangePassword",mustChangePassword,"lastLoginAt",lastLoginAt);}
        public boolean administrator(){return enabled&&role.equals("ADMIN")&&!mustChangePassword;}
    }
    public record Temporary(User user,String password,String expiresAt) {
        public Map<String,Object> response(){return map("user",user.dto(),"temporaryPassword",password,"expiresAt",expiresAt);}
    }
    private final InventoryStore store;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private static final String DUMMY=PasswordHash.hash("Unusable dummy account password "+UUID.randomUUID());
    public UserAuth(InventoryStore store){this(store,Clock.systemUTC());}
    UserAuth(InventoryStore store,Clock clock) {
        this.store=store;this.clock=clock;
        store.transaction(()->{
            store.exec("CREATE TABLE IF NOT EXISTS human_users(id TEXT PRIMARY KEY,name TEXT NOT NULL,email TEXT NOT NULL UNIQUE,role TEXT NOT NULL CHECK(role IN ('ADMIN','READER')),enabled INTEGER NOT NULL,password_hash TEXT NOT NULL,must_change INTEGER NOT NULL,temporary_expires INTEGER NOT NULL,revision INTEGER NOT NULL,last_login TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL)");
            store.exec("CREATE TABLE IF NOT EXISTS human_audit(id INTEGER PRIMARY KEY AUTOINCREMENT,at TEXT NOT NULL,actor_id TEXT NOT NULL,actor_name TEXT NOT NULL,action TEXT NOT NULL,target_id TEXT NOT NULL,target_name TEXT NOT NULL)");
            if(store.scalar("SELECT count(*) FROM human_users")>0)store.metadata("humanAuthActivated",true);
            return null;
        });
    }
    public boolean bootstrapRequired(){return !Boolean.TRUE.equals(store.metadata("humanAuthActivated"))&&store.scalar("SELECT count(*) FROM human_users")==0;}
    public User bootstrap(String name,String email,String password) {return first(name,email,password,false,"BOOTSTRAP");}
    public User provision(String name,String email,String password) {return first(name,email,password,true,"PROVISION");}
    private User first(String name,String email,String password,boolean temporary,String action) {
        String cleanName=name(name),cleanEmail=email(email),hash=PasswordHash.hash(password);
        return store.transaction(()->{
            if(!bootstrapRequired())throw new Failure(409,"Administrator setup has already been completed");
            User user=insert(cleanName,cleanEmail,"ADMIN",hash,temporary);
            if(!temporary){store.exec("UPDATE human_users SET last_login=? WHERE id=?",instant(),user.id);user=find(user.id);}
            store.metadata("humanAuthActivated",true);audit(user,action,user);return user;
        });
    }
    public User find(String id){List<Map<String,Object>> rows=store.rows("SELECT * FROM human_users WHERE id=?",id);return rows.isEmpty()?null:from(rows.get(0));}
    private User findEmail(String email){List<Map<String,Object>> rows=store.rows("SELECT * FROM human_users WHERE email=?",email);return rows.isEmpty()?null:from(rows.get(0));}
    public User current(String id,long revision) {
        User user=find(id);if(user==null||!user.enabled||user.revision!=revision||user.mustChangePassword&&user.temporaryExpires<=clock.millis())throw new Failure(401,"Sign in required");return user;
    }
    public User authenticate(String email,String password) {
        String normalized=normalize(email);User candidate=findEmail(normalized);
        boolean matched=PasswordHash.verify(password,candidate==null?DUMMY:candidate.passwordHash);
        User authenticated=store.transaction(()->{
            User user=candidate==null?null:find(candidate.id);
            if(!matched||user==null||!user.enabled||user.revision!=candidate.revision||user.mustChangePassword&&user.temporaryExpires<=clock.millis())return null;
            store.exec("UPDATE human_users SET last_login=? WHERE id=?",instant(),user.id);user=find(user.id);audit(user,"LOGIN",user);return user;
        });
        if(authenticated==null){failedLogin();throw new LoginFailure();}return authenticated;
    }
    public static final class LoginFailure extends RuntimeException {LoginFailure(){super("Email or password is incorrect");}}
    public void failedLogin(){store.transaction(()->{audit(null,"LOGIN_FAILED",null);return null;});}
    public void failedReauthentication(User actor,boolean passwordChange){store.transaction(()->{audit(actor,passwordChange?"PASSWORD_CHANGE_FAILED":"REAUTH_FAILED",actor);return null;});}
    public void logout(User user){store.transaction(()->{audit(user,"LOGOUT",user);return null;});}
    public User changePassword(String id,long revision,String currentPassword,String newPassword) {
        PasswordHash.validate(newPassword);User original=current(id,revision);
        if(!PasswordHash.verify(currentPassword,original.passwordHash))throw new Failure(403,"Current password is incorrect");
        if(PasswordHash.verify(newPassword,original.passwordHash))throw new Failure(400,"Choose a different password");String hash=PasswordHash.hash(newPassword);
        return store.transaction(()->{
            User user=current(id,revision);store.exec("UPDATE human_users SET password_hash=?,must_change=0,temporary_expires=0,revision=revision+1 WHERE id=?",hash,id);User updated=find(id);audit(user,"PASSWORD_CHANGED",updated);return updated;
        });
    }
    public void requireAdministrator(User actor,String password) {
        User user=current(actor.id,actor.revision);
        if(!user.administrator())throw new Failure(403,"Administrator access required");
        if(!PasswordHash.verify(password,user.passwordHash))throw new Failure(403,"Administrator password is incorrect");
    }
    private User currentAdmin(User actor){User user=current(actor.id,actor.revision);if(!user.administrator())throw new Failure(403,"Administrator access required");return user;}
    public List<Map<String,Object>> users() {List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> row:store.rows("SELECT * FROM human_users ORDER BY name COLLATE NOCASE,email"))out.add(from(row).dto());return out;}
    public Temporary create(User actor,String name,String email,String role) {
        String cleanName=name(name),cleanEmail=email(email),cleanRole=role(role),password=temporary(),hash=PasswordHash.hash(password);
        return store.transaction(()->{User administrator=currentAdmin(actor);if(store.scalar("SELECT count(*) FROM human_users")>=1000)throw new Failure(409,"User limit reached");unique(cleanEmail,"");User user=insert(cleanName,cleanEmail,cleanRole,hash,true);audit(administrator,"USER_CREATED",user);return new Temporary(user,password,Instant.ofEpochMilli(user.temporaryExpires).toString());});
    }
    public User update(User actor,String id,String name,String email,String role,boolean enabled) {
        String cleanName=name(name),cleanEmail=email(email),cleanRole=role(role);
        return store.transaction(()->{
            User administrator=currentAdmin(actor),user=find(id);if(user==null)throw new Failure(404,"User not found");unique(cleanEmail,id);
            if(user.enabled&&user.role.equals("ADMIN")&&(!enabled||!cleanRole.equals("ADMIN")))lastAdministrator(id);
            boolean revoke=!user.email.equals(cleanEmail)||!user.role.equals(cleanRole)||user.enabled!=enabled;
            store.exec("UPDATE human_users SET name=?,email=?,role=?,enabled=?,revision=revision+? WHERE id=?",cleanName,cleanEmail,cleanRole,enabled?1:0,revoke?1:0,id);User updated=find(id);
            audit(administrator,"USER_UPDATED",updated);if(!user.role.equals(cleanRole))audit(administrator,"GROUP_CHANGED",updated);if(user.enabled!=enabled)audit(administrator,enabled?"USER_ENABLED":"USER_DISABLED",updated);return updated;
        });
    }
    public Temporary reset(User actor,String id){String password=temporary();String hash=PasswordHash.hash(password);return store.transaction(()->{User administrator=currentAdmin(actor),user=find(id);if(user==null)throw new Failure(404,"User not found");User updated=resetTo(user,hash);audit(administrator,"PASSWORD_RESET",updated);return new Temporary(updated,password,Instant.ofEpochMilli(updated.temporaryExpires).toString());});}
    public User recover(String email,String password) {
        String cleanEmail=email(email),hash=PasswordHash.hash(password);
        return store.transaction(()->{User user=findEmail(cleanEmail);if(user==null||!user.role.equals("ADMIN"))throw new Failure(404,"An existing administrator email is required");store.exec("UPDATE human_users SET enabled=1 WHERE id=?",user.id);User updated=resetTo(user,hash);store.metadata("humanAuthActivated",true);audit(null,"LOCAL_ADMIN_RECOVERY",updated);return updated;});
    }
    private User resetTo(User user,String hash){store.exec("UPDATE human_users SET password_hash=?,must_change=1,temporary_expires=?,revision=revision+1 WHERE id=?",hash,clock.millis()+86400000L,user.id);return find(user.id);}
    private void lastAdministrator(String id){if(store.scalar("SELECT count(*) FROM human_users WHERE role='ADMIN' AND enabled=1 AND id<>?",id)==0)throw new Failure(409,"Keep at least one enabled administrator");}
    private void unique(String email,String id){if(store.scalar("SELECT count(*) FROM human_users WHERE email=? AND id<>?",email,id)>0)throw new Failure(409,"This email is already in use");}
    private User insert(String name,String email,String role,String hash,boolean temporary) {
        String id=UUID.randomUUID().toString();store.exec("INSERT INTO human_users(id,name,email,role,enabled,password_hash,must_change,temporary_expires,revision,created_at) VALUES(?,?,?,?,1,?,?,?,?,?)",id,name,email,role,hash,temporary?1:0,temporary?clock.millis()+86400000L:0,1,instant());return find(id);
    }
    public List<Map<String,Object>> events() {
        List<Map<String,Object>> events=new ArrayList<>();for(Map<String,Object> row:store.rows("SELECT * FROM human_audit ORDER BY id DESC LIMIT 200"))events.add(map("id",row.get("id"),"at",row.get("at"),"actorId",row.get("actor_id"),"actorName",row.get("actor_name"),"action",row.get("action"),"targetId",row.get("target_id"),"targetName",row.get("target_name")));return events;
    }
    private void audit(User actor,String action,User target){store.exec("INSERT INTO human_audit(at,actor_id,actor_name,action,target_id,target_name) VALUES(?,?,?,?,?,?)",instant(),actor==null?"":actor.id,actor==null?"System":actor.name,action,target==null?"":target.id,target==null?"":target.name);store.exec("DELETE FROM human_audit WHERE id NOT IN (SELECT id FROM human_audit ORDER BY id DESC LIMIT 10000)");}
    private String temporary(){byte[] bytes=new byte[24];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private String instant(){return Instant.ofEpochMilli(clock.millis()).toString();}
    private static User from(Map<String,Object> row){return new User(text(row,"id"),text(row,"name"),text(row,"email"),text(row,"role"),number(row,"enabled")==1,number(row,"must_change")==1,number(row,"revision"),number(row,"temporary_expires"),text(row,"last_login"),text(row,"password_hash"));}
    private static String text(Map<String,Object> row,String key){return String.valueOf(row.get(key));}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static String normalize(String email){return email==null?"":email.strip().toLowerCase(Locale.ROOT);}
    private static String email(String value){String email=normalize(value);if(email.length()>254||!email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))throw new Failure(400,"Enter a valid email address");return email;}
    private static String name(String value){String name=value==null?"":value.strip();if(name.isEmpty()||name.length()>120||name.matches("(?s).*[\\x00-\\x1f\\x7f].*"))throw new Failure(400,"Enter a name with up to 120 characters");return name;}
    private static String role(String value){String role=value==null||value.isEmpty()?"READER":value;if(!Set.of("ADMIN","READER").contains(role))throw new Failure(400,"Invalid group");return role;}
}
