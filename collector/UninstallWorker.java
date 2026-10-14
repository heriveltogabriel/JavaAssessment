package assessment;

import java.io.*;
import java.util.*;
import java.nio.channels.*;

/** Java 6 completion helper, installed in an independent root service. */
public final class UninstallWorker {
    static final File HOME=new File("/opt/java-assessment-uninstall");
    static final File TARGET=new File("/opt/java-assessment-collector");
    static final String TYPE="UNINSTALL_AGENT_V1";
    static final long RETENTION=86400000L;
    static Map read(File f)throws Exception{return (Map)Json.parse(AssessmentScan.readFile(f,32768));}
    static void write(File f,Map m)throws Exception{durable(f,Json.stringify(m));}
    static void durable(File f,String text)throws Exception{File temp=new File(f.getPath()+".tmp");FileOutputStream out=new FileOutputStream(temp);try{out.write(text.getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}if(!temp.renameTo(f))throw new IOException("Cannot publish worker state");}
    static long num(Object o){return o instanceof Number?((Number)o).longValue():0;}
    static void copy(File from,File to)throws Exception{durable(to,AssessmentScan.readFile(from,32768));}
    static void copyCa(File from,File to)throws Exception{
        byte[] bytes=AgentUpdateWorker.read(from,512L*1024L);File temporary=new File(to.getPath()+".tmp");FileOutputStream out=new FileOutputStream(temporary);
        try{out.write(bytes);out.getFD().sync();}finally{out.close();}AgentTool.ownerOnly(temporary);
        if(!temporary.renameTo(to))throw new IOException("Cannot publish private worker CA");
    }
    static int local(String action)throws Exception {
        Process p=new ProcessBuilder(new String[]{"/bin/sh",new File(HOME,"uninstall-local.sh").getPath(),action}).redirectErrorStream(true).start();
        InputStream in=p.getInputStream();byte[] buf=new byte[1024];int bytes=0,n;try{while((n=in.read(buf))!=-1){bytes+=n;if(bytes>8192){p.destroy();throw new IOException("Local verification output limit");}}}finally{in.close();}return p.waitFor();
    }
    static Properties config()throws Exception{Properties c=new Properties();InputStream in=new FileInputStream(new File(HOME,"identity.properties"));try{c.load(in);}finally{in.close();}return c;}
    static void cycle()throws Exception{
        File requestFile=new File(HOME,"request.json"),claimFile=new File(HOME,"claim.json"),resultFile=new File(HOME,"result.json");
        if(!requestFile.isFile()){
            File incoming=new File(TARGET,"collector-spool/uninstall-request.json");if(!incoming.isFile()){new File(HOME,"identity.properties").delete();new File(HOME,"ca.pem").delete();return;}Map r=read(incoming);
            if(!TYPE.equals(r.get("type"))||!(r.get("requestId") instanceof String)||!((String)r.get("requestId")).matches("[a-f0-9-]{36}")||num(r.get("expiresMillis"))<=System.currentTimeMillis()) {incoming.delete();return;}
            copy(new File(TARGET,"conf/collector.properties"),new File(HOME,"identity.properties"));
            Properties current=config();File ca=new File(TARGET,"trust/ca.pem");
            if(new File(HOME,"v3-inventory.json").isFile())ca=new File(AssessmentCollector.required(current,"tlsCaFile"));
            copyCa(ca,new File(HOME,"ca.pem"));
            r.put("localDeadline",new Long(System.currentTimeMillis()+RETENTION));write(requestFile,r);incoming.delete();
        }
        Map r=read(requestFile);
        if(num(r.get("localDeadline"))<=System.currentTimeMillis()){
            if(new File(HOME,"v3-inventory.json").isFile())throw new IOException("V3 removal recovery retained after deadline");
            local("cleanup");return;
        }
        Properties c=config();c.setProperty("tlsCaFile",new File(HOME,"ca.pem").getPath());AgentTransport transport=new AgentTransport(c);String host=AssessmentCollector.required(c,"hostId"),id=(String)r.get("requestId");Map claim;
        if(!claimFile.isFile()){
            try{claim=transport.request("POST","/api/agent/uninstall/claim",Json.stringify(AssessmentScan.map("hostId",host,"requestId",id)),AssessmentCollector.required(c,"agentToken"));}
            catch(AgentTransport.ApiException e){
                if((e.status==409&&num(r.get("expiresMillis"))<=System.currentTimeMillis())||e.status==401||e.status==403||e.status==404||e.status==410){
                    // No local changes were made; clear temporary credentials and await next request.
                    new File(HOME,"identity.properties").delete();new File(HOME,"ca.pem").delete();requestFile.delete();return;
                }
                throw e;
            }
            if(Boolean.FALSE.equals(claim.get("executable"))&&id.equals(claim.get("requestId"))){new File(HOME,"identity.properties").delete();new File(HOME,"ca.pem").delete();requestFile.delete();return;}
            if(!id.equals(claim.get("requestId"))||!(claim.get("completionToken") instanceof String)||!((String)claim.get("completionToken")).matches("[a-f0-9]{64}"))throw new IOException("Invalid claim acknowledgement");
            write(claimFile,claim); // Durable before any destructive operation.
        }else claim=read(claimFile);
        Map result;
        if(!resultFile.isFile()){
            int status=local("remove");
            if(status!=0&&new File(HOME,"v3-inventory.json").isFile())throw new IOException("V3 removal requires durable retry");
            result=AssessmentScan.map("hostId",host,"requestId",id,"outcome",status==0?"SUCCEEDED":"FAILED","verification",status==0?"OWN_FILES_AND_SCHEDULER_ABSENT_V1":"LOCAL_VERIFICATION_FAILED_V1");write(resultFile,result);
        }else result=read(resultFile);
        Map ack=transport.request("POST","/api/agent/uninstall/complete",Json.stringify(result),(String)claim.get("completionToken"));
        if(!Boolean.TRUE.equals(ack.get("accepted"))||!id.equals(ack.get("requestId"))||!result.get("outcome").equals(ack.get("status")))throw new IOException("Invalid completion acknowledgement");
        write(new File(HOME,"ack.json"),ack);
        local("cleanup");
    }
    public static void main(String[] args){
        try{
            if(args.length!=0)throw new IllegalArgumentException("No arguments accepted");
            RandomAccessFile lf=new RandomAccessFile(new File(HOME,"worker.lock"),"rw");FileLock lock=null;
            try{lock=lf.getChannel().tryLock();if(lock==null)return;cycle();}finally{if(lock!=null)lock.release();lf.close();}
        }catch(Exception e){System.err.println("UNINSTALL_RETRY="+AgentTransport.safeError(e));System.exit(1);}
    }
}
