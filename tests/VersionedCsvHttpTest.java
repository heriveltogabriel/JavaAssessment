package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;
import static assessment.BetaServerTest.*;

/** Isolated HTTP/SQLite regressions. No installed dashboard or agent is contacted. */
public final class VersionedCsvHttpTest {
    static void packageZip(String version)throws Exception{
        try(var zip=new ZipOutputStream(Files.newOutputStream(root.resolve("dist/assessment-collector.zip")))){
            for(String name:List.of("install.sh","system-install.sh","bootstrap-config.sh","collector.jar","conf/ca.pem","DEPENDENCIES.json")){
                zip.putNextEntry(new ZipEntry(name));zip.write((name.equals("DEPENDENCIES.json")?Json.stringify(map("version",version)):"fixture").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
    }
    static List<Map<String,String>> csv(byte[] bytes){
        String text=new String(bytes,StandardCharsets.UTF_8);check(text.startsWith("\ufeff"),"UTF-8 BOM");text=text.substring(1);
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(c=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}
            else if(!quoted&&c==','){row.add(cell.toString());cell.setLength(0);}
            else if(!quoted&&c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n'){row.add(cell.toString());rows.add(row);row=new ArrayList<>();cell.setLength(0);i++;}
            else cell.append(c);
        }
        check(!quoted&&row.isEmpty()&&cell.length()==0,"complete quoted CSV rows with CRLF");check(!rows.isEmpty(),"header present");
        List<String> header=rows.remove(0);List<Map<String,String>> result=new ArrayList<>();
        for(List<String> values:rows){check(values.size()==header.size(),"CSV column count");Map<String,String> record=new LinkedHashMap<>();for(int i=0;i<header.size();i++)record.put(header.get(i),values.get(i));result.add(record);}
        return result;
    }
    static List<Map<String,String>> exported(String query)throws Exception{
        var response=raw("GET","/api/export.csv"+query,null,true,null);check(response.statusCode()==200,"CSV HTTP status");
        check(response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"),"CSV MIME");
        check(response.headers().firstValue("Content-Disposition").orElse("").matches("attachment; filename=\"assessment-inventory-[a-z0-9-]+-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.csv\""),"safe CSV filename");
        String body=new String(response.body(),StandardCharsets.UTF_8);for(String forbidden:List.of("fixture-agent-secret","fixture-completion-secret","tokenHash","installationId","enrollmentToken"))check(!body.contains(forbidden),"CSV omits secret field: "+forbidden);
        return csv(response.body());
    }
    static long count(List<Map<String,String>> rows,String kind){return rows.stream().filter(r->kind.equals(r.get("record_type"))).count();}
    static String register(String code,String installation,String environment)throws Exception{
        var identity=ok("POST","/api/agent/register",registration(installation),false,code,200);String id=(String)identity.get("hostId");
        if(!environment.isEmpty())ok("PUT","/api/hosts/environment",map("hostId",id,"environmentId",environment),true,null,200);
        return id;
    }
    public static void main(String[] args)throws Exception{
        root=Files.createTempDirectory("versioned-csv-http-");Files.createDirectories(root.resolve("conf"));Files.createDirectories(root.resolve("dist"));
        Properties c=new Properties();c.setProperty("seedLegacyHost","false");c.setProperty("adminSalt","fixture-salt");c.setProperty("adminPasswordSha256",InventoryStore.hash("fixture-saltfixture-password"));c.setProperty("hostId","unused-fixture");c.setProperty("agentToken","unused-fixture");c.setProperty("publicUrl","https://fixture.invalid");try(var out=Files.newOutputStream(root.resolve("conf/server.properties"))){c.store(out,"isolated");}
        packageZip("1.4.4");
        try{
            start();for(String path:List.of("/api/collector/info","/api/collector.zip","/api/export.csv"))check(raw("GET",path,null,false,null).statusCode()==401,"login required: "+path);
            String env=(String)ok("POST","/api/environments",map("name","Produção, São Paulo"),true,null,201).get("id");
            String other=(String)ok("POST","/api/environments",map("name","Homologação"),true,null,201).get("id");
            String empty=(String)ok("POST","/api/environments",map("name","Vazio"),true,null,201).get("id");
            var info=ok("GET","/api/collector/info",null,true,null,200);check("1.4.4".equals(info.get("version"))&&"assessment-collector-1.4.4.zip".equals(info.get("filename")),"actual generic version");
            info=ok("GET","/api/collector/info?environmentId="+env,null,true,null,200);check("assessment-producao-sao-paulo-1.4.4.zip".equals(info.get("filename")),"versioned environment slug");
            var download=raw("GET","/api/collector.zip",null,true,null);check(download.headers().firstValue("Content-Disposition").orElse("").contains("assessment-collector-1.4.4.zip"),"generic download header");
            download=raw("POST","/api/environments/package",map("environmentId",env,"autoCollect",false),true,null);check(download.statusCode()==200&&download.headers().firstValue("Content-Disposition").orElse("").contains("assessment-producao-sao-paulo-1.4.4.zip"),"configured download header");
            boolean bootstrap=false;try(var zip=new ZipInputStream(new ByteArrayInputStream(download.body()))){ZipEntry e;while((e=zip.getNextEntry())!=null)if(e.getName().equals("conf/bootstrap.properties")){String content=new String(zip.readAllBytes(),StandardCharsets.UTF_8);bootstrap=content.contains("environmentId="+env)&&content.contains("enrollmentToken=");}}check(bootstrap,"configured ZIP preserves bootstrap");
            packageZip("1.5.0");check("assessment-collector-1.5.0.zip".equals(ok("GET","/api/collector/info",null,true,null,200).get("filename")),"metadata follows changed served ZIP");
            check(raw("GET","/api/collector/info?environmentId=missing",null,true,null).statusCode()==404,"unknown environment rejected");
            String code=(String)ok("POST","/api/enrollments",map("label","CSV fixture","maxUses",5,"autoCollect",false),true,null,201).get("enrollmentToken");
            String h=register(code,"csv-installation-001",env),h2=register(code,"csv-installation-002",other),hu=register(code,"csv-installation-003","");
            app.store.transaction(()->{
                List<Object> items=new ArrayList<>();for(int i=0;i<201;i++)items.add(map("id","java-"+i,"hostId",h,"name",i==0?"Java, \"produção\"\nsegunda linha":"Java "+i,"kind","JDK","version","17.0.1","javaHome","/opt/java/"+i));
                app.store.putInventory("runtimes",h,items);app.store.putInventory("runtimes",h2,List.of(map("id","other","hostId",h2,"name","Other","kind","JDK","version","21")));app.store.putInventory("runtimes",hu,List.of(map("id","unassigned","hostId",hu,"name","Unassigned","kind","JDK","version","8")));
                var host=app.store.get("hosts",h);host.put("agentToken","fixture-agent-secret");host.put("uninstall",map("completionToken","fixture-completion-secret"));app.store.put("hosts",host);return null;
            });
            var rows=exported("");check(count(rows,"hosts")==3&&count(rows,"runtimes")==203,"all rows across pages");
            rows=exported("?environmentId="+env);check(count(rows,"hosts")==1&&count(rows,"runtimes")==201,"selected environment full inventory");check(rows.stream().allMatch(r->h.equals(r.get("host_id"))),"no other environment host");
            check(rows.stream().anyMatch(r->"Java, \"produção\"\nsegunda linha".equals(r.get("name"))),"quoted unicode multiline roundtrip");
            rows=exported("?environmentId=__unassigned__");check(count(rows,"hosts")==1&&count(rows,"runtimes")==1&&rows.stream().allMatch(r->hu.equals(r.get("host_id"))),"unassigned scope");
            rows=exported("?hostId="+h2);check(count(rows,"hosts")==1&&count(rows,"runtimes")==1,"single machine scope");
            rows=exported("?environmentId="+env+"&page=2&pageSize=1&q=no-match&view=runtimes&from=2099-01-01&version=none");check(count(rows,"runtimes")==201,"CSV ignores hidden table filters");
            check(exported("?environmentId="+empty).isEmpty(),"empty scope has valid header");
            check(raw("GET","/api/export.csv?environmentId="+env+"&hostId="+h2,null,true,null).statusCode()==404,"mismatched machine environment denied");
            check(raw("GET","/api/export.csv?environmentId=missing",null,true,null).statusCode()==404,"unknown CSV scope denied");
            check(raw("POST","/api/export.csv",map(),true,null).statusCode()==405,"CSV read-only");
            check(ok("GET","/api/export?environmentId="+env,null,true,null,200).containsKey("runtimes"),"JSON API compatibility");
            long issued=app.store.scalar("SELECT count(*) FROM enrollments");packageZip("bad\r\nversion");check(raw("GET","/api/collector/info",null,true,null).statusCode()==503,"bad metadata clear availability error");check(raw("POST","/api/environments/package",map("environmentId",env),true,null).statusCode()==503,"invalid version refuses configured package");check(app.store.scalar("SELECT count(*) FROM enrollments")==issued,"invalid package creates no enrollment");
            try(var files=Files.list(root.resolve("data"))){check(files.noneMatch(p->p.getFileName().toString().startsWith("export-")),"temporary exports cleaned after success and error");}
            System.out.println("VersionedCsvHttpTest PASS: package versions and headers, bootstrap, authentication, full CSV scope/pagination, escaping, redaction, empty scope, JSON compatibility, metadata errors and cleanup.");
        }finally{stop();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
