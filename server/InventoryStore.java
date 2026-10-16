package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/** SQLite document storage with indexed query columns and atomic per-host snapshots. */
public final class InventoryStore implements AutoCloseable {
    private static final int STATE_LIMIT=32*1024*1024, AUTH_LIMIT=8*1024*1024, READERS=4;
    private static final Set<String> TABLES=Set.of("hosts","environments","jobs","identities","enrollments","uninstall_requests","agent_update_requests");
    private static final Set<String> KINDS=Set.of("runtimes","processes","servers","libraries","applications","datasources");
    private final File data;
    private Connection writer;
    private final BlockingQueue<Connection> readers=new ArrayBlockingQueue<>(READERS);
    private final ReentrantLock writeLock=new ReentrantLock(true);
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock(true);
    private final ThreadLocal<Connection> current=new ThreadLocal<>();
    private final ThreadLocal<Boolean> reading=new ThreadLocal<>();
    private volatile boolean closed;

    public InventoryStore(File data,Map<String,Object> initialState,String legacyHostId,String legacyToken) {
        this.data=data;
        try {
            privateDirectory(data.toPath());
            Class.forName("org.sqlite.JDBC");
            Path db=new File(data,"inventory.sqlite").toPath();
            privateFile(db);
            writer=open(db,false);
            schema();
            protectDatabaseFiles();
            migrate(initialState,legacyHostId,legacyToken);
            transaction(()->{
                for(Map<String,Object> job:documents("SELECT payload FROM jobs WHERE status=?","RUNNING")) {
                    job.put("status","FAILED");job.put("error","Server restarted before result acknowledgement");
                    job.put("finishedAt",Instant.now().toString());put("jobs",job);
                }
                return null;
            });
            for(int i=0;i<READERS;i++)readers.add(open(db,true));
        } catch(Exception error) {
            close();
            throw failure("Cannot initialize inventory database",error);
        }
    }

