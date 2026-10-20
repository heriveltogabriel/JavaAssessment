package assessment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Support lifecycle (EOL) from web/lifecycle.json; one classifier shared by SQL filters, counts and item badges. */
final class Lifecycle {
    static final String OUT="fora",EXTENDED="estendido",ENDING="proximo",SUPPORTED="suportado",UNKNOWN="desconhecido";
    static final List<String> STATUSES=List.of(OUT,EXTENDED,ENDING,SUPPORTED,UNKNOWN);
    static final Set<String> KINDS=Set.of("runtimes","servers","applications","libraries","datasources");
    /** SQL expression for the row status; bind the reference date (yyyy-MM-dd) once where it appears. */
    static final String SQL="lifecycle_status(i.kind,CASE WHEN i.kind='datasources' THEN coalesce(json_extract(i.payload,'$.driverName'),'') ELSE i.type END,CASE WHEN i.kind='datasources' THEN CASE WHEN upper(coalesce(json_extract(i.payload,'$.driverResolution'),'')) IN ('AMBIGUOUS','INCOMPLETE','VARIES','NOT_FOUND') THEN '' ELSE coalesce(json_extract(i.payload,'$.driverVersion'),'') END ELSE i.version END,i.major,coalesce(json_extract(i.payload,'$.applicationType'),''),coalesce(json_extract(i.payload,'$.frameworkVersion'),''),coalesce(json_extract(i.payload,'$.name'),''),?)";
    static final String DRIVER_UNRESOLVED="(upper(coalesce(json_extract(i.payload,'$.driverResolution'),'')) IN ('NOT_FOUND','INCOMPLETE','AMBIGUOUS') OR (coalesce(json_extract(i.payload,'$.driverVersion'),'')='' AND upper(coalesce(json_extract(i.payload,'$.driverResolution'),'')) NOT IN ('PROBABLE','VARIES')))";
    static final String REPLACED="json_extract(i.payload,'$.runtimeReplacedOnDisk')=1";
    private static volatile Map<String,Object> catalog=map("produtos",List.of());

