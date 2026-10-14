package assessment;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.Manifest;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

/** Actual agent ZIP/binary and HTTP/SQLite version checks. No install and no remote host. */
public final class UnifiedReleaseHttpTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static Map<String,Object> parsed(String text){return (Map<String,Object>)Json.parse(text);}
    static Map<String,byte[]> entries(byte[] data)throws IOException{
        Map<String,byte[]> result=new LinkedHashMap<>();try(var zip=new ZipInputStream(new ByteArrayInputStream(data))){ZipEntry item;while((item=zip.getNextEntry())!=null)result.put(item.getName(),zip.readAllBytes());}return result;
    }
    static void packageVersion(byte[] data,String version,Path temporary)throws Exception {
        Map<String,byte[]> files=entries(data);check(version.equals(parsed(new String(files.get("DEPENDENCIES.json"),"UTF-8")).get("version")),"ZIP metadata matches shared version");
        Map<String,byte[]> jar=entries(files.get("collector.jar"));
        check(version.equals(new Manifest(new ByteArrayInputStream(jar.get("META-INF/MANIFEST.MF"))).getMainAttributes().getValue("Implementation-Version")),"JAR manifest matches shared version");
        for(var entry:jar.entrySet())if(entry.getKey().endsWith(".class")){
            byte[] b=entry.getValue();check(((b[6]&255)*256+(b[7]&255))==50,"agent retains Java6 bytecode");check(!entry.getKey().contains("Test"),"product classes only");
        }
        Path directory=Files.createTempDirectory(temporary,"binary-");List<URL> urls=new ArrayList<>();
        for(var entry:files.entrySet())if(entry.getKey().equals("collector.jar")||entry.getKey().startsWith("lib/")&&entry.getKey().endsWith(".jar")){
            Path path=directory.resolve(Path.of(entry.getKey()).getFileName());Files.write(path,entry.getValue());urls.add(path.toUri().toURL());
        }
        try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())){
            Class<?> transport=Class.forName("assessment.AgentTransport",false,loader);var field=transport.getDeclaredField("VERSION");field.setAccessible(true);
            check(version.equals(field.get(null)),"actual compiled AgentTransport.VERSION matches shared version");
        }
        if(files.containsKey("SHA256SUMS"))for(String line:new String(files.get("SHA256SUMS"),"UTF-8").split("\n"))if(!line.isBlank()){
            String[] parts=line.split("  ",2);check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(files.get(parts[1]))).equals(parts[0]),"package manifest checksum");
        }
    }
    public static void main(String[] args)throws Exception{
        String expected=args[1];check(expected.equals(DashboardVersion.VERSION),"compiled dashboard equals release");check(expected.matches("[0-9]+\\.[0-9]+\\.[0-9]+"),"no beta suffix");
        byte[] generic=Files.readAllBytes(Path.of(args[0]));Path root=Files.createTempDirectory("assessment-unified-release-");
        Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));Files.write(root.resolve("dist/assessment-collector.zip"),generic);
        Properties p=new Properties();p.setProperty("adminSalt","fixture-salt");p.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));
        p.setProperty("agentToken","fixture-token");p.setProperty("hostId","fixture-host");p.setProperty("seedLegacyHost","false");p.setProperty("publicUrl","https://fixture.invalid");
        try(var stream=Files.newOutputStream(root.resolve("conf/server.properties"))){p.store(stream,"Isolated unified release test");}
        try(AssessmentServer app=new AssessmentServer(root.toFile())){
            HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);ExecutorService pool=Executors.newFixedThreadPool(2);
            http.createContext("/",app);http.setExecutor(pool);http.start();String base="http://127.0.0.1:"+http.getAddress().getPort();HttpClient client=HttpClient.newHttpClient();
            try{
                packageVersion(generic,expected,root);
                var health=client.send(HttpRequest.newBuilder(URI.create(base+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString());check(expected.equals(parsed(health.body()).get("version")),"HTTP health equals shared version");
                var login=TestAccounts.login(base);
                check(login.statusCode()==200,"login");String cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0],csrf=(String)parsed(login.body()).get("csrfToken");
                var info=client.send(HttpRequest.newBuilder(URI.create(base+"/api/collector/info")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
                check(info.statusCode()==200&&expected.equals(parsed(info.body()).get("version")),"authenticated download info equals dashboard");
                check(("assessment-collector-"+expected+".zip").equals(parsed(info.body()).get("filename")),"generic ZIP name matches shared version");
                var download=client.send(HttpRequest.newBuilder(URI.create(base+"/api/collector.zip")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                check(download.statusCode()==200&&download.headers().firstValue("Content-Disposition").orElse("").contains("assessment-collector-"+expected+".zip"),"generic HTTP filename matches version");
                var servedFiles=entries(download.body());var originalFiles=entries(generic);
                check(servedFiles.keySet().equals(originalFiles.keySet()),"generic download keeps all verified entries");
                for(String name:originalFiles.keySet())check(Arrays.equals(servedFiles.get(name),originalFiles.get(name)),"generic download keeps verified entry "+name);
                var environment=client.send(HttpRequest.newBuilder(URI.create(base+"/api/environments")).header("Cookie",cookie).header("X-CSRF-Token",csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("name","Homologação")))).build(),HttpResponse.BodyHandlers.ofString());
                check(environment.statusCode()==201,"create fixture environment");String id=(String)parsed(environment.body()).get("id");
                var configured=client.send(HttpRequest.newBuilder(URI.create(base+"/api/environments/package")).header("Cookie",cookie).header("X-CSRF-Token",csrf).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(map("environmentId",id,"autoCollect",false)))).build(),HttpResponse.BodyHandlers.ofByteArray());
                check(configured.statusCode()==200&&configured.headers().firstValue("Content-Disposition").orElse("").contains("assessment-homologacao-"+expected+".zip"),"environment ZIP filename matches shared version");
                packageVersion(configured.body(),expected,root);check(entries(configured.body()).containsKey("conf/bootstrap.properties"),"configured ZIP keeps enrollment instructions");
                check(!entries(generic).containsKey("conf/bootstrap.properties"),"generic ZIP remains credential-free");
                System.out.println("UnifiedReleaseHttpTest PASS: dashboard, actual agent binary, metadata, manifest and authenticated generic/environment downloads all "+expected+".");
            }finally{http.stop(0);pool.shutdownNow();}
        }finally{try(var paths=Files.walk(root)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
