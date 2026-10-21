package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

public final class InventoryArchiveTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    static Map<String,String> entries(byte[] archive)throws Exception{
        Map<String,String> result=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)){
            ZipEntry e;while((e=zip.getNextEntry())!=null){check(result.put(e.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8))==null,"unique ZIP entries");}
        }return result;
    }
    static void seed(InventoryStore store){store.transaction(()->{
        store.put("environments",map("id","env-a","name","Produção","description","ENV_DESCRIPTION_SECRET","settings",map("password","ENV_PASSWORD")));
        store.put("hosts",map("id","host-a","environmentId","env-a","hostname","ol07","distribution","Oracle Linux 7","ipAddresses",List.of("10.0.0.2"),"agentToken","HOST_TOKEN","collectorVersion","2.1.14","lastSeen","2026-10-20T10:00:00Z","kernelVersion","3.10","coverage",map("status","partial","warnings",List.of("Metadados limitados; password=NOTE_SECRET"),"stats",map("filesVisited",50000,"durationMillis",12345,"secret","STATS_SECRET")),"disks",List.of(map("mount","/u01","totalBytes",123456,"password","DISK_SECRET")),"settings",map("secret","HOST_SETTING_SECRET")));
        store.put("identities",map("hostId","host-a","agentToken","IDENTITY_SECRET","status","active"));
        store.put("jobs",map("id","job-a","hostId","host-a","status","SUCCEEDED","summary","JOB_SECRET"));
        var candidate=map("path","/domain/lib/ojdbc6.jar","version","11.2.0.3.0","versionSource","Manifesto","family","Oracle","open",true,"onClasspath",true,"loaded",false,"password","CANDIDATE_PASSWORD");
        var decision=map("driverResolution","INCOMPLETE","driverCandidates",List.of(candidate),"driverResolutionReason","Classpath truncado","token","DECISION_TOKEN");
        for(String kind:InventoryArchive.COLLECTIONS.subList(2,8)){
            var row=map("id",kind+"-a","hostId","host-a","name","=JDBC de \"Produção\"\nSegunda linha","version","17.0.1","path","/opt/java","driverName","oracle.jdbc.OracleDriver","driverCandidates",List.of(candidate),"driverResolution","INCOMPLETE","databaseHost","db.internal","databaseEndpoints",List.of(map("host","db.internal","port",1521,"service","sales","password","DB_PASSWORD")),"associations",List.of(map("applicationId","applications-a","serverId","servers-a","pid",42,"driverResult",decision,"secret","ASSOC_SECRET")),"commandLine","COMMAND_SECRET","environment",map("PASSWORD","ENV_SECRET"),"jdbcUrl","jdbc:oracle:thin:user/URL_SECRET@db:1521/sales","evidence","configuration; password=TEXT_SECRET; token=TEXT_TOKEN; https://user:URL_AUTH@db/path", "unknownFutureSecret","FUTURE_SECRET");
            store.putInventory(kind,"host-a",new ArrayList<>(List.of(row)));
        }return null;
    });}
    static byte[] archive(InventoryStore store)throws Exception{return archive(store,"json");}
    static byte[] archive(InventoryStore store,String format)throws Exception{var out=new ByteArrayOutputStream();InventoryArchive.write(out,store,"Cliente A",format);return out.toByteArray();}
    /** Minimal ';' CSV reader for the map: quoted cells, doubled quotes, CRLF rows. */
    static List<List<String>> rows(String text){
        List<List<String>> out=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean q=false;
        for(int i=text.startsWith("﻿")?1:0;i<text.length();i++){char c=text.charAt(i);
            if(q){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else q=false;}else cell.append(c);}
            else if(c=='"')q=true;else if(c==';'){row.add(cell.toString());cell.setLength(0);}
            else if(c=='\r'){}else if(c=='\n'){row.add(cell.toString());cell.setLength(0);out.add(row);row=new ArrayList<>();}else cell.append(c);
        }return out;
    }
    static void mapa(InventoryStore store)throws Exception{
        store.transaction(()->{
            store.putInventory("processes","host-a",new ArrayList<>(List.of(map("id","proc-wl","hostId","host-a","pid",22195,"javaHome","/u01/jdk8","serverType","WebLogic"),map("id","proc-wl2","hostId","host-a","pid",22215,"javaHome","/u01/jdk8","serverType","WebLogic"))));
            store.putInventory("runtimes","host-a",new ArrayList<>(List.of(map("id","rt","hostId","host-a","javaHome","/u01/jdk8","version","1.8.0_202"))));
            store.putInventory("servers","host-a",new ArrayList<>(List.of(map("id","srv-wl","hostId","host-a","type","WebLogic","version","12.2.1.4.0","name","AdminServer","domainName","dsv04_domain","pid",22195))));
            store.putInventory("applications","host-a",new ArrayList<>(List.of(map("id","app-x","hostId","host-a","name","FORMACAO_PERIODOS_SE.jar","applicationType","Spring Boot","pid",22215))));
            var identified=map("driverResolution","IDENTIFIED","driverArtifact","/u01/wl/oracle_common/modules/oracle.jdbc/ojdbc8.jar","driverVersion","19.3.0.0.0");
            var appOnly=map("driverResolution","INCOMPLETE","driverResolutionReason","Classpath truncado","driverCandidates",List.of(map("path","/app/WEB-INF/lib/ojdbc11.jar","version","23.4","onClasspath",false)));
            store.putInventory("datasources","host-a",new ArrayList<>(List.of(
                map("id","ds-gercor","hostId","host-a","name","jdbc/GERCOR","jndiName","jdbc/GERCOR","databaseType","Oracle","databaseName","dPDBrj2","driverName","oracle.jdbc.OracleDriver","configurationSource","/u01/domains/dsv04_domain/config/jdbc/gercor-jdbc.xml","password","MAP_PASSWORD",
                    "databaseEndpoints",List.of(map("host","E1113-dscan10.exadata.prevnet","port",1521),map("host","10.1.2.3","port",1522)),
                    "associations",List.of(map("pid",22195,"serverId","srv-wl","serverName","AdminServer","driverResult",identified),map("pid",22215,"applicationId","app-x","applicationName","FORMACAO_PERIODOS_SE.jar","driverResult",appOnly))),
                map("id","ds-jndi","hostId","host-a","name","cache-consultas-jndi","jndiName","java:/cacheConsultasDS","databaseType","PostgreSQL","jndiResolution","JNDI declarado; datasource não encontrado nesta máquina","pid",22215,"applicationId","app-x")
            )));
            return null;
        });
        var files=entries(archive(store,"mapa"));check(files.keySet().equals(Set.of("mapa-bancos.csv")),"one map file");
        String text=files.get("mapa-bancos.csv");check(text.startsWith("﻿")&&!text.contains("MAP_PASSWORD")&&!text.contains("jdbc:oracle"),"BOM and no secrets/URLs");
        var table=rows(text);var header=table.get(0);check(header.equals(InventoryArchiveMap.COLUMNS),"header");
        java.util.function.BiFunction<List<String>,String,String> col=(r,name)->r.get(header.indexOf(name));
        var gercor=table.stream().filter(r->r.size()==header.size()&&"jdbc/GERCOR".equals(col.apply(r,"datasource"))).toList();
        check(gercor.size()==4,"two endpoints x two processes: "+gercor.size());
        var admin=gercor.stream().filter(r->"22195".equals(col.apply(r,"pid"))&&"1521".equals(col.apply(r,"bancoPorta"))).findFirst().orElseThrow();
        check(col.apply(admin,"servidorTipo").equals("WebLogic")&&col.apply(admin,"dominio").equals("dsv04_domain")&&col.apply(admin,"javaVersao").equals("1.8.0_202"),"server and Java joined: "+admin);
        check(col.apply(admin,"chaveServico").equals("oracle|DPDBRJ2")&&col.apply(admin,"chaveHostPorta").equals("e1113-dscan10.exadata.prevnet:1521")&&col.apply(admin,"hostCurto").equals("e1113-dscan10"),"join keys normalized: "+admin);
        check(col.apply(admin,"driverJar").endsWith("ojdbc8.jar")&&col.apply(admin,"driverVersao").equals("19.3.0.0.0")&&col.apply(admin,"driverStatus").startsWith("Em uso")&&col.apply(admin,"ambiente").equals("Produção")&&col.apply(admin,"levantamento").equals("Cliente A"),"driver, environment and label: "+admin);
        var ip=gercor.stream().filter(r->"1522".equals(col.apply(r,"bancoPorta"))).findFirst().orElseThrow();check(col.apply(ip,"hostCurto").equals("10.1.2.3"),"IP kept whole");
        var app=gercor.stream().filter(r->"22215".equals(col.apply(r,"pid"))).findFirst().orElseThrow();
        check(col.apply(app,"aplicacao").equals("FORMACAO_PERIODOS_SE.jar")&&col.apply(app,"aplicacaoTipo").equals("Spring Boot")&&col.apply(app,"driverJar").isEmpty()&&col.apply(app,"driverStatus").equals("Inspeção incompleta"),"off-classpath candidate never becomes the driver: "+app);
        var jndi=table.stream().filter(r->r.size()==header.size()&&"cache-consultas-jndi".equals(col.apply(r,"datasource"))).toList();
        check(jndi.size()==1&&col.apply(jndi.get(0),"bancoHost").isEmpty()&&col.apply(jndi.get(0),"observacao").contains("JNDI")&&col.apply(jndi.get(0),"bancoTipo").equals("PostgreSQL")&&col.apply(jndi.get(0),"evidenciaBanco").equals("Destino não identificado"),"missing endpoint row kept with reason: "+jndi);
        String[] probable=InventoryArchiveMap.driver(map("driverResolution","INCOMPLETE","driverCandidates",List.of(map("path","/domain/lib/ojdbc6.jar","version","11.2","onClasspath",true))));
        check(probable[0].endsWith("ojdbc6.jar")&&InventoryArchiveMap.statusLabel(probable[2]).contains("provável"),"unique on-classpath candidate labeled probable");
        check(table.stream().allMatch(r->r.size()==header.size()),"rectangular table");
    }
    static Map<String,String> nested(byte[] data)throws Exception{
        Map<String,String> result=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data),StandardCharsets.UTF_8)){ZipEntry e;while((e=zip.getNextEntry())!=null)result.put(e.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));}
        return result;
    }
    static void xlsx(InventoryStore store)throws Exception{
        var out=new ByteArrayOutputStream();InventoryArchive.write(out,store,"Cliente A","xlsx");
        byte[] book=null;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(out.toByteArray()),StandardCharsets.UTF_8)){ZipEntry e=zip.getNextEntry();check(e!=null&&e.getName().equals("inventario.xlsx"),"one workbook entry");book=zip.readAllBytes();check(zip.getNextEntry()==null,"only the workbook");}
        var parts=nested(book);String all=String.join("\n",parts.values());
        check(parts.keySet().containsAll(List.of("[Content_Types].xml","_rels/.rels","xl/workbook.xml","xl/_rels/workbook.xml.rels","xl/styles.xml","xl/worksheets/sheet1.xml","xl/worksheets/sheet10.xml")),"workbook parts: "+parts.keySet());
        for(String sheet:List.of("Resumo","Ambientes","Máquinas","Java","Processos","Servidores","Bibliotecas","Aplicações","Datasources","Mapa de bancos"))check(parts.get("xl/workbook.xml").contains("name=\""+sheet+"\""),"sheet "+sheet);
        for(String secret:List.of("MAP_PASSWORD","CANDIDATE_PASSWORD","DECISION_TOKEN","DB_PASSWORD","ASSOC_SECRET","COMMAND_SECRET","HOST_TOKEN","NOTE_SECRET"))check(!all.contains(secret),"xlsx omitted "+secret);
        check(parts.get("xl/worksheets/sheet10.xml").contains("oracle|DPDBRJ2")&&parts.get("xl/worksheets/sheet10.xml").contains("dsv04_domain"),"database map sheet");
        check(parts.get("xl/worksheets/sheet3.xml").contains("ol07")&&parts.get("xl/worksheets/sheet8.xml").contains("FORMACAO_PERIODOS_SE.jar"),"machine and application sheets");
        check(!all.contains("<f>")&&!all.contains("\u0001"),"no formulas, no invalid XML characters");
        check(InventoryArchiveXlsx.escape("a<b&\u0001").equals("a&lt;b&amp; "),"XML escaping");
        check(InventoryArchiveXlsx.column(0).equals("A")&&InventoryArchiveXlsx.column(25).equals("Z")&&InventoryArchiveXlsx.column(26).equals("AA")&&InventoryArchiveXlsx.column(33).equals("AH"),"column letters");
        check(InventoryArchiveXlsx.cell("driverCandidates",List.of(map("path","/a/ojdbc8.jar","version","19.3","onClasspath",false))).equals("/a/ojdbc8.jar (19.3) [fora do classpath]"),"candidate summary");
        check(InventoryArchiveXlsx.cell("databaseEndpoints",List.of(map("host","db","port",1521,"service","S"))).equals("db:1521/S"),"endpoint summary");
        var sheetTest=new ByteArrayOutputStream();var wb=new InventoryArchiveXlsx(sheetTest,text->{});
        try(var sheet=wb.sheet("T",List.of("x"))){sheet.row(new ArrayList<>(List.of("y".repeat(40_000))));}wb.finish();
        String longCell=nested(sheetTest.toByteArray()).get("xl/worksheets/sheet1.xml");check(longCell.contains(InventoryArchiveXlsx.CUT)&&!longCell.contains("y".repeat(32_001)),"long cell cut with notice");
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("inventory-archive-");
        try(InventoryStore store=new InventoryStore(root.toFile(),map(),"","")){
            var empty=entries(archive(store));check(empty.keySet().equals(Set.of("inventario.json")),"one JSON document");check(((List<?>)obj(obj(Json.parse(empty.get("inventario.json"))).get("data")).get("hosts")).isEmpty(),"empty fleet supported");
            seed(store);var files=entries(archive(store));String all=String.join("\n",files.values());
            for(String secret:List.of("ENV_DESCRIPTION_SECRET","ENV_PASSWORD","HOST_TOKEN","HOST_SETTING_SECRET","IDENTITY_SECRET","JOB_SECRET","CANDIDATE_PASSWORD","DECISION_TOKEN","DB_PASSWORD","ASSOC_SECRET","COMMAND_SECRET","ENV_SECRET","URL_SECRET","TEXT_SECRET","TEXT_TOKEN","URL_AUTH","FUTURE_SECRET","NOTE_SECRET","STATS_SECRET","DISK_SECRET"))check(!all.contains(secret),"omitted "+secret);
            var document=obj(Json.parse(files.get("inventario.json")));var manifest=obj(document.get("metadata"));check(manifest.get("formatVersion").equals(2L),"format is versioned");check(manifest.get("label").equals("Cliente A"),"source label");
            var counts=obj(manifest.get("counts"));for(String kind:InventoryArchive.COLLECTIONS)check(counts.get(kind).equals(1L),"complete count "+kind);
            var ds=obj(((List<?>)obj(document.get("data")).get("datasources")).get(0));
            check(ds.get("hostId").equals("host-a"),"host relationship");var association=obj(((List<?>)ds.get("associations")).get(0));check(association.get("applicationId").equals("applications-a"),"app relationship");
            var decision=obj(association.get("driverResult"));check(decision.get("driverResolution").equals("INCOMPLETE"),"per-PID uncertainty retained");var candidate=obj(((List<?>)decision.get("driverCandidates")).get(0));check(candidate.get("version").equals("11.2.0.3.0")&&candidate.get("path").equals("/domain/lib/ojdbc6.jar"),"driver jar and version retained");check(candidate.get("onClasspath").equals(true),"candidate evidence retained");
            check(all.contains("filesVisited")&&all.contains("50000")&&all.contains("Metadados limitados")&&all.contains("totalBytes"),"coverage and hardware retained");
            check(all.contains("db.internal")&&all.contains("Oracle Linux 7"),"technical infrastructure retained");
            var csv=entries(archive(store,"csv"));check(csv.keySet().equals(Set.of("inventario.csv")),"one CSV file");
            check(csv.get("inventario.csv").contains("\"'=JDBC"),"CSV formula protected");
            mapa(store);
            xlsx(store);
            for(String invalid:List.of("xml","JSON","../csv")){try{InventoryArchive.format(invalid);throw new AssertionError("invalid format accepted");}catch(InventoryArchive.Failure expected){}}
            if(args.length==2&&args[0].equals("--export")){Path dir=Path.of(args[1]);Files.createDirectories(dir);Files.write(dir.resolve("json.zip"),archive(store));Files.write(dir.resolve("csv.zip"),archive(store,"csv"));}
            for(long[] limits:List.of(new long[]{50,100},new long[]{999999,2})){
                for(String format:List.of("json","csv")){try{InventoryArchive.write(new ByteArrayOutputStream(),store,"",format,limits[0],limits[1]);throw new AssertionError("limit accepted");}catch(InventoryArchive.Failure expected){}}
            }
            for(String label:List.of("x".repeat(121),"line\nbreak")){try{InventoryArchive.label(label);throw new AssertionError("invalid label");}catch(InventoryArchive.Failure expected){}}
            for(String secretText:List.of("{\"password\":\"QUOTED_SECRET\"}","Authorization: Basic QUOTED_SECRET","-----BEGIN PRIVATE KEY-----\nQUOTED_SECRET\n-----END PRIVATE KEY-----","username=QUOTED_SECRET"))check(!InventoryArchive.scrub(secretText).contains("QUOTED_SECRET"),"credential text redacted");
            check(!InventoryArchive.scrub("jdbc:oracle:thin:user/ORACLE_SECRET@db:1521/sales").contains("ORACLE_SECRET"),"Oracle URL redacted");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("InventoryArchiveTest passed");
    }
}
