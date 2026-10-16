package assessment;

import java.io.*;
import java.time.Instant;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Read-only, allowlisted reports over the latest stored inventory. */
final class Reports {
    static final int MAX_ROWS=10000, MAX_CELL=8192;
    static final class Failure extends RuntimeException { final int status; Failure(int status,String message){super(message);this.status=status;} }
    static final Map<String,String> LABELS=new LinkedHashMap<>();
    static final Map<String,List<String>> COLUMNS=new LinkedHashMap<>(), DEFAULTS=new LinkedHashMap<>();
    static final Map<String,String> TITLES=new LinkedHashMap<>(), FILTERS=new LinkedHashMap<>();
    static {
        String[][] labels={{"jdbc","JDBC"},{"hostJdbc","JDBC da máquina"},{"javaVersion","Java do servidor"},{"path","Caminho do servidor"},{"pid","PID"},{"databaseType","Banco"},{"databaseVersion","Versão do banco"},{"databaseVersionEvidence","Origem da versão do banco"},{"datasourceKey","Identificador do datasource"},{"poolName","Pool"},{"configurationResolution","Resolução da configuração"},{"databaseHost","Host/IP do banco (configurado)"},{"databasePort","Portas do banco (configuradas)"},{"databaseName","Banco / serviço / SID (configurado)"},{"databaseDestination","Destino do banco (configurado)"},{"applicationName","Aplicação associada"},{"driverReference","Referência do driver"},{"resourceType","Tipo de recurso"},{"datasourceState","Estado informado"},{"configuredMaxPoolSize","Pool máximo configurado"},{"machine","Máquina"},{"environment","Ambiente"},{"ip","IP de rede"},{"os","Sistema operacional"},{"architecture","Arquitetura"},{"cpus","CPUs lógicas"},{"memory","Memória (GiB)"},{"agentVersion","Versão do agente"},{"javaVersions","Versões Java"},{"servers","Servidores da máquina"},{"datasources","Datasources da máquina"},{"drivers","Drivers JDBC da máquina"},{"version","Versão"},{"javaHome","Diretório Java"},{"vendor","Fornecedor Java"},{"product","Servidor Java"},{"name","Nome do servidor"},{"datasource","Datasource / nome JNDI"},{"driver","Driver JDBC"},{"driverClass","Classe do driver"},{"driverVersion","Versão do driver"},{"serverName","Servidor associado"},{"machines","Máquinas"},{"javaCount","Instalações Java"},{"serverCount","Servidores Java"},{"datasourceCount","Datasources JDBC"}};
        for(String[] l:labels)LABELS.put(l[0],l[1]);
        model("machines","Inventário de máquinas","machine,environment,ip,os,javaVersions,servers,jdbc,databaseHost,datasources,drivers,architecture,cpus,memory,agentVersion,databaseDestination,databasePort,databaseName","machine,environment,ip,os,javaVersions,servers,jdbc,databaseHost");
        model("java","Instalações Java","machine,environment,ip,version,javaHome,vendor,os,architecture,jdbc,databaseHost,databaseDestination,databasePort,databaseName","machine,environment,ip,version,javaHome,vendor,jdbc,databaseHost");
        model("servers","Servidores Java","machine,environment,ip,product,name,version,javaVersion,os,jdbc,databaseHost,javaHome,path,pid,hostJdbc,datasources,drivers,databaseDestination,databasePort,databaseName","machine,environment,ip,product,name,version,javaVersion,os,jdbc,databaseHost");
        model("jdbc","Datasources e drivers JDBC","machine,environment,ip,datasource,databaseType,databaseVersion,databaseVersionEvidence,driver,driverVersion,driverClass,serverName,applicationName,jdbc,resourceType,datasourceState,driverReference,configuredMaxPoolSize,datasourceKey,poolName,configurationResolution,databaseHost,databasePort,databaseName,os,databaseDestination","machine,environment,ip,datasource,databaseType,databaseVersion,databaseVersionEvidence,driver,driverVersion,driverClass,serverName,applicationName,jdbc,databaseHost");
        model("environments","Resumo por ambiente","environment,machines,javaCount,serverCount,datasourceCount,jdbc,databaseHost,databaseDestination,databasePort,databaseName","environment,machines,javaCount,serverCount,datasourceCount,jdbc,databaseHost");
        for(String[] f:new String[][]{{"environmentId","Ambiente"},{"machine","Máquina"},{"os","Sistema operacional"},{"architecture","Arquitetura"},{"javaVersion","Versão Java"},{"serverType","Servidor Java"},{"serverVersion","Versão do servidor"},{"datasource","Datasource / nome JNDI"},{"driver","Driver JDBC"},{"driverVersion","Versão do driver"},{"databaseVersion","Versão do banco"},{"databaseType","Banco"},{"driverClass","Classe do driver"},{"driverReference","Referência do driver"},{"serverName","Servidor associado ao JDBC"},{"applicationName","Aplicação associada ao JDBC"},{"resourceType","Tipo de recurso JDBC"},{"datasourceState","Estado informado do JDBC"},{"databaseHost","Host/IP do banco (configurado)"},{"databasePort","Porta do banco (configurada)"},{"databaseName","Banco / serviço / SID (configurado)"},{"jdbcPresence","Presença de JDBC"}})FILTERS.put(f[0],f[1]);
    }
    private static void model(String key,String title,String columns,String defaults){TITLES.put(key,title);COLUMNS.put(key,List.of(columns.split(",")));DEFAULTS.put(key,List.of(defaults.split(",")));}
    static String s(Object value){return value==null?"":String.valueOf(value);}
    static List<Map<String,Object>> columns(List<String> keys){return keys.stream().map(k->map("key",k,"label",LABELS.get(k))).toList();}
    static List<Map<String,Object>> modelColumns(List<String> keys,String model){
        var result=columns(keys);
        for(var column:result)if(column.get("key").equals("jdbc"))column.put("label",switch(model){case "servers"->"JDBC do servidor";case "environments"->"JDBC do ambiente";case "jdbc"->"JDBC · datasource / driver / versão";default->"JDBC da máquina";});
        return result;
    }
    final InventoryStore db;
    Reports(InventoryStore db){this.db=db;}
    Map<String,Object> options(){
        return map("models",TITLES.keySet().stream().map(k->map("key",k,"label",TITLES.get(k),"columns",modelColumns(COLUMNS.get(k),k),"defaults",DEFAULTS.get(k))).toList(),
            "environments",db.rows("SELECT id,name FROM environments ORDER BY name,id"),"suggestions",suggestions());
    }
    private Map<String,Object> suggestions(){
        var result=new LinkedHashMap<String,Object>();
        var expressions=new LinkedHashMap<String,String>();
        for(String key:List.of("databaseType","driverReference","resourceType","driverVersion","databaseVersion","databaseName"))expressions.put(key,field("i",key));
        expressions.put("datasource",field("i","name"));expressions.put("driver",field("i","driverName"));expressions.put("driverClass",driverClass("i"));expressions.put("datasourceState",field("i","state"));
        for(var entry:expressions.entrySet())result.put(entry.getKey(),db.rows("SELECT DISTINCT "+entry.getValue()+" AS value FROM inventory i WHERE kind='datasources' AND length("+entry.getValue()+") BETWEEN 1 AND 200 ORDER BY value LIMIT 100").stream().map(r->s(r.get("value"))).toList());
        for(String key:List.of("serverName","applicationName")){
            String scalar=field("i",key), nested=associationField("a",key);
            result.put(key,db.rows("SELECT DISTINCT value FROM (SELECT "+scalar+" AS value FROM inventory i WHERE kind='datasources' UNION ALL SELECT "+nested+" AS value FROM inventory i,json_each("+associationArray("i")+") a WHERE i.kind='datasources') WHERE length(value) BETWEEN 1 AND 200 ORDER BY value LIMIT 100").stream().map(r->s(r.get("value"))).toList());
        }
        for(String key:List.of("databaseHost","databasePort")) {
            String member=key.equals("databaseHost")?"host":"port";
            result.put(key,db.rows("SELECT DISTINCT value FROM (SELECT "+field("i",key)+" AS value FROM inventory i WHERE kind='datasources' UNION ALL SELECT "+associationField("ep",member)+" FROM inventory i,json_each("+endpointArray("i")+") ep WHERE i.kind='datasources') WHERE length(value) BETWEEN 1 AND 200 ORDER BY value LIMIT 100").stream().map(r->s(r.get("value"))).toList());
        }
        return result;
    }
    static String context(String model){return "Destinos de banco são configurações encontradas; não confirmam conexão ativa. " + switch(model){
        case "servers" -> "Java e JDBC associados ao servidor pelo inventário. JDBC da máquina está disponível nas colunas adicionais. Associação ausente não confirma ausência de uso.";
        case "java" -> "JDBC da máquina: não confirma uso por esta instalação Java. Cada entrada mantém datasource, driver e versão juntos.";
        case "environments" -> "JDBC distinto nas máquinas filtradas de cada ambiente. Datasource, driver e versão permanecem juntos.";
        case "jdbc" -> "Uma linha por datasource. Versão do banco é distinta da versão do driver. Dados informados pela aplicação não constituem verificação independente de conectividade.";
        default -> "JDBC encontrado na máquina, com datasource, driver e versão juntos. Ausência no inventário não comprova ausência na máquina.";
    };}
    static final class Selection {
        final String model; final Map<String,String> filters=new LinkedHashMap<>(); final List<String> columns;
        final int page,size;
        Selection(Map<String,String> input){
            model=input.getOrDefault("model","machines");
            if(!COLUMNS.containsKey(model))throw new Failure(400,"Modelo de relatório inválido.");
            for(String key:input.keySet())if(!FILTERS.containsKey(key)&&!Set.of("model","columns","page","pageSize","revision").contains(key))throw new Failure(400,"Filtro de relatório inválido: "+key);
            for(String key:FILTERS.keySet()){
                String value=input.getOrDefault(key,"").strip();
                if(value.length()>200||value.codePoints().anyMatch(c->Character.isISOControl(c)))throw new Failure(400,"Filtro inválido: "+FILTERS.get(key));
                if(!value.isEmpty())filters.put(key,value);
            }
            if(filters.containsKey("jdbcPresence")&&!Set.of("present","missing").contains(filters.get("jdbcPresence")))throw new Failure(400,"Presença de JDBC inválida.");
            String raw=input.getOrDefault("columns","");columns=raw.isEmpty()?DEFAULTS.get(model):List.of(raw.split(",",-1));
            if(columns.isEmpty()||columns.size()>COLUMNS.get(model).size()||new HashSet<>(columns).size()!=columns.size()||!COLUMNS.get(model).containsAll(columns))throw new Failure(400,"Selecione colunas válidas para este modelo.");
            page=integer(input.getOrDefault("page","1"),1,10000);size=integer(input.getOrDefault("pageSize","20"),1,100);
        }
        private static int integer(String value,int min,int max){try{int n=Integer.parseInt(value);if(n>=min&&n<=max)return n;}catch(NumberFormatException ignored){}throw new Failure(400,"Paginação inválida.");}
    }
    static String field(String alias,String key){return "CASE WHEN json_type("+alias+".payload,'$."+key+"') IN ('text','integer','real') THEN json_extract("+alias+".payload,'$."+key+"') ELSE '' END";}
    private static String associationArray(String alias){return "CASE WHEN json_type("+alias+".payload,'$.associations')='array' THEN json_extract("+alias+".payload,'$.associations') ELSE '[]' END";}
    private static String associationField(String alias,String key){String value="CASE WHEN "+alias+".type='object' THEN "+alias+".value ELSE '{}' END";return "CASE WHEN json_type("+value+",'$."+key+"') IN ('text','integer','real') THEN json_extract("+value+",'$."+key+"') ELSE '' END";}
    private static String associatedText(String alias,String key){String scalar=field(alias,key),nested=associationField("assoc",key);return "(SELECT group_concat(value, char(10)) FROM (SELECT DISTINCT value FROM (SELECT "+scalar+" AS value UNION ALL SELECT "+nested+" FROM json_each("+associationArray(alias)+") assoc) WHERE value<>'' ORDER BY value))";}
    private static String driverClass(String alias){String name=field(alias,"driverName");return "coalesce(nullif("+field(alias,"driverClass")+",''),CASE WHEN "+name+" LIKE '%.%' AND "+name+" NOT LIKE '% %' THEN "+name+" ELSE '' END)";}
    /** Structured destinations only: never read or reconstruct a raw JDBC URL. */
    private static String endpointArray(String alias){return "CASE WHEN json_type("+alias+".payload,'$.databaseEndpoints')='array' THEN json_extract("+alias+".payload,'$.databaseEndpoints') ELSE '[]' END";}
    private static String endpointText(String alias,String key){
        String scalar=field(alias,key),member=key.equals("databaseHost")?"host":"port";
        return "coalesce((SELECT group_concat(value, ', ') FROM (SELECT DISTINCT "+associationField("ep",member)+" AS value FROM json_each("+endpointArray(alias)+") ep WHERE "+associationField("ep","host")+"<>'' AND "+associationField("ep",member)+"<>'')),nullif("+scalar+",''),'')";
    }
    private static String address(String host,String port){return "(CASE WHEN instr("+host+",':')>0 AND substr("+host+",1,1)<>'[' THEN '[' || "+host+" || ']' ELSE "+host+" END || CASE WHEN "+port+"<>'' THEN ':' || "+port+" ELSE '' END)";}
    private static String destination(String alias){
        String host=field(alias,"databaseHost"),port=field(alias,"databasePort"),name=field(alias,"databaseName");
        String endpoints="coalesce((SELECT group_concat(value, ', ') FROM (SELECT DISTINCT "+address(associationField("ep","host"),associationField("ep","port"))+" AS value FROM json_each("+endpointArray(alias)+") ep WHERE "+associationField("ep","host")+"<>'')),CASE WHEN "+host+"<>'' THEN "+address(host,port)+" ELSE '' END)";
        return "("+endpoints+" || CASE WHEN "+name+"<>'' THEN ' / ' || "+name+" ELSE '' END)";
    }
    private static String destinationValue(String alias,String key){return key.equals("databaseDestination")?destination(alias):key.equals("databaseName")?field(alias,key):endpointText(alias,key);}
    private static String tuple(String alias){return identified(field(alias,"name"))+" || ' — ' || "+identified("coalesce(nullif("+field(alias,"driverName")+",''),nullif("+driverClass(alias)+",''),'')")+" || ' · ' || "+identified(field(alias,"driverVersion"))+" || CASE WHEN "+field(alias,"databaseVersion")+"<>'' THEN ' · Banco: ' || "+identified(field(alias,"databaseType"))+" || ' ' || "+field(alias,"databaseVersion")+" || ' (' || "+databaseEvidence(alias)+" || ')' ELSE '' END"+" || ' · Destino configurado: ' || "+identified(destination(alias));}
    private static String databaseEvidence(String alias){return "CASE WHEN lower("+field(alias,"databaseVersionEvidence")+")='application-reported' THEN 'Informado pela aplicação' WHEN "+field(alias,"databaseVersionEvidence")+"='' THEN 'Origem não identificada' ELSE "+field(alias,"databaseVersionEvidence")+" END";}
    private static String serverAssociation(String ds,String server){
        String id=field(server,"id"),otherId=field(ds,"serverId"),nestedId=associationField("assoc","serverId");
        String hasId="("+otherId+"<>'' OR EXISTS (SELECT 1 FROM json_each("+associationArray(ds)+") assoc WHERE "+nestedId+"<>''))";
        String matchId="("+otherId+"="+id+" OR EXISTS (SELECT 1 FROM json_each("+associationArray(ds)+") assoc WHERE "+nestedId+"="+id+"))";
        String name=field(server,"name"),pid=field(server,"pid"),otherPid=field(ds,"pid"),otherName=field(ds,"serverName");
        String direct="(CASE WHEN CAST("+pid+" AS INTEGER)>0 AND CAST("+otherPid+" AS INTEGER)>0 THEN CAST("+pid+" AS INTEGER)=CAST("+otherPid+" AS INTEGER) ELSE "+name+"<>'' AND "+name+"="+otherName+" END)";
        String nestedPid=associationField("assoc","pid"),nestedName=associationField("assoc","serverName");
        String legacy="("+direct+" OR EXISTS (SELECT 1 FROM json_each("+associationArray(ds)+") assoc WHERE CASE WHEN CAST("+pid+" AS INTEGER)>0 AND CAST("+nestedPid+" AS INTEGER)>0 THEN CAST("+pid+" AS INTEGER)=CAST("+nestedPid+" AS INTEGER) ELSE "+name+"<>'' AND "+name+"="+nestedName+" END))";
        // An explicit relationship is authoritative; a same-name server in another domain must never inherit it.
        return "(CASE WHEN "+hasId+" THEN "+id+"<>'' AND "+matchId+" ELSE "+legacy+" END)";
    }
    private static String runtimeAssociation(String runtime,String server){String home=field(server,"javaHome");return "("+home+"<>'' AND rtrim("+field(runtime,"javaHome")+",'/')=rtrim("+home+",'/'))";}
    private static String serverJava(){return "(SELECT group_concat(DISTINCT "+field("j","version")+") FROM inventory j WHERE j.host_id=h.id AND j.kind='runtimes' AND "+runtimeAssociation("j","i")+")";}
    private String jdbcAggregate(Selection f,List<Object> args,boolean linked,boolean json){
        return jdbcAggregate(f,args,linked,json,"jdbc");
    }
    private String jdbcAggregate(Selection f,List<Object> args,boolean linked,boolean json,String key){
        var predicates=resourceFilters(f,"datasources","a",args);
        String expression=key.equals("jdbc")?tuple("a"):identified(field("a","name"))+" || ' → ' || "+identified(destinationValue("a",key));
        return "(SELECT "+(json?"json_group_array(label)":"group_concat(label,char(10))")+" FROM (SELECT DISTINCT "+expression+" AS label FROM inventory a WHERE a.host_id=h.id AND a.kind='datasources'"+(linked?" AND "+serverAssociation("a","i"):"")+(predicates.isEmpty()?"":" AND "+String.join(" AND ",predicates))+" ORDER BY label))";
    }
    private static void term(List<String> where,List<Object> args,String expression,String value,boolean exact){
        if(value==null)return;
        where.add("assessment_lower("+expression+")"+(exact?" = assessment_lower(?)":" LIKE assessment_lower(?) ESCAPE '\\'"));
        args.add(exact?value:"%"+InventoryQueries.escapeLike(value)+"%");
    }
    private static List<String> resourceFilters(Selection f,String kind,String alias,List<Object> args){
        var w=new ArrayList<String>();
        if(kind.equals("runtimes"))term(w,args,alias+(f.filters.getOrDefault("javaVersion","").matches("[0-9]+")?".major":".version"),f.filters.get("javaVersion"),true);
        if(kind.equals("servers")){
            term(w,args,alias+".type",f.filters.get("serverType"),false);
            term(w,args,alias+".version",f.filters.get("serverVersion"),true);
        }
        if(kind.equals("datasources")){
            term(w,args,field(alias,"name"),f.filters.get("datasource"),false);
            term(w,args,"("+field(alias,"driverName")+" || ' ' || "+field(alias,"driverClass")+")",f.filters.get("driver"),false);
            term(w,args,field(alias,"driverVersion"),f.filters.get("driverVersion"),true);
            term(w,args,field(alias,"databaseVersion"),f.filters.get("databaseVersion"),true);
            for(String key:List.of("databaseType","driverReference","resourceType"))term(w,args,field(alias,key),f.filters.get(key),false);
            term(w,args,driverClass(alias),f.filters.get("driverClass"),false);
            term(w,args,field(alias,"state"),f.filters.get("datasourceState"),false);
            for(String key:List.of("serverName","applicationName"))term(w,args,associatedText(alias,key),f.filters.get(key),false);
            term(w,args,field(alias,"databaseName"),f.filters.get("databaseName"),false);
            // Host and port, when both selected, must match the same endpoint.
            if(f.filters.containsKey("databaseHost")||f.filters.containsKey("databasePort")){
                var direct=new ArrayList<String>();var nested=new ArrayList<String>();
                for(String key:List.of("databaseHost","databasePort"))term(direct,args,field(alias,key),f.filters.get(key),key.equals("databasePort"));
                for(String key:List.of("databaseHost","databasePort"))term(nested,args,associationField("ep",key.equals("databaseHost")?"host":"port"),f.filters.get(key),key.equals("databasePort"));
                w.add("(("+String.join(" AND ",direct)+") OR EXISTS (SELECT 1 FROM json_each("+endpointArray(alias)+") ep WHERE "+associationField("ep","host")+"<>'' AND "+String.join(" AND ",nested)+"))");
            }
        }
        return w;
    }
    private String aggregate(String kind,String expression,Selection selection,List<Object> args,boolean count){
        var predicates=resourceFilters(selection,kind,"a",args);
        return "(SELECT "+(count?"count(*)":"group_concat(DISTINCT "+expression+")")+" FROM inventory a WHERE a.host_id=h.id AND a.kind='"+kind+"'"+(predicates.isEmpty()?"":" AND "+String.join(" AND ",predicates))+")";
    }
    private static String identified(String expr){return "coalesce(nullif("+expr+",''),'Não identificada')";}
    Map<String,Object> query(Map<String,String> input,boolean all){
        Selection f=new Selection(input);
        String kind=switch(f.model){case "java"->"runtimes";case "servers"->"servers";case "jdbc"->"datasources";default->"";};
        var args=new ArrayList<Object>();var select=new ArrayList<String>();
        select.add("h.id AS _hostId");select.add("h.environment_id AS _envId");
        select.add("coalesce(nullif("+field("h","hostname")+",''),h.id) AS machine");select.add("coalesce(nullif(e.name,''),'Sem ambiente') AS environment");
        select.add("coalesce(nullif("+field("h","distribution")+",''),nullif("+field("h","osName")+",''),'Não identificado') AS os");
        for(String key:List.of("architecture","logicalCpus","collectorVersion","ipAddresses","memoryTotalBytes"))select.add((key.equals("ipAddresses")?"coalesce(json_extract(h.payload,'$.ipAddresses'),'')":field("h",key))+" AS "+switch(key){case "logicalCpus"->"cpus";case "collectorVersion"->"agentVersion";case "ipAddresses"->"_ips";case "memoryTotalBytes"->"_memory";default->key;});
        if(kind.isEmpty()||kind.equals("servers")){
            if(f.columns.contains("javaVersions"))select.add(aggregate("runtimes",identified(field("a","version")),f,args,false)+" AS javaVersions");
            if(f.columns.contains("servers"))select.add(aggregate("servers",identified(field("a","type"))+" || ' ' || "+identified(field("a","version")),f,args,false)+" AS servers");
            if(f.columns.contains("datasources"))select.add(aggregate("datasources",identified(field("a","name")),f,args,false)+" AS datasources");
            if(f.columns.contains("drivers"))select.add(aggregate("datasources",identified("coalesce(nullif("+field("a","driverName")+",''),nullif("+field("a","driverClass")+",''),'')")+" || ' ' || "+identified(field("a","driverVersion")),f,args,false)+" AS drivers");
            if(f.model.equals("environments"))for(String[] pair:new String[][]{{"runtimes","javaCount"},{"servers","serverCount"},{"datasources","datasourceCount"}})select.add(aggregate(pair[0],"",f,args,true)+" AS "+pair[1]);
        }
        if(!kind.isEmpty()){
            select.add("i.id AS _recordId");
            if(kind.equals("runtimes")){select.add(field("i","version")+" AS version");select.add(field("i","javaHome")+" AS javaHome");select.add(field("i","vendor")+" AS vendor");}
            if(kind.equals("servers")){select.add(field("i","type")+" AS product");select.add(field("i","version")+" AS version");select.add(field("i","name")+" AS name");select.add(field("i","javaHome")+" AS javaHome");}
            if(kind.equals("datasources"))for(String[] pair:new String[][]{{"name","datasource"},{"driverName","driver"},{"driverVersion","driverVersion"},{"driverClass","driverClass"},{"serverName","serverName"}})select.add((pair[0].equals("serverName")?associatedText("i","serverName"):pair[0].equals("driverClass")?driverClass("i"):field("i",pair[0]))+" AS "+pair[1]);
        }
        if(f.columns.contains("jdbc"))select.add((kind.equals("datasources")?tuple("i"):jdbcAggregate(f,args,kind.equals("servers"),f.model.equals("environments")))+" AS jdbc");
        for(String key:List.of("databaseHost","databasePort","databaseName","databaseDestination"))if(f.columns.contains(key))select.add((kind.equals("datasources")?destinationValue("i",key):jdbcAggregate(f,args,kind.equals("servers"),f.model.equals("environments"),key))+" AS "+key);
        if(f.columns.contains("hostJdbc"))select.add(jdbcAggregate(f,args,false,false)+" AS hostJdbc");
        if(kind.equals("servers")){
            select.add(serverJava()+" AS javaVersion");select.add(field("i","path")+" AS path");select.add(field("i","pid")+" AS pid");
        }
        if(kind.equals("datasources")){
            for(String key:List.of("databaseType","databaseVersion","driverReference","resourceType","configuredMaxPoolSize","datasourceKey","poolName","configurationResolution"))select.add(field("i",key)+" AS "+key);
            select.add(databaseEvidence("i")+" AS databaseVersionEvidence");
            select.add("CASE WHEN upper("+field("i","state")+")='APPLICATION_REPORTED' THEN 'Informado pela aplicação' ELSE "+field("i","state")+" END AS datasourceState");select.add(associatedText("i","applicationName")+" AS applicationName");
        }
        var where=new ArrayList<String>();
        String env=f.filters.get("environmentId");if(env!=null){where.add("h.environment_id=?");args.add(env.equals("__unassigned__")?"":env);}
        term(where,args,"(h.hostname || ' ' || "+field("h","fqdn")+" || ' ' || coalesce((SELECT group_concat(value) FROM json_each(h.payload,'$.ipAddresses') WHERE type='text'),''))",f.filters.get("machine"),false);
        term(where,args,"("+field("h","distribution")+" || ' ' || "+field("h","osName")+")",f.filters.get("os"),false);
        term(where,args,field("h","architecture"),f.filters.get("architecture"),false);
        for(String resource:List.of("runtimes","servers","datasources")){
            var conditions=resourceFilters(f,resource,resource.equals(kind)?"i":"r",args);
            if(resource.equals("datasources")){
                boolean missing="missing".equals(f.filters.get("jdbcPresence")),present="present".equals(f.filters.get("jdbcPresence"));
                if(resource.equals(kind)){if(missing)where.add("0=1");if(!conditions.isEmpty())where.add("("+String.join(" AND ",conditions)+")");}
                else{
                    String base="SELECT 1 FROM inventory r WHERE r.host_id=h.id AND r.kind='datasources'"+(kind.equals("servers")?" AND "+serverAssociation("r","i"):"");
                    if(missing)where.add("NOT EXISTS ("+base+")");
                    if(present||!conditions.isEmpty())where.add("EXISTS ("+base+(conditions.isEmpty()?"":" AND "+String.join(" AND ",conditions))+")");
                }
            }else if(!conditions.isEmpty())where.add(resource.equals(kind)?"("+String.join(" AND ",conditions)+")":"EXISTS (SELECT 1 FROM inventory r WHERE r.host_id=h.id AND r.kind='"+resource+"'"+(resource.equals("runtimes")&&kind.equals("servers")?" AND "+runtimeAssociation("r","i"):"")+" AND "+String.join(" AND ",conditions)+")");
        }
        String from=" FROM hosts h LEFT JOIN environments e ON e.id=h.environment_id"+(!kind.isEmpty()?" JOIN inventory i ON i.host_id=h.id AND i.kind='"+kind+"'":"");
        // Fetch only selected columns and bound each SQL text result before it reaches the JVM.
        var projection=new ArrayList<String>();
        for(String expression:select){
            int split=expression.lastIndexOf(" AS ");String key=expression.substring(split+4),body=expression.substring(0,split);
            boolean needed=Set.of("_hostId","_envId").contains(key)||f.columns.contains(key)||key.equals("_ips")&&f.columns.contains("ip")||key.equals("_memory")&&f.columns.contains("memory")||f.model.equals("environments")&&Set.of("environment","javaCount","serverCount","datasourceCount").contains(key);
            if(!needed)continue;
            if(!Set.of("_memory","javaCount","serverCount","datasourceCount").contains(key))body="substr(CAST(("+body+") AS TEXT),1,"+(MAX_CELL+1)+")";
            projection.add(body+" AS "+key);
        }
        var hosts=new HashSet<String>();var environments=new HashSet<String>();var grouped=new LinkedHashMap<String,Map<String,Object>>();var envJdbc=new LinkedHashMap<String,Set<String>>();
        var records=new ArrayList<Map<String,Object>>();long[] chars={0};int[] read={0};
        db.forEachRow("SELECT "+String.join(",",projection)+from+(where.isEmpty()?"":" WHERE "+String.join(" AND ",where))+" ORDER BY lower(h.hostname),h.id"+(!kind.isEmpty()?",i.id":"")+" LIMIT "+(MAX_ROWS+1),source->{
            if(++read[0]>MAX_ROWS)throw new Failure(413,"O relatório excede 10.000 registros. Refine os filtros antes de gerar ou exportar.");
            for(Object value:source.values())if(value instanceof String text&&text.length()>MAX_CELL)throw new Failure(413,"Uma célula excede o limite de 8.192 caracteres. Escolha um modelo mais detalhado ou refine os filtros.");
            hosts.add(s(source.get("_hostId")));environments.add(s(source.get("_envId")));
            source.put("ip",networkIps(s(source.get("_ips"))));
            Object memory=source.get("_memory");source.put("memory",memory instanceof Number?String.format(Locale.forLanguageTag("pt-BR"),"%.1f",((Number)memory).doubleValue()/1073741824d):"");
            if(f.model.equals("environments")){
                var row=grouped.computeIfAbsent(s(source.get("_envId")),key->map("environment",source.get("environment"),"machines",0L,"javaCount",0L,"serverCount",0L,"datasourceCount",0L));
                row.put("machines",((Number)row.get("machines")).longValue()+1);
                for(String jdbcKey:List.of("jdbc","databaseHost","databasePort","databaseName","databaseDestination"))if(f.columns.contains(jdbcKey)){
                    Set<String> tuples=envJdbc.computeIfAbsent(s(source.get("_envId"))+"/"+jdbcKey,key->new LinkedHashSet<>());
                    Object entries=Json.parse(s(source.get(jdbcKey)));
                    if(entries instanceof List<?> values)for(Object value:values)if(value instanceof String text){tuples.add(text);if(tuples.stream().mapToInt(String::length).sum()+tuples.size()>MAX_CELL)throw new Failure(413,"JDBC do ambiente excede 8.192 caracteres. Refine os filtros ou use o relatório JDBC.");}
                    row.put(jdbcKey,String.join("\n",tuples));
                }
                for(String key:List.of("javaCount","serverCount","datasourceCount"))row.put(key,((Number)row.get(key)).longValue()+((Number)source.get(key)).longValue());
            }else{
                var row=new LinkedHashMap<String,Object>();
                for(String key:f.columns){String value=s(source.get(key));if(value.isEmpty())value="Não identificada";if(value.length()>MAX_CELL)throw new Failure(413,"Uma célula excede o limite de 8.192 caracteres. Escolha um modelo mais detalhado ou refine os filtros.");chars[0]+=value.length();row.put(key,value);}
                records.add(row);
            }
            if(chars[0]>7*1024*1024)throw new Failure(413,"Relatório muito grande. Refine os filtros antes de exportar.");
        },args.toArray());
        if(f.model.equals("environments")&&f.filters.keySet().stream().allMatch(k->k.equals("environmentId"))){
            for(var environment:db.rows("SELECT id,name FROM environments ORDER BY name,id")){
                String id=s(environment.get("id"));if(env!=null&&!env.equals(id))continue;
                environments.add(id);grouped.putIfAbsent(id,map("environment",environment.get("name"),"machines",0L,"javaCount",0L,"serverCount",0L,"datasourceCount",0L));
            }
        }
        if(f.model.equals("environments"))for(var group:grouped.values()){
            if(records.size()>=MAX_ROWS)throw new Failure(413,"O relatório excede 10.000 registros. Refine os filtros antes de gerar ou exportar.");
            var row=new LinkedHashMap<String,Object>();
            for(String key:f.columns){String value=s(group.get(key));if(key.equals("jdbc")&&value.isEmpty())value="Não identificado";if(value.length()>MAX_CELL)throw new Failure(413,"Uma célula excede o limite de 8.192 caracteres. Refine os filtros.");chars[0]+=value.length();row.put(key,value);}
            if(chars[0]>7*1024*1024)throw new Failure(413,"Relatório muito grande. Refine os filtros antes de exportar.");
            records.add(row);
        }
        var filterLabels=new ArrayList<Map<String,Object>>();
        for(var entry:f.filters.entrySet()){
            String value=entry.getValue();
            if(entry.getKey().equals("environmentId")){var names=db.rows("SELECT name FROM environments WHERE id=?",value);value=value.equals("__unassigned__")?"Sem ambiente":names.isEmpty()?"Ambiente não encontrado":s(names.get(0).get("name"));}
            if(entry.getKey().equals("jdbcPresence"))value=value.equals("present")?"Com JDBC no inventário":"Sem JDBC no inventário";
            filterLabels.add(map("label",FILTERS.get(entry.getKey()),"value",value));
        }
        var summary=map("machines",hosts.size(),"environments",environments.size(),"records",records.size());
        String revision=InventoryStore.hash(Json.stringify(map("model",f.model,"filters",filterLabels,"columns",f.columns,"rows",records,"summary",summary)));
        if(all&&!revision.equals(input.getOrDefault("revision","")))throw new Failure(409,"O inventário ou as colunas mudaram. Atualize o relatório antes de exportar.");
        int total=records.size(),pages=Math.max(1,(total+f.size-1)/f.size),page=Math.min(f.page,pages);
        return map("title",TITLES.get(f.model),"context",context(f.model),"generatedAt",Instant.now().toString(),"filters",filterLabels,"columns",modelColumns(f.columns,f.model),"availableColumns",modelColumns(COLUMNS.get(f.model),f.model),"rows",all?records:records.subList((page-1)*f.size,Math.min(page*f.size,total)),"summary",summary,"revision",revision,"pagination",map("page",page,"pageSize",f.size,"total",total,"pages",pages));
    }
    static String networkIps(String raw){
        var v4=new LinkedHashSet<String>();var v6=new LinkedHashSet<String>();
        try{Object value=Json.parse(raw);if(value instanceof List<?> list)for(Object item:list){String ip=s(item).strip(),base=ip.split("%",2)[0].toLowerCase(Locale.ROOT);if(base.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")){String[] bits=base.split("\\.");boolean valid=Arrays.stream(bits).allMatch(n->Integer.parseInt(n)<=255);if(valid&&!bits[0].equals("127")&&!base.equals("0.0.0.0"))v4.add(ip);}else if(base.contains(":")&&base.matches("[0-9a-f:.]+")){try{byte[] bytes=java.net.InetAddress.getByName(base).getAddress();boolean loop=true;for(int i=0;i<bytes.length-1;i++)if(bytes[i]!=0)loop=false;if(loop&&(bytes[bytes.length-1]==0||bytes[bytes.length-1]==1))continue;if(bytes.length==16&&(bytes[0]&255)==254&&(bytes[1]&192)==128)continue;if(bytes.length==4&&(bytes[0]&255)==127)continue;v6.add(ip);}catch(Exception ignored){}}}}catch(Exception ignored){}
        v4.addAll(v6);return String.join("; ",v4);
    }
    @SuppressWarnings("unchecked") static void csv(Writer out,Map<String,Object> report)throws IOException{
        out.write('\ufeff');var columns=(List<Map<String,Object>>)report.get("columns");var rows=(List<Map<String,Object>>)report.get("rows");
        csvLine(out,columns.stream().map(c->s(c.get("label"))).toList());
        for(var row:rows)csvLine(out,columns.stream().map(c->s(row.get(s(c.get("key"))))).toList());
    }
    static void csvLine(Writer out,List<String> values)throws IOException{
        for(int i=0;i<values.size();i++){if(i>0)out.write(',');String value=values.get(i);if(formula(value))value="'"+value;out.write('"');out.write(value.replace("\"","\"\""));out.write('"');}out.write("\r\n");
    }
    private static boolean formula(String value){
        for(int offset=0;offset<value.length();){int c=value.codePointAt(offset);if(c=='\t'||c=='\r'||c=='\n'||c==0xfeff)return true;if(Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)){offset+=Character.charCount(c);continue;}return c=='='||c=='+'||c=='-'||c=='@';}return false;
    }

}
