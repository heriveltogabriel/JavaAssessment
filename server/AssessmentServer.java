package assessment;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Java Assessment dashboard. Java 17 server; Java 6 collector protocol retained. */
public final class AssessmentServer implements HttpHandler,AutoCloseable {
    private final File root,data;
    private final Properties conf;
    final InventoryStore store;
    private final InventoryQueries queries;
    private final EnrollmentRegistry registry;
    private final UninstallRegistry uninstalls;
    private final AgentUpdateCatalog updateCatalog;
    private final AgentUpdateRegistry updates;
    private final WorkRequests workRequests;
    final UserAuth auth;
    private java.nio.channels.FileChannel authLockChannel;
    private java.nio.channels.FileLock authLock;
    private final ThreadLocal<UserAuth.User> requestUser=new ThreadLocal<>();
    private final ThreadLocal<String> agentHost=new ThreadLocal<>();
    private final ThreadLocal<String> requestBody=new ThreadLocal<>();
    private final Map<String,long[]> registrationAttempts=new HashMap<>(),attempts=new HashMap<>();
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final Semaphore exportSlot=new Semaphore(1);
    private static final String[] COLLECTIONS={"runtimes","processes","servers","libraries","applications","datasources"};
    private static class Session {
        final String csrf,userId;final long revision,expires;volatile long lastSeen;
        Session(String csrf,UserAuth.User user){this.csrf=csrf;userId=user.id();revision=user.revision();lastSeen=System.currentTimeMillis();expires=lastSeen+8*3600000L;}
        boolean live(long now){return expires>now&&now-lastSeen<30*60000L;}
    }
    private static class ApiError extends RuntimeException{final int status;ApiError(int s,String m){super(m);status=s;}}
    private record Reply(int status,Object body){}
    private record BinaryReply(byte[] data,String contentType){}
    private record PackageReply(String environmentName,String url,String token,String environment){}
    public static Map<String,Object> map(Object... values){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object o){if(!(o instanceof Map))throw new ApiError(400,"Expected an object");return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object o){if(!(o instanceof List))throw new ApiError(400,"Expected an array");return (List<Object>)o;}
    private static String str(Object o){return o==null?"":String.valueOf(o);}
    private static long number(Object o,long fallback){return o instanceof Number?((Number)o).longValue():fallback;}
    public static String now(){return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"){{setTimeZone(TimeZone.getTimeZone("UTC"));}}.format(new Date());}
    public AssessmentServer(File root)throws Exception {this(root,false);}
    AssessmentServer(File root,boolean provisioning)throws Exception {
        this.root=root.getCanonicalFile();data=new File(root,"data");conf=new Properties();try(InputStream in=new FileInputStream(new File(root,"conf/server.properties"))){conf.load(in);}
        for(String k:new String[]{"agentToken","hostId"})if(conf.getProperty(k,"").length()<4)throw new IllegalArgumentException("Missing server configuration: "+k);
        boolean legacy=Boolean.parseBoolean(conf.getProperty("seedLegacyHost","true"));
        Map<String,Object> defaults=map("fleet",map("name",conf.getProperty("fleetName","Java Assessment"),"description","Java estate assessment","createdAt",now()),"hosts",new ArrayList<>(),"jobs",new ArrayList<>(),"environments",new ArrayList<>(),"settings",map("enabled",false,"frequency","once","timeZone","America/Sao_Paulo","windowStart","00:00","windowEnd","06:00","maxDurationSeconds",120,"maxFiles",10000,"batchSize",100,"pauseMillis",30,"includePaths",legacy?Arrays.asList("/home/opc/oracle","/usr/java","/usr/lib/jvm","/opt"):Arrays.asList("/usr/java","/usr/lib/jvm","/opt","/u01"),"excludePaths",Arrays.asList("/proc","/sys","/dev","/tmp"),"inspectArchives",false),"coverage",map("status","pending","warnings",new ArrayList<>()),"eventIds",new ArrayList<>(),"scheduleRevision",UUID.randomUUID().toString());
        for(String key:COLLECTIONS)defaults.put(key,new ArrayList<>());
        if(legacy)list(defaults.get("hosts")).add(map("id",conf.getProperty("hostId"),"hostname","instance-rh5","environmentId","","status","Awaiting assessment"));
        acquireAuthLock();
        InventoryStore opened=null;
        try{
            opened=new InventoryStore(data,defaults,legacy?conf.getProperty("hostId"):"",conf.getProperty("agentToken"));store=opened;
            auth=new UserAuth(store);
            if(!provisioning&&auth.bootstrapRequired()&&(conf.getProperty("adminSalt","").length()<4||!conf.getProperty("adminPasswordSha256","").matches("[a-fA-F0-9]{64}")))throw new IllegalArgumentException("Provision the initial administrator before starting the server");
            registry=new EnrollmentRegistry(store);uninstalls=new UninstallRegistry(store,registry);updateCatalog=new AgentUpdateCatalog(this.root);updates=new AgentUpdateRegistry(store,registry,updateCatalog);queries=new InventoryQueries(store);workRequests=new WorkRequests(store);
        }catch(Exception e){if(opened!=null)opened.close();releaseAuthLock();throw e;}
    }
    private void acquireAuthLock()throws IOException {
        java.nio.file.Path directory=data.toPath();
        if(java.nio.file.Files.isSymbolicLink(directory))throw new IOException("Dashboard data directory cannot be a symbolic link");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Path path=directory.resolve(".human-auth.lock");
        authLockChannel=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        try{
            try{java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            authLock=authLockChannel.tryLock();if(authLock==null)throw new IOException("Stop the dashboard service before local account maintenance");
        }catch(java.nio.channels.OverlappingFileLockException e){releaseAuthLock();throw new IOException("Dashboard is already running; stop it before local account maintenance");}
        catch(IOException e){releaseAuthLock();throw e;}
    }
    private void releaseAuthLock(){try{if(authLock!=null)authLock.release();}catch(IOException ignored){}try{if(authLockChannel!=null)authLockChannel.close();}catch(IOException ignored){}}
    public void close(){try{store.close();}finally{sessions.clear();releaseAuthLock();}}
    public static String read(File f,int max) throws IOException{FileInputStream in=new FileInputStream(f);try{return readStream(in,max);}finally{in.close();}}
    private static String readStream(InputStream in,int max) throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(b.size()+n>max)throw new ApiError(413,"Request too large");b.write(buf,0,n);}return new String(b.toByteArray(),"UTF-8");}
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return hex(bytes);}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes){String h=Integer.toHexString(x&255);if(h.length()<2)b.append('0');b.append(h);}return b.toString();}
    private static boolean same(String a,String b){try{return MessageDigest.isEqual(a.getBytes("UTF-8"),b.getBytes("UTF-8"));}catch(Exception e){return false;}}
    private boolean legacyPassword(String password)throws Exception {
        if(password.length()>512||auth.bootstrapRequired()==false)return false;
        String salt=conf.getProperty("adminSalt",""),hash=conf.getProperty("adminPasswordSha256","");
        return salt.length()>=4&&hash.length()==64&&same(hex(MessageDigest.getInstance("SHA-256").digest((salt+password).getBytes("UTF-8"))),hash);
    }
    private Session session(HttpExchange x){
        String cookies=x.getRequestHeaders().getFirst("Cookie");if(cookies==null||cookies.length()>8192)return null;
        for(String cookie:cookies.split(";")){cookie=cookie.trim();if(!cookie.startsWith("assessment_session="))continue;String key=cookie.substring(19);Session session=sessions.get(key);long now=System.currentTimeMillis();
            if(session!=null&&session.live(now)){try{auth.current(session.userId,session.revision);if(!"1".equals(x.getRequestHeaders().getFirst("X-Assessment-Background")))session.lastSeen=now;return session;}catch(UserAuth.Failure ignored){}}
            sessions.remove(key);
        }return null;
    }
    private UserAuth.User authority(Session session){UserAuth.User user=auth.current(session.userId,session.revision);requestUser.set(user);return user;}
    private boolean reader(){UserAuth.User user=requestUser.get();return user!=null&&user.role().equals("READER");}
    private Object readerView(Object value){
        if(!reader())return value;
        if(value instanceof Map<?,?> source){Map<String,Object> clean=new LinkedHashMap<>();for(var entry:source.entrySet()){
            String key=String.valueOf(entry.getKey()),normalized=key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","");
            if(Set.of("enrollments","defaultsettings","settings","settingsoverrides","effectivesettings","credentialstatus","uninstall","initialassessmentrequested","scheduleRevision".toLowerCase(Locale.ROOT),"requestkey","nonce","commandid","installationid").contains(normalized)||normalized.contains("password")||normalized.contains("token")||normalized.contains("secret")||normalized.contains("credential")||normalized.contains("salt")||normalized.contains("hash"))continue;
            clean.put(key,readerView(entry.getValue()));
        }return clean;}
        if(value instanceof List<?> source){List<Object> clean=new ArrayList<>();for(Object item:source)clean.add(readerView(item));return clean;}return value;
    }
    private void authorize(HttpExchange x,Session session)throws Exception{
        UserAuth.User user=authority(session);String path=x.getRequestURI().getPath();
        if(Set.of("/api/session","/api/account/password","/api/logout").contains(path))return;
        if(user.mustChangePassword())throw new ApiError(403,"Change your temporary password before continuing");
        if(user.role().equals("ADMIN"))return;
        if(!x.getRequestMethod().equals("GET")||!Set.of("/api/state","/api/item","/api/export.csv","/api/export","/api/work-requests","/api/work-requests/active","/api/work-requests/detail","/api/reports","/api/reports/options","/api/reports/export.csv","/api/reports/export.pdf").contains(path)||query(x,"view").equals("settings"))throw new ApiError(403,"Administrator access required");
    }
    private String bearer(HttpExchange x){String v=str(x.getRequestHeaders().getFirst("Authorization"));return v.startsWith("Bearer ")?v.substring(7):"";}
    private String agent(HttpExchange x){return registry.authenticate(bearer(x));}
    private static boolean local(HttpExchange x){return x.getRemoteAddress().getAddress().isLoopbackAddress();}
    private void headers(HttpExchange x){Headers h=x.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("X-Frame-Options","DENY");h.set("Referrer-Policy","no-referrer");h.set("Cache-Control","no-store");h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");}
    private void json(HttpExchange x,int status,Object o)throws IOException{byte[] b=Json.stringify(o).getBytes("UTF-8");x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);}
    private void file(HttpExchange x,File f,String type)throws IOException{if(!f.isFile())throw new ApiError(404,"Not found");x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(200,f.length());FileInputStream in=new FileInputStream(f);try{byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)x.getResponseBody().write(b,0,n);}finally{in.close();}}
    private Map<String,Object> body(HttpExchange x)throws IOException{return obj(Json.parse(str(requestBody.get())));}
    private void method(HttpExchange x,String expected){if(!expected.equals(x.getRequestMethod()))throw new ApiError(405,"Method not allowed");}
    private void csrf(HttpExchange x,Session s){if(!same(str(x.getRequestHeaders().getFirst("X-CSRF-Token")),s.csrf))throw new ApiError(403,"Invalid request token");}
    private String query(HttpExchange x,String name)throws Exception{String q=x.getRequestURI().getRawQuery();if(q!=null)for(String item:q.split("&")){String[] kv=item.split("=",2);if(URLDecoder.decode(kv[0],"UTF-8").equals(name))return kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"";}return "";}
    private static int minutes(String s){if(!s.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))throw new ApiError(400,"Invalid time");return Integer.parseInt(s.substring(0,2))*60+Integer.parseInt(s.substring(3));}
    private Map<String,Object> settings(Map<String,Object> incoming){Map<String,Object> s=new LinkedHashMap<String,Object>(obj(store.metadata("settings")));for(String k:incoming.keySet())if(!s.containsKey(k))throw new ApiError(400,"Unknown setting: "+k);s.putAll(incoming);if(!(s.get("enabled") instanceof Boolean)||!(s.get("inspectArchives") instanceof Boolean))throw new ApiError(400,"Boolean setting expected");if(!Arrays.asList("once","daily","weekly").contains(s.get("frequency")))throw new ApiError(400,"Invalid frequency");if(!Arrays.asList(TimeZone.getAvailableIDs()).contains(str(s.get("timeZone"))))throw new ApiError(400,"Unknown timezone");minutes(str(s.get("windowStart")));minutes(str(s.get("windowEnd")));String[] nums={"maxDurationSeconds","maxFiles","batchSize","pauseMillis"};int[] min={10,100,1,0},max={900,50000,500,2000};for(int i=0;i<nums.length;i++){Object n=s.get(nums[i]);long v=number(n,-1);if(!(n instanceof Number)||((Number)n).doubleValue()!=v||v<min[i]||v>max[i])throw new ApiError(400,"Invalid "+nums[i]);}for(String k:new String[]{"includePaths","excludePaths"}){List<Object> paths=list(s.get(k));if(paths.size()>20||k.equals("includePaths")&&paths.isEmpty())throw new ApiError(400,"Invalid path count");for(Object p:paths){String path=str(p);if(!(p instanceof String)||!path.startsWith("/")||path.length()>512||path.equals("/")||path.indexOf('\0')>=0||path.indexOf("..")>=0)throw new ApiError(400,"Use explicit absolute scan paths");}}return s;}
    private SSLContext tls()throws Exception{
        Security.addProvider((Provider)Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").newInstance());Security.addProvider((Provider)Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider").newInstance());
        String pem=read(new File(root,"conf/tls/privkey.pem"),65536);String content=pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");byte[] key=decode64(content);PrivateKey privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
        CertificateFactory cf=CertificateFactory.getInstance("X.509");FileInputStream in=new FileInputStream(new File(root,"conf/tls/fullchain.pem"));Collection<? extends java.security.cert.Certificate> certs;try{certs=cf.generateCertificates(in);}finally{in.close();}KeyStore ks=KeyStore.getInstance("JKS");ks.load(null,null);char[] password=token().toCharArray();ks.setKeyEntry("server",privateKey,password,certs.toArray(new java.security.cert.Certificate[certs.size()]));KeyManagerFactory km=KeyManagerFactory.getInstance("PKIX","BCJSSE");km.init(ks,password);SSLContext ctx=SSLContext.getInstance("TLS","BCJSSE");ctx.init(km.getKeyManagers(),null,new SecureRandom());return ctx;
    }
    private static byte[] decode64(String s){String chars="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";ByteArrayOutputStream out=new ByteArrayOutputStream();int v=0,bits=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='=')break;int n=chars.indexOf(c);if(n<0)throw new IllegalArgumentException("Invalid PEM");v=(v<<6)|n;bits+=6;if(bits>=8){bits-=8;out.write((v>>bits)&255);}}return out.toByteArray();}
    private void checkHost(String id){host(id);if(!"active".equals(registry.credentialStatus(id)))throw new ApiError(403,"Collector credential has been revoked");}
    private void ownHost(String id){if(!same(id,str(agentHost.get())))throw new ApiError(403,"Collector is not registered for this host");checkHost(id);}
    private Map<String,Object> host(String id){Map<String,Object> h=store.get("hosts",id);if(h==null)throw new ApiError(404,"Host not found");return h;}
    private Map<String,Object> environment(String id){if(id.isEmpty())return null;Map<String,Object> e=store.get("environments",id);if(e==null)throw new ApiError(404,"Environment not found");return e;}
    private Set<String> environmentIds(){Set<String> ids=new HashSet<>();for(Map<String,Object> e:store.rows("SELECT id FROM environments"))ids.add(str(e.get("id")));return ids;}
    private Map<String,Object> effectiveWithHost(Map<String,Object> h,Map<String,Object> overrides){Map<String,Object> env=environment(str(h.get("environmentId")));Map<String,Object> merged=env==null?map():new LinkedHashMap<>(obj(env.get("settings")));merged.putAll(overrides);return settings(merged);}
    private Map<String,Object> effectiveSettings(String id){Map<String,Object> h=host(id);return effectiveWithHost(h,h.get("settings") instanceof Map?obj(h.get("settings")):map());}
    private Map<String,Object> publicHost(Map<String,Object> h){Map<String,Object> copy=new LinkedHashMap<>(h);Map<String,Object> env=environment(str(h.get("environmentId")));copy.remove("initialAssessmentRequested");copy.put("uninstall",uninstalls.publicRecord(uninstalls.latest(str(h.get("id")))));copy.put("update",updates.publicRecord(updates.latest(str(h.get("id")))));copy.put("environmentName",env==null?"Sem ambiente":env.get("name"));copy.put("credentialStatus",registry.credentialStatus(str(h.get("id"))));copy.put("settingsOverrides",h.get("settings") instanceof Map?h.get("settings"):map());copy.put("settings",effectiveWithHost(h,obj(copy.get("settingsOverrides"))));return copy;}
    private Map<String,Object> publicEnvironment(Map<String,Object> env){Map<String,Object> copy=new LinkedHashMap<>(env);copy.put("settingsOverrides",env.get("settings"));copy.put("effectiveSettings",settings(obj(env.get("settings"))));return copy;}
    private void expireJobs(){
        for(Map<String,Object> j:store.documents("SELECT payload FROM jobs WHERE status='RUNNING' AND lease_expires<? LIMIT 1000",System.currentTimeMillis())){j.put("status","FAILED");j.put("error","Collector exceeded lease or did not report a result");j.put("finishedAt",now());store.put("jobs",j);updates.assessments().jobChanged(j,System.currentTimeMillis());}
    }
    private Map<String,Object> queue(String hostId,String trigger){
        checkHost(hostId);updates.maintain();if(AgentUpdateRegistry.busy(store,hostId))throw new ApiError(409,"Agent update is pending or in progress");uninstalls.maintain();if(UninstallRegistry.busy(uninstalls.latest(hostId)))throw new ApiError(409,"Agent uninstall is pending or in progress");if(store.scalar("SELECT count(*) FROM jobs WHERE host_id=? AND status IN ('QUEUED','RUNNING')",hostId)>0)throw new ApiError(409,"An assessment is already queued or running");
        Map<String,Object> j=map("id",UUID.randomUUID().toString(),"hostId",hostId,"status","QUEUED","createdAt",now(),"trigger",trigger,"summary","Waiting for collector");j.putAll(WorkRequests.snapshot(store,host(hostId),hostId));UserAuth.User actor=requestUser.get();j.put("actorName",actor!=null?actor.name():trigger.equals("scheduled")?"Agendamento":"Agente");store.put("jobs",j);trimJobs(hostId);return j;
    }
    private void trimJobs(String id){
        for(var old:store.documents("SELECT j.payload FROM jobs j WHERE j.host_id=? AND j.status NOT IN ('QUEUED','RUNNING') AND j.id IN (SELECT job_id FROM agent_update_assessments WHERE host_id=?)",id,id))updates.assessments().jobChanged(old,System.currentTimeMillis());
        store.exec("DELETE FROM jobs WHERE host_id=? AND status NOT IN ('QUEUED','RUNNING') AND id NOT IN (SELECT id FROM jobs WHERE host_id=? AND status NOT IN ('QUEUED','RUNNING') ORDER BY created_at DESC,id DESC LIMIT ?)",id,id,Math.max(10,Math.min(1000,Integer.parseInt(conf.getProperty("jobHistoryPerHost","100")))));
        store.exec("DELETE FROM receipts WHERE host_id=? AND rowid NOT IN (SELECT rowid FROM receipts WHERE host_id=? ORDER BY received_at DESC,rowid DESC LIMIT ?)",id,id,Math.max(200,Math.min(2000,Integer.parseInt(conf.getProperty("receiptHistoryPerHost","200")))));
    }
    private void schedule(String id){
        Map<String,Object> s=effectiveSettings(id);if(!Boolean.TRUE.equals(s.get("enabled")))return;
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone(str(s.get("timeZone"))));int minute=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE),start=minutes(str(s.get("windowStart"))),end=minutes(str(s.get("windowEnd")));if(!(start==end||(start<end?minute>=start&&minute<end:minute>=start||minute<end)))return;
        List<Map<String,Object>> records=store.rows("SELECT revision,slot FROM schedules WHERE host_id=?",id);Map<String,Object> record=records.isEmpty()?map():records.get(0),env=environment(str(host(id).get("environmentId")));
        String slot=store.metadata("scheduleRevision")+":"+str(record.get("revision"))+(env==null?"":":env:"+env.get("id")+":"+env.get("revision"))+":"+id+":";
        String frequency=str(s.get("frequency"));slot+=frequency.equals("once")?"once":frequency.equals("weekly")?c.get(Calendar.YEAR)+"-W"+c.get(Calendar.WEEK_OF_YEAR):c.get(Calendar.YEAR)+"-"+c.get(Calendar.DAY_OF_YEAR);
        if(slot.equals(record.get("slot")))return;
        try{queue(id,"scheduled");store.exec("INSERT INTO schedules(host_id,revision,slot) VALUES(?,?,?) ON CONFLICT(host_id) DO UPDATE SET slot=excluded.slot",id,str(record.get("revision")),slot);}catch(ApiError e){if(e.status!=409)throw e;}
    }
    private void resetSchedule(String id){store.exec("INSERT INTO schedules(host_id,revision,slot) VALUES(?,?,'') ON CONFLICT(host_id) DO UPDATE SET revision=excluded.revision,slot=''",id,UUID.randomUUID().toString());}
    private String serverUrl(){return conf.getProperty("publicUrl","https://"+conf.getProperty("publicHost","localhost"));}
    private static String bounded(Object value,String name,int max,boolean required){if(!(value instanceof String))throw new ApiError(400,"Invalid "+name);String s=(String)value;if(s.length()>max||(required&&s.trim().isEmpty())||s.matches("(?s).*[\\x00-\\x1f\\x7f].*"))throw new ApiError(400,"Invalid "+name);return s;}
    private static int integer(Object value,int fallback,int min,int max){if(value==null)return fallback;if(!(value instanceof Number))throw new ApiError(400,"Integer expected");long n=((Number)value).longValue();if(((Number)value).doubleValue()!=n||n<min||n>max)throw new ApiError(400,"Integer outside allowed range");return (int)n;}
    private synchronized void rateRegistration(HttpExchange x){String addr=x.getRemoteAddress().getAddress().getHostAddress();long now=System.currentTimeMillis();registrationAttempts.entrySet().removeIf(e->now-e.getValue()[0]>60000);long[] rate=registrationAttempts.get(addr);if(rate==null){if(registrationAttempts.size()>=4096)throw new ApiError(429,"Please retry later");rate=new long[]{now,0};registrationAttempts.put(addr,rate);}if(++rate[1]>30)throw new ApiError(429,"Please wait one minute before retrying");}
    private Map<String,Object> register(HttpExchange x)throws Exception{
        method(x,"POST");rateRegistration(x);Map<String,Object> b=body(x);String installationId=bounded(b.get("installationId"),"installationId",100,true);if(!installationId.matches("[A-Za-z0-9_-]{16,100}"))throw new ApiError(400,"Invalid installation identifier");
        Map<String,Object> meta=map();for(String key:new String[]{"hostname","collectorVersion","javaVersion","osName","architecture"})meta.put(key,bounded(b.get(key),key,200,true));
        Map<String,Object> identity=registry.register(bearer(x),installationId,meta,environmentIds());String id=str(identity.get("hostId"));Map<String,Object> h=store.get("hosts",id);
        if(h==null){h=map("id",id,"environmentId",str(identity.get("environmentId")),"hostname",meta.get("hostname"),"collectorJavaVersion",meta.get("javaVersion"),"collectorVersion",meta.get("collectorVersion"),"osName",meta.get("osName"),"architecture",meta.get("architecture"),"registeredAt",identity.get("createdAt"),"status","Registered — awaiting first contact","settings",identity.get("settings"),"coverage",map("status","pending","warnings",new ArrayList<>()),"installation",map("status","registering","updatedAt",now(),"events",new ArrayList<>()));store.put("hosts",h);}
        if(Boolean.TRUE.equals(identity.get("autoCollect"))&&!Boolean.TRUE.equals(h.get("initialAssessmentRequested"))){try{queue(id,"registration");}catch(ApiError e){if(e.status!=409)throw e;}h.put("initialAssessmentRequested",true);store.put("hosts",h);}
        return map("registered",true,"hostId",id,"agentToken",identity.get("agentToken"),"serverUrl",serverUrl(),"settings",effectiveSettings(id));
    }
    private Map<String,Object> installEvents(HttpExchange x)throws Exception{
        method(x,"POST");Map<String,Object> b=body(x);String id=str(b.get("hostId"));ownHost(id);List<Object> incoming=list(b.get("events"));if(incoming.size()>30)throw new ApiError(400,"Too many installation events");
        Map<String,Object> h=host(id),installation=h.get("installation") instanceof Map?obj(h.get("installation")):map("status","registering","events",new ArrayList<>());List<Object> events=list(installation.get("events"));boolean changed=false;
        for(Object value:incoming){Map<String,Object> e=obj(value);String event=bounded(e.get("eventId"),"eventId",100,true),time=bounded(e.get("time"),"time",40,true),stage=str(e.get("stage")),status=str(e.get("status"));if(!event.matches("[A-Za-z0-9_-]{8,100}")||!time.matches("\\d{4}-\\d{2}-\\d{2}T.*Z"))throw new ApiError(400,"Invalid event identity");if(!Arrays.asList("system","java","permissions","tls","registration","scheduler","heartbeat","complete","diagnostic","failure").contains(stage)||!Arrays.asList("INFO","OK","WARNING","ERROR").contains(status))throw new ApiError(400,"Invalid event stage or status");String message=bounded(e.get("message"),"message",300,false);if(message.contains(bearer(x)))message="Credential value removed from event";
            if(events.stream().anyMatch(prior->event.equals(obj(prior).get("eventId"))))continue;events.add(map("eventId",event,"time",time,"stage",stage,"status",status,"message",message));changed=true;
            if(time.compareTo(str(installation.get("lastEventAt")))>=0){if(status.equals("ERROR"))installation.put("status","failed");else if(stage.equals("complete")&&status.equals("OK"))installation.put("status","installed");installation.put("lastEventAt",time);}while(events.size()>100)events.remove(0);
        }
        if(changed){installation.put("updatedAt",now());h.put("installation",installation);store.put("hosts",h);}return map("accepted",true);
    }
    private void validateHost(Map<String,Object> h,Map<String,Object> coverage){
        for(String key:new String[]{"hostname","fqdn","osName","distribution","kernelVersion","architecture","cpuModel"})if(h.get(key)!=null)bounded(h.get(key),key,1000,false);
        for(String key:new String[]{"logicalCpus","memoryTotalBytes","memoryAvailableBytes","memoryFreeBytes"})if(h.get(key)!=null&&(!(h.get(key) instanceof Number)||number(h.get(key),-1)<0))throw new ApiError(400,"Invalid host resource "+key);
        if(h.containsKey("ipAddresses")){List<Object> addresses=list(h.get("ipAddresses"));if(addresses.size()>64)throw new ApiError(400,"Too many addresses");for(Object address:addresses)bounded(address,"address",128,true);}
        if(h.containsKey("disks")){List<Object> disks=list(h.get("disks"));if(disks.size()>128)throw new ApiError(400,"Too many disks");for(Object disk:disks){Map<String,Object> d=obj(disk);bounded(d.get("mount"),"mount",4096,true);for(String key:new String[]{"totalBytes","availableBytes"})if(d.get(key)!=null&&(!(d.get(key) instanceof Number)||number(d.get(key),-1)<0))throw new ApiError(400,"Invalid disk size");}}
        if(!coverage.containsKey("warnings"))coverage.put("warnings",new ArrayList<>());List<Object> notes=list(coverage.get("warnings"));if(notes.size()>500)throw new ApiError(400,"Too many coverage notes");for(Object note:notes)bounded(note,"coverage note",2000,false);
        if(coverage.containsKey("stats"))obj(coverage.get("stats"));else coverage.put("stats",map());
    }
    private Map<String,Object> accept(HttpExchange x)throws Exception{
        method(x,"POST");Map<String,Object> snap=body(x),h=obj(snap.get("host"));String id=str(h.get("id"));ownHost(id);String event=str(snap.get("eventId")),jobId=str(snap.get("jobId"));if(!event.matches("[A-Za-z0-9-]{8,80}")||!"1.0".equals(snap.get("schemaVersion")))throw new ApiError(400,"Invalid snapshot identity");
        if(store.scalar("SELECT count(*) FROM receipts WHERE host_id=? AND job_id=? AND event_id=?",id,jobId,event)>0)return map("accepted",true,"eventId",event,"duplicate",true);
        Map<String,Object> job=store.get("jobs",jobId);if(job==null||!id.equals(job.get("hostId")))throw new ApiError(409,"Assessment has no active lease");
        boolean late="FAILED".equals(job.get("status"))&&Arrays.asList("Server restarted before result acknowledgement","Collector exceeded lease or did not report a result").contains(job.get("error"));if(!"RUNNING".equals(job.get("status"))&&!late)throw new ApiError(409,"Assessment has no active lease");
        String captured=str(snap.get("capturedAt"));try{java.time.Instant.parse(captured);}catch(Exception e){throw new ApiError(400,"Missing capture timestamp");}
        Map<String,Object> coverage=obj(snap.get("coverage"));if(!Arrays.asList("complete","partial").contains(coverage.get("status")))throw new ApiError(400,"Invalid coverage");
        for(String key:COLLECTIONS){List<Object> rows=list(snap.get(key));if(rows.size()>2000)throw new ApiError(400,"Too many "+key);Set<String> seen=new HashSet<>();for(Object row:rows){Map<String,Object> r=obj(row);String rid=str(r.get("id"));if(Json.stringify(r).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new ApiError(400,"Inventory record exceeds 16 KiB");if(!id.equals(r.get("hostId"))||rid.isEmpty()||rid.length()>300||!seen.add(rid))throw new ApiError(400,"Invalid inventory ownership or duplicate ID");}}
        validateHost(h,coverage);Map<String,Object> current=host(id);boolean newer=str(current.get("lastAssessmentAt")).isEmpty()||java.time.Instant.parse(captured).compareTo(java.time.Instant.parse(str(current.get("lastAssessmentAt"))))>=0;
        if(newer){for(String key:new String[]{"hostname","fqdn","osName","distribution","kernelVersion","architecture","cpuModel","logicalCpus","memoryTotalBytes","memoryAvailableBytes","memoryFreeBytes","ipAddresses","disks"})if(h.containsKey(key))current.put(key,h.get(key));
            current.put("lastAssessmentAt",captured);current.put("lastSeen",now());current.put("collectorJavaVersion",snap.get("collectorJavaVersion"));current.put("status","Collected");if(!current.containsKey("firstAssessmentAt"))current.put("firstAssessmentAt",captured);coverage.put("lastAssessmentAt",captured);current.put("coverage",coverage);if(Json.stringify(current).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new ApiError(400,"Host details exceed 64 KiB");store.put("hosts",current);
            for(String kind:COLLECTIONS)store.putInventory(kind,id,list(snap.get(kind)));
        }
        long javaCount=list(snap.get("runtimes")).stream().filter(row->InventoryStore.includeRuntime(obj(row))).count();
        job.put("lateResult",late);job.remove("error");job.put("status","partial".equals(coverage.get("status"))?"PARTIAL":"SUCCEEDED");job.put("finishedAt",now());job.put("summary","Assessment received — "+javaCount+" Java installation(s), "+list(snap.get("servers")).size()+" server(s)");job.put("stats",coverage.get("stats"));
        // Coverage belongs to this result, including a late result older than the host's latest inventory.
        job.put("capturedAt",captured);job.put("coverage",map("status",coverage.get("status"),"warnings",WorkRequests.observationNotes(coverage.get("warnings"),bearer(x))));
        store.put("jobs",job);updates.assessments().jobChanged(job,System.currentTimeMillis());store.exec("INSERT INTO receipts(host_id,job_id,event_id,received_at) VALUES(?,?,?,?)",id,jobId,event,now());trimJobs(id);
        return map("accepted",true,"eventId",event);
    }
    private InventoryQueries.Scope selectedScope(HttpExchange x)throws Exception{
        InventoryQueries.Scope f=new InventoryQueries.Scope();f.environment=query(x,"environmentId");f.host=query(x,"hostId");if(!f.environment.isEmpty()&&!f.environment.equals("__unassigned__"))environment(f.environment);
        if(!f.host.isEmpty()){Map<String,Object> h=host(f.host);String env=f.environment.equals("__unassigned__")?"":f.environment;if(!f.environment.isEmpty()&&!env.equals(str(h.get("environmentId"))))throw new ApiError(404,"Machine is outside this environment");}
        return f;
    }
    private InventoryQueries.Scope scope(HttpExchange x)throws Exception{
        InventoryQueries.Scope f=selectedScope(x);
        String view=query(x,"view");if(!view.isEmpty())f.view=view;if(!Set.of("overview","machine","environments","versions","hosts","runtimes","applications","servers","libraries","datasources","processes","jobs","settings").contains(f.view))throw new ApiError(400,"Invalid view");
        f.page=queryInteger(x,"page",1,1,1000000);f.size=queryInteger(x,"pageSize",20,1,100);f.q=query(x,"q");if(f.q.length()>200)throw new ApiError(400,"Search is too long");f.subset=query(x,"subset");f.version=query(x,"version");f.mode=query(x,"versionMode").equals("exact")?"exact":"family";
        f.from=InventoryQueries.time(query(x,"from"),false);f.to=InventoryQueries.time(query(x,"to"),true);String range=query(x,"range");if(f.from.isEmpty()&&!range.isEmpty()&&!range.equals("all")){long hours=switch(range){case "24h"->24;case "7d"->168;case "30d"->720;default->throw new ApiError(400,"Invalid range");};f.from=java.time.Instant.now().minusSeconds(hours*3600).toString();}
        if(!f.from.isEmpty()&&!f.to.isEmpty()&&!java.time.Instant.parse(f.from).isBefore(java.time.Instant.parse(f.to)))throw new ApiError(400,"Invalid date range");return f;
    }
    private int queryInteger(HttpExchange x,String name,int fallback,int min,int max)throws Exception{String v=query(x,name);if(v.isEmpty())return fallback;try{int n=Integer.parseInt(v);if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new ApiError(400,"Invalid "+name);}}
    private WorkRequests.Filter workRequestFilter(HttpExchange x)throws Exception{
        WorkRequests.Filter f=new WorkRequests.Filter();f.environment=query(x,"environmentId");f.host=query(x,"hostId");f.q=query(x,"q");f.from=query(x,"from");f.to=query(x,"to");
        if(!query(x,"type").isEmpty())f.type=query(x,"type");if(!query(x,"status").isEmpty())f.status=query(x,"status");f.page=queryInteger(x,"page",1,1,1000000);f.size=queryInteger(x,"pageSize",20,1,100);return f;
    }
    private Map<String,Object> publicView(InventoryQueries.Scope f){Map<String,Object> view=queries.view(f);List<Object> hosts=new ArrayList<>();for(Object h:list(view.get("hosts")))hosts.add(publicHost(obj(h)));view.put("hosts",hosts);List<Object> envs=new ArrayList<>();for(Object env:list(view.get("environments")))envs.add(publicEnvironment(obj(env)));view.put("environments",envs);List<Object> enrollments=new ArrayList<>();if(f.host.isEmpty())for(Object value:registry.summaries(environmentIds())){Map<String,Object> enrollment=obj(value);if(f.environment.isEmpty()||(f.environment.equals("__unassigned__")?"":f.environment).equals(str(enrollment.get("environmentId"))))enrollments.add(enrollment);}view.put("enrollments",enrollments);view.put("defaultSettings",store.metadata("settings"));if(!f.host.isEmpty())view.put("settings",effectiveSettings(f.host));else if(!f.environment.isEmpty()&&!f.environment.equals("__unassigned__"))view.put("settings",settings(obj(environment(f.environment).get("settings"))));view.put("unassignedCount",store.scalar("SELECT count(*) FROM hosts WHERE environment_id=''") );if(!reader())view.put("agentUpdateNotice",updates.versionNotice());return view;}
    private Map<String,Object> item(HttpExchange x)throws Exception{
        InventoryQueries.Scope f=scope(x);String kind=query(x,"kind"),id=query(x,"id");if(f.host.isEmpty())throw new ApiError(400,"Select the item's machine");Map<String,Object> item;
        if(kind.equals("hosts")){item=publicHost(host(f.host));}
        else if(kind.equals("jobs")){item=store.get("jobs",id);if(item==null||!f.host.equals(item.get("hostId")))throw new ApiError(404,"Item not found");}
        else {if(!InventoryQueries.KINDS.contains(kind))throw new ApiError(400,"Unknown inventory kind");List<Map<String,Object>> rows=store.documents("SELECT payload FROM inventory WHERE kind=? AND host_id=? AND id=?",kind,f.host,id);if(rows.isEmpty())throw new ApiError(404,"Item not found");item=rows.get(0);}
        Set<String> applicationIds=new LinkedHashSet<>();if(item.get("applicationId") instanceof String)applicationIds.add(str(item.get("applicationId")));if(item.get("associations") instanceof List)for(Object association:list(item.get("associations"))){if(association instanceof Map&&obj(association).get("applicationId") instanceof String)applicationIds.add(str(obj(association).get("applicationId")));if(applicationIds.size()>=20)break;}
        Map<String,Object> related=map();for(String k:new String[]{"libraries","datasources","applications"}){
            List<Object> params=new ArrayList<>(Arrays.asList(f.host,k,id,str(item.get("applicationId")),id));String condition="(json_extract(i.payload,'$.applicationId')=? OR i.id=? OR EXISTS(SELECT 1 FROM json_each(i.payload,'$.associations') a WHERE json_extract(a.value,'$.applicationId')=?))";
            if(kind.equals("servers")&&!str(item.get("name")).isEmpty()){condition="("+condition+" OR json_extract(i.payload,'$.serverName')=?)";params.add(item.get("name"));}
            if(k.equals("applications")&&!applicationIds.isEmpty()){condition="("+condition+" OR i.id IN ("+String.join(",",Collections.nCopies(applicationIds.size(),"?"))+"))";params.addAll(applicationIds);}
            related.put(k,store.documents("SELECT i.payload FROM inventory i WHERE i.host_id=? AND i.kind=? AND "+condition+" ORDER BY i.id LIMIT 20",params.toArray()));
        }
        return map("item",item,"host",publicHost(host(f.host)),"related",related,"relatedLimit",20);
    }
    private Reply environmentChange(HttpExchange x)throws Exception{
        Map<String,Object> b=body(x);boolean create=x.getRequestMethod().equals("POST");String id=create?"env-"+UUID.randomUUID():bounded(b.get("environmentId"),"environmentId",100,true);Map<String,Object> env=create?map("id",id,"createdAt",now(),"settings",map(),"description","","revision",UUID.randomUUID().toString()):environment(id);
        if(x.getRequestMethod().equals("DELETE")){
            if(!str(env.get("name")).equals(bounded(b.get("confirmationName"),"confirmationName",100,true)))throw new ApiError(400,"Type the environment name exactly to confirm deletion");
            if(store.scalar("SELECT count(*) FROM hosts WHERE environment_id=?",id)>0)throw new ApiError(409,"Move all machines out of this environment before deleting it");store.delete("environments",id);return new Reply(200,map("deleted",true,"environmentId",id));
        }
        if(create&&store.scalar("SELECT count(*) FROM environments")>=200)throw new ApiError(409,"Environment limit reached");
        if(create||b.containsKey("name")){String name=bounded(b.get("name"),"name",100,true).trim();if(store.scalar("SELECT count(*) FROM environments WHERE lower(name)=lower(?) AND id<>?",name,id)>0)throw new ApiError(409,"An environment with this name already exists");env.put("name",name);}
        if(b.containsKey("description"))env.put("description",bounded(b.get("description"),"description",1000,false));if(b.containsKey("settings")){Map<String,Object> override=obj(b.get("settings"));settings(override);if(!override.equals(env.get("settings")))env.put("revision",UUID.randomUUID().toString());env.put("settings",override);}env.put("updatedAt",now());store.put("environments",env);return new Reply(create?201:200,publicEnvironment(env));
    }
    private Reply api(HttpExchange x,Session session)throws Exception{
        String path=x.getRequestURI().getPath();
        if(path.equals("/api/agent/uninstall/complete")){method(x,"POST");return new Reply(200,uninstalls.complete(body(x),bearer(x)));}
        if(path.equals("/api/agent/register"))return new Reply(200,register(x));
        if(path.startsWith("/api/agent/")){
            String authenticated=agent(x);if(authenticated==null)throw new ApiError(401,"Invalid collector credential");agentHost.set(authenticated);
            if(path.equals("/api/agent/update-v3/action")){method(x,"GET");String id=query(x,"hostId");ownHost(id);var action=updates.delivery(id,query(x,"updateCapability"));return new Reply(200,map("action",action!=null&&"UPDATE_AGENT_V3".equals(action.get("type"))?action:null));}
            if(path.equals("/api/agent/update-v3/claim")){method(x,"POST");var b=body(x);if(!Set.of("hostId","operationId").containsAll(b.keySet()))throw new ApiError(400,"Unknown bundle claim field");String id=str(b.get("hostId"));ownHost(id);return new Reply(200,updates.bundleClaim(id,str(b.get("operationId"))));}
            if(path.equals("/api/agent/update-v3/report")){method(x,"POST");var b=body(x);String id=str(b.get("hostId"));ownHost(id);return new Reply(200,updates.bundleReport(id,b));}
            if(Set.of("/api/agent/update-v3/manifest","/api/agent/update-v3/signature","/api/agent/update-v3/package").contains(path)){method(x,"GET");String id=query(x,"hostId");ownHost(id);String part=path.substring(path.lastIndexOf('/')+1);return new Reply(200,new BinaryReply(updates.bundleDownload(id,query(x,"operationId"),part),part.equals("manifest")?"application/json; charset=utf-8":"application/octet-stream"));}
            if(path.equals("/api/agent/update/claim")){method(x,"POST");var b=body(x);if(!Set.of("hostId","requestId").containsAll(b.keySet()))throw new ApiError(400,"Unknown update claim field");String id=str(b.get("hostId"));ownHost(id);return new Reply(200,updates.claim(id,str(b.get("requestId"))));}
            if(path.equals("/api/agent/update/report")){method(x,"POST");var b=body(x);String id=str(b.get("hostId"));ownHost(id);return new Reply(200,updates.report(id,b));}
            if(Set.of("/api/agent/update/manifest","/api/agent/update/signature","/api/agent/update/package").contains(path)){method(x,"GET");String id=query(x,"hostId");ownHost(id);var release=updates.download(id,query(x,"requestId"));String part=path.substring(path.lastIndexOf('/')+1);try{return new Reply(200,new BinaryReply(updateCatalog.bytes(release,part),part.equals("manifest")?"application/json; charset=utf-8":"application/octet-stream"));}catch(IOException e){throw new ApiError(503,"RELEASE_UNAVAILABLE");}}
            if(path.equals("/api/agent/uninstall/claim")){method(x,"POST");var b=body(x);String id=str(b.get("hostId"));ownHost(id);return new Reply(200,uninstalls.claim(id,str(b.get("requestId"))));}
            if(path.equals("/api/agent/results"))return new Reply(200,accept(x));
            if(path.equals("/api/agent/install-events"))return new Reply(200,installEvents(x));
            if(path.equals("/api/agent/settings")){method(x,"GET");String id=query(x,"hostId");ownHost(id);return new Reply(200,map("settings",effectiveSettings(id)));}
            if(path.equals("/api/agent/heartbeat")){
                method(x,"POST");Map<String,Object> b=body(x);String id=str(b.get("hostId"));ownHost(id);Map<String,Object> h=host(id);h.put("uninstallCapability","uninstall-v1".equals(b.get("uninstallCapability"))?"uninstall-v1":"");h.put("updateCapability",AgentUpdateRegistry.updaterGeneration(b.get("updateCapability"))>0?b.get("updateCapability"):"");for(String key:new String[]{"bundleSha256","managerSha256"}){if("update-v3".equals(b.get("updateCapability"))&&b.get(key) instanceof String value&&value.matches("[a-f0-9]{64}"))h.put(key,value);else h.remove(key);}h.put("lastSeen",now());h.putIfAbsent("firstContactAt",now());for(String key:new String[]{"hostname","javaVersion","collectorVersion"})if(b.containsKey(key))h.put(key.equals("javaVersion")?"collectorJavaVersion":key,bounded(b.get(key),key,200,true));store.put("hosts",h);updates.heartbeat(id,str(b.get("collectorVersion")));return new Reply(200,map("accepted",true));
            }
            if(path.equals("/api/agent/actions")){method(x,"GET");String id=query(x,"hostId");ownHost(id);expireJobs();Map<String,Object> action=updates.delivery(id,query(x,"updateCapability"));if(action==null)action=uninstalls.delivery(id,query(x,"capability"));return new Reply(200,map("action",action==null||action.containsKey("blocked")?null:action));}
            if(path.equals("/api/agent/poll")){
                method(x,"GET");String id=query(x,"hostId");ownHost(id);expireJobs();Map<String,Object> action=updates.delivery(id,query(x,"updateCapability"));if(action==null)action=uninstalls.delivery(id,query(x,"capability"));if(action!=null)return new Reply(200,map("jobId",null,"action",action.containsKey("blocked")?null:action));updates.assessments().dispatch(id,System.currentTimeMillis(),host->queue(host,"post-update"));schedule(id);Map<String,Object> effective=effectiveSettings(id);
                if(store.scalar("SELECT count(*) FROM jobs WHERE host_id=? AND status='RUNNING'",id)>0)return new Reply(200,map("jobId",null,"settings",effective));
                List<Map<String,Object>> jobs=store.documents("SELECT payload FROM jobs WHERE host_id=? AND status='QUEUED' ORDER BY created_at LIMIT 1",id);Map<String,Object> j=jobs.isEmpty()?null:jobs.get(0);
                if(j!=null&&!updates.assessments().allowLease(id,str(j.get("id")),System.currentTimeMillis()))j=null;if(j!=null){j.put("status","RUNNING");j.put("startedAt",now());j.put("leaseExpires",System.currentTimeMillis()+(number(effective.get("maxDurationSeconds"),120)+120)*1000L);j.put("summary","Collector is examining the configured paths");store.put("jobs",j);updates.assessments().jobChanged(j,System.currentTimeMillis());}return new Reply(200,map("jobId",j==null?null:j.get("id"),"settings",effective));
            }
            throw new ApiError(404,"Unknown collector endpoint");
        }
        if(Set.of("/api/work-requests","/api/work-requests/active","/api/work-requests/detail").contains(path)){method(x,"GET");updates.maintain();expireJobs();WorkRequests.Filter filter=workRequestFilter(x);return new Reply(200,path.endsWith("/detail")?workRequests.detail(filter,query(x,"id"),query(x,"type")):path.endsWith("/active")?workRequests.active(filter):workRequests.list(filter));}
        if(path.equals("/api/agent-updates")){if(x.getRequestMethod().equals("GET")){updates.maintain();return new Reply(200,updates.preview(query(x,"hostId"),query(x,"environmentId")));}method(x,"POST");return new Reply(200,updates.create(body(x),requestUser.get()));}
        if(path.equals("/api/agent-updates/cancel")){method(x,"POST");var b=body(x);if(!Set.of("hostId","requestId").containsAll(b.keySet()))throw new ApiError(400,"Unknown update cancellation field");return new Reply(200,updates.cancel(str(b.get("hostId")),str(b.get("requestId")),requestUser.get()));}
        if(path.equals("/api/hosts")){method(x,"DELETE");return new Reply(200,new MachineActions(store,registry).delete(body(x),requestUser.get()));}
        if(path.equals("/api/hosts/uninstall")){method(x,"POST");return new Reply(202,uninstalls.create(body(x),"user:"+requestUser.get().id()));}
        if(path.equals("/api/hosts/uninstall/cancel")){method(x,"POST");var b=body(x);return new Reply(200,uninstalls.cancel(str(b.get("hostId")),str(b.get("requestId"))));}
        if(path.equals("/api/state")){method(x,"GET");return new Reply(200,publicView(scope(x)));}
        if(path.equals("/api/item")){method(x,"GET");return new Reply(200,item(x));}
        if(path.equals("/api/jobs")){method(x,"POST");return new Reply(202,queue(str(body(x).get("hostId")),"manual"));}
        if(path.equals("/api/jobs/run-scope")){
            method(x,"POST");Map<String,Object> b=body(x);InventoryQueries.Scope f=new InventoryQueries.Scope();f.environment=str(b.get("environmentId"));f.host=str(b.get("hostId"));if(!f.environment.isEmpty()&&!f.environment.equals("__unassigned__"))environment(f.environment);if(!f.host.isEmpty()){Map<String,Object> h=host(f.host);if(!f.environment.isEmpty()&&!(f.environment.equals("__unassigned__")?"":f.environment).equals(str(h.get("environmentId"))))throw new ApiError(404,"Machine is outside this environment");}
            String w=f.where();int queued=0,skipped=0;for(Map<String,Object> h:store.rows("SELECT h.id FROM hosts h WHERE "+w+" ORDER BY h.id LIMIT 1001",f.args.toArray())){if(queued+skipped>=1000)throw new ApiError(409,"Beta scope exceeds 1,000 machines");try{queue(str(h.get("id")),"manual");queued++;}catch(ApiError e){if(e.status!=409&&e.status!=403)throw e;skipped++;}}return new Reply(202,map("queued",queued,"skipped",skipped));
        }
        if(path.equals("/api/settings")){method(x,"PUT");Map<String,Object> settings=settings(body(x));store.metadata("settings",settings);store.metadata("scheduleRevision",UUID.randomUUID().toString());return new Reply(200,settings);}
        if(path.equals("/api/enrollments")){
            method(x,"POST");Map<String,Object> b=body(x);String label=bounded(b.get("label"),"label",100,true);int expires=integer(b.get("expiresInMinutes"),60,5,10080),uses=integer(b.get("maxUses"),1,1,1000);if(b.containsKey("autoCollect")&&!(b.get("autoCollect") instanceof Boolean))throw new ApiError(400,"Boolean autoCollect expected");Map<String,Object> overrides=b.containsKey("settings")?obj(b.get("settings")):map();settings(overrides);overrides.putIfAbsent("enabled",false);Map<String,Object> issued=registry.issue(label,expires,uses,overrides,!Boolean.FALSE.equals(b.get("autoCollect")),"",environmentIds());issued.put("serverUrl",serverUrl());return new Reply(201,issued);
        }
        if(path.equals("/api/enrollments/revoke")){method(x,"POST");registry.revokeEnrollment(str(body(x).get("enrollmentId")));return new Reply(200,map("revoked",true));}
        if(path.equals("/api/hosts/revoke")){
            method(x,"POST");String id=str(body(x).get("hostId"));host(id);registry.revokeHost(id);updates.maintain();for(Map<String,Object> job:store.documents("SELECT payload FROM jobs WHERE host_id=? AND status IN ('QUEUED','RUNNING')",id)){job.put("status","FAILED");job.put("error","Collector credential revoked by administrator");job.put("finishedAt",now());store.put("jobs",job);updates.assessments().jobChanged(job,System.currentTimeMillis());}trimJobs(id);return new Reply(200,map("revoked",true));
        }
        if(path.equals("/api/hosts/settings")){
            method(x,"PUT");Map<String,Object> b=body(x);String id=str(b.get("hostId"));if(b.containsKey("replaceOverrides")&&!(b.get("replaceOverrides") instanceof Boolean))throw new ApiError(400,"Boolean replaceOverrides expected");Map<String,Object> h=host(id),overrides=!Boolean.TRUE.equals(b.get("replaceOverrides"))&&h.get("settings") instanceof Map?obj(h.get("settings")):map();overrides.putAll(obj(b.get("settings")));Map<String,Object> effective=effectiveWithHost(h,overrides);h.put("settings",overrides);store.put("hosts",h);resetSchedule(id);return new Reply(200,effective);
        }
        if(path.equals("/api/environments")){if(!Set.of("POST","PUT","DELETE").contains(x.getRequestMethod()))throw new ApiError(405,"Method not allowed");return environmentChange(x);}
        if(path.equals("/api/environments/package")){
            method(x,"POST");Map<String,Object> b=body(x);String id=bounded(b.get("environmentId"),"environmentId",100,true);Map<String,Object> env=environment(id);int minutes=integer(b.get("expiresInMinutes"),1440,5,10080),uses=integer(b.get("maxUses"),100,1,1000);if(b.containsKey("autoCollect")&&!(b.get("autoCollect") instanceof Boolean))throw new ApiError(400,"Boolean autoCollect expected");
            InstallerPackage installer=installerPackage();try{Map<String,Object> issued=registry.issue(str(env.get("name")),minutes,uses,map(),!Boolean.FALSE.equals(b.get("autoCollect")),id,environmentIds());return new Reply(200,new PackageReply(str(env.get("name")),serverUrl(),str(issued.get("enrollmentToken")),id));}finally{installer.close();}
        }
        if(path.equals("/api/collector/info")){
            method(x,"GET");String id=query(x,"environmentId");Map<String,Object> env=id.isEmpty()?null:environment(id);
            InstallerPackage installer=installerPackage();try{return new Reply(200,map("version",installer.version(),"filename",env==null?installer.filename():installer.filenameForEnvironment(str(env.get("name")))));}finally{installer.close();}
        }
        if(path.equals("/api/hosts/environment")){
            method(x,"PUT");Map<String,Object> b=body(x),h=host(str(b.get("hostId")));String id=bounded(b.get("environmentId"),"environmentId",100,false);environment(id);if(!id.equals(str(h.get("environmentId")))){if(AgentUpdateRegistry.busy(store,str(h.get("id"))))throw new ApiError(409,"Agent update is pending or in progress");h.put("environmentId",id);store.put("hosts",h);resetSchedule(str(h.get("id")));}return new Reply(200,publicHost(h));
        }
        throw new ApiError(404,"Unknown endpoint");
    }
    private void origin(HttpExchange x){String origin=x.getRequestHeaders().getFirst("Origin");if(origin!=null&&!origin.equals((x instanceof HttpsExchange?"https://":"http://")+x.getRequestHeaders().getFirst("Host")))throw new ApiError(403,"Invalid origin");}
    private String[] rateKeys(HttpExchange x,String email){return new String[]{"ip:"+x.getRemoteAddress().getAddress().getHostAddress(),"account:"+InventoryStore.hash(UserAuth.normalize(email))};}
    private void rateCheck(HttpExchange x,String email){
        long now=System.currentTimeMillis();synchronized(attempts){attempts.entrySet().removeIf(e->now-e.getValue()[0]>15*60000L);for(String key:rateKeys(x,email)){
            long[] state=attempts.get(key);if(state==null){if(attempts.size()>=8192)throw new ApiError(429,"Please retry later");state=new long[]{now,0,0};attempts.put(key,state);}
            if(state[2]>now)throw new ApiError(429,"Please wait before trying again");state[0]=now;
        }}
    }
    private void rateResult(HttpExchange x,String email,boolean success){
        long now=System.currentTimeMillis();synchronized(attempts){for(String key:rateKeys(x,email)){long[] state=attempts.computeIfAbsent(key,k->new long[]{now,0,0});state[0]=now;
            if(success){if(key.startsWith("account:")){state[1]=0;state[2]=0;}continue;}
            state[1]++;int threshold=key.startsWith("ip:")?20:5;if(state[1]>=threshold)state[2]=now+Math.min(60000L,1000L<<(int)Math.min(6,state[1]-threshold));
        }}
    }
    private Map<String,Object> envelope(Session session,UserAuth.User user){return map("authenticated",true,"csrfToken",session.csrf,"user",user.dto(),"mustChangePassword",user.mustChangePassword());}
    private synchronized Map<String,Object> startSession(HttpExchange x,UserAuth.User user){
        long now=System.currentTimeMillis();sessions.entrySet().removeIf(e->!e.getValue().live(now));
        String cookie=x.getRequestHeaders().getFirst("Cookie");if(cookie!=null)for(String item:cookie.split(";")){item=item.trim();if(item.startsWith("assessment_session="))sessions.remove(item.substring(19));}
        if(sessions.size()>=128)throw new ApiError(429,"Too many active sessions");
        String token=token();Session session=new Session(token(),user);sessions.put(token,session);x.getResponseHeaders().set("Set-Cookie","assessment_session="+token+"; Path=/; HttpOnly; SameSite=Strict"+(x instanceof HttpsExchange?"; Secure":""));return envelope(session,user);
    }
    private Reply login(HttpExchange x)throws Exception{
        method(x,"POST");origin(x);Map<String,Object> input=body(x);String email=str(input.get("email"));rateCheck(x,email);
        try{UserAuth.User user=auth.authenticate(email,str(input.get("password")));rateResult(x,email,true);return new Reply(200,startSession(x,user));}
        catch(UserAuth.LoginFailure e){rateResult(x,email,false);throw new ApiError(401,"Email or password is incorrect");}
    }
    private Reply bootstrap(HttpExchange x)throws Exception{
        method(x,"POST");origin(x);Map<String,Object> input=body(x);String email=str(input.get("email"));rateCheck(x,"bootstrap");
        if(!auth.bootstrapRequired())throw new ApiError(409,"Administrator setup has already been completed");
        if(!legacyPassword(str(input.get("currentPassword")))){if(!auth.bootstrapRequired())throw new ApiError(409,"Administrator setup has already been completed");rateResult(x,"bootstrap",false);auth.failedLogin();throw new ApiError(401,"Current administrative password is incorrect");}
        if(same(str(input.get("currentPassword")),str(input.get("password"))))throw new ApiError(400,"Choose a different password");
        UserAuth.User user=auth.bootstrap(str(input.get("name")),email,str(input.get("password")));rateResult(x,"bootstrap",true);return new Reply(200,startSession(x,user));
    }
    private Reply account(HttpExchange x,Session session)throws Exception{
        String path=x.getRequestURI().getPath();UserAuth.User actor=authority(session);
        if(path.equals("/api/account/password")){
            method(x,"POST");var input=body(x);rateCheck(x,actor.email());
            UserAuth.User user;
            try{user=auth.changePassword(actor.id(),actor.revision(),str(input.get("currentPassword")),str(input.get("newPassword")));rateResult(x,actor.email(),true);}
            catch(UserAuth.Failure e){if(e.status==403){rateResult(x,actor.email(),false);auth.failedReauthentication(actor,true);}throw e;}
            sessions.entrySet().removeIf(e->e.getValue().userId.equals(user.id()));return new Reply(200,startSession(x,user));
        }
        if(!actor.administrator())throw new ApiError(403,"Administrator access required");
        if(path.equals("/api/admin/users")&&x.getRequestMethod().equals("GET"))return new Reply(200,map("users",auth.users()));
        if(path.equals("/api/admin/audit")){method(x,"GET");return new Reply(200,map("events",auth.events()));}
        var input=body(x);rateCheck(x,actor.email());
        try{auth.requireAdministrator(actor,str(input.get("adminPassword")));rateResult(x,actor.email(),true);}catch(UserAuth.Failure e){if(e.status==403){rateResult(x,actor.email(),false);auth.failedReauthentication(actor,false);}throw e;}
        if(path.equals("/api/admin/users")&&x.getRequestMethod().equals("POST"))return new Reply(201,auth.create(actor,str(input.get("name")),str(input.get("email")),str(input.get("role"))).response());
        if(path.equals("/api/admin/users")&&x.getRequestMethod().equals("PUT")){
            if(!(input.get("enabled") instanceof Boolean))throw new ApiError(400,"Boolean enabled expected");
            UserAuth.User user=auth.update(actor,str(input.get("id")),str(input.get("name")),str(input.get("email")),str(input.get("role")),Boolean.TRUE.equals(input.get("enabled")));sessions.entrySet().removeIf(e->e.getValue().userId.equals(user.id())&&e.getValue().revision!=user.revision());return new Reply(200,map("user",user.dto()));
        }
        if(path.equals("/api/admin/users/reset")){method(x,"POST");UserAuth.Temporary reset=auth.reset(actor,str(input.get("id")));sessions.entrySet().removeIf(e->e.getValue().userId.equals(reset.user().id()));return new Reply(200,reset.response());}
        throw new ApiError(405,"Method not allowed");
    }
    private InstallerPackage installerPackage()throws IOException{
        try{return new InstallerPackage(new File(root,"dist/assessment-collector.zip"));}
        catch(IOException e){throw new ApiError(503,"Agent package metadata is unavailable or invalid. Ask the administrator to verify the package.");}
    }
    private String csvFilename(InventoryQueries.Scope filter){
        String name=!filter.host.isEmpty()?"maquina-"+str(host(filter.host).get("hostname")):filter.environment.isEmpty()?"todos":filter.environment.equals("__unassigned__")?"sem-ambiente":str(environment(filter.environment).get("name"));
        String slug=java.text.Normalizer.normalize(name.toLowerCase(Locale.ROOT),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","").replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$","");
        if(slug.isEmpty())slug="inventario";if(slug.length()>100)slug=slug.substring(0,100);
        return "assessment-inventory-"+slug+"-"+java.time.LocalDate.now(java.time.ZoneOffset.UTC)+".csv";
    }
    private void reports(HttpExchange x,Session session)throws Exception {
        method(x,"GET");
        String path=x.getRequestURI().getPath();
        if(path.equals("/api/reports/options")){json(x,200,store.readSnapshot(()->{authorize(x,session);return new Reports(store).options();}));return;}
        Map<String,String> input=new LinkedHashMap<>();
        String raw=x.getRequestURI().getRawQuery();
        if(raw!=null){
            if(raw.length()>8192)throw new ApiError(400,"Filtros muito longos.");
            for(String pair:raw.split("&")){
                if(pair.isEmpty())continue;
                String[] parts=pair.split("=",2);
                String key=java.net.URLDecoder.decode(parts[0],java.nio.charset.StandardCharsets.UTF_8),value=java.net.URLDecoder.decode(parts.length>1?parts[1]:"",java.nio.charset.StandardCharsets.UTF_8);
                if(input.putIfAbsent(key,value)!=null)throw new ApiError(400,"Filtro duplicado.");
            }
        }
        boolean exporting=!path.equals("/api/reports");
        if(!exportSlot.tryAcquire())throw new ApiError(429,"Outro relatório ou exportação está em andamento. Tente novamente em instantes.");
        java.nio.file.Path temporary=null;
        try {
            Map<String,Object> result=store.readSnapshot(()->{authorize(x,session);return new Reports(store).query(input,exporting);});
            if(!exporting){json(x,200,result);return;}
            boolean pdf=path.endsWith(".pdf");String extension=pdf?"pdf":"csv";
            temporary=java.nio.file.Files.createTempFile(data.toPath(),"report-","."+extension);
            if(pdf){try(OutputStream out=java.nio.file.Files.newOutputStream(temporary)){ReportPdf.write(out,result);}}
            else try(Writer out=java.nio.file.Files.newBufferedWriter(temporary,java.nio.charset.StandardCharsets.UTF_8)){Reports.csv(out,result);}
            store.readSnapshot(()->{authorize(x,session);return null;});
            x.getResponseHeaders().set("Content-Disposition","attachment; filename=\"java-assessment-"+input.getOrDefault("model","machines")+"-"+java.time.LocalDate.now(java.time.ZoneOffset.UTC)+"."+extension+"\"");
            file(x,temporary.toFile(),pdf?"application/pdf":"text/csv; charset=utf-8");
        } finally {if(temporary!=null)java.nio.file.Files.deleteIfExists(temporary);exportSlot.release();}
    }

    private void exportCsv(HttpExchange x,Session session)throws Exception{
        method(x,"GET");if(!exportSlot.tryAcquire())throw new ApiError(429,"An export is already running; retry shortly");java.nio.file.Path temporary=null;
        try{
            temporary=java.nio.file.Files.createTempFile(data.toPath(),"export-",".csv");java.nio.file.Path output=temporary;
            String filename=store.readSnapshot(()->{
                authorize(x,session);InventoryQueries.Scope filter=selectedScope(x);
                try(Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output.toFile()),java.nio.charset.StandardCharsets.UTF_8))){CsvExport.write(writer,store,queries,filter);}
                return csvFilename(filter);
            });
            x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+filename+"\"");file(x,temporary.toFile(),"text/csv; charset=utf-8");
        }finally{if(temporary!=null)java.nio.file.Files.deleteIfExists(temporary);exportSlot.release();}
    }
    private void export(HttpExchange x,Session session)throws Exception{
        method(x,"GET");if(!exportSlot.tryAcquire())throw new ApiError(429,"An export is already running; retry shortly");java.nio.file.Path temporary=null;
        try{
            InventoryQueries.Scope filter=scope(x);temporary=java.nio.file.Files.createTempFile(data.toPath(),"export-",".json");java.nio.file.Path output=temporary;
            store.readSnapshot(()->{authorize(x,session);try(Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output.toFile()),java.nio.charset.StandardCharsets.UTF_8))){
                writer.write("{\"version\":"+Json.stringify(DashboardVersion.VERSION)+",\"exportedAt\":"+Json.stringify(now())+",\"scope\":"+Json.stringify(map("environmentId",filter.environment,"hostId",filter.host)));
                List<Object> envs=new ArrayList<>();for(Map<String,Object> e:store.documents("SELECT payload FROM environments ORDER BY id"))if(filter.environment.isEmpty()||filter.environment.equals(e.get("id")))envs.add(readerView(publicEnvironment(e)));writer.write(",\"environments\":"+Json.stringify(envs));
                for(String kind:new String[]{"hosts","runtimes","processes","servers","libraries","applications","datasources","jobs"}){
                    writer.write(","+Json.stringify(kind)+":[");boolean comma=false;InventoryQueries.Scope f=new InventoryQueries.Scope();f.environment=filter.environment;f.host=filter.host;f.size=100;f.from=filter.from;f.to=filter.to;
                    if(kind.equals(filter.view)||filter.view.equals("versions")&&kind.equals("runtimes")||filter.view.equals("libraries")&&kind.equals("datasources")){f.q=filter.q;f.subset=filter.subset;f.version=filter.version;f.mode=filter.mode;}
                    while(true){if(filter.view.equals("libraries")&&filter.subset.equals("datasources")&&kind.equals("libraries"))break;if(filter.view.equals("libraries")&&filter.subset.equals("jdbc")&&kind.equals("datasources"))break;Map<String,Object> page=queries.page(f,kind);for(Object row:list(page.get("items"))){if(comma)writer.write(',');comma=true;writer.write(Json.stringify(readerView(kind.equals("hosts")?publicHost(obj(row)):row)));}Map<String,Object> paging=obj(page.get("pagination"));if(f.page>=number(paging.get("pages"),1))break;f.page++;}writer.write(']');
                }
                writer.write('}');return null;
            }});
            x.getResponseHeaders().set("Content-Disposition","attachment; filename=java-assessment.json");file(x,temporary.toFile(),"application/json; charset=utf-8");
        }finally{if(temporary!=null)java.nio.file.Files.deleteIfExists(temporary);exportSlot.release();}
    }
    public void handle(HttpExchange x)throws IOException{
        headers(x);try{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();boolean write=Set.of("POST","PUT","DELETE").contains(method);
            if(write)requestBody.set(readStream(x.getRequestBody(),path.equals("/api/agent/results")?2097152:path.equals("/api/agent-updates")?131072:32768));
            if(!(x instanceof HttpsExchange)&&!local(x)&&Boolean.parseBoolean(conf.getProperty("requireHttps","true"))){x.getResponseHeaders().set("Location",serverUrl()+"/");x.sendResponseHeaders(308,-1);return;}
            if(path.startsWith("/.well-known/acme-challenge/")){method(x,"GET");String name=path.substring(path.lastIndexOf('/')+1);if(!name.matches("[A-Za-z0-9_-]{10,200}"))throw new ApiError(404,"Not found");file(x,new File(root,"conf/acme/"+name),"text/plain");return;}
            if(path.equals("/health")){method(x,"GET");store.scalar("SELECT 1");json(x,200,map("status","ok","version",DashboardVersion.VERSION,"builtAt",DashboardVersion.BUILT_AT,"storage","sqlite"));return;}
            if(path.equals("/api/auth/status")){method(x,"GET");json(x,200,map("bootstrapRequired",auth.bootstrapRequired()));return;}
            if(path.equals("/api/auth/bootstrap")){Reply r=bootstrap(x);json(x,r.status,r.body);return;}
            if(path.equals("/api/login")){Reply r=login(x);json(x,r.status,r.body);return;}
            if(path.startsWith("/api/")){
                Session session=null;if(!path.startsWith("/api/agent/")){session=session(x);if(session==null)throw new ApiError(401,"Sign in required");authorize(x,session);if(write)csrf(x,session);}
                if(path.equals("/api/session")){method(x,"GET");json(x,200,envelope(session,authority(session)));return;}
                if(path.equals("/api/logout")){method(x,"POST");auth.logout(authority(session));Session ending=session;sessions.entrySet().removeIf(e->e.getValue()==ending);x.getResponseHeaders().set("Set-Cookie","assessment_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"+(x instanceof HttpsExchange?"; Secure":""));json(x,200,map("ok",true));return;}
                if(path.equals("/api/export")){export(x,session);return;}
                if(path.equals("/api/export.csv")){exportCsv(x,session);return;}
                if(Set.of("/api/reports","/api/reports/options","/api/reports/export.csv","/api/reports/export.pdf").contains(path)){reports(x,session);return;}
                if(path.equals("/api/account/password")||path.startsWith("/api/admin/")){Reply reply=account(x,session);json(x,reply.status,reply.body);return;}
                if(path.equals("/api/collector.zip")){method(x,"GET");InstallerPackage installer=installerPackage();try{x.getResponseHeaders().set("Content-Type","application/zip");x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+installer.filename()+"\"");x.sendResponseHeaders(200,0);installer.writeOriginal(x.getResponseBody());}finally{installer.close();}return;}
                Session authenticated=session;java.util.concurrent.Callable<Reply> operation=()->{if(authenticated!=null)authorize(x,authenticated);return api(x,authenticated);};Reply reply=write||(path.equals("/api/agent/poll")||path.equals("/api/agent/actions")||path.equals("/api/agent/update-v3/action")||path.equals("/api/agent-updates")||Set.of("/api/work-requests","/api/work-requests/active","/api/work-requests/detail").contains(path))?store.transaction(operation):store.readSnapshot(operation);
                if(reply.body instanceof BinaryReply binary){x.getResponseHeaders().set("Content-Type",binary.contentType);x.sendResponseHeaders(reply.status,binary.data.length);x.getResponseBody().write(binary.data);}
                else if(reply.body instanceof PackageReply p){InstallerPackage installer=installerPackage();try{x.getResponseHeaders().set("Content-Type","application/zip");x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+installer.filenameForEnvironment(p.environmentName)+"\"");x.sendResponseHeaders(200,0);installer.write(x.getResponseBody(),p.url,p.token,p.environment);}finally{installer.close();}}
                else json(x,reply.status,readerView(reply.body));return;
            }
            method(x,"GET");if(path.equals("/"))path="/index.html";File web=new File(root,"web").getCanonicalFile(),f=new File(web,path.substring(1)).getCanonicalFile();if(!f.getPath().startsWith(web.getPath()+File.separator))throw new ApiError(404,"Not found");String type=path.endsWith(".html")?"text/html; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":path.endsWith(".js")?"application/javascript; charset=utf-8":path.endsWith(".svg")?"image/svg+xml":"application/octet-stream";file(x,f,type);
        }catch(ApiError e){json(x,e.status,map("error",e.getMessage()));}
        catch(Reports.Failure e){json(x,e.status,map("error",e.getMessage()));}
        catch(UserAuth.Failure e){json(x,e.status,map("error",e.getMessage()));}
        catch(EnrollmentRegistry.Failure e){json(x,e.status,map("error",e.getMessage()));}
        catch(IllegalArgumentException e){json(x,400,map("error","Invalid request data"));}
        catch(Exception e){System.err.println(now()+" "+x.getRequestURI().getPath()+" "+e.getClass().getName()+": "+e.getMessage());if(x.getResponseCode()==-1)json(x,500,map("error","The request could not be completed"));}
        finally{requestBody.remove();agentHost.remove();requestUser.remove();x.close();}
    }
    private static ExecutorService workers(){return new ThreadPoolExecutor(8,8,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.CallerRunsPolicy());}
    public static void main(String[] args)throws Exception{
        System.setProperty("sun.net.httpserver.maxReqTime","30");System.setProperty("sun.net.httpserver.maxRspTime","60");
        AssessmentServer app=new AssessmentServer(new File(args[0]));String bind=app.conf.getProperty("bind","127.0.0.1");HttpServer http=HttpServer.create(new InetSocketAddress(app.conf.getProperty("httpBind",bind),Integer.parseInt(app.conf.getProperty("httpPort","8085"))),64);http.createContext("/",app);ExecutorService httpWorkers=workers();http.setExecutor(httpWorkers);http.start();
        HttpsServer https=null;ExecutorService tlsWorkers=null;
        if(new File(app.root,"conf/tls/fullchain.pem").isFile()){https=HttpsServer.create(new InetSocketAddress(app.conf.getProperty("httpsBind",bind),Integer.parseInt(app.conf.getProperty("httpsPort","8445"))),64);SSLContext context=app.tls();https.setHttpsConfigurator(new HttpsConfigurator(context){public void configure(HttpsParameters p){SSLParameters parameters=context.getDefaultSSLParameters();parameters.setProtocols(new String[]{"TLSv1.2"});p.setSSLParameters(parameters);}});https.createContext("/",app);tlsWorkers=workers();https.setExecutor(tlsWorkers);https.start();System.out.println("HTTPS ready");}
        HttpsServer finalHttps=https;ExecutorService finalTls=tlsWorkers;ScheduledExecutorService maintenance=Executors.newSingleThreadScheduledExecutor();maintenance.scheduleWithFixedDelay(()->{try{app.store.transaction(()->{app.expireJobs();app.uninstalls.maintain();app.updates.maintain();return null;});}catch(Exception e){System.err.println("Job maintenance failed: "+e.getClass().getSimpleName());}},30,30,TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{http.stop(1);httpWorkers.shutdownNow();if(finalHttps!=null){finalHttps.stop(1);finalTls.shutdownNow();}maintenance.shutdownNow();app.close();}));System.out.println("Java Assessment "+DashboardVersion.VERSION+" ready "+now());
    }
}
