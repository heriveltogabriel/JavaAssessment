package assessment;

import java.util.*;
import java.util.function.Consumer;

/**
 * "Mapa de bancos": one flat row per datasource x configured database endpoint x process/application,
 * ready to join with another inventory. Values come only from the sanitized export documents.
 */
final class InventoryArchiveMap {
    static final List<String> COLUMNS=List.of("levantamento","ambiente","maquina","maquinaFqdn","maquinaIps",
        "servidorTipo","servidorVersao","servidorNome","dominio","pid","javaVersao",
        "aplicacao","aplicacaoTipo","contexto",
        "datasource","jndi","origemConfiguracao","arquivoConfiguracao",
        "bancoTipo","bancoHost","bancoPorta","bancoServico","chaveServico","chaveHostPorta","hostCurto",
        "driverClasse","driverJar","driverVersao","driverStatus","driverSuporte",
        "evidenciaBanco","observacao","datasourceId","hostId");
    private final Consumer<List<Object>> sink;
    private final String label;
    private final Map<String,Map<String,Object>> hosts=new HashMap<>(),environments=new HashMap<>(),servers=new HashMap<>(),applications=new HashMap<>(),processes=new HashMap<>();
    private final Map<String,String> runtimes=new HashMap<>();
    /** Rows go to a sink: the ';' CSV writer or a spreadsheet sheet. */
    InventoryArchiveMap(Consumer<List<Object>> sink,String label){this.sink=sink;this.label=label;}
    static InventoryArchiveMap csv(Consumer<String> output,String label){output.accept("\uFEFF");InventoryArchiveMap map=new InventoryArchiveMap(values->output.accept(line(values)),label);map.sink.accept(new ArrayList<>(COLUMNS));return map;}
    /** Indexes are filled first, inside the same read snapshot as the datasources. */
    void index(String collection,Map<String,Object> clean){
        String id=s(clean.get("id")),host=s(clean.get("hostId"));
        switch(collection){
            case "environments"->environments.put(id,slim(clean,"name"));
            case "hosts"->hosts.put(id,slim(clean,"hostname","fqdn","ipAddresses","environmentId"));
            case "servers"->servers.put(host+"\u0000"+id,slim(clean,"type","version","name","domainName","domainPath","pid"));
            case "applications"->applications.put(host+"\u0000"+id,slim(clean,"name","applicationType","type","contextPath","pid"));
            case "processes"->{Map<String,Object> p=slim(clean,"javaHome","javaVersion","serverType");processes.put(host+"\u0000"+s(clean.get("pid")),p);}
            case "runtimes"->{if(clean.get("javaHome")!=null&&clean.get("version")!=null)runtimes.put(host+"\u0000"+s(clean.get("javaHome")),s(clean.get("version")));}
            default->{}
        }
    }
    @SuppressWarnings("unchecked")
    long datasource(Map<String,Object> ds){
        String hostId=s(ds.get("hostId"));
        Map<String,Object> host=hosts.getOrDefault(hostId,Map.of()),env=environments.getOrDefault(s(host.get("environmentId")),Map.of());
        List<Map<String,Object>> endpoints=new ArrayList<>();
        if(ds.get("databaseEndpoints") instanceof List<?> list)for(Object e:list)if(e instanceof Map<?,?> m)endpoints.add((Map<String,Object>)m);
        if(endpoints.isEmpty())endpoints.add(map("host",ds.get("databaseHost"),"port",ds.get("databasePort")));
        List<Map<String,Object>> associations=new ArrayList<>();
        if(ds.get("associations") instanceof List<?> list)for(Object a:list)if(a instanceof Map<?,?> m)associations.add((Map<String,Object>)m);
        if(associations.isEmpty())associations.add(map("pid",ds.get("pid"),"serverId",ds.get("serverId"),"serverName",ds.get("serverName"),"applicationId",ds.get("applicationId"),"applicationName",ds.get("applicationName")));
        long rows=0;
        for(Map<String,Object> a:associations){
            Map<String,Object> server=servers.getOrDefault(hostId+"\u0000"+s(a.get("serverId")),Map.of());
            Map<String,Object> app=applications.getOrDefault(hostId+"\u0000"+s(a.get("applicationId")),Map.of());
            String pid=first(a.get("pid"),server.get("pid"),app.get("pid"));
            Map<String,Object> process=processes.getOrDefault(hostId+"\u0000"+pid,Map.of());
            String java=first(process.get("javaVersion"),runtimes.get(hostId+"\u0000"+s(process.get("javaHome"))));
            Map<String,Object> decision=a.get("driverResult") instanceof Map<?,?> m?(Map<String,Object>)m:ds;
            String[] driver=driver(decision);
            for(Map<String,Object> e:endpoints){
                String type=s(ds.get("databaseType")),dbHost=first(e.get("host"),ds.get("databaseHost")),port=first(e.get("port"),ds.get("databasePort"));
                String service=first(e.get("service"),e.get("database"),e.get("sid"),e.get("name"),ds.get("databaseName"));
                String observation=first(ds.get("datasourceObservation"),ds.get("configurationObservation"),ds.get("jndiResolution"),
                    dbHost.isEmpty()?"Destino do banco não encontrado na configuração lida":"",
                    "IDENTIFIED".equals(driver[2])?"":s(decision.get("driverResolutionReason")));
                List<Object> values=new ArrayList<>(List.of(label,s(env.get("name")),s(host.get("hostname")),s(host.get("fqdn")),list(host.get("ipAddresses")),
                    first(server.get("type"),process.get("serverType")),s(server.get("version")),first(a.get("serverName"),server.get("name")),first(server.get("domainName"),server.get("domainPath")),pid,java,
                    first(a.get("applicationName"),app.get("name")),first(app.get("applicationType"),app.get("type")),s(app.get("contextPath")),
                    s(ds.get("name")),first(ds.get("jndiName"),list(ds.get("jndiNames"))),first(ds.get("discoveryMethod"),ds.get("resolvedFrom"),ds.get("configurationContext")),first(ds.get("configurationSource"),ds.get("sourcePath")),
                    type,dbHost,port,service,serviceKey(type,service),hostPortKey(dbHost,port),shortHost(dbHost),
                    first(ds.get("driverName"),ds.get("driverClass")),driver[0],driver[1],statusLabel(driver[2]),supportStatus(ds),
                    dbHost.isEmpty()?"Destino não identificado":evidence(ds),observation,s(ds.get("id")),hostId));
                sink.accept(values);rows++;
            }
        }
        return rows;
    }
    /** [jar, version, status]; a single on-classpath candidate of an incomplete decision is labeled, never promoted. */
    @SuppressWarnings("unchecked")
    static String[] driver(Map<String,Object> d){
        String status=s(d.get("driverResolution")),jar=s(d.get("driverArtifact")),version=s(d.get("driverVersion"));
        if(jar.isEmpty()&&"INCOMPLETE".equals(status)&&d.get("driverCandidates") instanceof List<?> list&&!list.isEmpty()){
            Map<String,Object> unique=null;
            for(Object raw:list){
                if(!(raw instanceof Map<?,?> m))return new String[]{"","",status};
                Map<String,Object> c=(Map<String,Object>)m;
                if(Boolean.FALSE.equals(c.get("onClasspath"))||Boolean.TRUE.equals(c.get("replacedOnDisk"))||!s(c.get("path")).toLowerCase(Locale.ROOT).endsWith(".jar")||s(c.get("version")).isEmpty())return new String[]{"","",status};
                if(unique!=null&&(!s(unique.get("path")).equals(s(c.get("path")))||!s(unique.get("version")).equals(s(c.get("version")))))return new String[]{"","",status};
                unique=c;
            }
            return new String[]{s(unique.get("path")),s(unique.get("version")),"INCOMPLETE_PROBABLE"};
        }
        return new String[]{jar,version,status};
    }
    static String statusLabel(String status){
        return switch(status){case "IDENTIFIED"->"Em uso (ordem de carregamento)";case "PROBABLE"->"Provável";case "AMBIGUOUS"->"Múltiplos candidatos";case "VARIES"->"Varia por servidor";
            case "VERSION_UNAVAILABLE"->"Versão indisponível";case "NOT_FOUND"->"Driver não localizado";case "INCOMPLETE"->"Inspeção incompleta";
            case "INCOMPLETE_PROBABLE"->"Inspeção incompleta (candidato provável)";default->status;};
    }
    static String serviceKey(String type,String service){return type.isEmpty()||service.isEmpty()?"":type.toLowerCase(Locale.ROOT)+"|"+service.toUpperCase(Locale.ROOT);}
    static String hostPortKey(String host,String port){return host.isEmpty()?"":host.toLowerCase(Locale.ROOT)+(port.isEmpty()?"":":"+port);}
    static String shortHost(String host){
        String h=host.toLowerCase(Locale.ROOT);
        if(h.isEmpty()||h.matches("[0-9.]+")||h.contains(":"))return h;
        int dot=h.indexOf('.');return dot>0?h.substring(0,dot):h;
    }
    @SuppressWarnings("unchecked")
    static String supportStatus(Map<String,Object> ds){return ds.get("support") instanceof Map<?,?> m?s(((Map<String,Object>)m).get("status")):"";}
    static String evidence(Map<String,Object> ds){
        String level=s(ds.get("databaseEndpointEvidence"));
        String base="Configurado; conexão não confirmada";
        return level.isEmpty()||level.equals("configured")?base:base+" ("+level+")";
    }
    private static Map<String,Object> slim(Map<String,Object> source,String... keys){Map<String,Object> r=new HashMap<>();for(String k:keys)if(source.get(k)!=null)r.put(k,source.get(k));return r;}
    private static Map<String,Object> map(Object... kv){Map<String,Object> r=new HashMap<>();for(int i=0;i<kv.length;i+=2)if(kv[i+1]!=null)r.put((String)kv[i],kv[i+1]);return r;}
    static String s(Object v){return v==null?"":v instanceof Double d&&d==Math.rint(d)?String.valueOf(d.longValue()):String.valueOf(v);}
    static String list(Object v){if(v instanceof List<?> l){StringBuilder b=new StringBuilder();for(Object o:l){if(o==null)continue;if(b.length()>0)b.append("; ");b.append(o);}return b.toString();}return s(v);}
    static String first(Object... values){for(Object v:values){String t=s(v);if(!t.isEmpty())return t;}return "";}
    static String line(List<Object> values){
        StringBuilder line=new StringBuilder();boolean firstCell=true;
        for(Object value:values){
            if(!firstCell)line.append(';');firstCell=false;
            String text=s(value).replace("\r"," ").replace("\n"," ");
            if(text.isEmpty())continue;
            line.append('"').append(formula(text)?"'":"").append(text.replace("\"","\"\"")).append('"');
        }
        return line.append("\r\n").toString();
    }
    private static boolean formula(String text){char c=text.charAt(0);return c=='='||c=='+'||c=='-'||c=='@'||c=='\t';}
}
