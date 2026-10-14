package assessment;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/** Fixed-column inventory export. The caller owns the read snapshot and writer. */
final class CsvExport {
    private static final String[] KINDS={"hosts","runtimes","processes","servers","libraries","applications","datasources","jobs"};
    private static final String[] COLUMNS={
        "record_type","environment","environment_id","hostname","host_id","record_id",
        "name","version","type","path","state","observed_at","vendor","java_home","in_use",
        "application_id","application_name","server_name","category","usage","evidence","evidence_level",
        "framework_version","driver_name","driver_version","database_type","configuration_source",
        "configured_max_pool_size","pool_capacity","pool_available","active_connections",
        "pid","main_class","module_name","os","distribution","architecture","ip_addresses",
        "collector_version","collector_java_version","agent_lifecycle","last_seen","last_assessment_at",
        "coverage_status","logical_cpus","memory_total_bytes","created_at","started_at","finished_at","summary"
    };

    private CsvExport() {}

    static void write(Writer writer,InventoryStore store,InventoryQueries queries,InventoryQueries.Scope scope)throws Exception {
        writer.write('\uFEFF');
        row(writer,COLUMNS);
        for(String kind:KINDS) {
            InventoryQueries.Scope selected=new InventoryQueries.Scope();
            selected.environment=scope.environment;selected.host=scope.host;selected.size=100;
            while(true) {
                Map<String,Object> page=queries.page(selected,kind);
                // Each batch owns at most 100 hosts/environments, including an all-fleet export.
                Map<String,Map<String,Object>> hosts=new HashMap<>(),environments=new HashMap<>();
                for(Object value:(List<?>)page.get("items")) {
                    Map<?,?> item=(Map<?,?>)value;
                    String hostId=text(item.get(kind.equals("hosts")?"id":"hostId"));
                    Map<?,?> host=kind.equals("hosts")?item:hosts.computeIfAbsent(hostId,id->document(store,"hosts",id));
                    String environmentId=text(host.get("environmentId"));
                    Map<?,?> environment=environmentId.isEmpty()?Map.of():environments.computeIfAbsent(environmentId,id->document(store,"environments",id));
                    String environmentName=environmentId.isEmpty()?"Sem ambiente":text(environment.get("name"));
                    if(environmentName.isEmpty())environmentName=environmentId;
                    row(writer,values(kind,item,host,environmentName,environmentId,hostId));
                }
                Map<?,?> pagination=(Map<?,?>)page.get("pagination");
                if(selected.page>=((Number)pagination.get("pages")).intValue())break;
                selected.page++;
            }
        }
    }

    private static Map<String,Object> document(InventoryStore store,String table,String id) {
        Map<String,Object> result=store.get(table,id);
        return result==null?Map.of():result;
    }

    private static Object[] values(String kind,Map<?,?> item,Map<?,?> host,String environment,String environmentId,String hostId) {
        boolean machine=kind.equals("hosts"),job=kind.equals("jobs");
        Map<?,?> coverage=host.get("coverage") instanceof Map<?,?> map?map:Map.of();
        return new Object[]{
            kind,environment,environmentId,first(host,"hostname","fqdn"),hostId,item.get("id"),
            machine?first(host,"hostname","fqdn"):job?item.get("id"):first(item,"name","mainClass"),
            machine?host.get("collectorVersion"):first(item,"version","driverVersion"),
            machine?host.get("osName"):first(item,"applicationType","type","kind","serverType"),
            first(item,"path","sourcePath","launchJar","javaHome"),
            first(item,"state","status"),machine?first(host,"lastAssessmentAt","lastSeen"):job?first(item,"finishedAt","createdAt"):item.get("observedAt"),
            item.get("vendor"),item.get("javaHome"),item.get("inUse"),
            associated(item,"applicationId"),associated(item,"applicationName"),associated(item,"serverName"),item.get("category"),item.get("usage"),item.get("evidence"),item.get("evidenceLevel"),
            item.get("frameworkVersion"),item.get("driverName"),item.get("driverVersion"),item.get("databaseType"),item.get("configurationSource"),
            item.get("configuredMaxPoolSize"),item.get("poolCapacity"),item.get("poolAvailable"),item.get("activeConnections"),
            item.get("pid"),item.get("mainClass"),item.get("moduleName"),host.get("osName"),host.get("distribution"),first(item,"architecture")!=null?item.get("architecture"):host.get("architecture"),strings(host.get("ipAddresses")),
            host.get("collectorVersion"),host.get("collectorJavaVersion"),host.get("agentLifecycle"),host.get("lastSeen"),host.get("lastAssessmentAt"),
            coverage.get("status"),host.get("logicalCpus"),host.get("memoryTotalBytes"),item.get("createdAt"),item.get("startedAt"),item.get("finishedAt"),job?first(item,"error","summary"):null
        };
    }

    /** Export only scalar values; nested metadata is deliberately never serialized. */
    private static Object first(Map<?,?> source,String... names) {
        for(String name:names) {
            Object value=source.get(name);
            if(value instanceof String text&&!text.isEmpty()||value instanceof Number||value instanceof Boolean)return value;
        }
        return null;
    }

    private static String text(Object value) {return value instanceof String text?text:"";}

    /** Relationships expose only the three named scalar fields above, never their full payload. */
    private static Object associated(Map<?,?> item,String field) {
        Object direct=first(item,field);
        if(direct!=null)return direct;
        if(!(item.get("associations") instanceof List<?> associations))return null;
        Set<String> values=new LinkedHashSet<>();
        for(Object value:associations)if(value instanceof Map<?,?> relationship) {
            String name=text(relationship.get(field));if(!name.isEmpty())values.add(name);
        }
        return String.join(" | ",values);
    }

    private static String strings(Object value) {
        if(!(value instanceof List<?> values))return "";
        StringJoiner result=new StringJoiner(" | ");
        for(Object item:values)if(item instanceof String text)result.add(text);
        return result.toString();
    }

    private static void row(Writer writer,Object[] values)throws IOException {
        for(int i=0;i<values.length;i++) {
            if(i>0)writer.write(',');
            cell(writer,values[i]);
        }
        writer.write("\r\n");
    }

    private static void cell(Writer writer,Object value)throws IOException {
        if(value instanceof Number number) {
            if(Double.isFinite(number.doubleValue()))writer.write(number.toString());
        }else if(value instanceof Boolean flag)writer.write(flag.toString());
        else if(value instanceof String text) {
            writer.write('"');
            if(formula(text))writer.write('\'');
            writer.write(text.replace("\"","\"\""));writer.write('"');
        }
    }

    private static boolean formula(String text) {
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i);
            if(cp=='\t'||cp=='\r'||cp=='\n'||cp==0xFEFF)return true;
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)||Character.isISOControl(cp)){i+=Character.charCount(cp);continue;}
            return cp=='='||cp=='+'||cp=='-'||cp=='@';
        }
        return false;
    }
}
