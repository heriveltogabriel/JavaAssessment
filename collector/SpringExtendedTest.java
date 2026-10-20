package assessment;
import java.io.*;
import java.util.*;

public final class SpringExtendedTest {
    static void check(boolean v,String m){ProcessLaunchTest.check(v,m);}
    public static void main(String[] args)throws Exception {
        File root=CollectorTest.temp("spring-extended");
        try {
            File ext=new File(root,"external"),base=new File(ext,"application.properties");
            ProcessLaunchTest.text(base,"spring.config.import=optional:file:absent.properties,file:db.properties\nspring.profiles.active=prod\nspring.jpa.database=postgresql\ncache-consultas-jndi.datasource.jndi_name=java:/cacheConsultasDS\n");
            ProcessLaunchTest.text(new File(ext,"db.properties"),"spring.datasource.url=jdbc:oracle:thin:@db:1521/SVC\nspring.datasource.driver-class-name=oracle.jdbc.OracleDriver\nspring.datasource.password=SECRET_CANARY\n");
            ProcessLaunchTest.text(new File(ext,"application-prod.yml"),"other:\n  datasource:\n    jdbcUrl: jdbc:postgresql://second:5432/prod\n");
            File jar=SpringConfigurationTest.app(root,"app.jar",SpringConfigurationTest.content());
            AssessmentScan scan=SpringConfigurationTest.scan(root,new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.config.location="+ext.getPath()+"/"});
            check(scan.datasources.size()==3,"main, automatic JNDI prefix and profile datasource");
            boolean oracle=false,jndi=false,profile=false;
            for(Object item:scan.datasources){Map d=(Map)item;
                if("Oracle".equals(d.get("databaseType"))){oracle=true;check("db".equals(d.get("databaseHost"))&&d.get("configurationObservation")!=null&&"spring-import".equals(d.get("discoveryMethod")),"import evidence and URL wins over dialect");}
                if(d.get("jndiName")!=null){jndi=true;check("PostgreSQL".equals(d.get("databaseType"))&&d.get("databaseTypeEvidence")!=null,"JNDI dialect inference");}
                if("second".equals(d.get("databaseHost")))profile=true;
            }
            check(oracle&&jndi&&profile,"all candidates preserved");
            check(!Json.stringify(scan.datasources).contains("SECRET_CANARY")&&!Json.stringify(scan.datasources).contains("jdbc:"),"sanitized output");
            ProcessLaunchTest.text(new File(ext,"db.properties"),"spring.config.import=file:application.properties\nspring.datasource.url=jdbc:oracle:thin:@db:1521/SVC\n");
            AssessmentScan cycle=SpringConfigurationTest.scan(new File(root,"cycle"),new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.config.location="+ext.getPath()+"/"});
            check(cycle.warnings.toString().contains("Ciclo"),"import cycles terminate with observation");
            ProcessLaunchTest.text(new File(ext,"db.properties"),"spring.config.import=vault:SECRET_CANARY\nspring.datasource.url=jdbc:oracle:thin:@db:1521/SVC\n");
            AssessmentScan remote=SpringConfigurationTest.scan(new File(root,"remote"),new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.config.location="+ext.getPath()+"/"});
            check(!remote.warnings.toString().contains("SECRET_CANARY"),"unsupported import diagnostics sanitized");
            for(int i=0;i<5;i++)ProcessLaunchTest.text(new File(ext,"depth"+i+".properties"),"spring.config.import=file:depth"+(i+1)+".properties\nspring.datasource.url=jdbc:postgresql://depth"+i+"/db\n");
            AssessmentScan depth=SpringConfigurationTest.scan(new File(root,"depth"),new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.config.location="+new File(ext,"depth0.properties").getPath()});
            check(depth.warnings.toString().contains("profundidade"),"import depth bound");
            StringBuilder imports=new StringBuilder();
            for(int i=0;i<21;i++){if(i>0)imports.append(',');imports.append("file:limit").append(i).append(".properties");ProcessLaunchTest.text(new File(ext,"limit"+i+".properties"),"ds"+i+".datasource.url=jdbc:postgresql://limit"+i+"/db\n");}
            ProcessLaunchTest.text(new File(ext,"limits.properties"),"spring.config.import="+imports+"\n");
            AssessmentScan bounded=SpringConfigurationTest.scan(new File(root,"bounded"),new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.config.location="+new File(ext,"limits.properties").getPath()});
            check(bounded.warnings.toString().contains("20 arquivos"),"import file count bound");
            for(Object value:bounded.datasources)check(!"limit20".equals(((Map)value).get("databaseHost")),"twenty-first import not read");
            byte[] huge=new byte[1024*1024+1];Arrays.fill(huge,(byte)' ');byte[] header="spring.datasource.url=jdbc:postgresql://oversize/db\n".getBytes("UTF-8");System.arraycopy(header,0,huge,0,header.length);
            ProcessLaunchTest.write(new File(ext,"huge.properties"),huge);
            ProcessLaunchTest.text(new File(ext,"limits.properties"),"spring.config.import=file:huge.properties\n");
            AssessmentScan oversized=SpringConfigurationTest.scan(new File(root,"oversized"),new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.config.location="+new File(ext,"limits.properties").getPath()});
            check(oversized.datasources.isEmpty(),"import over 1 MiB ignored");
            ProcessLaunchTest.text(new File(ext,"linked-target.properties"),"spring.datasource.url=jdbc:postgresql://link-secret/db\n");
            Process link=new ProcessBuilder("ln","-s",new File(ext,"linked-target.properties").getPath(),new File(ext,"linked.properties").getPath()).start();check(link.waitFor()==0,"symlink fixture");
            ProcessLaunchTest.text(new File(ext,"limits.properties"),"spring.config.import=file:linked.properties\n");
            AssessmentScan linked=SpringConfigurationTest.scan(new File(root,"linked"),new Properties(),new String[]{"java","-jar",jar.getPath(),"--spring.config.location="+new File(ext,"limits.properties").getPath()});
            check(linked.datasources.isEmpty()&&linked.warnings.toString().contains("link simbólico"),"symlink import rejected");
            JdbcEndpoint.Resolver resolver=new JdbcEndpoint.Resolver(null,null);
            for(String userKey:new String[]{"DB_USERNAME","DB_USER","dbUser","user","database.userId"})check(resolver.expand("${"+userKey+":SECRET_CANARY}",new ArrayList<String>())==null,"user placeholders forbidden: "+userKey);
            System.out.println("SPRING_EXTENDED=PASS auto-prefix relaxed JPA imports profiles privacy cycles depth count size symlink");
        } finally{CollectorTest.remove(root);}
    }
}
