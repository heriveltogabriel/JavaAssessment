package assessment;
import java.io.*;
import java.util.*;
public final class ApplicationJdbcTest {
    static void check(boolean value,String message){ProcessLaunchTest.check(value,message);}
    static AssessmentScan.ProcessContext context(AssessmentScan scan){
        Map app=AssessmentScan.map("id","app","name","Orders");scan.applications.add(app);
        return new AssessmentScan.ProcessContext(new ProcessLaunch(),42,null,AssessmentScan.map(),app);
    }
    static void parse(ApplicationJdbc reader,String path,String text,AssessmentScan.ProcessContext c)throws Exception{reader.parse(path,text.getBytes("UTF-8"),c);}
    public static void main(String[] args)throws Exception{
        AssessmentScan scan=ProcessLaunchTest.scanner();ApplicationJdbc reader=new ApplicationJdbc(scan);AssessmentScan.ProcessContext c=context(scan);
        parse(reader,"/app/META-INF/persistence.xml","<persistence><persistence-unit name='OrdersPU'><properties><property name='jakarta.persistence.jdbc.url' value='jdbc:oracle:thin:USER_CANARY/SECRET_CANARY@db:1521/SVC'/><property name='jakarta.persistence.jdbc.driver' value='oracle.jdbc.OracleDriver'/><property name='jakarta.persistence.jdbc.password' value='SECRET_CANARY'/><property name='hibernate.dialect' value='org.hibernate.dialect.PostgreSQLDialect'/></properties></persistence-unit><persistence-unit name='JndiPU'><jta-data-source>java:/cacheDS</jta-data-source></persistence-unit></persistence>",c);
        check(scan.datasources.size()==2,"one row per persistence unit, no generic duplicate");
        Map first=(Map)scan.datasources.get(0);check("db".equals(first.get("databaseHost"))&&"persistence-xml".equals(first.get("discoveryMethod"))&&first.get("configurationObservation")!=null,"JPA sanitized endpoint and divergence");
        parse(reader,"/app/hibernate.cfg.xml","<hibernate-configuration><session-factory><property name='connection.url'>jdbc:postgresql://hibernate:5432/test</property><property name='connection.driver_class'>org.postgresql.Driver</property><property name='connection.username'>USER_CANARY</property></session-factory></hibernate-configuration>",c);
        parse(reader,"/app/hibernate.properties","hibernate.connection.datasource=java:comp/env/jdbc/Persistence\nhibernate.dialect=org.hibernate.dialect.DB2Dialect\nhibernate.connection.password=SECRET_CANARY\n",c);
        check(scan.datasources.size()==4,"Hibernate XML and properties");
        parse(reader,"/app/applicationContext.xml","<beans xmlns:jee='urn:test'><bean id='hikari' class='com.zaxxer.hikari.HikariDataSource'><property name='jdbcUrl' value='jdbc:mysql://mysql:3306/orders?user=USER_CANARY&amp;password=SECRET_CANARY'/><property name='driverClassName'><value>com.mysql.cj.jdbc.Driver</value></property></bean><bean id='jndi' class='org.springframework.jndi.JndiObjectFactoryBean'><property name='jndiName' value='java:/OtherDS'/></bean><jee:jndi-lookup id='lookup' jndi-name='java:/ThirdDS'/></beans>",c);
        check(scan.datasources.size()==7,"Spring XML beans and JNDI lookup");
        parse(reader,"/app/db.json","{\"endpoint\":\"jdbc:sqlserver://sql:1433;databaseName=Orders;password=SECRET_CANARY\"}",c);
        check(scan.datasources.size()==8,"generic JSON URL");
        parse(reader,"/app/copy.ini","jdbc:sqlserver://sql:1433;databaseName=Orders",c);check(scan.datasources.size()==8,"generic duplicates suppressed");
        parse(reader,"/app/secrets.env","PASSWORD=jdbc:postgresql://SECRET_CANARY/leak\nUSERNAME=jdbc:postgresql://USER_CANARY/leak",c);check(scan.datasources.size()==8,"generic secret fields skipped");
        parse(reader,"/app/evil.xml","<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><beans>&e;</beans>",c);check(scan.datasources.size()==8,"XXE rejected");
        String output=Json.stringify(scan.datasources)+scan.warnings.toString();check(!output.contains("CANARY")&&!output.contains("jdbc:"),"no users, secrets or URLs");
        File root=CollectorTest.temp("application-jdbc");
        try{
            File proc=new File(root,"proc"),jar=new File(root,"plain.jar");Map<String,byte[]> entries=ProcessLaunchTest.entries();
            entries.put("META-INF/persistence.xml","<persistence><persistence-unit name='Packed'><properties><property name='javax.persistence.jdbc.url' value='jdbc:postgresql://packed:5432/db'/></properties></persistence-unit></persistence>".getBytes("UTF-8"));
            ProcessLaunchTest.write(jar,ProcessLaunchTest.jar(ProcessLaunchTest.attrs("Main-Class","demo.Main"),entries));
            ProcessLaunchTest.text(new File(root,"config/custom.conf"),"jdbc:mysql://external:3306/test?password=SECRET_CANARY");
            ProcessLaunchTest.text(new File(root,"unrelated/db.conf"),"jdbc:mysql://unrelated:3306/test");
            ProcessLaunchTest.process(proc,"98779001","java","-jar",jar.getCanonicalPath());
            AssessmentScan actual=ProcessLaunchTest.scanner();actual.discoverProcesses(proc);
            check(actual.datasources.size()==2,"real process scope reads jar and config only");
            check(!Json.stringify(actual.datasources).contains("unrelated"),"unrelated sibling skipped");
            File war=new File(root,"demo.war");ProcessLaunchTest.write(war,ProcessLaunchTest.jar(ProcessLaunchTest.attrs(),entries));
            AssessmentScan warScan=ProcessLaunchTest.scanner();new ApplicationJdbc(warScan).archive(war,context(warScan));check(warScan.datasources.size()==1,"WAR persistence");
            Map<String,byte[]> nested=ProcessLaunchTest.entries();nested.put("demo.war",ProcessLaunchTest.jar(ProcessLaunchTest.attrs(),entries));
            File ear=new File(root,"demo.ear");ProcessLaunchTest.write(ear,ProcessLaunchTest.jar(ProcessLaunchTest.attrs(),nested));
            AssessmentScan earScan=ProcessLaunchTest.scanner();new ApplicationJdbc(earScan).archive(ear,context(earScan));check(earScan.datasources.size()==1,"EAR nested WAR persistence");
            File big=new File(root,"config/huge.properties");RandomAccessFile out=new RandomAccessFile(big,"rw");out.setLength(512*1024+1);out.close();
            AssessmentScan bounded=ProcessLaunchTest.scanner();ApplicationJdbc limited=new ApplicationJdbc(bounded);limited.inspect(big,context(bounded));check(bounded.datasources.isEmpty()&&bounded.warnings.toString().contains("512 KiB"),"text size bound");
            // A sidecar JVM (Derby) started in a WebLogic domain home must not own the domain configuration: the domain's WebLogic server does.
            File domain=new File(root,"domain");JdbcEndpointTest.domain(domain,"db.example");
            ProcessLaunchTest.text(new File(domain,"config/datasources_wl_config.json"),"{\"a\":\"jdbc:oracle:thin:@//db.example:1521/ORDERS\",\"b\":\"jdbc:oracle:thin:@//extra.example:1521/EXTRA\"}");
            File sidecar=new File(root,"sidecar-proc");
            ProcessLaunchTest.process(sidecar,"98779010","java","org.apache.derby.drda.NetworkServerControl","start");
            java.nio.file.Files.createSymbolicLink(new File(sidecar,"98779010/cwd").toPath(),domain.getCanonicalFile().toPath());
            AssessmentScan stopped=ProcessLaunchTest.scanner();stopped.discoverProcesses(sidecar);
            check(stopped.datasources.isEmpty(),"Without a running WebLogic server of that domain nothing is attributed to the sidecar: "+Json.stringify(stopped.datasources));
            ProcessLaunchTest.process(sidecar,"98779011","java","-Dweblogic.RootDirectory="+domain.getCanonicalPath(),"-Dweblogic.Name=AdminServer","weblogic.Server");
            AssessmentScan homeScan=ProcessLaunchTest.scanner();homeScan.discoverProcesses(sidecar);
            String all=Json.stringify(homeScan.datasources);
            check(homeScan.datasources.size()==2,"Domain datasource (config.xml) plus the one endpoint only present in the JSON file: "+all);
            int found=0;for(Object o:homeScan.datasources){Map row=(Map)o;if("EXTRA".equals(row.get("databaseName"))){found++;
                check(row.get("domainPath")!=null&&row.get("domainName")!=null&&"domain".equals(row.get("domainName")),"New endpoint carries the domain");
                for(Object assoc:(List)row.get("associations"))check(Long.valueOf(98779011L).equals(((Map)assoc).get("pid")),"Associated with the WebLogic server, not the Derby sidecar: "+all);}}
            check(found==1&&!all.contains("NetworkServerControl datasource"),"Single extra row and no Derby-named datasource: "+all);
            File plain=new File(root,"plain-dir");ProcessLaunchTest.text(new File(plain,"config/custom.json"),"{\"url\":\"jdbc:oracle:thin:@//db.example:1521/SVC\"}");
            File other=new File(root,"other-proc");ProcessLaunchTest.process(other,"98779011","java","org.example.Worker");
            java.nio.file.Files.createSymbolicLink(new File(other,"98779011/cwd").toPath(),plain.getCanonicalFile().toPath());
            AssessmentScan plainScan=ProcessLaunchTest.scanner();plainScan.discoverProcesses(other);
            check(plainScan.datasources.size()==1,"A process working directory that is not a domain home is still scanned");
        }finally{CollectorTest.remove(root);}
        System.out.println("APPLICATION_JDBC=PASS persistence Hibernate Spring-XML generic dedup scopes archives limits privacy XXE");
    }
}