    private static Connection open(Path path,boolean reader)throws SQLException {
        Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try(Statement s=c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA temp_store=FILE");
            s.execute("PRAGMA cache_size="+(reader?-2048:-8192));
            s.execute("PRAGMA mmap_size=0");
            if(reader)s.execute("PRAGMA query_only=ON");
            else {
                try(ResultSet mode=s.executeQuery("PRAGMA journal_mode=WAL")) {
                    if(!mode.next()||!"wal".equalsIgnoreCase(mode.getString(1)))throw new SQLException("WAL mode is unavailable");
                }
                s.execute("PRAGMA synchronous=FULL");
                s.execute("PRAGMA wal_autocheckpoint=1000");
                s.execute("PRAGMA journal_size_limit=16777216");
            }
            org.sqlite.Function.create(c,"assessment_lower",new org.sqlite.Function(){
                @Override protected void xFunc()throws SQLException {String value=value_text(0);result(value==null?"":java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT));}
            },1,org.sqlite.Function.FLAG_DETERMINISTIC);
            return c;
        } catch(SQLException e) {c.close();throw e;}
    }

    private void schema() {
        transaction(()->{
            String[] statements={
                "CREATE TABLE IF NOT EXISTS agent_update_batches(id TEXT PRIMARY KEY,request_key TEXT NOT NULL UNIQUE,target_version TEXT NOT NULL,selection TEXT NOT NULL,payload TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS agent_update_requests(id TEXT PRIMARY KEY,host_id TEXT NOT NULL,batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,status TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(batch_id,host_id))",
                "CREATE TABLE IF NOT EXISTS agent_update_operations(id TEXT PRIMARY KEY,host_id TEXT NOT NULL,batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,status TEXT NOT NULL,payload TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS agent_update_steps(id TEXT PRIMARY KEY,operation_id TEXT NOT NULL,ordinal INTEGER NOT NULL,kind TEXT NOT NULL,request_id TEXT,status TEXT NOT NULL,payload TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS agent_update_operation_host ON agent_update_operations(host_id,status)",
                "CREATE INDEX IF NOT EXISTS agent_update_operation_batch ON agent_update_operations(batch_id,ordinal)",
                "CREATE INDEX IF NOT EXISTS agent_update_step_operation ON agent_update_steps(operation_id,ordinal)",
                "CREATE TABLE IF NOT EXISTS agent_update_assessments(operation_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,state TEXT NOT NULL,job_id TEXT,not_before INTEGER NOT NULL DEFAULT 0,expires_at INTEGER NOT NULL,payload TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS agent_update_assessments_ready ON agent_update_assessments(state,not_before)",
                "CREATE INDEX IF NOT EXISTS agent_update_assessments_host ON agent_update_assessments(host_id,state)",
                "CREATE INDEX IF NOT EXISTS agent_update_assessments_job ON agent_update_assessments(job_id,state)",
                "CREATE INDEX IF NOT EXISTS agent_update_assessments_spacing ON agent_update_assessments(coalesce(json_extract(payload,'$.leaseEnvironmentId'),''),CAST(json_extract(payload,'$.leaseStartedMillis') AS INTEGER))",
                "CREATE INDEX IF NOT EXISTS agent_update_host ON agent_update_requests(host_id,status)",
                "CREATE INDEX IF NOT EXISTS agent_update_batch ON agent_update_requests(batch_id,ordinal)",
                "CREATE TABLE IF NOT EXISTS uninstall_requests(id TEXT PRIMARY KEY,host_id TEXT NOT NULL,request_key TEXT NOT NULL,status TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(host_id,request_key))",
                "CREATE INDEX IF NOT EXISTS uninstall_host ON uninstall_requests(host_id,status)",
                "CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,payload TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS hosts(id TEXT PRIMARY KEY,environment_id TEXT NOT NULL DEFAULT '',hostname TEXT NOT NULL DEFAULT '',last_seen TEXT NOT NULL DEFAULT '',captured_at TEXT NOT NULL DEFAULT '',coverage_status TEXT NOT NULL DEFAULT 'pending',payload TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS environments(id TEXT PRIMARY KEY,name TEXT NOT NULL,payload TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,host_id TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT '',lease_expires INTEGER NOT NULL DEFAULT 0,payload TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS inventory(kind TEXT NOT NULL,host_id TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL DEFAULT '',major TEXT NOT NULL DEFAULT '',type TEXT NOT NULL DEFAULT '',observed_at TEXT NOT NULL DEFAULT '',search_text TEXT NOT NULL DEFAULT '',payload TEXT NOT NULL,PRIMARY KEY(kind,host_id,id))",
                "CREATE TABLE IF NOT EXISTS receipts(host_id TEXT NOT NULL,job_id TEXT NOT NULL,event_id TEXT NOT NULL,received_at TEXT NOT NULL,PRIMARY KEY(host_id,job_id,event_id))",
                "CREATE TABLE IF NOT EXISTS schedules(host_id TEXT PRIMARY KEY,revision TEXT NOT NULL DEFAULT '',slot TEXT NOT NULL DEFAULT '')",
                "CREATE TABLE IF NOT EXISTS identities(id TEXT PRIMARY KEY,installation_id TEXT NOT NULL DEFAULT '',token_hash TEXT NOT NULL,status TEXT NOT NULL,payload TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS enrollments(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL,status TEXT NOT NULL,expires_millis INTEGER NOT NULL,payload TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS hosts_environment ON hosts(environment_id,id)",
                "CREATE INDEX IF NOT EXISTS hosts_hostname ON hosts(hostname COLLATE NOCASE,id)",
                "CREATE INDEX IF NOT EXISTS hosts_coverage ON hosts(coverage_status,id)",
                "CREATE INDEX IF NOT EXISTS inventory_host ON inventory(host_id,kind,id)",
                "CREATE INDEX IF NOT EXISTS inventory_version ON inventory(kind,version,host_id)",
                "CREATE INDEX IF NOT EXISTS inventory_major ON inventory(kind,major,host_id)",
                "CREATE INDEX IF NOT EXISTS inventory_type ON inventory(kind,type,host_id)",
                "CREATE INDEX IF NOT EXISTS jobs_host ON jobs(host_id,status,created_at)",
                "CREATE INDEX IF NOT EXISTS jobs_status_lease ON jobs(status,lease_expires)",
                "CREATE INDEX IF NOT EXISTS jobs_created ON jobs(created_at DESC,id)",
                "CREATE INDEX IF NOT EXISTS identities_token ON identities(token_hash,status)",
                "CREATE INDEX IF NOT EXISTS identities_installation ON identities(installation_id)",
                "CREATE INDEX IF NOT EXISTS enrollments_token ON enrollments(token_hash,status)",
                "CREATE INDEX IF NOT EXISTS receipts_host_received ON receipts(host_id,received_at)"
            };
            for(String sql:statements)exec(sql);
            return null;
        });
    }

    public Map<String,Object> get(String table,String id) {
        table(table);List<Map<String,Object>> result=documents("SELECT payload FROM "+table+" WHERE id=?",id);
        return result.isEmpty()?null:result.get(0);
    }

    public void put(String table,Map<String,Object> document) {
        table(table);
        String id=required(document,table.equals("identities")?"hostId":"id");
        String payload=encode(document);
        switch(table) {
            case "hosts":
                Map<String,Object> coverage=document.get("coverage") instanceof Map?object(document.get("coverage")):Map.of();
                exec("INSERT INTO hosts(id,environment_id,hostname,last_seen,captured_at,coverage_status,payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET environment_id=excluded.environment_id,hostname=excluded.hostname,last_seen=excluded.last_seen,captured_at=excluded.captured_at,coverage_status=excluded.coverage_status,payload=excluded.payload",
                    id,string(document.get("environmentId")),string(document.get("hostname")),string(document.get("lastSeen")),string(document.get("lastAssessmentAt")),coverage.getOrDefault("status","pending"),payload);break;
            case "environments":
                exec("INSERT INTO environments(id,name,payload) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,payload=excluded.payload",id,string(document.get("name")),payload);break;
            case "jobs":
                exec("INSERT INTO jobs(id,host_id,status,created_at,lease_expires,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET host_id=excluded.host_id,status=excluded.status,created_at=excluded.created_at,lease_expires=excluded.lease_expires,payload=excluded.payload",id,required(document,"hostId"),required(document,"status"),string(document.get("createdAt")),number(document.get("leaseExpires")),payload);break;
            case "identities":
                String tokenHash=document.containsKey("agentToken")?hash(string(document.get("agentToken"))):required(document,"tokenHash");
                exec("INSERT INTO identities(id,installation_id,token_hash,status,payload) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET installation_id=excluded.installation_id,token_hash=excluded.token_hash,status=excluded.status,payload=excluded.payload",id,string(document.get("installationId")),tokenHash,required(document,"status"),payload);break;
            case "agent_update_requests":
                exec("INSERT INTO agent_update_requests(id,host_id,batch_id,ordinal,status,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,payload=excluded.payload",id,required(document,"hostId"),required(document,"batchId"),number(document.get("ordinal")),required(document,"status"),payload);break;
            case "uninstall_requests":
                exec("INSERT INTO uninstall_requests(id,host_id,request_key,status,payload) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,payload=excluded.payload",id,required(document,"hostId"),required(document,"requestKey"),required(document,"status"),payload);break;
            case "enrollments":
                exec("INSERT INTO enrollments(id,token_hash,status,expires_millis,payload) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash,status=excluded.status,expires_millis=excluded.expires_millis,payload=excluded.payload",id,required(document,"tokenHash"),required(document,"status"),number(document.get("expiresMillis")),payload);break;
            default:throw new IllegalArgumentException("Unsupported document table");
        }
    }

    public void delete(String table,String id) {table(table);exec("DELETE FROM "+table+" WHERE id=?",id);}
    public Object metadata(String key) {
        List<Map<String,Object>> result=rows("SELECT payload FROM metadata WHERE key=?",key);
        return result.isEmpty()?null:Json.parse(string(result.get(0).get("payload")),STATE_LIMIT);
    }
    public void metadata(String key,Object value) {
        exec("INSERT INTO metadata(key,payload) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload",key,encode(value));
    }

    /** Replace one collection for one host in the surrounding result transaction. */
    static boolean includeRuntime(Map<String,Object> document) {
        return !"JRE".equalsIgnoreCase(string(document.get("kind")).trim()) || Boolean.TRUE.equals(document.get("inUse"));
    }
    public void putInventory(String kind,String hostId,List<Object> documents) {
        if(!KINDS.contains(kind))throw new IllegalArgumentException("Invalid inventory collection");
        if(hostId==null||hostId.isEmpty())throw new IllegalArgumentException("Missing inventory host");
        transaction(()->{
            exec("DELETE FROM inventory WHERE kind=? AND host_id=?",kind,hostId);
            for(Object value:documents) {
                Map<String,Object> d=object(value);
                if(!hostId.equals(d.get("hostId")))throw new IllegalArgumentException("Invalid inventory ownership");
                String id=required(d,"id");
                if("runtimes".equals(kind)&&!includeRuntime(d))continue;
                String version=string(d.get("version")),major=major(version);
                String type=string(d.getOrDefault("type",d.getOrDefault("kind",d.getOrDefault("category",""))));
                exec("INSERT INTO inventory(kind,host_id,id,version,major,type,observed_at,search_text,payload) VALUES(?,?,?,?,?,?,?,?,?)",
                    kind,hostId,id,version,major,type,string(d.get("observedAt")),searchText(d),encode(d));
            }
            return null;
        });
    }

    public List<Map<String,Object>> rows(String sql,Object...args) {
        return read(c->{
            try(PreparedStatement s=prepare(c,sql,args);ResultSet rs=s.executeQuery()) {
                List<Map<String,Object>> results=new ArrayList<>();ResultSetMetaData columns=rs.getMetaData();
                while(rs.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=1;i<=columns.getColumnCount();i++)row.put(columns.getColumnLabel(i),rs.getObject(i));
                    results.add(row);
                }
                return results;
            }
        });
    }

    /** Iterate within the caller's snapshot so consumers can enforce limits before materializing a fleet. */
    public void forEachRow(String sql,java.util.function.Consumer<Map<String,Object>> consumer,Object...args) {
        read(c->{
            try(PreparedStatement statement=prepare(c,sql,args);ResultSet rs=statement.executeQuery()){
                ResultSetMetaData columns=rs.getMetaData();
                while(rs.next()){
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=1;i<=columns.getColumnCount();i++)row.put(columns.getColumnLabel(i),rs.getObject(i));
                    consumer.accept(row);
                }
            }
            return null;
        });
    }

    public List<Map<String,Object>> documents(String sql,Object...args) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows(sql,args))result.add(object(Json.parse(string(row.get("payload")),STATE_LIMIT)));
        return result;
    }

    public long scalar(String sql,Object...args) {
        return read(c->{try(PreparedStatement s=prepare(c,sql,args);ResultSet r=s.executeQuery()){return r.next()?r.getLong(1):0L;}});
    }

    public void exec(String sql,Object...args) {
        if(Boolean.TRUE.equals(reading.get()))throw new IllegalStateException("Cannot write within a read snapshot");
        if(current.get()==null) {transaction(()->{exec(sql,args);return null;});return;}
        try(PreparedStatement s=prepare(current.get(),sql,args)) {s.executeUpdate();}
        catch(SQLException error) {throw failure("Inventory write failed",error);}
    }

    /** Nested transactions use savepoints so caught failures cannot commit partial writes. */
    public <T>T transaction(Callable<T> operation) {
        if(Boolean.TRUE.equals(reading.get()))throw new IllegalStateException("Cannot write within a read snapshot");
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            if(current.get()!=null) {
                Savepoint savepoint=null;
                try {savepoint=writer.setSavepoint();T result=operation.call();writer.releaseSavepoint(savepoint);return result;}
                catch(Throwable error) {
                    if(savepoint!=null)try {writer.rollback(savepoint);writer.releaseSavepoint(savepoint);}catch(SQLException rollback){error.addSuppressed(rollback);}
                    return rethrow(error);
                }
            }
            writeLock.lock();
            try {
                writer.setAutoCommit(false);current.set(writer);
                try {T result=operation.call();writer.commit();return result;}
                catch(Throwable error) {try{writer.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}return rethrow(error);}
                finally {current.remove();writer.setAutoCommit(true);}
            } catch(SQLException error) {throw failure("Inventory transaction failed",error);}
            finally {writeLock.unlock();}
        } finally {lifecycle.readLock().unlock();}
    }

    /** Keep related reads on one SQLite snapshot without holding the writer lock. */
    public <T>T readSnapshot(Callable<T> operation) {
        lifecycle.readLock().lock();Connection c=null;
        try {
            ensureOpen();
            if(current.get()!=null) {try{return operation.call();}catch(Exception e){return rethrow(e);}}
            c=readers.poll(5,TimeUnit.SECONDS);
            if(c==null)throw new IllegalStateException("Inventory readers are busy; please retry");
            c.setAutoCommit(false);current.set(c);reading.set(Boolean.TRUE);
            try {T result=operation.call();c.commit();return result;}
            catch(Throwable error) {try{c.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}return rethrow(error);}
            finally {current.remove();reading.remove();c.setAutoCommit(true);}
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw failure("Inventory read interrupted",e);}
        catch(SQLException e) {throw failure("Inventory snapshot failed",e);}
        finally {if(c!=null)readers.add(c);lifecycle.readLock().unlock();}
    }

    private interface Query<T>{T run(Connection connection)throws SQLException;}
    private <T>T read(Query<T> query) {
        lifecycle.readLock().lock();Connection c=null;
        try {
            ensureOpen();
            if(current.get()!=null)return query.run(current.get());
            c=readers.poll(5,TimeUnit.SECONDS);
            if(c==null)throw new IllegalStateException("Inventory readers are busy; please retry");
            return query.run(c);
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw failure("Inventory read interrupted",e);}
        catch(SQLException e) {throw failure("Inventory read failed",e);}
        finally {if(c!=null)readers.add(c);lifecycle.readLock().unlock();}
    }

    private static PreparedStatement prepare(Connection c,String sql,Object...args)throws SQLException {
        PreparedStatement s=c.prepareStatement(sql);
        try {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
        catch(SQLException e) {s.close();throw e;}
    }

    private void migrate(Map<String,Object> initial,String legacyHostId,String legacyToken)throws IOException {
        // No legacy files are even opened after the marker has committed.
        transaction(()->{
            if(metadata("migration.v1")!=null)return null;
            File stateFile=new File(data,"state.json"),authFile=new File(data,"auth-registry.json");
            Map<String,Object> state=stateFile.isFile()?readLegacy(stateFile,STATE_LIMIT):object(Json.parse(encode(initial==null?Map.of():initial),STATE_LIMIT));
            Map<String,Object> auth=authFile.isFile()?readLegacy(authFile,AUTH_LIMIT):Map.of();
            backup(stateFile,authFile);
            migrateState(state,legacyHostId);
            for(Map.Entry<String,Object> entry:optionalObject(auth,"hosts").entrySet()) {
                Map<String,Object> identity=new LinkedHashMap<>(object(entry.getValue()));
                if(!entry.getKey().equals(identity.get("hostId")))throw new IllegalArgumentException("Legacy identity ownership mismatch");
                put("identities",identity);
            }
            for(Map.Entry<String,Object> entry:optionalObject(auth,"enrollments").entrySet()) {
                Map<String,Object> enrollment=object(entry.getValue());
                if(!entry.getKey().equals(enrollment.get("id")))throw new IllegalArgumentException("Legacy enrollment identity mismatch");
                put("enrollments",enrollment);
            }
            if(legacyHostId!=null&&!legacyHostId.isEmpty()&&get("identities",legacyHostId)==null) {
                Map<String,Object> identity=new LinkedHashMap<>();identity.put("hostId",legacyHostId);identity.put("agentToken",legacyToken);
                identity.put("status","active");identity.put("legacy",true);identity.put("createdAt",Instant.now().toString());put("identities",identity);
            }
            metadata("migration.v1",Map.of("completedAt",Instant.now().toString(),"schemaVersion",1));
            return null;
        });
        protectDatabaseFiles();
    }

    private void migrateState(Map<String,Object> state,String legacyHostId) {
        Set<String> normalized=new HashSet<>(KINDS);normalized.addAll(Set.of("hosts","environments","jobs","eventIds","hostSchedules","lastScheduleSlot"));
        for(Map.Entry<String,Object> e:state.entrySet())if(!normalized.contains(e.getKey()))metadata(e.getKey(),e.getValue());
        List<Object> hosts=optionalList(state,"hosts");
        for(Object value:hosts) {
            Map<String,Object> host=new LinkedHashMap<>(object(value));host.putIfAbsent("environmentId","");
            host.putIfAbsent("coverage",hosts.size()==1&&state.get("coverage") instanceof Map?state.get("coverage"):Map.of("status","pending","warnings",List.of()));
            put("hosts",host);
        }
        for(String table:List.of("environments","jobs"))for(Object value:optionalList(state,table))put(table,object(value));
        for(String kind:KINDS) {
            Map<String,List<Object>> grouped=new LinkedHashMap<>();
            for(Object value:optionalList(state,kind)) {
                Map<String,Object> row=object(value);String hostId=required(row,"hostId");
                if(get("hosts",hostId)==null)throw new IllegalArgumentException("Legacy inventory references an unknown host");
                grouped.computeIfAbsent(hostId,key->new ArrayList<>()).add(row);
            }
            for(Map.Entry<String,List<Object>> group:grouped.entrySet())putInventory(kind,group.getKey(),group.getValue());
        }
        for(Object value:optionalList(state,"eventIds")) {
            if(!(value instanceof String))throw new IllegalArgumentException("Invalid legacy receipt");
            String[] parts=((String)value).split(":",3);
            if(parts.length!=3||parts[0].isEmpty()||parts[1].isEmpty()||parts[2].isEmpty())throw new IllegalArgumentException("Invalid legacy receipt");
            exec("INSERT OR IGNORE INTO receipts(host_id,job_id,event_id,received_at) VALUES(?,?,?,?)",parts[0],parts[1],parts[2],Instant.now().toString());
        }
        Map<String,Object> schedules=new LinkedHashMap<>(optionalObject(state,"hostSchedules"));
        if(state.containsKey("lastScheduleSlot")) {
            String slot=string(state.get("lastScheduleSlot")),prefix=string(state.get("scheduleRevision"))+":";
            if(slot.startsWith(prefix))slot=prefix+":"+slot.substring(prefix.length());
            schedules.put(legacyHostId,Map.of("slot",slot));
        }
        for(Map.Entry<String,Object> e:schedules.entrySet()) {
            Map<String,Object> schedule=object(e.getValue());
            exec("INSERT INTO schedules(host_id,revision,slot) VALUES(?,?,?)",e.getKey(),string(schedule.get("revision")),string(schedule.get("slot")));
        }
    }

    private static Map<String,Object> readLegacy(File file,int limit)throws IOException {
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("Legacy state must be a regular private file");
        if(file.length()>limit)throw new IOException("Legacy state exceeds migration limit");
        byte[] bytes;
        try(InputStream in=new FileInputStream(file)) {bytes=in.readNBytes(limit+1);}
        if(bytes.length>limit)throw new IOException("Legacy state exceeds migration limit");
        return object(Json.parse(new String(bytes,StandardCharsets.UTF_8),limit));
    }

    private void backup(File...sources)throws IOException {
        Path backup=null;
        for(File source:sources)if(source.isFile()) {
            if(backup==null) {backup=data.toPath().resolve("migration-backup-"+UUID.randomUUID());privateDirectory(backup);}
            privateFile(source.toPath());Path target=backup.resolve(source.getName());privateFile(target);
            try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target.toFile())) {in.transferTo(out);out.getFD().sync();}
        }
    }

    private void protectDatabaseFiles()throws IOException {
        for(String suffix:List.of("","-wal","-shm")) {
            Path path=data.toPath().resolve("inventory.sqlite"+suffix);if(Files.exists(path))privateFile(path);
        }
    }
    private static void privateDirectory(Path path)throws IOException {
        if(Files.isSymbolicLink(path))throw new IOException("Private storage directory must not be a symbolic link");
        try {Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));}
        catch(UnsupportedOperationException e) {Files.createDirectories(path);protectPortable(path.toFile(),true);}
    }
    private static void privateFile(Path path)throws IOException {
        if(Files.isSymbolicLink(path))throw new IOException("Private storage file must not be a symbolic link");
        try {if(!Files.exists(path))Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
        catch(UnsupportedOperationException e) {if(!Files.exists(path))Files.createFile(path);protectPortable(path.toFile(),false);}
    }
    private static void protectPortable(File file,boolean directory)throws IOException {
        if(!file.setReadable(false,false)||!file.setWritable(false,false)||!file.setExecutable(false,false)||!file.setReadable(true,true)||!file.setWritable(true,true)||(directory&&!file.setExecutable(true,true)))throw new IOException("Cannot protect private database storage");
    }

    public static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error) {throw failure("Cannot hash credential",error);}
    }
    private static String major(String version) {
        String normalized=version.startsWith("1.")?version.substring(2):version;
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("^(\\d+)").matcher(normalized);
        return matcher.find()?matcher.group(1):"";
    }
    private static String searchText(Map<String,Object> document) {
        StringBuilder text=new StringBuilder();
        for(Map.Entry<String,Object> entry:document.entrySet())if(entry.getValue() instanceof String||entry.getValue() instanceof Number)text.append(entry.getValue()).append(' ');
        if(document.get("databaseEndpoints") instanceof List<?> endpoints)for(Object value:endpoints)if(value instanceof Map<?,?> endpoint)for(String key:List.of("host","port")){Object field=endpoint.get(key);if(field instanceof String||field instanceof Number)text.append(field).append(' ');}
        return text.toString().toLowerCase(Locale.ROOT);
    }
    private static String encode(Object value) {String text=Json.stringify(value);if(text.length()>STATE_LIMIT)throw new IllegalArgumentException("Document exceeds storage limit");return text;}
    private static String required(Map<String,Object> value,String key) {Object s=value.get(key);if(!(s instanceof String)||((String)s).isEmpty())throw new IllegalArgumentException("Missing document "+key);return (String)s;}
    private static void table(String table) {if(!TABLES.contains(table))throw new IllegalArgumentException("Unsupported document table");}
    private static String string(Object value) {return value==null?"":String.valueOf(value);}
    private static long number(Object value) {return value instanceof Number?((Number)value).longValue():0L;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {if(!(value instanceof Map))throw new IllegalArgumentException("Expected stored object");return (Map<String,Object>)value;}
    private static Map<String,Object> optionalObject(Map<String,Object> value,String key) {return value.containsKey(key)?object(value.get(key)):Map.of();}
    @SuppressWarnings("unchecked") private static List<Object> optionalList(Map<String,Object> value,String key) {Object list=value.get(key);if(!value.containsKey(key))return List.of();if(!(list instanceof List))throw new IllegalArgumentException("Expected stored array: "+key);return (List<Object>)list;}
    private static RuntimeException failure(String message,Exception error) {return new IllegalStateException(message,error);}
    private static <T>T rethrow(Throwable error) {if(error instanceof Error)throw (Error)error;if(error instanceof RuntimeException)throw (RuntimeException)error;throw new IllegalStateException("Inventory transaction failed",error);}
    private void ensureOpen() {if(closed)throw new IllegalStateException("Inventory database is closed");}

    @Override public void close() {
        if(current.get()!=null)throw new IllegalStateException("Cannot close the database during a transaction");
        lifecycle.writeLock().lock();
        try {
            if(closed)return;closed=true;
            for(Connection c:readers)try {c.close();}catch(SQLException ignored){}
            readers.clear();
            if(writer!=null)try {writer.close();}catch(SQLException ignored){}
        } finally {lifecycle.writeLock().unlock();}
    }
}
