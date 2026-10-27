package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Local-only persistence, migration and WAL concurrency checks. */
public final class InventoryStoreTest {
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;
    }
    private static void fails(Runnable operation,String message) {
        boolean failed=false;try {operation.run();}catch(RuntimeException expected){failed=true;}check(failed,message);
    }
    private static Map<String,Object> host(String id) {return map("id",id,"hostname","Machine "+id,"environmentId","env-a","coverage",map("status","complete"));}
    private static Map<String,Object> runtime(String hostId,String id,String version,String kind) {return map("id",id,"hostId",hostId,"version",version,"kind",kind,"javaHome","/opt/"+id,"observedAt","2026-10-13T10:00:00Z");}
    private static InventoryStore open(Path path) {return new InventoryStore(path.toFile(),map(),"","");}
    private static void write(Path path,Object value)throws IOException {Files.writeString(path,Json.stringify(value),StandardCharsets.UTF_8);}
    private static long rawCount(Path path,String table)throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.resolve("inventory.sqlite"));Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)) {r.next();return r.getLong(1);}
    }
    private static void permissions(Path path,String expected)throws IOException {
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))check(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)).equals(expected),"private permissions for "+path.getFileName());
    }
    private static void deleteTree(Path root)throws IOException {
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }

    private static void documentsAndRollback(Path dir)throws Exception {
        try(InventoryStore store=open(dir)) {
            store.put("environments",map("id","env-a","name","Production"));
            store.put("hosts",host("h1"));store.put("hosts",host("h2"));
            store.metadata("settings",map("enabled",false,"frequency","once"));
            check(Boolean.FALSE.equals(((Map<?,?>)store.metadata("settings")).get("enabled")),"metadata round trip");
            check(store.get("hosts","unknown")==null,"missing documents return null");
            store.putInventory("runtimes","h1",List.of(runtime("h1","jdk6","1.6.0_45","JDK"),runtime("h1","jre8","1.8.0_452"," JRE ")));
            store.putInventory("runtimes","h2",List.of(runtime("h2","jdk17","17.0.20","JDK")));
            check(store.scalar("SELECT COUNT(*) FROM inventory")==2,"unused standalone JRE filtered");
            check(store.scalar("SELECT COUNT(*) FROM inventory WHERE major=? AND kind=?","6","runtimes")==1,"legacy Java major index");
            check(store.scalar("SELECT COUNT(*) FROM inventory WHERE search_text LIKE ?","%/opt/jdk6%")==1,"inventory search text");
            Map<String,Object> h=host("h1");h.put("environmentId","env-b");h.put("lastSeen","2026-10-13T11:00:00Z");h.put("lastAssessmentAt","2026-10-13T10:00:00Z");h.put("coverage",map("status","partial"));store.put("hosts",h);
            Map<String,Object> indexed=store.rows("SELECT environment_id,last_seen,captured_at,coverage_status FROM hosts WHERE id=?","h1").get(0);
            check(indexed.get("environment_id").equals("env-b")&&indexed.get("coverage_status").equals("partial")&&indexed.get("captured_at").equals(h.get("lastAssessmentAt")),"host update maintains query columns");
            store.put("identities",map("hostId","h1","installationId","install-1","agentToken","secret-fixture","status","active"));
            check(store.rows("SELECT token_hash FROM identities WHERE id=?","h1").get(0).get("token_hash").equals(InventoryStore.hash("secret-fixture")),"credential hash indexed");
            check(store.get("identities","h1").get("agentToken").equals("secret-fixture"),"credential retry data survives");
            store.put("enrollments",map("id","enr-1","tokenHash",InventoryStore.hash("code"),"status","active","expiresMillis",12345L));
            check(store.scalar("SELECT expires_millis FROM enrollments WHERE id=?","enr-1")==12345,"enrollment index populated");
            fails(()->store.transaction(()->{store.delete("hosts","h1");store.putInventory("runtimes","h2",List.of());store.metadata("settings","bad");throw new IOException("simulated durable commit failure");}),"transaction propagates failure");
            check(store.get("hosts","h1")!=null&&store.scalar("SELECT COUNT(*) FROM inventory")==2&&store.metadata("settings") instanceof Map,"transaction rolls back related writes");
            store.transaction(()->{
                store.metadata("outer","before");
                fails(()->store.transaction(()->{store.metadata("outer","after");store.delete("hosts","h1");throw new IllegalArgumentException("nested");}),"nested failure propagated");
                check(store.metadata("outer").equals("before")&&store.get("hosts","h1")!=null,"nested transaction savepoint");return null;
            });
            fails(()->store.putInventory("runtimes","h1",List.of(runtime("h1","new","21","JDK"),runtime("foreign","invalid","8","JRE"))),"JRE ownership enforced before filtering");
            check(store.scalar("SELECT COUNT(*) FROM inventory WHERE host_id=?","h1")==1&&store.documents("SELECT payload FROM inventory WHERE host_id=?","h1").get(0).get("id").equals("jdk6"),"invalid replacement keeps committed snapshot");
            store.putInventory("runtimes","h1",List.of(runtime("h1","jdk21","21.0.4","JDK")));
            check(store.scalar("SELECT COUNT(*) FROM inventory WHERE host_id=?","h2")==1,"snapshot replacement leaves other hosts alone");
            fails(()->store.get("hosts; DROP TABLE hosts","h1"),"table identifiers allowlisted");
            check(store.get("hosts","h1' OR 1=1 --")==null,"query values parameterized");
            check(store.rows("PRAGMA journal_mode").get(0).values().iterator().next().equals("wal"),"WAL configured");
            check(store.scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN ('hosts_environment','inventory_host','inventory_major','inventory_version','jobs_status_lease','identities_token')")==6,"required indexes exist");
            store.put("jobs",map("id","job-restart","hostId","h1","status","RUNNING","createdAt","2026-10-13T10:00:00Z","leaseExpires",9999999999999L));
            permissions(dir,"rwx------");for(String suffix:List.of("","-wal","-shm"))permissions(dir.resolve("inventory.sqlite"+suffix),"rw-------");
        }
        try(InventoryStore reopened=open(dir)) {
            check(reopened.get("hosts","h1")!=null&&reopened.scalar("SELECT COUNT(*) FROM inventory")==2,"documents persist after reopen");
            Map<String,Object> job=reopened.get("jobs","job-restart");
            check(job.get("status").equals("FAILED")&&String.valueOf(job.get("error")).startsWith("Server restarted")&&((Number)job.get("leaseExpires")).longValue()==9999999999999L,"restart preserves late result eligibility");
            check(reopened.metadata("migration.v1")!=null,"migration marker persists");
        }
    }

    private static void migration(Path dir)throws Exception {
        Files.createDirectories(dir);
        Map<String,Object> h=host("legacy");h.remove("coverage");h.remove("environmentId");
        Map<String,Object> active=runtime("legacy","runtime21","21.0.12","JRE");active.put("inUse",true);
        Map<String,Object> state=map("fleet",map("name","Old fleet"),"settings",map("enabled",false),"hosts",List.of(h),"coverage",map("status","complete"),"jobs",List.of(map("id","j1","hostId","legacy","status","RUNNING","leaseExpires",123L)),"runtimes",List.of(runtime("legacy","jdk6","1.6.0_45","JDK"),runtime("legacy","jre6","1.6.0_45","JRE"),active),"eventIds",List.of("legacy:j1:event-one"),"scheduleRevision","rev1","lastScheduleSlot","rev1:2026-10-13","hostSchedules",map());
        Map<String,Object> identity=map("hostId","legacy","installationId","installation-one","agentToken","fixture-secret","status","active");
        Map<String,Object> enrollment=map("id","e1","tokenHash",InventoryStore.hash("old-code"),"status","active","expiresMillis",100000L,"usedCount",1);
        write(dir.resolve("state.json"),state);write(dir.resolve("auth-registry.json"),map("hosts",map("legacy",identity),"enrollments",map("e1",enrollment)));
        byte[] original=Files.readAllBytes(dir.resolve("state.json"));
        try(InventoryStore store=new InventoryStore(dir.toFile(),map("hosts",List.of(host("ignored"))),"legacy","different-token")) {
            check(store.scalar("SELECT COUNT(*) FROM hosts")==1&&store.get("hosts","ignored")==null,"legacy state takes precedence over fallback");
            check(store.get("hosts","legacy").get("environmentId").equals("")&&((Map<?,?>)store.get("hosts","legacy").get("coverage")).get("status").equals("complete"),"host defaults migrated");
            check(store.scalar("SELECT COUNT(*) FROM inventory")==2,"migration retains observed runtime and filters unused JRE");
            check(store.scalar("SELECT COUNT(*) FROM receipts WHERE host_id=? AND job_id=? AND event_id=?","legacy","j1","event-one")==1,"receipt migration");
            check(store.rows("SELECT slot FROM schedules WHERE host_id=?","legacy").get(0).get("slot").equals("rev1::2026-10-13"),"legacy schedule normalized");
            check(store.get("identities","legacy").get("agentToken").equals("fixture-secret")&&store.get("enrollments","e1")!=null,"existing identities and enrollments retained");
            check(Arrays.equals(original,Files.readAllBytes(dir.resolve("state.json"))),"original state content preserved");
            try(var paths=Files.list(dir)) {
                Path backup=paths.filter(p->p.getFileName().toString().startsWith("migration-backup-")).findFirst().orElseThrow();
                permissions(backup,"rwx------");permissions(backup.resolve("state.json"),"rw-------");permissions(backup.resolve("auth-registry.json"),"rw-------");
                check(Arrays.equals(original,Files.readAllBytes(backup.resolve("state.json"))),"backup content exact");
            }
        }
        Files.writeString(dir.resolve("state.json"),"{broken");Files.writeString(dir.resolve("auth-registry.json"),"{broken");
        try(InventoryStore reopened=new InventoryStore(dir.toFile(),map("hosts",List.of(host("new-seed"))),"new-seed","new-secret")) {
            check(reopened.get("hosts","legacy")!=null&&reopened.get("hosts","new-seed")==null&&reopened.get("identities","new-seed")==null,"ordinary startup never reimports or seeds legacy state");
        }
    }

    private static void migrationFailure(Path dir)throws Exception {
        Files.createDirectories(dir);
        write(dir.resolve("state.json"),map("hosts",List.of(host("safe"))));
        write(dir.resolve("auth-registry.json"),map("hosts",map("mismatch",map("hostId","other","agentToken","fixture-secret","status","active"))));
        fails(()->open(dir),"malformed identity aborts migration");
        check(rawCount(dir,"hosts")==0&&rawCount(dir,"metadata")==0,"failed migration rolls back state and marker together");
        write(dir.resolve("auth-registry.json"),map("hosts",map(),"enrollments",map()));
        try(InventoryStore recovered=open(dir)) {check(recovered.get("hosts","safe")!=null,"migration can resume after repaired input");}
        Path malformed=dir.resolve("malformed");Files.createDirectories(malformed);Files.writeString(malformed.resolve("state.json"),"{invalid");
        fails(()->open(malformed),"malformed state refuses startup");
        check(rawCount(malformed,"metadata")==0,"malformed state leaves no completed marker");
        Path large=dir.resolve("oversize");Files.createDirectories(large);
        try(RandomAccessFile file=new RandomAccessFile(large.resolve("state.json").toFile(),"rw")){file.setLength(32L*1024*1024+1);}
        fails(()->open(large),"oversized legacy state rejected");
        Files.delete(large.resolve("state.json"));
        try(RandomAccessFile file=new RandomAccessFile(large.resolve("auth-registry.json").toFile(),"rw")){file.setLength(8L*1024*1024+1);}
        fails(()->open(large),"oversized legacy identity storage rejected");
    }

    private static void concurrentSnapshot(Path dir)throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try(InventoryStore store=open(dir)) {
            store.metadata("sequence",1);
            store.readSnapshot(()->{
                check(((Number)store.metadata("sequence")).intValue()==1,"first snapshot read");
                Future<?> write=pool.submit(()->store.metadata("sequence",2));write.get(3,TimeUnit.SECONDS);
                check(((Number)store.metadata("sequence")).intValue()==1,"WAL reader retains one consistent snapshot while writer commits");
                fails(()->store.metadata("sequence",3),"writes prohibited within read snapshot");
                check(store.readSnapshot(()->((Number)store.metadata("sequence")).intValue())==1,"nested read retains same connection");
                return null;
            });
            check(((Number)store.metadata("sequence")).intValue()==2,"new read observes committed writer");
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{for(int n=0;n<20;n++)check(((Number)store.metadata("sequence")).intValue()==2,"bounded pool shares concurrent reads");}));
            for(Future<?> future:futures)future.get(5,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }

    /** readOrWrite serves pure reads without the writer lock and commits exactly once when a write is reached. */
    private static void readOrWrite(Path path)throws Exception {
        try(InventoryStore store=open(path)) {
            store.transaction(()->{store.metadata("rw","0");return null;});
            var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            Future<?> writer=pool.submit(()->store.transaction(()->{store.metadata("held","open");locked.countDown();release.await();return null;}));
            try {
                check(locked.await(5,TimeUnit.SECONDS),"writer held for readOrWrite test");
                check("0".equals(pool.submit(()->store.readOrWrite(()->store.metadata("rw"))).get(5,TimeUnit.SECONDS)),"pure read does not wait for the writer");
                Future<String> pending=pool.submit(()->store.readOrWrite(()->{String seen=String.valueOf(store.metadata("rw"));store.metadata("rw","1");return seen;}));
                try {pending.get(300,TimeUnit.MILLISECONDS);check(false,"writing operation queues behind the writer");}catch(TimeoutException expected) {}
                release.countDown();writer.get(5,TimeUnit.SECONDS);
                check("0".equals(pending.get(5,TimeUnit.SECONDS)),"operation reran in the writer transaction after its first write attempt");
            } finally {release.countDown();pool.shutdownNow();}
            check("1".equals(store.metadata("rw")),"fallback write committed once");
            int[] runs={0};
            check(store.readOrWrite(()->{runs[0]++;return "ok";}).equals("ok")&&runs[0]==1,"pure read runs exactly once");
        }
    }

    /** WAL durability mode is configurable; NORMAL is the default and anything unknown fails safe to FULL. */
    private static void synchronousMode(Path path)throws Exception {
        check("NORMAL".equals(InventoryStore.synchronousMode(null))&&"FULL".equals(InventoryStore.synchronousMode("full"))&&"FULL".equals(InventoryStore.synchronousMode("OFF")),"synchronous mode parsing fails safe");
        try(InventoryStore store=open(path.resolve("default"))) {check(store.transaction(()->store.scalar("PRAGMA synchronous"))==1,"default writer uses synchronous NORMAL");}
        try(InventoryStore store=new InventoryStore(path.resolve("full").toFile(),map(),"","","FULL")) {check(store.transaction(()->store.scalar("PRAGMA synchronous"))==2,"configured writer uses synchronous FULL");}
        try(InventoryStore store=new InventoryStore(path.resolve("bad").toFile(),map(),"","","OFF")) {check(store.transaction(()->store.scalar("PRAGMA synchronous"))==2,"unsupported value falls back to FULL");}
    }

    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("inventory-store-test-");
        try {
            documentsAndRollback(root.resolve("documents"));migration(root.resolve("migration"));migrationFailure(root.resolve("failure"));concurrentSnapshot(root.resolve("concurrent"));readOrWrite(root.resolve("read-or-write"));synchronousMode(root.resolve("synchronous"));
            System.out.println("INVENTORY_STORE_TESTS=PASS persistence index-columns rollback nested-savepoints migration backups limits observed-JRE unused-JRE-filter restart-late-retry WAL-readers read-snapshots permissions");
        } finally {deleteTree(root);}
    }
}
