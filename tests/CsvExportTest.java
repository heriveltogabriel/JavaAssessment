package assessment;

import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Local SQLite export fixtures; no server, credentials or remote machines are used. */
public final class CsvExportTest {
    private static final String TIME="2026-10-13T12:00:00Z";
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static Map<String,Object> host(String id,String environment,String name) {
        return map("id",id,"environmentId",environment,"hostname",name,"osName","Linux","distribution","Oracle Linux",
            "architecture","x86_64","collectorVersion","1.5.0","collectorJavaVersion","17.0.20","logicalCpus",8,"memoryTotalBytes",4294967296L,
            "lastSeen",TIME,"lastAssessmentAt",TIME,"coverage",map("status","partial","secret","COVERAGE_SECRET"),
            "ipAddresses",List.of("192.0.2.1","2001:db8::1"),"agentToken","HOST_SECRET","uninstall",map("completionToken","UNINSTALL_SECRET","requestKey","REQUEST_SECRET"));
    }
    private static Map<String,Object> inventory(String host,String id) {
        return map("id",id,"hostId",host,"name",id,"version","17.0.20","kind","JDK","javaHome","/opt/jdk","inUse",true,"observedAt",TIME,"secret","INVENTORY_SECRET");
    }
    private static String export(InventoryStore store,InventoryQueries.Scope scope) {
        return store.readSnapshot(()->{StringWriter out=new StringWriter();CsvExport.write(out,store,new InventoryQueries(store),scope);return out.toString();});
    }
    private static InventoryQueries.Scope scope(String environment,String host) {InventoryQueries.Scope result=new InventoryQueries.Scope();result.environment=environment;result.host=host;return result;}

