package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

/** Portable inventory only: never serialize the database or operational/account tables. */
final class InventoryArchive {
    static final long MAX_BYTES=512L*1024*1024, MAX_RECORDS=1_000_000;
    static final List<String> COLLECTIONS=List.of("environments","hosts","runtimes","processes","servers","libraries","applications","datasources");
    private static Set<String> keys(String value){return Set.of(value.split(" "));}
    private static final Set<String> ENV=keys("id name");
    private static final Set<String> HOST=keys("id environmentId hostname fqdn osName osVersion kernelVersion distribution architecture ipAddresses logicalCpus cpuModel memoryTotalBytes memoryAvailableBytes memoryFreeBytes collectorVersion collectorJavaVersion collectorJavaVendor lastSeen lastAssessmentAt firstAssessmentAt registeredAt agentLifecycle status observedAt coverage disks");
    private static final Set<String> RECORD=keys("id hostId name version distribution type kind category path javaHome javaVersion javaVendor vendor architecture inUse usage state status observedAt discoveredAt applicationId applicationName applicationType applicationPath applicationPid applicationSource applicationDeployment applicationLinkEvidence serverId serverName serverType serverVersion runtimeId processId pid launchJar mainClass startClass module moduleName frameworkVersion framework applicationFramework springBootVersion springBootLibrary discoveryMethod scanScope evidence evidenceLevel domainName domainPath configurationSource configurationBinding configuredTargets configuredEnabled configuredMaxPoolSize configuredName configurationScope configurationResolution configurationContext configurationEvidence configurationKey configurationObservation activationUnresolved driverName driverVersion driverArtifact driverResolution driverVersionEvidence driverVersionSource driverResolutionReason driverResolutionCaveat driverConfidence driverClass driverClassEvidence driverFamily driverModule driverDeployment driverReference driverReplacedOnDisk databaseType databaseVersion databaseVersionEvidence databaseTypeEvidence databaseEndpointEvidence databaseHost databasePort databaseName databaseEndpoints datasourceKey datasourceObservation jndi jndiName jndiNames jndiEvidence jndiResolution resourceType linkedDatasourceId linkedContexts applicationReportedAtEpochMillis activeProfiles containerized contextIdentity descriptor sourcePath runtimeMetadataSource runtimeReplacedOnDisk runningVersions patches poolCapacity poolAvailable activeConnections capacity available connections deploymentPath deploymentName contextPath context linkName resolvedFrom launcherObservation excludedPathObservation tomcatApplicationOnlyDriver tomcatInstallationEvidence support associations driverResult driverCandidates embeddedServers");
    // Nested objects are purpose-specific. Unknown keys are not carried to a future consumer.
    private static final Set<String> CANDIDATE=keys("path name version versionSource source sourcePath order open loaded family onClasspath replacedOnDisk selected selectionReason precedence evidence origin driverClass driverName driverVersion driverArtifact driverVersionSource driverResolution driverResolutionReason driverResolutionCaveat");
    private static final Set<String> ASSOCIATION=union(RECORD,keys("serverIds applicationIds driverCandidates effectiveClasspathComplete classpathTruncated argumentsTruncated"));
    private static final Set<String> COVERAGE=keys("status warnings stats lastAssessmentAt");
    private static final Set<String> STATS=keys("durationMillis filesVisited bytesRead collectorCpuMillis peakHeapBytes collectorPeakRssBytes archivesInspected scanRoots processScanRoots maxFiles sweepFilesVisited processLinkedFilesVisited");
    private static final Set<String> DISK=keys("mount totalBytes availableBytes");
    private static final Set<String> SUPPORT=keys("status dataReferencia produto fornecedor referencia linha fimPrincipal fimEstendido vigente observacao fonte confidence");
    private static final Set<String> ENDPOINT=keys("host port database service sid name type evidence");
    private static final Pattern PRIVATE_KEY=Pattern.compile("(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|$)");
    private static final Pattern URL_LOGIN=Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@");
    private static final Pattern ORACLE_LOGIN=Pattern.compile("(?i)(jdbc:oracle:[^:]+:)[^/@\\s]+/[^@\\s]+@");
    private static final Pattern AUTH_HEADER=Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern ASSIGNMENT=Pattern.compile("(?i)((?:username|user_name|userid|user_id|user|password|passwd|pwd|secret|token|credential|authorization|api[_-]?key|access[_-]?key)[\"']?\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s;,&)]+)");
    static final class Failure extends RuntimeException {Failure(String message){super(message);}}
    private static Set<String> union(Set<String> a,Set<String> b){Set<String> result=new HashSet<>(a);result.addAll(b);return Set.copyOf(result);}

