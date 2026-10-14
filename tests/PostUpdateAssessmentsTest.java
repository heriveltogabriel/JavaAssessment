package assessment;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static assessment.AssessmentServer.map;

/** Real SQLite fixtures; no services, remote machines, or production signing material. */
public final class PostUpdateAssessmentsTest {
    static final long NOW=1800000000000L;
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static String s(Object value){return value instanceof String?(String)value:"";}
    static String at(long now){return Instant.ofEpochMilli(now).toString();}
    static final class Fixture implements AutoCloseable {
        final Path dir;
        InventoryStore db;
        PostUpdateAssessments coordinator;
        long time=NOW;
        final AtomicInteger queued=new AtomicInteger();
        Fixture()throws Exception{dir=Files.createTempDirectory("post-update-sqlite-");open();}
        void open(){
            db=new InventoryStore(dir.toFile(),map(),"","");
            db.exec("CREATE TABLE IF NOT EXISTS agent_update_assessments(operation_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,batch_id TEXT NOT NULL,ordinal INTEGER NOT NULL,state TEXT NOT NULL,job_id TEXT,not_before INTEGER NOT NULL DEFAULT 0,expires_at INTEGER NOT NULL,payload TEXT NOT NULL)");
            db.exec("CREATE INDEX IF NOT EXISTS agent_update_assessments_ready ON agent_update_assessments(state,not_before)");
            db.exec("CREATE INDEX IF NOT EXISTS agent_update_assessments_host ON agent_update_assessments(host_id,state)");
            coordinator=new PostUpdateAssessments(db);
        }
        void reopen(){db.close();open();}
        void tx(Runnable action){db.transaction(()->{action.run();return null;});}
        void host(String host,String env){tx(()->{db.put("hosts",map("id",host,"hostname",host,"environmentId",env,"collectorVersion","1.5.12"));db.put("identities",map("hostId",host,"status","active","agentToken","private-fixture-"+host));});}
        Map<String,Object> operation(String id,String host,boolean automatic){
            return operation(id,host,automatic,"9.0.2");
        }
        Map<String,Object> operation(String id,String host,boolean automatic,String target){
            var operation=map("id",id,"hostId",host,"batchId","batch-"+id,"ordinal",0,"targetVersion",target,"fromVersion","1.5.12","status","WAITING","runAssessmentAfterUpdate",automatic,"actorName","Fixture administrator");
            tx(()->{db.put("agent_update_requests",operation);coordinator.onCreated(operation,time);});return operation;
        }
        void succeeded(Map<String,Object> operation){tx(()->{operation.put("status","SUCCEEDED");db.put("agent_update_requests",operation);var host=db.get("hosts",s(operation.get("hostId")));host.put("collectorVersion",operation.get("targetVersion"));db.put("hosts",host);coordinator.onSucceeded(operation,time);});}
        Map<String,Object> queue(String host){var job=map("id","job-"+queued.incrementAndGet(),"hostId",host,"status","QUEUED","createdAt",at(time),"trigger","post-update","summary","Waiting for collector");db.put("jobs",job);return job;}
        void dispatch(String host){tx(()->coordinator.dispatch(host,time,this::queue));}
        Map<String,Object> relation(String id){return coordinator.publicRelation(id);}
        String job(String id){return s(relation(id).get("jobId"));}
        boolean lease(String host,String job){return db.transaction(()->{if(!coordinator.allowLease(host,job,time))return false;var record=db.get("jobs",job);record.put("status","RUNNING");record.put("startedAt",at(time));record.put("leaseExpires",time+240000L);db.put("jobs",record);coordinator.jobChanged(record,time);return true;});}
        void finish(String job,String status){tx(()->{var record=db.get("jobs",job);record.put("status",status);record.put("finishedAt",at(time));record.put("summary","Assessment received");if(status.equals("FAILED"))record.put("error","Fixture collection failed");db.put("jobs",record);coordinator.jobChanged(record,time);});}
        @Override public void close()throws Exception{db.close();try(var paths=Files.walk(dir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }

    static void intentAndFinalOnly()throws Exception{
        try(var f=new Fixture()){
            f.host("one","production");var disabled=f.operation("disabled","one",false);
            check(f.relation("disabled")==null,"false creates no intent");
            check(!PostUpdateAssessments.requested(map())&&!PostUpdateAssessments.requested(map("runAssessmentAfterUpdate","true")),"historical and nonboolean values are disabled");
            check(!PostUpdateAssessments.requested(map("runAssessmentAfterUpdate",true,"parentOperationId","parent")),"bridge child cannot request assessment");
            f.tx(()->{disabled.put("status","CANCELLED");f.db.put("agent_update_requests",disabled);});
            var op=f.operation("final-only","one",true);f.tx(()->f.coordinator.onCreated(op,f.time));
            check(f.db.scalar("SELECT count(*) FROM agent_update_assessments")==1,"one intent for duplicate creation");
            f.dispatch("one");check(f.queued.get()==0&&f.relation("final-only").get("status").equals("WAITING_FINAL"),"no job before final success");
            var child=map("id","bridge","hostId","one","batchId","child-batch","ordinal",0,"parentOperationId","final-only","runAssessmentAfterUpdate",true,"status","SUCCEEDED","targetVersion","9.0.1");
            f.tx(()->f.coordinator.onSucceeded(child,f.time));check(f.db.scalar("SELECT count(*) FROM agent_update_assessments")==1,"successful bridge does not create intent");
            f.tx(()->f.coordinator.onSucceeded(op,f.time));check(f.relation("final-only").get("status").equals("WAITING_FINAL"),"WAITING parent cannot enable intent");
            f.succeeded(op);String expiry=s(f.relation("final-only").get("expiresAt"));f.time+=100;
            f.tx(()->f.coordinator.onSucceeded(op,f.time));check(expiry.equals(f.relation("final-only").get("expiresAt")),"repeated final report does not extend deadline");
            f.dispatch("one");f.dispatch("one");check(f.queued.get()==1&&f.job("final-only").equals("job-1"),"repeated dispatch creates exactly one job");
            check(f.db.get("jobs","job-1").get("requiredCollectorVersion").equals("9.0.2"),"job retains minimum confirmed collector version");
            check(!f.coordinator.settled("final-only"),"queued relation is unsettled");
            check(f.lease("one","job-1"),"new collector receives linked lease");f.finish("job-1","FAILED");
            check(f.coordinator.settled("final-only")&&f.relation("final-only").get("status").equals("FAILED"),"failed collection settles independently");
            check(f.db.get("agent_update_requests","final-only").get("status").equals("SUCCEEDED"),"collection failure never rolls back successful update");
            f.dispatch("one");check(f.queued.get()==1,"failed collection does not retry automatically");
        }
    }

    static void reusingAndOldRunning()throws Exception{
        try(var f=new Fixture()){
            f.host("one","production");f.tx(()->f.db.put("jobs",map("id","manual-before","hostId","one","status","QUEUED","trigger","manual","actorName","Original requester","createdAt",at(f.time-1000))));
            var op=f.operation("reuse","one",true);f.dispatch("one");check(f.relation("reuse").get("status").equals("WAITING_FINAL"),"pending manual job does not bypass final gate");
            f.succeeded(op);f.dispatch("one");check(f.queued.get()==0&&f.job("reuse").equals("manual-before"),"reuse not-yet-started job without duplicate");
            check(f.db.get("jobs","manual-before").get("actorName").equals("Original requester")&&f.db.get("jobs","manual-before").get("trigger").equals("manual"),"reuse preserves original actor and trigger");
            check(Boolean.TRUE.equals(f.relation("reuse").get("reused")),"reuse is explicit in public relation");check(f.lease("one","manual-before"),"reused job leases only after final update");f.finish("manual-before","SUCCEEDED");
        }
        try(var f=new Fixture()){
            f.host("one","production");f.tx(()->f.db.put("jobs",map("id","running-before","hostId","one","status","RUNNING","trigger","manual","createdAt",at(f.time-2000),"startedAt",at(f.time-1000),"leaseExpires",f.time+240000)));
            var op=f.operation("after-running","one",true);f.succeeded(op);f.dispatch("one");check(f.queued.get()==0&&f.relation("after-running").get("status").equals("READY"),"preexisting RUNNING cannot satisfy post-update collection");
            f.finish("running-before","SUCCEEDED");f.dispatch("one");check(f.queued.get()==1&&!f.job("after-running").equals("running-before"),"one new collection follows old RUNNING job");
            // A requeued previously started request is not a fresh assessment either.
            f.host("two","production");f.tx(()->f.db.put("jobs",map("id","previously-started","hostId","two","status","QUEUED","trigger","manual","createdAt",at(f.time-2000),"startedAt",at(f.time-1000))));var two=f.operation("not-fresh","two",true);f.succeeded(two);f.dispatch("two");check(s(f.relation("not-fresh").get("jobId")).isEmpty(),"queued request with prior start is not reused");
        }
    }

    static void throttleAndSpacing()throws Exception{
        try(var f=new Fixture()){
            for(String id:List.of("a","b","c","d")){f.host(id,id.equals("a")||id.equals("b")?"env-1":"env-"+id);var op=f.operation("op-"+id,id,true);f.succeeded(op);f.dispatch(id);}
            check(f.lease("a",f.job("op-a")),"first environment lease starts");
            check(!f.lease("b",f.job("op-b")),"one running post-update collection per environment");
            check(f.lease("c",f.job("op-c")),"second environment can run concurrently");
            check(!f.lease("d",f.job("op-d")),"global two-collection limit");
            f.finish(f.job("op-a"),"SUCCEEDED");check(!f.lease("b",f.job("op-b")),"ten-second spacing persists after fast completion");
            f.time+=10000;check(f.lease("b",f.job("op-b")),"environment slot available after spacing");
            check(!f.lease("d",f.job("op-d")),"global cap still includes both active jobs");
            f.finish(f.job("op-c"),"FAILED");check(f.lease("d",f.job("op-d")),"failed collection releases slot without stopping updates");
            check(f.db.get("agent_update_requests","op-c").get("status").equals("SUCCEEDED"),"cap release does not change upgrade result");
            // Moving a running host does not rewrite the environment charged at its lease.
            f.tx(()->{var h=f.db.get("hosts","b");h.put("environmentId","env-new");f.db.put("hosts",h);});
            check(f.db.scalar("SELECT count(*) FROM agent_update_assessments WHERE json_extract(payload,'$.leaseEnvironmentId')='env-1' AND state='RUNNING'")==1,"running lease retains original environment reservation");
        }
    }

    static void replacementAndBusy()throws Exception{
        try(var f=new Fixture()){
            f.host("one","production");var first=f.operation("first","one",true);f.succeeded(first);
            var second=f.operation("second","one",true,"9.0.3");
            f.dispatch("one");check(f.queued.get()==0,"new update suspends earlier ready intent");
            f.succeeded(second);f.dispatch("one");check(f.queued.get()==1&&f.job("first").equals(f.job("second")),"latest final collector satisfies both intents with one assessment");
            check(f.db.get("jobs",f.job("first")).get("requiredCollectorVersion").equals("9.0.3"),"coalesced job requires latest final version");
            check(f.coordinator.publicOrigins(f.job("first")).size()==2,"job exposes both update origins");
            check(f.lease("one",f.job("first")),"coalesced lease starts");
            f.host("other","other-env");var other=f.operation("other-op","other",true);f.succeeded(other);f.dispatch("other");check(f.lease("other",f.job("other-op")),"multiple links count as one running job globally");
            f.finish(f.job("first"),"PARTIAL");check(f.relation("first").get("status").equals("PARTIAL")&&f.relation("second").get("status").equals("PARTIAL"),"one result updates all linked origins");
        }
        try(var f=new Fixture()){
            f.host("one","production");var op=f.operation("busy","one",true);f.succeeded(op);f.tx(()->f.db.put("agent_update_requests",map("id","legacy-child","hostId","one","batchId","child-batch","ordinal",0,"parentOperationId","busy","status","VERIFYING")));
            f.dispatch("one");check(f.queued.get()==0,"busy bridge child blocks dispatch after an inconsistent early parent success");
            f.tx(()->f.db.delete("agent_update_requests","legacy-child"));f.tx(()->f.db.put("uninstall_requests",map("id","remove-one","hostId","one","requestKey","remove-fixture","status","WAITING")));
            f.dispatch("one");check(f.queued.get()==0,"uninstall blocks post-update collection");
        }
    }

    static void expiryAndRevocation()throws Exception{
        try(var f=new Fixture()){
            f.host("one","production");var op=f.operation("offline","one",true);f.succeeded(op);f.time+=PostUpdateAssessments.INTENT_LIFETIME_MILLIS;f.tx(()->f.coordinator.maintain(f.time));
            check(f.relation("offline").get("status").equals("EXPIRED")&&f.queued.get()==0,"offline intent expires without another heartbeat");
            f.host("two","production");var two=f.operation("queued-expiry","two",true);f.succeeded(two);f.dispatch("two");String owned=f.job("queued-expiry");f.time+=PostUpdateAssessments.INTENT_LIFETIME_MILLIS;f.tx(()->f.coordinator.maintain(f.time));
            check(f.relation("queued-expiry").get("status").equals("EXPIRED")&&f.db.get("jobs",owned).get("status").equals("FAILED"),"expired owned queued job cannot run later");
            f.host("three","production");f.tx(()->f.db.put("jobs",map("id","manual-queued","hostId","three","status","QUEUED","trigger","manual","createdAt",at(f.time))));var three=f.operation("manual-expiry","three",true);f.succeeded(three);f.dispatch("three");f.time+=PostUpdateAssessments.INTENT_LIFETIME_MILLIS;f.tx(()->f.coordinator.maintain(f.time));
            check(f.relation("manual-expiry").get("status").equals("EXPIRED")&&f.db.get("jobs","manual-queued").get("status").equals("QUEUED"),"expiry does not cancel independently requested manual job");
            f.host("four","production");var four=f.operation("revoked","four",true);f.succeeded(four);f.tx(()->{var identity=f.db.get("identities","four");identity.put("status","revoked");f.db.put("identities",identity);f.coordinator.maintain(f.time);});check(f.relation("revoked").get("status").equals("CANCELLED")&&f.relation("revoked").get("reason").equals("AUTHORIZATION_REVOKED"),"revocation cancels intent");
            f.host("five","production");var five=f.operation("rolled-back","five",true);f.tx(()->{five.put("status","ROLLED_BACK");f.db.put("agent_update_requests",five);f.coordinator.onStopped(five,"ROLLED_BACK",f.time);});f.dispatch("five");check(f.relation("rolled-back").get("status").equals("CANCELLED")&&s(f.relation("rolled-back").get("jobId")).isEmpty(),"rollback cannot create assessment");
        }
    }

    static void restartAndRetention()throws Exception{
        try(var f=new Fixture()){
            f.host("one","production");var op=f.operation("restart","one",true);f.succeeded(op);f.dispatch("one");String job=f.job("restart");f.reopen();f.dispatch("one");
            check(f.job("restart").equals(job)&&f.queued.get()==1,"restart recovers same queued relation");
            check(f.lease("one",job),"queued work resumes after restart");f.reopen();f.dispatch("one");
            check(f.relation("restart").get("status").equals("FAILED")&&f.db.get("agent_update_requests","restart").get("status").equals("SUCCEEDED"),"startup job failure reconciles without invalidating upgrade");
            // A late acknowledged receipt may legitimately replace restart failure.
            f.tx(()->{var record=f.db.get("jobs",job);record.put("status","SUCCEEDED");record.remove("error");record.put("summary","Assessment received; token=private-fixture");record.put("coverage",map("warnings",List.of("password=hidden fixture note")));record.put("finishedAt",at(f.time));f.db.put("jobs",record);f.coordinator.jobChanged(record,f.time);});
            check(f.relation("restart").get("status").equals("SUCCEEDED"),"late valid result replaces restart-only failure");
            f.tx(()->{f.coordinator.jobChanged(f.db.get("jobs",job),f.time);f.db.delete("jobs",job);});
            var archived=f.relation("restart");String serialized=Json.stringify(archived);check(Boolean.FALSE.equals(archived.get("detailsAvailable"))&&archived.get("jobId").equals(job)&&archived.get("status").equals("SUCCEEDED"),"trimming preserves correlated result and marks details unavailable");
            check(!serialized.contains("private-fixture")&&!serialized.contains("hidden")&&serialized.contains("[redacted]"),"retained summaries and observations redact credentials");
            f.dispatch("one");check(f.queued.get()==1,"history trimming never creates replacement job");
        }
    }

    static void atomicityAndConcurrentDispatch()throws Exception{
        try(var f=new Fixture()){
            f.host("one","production");var op=f.operation("atomic","one",true);
            try{f.db.transaction(()->{op.put("status","SUCCEEDED");f.db.put("agent_update_requests",op);f.coordinator.onSucceeded(op,f.time);throw new IllegalStateException("fixture final commit failed");});throw new AssertionError("failure expected");}catch(IllegalStateException expected){}
            check(f.db.get("agent_update_requests","atomic").get("status").equals("WAITING")&&f.relation("atomic").get("status").equals("WAITING_FINAL"),"final update and intent readiness roll back atomically");
            f.succeeded(op);
            try{f.tx(()->f.coordinator.dispatch("one",f.time,host->{f.queue(host);throw new IllegalStateException("fixture queue commit failed");}));throw new AssertionError("failure expected");}catch(IllegalStateException expected){}
            check(f.db.scalar("SELECT count(*) FROM jobs")==0&&f.relation("atomic").get("status").equals("READY"),"queue and association roll back together");
            var pool=Executors.newFixedThreadPool(2);
            try{
                List<Callable<Void>> work=List.of(()->{f.dispatch("one");return null;},()->{f.dispatch("one");return null;});for(var result:pool.invokeAll(work))result.get();
                check(f.db.scalar("SELECT count(*) FROM jobs")==1,"simultaneous dispatch creates exactly one durable job");
                String job=f.job("atomic");List<Callable<Boolean>> claims=List.of(()->f.lease("one",job),()->f.lease("one",job));var leases=pool.invokeAll(claims);int accepted=0;for(var result:leases)if(result.get())accepted++;
                check(accepted==1,"simultaneous polls grant one linked lease");
            }finally{pool.shutdownNow();}
        }
        try(var f=new Fixture()){
            f.host("one","production");var op=f.operation("recursive","one",true);f.succeeded(op);
            f.tx(()->f.coordinator.dispatch("one",f.time,host->{f.coordinator.dispatch(host,f.time,f::queue);return f.queue(host);}));
            check(f.queued.get()==1,"maintenance recursion cannot duplicate dispatch");
        }
    }

    static void persistedSpacingAndVersionGuard()throws Exception{
        try(var f=new Fixture()){
            for(String host:List.of("one","two")){f.host(host,"same-env");var op=f.operation("spacing-"+host,host,true);f.succeeded(op);f.dispatch(host);}
            check(f.lease("one",f.job("spacing-one")),"spacing fixture starts first collection");f.finish(f.job("spacing-one"),"SUCCEEDED");f.reopen();f.time+=5000;
            check(!f.lease("two",f.job("spacing-two")),"restart does not erase persisted environment spacing");f.time+=5000;
            check(f.lease("two",f.job("spacing-two")),"persisted spacing expires at original deadline");
        }
        try(var f=new Fixture()){
            f.host("one","production");var op=f.operation("version-guard","one",true);f.succeeded(op);f.dispatch("one");String job=f.job("version-guard");
            for(String observed:List.of("1.5.12","unknown")){f.tx(()->{var host=f.db.get("hosts","one");host.put("collectorVersion",observed);f.db.put("hosts",host);});check(!f.lease("one",job),"late downgrade/unknown heartbeat cannot lease post-update job: "+observed);}
            f.tx(()->{var host=f.db.get("hosts","one");host.put("collectorVersion","9.0.2");f.db.put("hosts",host);});check(f.lease("one",job),"target version confirmation restores eligibility without duplicating job");
        }
    }

    public static void main(String[] args)throws Exception{
        intentAndFinalOnly();reusingAndOldRunning();throttleAndSpacing();replacementAndBusy();expiryAndRevocation();restartAndRetention();atomicityAndConcurrentDispatch();persistedSpacingAndVersionGuard();
        System.out.println("PostUpdateAssessmentsTest PASS: "+checks+" SQLite checks; final-only/idempotency/reuse/conflicts/throttle/expiry/restart/retention/rollback/concurrency; local fixtures only.");
    }
}
