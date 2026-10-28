
package assessment;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.security.*;
import static assessment.AssessmentServer.map;

/** Real SQLite and signed fixture; exercises fleet scale without contacting any hosts. */
public final class AgentUpdateCampaignsTest {
    static Path root; private static KeyPair key; static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static long n(Object o){return o instanceof Number?((Number)o).longValue():0;}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
    static final UserAuth.User ADMIN=new UserAuth.User("admin","Admin","test@example.invalid","ADMIN",true,false,1,0,"","");
    private static String digest(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static Path artifact(String name){return root.resolve("dist/agent-updates/"+DashboardVersion.VERSION+"/"+name);}
    private static void release(String jarVersion,Map<String,Object> overrides)throws Exception{
        Files.createDirectories(artifact("collector.jar").getParent());byte[] publicKey=key.getPublic().getEncoded();Files.write(root.resolve("dist/agent-update-public.der"),publicKey);
        Manifest metadata=new Manifest();metadata.getMainAttributes().putValue("Manifest-Version","1.0");metadata.getMainAttributes().putValue("Implementation-Version",jarVersion);ByteArrayOutputStream binary=new ByteArrayOutputStream();try(JarOutputStream jar=new JarOutputStream(binary,metadata)){jar.putNextEntry(new JarEntry("fixture.txt"));jar.write("signed harmless test data".getBytes());jar.closeEntry();}byte[] bytes=binary.toByteArray();Files.write(artifact("collector.jar"),bytes);
        Map<String,byte[]> assets=new LinkedHashMap<>();assets.put("update-public.der",publicKey);assets.put("update-service.sh","#!/bin/sh\nexit 0\n".getBytes());assets.put("lib/fixture.jar","stable fixture library".getBytes());Map<String,Object> required=new LinkedHashMap<>();for(var item:assets.entrySet())required.put(item.getKey(),digest(item.getValue()));
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(root.resolve("dist/assessment-collector.zip")))){for(var item:assets.entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}
        Map<String,Object> manifest=map("schema",1,"type","ASSESSMENT_AGENT_UPDATE_V1","version",DashboardVersion.VERSION,"format","collector-jar-v1","minUpdater",2,"sha256",digest(bytes),"bytes",bytes.length,"requiredFiles",required);manifest.putAll(overrides);byte[] content=Json.stringify(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8);Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update(content);Files.write(artifact("manifest.json"),content);Files.write(artifact("manifest.sig"),signer.sign());
    }

