package assessment;
import java.io.*;
import java.util.*;

public final class ApplicationConfigFilesTest {
    static void check(boolean value,String message) { ProcessLaunchTest.check(value,message); }
    static AssessmentScan collect(File root,File config,int count) throws Exception {
        File proc = new File(root,"proc"), app = SpringConfigurationTest.app(root,"orders.jar",SpringConfigurationTest.content());
        for(int i=0;i<count;i++) ProcessLaunchTest.process(proc,"9877800"+i,"java","-jar",app.getPath());
        AssessmentScan scan = new AssessmentScan(new Properties(),"test","test",AssessmentScan.map("inspectArchives",true,"pauseMillis",0,"appConfigPaths",Arrays.asList(config.getCanonicalPath())));
        scan.excluded.clear(); scan.discoverProcesses(proc); return scan;
    }
    public static void main(String[] args) throws Exception {
        File root=CollectorTest.temp("app-config");
        try {
            File conf=new File(root,"external");
            ProcessLaunchTest.text(new File(conf,"application.properties"),"spring.datasource.url=jdbc:postgresql://db:5432/orders?password=SECRET_CANARY\nspring.datasource.username=USER_CANARY\n");
            AssessmentScan one=collect(new File(root,"one"),conf,1);
            check(one.datasources.size()==1 && ((Map)one.datasources.get(0)).get("applicationId")!=null,"single Boot owns generic configuration");
            AssessmentScan many=collect(new File(root,"many"),conf,2);
            check(many.datasources.size()==1 && "machine-unbound".equals(((Map)many.datasources.get(0)).get("configurationBinding")),"generic config with multiple Boot processes stays unbound");
            File directRoot=new File(root,"direct"),directProc=new File(directRoot,"proc"),directJar=SpringConfigurationTest.app(directRoot,"direct.jar",SpringConfigurationTest.content());
            ProcessLaunchTest.process(directProc,"98778011","java","-jar",directJar.getPath(),"--spring.config.location="+new File(conf,"application.properties").getPath());
            ProcessLaunchTest.process(directProc,"98778012","java","-jar",directJar.getPath());
            AssessmentScan direct=new AssessmentScan(new Properties(),"test","test",AssessmentScan.map("inspectArchives",true,"pauseMillis",0,"appConfigPaths",Arrays.asList(conf.getCanonicalPath())));direct.excluded.clear();direct.discoverProcesses(directProc);
            check(direct.datasources.size()==1&&Long.valueOf(98778011).equals(((Map)direct.datasources.get(0)).get("pid")),"explicit location stays bound without a duplicate machine row");
            ProcessLaunchTest.text(new File(conf,"orders.properties"),"spring.datasource.url=jdbc:oracle:thin:@db:1521/SVC\n");
            AssessmentScan named=collect(new File(root,"named"),conf,1);
            check(named.datasources.size()==1,"named config overrides generic for unique app");
            check(!Json.stringify(named.datasources).contains("jdbc:")&&!Json.stringify(one.datasources).contains("CANARY"),"no URLs or credentials");
            check(new ApplicationConfigFiles(one).roots().size()==1,"configured roots retained");
            AssessmentScan fallback=ProcessLaunchTest.scanner();check(fallback.applicationConfigFiles.roots().equals(Arrays.asList("/etc/config")),"default root");
            fallback.settings.put("appConfigPaths",Collections.emptyList());check(fallback.applicationConfigFiles.roots().isEmpty(),"empty disables additional roots");
            File target=new File(root,"target.properties");ProcessLaunchTest.text(target,"spring.datasource.url=jdbc:h2:mem:secret");
            Process symlink=new ProcessBuilder("ln","-s",target.getCanonicalPath(),new File(conf,"link.properties").getAbsolutePath()).start();check(symlink.waitFor()==0,"symlink fixture");
            check(ApplicationConfigFiles.safe(one,new File(conf,"link.properties"))==null,"links rejected");
            AssessmentScan names=ProcessLaunchTest.scanner();ApplicationConfigFiles files=names.applicationConfigFiles;
            AssessmentScan.ProcessContext formacao=new AssessmentScan.ProcessContext(ProcessLaunch.parse(new String[]{"java","-jar","/u01/app/FORMACAO_PERIODOS_SE.jar"},new File("/")),1,null,AssessmentScan.map(),AssessmentScan.map("name","FORMACAO_PERIODOS_SE.jar"));
            AssessmentScan.ProcessContext app=new AssessmentScan.ProcessContext(ProcessLaunch.parse(new String[]{"java","-jar","/srv/app.jar"},new File("/")),2,null,AssessmentScan.map(),AssessmentScan.map("name","app.jar"));
            AssessmentScan.ProcessContext api=new AssessmentScan.ProcessContext(ProcessLaunch.parse(new String[]{"java","-jar","/srv/api-2.4.1-SNAPSHOT.jar"},new File("/")),3,null,AssessmentScan.map(),AssessmentScan.map("name","api-2.4.1-SNAPSHOT.jar"));
            check(files.matches(new File("/etc/config/formacao-periodos-se/application.yml"),formacao),"folder name matches across _ and -");
            check(files.matches(new File("/etc/config/formacao-periodos-se.properties"),formacao)&&files.matches(new File("/etc/config/FORMACAO_PERIODOS_SE-prod.yml"),formacao),"file name matches by token");
            check(!files.matches(new File("/etc/config/application.yml"),app)&&!files.matches(new File("/etc/config/application-prod.properties"),app),"app.jar never claims generic application files");
            check(!files.matches(new File("/etc/config/apigateway.yml"),api)&&files.matches(new File("/etc/config/api.properties"),api),"no substring match; version suffix removed");
            System.out.println("APPLICATION_CONFIG_FILES=PASS unique ambiguous explicit named defaults privacy symlink");
        } finally { CollectorTest.remove(root); }
    }
}
