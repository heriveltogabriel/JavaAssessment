package assessment;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Unprivileged bridge to the independent worker. Never executes commands. */
final class DashboardMaintenance {
    static final long MAX_UPLOAD=256L*1024*1024;
    private final Path state;
    private final Semaphore uploadSlot=new Semaphore(1);
    DashboardMaintenance(Path state){this.state=state;}
    boolean active(){return Files.exists(state.resolve("maintenance"),LinkOption.NOFOLLOW_LINKS);}
    Map<String,Object> status()throws IOException {
        Path file=state.resolve("status.json");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return AssessmentServer.map("phase","UNAVAILABLE","message","Atualizador não instalado. Instale a versão com suporte à atualização pelo painel.");
        try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=in.readNBytes(256*1024+1);if(bytes.length>256*1024)throw new IOException("Invalid updater status");
            @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)Json.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));return value;
        }
    }
    private Path inbox()throws IOException {
        Path p=state.resolve("inbox");
        if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||!p.toRealPath().equals(p.toAbsolutePath()))throw new IOException("Updater unavailable");
        try(var entries=Files.list(p)){if(entries.limit(10).count()>=10)throw new IOException("Updater queue full");}
        return p;
    }
    private String id(){return UUID.randomUUID().toString().replace("-","");}
    private void publish(Path directory,String id,Map<String,Object> value)throws IOException {
        Path temp=directory.resolve(id+".request");
        try{
            try(OutputStream out=Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){out.write(Json.stringify(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
            Files.move(temp,directory.resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE);
        } finally{Files.deleteIfExists(temp);}
    }
    Map<String,Object> upload(InputStream stream,long length,String actor,Runnable reauthorize)throws IOException {
        if(length<=0||length>MAX_UPLOAD)throw new IllegalArgumentException("O pacote deve ter até 256 MiB.");
        if(!uploadSlot.tryAcquire())throw new IOException("Another upload is in progress");
        Path part=null,zip=null;
        try{
            Path directory=inbox();
            String phase=String.valueOf(status().get("phase"));
            if(!Set.of("IDLE","SUCCEEDED","ROLLED_BACK","CANCELLED","FAILED").contains(phase))throw new IOException("Updater is not ready for another package");
            try(var queued=Files.list(directory)){if(queued.anyMatch(p->p.toString().endsWith(".zip")||p.toString().endsWith(".json")))throw new IOException("An updater request is already queued");}
            String id=id();part=directory.resolve(id+".part");zip=directory.resolve(id+".zip");
            try(OutputStream out=Files.newOutputStream(part,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                byte[] buffer=new byte[65536];long total=0;int n;
                while((n=stream.read(buffer))!=-1){total+=n;if(total>length||total>MAX_UPLOAD)throw new IOException("Upload exceeds declared size");out.write(buffer,0,n);}
                if(total!=length)throw new IOException("Incomplete upload");
            }
            reauthorize.run();Files.setPosixFilePermissions(part,PosixFilePermissions.fromString("rw-------"));
            Files.move(part,zip,StandardCopyOption.ATOMIC_MOVE);
            publish(directory,id,AssessmentServer.map("action","prepare","actor",actor));zip=null;
            return AssessmentServer.map("accepted",true,"operationId",id);
        }finally{if(part!=null)Files.deleteIfExists(part);if(zip!=null)Files.deleteIfExists(zip);uploadSlot.release();}
    }
    Map<String,Object> command(String action,String operation,String actor)throws IOException {
        if(!Set.of("confirm","cancel","rollback").contains(action)||!operation.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid update action");
        publish(inbox(),id(),AssessmentServer.map("action",action,"operationId",operation,"actor",actor));
        return AssessmentServer.map("accepted",true,"operationId",operation);
    }
}
