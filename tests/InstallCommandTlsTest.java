package assessment;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import javax.net.ssl.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.*;
import java.util.zip.*;
import java.security.MessageDigest;

/** Real local curl HTTPS test using a disposable key; no external host is contacted. */
public final class InstallCommandTlsTest {
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("install-command-tls-"),ksPath=dir.resolve("tls.p12");String pass="disposable-test-password";
        Process keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/keytool").toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=127.0.0.1","-ext","SAN=ip:127.0.0.1","-storetype","PKCS12","-keystore",ksPath.toString(),"-storepass",pass,"-keypass",pass).redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();check(keytool.waitFor()==0,"test key generation");KeyStore ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(ksPath)){ks.load(in,pass.toCharArray());}
        String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(ks.getCertificate("test").getEncoded())+"\n-----END CERTIFICATE-----\n";
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,pass.toCharArray());SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),null,null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),4);ExecutorService workers=Executors.newFixedThreadPool(2);server.setExecutor(workers);server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        String token="eia1_"+"b".repeat(64);byte[] install="#!/bin/sh\nexit 99\n".getBytes();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(var e:Map.of("install.sh",install,"SHA256SUMS",(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(install))+"  install.sh\n").getBytes()).entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
        AtomicInteger accepted=new AtomicInteger(),redirectTarget=new AtomicInteger(),mode=new AtomicInteger();
        server.createContext("/api/install/package",x->{try{if(!("Bearer "+token).equals(x.getRequestHeaders().getFirst("Authorization"))){x.sendResponseHeaders(403,-1);return;}accepted.incrementAndGet();if(mode.get()==1){x.getResponseHeaders().set("Location","https://127.0.0.1:"+server.getAddress().getPort()+"/redirect-target");x.sendResponseHeaders(302,-1);return;}x.sendResponseHeaders(200,bytes.size());x.getResponseBody().write(bytes.toByteArray());}finally{x.close();}});
        server.createContext("/redirect-target",x->{redirectTarget.incrementAndGet();x.sendResponseHeaders(500,-1);x.close();});server.start();
        Path bin=Files.createDirectory(dir.resolve("bin"));Files.writeString(bin.resolve("sleep"),"#!/bin/sh\nexit 0\n");bin.resolve("sleep").toFile().setExecutable(true);
        try{
            String origin="https://127.0.0.1:"+server.getAddress().getPort();
            for(int test=0;test<3;test++){
                mode.set(test==2?1:0);int before=accepted.get();byte[] ca=test==1?Files.readAllBytes(Path.of("collector/public/ca.pem")):pem.getBytes();String command=(String)InstallCommand.build(origin,"env-tls",token,ca).get("curl");Path script=dir.resolve("download.sh");Files.writeString(script,command);
                ProcessBuilder pb=new ProcessBuilder("/bin/sh",script.toString()).redirectErrorStream(true);pb.environment().put("PATH",bin+":"+pb.environment().get("PATH"));pb.environment().put("NO_PROXY","127.0.0.1");Process process=pb.start();String output=new String(process.getInputStream().readAllBytes());int result=process.waitFor();
                check(test==0?result==0:result!=0,"TLS test "+test+": "+output);check(!output.contains(token),"no bearer output");
                if(test==0){check(accepted.get()==before+1&&output.contains("sudo /bin/sh"),"authenticated download succeeded");var matcher=java.util.regex.Pattern.compile("'(/tmp/java-assessment-install[^']+)/package/install.sh'").matcher(output);check(matcher.find(),"exact installation stage");Path stage=Path.of(matcher.group(1));check(!Files.exists(stage.resolve("download.conf")),"config cleaned");try(var paths=Files.walk(stage)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
                if(test==1)check(accepted.get()==before,"wrong CA never sends HTTP credential");
                if(test==2)check(redirectTarget.get()==0,"redirect never followed");
            }
            System.out.println("InstallCommandTlsTest PASS: real curl HTTPS success, wrong-CA refusal before credentials, no redirects, checksum and separate install command.");
        }finally{server.stop(0);workers.shutdownNow();try(var paths=Files.walk(dir)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
