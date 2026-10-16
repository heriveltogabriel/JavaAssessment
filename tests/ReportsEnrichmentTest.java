package assessment;

import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Associations and filters must enrich reports without inventing dependencies. */
public final class ReportsEnrichmentTest {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    static Map<String,Object> query(AssessmentServer app,String... values){var f=new LinkedHashMap<String,String>();for(int i=0;i<values.length;i+=2)f.put(values[i],values[i+1]);return ReportsTest.get(app,f);}
    static String value(Map<String,Object> result,String key){return String.valueOf(ReportsTest.rows(result).get(0).get(key));}
    static long records(Map<String,Object> result){return ReportsTest.count(result,"records");}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("reports-enrichment-");Files.createDirectories(root.resolve("conf"));
        Files.writeString(root.resolve("conf/server.properties"),"adminSalt=fixture-salt\nadminPasswordSha256="+InventoryStore.hash("fixture-saltfixture-password")+"\nseedLegacyHost=false\nhostId=unused\nagentToken=unused\n");
        try(var app=new AssessmentServer(root.toFile())){
            ReportsTest.seed(app);
            app.store.putInventory("runtimes","host-0",List.of(map("hostId","host-0","id","j17","version","17.0.12","javaHome","/opt/jdk17"),map("hostId","host-0","id","j11","version","11.0.25","javaHome","/opt/jdk11")));
            app.store.putInventory("servers","host-0",List.of(map("hostId","host-0","id","wls","name","AdminServer","type","WebLogic","version","14.1.2.0","javaHome","/opt/jdk17/","pid",101,"path","/opt/wls/domain"),map("hostId","host-0","id","tc","name","Tomcat","type","Tomcat","version","10.1","javaHome","/opt/jdk11","pid",102)));
            var orders=map("hostId","host-0","id","orders","name","java:/OrdersDS","driverName","org.postgresql.Driver","driverVersion","42.7","databaseType","PostgreSQL","resourceType","javax.sql.DataSource","state","CONFIGURED","driverReference","pg-main","configuredMaxPoolSize",30,"associations",List.of(map("pid",101,"serverName","AdminServer","applicationName","Pedidos"),map("pid",103,"serverName","Other","applicationName","Shared")),"url","jdbc:secret","password","hidden-secret");
            var billing=map("hostId","host-0","id","billing","name","BillingDS","driverName","MySQL","driverClass","com.mysql.cj.jdbc.Driver","driverVersion","8.4","pid",102,"serverName","Tomcat","databaseType","MySQL","applicationName","Billing");
            var orphan=map("hostId","host-0","id","orphan","name","OrphanDS","driverName","Oracle","driverVersion","23");
            var wrong=map("hostId","host-0","id","wrong","name","WrongPid","driverName","Unknown","pid",999,"serverName","AdminServer");
            app.store.putInventory("datasources","host-0",List.of(orders,billing,orphan,wrong));
            app.store.putInventory("datasources","host-1",List.of());
            for(String model:List.of("machines","java","servers","jdbc","environments")){
                var q=query(app,"model",model,"machine","app-00");
                check(((List<?>)q.get("columns")).stream().anyMatch(c->((Map<?,?>)c).get("key").equals("jdbc")),"default JDBC "+model);
                check(!String.valueOf(q).contains("hidden-secret")&&!String.valueOf(q).contains("jdbc:secret"),"no secrets "+model);
            }
            var wls=query(app,"model","servers","machine","app-00","serverType","WebLogic");
            check(value(wls,"ip").equals("10.0.0.1"),"server IP");check(value(wls,"javaVersion").equals("17.0.12"),"server Java matched by normalized home");
            check(value(wls,"jdbc").contains("java:/OrdersDS — org.postgresql.Driver · 42.7"),"array association + paired JDBC");
            check(!value(wls,"jdbc").contains("BillingDS")&&!value(wls,"jdbc").contains("OrphanDS")&&!value(wls,"jdbc").contains("WrongPid"),"no same-host or same-name wrong-PID inference");
            check(records(query(app,"model","servers","machine","app-00","serverType","Tomcat","driverClass","org.postgresql.Driver"))==0,"server JDBC filter requires real association");
            check(records(query(app,"model","servers","machine","app-00","serverType","Tomcat","javaVersion","17"))==0,"server Java filter is associated runtime");
            check(records(query(app,"model","servers","machine","app-00","serverType","Tomcat","javaVersion","11"))==1,"correct Java version filter");
            var hostJdbc=query(app,"model","servers","machine","app-00","serverType","WebLogic","columns","machine,hostJdbc,path,pid");check(value(hostJdbc,"hostJdbc").contains("OrphanDS")&&value(hostJdbc,"path").equals("/opt/wls/domain")&&value(hostJdbc,"pid").equals("101"),"optional machine JDBC/path/PID");
            var detail=query(app,"model","jdbc","machine","app-00","datasource","java:/Orders","databaseType","postgres","driverClass","org.postgresql","driverReference","pg-main","serverName","Other","applicationName","Shared","resourceType","DataSource","datasourceState","CONFIGURED");
            check(records(detail)==1,"all new filters on same datasource with array associations");check(value(detail,"driverClass").equals("org.postgresql.Driver"),"legacy driverName class fallback");
            check(value(detail,"serverName").contains("Other")&&value(detail,"applicationName").contains("Pedidos"),"all associations retained");
            check(records(query(app,"model","machines","machine","app-00","databaseType","PostgreSQL","driverVersion","8.4"))==0,"database/driver version cannot cross datasources");
            check(records(query(app,"model","machines","jdbcPresence","missing"))==1,"missing JDBC hosts");
            check(records(query(app,"model","jdbc","jdbcPresence","missing"))==0,"missing JDBC has no datasource rows");
            check(records(query(app,"model","java","machine","app-00","jdbcPresence","present"))==2,"JDBC for every runtime on host");
            var java=query(app,"model","java","machine","app-00");check(value(java,"jdbc").contains("OrphanDS")&&String.valueOf(java.get("context")).contains("não confirma uso"),"Java host context explicit");
            var env=query(app,"model","environments","machine","app-00");check(value(env,"jdbc").contains("OrdersDS")&&value(env,"jdbc").contains("BillingDS"),"environment paired JDBC summary");
            Map<?,?> suggestions=(Map<?,?>)app.store.readSnapshot(()->new Reports(app.store).options()).get("suggestions");
            check(((List<?>)suggestions.get("applicationName")).contains("Shared")&&((List<?>)suggestions.get("driverClass")).contains("org.postgresql.Driver"),"array/class suggestions");
            check(suggestions.values().stream().allMatch(v->((List<?>)v).size()<=100),"bounded options");
            check(!suggestions.toString().contains("hidden-secret"),"suggestions allowlist");
            check(value(detail,"databaseVersion").equals("Não identificada"),"old snapshot database version remains unknown");
            orders.put("databaseVersion","16.4");orders.put("databaseVersionEvidence","application-reported");orders.put("state","APPLICATION_REPORTED");orders.put("evidenceLevel","application-reported");orders.put("datasourceKey","orders");orders.put("poolName","orders-pool");orders.put("configurationResolution","partial");
            app.store.putInventory("datasources","host-0",List.of(orders,billing,orphan,wrong));
            var enriched=query(app,"model","jdbc","machine","app-00","databaseVersion","16.4");
            check(records(enriched)==1&&value(enriched,"driverVersion").equals("42.7")&&value(enriched,"databaseVersion").equals("16.4"),"driver and database versions separate");
            check(value(enriched,"databaseVersionEvidence").equals("Informado pela aplicação"),"application provenance explicit");
            check(records(query(app,"model","jdbc","databaseVersion","16"))==0,"database version filter exact");
            check(records(query(app,"model","machines","machine","app-00","databaseVersion","16.4","driverVersion","8.4"))==0,"database version and driver same datasource");
            var extras=query(app,"model","jdbc","machine","app-00","datasource","Orders","columns","datasource,datasourceState,datasourceKey,poolName,configurationResolution,databaseHost");
            check(value(extras,"datasourceState").equals("Informado pela aplicação")&&value(extras,"poolName").equals("orders-pool")&&value(extras,"databaseHost").equals("Não identificada"),"optional metadata and honest state");
            check(value(query(app,"model","servers","machine","app-00","serverType","WebLogic"),"jdbc").contains("Banco: PostgreSQL 16.4 (Informado pela aplicação)"),"database version and provenance in shared JDBC projection");
            var scope=new InventoryQueries.Scope();scope.host="host-0";
            Map<String,Object> summary=app.store.readSnapshot(()->new InventoryQueries(app.store).datasourceSummary(scope));
            check(((Number)summary.get("applicationReported")).intValue()==1&&((Number)summary.get("runtime")).intValue()==0,"application reported counted independently");
            var csv=new StringWriter();Reports.csv(csv,enriched);check(csv.toString().contains("Versão do banco")&&csv.toString().contains("16.4")&&csv.toString().contains("42.7")&&csv.toString().contains("Informado pela aplicação"),"CSV same enriched projection");
            var pdf=new ByteArrayOutputStream();ReportPdf.write(pdf,enriched);String pdfText=ReportPdfTest.text(pdf.toString(StandardCharsets.ISO_8859_1));check(pdfText.contains("16.4")&&pdfText.contains("42.7")&&pdfText.contains("Informado pela aplicação"),"PDF same enriched projection");
            check(((List<?>)((Map<?,?>)app.store.readSnapshot(()->new Reports(app.store).options()).get("suggestions")).get("databaseVersion")).contains("16.4"),"database version suggestions");
            orders.put("associations",List.of("malformed",map("serverName",map("password","nested-secret"))));app.store.putInventory("datasources","host-0",List.of(orders));
            check(!String.valueOf(query(app,"model","jdbc","machine","app-00")).contains("nested-secret"),"malformed association scalars suppressed");
            check(!String.valueOf(app.store.readSnapshot(()->new Reports(app.store).options())).contains("nested-secret"),"malformed suggestions safe");
            System.out.println("ReportsEnrichmentTest PASS "+checks+" checks: JDBC defaults, server Java/IP, associations, filters, options and unknown metadata.");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
