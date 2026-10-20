package assessment;
import java.io.*;
import java.util.*;
public final class AgentConfigAcceptanceTest {
    static void check(boolean v,String m){ProcessLaunchTest.check(v,m);}
    public static void main(String[] args)throws Exception {
        File root=CollectorTest.temp("agent-config-acceptance");
        try{
            File jar=new File(root,"FORMACAO_PERIODOS_SE.jar"),proc=new File(root,"proc"),config=new File(root,"etc/config");
            Map<String,byte[]> entries=ProcessLaunchTest.entries();
            entries.put("BOOT-INF/classes/application.yml",("spring:\n  jpa:\n    database: postgresql\n    properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect\ncache-consultas-jndi:\n  datasource:\n    jndi-name: java:/cacheConsultasDS\n").getBytes("UTF-8"));
            ProcessLaunchTest.write(jar,ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Main-Class","org.springframework.boot.loader.launch.JarLauncher","Start-Class","example.Main","Spring-Boot-Version","3.5.0"),entries));
            ProcessLaunchTest.text(new File(config,"FORMACAO_PERIODOS_SE.properties"),"spring.datasource.url=jdbc:oracle:thin:@db-scan.exemplo:1521/SERVICO\nspring.datasource.username=USER_CANARY\nspring.datasource.password=PASSWORD_CANARY\nspring.datasource.driver-class-name=oracle.jdbc.OracleDriver\n");
            ProcessLaunchTest.process(proc,"98779901","java","-jar","-Duser.country=BR","-Duser.language=pt",jar.getCanonicalPath(),"-XX:MaxPermSize=512m","-XX:PermSize=512m","-Xms2G","-Xmx2G","-Dtoken=TOKEN_CANARY");
            AssessmentScan scan=new AssessmentScan(new Properties(),"client-case","client-case",AssessmentScan.map("inspectArchives",true,"pauseMillis",0,"appConfigPaths",Arrays.asList(config.getCanonicalPath())));scan.excluded.clear();scan.discoverProcesses(proc);
            Map app=(Map)scan.applications.get(0);check("FORMACAO_PERIODOS_SE.jar".equals(app.get("name"))&&"Spring Boot".equals(app.get("applicationType")),"real launcher identifies application and Spring Boot");
            check(app.get("launcherObservation")!=null,"post-JAR options observation");
            check(scan.datasources.size()==2,"external Oracle and custom JNDI");
            boolean oracle=false,jndi=false;for(Object v:scan.datasources){Map row=(Map)v;
                if("Oracle".equals(row.get("databaseType"))){oracle=true;check("db-scan.exemplo".equals(row.get("databaseHost"))&&"SERVICO".equals(row.get("databaseName"))&&"app-config-dir".equals(row.get("discoveryMethod")),"named external file endpoint and provenance");}
                if(row.get("jndiName")!=null){jndi=true;check("PostgreSQL".equals(row.get("databaseType"))&&"NOT_FOUND".equals(row.get("jndiResolution")),"JNDI missing with dialect hint");check("cache-consultas-jndi".equals(row.get("name")),"custom datasource has its own name");}}
            check(oracle&&jndi,"independent datasources");
            String serialized=Json.stringify(scan.datasources)+Json.stringify(scan.applications)+Json.stringify(scan.processes)+scan.warnings;
            check(!serialized.contains("CANARY")&&!serialized.contains("jdbc:")&&!serialized.contains("-Duser.country")&&!serialized.contains("512m"),"acceptance output contains no values, credentials or raw command");
            System.out.println("AGENT_CONFIG_ACCEPTANCE=PASS real-command named-config Oracle JNDI dialect observation privacy");
        }finally{CollectorTest.remove(root);}
    }
}
