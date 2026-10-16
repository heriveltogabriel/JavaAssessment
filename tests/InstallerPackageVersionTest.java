package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.*;

/** Local ZIP fixtures only: no HTTP, enrollment issuance, installation or agent execution. */
public final class InstallerPackageVersionTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static byte[] utf8(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static Path fixture(Path directory,String metadata)throws IOException {
        Path file=Files.createTempFile(directory,"installer-",".zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file))){
            zip.setComment("public fixture archive");
            for(String name:List.of("install.sh","system-install.sh","bootstrap-config.sh","collector.jar","conf/ca.pem")){
                ZipEntry entry=new ZipEntry(name);entry.setTime(1600000000000L);entry.setComment("public fixture entry");zip.putNextEntry(entry);zip.write(utf8("fixture: "+name+"\n"));zip.closeEntry();
            }
            if(metadata!=null){zip.putNextEntry(new ZipEntry("DEPENDENCIES.json"));zip.write(utf8(metadata));zip.closeEntry();}
        }
        return file;
    }
    private static String metadata(Object version){return Json.stringify(Collections.singletonMap("version",version));}
    private static Map<String,byte[]> entries(byte[] bytes)throws IOException {
        Map<String,byte[]> result=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null)result.put(e.getName(),zip.readAllBytes());}
        return result;
    }
    private static void rejected(Path directory,String metadata,String reason)throws Exception {
        Path file=fixture(directory,metadata);boolean failed=false;
        try{InstallerPackage installer=new InstallerPackage(file.toFile());installer.close();}catch(IOException expected){failed=true;}
        check(failed,reason);
    }
    private static void realPackage(Path file)throws Exception {
        InstallerPackage installer=new InstallerPackage(file.toFile());
        try(ZipFile zip=new ZipFile(file.toFile());ZipInputStream jar=new ZipInputStream(zip.getInputStream(zip.getEntry("collector.jar")))){
            String binaryVersion=null;ZipEntry entry;
            while((entry=jar.getNextEntry())!=null)if(entry.getName().equals("META-INF/MANIFEST.MF")){binaryVersion=new Manifest(jar).getMainAttributes().getValue("Implementation-Version");break;}
            check(binaryVersion!=null,"real ZIP binary has implementation version");
            check(binaryVersion.equals(installer.version()),"real ZIP metadata matches the binary manifest: "+file.getFileName());
            check(installer.filename().equals("assessment-collector-"+binaryVersion+".zip"),"real ZIP generic name follows binary version");
            check(installer.filenameForEnvironment("Produção").equals("assessment-producao-"+binaryVersion+".zip"),"real ZIP configured name follows binary version");
            ByteArrayOutputStream configured=new ByteArrayOutputStream();
            installer.write(configured,"https://fixture.invalid","fixture-code","env-fixture");
            Map<String,byte[]> original=entries(Files.readAllBytes(file)),downloaded=entries(configured.toByteArray());
            check(downloaded.size()==original.size()+1,"real ZIP adds only bootstrap");
            for(var member:original.entrySet())check(Arrays.equals(member.getValue(),downloaded.get(member.getKey())),"real ZIP preserves member: "+member.getKey());
        }finally{installer.close();}
    }
    private static void fullUpdateFiles(Path directory)throws Exception {
        List<String> trio=List.of("full-update/bundle.zip","full-update/manifest.json","full-update/manifest.sig");
        for(int mask=0;mask<8;mask++){
            Map<String,byte[]> members=entries(Files.readAllBytes(fixture(directory,metadata("2.0.25"))));
            for(int i=0;i<3;i++)if((mask&(1<<i))!=0)members.put(trio.get(i),utf8("public fixture"));
            Path path=directory.resolve("full-"+mask+".zip");writeEntries(path,members);
            boolean accepted=false;
            try{InstallerPackage installer=new InstallerPackage(path.toFile());installer.close();accepted=true;}catch(IOException expected){}
            check(accepted==(mask==0||mask==7),"only complete full-update trio or legacy ZIP accepted: "+mask);
        }
        for(String unexpected:List.of("full-update/extra.sh","full-update/../install.sh","full-update/bundle.zip/evil")){
            Map<String,byte[]> members=entries(Files.readAllBytes(fixture(directory,metadata("2.0.25"))));members.put(unexpected,utf8("unexpected"));
            Path path=directory.resolve("unsafe.zip");writeEntries(path,members);boolean accepted=false;
            try{InstallerPackage installer=new InstallerPackage(path.toFile());installer.close();accepted=true;}catch(IOException expected){}
            check(!accepted,"unknown full-update entry rejected");
        }
    }
    private static void writeEntries(Path path,Map<String,byte[]> members)throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){for(var member:members.entrySet()){zip.putNextEntry(new ZipEntry(member.getKey()));zip.write(member.getValue());zip.closeEntry();}}
    }
    private static void pathReplacement(Path directory)throws Exception {
        Path original=fixture(directory,metadata("1.4.4")),replacement=fixture(directory,metadata("1.5.0"));
        byte[] originalBytes=Files.readAllBytes(original),replacementBytes=Files.readAllBytes(replacement);
        InstallerPackage opened=new InstallerPackage(original.toFile());
        try{
            Files.move(replacement,original,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            check(opened.filename().equals("assessment-collector-1.4.4.zip"),"open archive retains its version after pathname replacement");
            ByteArrayOutputStream streamed=new ByteArrayOutputStream();opened.writeOriginal(streamed);
            Map<String,byte[]> expected=entries(originalBytes),actual=entries(streamed.toByteArray());
            check(actual.keySet().equals(expected.keySet()),"generic stream has original entries and no bootstrap");
            for(String name:expected.keySet())check(Arrays.equals(expected.get(name),actual.get(name)),"generic stream retains original bytes: "+name);
            check(Arrays.equals(Files.readAllBytes(original),replacementBytes),"streaming does not rewrite replacement artifact");
            InstallerPackage current=new InstallerPackage(original.toFile());try{check(current.version().equals("1.5.0"),"new instance observes replaced package");}finally{current.close();}
            Path copy=Files.createTempFile(directory,"streamed-",".zip");Files.write(copy,streamed.toByteArray());
            try(ZipFile zip=new ZipFile(copy.toFile())){
                check("public fixture archive".equals(zip.getComment()),"generic copy preserves archive comment");
                check("public fixture entry".equals(zip.getEntry("install.sh").getComment()),"generic copy preserves entry comment");
                check(zip.getEntry("install.sh").getTime()==1600000000000L,"generic copy preserves entry timestamp");
            }
        }finally{opened.close();}
    }
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("installer-version-test-");
        try{
            for(String version:List.of("1.4.4","1.5.0","2.0.0-rc.1")){
                Path file=fixture(directory,metadata(version));byte[] original=Files.readAllBytes(file);
                InstallerPackage installer=new InstallerPackage(file.toFile());
                try{
                    check(version.equals(installer.version()),"metadata version is authoritative");
                    check(installer.filename().equals("assessment-collector-"+version+".zip"),"generic versioned name");
                    check(installer.filenameForEnvironment("  HM — São Paulo / 2026  ").equals("assessment-hm-sao-paulo-2026-"+version+".zip"),"accented environment filename");
                    check(installer.filenameForEnvironment(" ../ \" ' $() \r\n").equals("assessment-ambiente-"+version+".zip"),"unsafe empty name has safe fallback");
                    check(installer.filenameForEnvironment(null).equals("assessment-ambiente-"+version+".zip"),"null environment fallback");
                    check(installer.filenameForEnvironment("Renamed").equals("assessment-renamed-"+version+".zip"),"current name is used after rename");
                    ByteArrayOutputStream configured=new ByteArrayOutputStream();installer.write(configured,"https://fixture.invalid","fixture-enrollment-code","env-fixture");
                    Map<String,byte[]> source=entries(original),output=entries(configured.toByteArray());
                    check(output.size()==source.size()+1,"configured package adds bootstrap only");
                    for(Map.Entry<String,byte[]> entry:source.entrySet())check(Arrays.equals(entry.getValue(),output.get(entry.getKey())),"configured copy preserves "+entry.getKey());
                    check(new String(output.get("conf/bootstrap.properties"),StandardCharsets.UTF_8).equals("serverUrl=https://fixture.invalid\nenrollmentToken=fixture-enrollment-code\nenvironmentId=env-fixture\n"),"configured bootstrap content preserved");
                    check(!source.containsKey("conf/bootstrap.properties"),"shared generic ZIP has no bootstrap credential");
                    check(Arrays.equals(MessageDigest.getInstance("SHA-256").digest(original),MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),"shared ZIP remains byte-for-byte unchanged");
                }finally{installer.close();}
            }
            rejected(directory,null,"missing metadata rejected");
            rejected(directory,"{}","missing version rejected");
            rejected(directory,"[]","non-object metadata rejected");
            rejected(directory,"{invalid}","malformed JSON rejected");
            rejected(directory,"{\"version\":\"1.4.4\",\"version\":\"1.5.0\"}","ambiguous duplicate versions rejected");
            for(Object version:Arrays.asList(null,15,true,List.of("1.5.0"),"","1","latest"," 1.5.0","1.5.0 ","1.5.0/other","1.5.0\\other","1.5.0\r\nInjected: value","1.5.0\".zip","1.5.0-","1.5.0+build","1.５.0","1."+"2".repeat(64)))rejected(directory,metadata(version),"invalid version rejected");
            rejected(directory,"{\"version\":\"1.5.0\",\"padding\":\""+"x".repeat(16384)+"\"}","oversized compressed metadata rejected");
            pathReplacement(directory);
            fullUpdateFiles(directory);
            for(String arg:args)realPackage(Path.of(arg));
            System.out.println("INSTALLER_PACKAGE_VERSION_TESTS=PASS checks="+checks+" realPackages="+args.length);
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
