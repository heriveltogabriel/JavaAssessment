package assessment;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;

public final class InstallCommandTest {
    static int checks;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    interface Action {void run()throws Exception;}
    static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("accepted unsafe command input");}catch(IllegalArgumentException|java.net.URISyntaxException|java.security.cert.CertificateException expected){checks++;}}
    static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    public static void main(String[] args)throws Exception{
        byte[] ca=Files.readAllBytes(Path.of("collector/public/ca.pem"));String token="eia1_"+"a".repeat(64);
        Map<String,Object> commands=InstallCommand.build("https://dashboard.example:8443","env-fixture",token,ca);
        for(String origin:List.of("http://host","https://user:pass@host","https://host/x","https://host/?token=bad","https://host/#bad","https://host\nX-Bad: value","https://host/$(id)","https://host/';echo bad"))reject(()->InstallCommand.build(origin,"env-fixture",token,ca));
        reject(()->InstallCommand.build("https://host","env-';echo bad",token,ca));
        reject(()->InstallCommand.build("https://host","env-fixture","admin-token",ca));
        reject(()->InstallCommand.build("https://host","env-fixture",token,"not a CA".getBytes()));
        Path folder=Files.createTempDirectory("install-command-test-");
        byte[] body="#!/bin/sh\nexit 99\n".getBytes(StandardCharsets.UTF_8);Path archive=folder.resolve("fixture.zip");
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(archive))){for(var row:Map.of("install.sh",body,"SHA256SUMS",(sha(body)+"  install.sh\n").getBytes()).entrySet()){z.putNextEntry(new ZipEntry(row.getKey()));z.write(row.getValue());z.closeEntry();}}
        // Mock downloaders inspect actual private config/argv, then supply a known ZIP. No network.
        Path bin=Files.createDirectory(folder.resolve("bin"));
        Files.writeString(bin.resolve("sleep"),"#!/bin/sh\nexit 0\n");bin.resolve("sleep").toFile().setExecutable(true);
        String stub="#!/bin/sh\nset -eu\nprintf '%s\\n' \"$*\" > \"$FIXTURE_ARGS\"\nif test \"${WGETRC:-}\" != ''; then conf=$WGETRC; else test \"$1\" = -q; test \"$2\" = --config; conf=$3; fi\nprintf '%s' \"$conf\" > \"$FIXTURE_CONF\"\n"+
            "test \"$(stat -c %a \"$conf\" 2>/dev/null || stat -f %Lp \"$conf\")\" = 600\n"+
            "out=$(sed -n 's/^output = \"\\(.*\\)\"$/\\1/p;s/^output_document = //p' \"$conf\")\n"+
            "if test \"${FIXTURE_FAIL:-0}\" = 1; then exit 22; fi\ncp \"$FIXTURE_ZIP\" \"$out\"\n";
        for(String client:commands.keySet()){
            Path program=bin.resolve(client);Files.writeString(program,stub);program.toFile().setExecutable(true);
            String script=(String)commands.get(client);check(!script.contains("--insecure")&&!script.contains("--no-check-certificate"),"TLS always checked");
            check(script.contains("sha256sum -c SHA256SUMS")&&script.contains("unzip -tq"),"ZIP/integrity checks");
            check(script.contains("max_redirect = 0")||script.contains("max-redirs = 0"),"no credential redirects");
            Path file=folder.resolve(client+".sh");Files.writeString(file,script);
            Path idStub=bin.resolve("id");Files.writeString(idStub,"#!/bin/sh\nprintf '0\\n'\n");idStub.toFile().setExecutable(true);
            ProcessBuilder sudoDownload=new ProcessBuilder("/bin/sh",file.toString()).redirectErrorStream(true);
            sudoDownload.environment().put("PATH",bin+":"+sudoDownload.environment().get("PATH"));sudoDownload.environment().put("SUDO_UID","1000");
            Process denied=sudoDownload.start();String deniedOutput=new String(denied.getInputStream().readAllBytes());
            check(denied.waitFor()!=0&&deniedOutput.contains("sem sudo")&&!deniedOutput.contains(token),"sudo download rejected before staging without exposing credentials");
            // Both a normal account and a direct root session can download.
            Files.writeString(idStub,"#!/bin/sh\nprintf '1000\\n'\n");

            for(boolean directRoot:List.of(false,true))for(boolean fail:List.of(false,true)){
                Files.writeString(idStub,"#!/bin/sh\nprintf '"+(directRoot?"0":"1000")+"\\n'\n");
                ProcessBuilder pb=new ProcessBuilder("/bin/sh",file.toString()).redirectErrorStream(true);
                var env=pb.environment();env.remove("SUDO_UID");env.put("PATH",bin+":"+env.get("PATH"));env.put("FIXTURE_ZIP",archive.toString());env.put("FIXTURE_CONF",folder.resolve("config-path").toString());env.put("FIXTURE_ARGS",folder.resolve("args").toString());env.put("FIXTURE_FAIL",fail?"1":"0");
                Process p=pb.start();String output=new String(p.getInputStream().readAllBytes());int code=p.waitFor();check(fail?code!=0:code==0,"shell completion "+client+" "+output);
                Path config=Path.of(Files.readString(folder.resolve("config-path")));check(!Files.exists(config),"credential file removed");
                check(!Files.readString(folder.resolve("args")).contains(token),"bearer absent from downloader argv");
                check(!output.contains(token),"bearer absent from output");check(output.contains("sudo /bin/sh")==!fail,"installation instruction only after successful integrity check");
                try(var paths=Files.walk(config.getParent())){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
            }
        }
        System.out.println("InstallCommandTest PASS "+checks+" checks: shell execution, TLS, private config, no secret argv, failure cleanup, no automatic sudo.");
    }
}
