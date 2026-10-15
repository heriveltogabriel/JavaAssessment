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
        String[][] labels={{"machine","Máquina"},{"environment","Ambiente"},{"ip","IP de rede"},{"os","Sistema operacional"},{"architecture","Arquitetura"},{"cpus","CPUs lógicas"},{"memory","Memória (GiB)"},{"agentVersion","Versão do agente"},{"javaVersions","Versões Java"},{"servers","Servidores da máquina"},{"datasources","Datasources da máquina"},{"drivers","Drivers JDBC da máquina"},{"version","Versão"},{"javaHome","Java home"},{"vendor","Fornecedor Java"},{"product","Servidor Java"},{"name","Nome do servidor"},{"datasource","Datasource"},{"driver","Driver JDBC"},{"driverClass","Classe do driver"},{"driverVersion","Versão do driver"},{"serverName","Servidor associado"},{"machines","Máquinas"},{"javaCount","Instalações Java"},{"serverCount","Servidores Java"},{"datasourceCount","Datasources JDBC"}};
        for(String[] l:labels)LABELS.put(l[0],l[1]);
        model("machines","Inventário de máquinas","machine,environment,ip,os,javaVersions,servers,datasources,drivers,architecture,cpus,memory,agentVersion","machine,environment,ip,os,javaVersions,servers,datasources,drivers");
        model("java","Instalações Java","machine,environment,version,javaHome,vendor,os,architecture,ip","machine,environment,version,javaHome,vendor");
        model("servers","Java Servers","machine,environment,product,name,version,os,ip,javaHome,datasources,drivers","machine,environment,product,name,version");
        model("jdbc","Datasources e drivers JDBC","machine,environment,datasource,driver,driverVersion,driverClass,serverName,os,ip","machine,environment,datasource,driver,driverVersion,driverClass,serverName");
        model("environments","Resumo por ambiente","environment,machines,javaCount,serverCount,datasourceCount","environment,machines,javaCount,serverCount,datasourceCount");
        for(String[] f:new String[][]{{"environmentId","Ambiente"},{"machine","Máquina"},{"os","Sistema operacional"},{"architecture","Arquitetura"},{"javaVersion","Versão Java"},{"serverType","Servidor Java"},{"serverVersion","Versão do servidor"},{"datasource","Datasource"},{"driver","Driver JDBC"},{"driverVersion","Versão do driver"}})FILTERS.put(f[0],f[1]);
    }
    private static void model(String key,String title,String columns,String defaults){TITLES.put(key,title);COLUMNS.put(key,List.of(columns.split(",")));DEFAULTS.put(key,List.of(defaults.split(",")));}
    static String s(Object value){return value==null?"":String.valueOf(value);}
    static List<Map<String,Object>> columns(List<String> keys){return keys.stream().map(k->map("key",k,"label",LABELS.get(k))).toList();}
    final InventoryStore db;
    Reports(InventoryStore db){this.db=db;}
    Map<String,Object> options(){
        return map("models",TITLES.keySet().stream().map(k->map("key",k,"label",TITLES.get(k),"columns",columns(COLUMNS.get(k)),"defaults",DEFAULTS.get(k))).toList(),
            "environments",db.rows("SELECT id,name FROM environments ORDER BY name,id"));
    }
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
            String raw=input.getOrDefault("columns","");columns=raw.isEmpty()?DEFAULTS.get(model):List.of(raw.split(",",-1));
            if(columns.isEmpty()||columns.size()>COLUMNS.get(model).size()||new HashSet<>(columns).size()!=columns.size()||!COLUMNS.get(model).containsAll(columns))throw new Failure(400,"Selecione colunas válidas para este modelo.");
            page=integer(input.getOrDefault("page","1"),1,10000);size=integer(input.getOrDefault("pageSize","20"),1,100);
        }
        private static int integer(String value,int min,int max){try{int n=Integer.parseInt(value);if(n>=min&&n<=max)return n;}catch(NumberFormatException ignored){}throw new Failure(400,"Paginação inválida.");}
    }
    static String field(String alias,String key){return "CASE WHEN json_type("+alias+".payload,'$."+key+"') IN ('text','integer','real') THEN json_extract("+alias+".payload,'$."+key+"') ELSE '' END";}
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
            if(kind.equals("datasources"))for(String[] pair:new String[][]{{"name","datasource"},{"driverName","driver"},{"driverVersion","driverVersion"},{"driverClass","driverClass"},{"serverName","serverName"}})select.add(field("i",pair[0])+" AS "+pair[1]);
        }
        var where=new ArrayList<String>();
        String env=f.filters.get("environmentId");if(env!=null){where.add("h.environment_id=?");args.add(env.equals("__unassigned__")?"":env);}
        term(where,args,"(h.hostname || ' ' || "+field("h","fqdn")+" || ' ' || coalesce((SELECT group_concat(value) FROM json_each(h.payload,'$.ipAddresses') WHERE type='text'),''))",f.filters.get("machine"),false);
        term(where,args,"("+field("h","distribution")+" || ' ' || "+field("h","osName")+")",f.filters.get("os"),false);
        term(where,args,field("h","architecture"),f.filters.get("architecture"),false);
        for(String resource:List.of("runtimes","servers","datasources")){
            var conditions=resourceFilters(f,resource,resource.equals(kind)?"i":"r",args);
            if(!conditions.isEmpty())where.add(resource.equals(kind)?"("+String.join(" AND ",conditions)+")":"EXISTS (SELECT 1 FROM inventory r WHERE r.host_id=h.id AND r.kind='"+resource+"' AND "+String.join(" AND ",conditions)+")");
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
        var hosts=new HashSet<String>();var environments=new HashSet<String>();var grouped=new LinkedHashMap<String,Map<String,Object>>();
        var records=new ArrayList<Map<String,Object>>();long[] chars={0};int[] read={0};
        db.forEachRow("SELECT "+String.join(",",projection)+from+(where.isEmpty()?"":" WHERE "+String.join(" AND ",where))+" ORDER BY lower(h.hostname),h.id"+(!kind.isEmpty()?",i.id":"")+" LIMIT "+(MAX_ROWS+1),source->{
            if(++read[0]>MAX_ROWS)throw new Failure(413,"O relatório excede 10.000 registros. Refine os filtros antes de gerar ou exportar.");
            for(Object value:source.values())if(value instanceof String text&&text.length()>MAX_CELL)throw new Failure(413,"Uma célula excede o limite de 8.192 caracteres. Escolha um modelo mais detalhado ou refine os filtros.");
            hosts.add(s(source.get("_hostId")));environments.add(s(source.get("_envId")));
            source.put("ip",networkIps(s(source.get("_ips"))));
            Object memory=source.get("_memory");source.put("memory",memory instanceof Number?String.format(Locale.ROOT,"%.1f",((Number)memory).doubleValue()/1073741824d):"");
            if(f.model.equals("environments")){
                var row=grouped.computeIfAbsent(s(source.get("_envId")),key->map("environment",source.get("environment"),"machines",0L,"javaCount",0L,"serverCount",0L,"datasourceCount",0L));
                row.put("machines",((Number)row.get("machines")).longValue()+1);
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
            for(String key:f.columns){String value=s(group.get(key));if(value.length()>MAX_CELL)throw new Failure(413,"Uma célula excede o limite de 8.192 caracteres. Refine os filtros.");chars[0]+=value.length();row.put(key,value);}
            if(chars[0]>7*1024*1024)throw new Failure(413,"Relatório muito grande. Refine os filtros antes de exportar.");
            records.add(row);
        }
        var filterLabels=new ArrayList<Map<String,Object>>();
        for(var entry:f.filters.entrySet()){
            String value=entry.getValue();
            if(entry.getKey().equals("environmentId")){var names=db.rows("SELECT name FROM environments WHERE id=?",value);value=value.equals("__unassigned__")?"Sem ambiente":names.isEmpty()?"Ambiente não encontrado":s(names.get(0).get("name"));}
            filterLabels.add(map("label",FILTERS.get(entry.getKey()),"value",value));
        }
        var summary=map("machines",hosts.size(),"environments",environments.size(),"records",records.size());
        String revision=InventoryStore.hash(Json.stringify(map("model",f.model,"filters",filterLabels,"columns",f.columns,"rows",records,"summary",summary)));
        if(all&&!revision.equals(input.getOrDefault("revision","")))throw new Failure(409,"O inventário ou as colunas mudaram. Atualize o relatório antes de exportar.");
        int total=records.size(),pages=Math.max(1,(total+f.size-1)/f.size),page=Math.min(f.page,pages);
        return map("title",TITLES.get(f.model),"generatedAt",Instant.now().toString(),"filters",filterLabels,"columns",columns(f.columns),"availableColumns",columns(COLUMNS.get(f.model)),"rows",all?records:records.subList((page-1)*f.size,Math.min(page*f.size,total)),"summary",summary,"revision",revision,"pagination",map("page",page,"pageSize",f.size,"total",total,"pages",pages));
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