    /** Independent CSV reader exercises escaped quotes, commas and embedded CR/LF. */
    private static List<Map<String,String>> parse(String csv) {
        check(csv.startsWith("\uFEFF"),"UTF-8 BOM marker present");
        List<List<String>> records=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=1;i<csv.length();i++) {
            char ch=csv.charAt(i);
            if(ch=='"') {
                if(quoted&&i+1<csv.length()&&csv.charAt(i+1)=='"'){cell.append('"');i++;}
                else quoted=!quoted;
            }else if(!quoted&&ch==','){row.add(cell.toString());cell.setLength(0);}
            else if(!quoted&&ch=='\r') {
                check(i+1<csv.length()&&csv.charAt(++i)=='\n',"record terminators are CRLF");row.add(cell.toString());records.add(row);row=new ArrayList<>();cell.setLength(0);
            }else {check(quoted||ch!='\n',"no bare record LF");cell.append(ch);}
        }
        check(!quoted&&row.isEmpty()&&cell.length()==0,"CSV ends after a complete record");
        List<String> columns=records.get(0);check(columns.equals(List.of("Tipo de registro","Ambiente","ID do ambiente","Máquina","ID da máquina","ID do registro","Nome","Versão","Tipo","Caminho","Estado","Observado em","Fornecedor","Diretório Java","Em uso","ID da aplicação","Aplicação","Servidor Java","Categoria","Uso","Evidência","Nível de evidência","Versão do framework","Driver JDBC","Versão do driver","Tipo de banco","Origem da configuração","Pool máximo configurado","Capacidade do pool","Conexões disponíveis","Conexões ativas","PID","Classe principal","Módulo","Sistema operacional","Distribuição","Arquitetura","Endereços IP","Versão do agente","Versão do Java do agente","Ciclo de vida do agente","Último contato","Última coleta","Estado da cobertura","CPUs lógicas","Memória total (bytes)","Criado em","Iniciado em","Concluído em","Resultado","Host/IP do banco (configurado)","Portas do banco (configuradas)","Banco / serviço / SID (configurado)","Destino do banco (configurado)","IDs dos servidores associados","Domínio WebLogic","Caminho do domínio","Arquivo do driver","Identificação do driver","Evidência da versão do driver","Drivers candidatos","Fonte da versão do driver","Motivo da identificação do driver")),"Portuguese column labels and ordering");columns=List.of("record_type","environment","environment_id","hostname","host_id","record_id","name","version","type","path","state","observed_at","vendor","java_home","in_use","application_id","application_name","server_name","category","usage","evidence","evidence_level","framework_version","driver_name","driver_version","database_type","configuration_source","configured_max_pool_size","pool_capacity","pool_available","active_connections","pid","main_class","module_name","os","distribution","architecture","ip_addresses","collector_version","collector_java_version","agent_lifecycle","last_seen","last_assessment_at","coverage_status","logical_cpus","memory_total_bytes","created_at","started_at","finished_at","summary","database_host","database_port","database_name","database_destination","server_ids","domain_name","domain_path","driver_artifact","driver_resolution","driver_version_evidence","driver_candidates","driver_version_source","driver_resolution_reason");check(new HashSet<>(columns).size()==columns.size(),"unique columns");
        List<Map<String,String>> result=new ArrayList<>();
        for(List<String> values:records.subList(1,records.size())) {
            check(values.size()==columns.size(),"every row has all fixed columns");Map<String,String> item=new LinkedHashMap<>();
            for(int i=0;i<columns.size();i++)item.put(columns.get(i),values.get(i));result.add(item);
        }
        return result;
    }
    private static Map<String,String> find(List<Map<String,String>> rows,String kind,String id) {
        return rows.stream().filter(row->row.get("record_type").equals(kind)&&row.get("record_id").equals(id)).findFirst().orElseThrow();
    }
    private static void fixtures(InventoryStore store) {
        store.put("environments",map("id","env-a","name","Produção, \"São Paulo\"","agentToken","ENV_SECRET"));
        store.put("environments",map("id","env-b","name","Homologação"));
        store.put("environments",map("id","env-empty","name","Empty"));
        store.put("hosts",host("h-a","env-a","Máquina, \"A\""));store.put("hosts",host("h-b","env-b","B"));store.put("hosts",host("h-u","","Unassigned"));
        store.put("identities",map("hostId","h-a","installationId","INSTALLATION_SECRET","agentToken","IDENTITY_SECRET","status","active"));
        store.put("enrollments",map("id","enrollment","tokenHash","ENROLLMENT_SECRET","status","active","expiresMillis",9999999999999L));
        store.put("uninstall_requests",map("id","uninstall","hostId","h-a","requestKey","REGISTRY_REQUEST_SECRET","status","UNINSTALLING","completionToken","REGISTRY_COMPLETION_SECRET"));
        List<Object> runtimes=new ArrayList<>();for(int i=0;i<205;i++)runtimes.add(inventory("h-a","runtime-"+i));
        Map<String,Object> special=inventory("h-a","runtime-special");special.put("name","Aplicação, \"com aspas\"\nsegunda linha\rterceira");special.put("evidence","Evidence, with \"quotes\"\r\nand newline");special.put("inUse",false);runtimes.add(special);
        String[] formulas={"=1+1","+SUM(A1:A2)","-1+1","@SUM(A1:A2)","  =1+1","\tplain","\rplain","\nplain","\u00A0=1+1","\uFEFF=1+1","  \t=1+1"};
        for(int i=0;i<formulas.length;i++){Map<String,Object> item=inventory("h-a","formula-"+i);item.put("name",formulas[i]);runtimes.add(item);}
        store.putInventory("runtimes","h-a",runtimes);store.putInventory("runtimes","h-b",List.of(inventory("h-b","runtime-b")));store.putInventory("runtimes","h-u",List.of(inventory("h-u","runtime-u")));
        for(String kind:List.of("processes","servers","libraries","applications","datasources")) {
            Map<String,Object> item=inventory("h-a",kind+"-1");item.put("type",kind);item.put("path","/opt/app,with-comma.jar");item.put("state","CONFIGURED");item.put("pid",-17L);item.put("driverName","org.example.Driver");item.put("poolCapacity",23);item.put("poolAvailable",0);item.put("activeConnections",4);item.put("configuredMaxPoolSize",100);item.put("configurationSource","application.properties");
            item.put("associations",List.of(map("applicationId","app-one","applicationName","Aplicação um","serverName","Tomcat","password","ASSOCIATION_SECRET"),map("applicationId","app-two","applicationName","Aplicação dois"),map("applicationId","app-one","applicationName","Aplicação um")));
            item.put("unknown",map("password","NESTED_SECRET"));store.putInventory(kind,"h-a",List.of(item));
        }
        Map<String,Object> malformed=inventory("h-b","malformed-scalars");malformed.remove("javaHome");malformed.put("name",map("password","NAME_MAP_SECRET"));malformed.put("path",List.of("PATH_LIST_SECRET"));malformed.put("evidence",map("agentToken","EVIDENCE_MAP_SECRET"));store.putInventory("libraries","h-b",List.of(malformed));
        store.put("jobs",map("id","job-1","hostId","h-a","status","SUCCEEDED","createdAt",TIME,"finishedAt",TIME,"summary","Completed, with notes","completionToken","JOB_SECRET"));
    }
    private static void verify(InventoryStore store) {
        InventoryQueries.Scope all=scope("","");all.view="libraries";all.q="does-not-exist";all.from="2099-01-01T00:00:00Z";all.to="2099-01-02T00:00:00Z";all.subset="jdbc";all.version="nonexistent";all.page=1000;all.size=1;
        String csv=export(store,all);List<Map<String,String>> rows=parse(csv);
        check(rows.size()==229,"all 229 records exported across pages and categories independent of table filters");
        check(rows.stream().map(row->row.get("record_type")).collect(java.util.stream.Collectors.toSet()).equals(Set.of("hosts","runtimes","processes","servers","libraries","applications","datasources","jobs")),"all eight categories");
        check(rows.stream().filter(row->row.get("record_type").equals("runtimes")).count()==219,"all 219 runtime rows, including all three pages");
        check(new HashSet<>(rows.stream().map(row->row.get("record_type")+":"+row.get("host_id")+":"+row.get("record_id")).toList()).size()==rows.size(),"no skipped or repeated rows");
        check(all.page==1000&&all.size==1&&all.q.equals("does-not-exist"),"input scope left intact");
        Map<String,String> special=find(rows,"runtimes","runtime-special");
        check(special.get("name").equals("Aplicação, \"com aspas\"\nsegunda linha\rterceira"),"Unicode, comma, quotes and embedded line breaks survive");
        check(special.get("evidence").equals("Evidence, with \"quotes\"\r\nand newline"),"quoted evidence survives");
        check(special.get("environment").equals("Produção, \"São Paulo\"")&&special.get("hostname").equals("Máquina, \"A\""),"metadata enrichment and escaping");
        check(special.get("in_use").equals("false")&&find(rows,"runtimes","runtime-0").get("in_use").equals("true"),"boolean values retained");
        check(special.get("ip_addresses").equals("192.0.2.1 | 2001:db8::1"),"IP list rendered as plain text");
        check(special.get("logical_cpus").equals("8")&&special.get("memory_total_bytes").equals("4294967296"),"large host numeric values retained");
        for(int i=0;i<11;i++)check(find(rows,"runtimes","formula-"+i).get("name").startsWith("'"),"text formula/leading control neutralized "+i);
        check(find(rows,"datasources","datasources-1").get("pid").equals("-17")&&csv.contains(",-17,"),"real negative number is numeric, not formula-escaped");
        check(find(rows,"datasources","datasources-1").get("pool_available").equals("0"),"numeric zero retained");
        check(find(rows,"libraries","libraries-1").get("application_id").equals("app-one | app-two")&&find(rows,"libraries","libraries-1").get("application_name").equals("Aplicação um | Aplicação dois"),"associated application fields projected and deduplicated");
        Map<String,String> malformed=find(rows,"libraries","malformed-scalars");check(malformed.get("name").isEmpty()&&malformed.get("path").isEmpty()&&malformed.get("evidence").isEmpty(),"nested values never serialized into scalar fields");
        for(String secret:List.of("HOST_SECRET","UNINSTALL_SECRET","REQUEST_SECRET","REGISTRY_REQUEST_SECRET","REGISTRY_COMPLETION_SECRET","COVERAGE_SECRET","ENV_SECRET","INSTALLATION_SECRET","IDENTITY_SECRET","ENROLLMENT_SECRET","INVENTORY_SECRET","NESTED_SECRET","ASSOCIATION_SECRET","NAME_MAP_SECRET","PATH_LIST_SECRET","EVIDENCE_MAP_SECRET","JOB_SECRET","completionToken","requestKey","tokenHash"))check(!csv.contains(secret),"excludes secret/metadata "+secret);
        List<Map<String,String>> selected=parse(export(store,scope("env-a","")));check(selected.size()==224&&selected.stream().allMatch(row->row.get("environment_id").equals("env-a")&&row.get("host_id").equals("h-a")),"one environment scoped");
        selected=parse(export(store,scope("env-b","")));check(selected.size()==3&&selected.stream().allMatch(row->row.get("host_id").equals("h-b")),"second environment scoped");
        selected=parse(export(store,scope("__unassigned__","")));check(selected.size()==2&&selected.stream().allMatch(row->row.get("host_id").equals("h-u")&&row.get("environment").equals("Sem ambiente")&&row.get("environment_id").isEmpty()),"unassigned scope");
        selected=parse(export(store,scope("","h-b")));check(selected.size()==3&&selected.stream().allMatch(row->row.get("host_id").equals("h-b")),"single host with all-environment scope");
        check(parse(export(store,scope("env-b","h-a"))).isEmpty(),"cross-environment host never leaks rows");
        check(parse(export(store,scope("env-empty",""))).isEmpty(),"empty selected scope has header only");
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("assessment-csv-test-");
        try(InventoryStore store=new InventoryStore(root.toFile(),map(),"","")) {
            check(parse(export(store,scope("", ""))).isEmpty(),"empty fleet produces valid header-only CSV");fixtures(store);verify(store);
            System.out.println("CSV_EXPORT_TESTS=PASS pagination full-scope eight-categories environments unassigned host empty Unicode CRLF quotes formula-defense numeric-types allowlist secret-exclusion");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
