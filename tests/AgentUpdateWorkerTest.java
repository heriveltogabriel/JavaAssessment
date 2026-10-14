package assessment;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import com.sun.net.httpserver.*;

/** Disposable files and object adapters only; never invokes the production shell. */
public final class AgentUpdateWorkerTest {
    static int checks;
    static KeyPair key;
    static final String ID="00000000-0000-0000-0000-000000000001";
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
    static void put(File f,byte[] b)throws Exception{f.getParentFile().mkdirs();FileOutputStream o=new FileOutputStream(f);try{o.write(b);}finally{o.close();}}
    static void put(File f,String s)throws Exception{put(f,s.getBytes("UTF-8"));}
    static void jar(File f,String version)throws Exception{
        Manifest m=new Manifest();m.getMainAttributes().putValue("Manifest-Version","1.0");m.getMainAttributes().putValue("Implementation-Version",version);
        JarOutputStream o=new JarOutputStream(new FileOutputStream(f),m);try{o.putNextEntry(new JarEntry("probe-marker"));o.write(version.getBytes("UTF-8"));o.closeEntry();}finally{o.close();}
    }
    static void remove(File f){if(f.isDirectory())for(File c:f.listFiles())remove(c);f.delete();}
    static final class Fixture implements AgentUpdateWorker.Platform,AgentUpdateWorker.Remote {
        final File base,root,home,payload;
        final List events=new ArrayList();
        Map manifest;
        byte[] signed,signature;
        int heartbeatResult;
        boolean running=true,failLiveProbe,failCandidate,network,loseAck,crashSwap,crashPause,denyThirdClaim,badVerifyAck;
        int claims, heartbeats;
        String lastStatus,lastDiagnostic;
        Fixture()throws Exception{
            base=File.createTempFile("agent-update-fixture-","").getCanonicalFile();base.delete();base.mkdir();root=new File(base,"collector");home=new File(base,"helper");root.mkdir();home.mkdir();
            put(new File(root,"conf/collector.properties"),"hostId=fixture-host\nagentToken=private-fixture-token\nserverUrl=http://127.0.0.1:1\n");
            put(new File(root,"collector-spool/update-request.json"),Json.stringify(AssessmentScan.map("type","UPDATE_AGENT_V1","requestId",ID,"targetVersion","1.0.1","expiresMillis",new Long(System.currentTimeMillis()+60000))));
            put(new File(root,"update-public.der"),key.getPublic().getEncoded());put(new File(root,"root-service.sh"),"stable service\n");put(new File(root,"lib/tls.jar"),"stable library\n");
            put(new File(root,"apps/application.properties"),"preserve application\n");jar(new File(root,"collector.jar"),"1.0.0");payload=new File(base,"payload.jar");jar(payload,"1.0.1");
            Map assets=new LinkedHashMap();for(Object n:AgentUpdateWorker.stableFiles(root))assets.put(n,AgentUpdateWorker.hash(new File(root,(String)n)));
            manifest=AssessmentScan.map("type","ASSESSMENT_AGENT_UPDATE_V1","schema",new Long(1),"version","1.0.1","format","collector-jar-v1","minUpdater",new Long(1),"bytes",new Long(payload.length()),"sha256",AgentUpdateWorker.hash(payload),"requiredFiles",assets);sign();
        }
        void sign()throws Exception{signed=Json.stringify(manifest).getBytes("UTF-8");Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key.getPrivate());s.update(signed);signature=s.sign();}
        AgentUpdateWorker worker(){return new AgentUpdateWorker(root,home,this,this);}
        public void local(String action)throws Exception{
            events.add(action);
            if(action.equals("pause")){running=false;if(crashPause){crashPause=false;throw new AssertionError("simulated process death");}}
            if(action.equals("start"))running=true;
            if(action.equals("sync")&&crashSwap&&AgentUpdateWorker.jarVersion(new File(root,"collector.jar")).equals("1.0.1")){crashSwap=false;throw new AssertionError("simulated process death");}
        }
        public int probe(File file,String version,boolean heartbeat)throws Exception{
            events.add(heartbeat?"heartbeat":"probe");
            if(heartbeat){heartbeats++;return network?10:AgentUpdateWorker.Production.probeResult(heartbeatResult,true);}
            if(failCandidate&&file.getName().equals("candidate.jar"))return 20;
            return failLiveProbe&&file.equals(new File(root,"collector.jar"))&&version.equals("1.0.1")?20:0;
        }
        public Map request(String action,Map body)throws Exception{
            if(action.equals("claim")){claims++;if(denyThirdClaim&&claims==3)return AssessmentScan.map("requestId",ID,"executable",Boolean.FALSE);
                return AssessmentScan.map("requestId",ID,"executable",Boolean.TRUE,"targetVersion","1.0.1","expiresMillis",new Long(System.currentTimeMillis()+60000));}
            lastStatus=(String)body.get("status");lastDiagnostic=(String)body.get("diagnostic");events.add(lastStatus);
            if(lastStatus.equals("VERIFYING")&&badVerifyAck)return AssessmentScan.map("accepted",Boolean.FALSE,"requestId",ID,"status",lastStatus);
            if(lastStatus.equals("SUCCEEDED")){check(heartbeats>0,"success must follow heartbeat");if(loseAck){loseAck=false;throw new IOException("ACK lost");}}
            return AssessmentScan.map("accepted",Boolean.TRUE,"requestId",ID,"status",lastStatus);
        }
        public void download(String asset,File file,long limit,Map state)throws Exception{
            byte[] bytes=asset.equals("manifest")?signed:asset.equals("signature")?signature:AgentUpdateWorker.read(payload,AgentUpdateWorker.MAX_JAR);
            check(bytes.length<=limit,"bounded asset");put(file,bytes);
        }
        void unchanged()throws Exception{
            check(AssessmentScan.readFile(new File(root,"conf/collector.properties"),4096).contains("private-fixture-token"),"identity unchanged");
            check(AssessmentScan.readFile(new File(root,"apps/application.properties"),4096).equals("preserve application\n"),"application unchanged");
        }
        void close(){remove(base);}
    }
    static void failure(String kind,String diagnostic)throws Exception{
        Fixture f=new Fixture();try{
            if(kind.equals("signature"))f.signature[0]^=1;
            if(kind.equals("payload"))put(f.payload,"tampered jar");
            if(kind.equals("asset"))put(new File(f.root,"lib/tls.jar"),"changed");
            if(kind.equals("traversal")){((Map)f.manifest.get("requiredFiles")).put("../collector.jar",AgentUpdateWorker.hash(f.payload));f.sign();}
            if(kind.equals("minimum")){f.manifest.put("minUpdater",new Long(3));f.sign();}
            if(kind.equals("bytes")){f.manifest.put("bytes",new Long(AgentUpdateWorker.MAX_JAR+1));f.sign();}
            if(kind.equals("downgrade")){jar(new File(f.root,"collector.jar"),"1.0.2");}
            if(kind.equals("same")){jar(new File(f.root,"collector.jar"),"1.0.1");}
            if(kind.equals("version")){jar(f.payload,"9.0.0");f.manifest.put("bytes",new Long(f.payload.length()));f.manifest.put("sha256",AgentUpdateWorker.hash(f.payload));f.sign();}
            if(kind.equals("broken"))f.failCandidate=true;
            String previous=AgentUpdateWorker.hash(new File(f.root,"collector.jar"));f.worker().cycle();
            check("FAILED".equals(f.lastStatus)&&diagnostic.equals(f.lastDiagnostic),kind+" refused as "+diagnostic+" actual="+f.lastStatus+"/"+f.lastDiagnostic);
            check(previous.equals(AgentUpdateWorker.hash(new File(f.root,"collector.jar"))),kind+" retains old jar");check(!f.events.contains("pause"),kind+" does not stop scheduler");f.unchanged();
        }finally{f.close();}
    }
    static void transport()throws Exception{
        final byte[] body=new byte[8192];final int[] mode={0};
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/agent/update/package",new HttpHandler(){public void handle(HttpExchange x)throws IOException{
            try{if(mode[0]==1){x.getResponseHeaders().set("Location","http://127.0.0.1:1/secret");x.sendResponseHeaders(302,-1);return;}
                x.sendResponseHeaders(200,mode[0]==2?0:body.length);x.getResponseBody().write(body);
            }finally{x.close();}
        }});server.start();File f=File.createTempFile("agent-update-transfer-","");
        try{Properties p=new Properties();p.setProperty("serverUrl","http://127.0.0.1:"+server.getAddress().getPort());AgentTransport t=new AgentTransport(p);
            String path="/api/agent/update/package?hostId=host&requestId="+ID;
            t.download(path,f,8192,"secret");check(f.length()==8192,"bounded download success");
            for(int i=0;i<3;i++){mode[0]=i;try{t.download(path,f,1024,"secret");throw new AssertionError("oversize/redirect accepted");}catch(IOException expected){}check(!f.exists(),"reject content length/chunked overflow/redirect and remove partial");}
            try{t.download("https://other.invalid/package",f,1024,"secret");throw new AssertionError("arbitrary URL");}catch(IllegalArgumentException expected){checks++;}
        }finally{server.stop(0);f.delete();}
    }
    public static void main(String[] args)throws Exception{
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);key=generator.generateKeyPair();
        String[][] cases={{"signature","INVALID_SIGNATURE"},{"payload","HASH_MISMATCH"},{"asset","INCOMPATIBLE_ASSETS"},{"traversal","INCOMPATIBLE_ASSETS"},{"minimum","INVALID_MANIFEST"},{"bytes","INVALID_MANIFEST"},{"downgrade","DOWNGRADE_REFUSED"},{"same","DOWNGRADE_REFUSED"},{"version","VERSION_MISMATCH"},{"broken","PROBE_FAILED"}};
        for(int i=0;i<cases.length;i++)failure(cases[i][0],cases[i][1]);
        Fixture f=new Fixture();try{f.worker().cycle();check("SUCCEEDED".equals(f.lastStatus),"success");check(f.running,"scheduler resumed");check(AgentUpdateWorker.jarVersion(new File(f.root,"collector.jar")).equals("1.0.1"),"actual jar changed");check(!new File(f.home,"journal.json").exists(),"ack clears journal");check(f.claims==3,"claim revalidated immediately before swap");f.unchanged();}finally{f.close();}
        f=new Fixture();try{f.failLiveProbe=true;f.worker().cycle();check("ROLLED_BACK".equals(f.lastStatus),"bad installed code rolls back");check(AgentUpdateWorker.jarVersion(new File(f.root,"collector.jar")).equals("1.0.0")&&f.running,"old jar and scheduler restored");f.unchanged();}finally{f.close();}
        f=new Fixture();try{f.network=true;try{f.worker().cycle();throw new AssertionError("network accepted");}catch(IOException expected){}check(AgentUpdateWorker.jarVersion(new File(f.root,"collector.jar")).equals("1.0.1")&&f.running,"network does not roll back healthy code");check(new File(f.home,"backup.jar").exists(),"backup retained offline");f.network=false;f.worker().cycle();check("SUCCEEDED".equals(f.lastStatus),"offline confirmation retry");f.unchanged();}finally{f.close();}
        f=new Fixture();try{f.loseAck=true;try{f.worker().cycle();throw new AssertionError("lost ACK accepted");}catch(IOException expected){}check(new File(f.home,"journal.json").exists()&&new File(f.home,"backup.jar").exists(),"lost ACK retains journal and backup");f.worker().cycle();check(!new File(f.home,"journal.json").exists(),"ACK replay clears journal");}finally{f.close();}
        f=new Fixture();try{f.badVerifyAck=true;for(int retry=0;retry<2;retry++){try{f.worker().cycle();throw new AssertionError("invalid ACK accepted");}catch(IOException expected){}check(AgentUpdateWorker.jarVersion(new File(f.root,"collector.jar")).equals("1.0.1")&&new File(f.home,"backup.jar").exists(),"invalid network ACK never rolls back healthy code on resume");}f.badVerifyAck=false;f.worker().cycle();check("SUCCEEDED".equals(f.lastStatus),"invalid ACK retry eventually confirms");}finally{f.close();}
        for(int i=0;i<2;i++){f=new Fixture();try{f.crashSwap=i==0;f.crashPause=i==1;try{f.worker().cycle();throw new AssertionError("missing crash");}catch(AssertionError expected){check(expected.getMessage().equals("simulated process death"),"simulated abrupt death");}f.worker().cycle();check(AgentUpdateWorker.jarVersion(new File(f.root,"collector.jar")).equals("1.0.0")&&f.running,"crash resumes old jar and scheduler");check((i==0?"ROLLED_BACK":"FAILED").equals(f.lastStatus),"crash outcome confirmed");}finally{f.close();}}
        f=new Fixture();try{f.denyThirdClaim=true;f.worker().cycle();check(AgentUpdateWorker.jarVersion(new File(f.root,"collector.jar")).equals("1.0.0")&&f.running,"revocation before swap restores scheduler without replacing jar");check(!new File(f.home,"journal.json").exists(),"cancelled authorization cleaned");}finally{f.close();}
        check(AgentUpdateWorker.Production.probeResult(20,true)==20 && AgentUpdateWorker.Production.probeResult(30,false)==30,"real probe failures are never masked");
        for(int code:new int[]{10,30,31}){f=new Fixture();try{f.heartbeatResult=code;for(int attempt=0;attempt<2;attempt++){try{f.worker().cycle();throw new AssertionError("inconclusive heartbeat accepted");}catch(IOException expected){}check(AgentUpdateWorker.jarVersion(new File(f.root,"collector.jar")).equals("1.0.1")&&f.running&&new File(f.home,"journal.json").exists(),"heartbeat timeout retains healthy version and journal on retry");}f.heartbeatResult=0;f.worker().cycle();check("SUCCEEDED".equals(f.lastStatus),"heartbeat recovered after timeout");}finally{f.close();}}
        f=new Fixture();try{f.manifest.put("minUpdater",new Long(2));f.sign();f.worker().cycle();check("SUCCEEDED".equals(f.lastStatus),"generation 2 signed release supported");}finally{f.close();}
        check("LOCAL_CHECK_FAILED".equals(AgentUpdateWorker.retryDiagnostic(new AgentUpdateWorker.Failure("LOCAL_CHECK_FAILED"))),"local failure is not mislabeled as network");
        check("FILE_OR_TRANSPORT_ERROR".equals(AgentUpdateWorker.retryCode(new IOException("secret fixture value"))),"retry logs do not disclose exception content");
        transport();System.out.println("AgentUpdateWorkerTest PASS "+checks+" checks");
    }
}