    private Lifecycle(){}
    static void load(File file){
        try{catalog=parse(Files.readString(file.toPath(),StandardCharsets.UTF_8));}
        catch(Exception error){System.err.println("Lifecycle catalog unavailable ("+file+"): "+error.getMessage());}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> parse(String json){
        Map<String,Object> parsed=(Map<String,Object>)Json.parse(json);
        if(!(parsed.get("produtos") instanceof List))throw new IllegalArgumentException("Missing produtos");
        return parsed;
    }
    static Map<String,Object> catalog(){return catalog;}
    static String today(Instant now){return LocalDate.ofInstant(now,ZoneOffset.UTC).toString();}

    static String product(String kind,String type,String applicationType,String name){
        String t=type.toLowerCase(Locale.ROOT);
        switch(kind){
            case "runtimes":return "java";
            case "servers":return t.contains("weblogic")?"weblogic":t.contains("wildfly")?"wildfly":t.contains("jboss")?"jboss-eap":t.contains("tomcat")?"tomcat":"";
            case "applications":return "spring boot".equalsIgnoreCase(applicationType)||"spring boot".equals(t)?"spring-boot":"";
            case "libraries":return name.toLowerCase(Locale.ROOT).contains("ojdbc")?"oracle-jdbc":"";
            case "datasources":return t.startsWith("oracle.jdbc.")||name.toLowerCase(Locale.ROOT).contains("ojdbc")?"oracle-jdbc":"";
            default:return "";
        }
    }
    /** Version from the first digit on: "EAP 7.4.0.GA" -> "7.4.0.GA". */
    static String numeric(String version){for(int i=0;i<version.length();i++)if(Character.isDigit(version.charAt(i)))return version.substring(i);return "";}

    static String status(String kind,String type,String version,String major,String applicationType,String frameworkVersion,String name,LocalDate today){
        Map<String,Object> line=line(kind,type,version,major,applicationType,frameworkVersion,name);
        if(line==null)return UNKNOWN;
        Object fixed=line.get("status");
        return fixed!=null?String.valueOf(fixed):status(line,today);
    }
    static String status(Map<String,Object> line,LocalDate today){
        LocalDate principal=date(line.get("fimPrincipal")),extended=date(line.get("fimEstendido"));
        if(principal==null&&extended==null)return Boolean.TRUE.equals(line.get("vigente"))?SUPPORTED:UNKNOWN;
        LocalDate end=extended!=null?extended:principal;
        if(today.isAfter(end))return OUT;
        if(principal==null||today.isAfter(principal))return EXTENDED;
        return today.plusMonths(12).isBefore(principal)?SUPPORTED:ENDING;
    }
    private static LocalDate date(Object value){return value instanceof String s&&!s.isEmpty()?LocalDate.parse(s):null;}

    /** Catalog line for the record, or null. WildFly lines are derived from the current community major. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> line(String kind,String type,String version,String major,String applicationType,String frameworkVersion,String name){
        String id=product(kind,s(type),s(applicationType),s(name));
        if(id.isEmpty())return null;
        String key=id.equals("java")?s(major):numeric(s(id.equals("spring-boot")?frameworkVersion:version));
        if(key.isEmpty())return null;
        for(Object value:(List<Object>)catalog.get("produtos")){
            Map<String,Object> product=(Map<String,Object>)value;
            if(!id.equals(product.get("id")))continue;
            if(product.get("versaoAtual") instanceof String current){
                String own=key.split("\\.",2)[0];
                if(!own.matches("\\d{1,4}"))return null;
                boolean latest=Integer.parseInt(own)>=Integer.parseInt(current);
                return map("produto",product,"linha",product.get("produto")+" "+own,"status",latest?SUPPORTED:OUT);
            }
            for(Object candidate:(List<Object>)product.getOrDefault("linhas",List.of())){
                Map<String,Object> line=(Map<String,Object>)candidate;
                for(Object prefix:(List<Object>)line.get("versoes"))if(key.equals(prefix)||key.startsWith(prefix+"."))return map("produto",product,"linha",line.get("linha"),"fimPrincipal",line.get("fimPrincipal"),"fimEstendido",line.get("fimEstendido"),"vigente",line.get("vigente"),"observacao",line.get("observacao"),"fonte",line.get("fonte"),"status",null);
            }
            return null;
        }
        return null;
    }
    private static String s(Object value){return value==null?"":String.valueOf(value);}

    /** Badge payload for one inventory record; null when the record kind has no lifecycle. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> support(String kind,Map<String,Object> item,LocalDate today){
        if(!KINDS.contains(kind))return null;
        String version=s(item.get(kind.equals("datasources")?"driverVersion":"version"));
        if(kind.equals("datasources")&&Set.of("AMBIGUOUS","INCOMPLETE","VARIES","NOT_FOUND").contains(s(item.get("driverResolution"))))version="";
        String type=kind.equals("datasources")?s(item.get("driverName")):s(item.containsKey("type")?item.get("type"):item.get("name"));
        Map<String,Object> line=line(kind,type,version,InventoryStore.major(version),s(item.get("applicationType")),s(item.get("frameworkVersion")),s(item.get("name")));
        if(line==null)return map("status",UNKNOWN,"dataReferencia",catalog.get("dataReferencia"));
        Map<String,Object> product=(Map<String,Object>)line.remove("produto");
        Object fixed=line.remove("status");
        Map<String,Object> result=map("status",fixed!=null?fixed:status(line,today),"produto",product.get("produto"),"fornecedor",product.get("fornecedor"),"referencia",product.get("referencia"),"dataReferencia",catalog.get("dataReferencia"));
        for(Map.Entry<String,Object> entry:line.entrySet())if(entry.getValue()!=null)result.put(entry.getKey(),entry.getValue());
        if(kind.equals("datasources")&&"PROBABLE".equals(item.get("driverResolution")))result.put("confidence","PROBABLE");
        if(!result.containsKey("fonte"))result.put("fonte",product.get("fonte"));
        if(!result.containsKey("observacao")&&product.get("observacao")!=null)result.put("observacao",product.get("observacao"));
        return result;
    }
    static void decorate(String kind,List<Map<String,Object>> items,Instant now){
        LocalDate today=LocalDate.parse(today(now));
        for(Map<String,Object> item:items){Map<String,Object> support=support(kind,item,today);if(support!=null)item.put("support",support);}
    }
    /** Group rows named "Java 8" or "1.8.0_381". */
    static void decorateJavaGroups(List<Map<String,Object>> groups,Instant now){
        LocalDate today=LocalDate.parse(today(now));
        for(Map<String,Object> group:groups){
            if(Boolean.TRUE.equals(group.get("other")))continue;
            String version=s(group.get("name")).replaceFirst("^Java ","");
            group.put("support",support("runtimes",map("version",version),today));
        }
    }

    static void register(Connection connection)throws SQLException{
        org.sqlite.Function.create(connection,"lifecycle_status",new org.sqlite.Function(){
            @Override protected void xFunc()throws SQLException{
                String date=value_text(7);
                result(status(text(0),text(1),text(2),text(3),text(4),text(5),text(6),date==null||date.isEmpty()?LocalDate.now(ZoneOffset.UTC):LocalDate.parse(date)));
            }
            private String text(int index)throws SQLException{String value=value_text(index);return value==null?"":value;}
        },8,org.sqlite.Function.FLAG_DETERMINISTIC);
    }

    /** Overview risk counts and Java-by-support breakdown for the scope. */
    static Map<String,Object> risks(InventoryStore db,InventoryQueries.Scope f){
        String w=f.where(),today=today(f.now);List<Object> scope=new ArrayList<>(f.args);
        String from=" FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE "+w;
        Map<String,Object> servers=map(OUT,0L,ENDING,0L);
        for(Map<String,Object> row:db.rows("SELECT "+SQL+" status,count(*) count"+from+" AND i.kind='servers' GROUP BY 1",args(today,scope).toArray()))servers.put(String.valueOf(row.get("status")),row.get("count"));
        long javaHosts=db.scalar("SELECT count(DISTINCT i.host_id)"+from+" AND i.kind='runtimes' AND "+SQL+"=?",withStatus(today,scope));
        long spring=db.scalar("SELECT count(*)"+from+" AND i.kind='applications' AND "+SQL+"=?",withStatus(today,scope));
        long drivers=db.scalar("SELECT count(*)"+from+" AND i.kind='datasources' AND "+DRIVER_UNRESOLVED,scope.toArray());
        long replaced=db.scalar("SELECT count(*)"+from+" AND i.kind='runtimes' AND "+REPLACED,scope.toArray());
        long partial=db.scalar("SELECT count(*) FROM hosts h WHERE "+w+" AND h.coverage_status='partial'",scope.toArray());
        return map("javaHostsOut",javaHosts,"serversOut",servers.get(OUT),"serversEnding",servers.get(ENDING),"springOut",spring,"driversUnresolved",drivers,"partialHosts",partial,"runtimesReplaced",replaced,"dataReferencia",catalog.get("dataReferencia"));
    }
    /** Java installations grouped by support status, listing the major versions inside each status. */
    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> javaSupport(InventoryStore db,InventoryQueries.Scope f){
        String today=today(f.now);
        List<Map<String,Object>> rows=db.rows("SELECT "+SQL+" status,CASE WHEN i.major='' THEN 'Versão desconhecida' ELSE 'Java ' || i.major END name,count(*) count FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='runtimes' AND "+f.where()+" GROUP BY 1,2 ORDER BY count DESC,name",args(today,f.args).toArray());
        Map<String,Map<String,Object>> groups=new LinkedHashMap<>();
        for(String status:STATUSES)groups.put(status,map("status",status,"count",0L,"versions",new ArrayList<>()));
        for(Map<String,Object> row:rows){
            Map<String,Object> group=groups.get(String.valueOf(row.get("status")));
            group.put("count",InventoryQueries.n(group.get("count"))+InventoryQueries.n(row.get("count")));
            ((List<Object>)group.get("versions")).add(map("name",row.get("name"),"count",row.get("count")));
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> group:groups.values())if(InventoryQueries.n(group.get("count"))>0)result.add(group);
        return result;
    }
    private static List<Object> args(String today,List<Object> scope){List<Object> values=new ArrayList<>();values.add(today);values.addAll(scope);return values;}
    private static Object[] withStatus(String today,List<Object> scope){List<Object> values=new ArrayList<>(scope);values.add(today);values.add(OUT);return values.toArray();}
}
