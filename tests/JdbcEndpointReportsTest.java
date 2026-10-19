package assessment;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Exercises multi-address JDBC destinations through actual SQLite reports and both exports. */
public final class JdbcEndpointReportsTest {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    static Map<String,Object> query(AssessmentServer app,String... pairs){var input=new LinkedHashMap<String,String>();input.put("machine","endpoint-host");for(int i=0;i<pairs.length;i+=2)input.put(pairs[i],pairs[i+1]);return ReportsTest.get(app,input);}
    static String cell(Map<String,Object> result,String key){return String.valueOf(ReportsTest.rows(result).get(0).get(key));}
    static long count(Map<String,Object> result){return ReportsTest.count(result,"records");}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("jdbc-endpoint-reports-");Files.createDirectories(root.resolve("conf"));
        Files.writeString(root.resolve("conf/server.properties"),"adminSalt=fixture-salt\nadminPasswordSha256="+InventoryStore.hash("fixture-saltfixture-password")+"\nseedLegacyHost=false\nhostId=unused\nagentToken=unused\n");
        try(var app=new AssessmentServer(root.toFile())){
            app.store.put("environments",map("id","prod","name","Produção"));
            app.store.put("hosts",map("id","h","hostname","endpoint-host","environmentId","prod","ipAddresses",List.of("10.0.0.8")));
            app.store.putInventory("runtimes","h",List.of(map("id","j","hostId","h","version","17","javaHome","/jdk")));
            app.store.putInventory("servers","h",List.of(map("id","s","hostId","h","name","AdminServer","type","WebLogic","pid",25,"javaHome","/jdk"),map("id","other","hostId","h","name","UnrelatedServer","type","Tomcat","pid",26)));
            var rac=map("id","rac","hostId","h","name","OrdersDS","serverName","AdminServer","pid",25,"driverName","Oracle","driverVersion","19.3","driverArtifact","/domain/lib/renamed.jar","driverResolution","IDENTIFIED","driverVersionEvidence","Classe exata no JAR","driverCandidates",List.of(map("path","/domain/lib/renamed.jar","version","19.3","password","SECRET_CANDIDATE")),"databaseHost","rac-a.example","databasePort",1521,"databaseName","orders.example","databaseEndpoints",List.of(map("host","rac-a.example","port",1521,"password","SECRET_ENDPOINT"),map("host","rac-b.example","port",1522),map("host","2001:db8::7","port",1523)),"url","jdbc:oracle:thin:user/SECRET_URL@rac-a.example:1521/orders.example","password","SECRET_PASSWORD");
            var legacy=map("id","legacy","hostId","h","name","BillingDS","databaseHost","legacy.example","databasePort",5432,"databaseName","billing","driverName","PostgreSQL","driverVersion","42.7");
            var absent=map("id","unknown","hostId","h","name","UnknownDS");
            app.store.putInventory("datasources","h",List.of(rac,legacy,absent));
            for(String model:List.of("machines","java","servers","jdbc","environments")){
                var report=query(app,"model",model,"databaseHost","rac-b.example");
                check(count(report)==1,"all models filter by non-primary endpoint: "+model);
                check(cell(report,"databaseHost").contains("rac-b.example"),"default host column: "+model);
                String summary=cell(report,"jdbc");check(summary.contains("renamed.jar")&&summary.contains("Identificado em arquivo"),"driver evidence in all report summaries: "+model);check(summary.contains("OrdersDS")&&summary.contains("rac-a.example:1521")&&summary.contains("rac-b.example:1522")&&summary.contains("[2001:db8::7]:1523")&&summary.contains("orders.example"),"paired tuple keeps all endpoints: "+model);
                check(!summary.contains("BillingDS")&&!String.valueOf(report).contains("SECRET_"),"filtered tuple excludes unrelated data/secrets: "+model);
                check(String.valueOf(report.get("context")).contains("não confirmam conexão ativa"),"honest destination evidence: "+model);
                var csv=new StringWriter();Reports.csv(csv,report);check(csv.toString().contains("rac-b.example")&&csv.toString().contains("Host/IP do banco"),"CSV includes host: "+model);
                var pdf=new ByteArrayOutputStream();ReportPdf.write(pdf,report);String pdfText=ReportPdfTest.text(pdf.toString(StandardCharsets.ISO_8859_1));check(pdfText.contains("rac-b.example")&&pdfText.contains("1522"),"PDF includes destination: "+model);
            }
            check(count(query(app,"model","jdbc","databaseHost","rac-b","databasePort","1522"))==1,"endpoint host/port match");
            check(count(query(app,"model","jdbc","databaseHost","rac-a","databasePort","1522"))==0,"host and port cannot cross endpoints");
            check(count(query(app,"model","jdbc","databaseHost","rac-b","databaseName","billing"))==0,"host and name cannot cross datasources");
            check(count(query(app,"model","jdbc","databasePort","152"))==0,"port exact filter");
            check(count(query(app,"model","jdbc","databaseHost","RAC-B.EXAMPLE","databaseName","orders"))==1,"case-insensitive host and partial name");
            check(count(query(app,"model","jdbc","databaseHost","2001:db8::7"))==1,"IPv6 endpoint filter");
            check(count(query(app,"model","jdbc","databaseHost","rac-%"))==0,"wildcards literal");
            check(count(query(app,"model","jdbc","databaseHost","' OR 1=1 --"))==0,"SQL injection remains literal");
            check(count(query(app,"model","servers","serverType","Tomcat","databaseHost","rac-b"))==0,"server linkage respected");
            var driverReport=query(app,"model","jdbc","datasource","OrdersDS","columns","datasource,driverVersion,driverArtifact,driverResolution,driverVersionEvidence,driverCandidates");
            check(cell(driverReport,"driverArtifact").equals("/domain/lib/renamed.jar")&&cell(driverReport,"driverCandidates").contains("19.3"),"driver columns selectable");
            var driverCsv=new StringWriter();Reports.csv(driverCsv,driverReport);check(driverCsv.toString().contains("renamed.jar")&&!driverCsv.toString().contains("SECRET_"),"driver CSV allowlist");
            var old=query(app,"model","jdbc","databaseHost","legacy","columns","datasource,databaseHost,databasePort,databaseName,databaseDestination");
            check(cell(old,"databaseDestination").equals("legacy.example:5432 / billing"),"old scalar fields supported");
            check(cell(query(app,"model","jdbc","datasource","Unknown","columns","databaseHost,databaseDestination"),"databaseDestination").equals("Não identificada"),"old snapshots remain unknown");
            for(String model:List.of("machines","java","servers","environments")){
                var report=query(app,"model",model,"databaseHost","rac-b","columns","databaseHost,databasePort,databaseName,databaseDestination");
                check(cell(report,"databaseDestination").contains("OrdersDS → rac-a.example:1521, rac-b.example:1522"),"destination optional column retains datasource relationship: "+model);
            }
            var suggestions=(Map<?,?>)app.store.readSnapshot(()->new Reports(app.store).options()).get("suggestions");
            check(((List<?>)suggestions.get("databaseHost")).containsAll(List.of("rac-a.example","rac-b.example","legacy.example","2001:db8::7")),"all endpoint suggestions");
            check(((List<?>)suggestions.get("databasePort")).containsAll(List.of("1521","1522","1523","5432")),"port suggestions");
            var scope=new InventoryQueries.Scope();scope.host="h";StringWriter inventory=new StringWriter();app.store.readSnapshot(()->{CsvExport.write(inventory,app.store,new InventoryQueries(app.store),scope);return null;});
            check(inventory.toString().contains("rac-b.example:1522")&&inventory.toString().contains("[2001:db8::7]:1523")&&inventory.toString().contains("legacy.example:5432 / billing"),"inventory CSV exports all addresses and legacy fields");
            check(!inventory.toString().contains("SECRET_"),"inventory CSV excludes endpoint nested metadata and raw URL");
            rac.put("databaseEndpoints",List.of("bad",7,map("host",map("password","SECRET_NESTED")),map("port",1522)));
            app.store.putInventory("datasources","h",List.of(rac));
            var malformed=query(app,"model","jdbc","columns","databaseHost,databaseDestination");check(cell(malformed,"databaseHost").equals("rac-a.example"),"malformed arrays fall back safely");
            check(!String.valueOf(malformed).contains("SECRET_")&&!String.valueOf(app.store.readSnapshot(()->new Reports(app.store).options())).contains("SECRET_"),"malformed endpoints never serialized");
            // Distinct domains commonly reuse AdminServer; explicit identity wins even over a stale PID/name.
            app.store.putInventory("servers","h",List.of(map("id","domain-a","hostId","h","name","AdminServer","type","WebLogic","pid",7,"path","/domains/a"),map("id","domain-b","hostId","h","name","AdminServer","type","WebLogic","pid",8,"path","/domains/b")));
            app.store.putInventory("applications","h",List.of(map("id","app-a","hostId","h","serverId","domain-a","serverName","AdminServer"),map("id","app-b","hostId","h","serverId","domain-b","serverName","AdminServer"),map("id","app-deleted","hostId","h","serverId","deleted-server","serverName","AdminServer")));
            var serverScope=new InventoryQueries.Scope();serverScope.host="h";serverScope.view="servers";
            var serverView=app.store.readSnapshot(()->new InventoryQueries(app.store).view(serverScope));
            for(Object raw:(List<?>)serverView.get("servers"))check(((Number)((Map<?,?>)raw).get("applicationCount")).intValue()==1,"application counts separate identical server names by explicit ID");
            var domainA=map("id","domain-a-ds","hostId","h","name","CommonDS","serverId","domain-a","serverName","AdminServer","pid",8,"databaseHost","domain-a-db","databasePort",1521);
            var domainB=map("id","domain-b-ds","hostId","h","name","CommonDS","serverName","AdminServer","pid",7,"databaseHost","domain-b-db","databasePort",1521,"associations",List.of(map("serverId","domain-b","serverName","AdminServer","pid",7)));
            var deleted=map("id","deleted-ds","hostId","h","name","DeletedDS","serverId","deleted-server","serverName","AdminServer","pid",7,"databaseHost","deleted-db");
            app.store.putInventory("datasources","h",List.of(domainA,domainB,deleted));
            for(String domain:List.of("a","b")){
                var linked=query(app,"model","servers","databaseHost","domain-"+domain+"-db","columns","name,path,jdbc,databaseHost,databaseDestination");
                check(count(linked)==1&&cell(linked,"path").equals("/domains/"+domain),"same-name AdminServer uses explicit identity: "+domain);
                String foreign="domain-"+(domain.equals("a")?"b":"a")+"-db";
                check(!cell(linked,"jdbc").contains(foreign)&&!cell(linked,"jdbc").contains("deleted-db"),"server destinations do not mix domains: "+domain);
                var csv=new StringWriter();Reports.csv(csv,linked);check(csv.toString().contains("domain-"+domain+"-db")&&!csv.toString().contains(foreign),"CSV preserves domain relationship: "+domain);
                var pdf=new ByteArrayOutputStream();ReportPdf.write(pdf,linked);String text=ReportPdfTest.text(pdf.toString(StandardCharsets.ISO_8859_1));check(text.contains("domain-"+domain+"-db")&&!text.contains(foreign),"PDF preserves domain relationship: "+domain);
            }
            check(count(query(app,"model","servers","databaseHost","deleted-db"))==0,"unknown explicit serverId never falls back to PID/name");
            var finalCsv=new StringWriter();app.store.readSnapshot(()->{CsvExport.write(finalCsv,app.store,new InventoryQueries(app.store),scope);return null;});
            String aLine=finalCsv.toString().lines().filter(line->line.contains("domain-a-ds")).findFirst().orElseThrow();
            String bLine=finalCsv.toString().lines().filter(line->line.contains("domain-b-ds")).findFirst().orElseThrow();
            check(aLine.contains("\"domain-a\"")&&!aLine.contains("domain-b-db")&&bLine.contains("\"domain-b\"")&&!bLine.contains("domain-a-db"),"inventory CSV keeps explicit IDs alongside each destination");
            var spring=map("id","spring-ds","hostId","h","name","SpringLab","driverName","org.h2.Driver","databaseType","H2");
            SpringDataSource.matchPackagedDriver(spring,List.of(map("category","JDBC","path","/app.jar!/BOOT-INF/lib/h2-2.3.232.jar","version","2.3.232")));
            app.store.putInventory("datasources","h",List.of(spring));
            var springReport=query(app,"model","jdbc","columns","datasource,driverArtifact,driverVersion");
            check(cell(springReport,"driverArtifact").equals(spring.get("driverArtifact")),"Spring nested driver path reaches report column");
            var springCsv=new StringWriter();Reports.csv(springCsv,springReport);
            check(springCsv.toString().contains("h2-2.3.232.jar")&&springCsv.toString().contains("2.3.232"),"Spring nested JAR and version exported to report CSV");
            var springPdf=new ByteArrayOutputStream();ReportPdf.write(springPdf,springReport);
            check(ReportPdfTest.text(springPdf.toString(StandardCharsets.ISO_8859_1)).contains("h2-2.3.232.jar"),"Spring nested JAR exported to PDF");
            var springInventory=new StringWriter();app.store.readSnapshot(()->{CsvExport.write(springInventory,app.store,new InventoryQueries(app.store),scope);return null;});
            check(springInventory.toString().contains("h2-2.3.232.jar"),"Spring nested JAR exported to inventory CSV");
            System.out.println("JdbcEndpointReportsTest PASS "+checks+" checks");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
