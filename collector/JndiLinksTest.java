package assessment;
import java.io.*;
import java.util.*;
public final class JndiLinksTest {
    static void check(boolean v,String m){ProcessLaunchTest.check(v,m);}
    public static void main(String[] args)throws Exception {
        for(String p:new String[]{"java:/","java:jboss/","java:comp/env/","java:global/",""})check("jdbc/X".equals(JndiLinks.normalized(p+"jdbc/X")),"JNDI prefixes");
        File root=CollectorTest.temp("jndi-links");
        try {
            File home=new File(root,"jboss"),proc=new File(root,"proc");
            ProcessLaunchTest.text(new File(home,"standalone/configuration/standalone.xml"),"<server><profile><subsystem xmlns='urn:jboss:domain:datasources:5.0'><datasources><datasource jndi-name='java:/cacheConsultasDS' pool-name='Cache'><connection-url>jdbc:postgresql://db:5432/cache</connection-url><driver>org.postgresql.Driver</driver></datasource></datasources></subsystem></profile></server>");
            Map<String,byte[]> map=SpringConfigurationTest.content();SpringConfigurationTest.text(map,"BOOT-INF/classes/application.properties","cache-consultas-jndi.datasource.jndi-name=java:/cacheConsultasDS\nspring.jpa.database=postgresql\n");
            File jar=SpringConfigurationTest.app(root,"app.jar",map);
            for(int order=0;order<2;order++){
                File pd=new File(proc,"run"+order);
                ProcessLaunchTest.process(pd,"9877000"+order,"java","-jar",jar.getPath());
                ProcessLaunchTest.process(pd,"9877000"+(1-order),"java","-Djboss.home.dir="+home.getPath(),"-jar",new File(home,"jboss-modules.jar").getPath(),"org.jboss.as.standalone");
                AssessmentScan scan=ProcessLaunchTest.scanner();scan.discoverProcesses(pd);
                Map linked=null;for(Object v:scan.datasources)if(((Map)v).get("jndiResolution")!=null)linked=(Map)v;
                check(linked!=null&&"MATCHED".equals(linked.get("jndiResolution"))&&"db".equals(linked.get("databaseHost")),"JBoss link independent of process order");
            }
            AssessmentScan scan=ProcessLaunchTest.scanner();
            Map app=AssessmentScan.map("hostId",scan.hostId,"jndiName","java:comp/env/jdbc/X");scan.datasources.add(app);
            JndiLinks.link(scan);check("NOT_FOUND".equals(app.get("jndiResolution")),"unresolved explicit");
            Map server=AssessmentScan.map("hostId",scan.hostId,"serverId","server1","jndiNames",Arrays.asList("java:global/jdbc/X"),"driverVersion","19.3","driverArtifact","/lib/ojdbc8.jar");
            scan.datasources.add(server);JndiLinks.link(scan);check("19.3".equals(app.get("driverVersion")),"WebLogic multi-name driver inheritance");
            List<Map> drivers=Arrays.asList(AssessmentScan.map("path","/lib/ojdbc8.jar","version","19.3"),AssessmentScan.map("path","/lib/other.jar","version","21.1"));
            Map decision=AssessmentScan.map("driverResolution","AMBIGUOUS","driverCandidates",drivers);
            server.put("driverCandidates",drivers);server.put("driverResolution","AMBIGUOUS");server.put("associations",Arrays.asList(AssessmentScan.map("pid",Long.valueOf(42),"serverId","server1","driverResult",decision)));
            JndiLinks.link(scan);
            check(((List)app.get("driverCandidates")).size()==2&&((Map)((List)app.get("associations")).get(0)).get("driverResult")==decision,"JNDI retains candidates and per-PID decision");
            app.remove("driverVersion");app.remove("driverArtifact");
            Map second=new LinkedHashMap(server);second.put("serverId","server2");scan.datasources.add(second);JndiLinks.link(scan);
            check("AMBIGUOUS".equals(app.get("jndiResolution"))&&app.get("driverVersion")==null,"ambiguous server names not guessed");
            System.out.println("JNDI_LINKS=PASS JBoss-order WebLogic prefixes missing ambiguous drivers");
        }finally{CollectorTest.remove(root);}
    }
}