    static String label(String value){
        String v=value==null?"":value.trim();
        if(v.length()>120||v.chars().anyMatch(c->Character.isISOControl(c)))throw new Failure("A identificação deve ter até 120 caracteres, sem quebras de linha.");
        return scrub(v);
    }
    /** Defense in depth for technical text. Raw JVM arguments, configuration and URLs are excluded by schema. */
    static String scrub(String value){
        String clean=PRIVATE_KEY.matcher(value).replaceAll("[REMOVIDO]");
        clean=URL_LOGIN.matcher(clean).replaceAll("$1[REMOVIDO]@");
        clean=ORACLE_LOGIN.matcher(clean).replaceAll("$1[REMOVIDO]@");
        clean=AUTH_HEADER.matcher(clean).replaceAll("$1 [REMOVIDO]");
        return ASSIGNMENT.matcher(clean).replaceAll("$1[REMOVIDO]");
    }
    static Map<String,Object> document(String collection,Map<String,Object> source){
        Set<String> allowed=collection.equals("environments")?ENV:collection.equals("hosts")?HOST:RECORD;
        return object(source,allowed,0);
    }
    private static Map<String,Object> object(Map<?,?> source,Set<String> allowed,int depth){
        if(depth>12)throw new Failure("Um registro excedeu a profundidade permitida para exportação.");
        Map<String,Object> result=new LinkedHashMap<>();
        for(var entry:source.entrySet()){
            String key=String.valueOf(entry.getKey());if(!allowed.contains(key))continue;
            Object v=entry.getValue();if(v==null){result.put(key,null);continue;}
            Set<String> nested=switch(key){case "coverage"->COVERAGE;case "stats"->STATS;case "disks"->DISK;case "support"->SUPPORT;case "linkedContexts"->keys("context contextPath applicationId applicationName serverId serverName sourcePath linkName");case "driverCandidates"->CANDIDATE;case "associations"->ASSOCIATION;case "databaseEndpoints"->ENDPOINT;case "embeddedServers","driverResult"->RECORD;default->Set.of();};
            Object clean=value(v,nested,depth+1);if(clean!=null)result.put(key,clean);
        }
        return result;
    }
    private static Object value(Object v,Set<String> nested,int depth){
        if(v instanceof String s)return scrub(s);
        if(v instanceof Boolean||v instanceof Number)return v;
        if(v instanceof Map<?,?> m)return nested.isEmpty()?null:object(m,nested,depth);
        if(v instanceof List<?> list){
            if(depth>12)throw new Failure("Um registro excedeu a profundidade permitida para exportação.");
            List<Object> result=new ArrayList<>();for(Object item:list){Object clean=value(item,nested,depth+1);if(clean!=null)result.add(clean);}return result;
        }
        return null;
    }
    static void write(OutputStream output,InventoryStore store,String label)throws Exception {write(output,store,label,MAX_BYTES,MAX_RECORDS);}
    static void write(OutputStream output,InventoryStore store,String label,long byteLimit,long recordLimit)throws Exception {
        String title=label(label);String exportedAt=Instant.now().toString();
        try(ZipOutputStream zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            Budget budget=new Budget(zip,byteLimit);Map<String,Object> counts=new LinkedHashMap<>();long[] total={0};
            store.readSnapshot(()->{
                for(String collection:COLLECTIONS){
                    zip.putNextEntry(new ZipEntry(collection+".json"));budget.text("[");long[] count={0};
                    String sql=collection.equals("hosts")||collection.equals("environments")?"SELECT payload FROM "+collection+" ORDER BY id":"SELECT payload FROM inventory WHERE kind=? ORDER BY host_id,id";
                    Object[] args=collection.equals("hosts")||collection.equals("environments")?new Object[0]:new Object[]{collection};
                    store.forEachRow(sql,row->{
                        if(++total[0]>recordLimit)throw new Failure("O inventário excedeu o limite de registros para este pacote.");
                        @SuppressWarnings("unchecked") Map<String,Object> record=(Map<String,Object>)Json.parse(String.valueOf(row.get("payload")),32*1024*1024);
                        Lifecycle.decorate(collection,List.of(record),Instant.parse(exportedAt));
                        if(count[0]++>0)budget.text(",");budget.text(Json.stringify(document(collection,record)));
                    },args);
                    budget.text("]\n");zip.closeEntry();counts.put(collection,count[0]);
                }
                return null;
            });
            zip.putNextEntry(new ZipEntry("manifest.json"));budget.text(Json.stringify(map("format","java-assessment-inventory","formatVersion",1,"dashboardVersion",DashboardVersion.VERSION,"exportedAt",exportedAt,"label",title,"scope","all-current-inventory","counts",counts,"collections",COLLECTIONS,"containsCredentials",false)));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("LEIA-ME.txt"));budget.text("Java Assessment — inventário para análise\n\nFormato 1. O manifesto identifica a versão, a data UTC e as contagens.\nCada coleção JSON contém uma lista. hostId vincula registros a hosts.id; environmentId vincula máquinas a environments.id. IDs de registros são locais à máquina e à coleção. As associações preservam os vínculos entre aplicações, servidores e JDBC.\n\nÉ uma cópia do inventário atual, não um backup do dashboard. Não inclui usuários, credenciais, tokens, certificados, configurações operacionais, comandos de processos ou histórico de solicitações. Campos desconhecidos ou fora do escopo são omitidos. Valores ausentes não significam ausência do recurso.\nPode conter nomes de máquinas, IPs, caminhos e destinos de banco. Compartilhe com quem estiver autorizado a analisar a infraestrutura.\n\nVersão do driver JDBC e versão do banco são informações distintas. Destinos configurados e candidatos a driver não comprovam conexão ativa ou carregamento efetivo. Preserve driverResolution e suas evidências nas análises.\n");zip.closeEntry();
        }
    }
    private static final class Budget {
        private final OutputStream out;private final long max;private long bytes;
        Budget(OutputStream out,long max){this.out=out;this.max=max;}
        void text(String text){byte[] data=text.getBytes(StandardCharsets.UTF_8);if(data.length>max-bytes)throw new Failure("O inventário excedeu o limite de 512 MiB descompactados para este pacote.");bytes+=data.length;try{out.write(data);}catch(IOException e){throw new UncheckedIOException(e);}}
    }
}
