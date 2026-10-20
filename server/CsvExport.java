package assessment;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/** Fixed-column inventory export. The caller owns the read snapshot and writer. */
final class CsvExport {
    private static final String[] KINDS={"hosts","runtimes","processes","servers","libraries","applications","datasources","jobs"};
    private static final String[] COLUMNS={
        "Tipo de registro","Ambiente","ID do ambiente","Máquina","ID da máquina","ID do registro","Nome","Versão","Tipo","Caminho","Estado","Observado em","Fornecedor","Diretório Java","Em uso","ID da aplicação","Aplicação","Servidor Java","Categoria","Uso","Evidência","Nível de evidência","Versão do framework","Driver JDBC","Versão do driver","Tipo de banco","Origem da configuração","Pool máximo configurado","Capacidade do pool","Conexões disponíveis","Conexões ativas","PID","Classe principal","Módulo","Sistema operacional","Distribuição","Arquitetura","Endereços IP","Versão do agente","Versão do Java do agente","Ciclo de vida do agente","Último contato","Última coleta","Estado da cobertura","CPUs lógicas","Memória total (bytes)","Criado em","Iniciado em","Concluído em","Resultado","Host/IP do banco (configurado)","Portas do banco (configuradas)","Banco / serviço / SID (configurado)","Destino do banco (configurado)","IDs dos servidores associados","Domínio WebLogic","Caminho do domínio","Arquivo do driver","Identificação do driver","Evidência da versão do driver","Drivers candidatos","Fonte da versão do driver","Motivo da identificação do driver"
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
            coverage.get("status"),host.get("logicalCpus"),host.get("memoryTotalBytes"),item.get("createdAt"),item.get("startedAt"),item.get("finishedAt"),job?first(item,"error","summary"):null,
            endpoints(item,"host"),endpoints(item,"port"),first(item,"databaseName"),destination(item),associatedIds(item,"serverId"),first(item,"domainName"),first(item,"domainPath"),first(item,"driverArtifact"),first(item,"driverResolution"),first(item,"driverVersionEvidence"),driverCandidates(item),first(item,"driverVersionSource"),first(item,"driverResolutionReason")
        };
    }

    private static String driverCandidates(Map<?,?> item){
        List<String> values=new ArrayList<>();
        if(item.get("driverCandidates") instanceof List<?> entries) for(Object raw:entries) if(raw instanceof Map<?,?> c && values.size()<32) values.add(String.valueOf(c.getOrDefault("path",null))+" — "+String.valueOf(c.getOrDefault("version",null)));
        return String.join(" | ",values);
    }

    private static List<Map<String,String>> endpointList(Map<?,?> item){
        var result=new ArrayList<Map<String,String>>();
        if(item.get("databaseEndpoints") instanceof List<?> entries)for(Object entry:entries)if(entry instanceof Map<?,?> endpoint){
            Object host=first(endpoint,"host"),port=first(endpoint,"port");
            if(host!=null&&!host.toString().isEmpty())result.add(Map.of("host",host.toString(),"port",port==null?"":port.toString()));
        }
        if(result.isEmpty()&&first(item,"databaseHost")!=null)result.add(Map.of("host",first(item,"databaseHost").toString(),"port",first(item,"databasePort")==null?"":first(item,"databasePort").toString()));
        return result;
    }
    private static String endpoints(Map<?,?> item,String key){
        Set<String> values=new LinkedHashSet<>();for(var endpoint:endpointList(item))if(!endpoint.get(key).isEmpty())values.add(endpoint.get(key));return String.join(" | ",values);
    }
    private static String destination(Map<?,?> item){
        Set<String> values=new LinkedHashSet<>();for(var endpoint:endpointList(item)){
            String host=endpoint.get("host"),port=endpoint.get("port");
            values.add((host.contains(":")&&!host.startsWith("[")?"["+host+"]":host)+(port.isEmpty()?"":":"+port));
        }
        Object name=first(item,"databaseName");return String.join(" | ",values)+(name==null?"":(values.isEmpty()?"":" / ")+name);
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

    private static String associatedIds(Map<?,?> item,String field){
        Set<String> ids=new LinkedHashSet<>();Object direct=first(item,field);if(direct!=null)ids.add(direct.toString());
        if(item.get("associations") instanceof List<?> values)for(Object value:values)if(value instanceof Map<?,?> relation){String id=text(relation.get(field));if(!id.isEmpty())ids.add(id);}
        return String.join(" | ",ids);
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