    static final class Fixture implements AutoCloseable {
        InventoryStore db; AgentUpdateRegistry registry; AgentUpdateCampaigns c;
        Fixture(int count)throws Exception{
            root=Files.createTempDirectory("campaign-test-");var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(3072);key=gen.generateKeyPair();release(DashboardVersion.VERSION,Map.of());open();
            db.transaction(()->{db.put("environments",map("id","env","name","Fixture","settings",map()));for(int i=0;i<count;i++){String id=String.format("host-%05d",i);db.put("hosts",map("id",id,"hostname",id,"environmentId","env","collectorVersion","2.1.20","updateCapability","update-v2","lastSeen",AssessmentServer.now()));db.put("identities",map("hostId",id,"status","active","agentToken","fixture-"+id));}return null;});
        }
        void open(){db=new InventoryStore(root.resolve("data").toFile(),map(),"","");new UserAuth(db);registry=new AgentUpdateRegistry(db,new EnrollmentRegistry(db),new AgentUpdateCatalog(root.toFile()));c=registry.campaigns();}
        void restart(){db.close();open();}
        Map<String,Object> select(Map<String,Object> input){return db.transaction(()->c.selection(input,ADMIN));}
        Map<String,Object> create(Map<String,Object> selected){return db.transaction(()->c.create(map("selectionId",selected.get("selectionId"),"requestKey",UUID.randomUUID().toString(),"targetVersion",DashboardVersion.VERSION,"batchSize",10,"runAssessmentAfterUpdate",false),ADMIN));}
        void command(String batch,String action){db.transaction(()->c.command(map("batchId",batch,"action",action),ADMIN));}
        void tick(){db.transaction(()->{registry.maintain();return null;});}
        List<Map<String,Object>> rows(String batch){return db.documents("SELECT payload FROM agent_update_requests WHERE batch_id=? ORDER BY ordinal",batch);}
        void state(Map<String,Object> r,String state){db.transaction(()->{registry.status(r,state,state.equals("FAILED")?"UPDATE_FAILED":"");return null;});}
        @Override public void close()throws Exception{db.close();try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    public static void main(String[] args)throws Exception{
        long started=System.nanoTime();
        try(var f=new Fixture(5000)){
            var preview=f.c.preview(map("eligibility","eligible"),1,50);
            check(((List<?>)preview.get("hosts")).size()==50,"only 50 hosts returned");check(n(obj(preview.get("pagination")).get("total"))==5000,"5000 visible, no fleet ceiling");
            check(Json.stringify(preview).length()<30000,"bounded preview response");
            check(((List<?>)f.c.preview(map("q","host-049"),2,50).get("hosts")).size()==50,"server search and page 2");
            var selected=f.select(map("filter",map()));check(n(selected.get("selected"))==5000,"server snapshot includes all 5000");
            var body=map("selectionId",selected.get("selectionId"),"requestKey","campaign-replay-test-0001","targetVersion",DashboardVersion.VERSION,"batchSize",10,"runAssessmentAfterUpdate",false);
            var created=f.db.transaction(()->f.c.create(body,ADMIN));String batch=(String)created.get("batchId");
            check(Json.stringify(created).length()<2000,"creation response bounded");
            check(f.db.scalar("SELECT count(*) FROM agent_update_requests")==5000,"5000 stored");
            check(f.db.scalar("SELECT count(*) FROM agent_update_requests WHERE id<>json_extract(payload,'$.id')")==0,"SQLite UUID evaluated once");
            check(batch.equals(f.db.transaction(()->f.c.create(body,ADMIN)).get("batchId")),"lost ACK idempotency");
            f.tick();check(AgentUpdateCampaigns.occupied(f.db)==10,"default global cap equals the default group size");
            check(f.db.scalar("SELECT count(*) FROM agent_update_requests WHERE json_extract(payload,'$.expiresMillis')=0")==4990,"waiting queue has no premature expiry");
            check(AgentUpdateCampaigns.limit(f.db)==10,"new installations default to ten");
            f.db.transaction(()->{f.db.metadata("agent-update-scheduler",map("concurrency",2));return null;});check(AgentUpdateCampaigns.limit(f.db)==10,"implicit 2 persisted by earlier releases becomes the new default");
            f.db.transaction(()->f.c.settings(map("concurrency",2),ADMIN));check(AgentUpdateCampaigns.limit(f.db)==2,"an administrator's explicit 2 is respected");
            f.db.transaction(()->f.c.settings(map("concurrency",25),ADMIN));check(AgentUpdateCampaigns.limit(f.db)==25,"limit accepts up to 25");
            boolean rejected=false;try{f.db.transaction(()->f.c.settings(map("concurrency",26),ADMIN));}catch(RuntimeException e){rejected=true;}check(rejected,"limit above 25 is rejected");
            f.db.transaction(()->f.c.settings(map("concurrency",10),ADMIN));
            var filter=new WorkRequests.Filter();filter.compact=true;filter.size=50;filter.page=75;
            var detail=new WorkRequests(f.db).detail(filter,batch,"AGENT_UPDATE");check(((List<?>)detail.get("machines")).size()==50,"detail page capped");check(n(obj(detail.get("machinePagination")).get("total"))==5000,"detail counts full fleet");check(Json.stringify(detail).length()<70000,"compact detail bounded");
            f.restart();check(AgentUpdateCampaigns.occupied(f.db)==10,"restart preserves admissions");
            f.command(batch,"pause");check(AgentUpdateCampaigns.occupied(f.db)==0,"pause withdraws only unclaimed admission");f.tick();check(AgentUpdateCampaigns.occupied(f.db)==0,"paused does not admit");
            f.command(batch,"resume");f.tick();check(AgentUpdateCampaigns.occupied(f.db)==10,"resume restores capacity");
            var rows=f.rows(batch);var first=rows.get(0);String firstId=(String)first.get("id");
            var claim=f.db.transaction(()->f.registry.claim((String)first.get("hostId"),firstId));check(Boolean.TRUE.equals(claim.get("executable")),"admitted claim accepted");
            f.command(batch,"cancel");check(AgentUpdateCampaigns.allowed(f.db,f.db.get("agent_update_requests",firstId)),"cancel preserves claimed transaction");
            check(!AgentUpdateCampaigns.allowed(f.db,f.db.get("agent_update_requests",(String)rows.get(1).get("id"))),"cancel fences unclaimed immediately before drain");
            f.tick();check(f.db.scalar("SELECT count(*) FROM agent_update_requests WHERE status='CANCELLED'")==200,"cancellation chunk bounded");
            f.restart();for(int i=0;i<26;i++)f.tick();
            check(f.db.scalar("SELECT count(*) FROM agent_update_requests WHERE status='WAITING'")==0,"background cancellation drains after restart");check("DOWNLOADING".equals(f.db.get("agent_update_requests",firstId).get("status")),"active claim not cancelled");
            check(n(f.select(map("filter",map("retryBatchId",batch))).get("selected"))==4999,"retry only stopped hosts excludes active");
            f.state(f.db.get("agent_update_requests",firstId),"SUCCEEDED");f.tick();check("CANCELLED".equals(f.c.control(batch).get("state")),"campaign closes after started machine ends");
        }
        try(var f=new Fixture(25)){
            var selected=f.select(map("filter",map()));String batch=(String)f.create(selected).get("batchId");
            f.db.transaction(()->f.c.settings(map("concurrency",10),ADMIN));f.tick();check(AgentUpdateCampaigns.occupied(f.db)==10,"at most first group");
            var rows=f.rows(batch);for(int i=0;i<9;i++)f.state(rows.get(i),"SUCCEEDED");f.tick();check(AgentUpdateCampaigns.occupied(f.db)==1,"next group waits for group completion");
            f.state(rows.get(9),"SUCCEEDED");f.tick();check(AgentUpdateCampaigns.occupied(f.db)==10,"next ten admitted after group completion");
            for(int i=10;i<13;i++)f.state(f.rows(batch).get(i),"FAILED");f.tick();check("PAUSED".equals(f.c.control(batch).get("state")),"three failures pause campaign");
            f.command(batch,"resume");f.tick();check("RUNNING".equals(f.c.control(batch).get("state")),"resume resets failure baseline");
            f.state(f.rows(batch).get(13),"UNCONFIRMED");f.tick();check("PAUSED".equals(f.c.control(batch).get("state")),"unknown result pauses");
            boolean denied=false;try{f.command(batch,"resume");}catch(EnrollmentRegistry.Failure e){denied=true;}check(denied,"cannot resume unresolved unknown");
        }
        try(var f=new Fixture(5)){
            f.db.transaction(()->f.c.settings(map("concurrency",2),ADMIN));
            var snap=f.select(map("hostIds",List.of("host-00000","host-00001")));String a=(String)f.create(snap).get("batchId");
            String b=(String)f.create(f.select(map("hostIds",List.of("host-00002","host-00003")))).get("batchId");f.tick();check(AgentUpdateCampaigns.occupied(f.db)==2,"global cap across campaigns");
            f.command(a,"pause");f.tick();check(f.rows(b).stream().allMatch(r->n(r.get("startDeadlineMillis"))>0),"other campaign uses released slots");
            f.db.transaction(()->{var h=f.db.get("hosts","host-00004");h.put("lastSeen","2020-01-01T00:00:00Z");f.db.put("hosts",h);return null;});String c=(String)f.create(f.select(map("hostIds",List.of("host-00004")))).get("batchId");f.tick();check("AGENT_OFFLINE".equals(f.rows(c).get(0).get("diagnostic")),"offline skipped without waiting five minutes");
        }
        try(var f=new Fixture(3)){
            var snap=f.select(map("filter",map()));
            var input=map("selectionId",snap.get("selectionId"),"requestKey","snapshot-validation-0001","targetVersion",DashboardVersion.VERSION,"batchSize",10);
            var another=new UserAuth.User("other","Other","other@example.invalid","ADMIN",true,false,1,0,"","");
            boolean rejected=false;try{f.db.transaction(()->f.c.create(input,another));}catch(EnrollmentRegistry.Failure e){rejected=e.status==403;}check(rejected,"snapshot belongs to administrator");
            f.db.exec("UPDATE agent_update_selections SET expires_at=0 WHERE id=?",snap.get("selectionId"));rejected=false;try{f.db.transaction(()->f.c.create(input,ADMIN));}catch(EnrollmentRegistry.Failure e){rejected=e.status==409;}check(rejected,"expired snapshot rejected");
            var fresh=f.select(map("filter",map()));String batch=(String)f.create(fresh).get("batchId");f.tick();
            var active=f.rows(batch).get(0);active.put("claimedMillis",System.currentTimeMillis());f.state(active,"UNCONFIRMED");f.tick();check(AgentUpdateCampaigns.occupied(f.db)==1,"unknown result retains its slot while pause withdraws unclaimed slots");
            f.state(f.db.get("agent_update_requests",(String)active.get("id")),"SUCCEEDED");f.command(batch,"resume");
            f.db.transaction(()->{f.registry.maintain(System.currentTimeMillis()+7200000L);return null;});
            check(f.rows(batch).stream().noneMatch(r->"EXPIRED".equals(r.get("diagnostic"))),"two hours in queue do not expire authorization before admission");
        }
        try(var f=new Fixture(12)){
            var snap=f.select(map("filter",map()));
            f.db.exec("UPDATE identities SET status='revoked' WHERE id<>'host-00011'");
            String batch=(String)f.create(snap).get("batchId");check(f.rows(batch).size()==1&&n(f.rows(batch).get(0).get("ordinal"))==0,"revalidated snapshot compacts ordinals and group count");
            f.tick();var row=f.rows(batch).get(0);long deadline=n(row.get("startDeadlineMillis"));
            f.db.transaction(()->{f.registry.maintain(deadline);return null;});check("AGENT_START_TIMEOUT".equals(f.rows(batch).get(0).get("diagnostic")),"admitted start timeout has specific diagnosis");
        }
        System.out.println("AgentUpdateCampaignsTest PASS: "+checks+" checks, 5000 hosts, "+((System.nanoTime()-started)/1000000)+" ms");
    }
}
