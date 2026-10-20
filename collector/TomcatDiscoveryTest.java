package assessment;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Passive fixtures: no Tomcat, database, HTTP or loaded JDBC classes. */
public final class TomcatDiscoveryTest {
    static int checks;static final String DRIVER="oracle.jdbc.OracleDriver";
    static void check(boolean condition,String text){checks++;if(!condition)throw new AssertionError(text);}
    static void text(File f,String s)throws Exception{ProcessLaunchTest.text(f,s);}
    static void jar(File f,String version)throws Exception{JdbcDriverResolverTest.jar(f,DRIVER,version,null,null);}
    static String resource(String url){return "<Resource name='jdbc/App' type='javax.sql.DataSource' driverClassName='"+DRIVER+"' url='"+url+"' password='PRIVATE_PASSWORD_CANARY'/>";}
    static File base(File root,String name)throws Exception{File b=new File(root,name);text(new File(b,"conf/server.xml"),"<Server><GlobalNamingResources>"+resource("jdbc:oracle:thin:@//db.example:1521/PROD")+"</GlobalNamingResources></Server>");return b;}
    static AssessmentScan.ProcessContext context(File base,File home,long pid)throws Exception{
        ProcessLaunch l=ProcessLaunch.parse(new String[]{"java","-Dcatalina.base="+base.getCanonicalPath(),"-Dcatalina.home="+home.getCanonicalPath(),"org.apache.catalina.startup.Bootstrap"},base);
        AssessmentScan.ProcessContext c=new AssessmentScan.ProcessContext(l,pid,null,AssessmentScan.map("javaVersion","17"),null);c.server=AssessmentScan.map("name","Tomcat "+pid,"version","9.0.100");return c;
    }
    static Map resolve(AssessmentScan scan,AssessmentScan.ProcessContext c,File proc)throws Exception{
        TomcatDataSources helper=scan.serverDataSources.tomcat;helper.configureResolver(c,scan.serverDataSources.resolver(c));helper.discover(c);
        Map row=(Map)scan.datasources.get(0);Set<String>wanted=Collections.singleton(DRIVER.replace('.','/')+".class");
        JdbcDriverResolver.Search search=scan.jdbcDrivers.new Search(c,wanted,DRIVER.replace('.','/')+".class",scan.jdbcDrivers.new OpenFiles(c,proc));helper.resolve(search,row);Map decision=search.result();
        if(Boolean.TRUE.equals(row.remove("tomcatApplicationOnlyDriver"))&&"NOT_FOUND".equals(decision.get("driverResolution")))decision.put("driverResolutionReason","Driver presente na aplicação; não usado pelo datasource do Tomcat");
        for(Object item:(List)row.get("associations")){Map a=(Map)item;if(Long.valueOf(c.pid).equals(a.get("pid")))a.put("driverResult",decision);}JdbcDriverResolver.aggregate(row);return row;
    }
    static Map candidate(Map row,File f)throws Exception{for(Object item:(List)row.get("driverCandidates")){Map c=(Map)item;if(f.getCanonicalPath().equals(c.get("path")))return c;}return null;}
    public static void main(String[] args)throws Exception{
        File root=CollectorTest.temp("tomcat-discovery");
        try{
            File b=base(root,"base"),home=new File(root,"home"),baseJar=new File(b,"lib/ojdbc8.jar"),homeJar=new File(home,"lib/ojdbc6.jar");jar(baseJar,"19.3.0");jar(homeJar,"11.2.0.4");
            AssessmentScan scan=ProcessLaunchTest.scanner();Map row=resolve(scan,context(b,home,301),new File(root,"proc/301"));
            check("IDENTIFIED".equals(row.get("driverResolution"))&&"19.3.0".equals(row.get("driverVersion")),"base precedes home: "+Json.stringify(row));
            check(Boolean.FALSE.equals(candidate(row,homeJar).get("loaded")),"home candidate retained as not selected");
            File extra=new File(root,"external-drivers/driver-renamed.jar");jar(extra,"21.5.0");text(new File(b,"conf/catalina.properties"),"common.loader=\""+extra.getParent()+"/*.jar\"\n");
            row=resolve(ProcessLaunchTest.scanner(),context(b,home,302),new File(root,"proc/302"));check(extra.getCanonicalPath().equals(row.get("driverArtifact"))&&"pasta do common.loader".equals(candidate(row,extra).get("source")),"external common loader exact class including renamed JAR");
            File ambiguous=base(root,"ambiguous");List<File> jars=new ArrayList<File>();for(int n=0;n<3;n++){File f=new File(ambiguous,"lib/ojdbc"+(6+n)+".jar");jar(f,"19."+n);jars.add(f);}
            row=resolve(ProcessLaunchTest.scanner(),context(ambiguous,home,303),new File(root,"proc/303"));check("AMBIGUOUS".equals(row.get("driverResolution")),"wildcard does not invent alphabetic precedence");
            File proc=new File(root,"proc/304"),fd=new File(proc,"fd");fd.mkdirs();check(new ProcessBuilder("ln","-s",jars.get(1).getCanonicalPath(),new File(fd,"42").getPath()).start().waitFor()==0,"synthetic open fd");
            row=resolve(ProcessLaunchTest.scanner(),context(ambiguous,home,304),proc);check("PROBABLE".equals(row.get("driverResolution"))&&jars.get(1).getCanonicalPath().equals(row.get("driverArtifact")),"one opened wildcard candidate is probable");
            File only=base(root,"only-app");jar(new File(only,"webapps/orders/WEB-INF/lib/ojdbc8.jar"),"23.4.0");
            row=resolve(ProcessLaunchTest.scanner(),context(only,new File(root,"empty-home"),305),new File(root,"proc/305"));check("NOT_FOUND".equals(row.get("driverResolution"))&&row.get("driverResolutionReason").toString().contains("não usado"),"application-only JDBC cannot satisfy common resource");
            File props=base(root,"props");text(new File(props,"conf/catalina.properties"),"db.url=jdbc:oracle:thin:@//props.example:1521/APP\ndb.pass=PRIVATE_PROPERTIES_CANARY\norg.apache.tomcat.util.digester.PROPERTY_SOURCE=org.apache.tomcat.util.digester.EnvironmentPropertySource\n");
            text(new File(props,"conf/server.xml"),"<Server><GlobalNamingResources>"+resource("${db.url}")+"</GlobalNamingResources></Server>");scan=ProcessLaunchTest.scanner();AssessmentScan.ProcessContext c=context(props,home,306);JdbcEndpoint.Resolver r=scan.serverDataSources.resolver(c);r.environment=new String[]{"DB_HOST=env.example"};
            check("jdbc:oracle:thin:@//props.example:1521/APP".equals(r.expand("${db.url}",new ArrayList<String>())),"properties URL expanded");List<String>sources=new ArrayList<String>();r.expand("${db.url}",sources);check(sources.contains("catalina.properties"),"property origin retained");
            r.arguments=new String[]{"-Ddb.url=jdbc:oracle:thin:@//override.example:1521/APP"};check(r.expand("${db.url}",new ArrayList<String>()).contains("override.example"),"-D wins");check(r.expand("${db.pass:unsafe}",new ArrayList<String>())==null,"secret cannot resolve even default");check("env.example".equals(r.expand("${DB_HOST}",new ArrayList<String>())) ,"explicit EnvironmentPropertySource enables plain env names");
            scan.serverDataSources.discover(c);check(!Json.stringify(scan.datasources).contains("PRIVATE_"),"password and raw properties never retained");
            File extBase=base(root,"docbase"),war=new File(root,"external/app.war");war.getParentFile().mkdirs();ByteArrayOutputStream nested=new ByteArrayOutputStream();ZipOutputStream nz=new ZipOutputStream(nested);JdbcDriverResolverTest.entry(nz,"META-INF/MANIFEST.MF","Manifest-Version: 1.0\nImplementation-Title: spring-core\nImplementation-Version: 5.3.30\n\n");nz.close();
            ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(war));JdbcDriverResolverTest.entry(zip,"META-INF/context.xml","<Context>"+resource("jdbc:oracle:thin:@//external.example:1521/APP")+"</Context>");zip.putNextEntry(new ZipEntry("WEB-INF/lib/spring-core-5.3.30.jar"));zip.write(nested.toByteArray());zip.closeEntry();zip.close();
            text(new File(extBase,"conf/server.xml"),"<Server><Service><Engine name='Catalina'><Host name='localhost' appBase='webapps' xmlBase='custom-contexts'/></Engine></Service></Server>");text(new File(extBase,"custom-contexts/app.xml"),"<Context docBase='"+war.getCanonicalPath()+"'/>");
            scan=ProcessLaunchTest.scanner();scan.serverDataSources.discover(context(extBase,home,307));check(scan.datasources.size()==1&&"/app".equals(((Map)scan.datasources.get(0)).get("applicationName")),"external docBase WAR linked to /app");check(scan.applications.size()==1,"docBase application not duplicated");check(Json.stringify(scan.libraries).contains("spring-core"),"external WAR libraries inventoried");check("/a/b".equals(TomcatDataSources.contextName("a#b.xml"))&&"/".equals(TomcatDataSources.contextName("ROOT.xml")),"context names");
            File pkg=new File(root,"package"),etc=new File(root,"etc-tomcat"),lib=new File(root,"usr-java-tomcat"),apps=new File(root,"var-webapps");pkg.mkdirs();etc.mkdirs();lib.mkdirs();apps.mkdirs();text(new File(etc,"server.xml"),"<Server><GlobalNamingResources>"+resource("jdbc:oracle:thin:@//pkg.example:1521/APP")+"</GlobalNamingResources></Server>");jar(new File(lib,"ojdbc8.jar"),"19.20.0");
            for(String[] link:new String[][]{{"conf",etc.getPath()},{"lib",lib.getPath()},{"webapps",apps.getPath()}})check(new ProcessBuilder("ln","-s",link[1],new File(pkg,link[0]).getPath()).start().waitFor()==0,"package link "+link[0]);
            scan=ProcessLaunchTest.scanner();c=context(pkg,pkg,308);row=resolve(scan,c,new File(root,"proc/308"));check("19.20.0".equals(row.get("driverVersion")),"package links followed");check(c.process.get("tomcatInstallationEvidence").toString().contains("pacote"),"package indication");
            text(new File(pkg,"lib/org/apache/catalina/util/ServerInfo.properties"),"server.info=Hidden\nserver.number=0.0.0.0\n");scan=ProcessLaunchTest.scanner();c=context(pkg,pkg,309);scan.serverDataSources.discover(c);check("9.0.100".equals(c.server.get("version"))&&c.process.get("tomcatInstallationEvidence").toString().contains("personalizado"),"hidden HTTP version does not replace real version");
            File old=base(root,"tomcat55");jar(new File(old,"common/lib/ojdbc14.jar"),"10.2.0.5");scan=ProcessLaunchTest.scanner();c=context(old,new File(root,"old-home"),310);c.server.put("version","5.5.36");row=resolve(scan,c,new File(root,"proc/310"));check("10.2.0.5".equals(row.get("driverVersion")),"legacy common/lib");
            scan=ProcessLaunchTest.scanner();c=context(b,home,311);scan.serverDataSources.resolver(c).arguments=new String[]{"-Dcommon.loader="+baseJar.getCanonicalPath()};resolve(scan,c,new File(root,"proc/311"));
            c=context(b,home,312);scan.serverDataSources.resolver(c).arguments=new String[]{"-Dcommon.loader="+homeJar.getCanonicalPath()};row=resolve(scan,c,new File(root,"proc/312"));check("VARIES".equals(row.get("driverResolution")),"same datasource with different PID driver keeps per-instance decisions");
            File excluded=base(root,"excluded");scan=ProcessLaunchTest.scanner();scan.excluded.add(excluded.getCanonicalPath());scan.serverDataSources.discover(context(excluded,home,313));check(scan.datasources.isEmpty(),"explicit excluded base does not leak resources");
            File invalid=base(root,"invalid-properties");text(new File(invalid,"conf/catalina.properties"),"common.loader=${missing.loader}\n");row=resolve(ProcessLaunchTest.scanner(),context(invalid,home,314),new File(root,"proc/314"));check("INCOMPLETE".equals(row.get("driverResolution")),"unresolved decisive loader is incomplete");
            File many=base(root,"many-contexts");for(int n=0;n<70;n++)text(new File(many,"conf/Catalina/localhost/app"+n+".xml"),"<Context>"+resource("jdbc:oracle:thin:@//db.example:1521/APP")+"</Context>");scan=ProcessLaunchTest.scanner();scan.serverDataSources.discover(context(many,home,315));check(scan.datasources.size()<=65,"at most 64 explicit context descriptors plus global resource");
            File e2e=base(root,"process-tomcat55"),catalina=new File(e2e,"server/lib/catalina.jar");catalina.getParentFile().mkdirs();zip=new ZipOutputStream(new FileOutputStream(catalina));JdbcDriverResolverTest.entry(zip,"org/apache/catalina/util/ServerInfo.properties","server.info=Apache Tomcat/5.5.36\nserver.number=5.5.36.0\n");zip.close();jar(new File(e2e,"common/lib/ojdbc14.jar"),"10.2.0.5");
            File realProc=new File(root,"process-fixture");ProcessLaunchTest.process(realProc,"98668211","java","-Dcatalina.base="+e2e.getCanonicalPath(),"-Dcatalina.home="+e2e.getCanonicalPath(),"org.apache.catalina.startup.Bootstrap","start");text(new File(realProc,"98668211/environ"),"PATH=/usr/bin\u0000");scan=ProcessLaunchTest.scanner();scan.discoverProcesses(realProc);row=(Map)scan.datasources.get(0);check("IDENTIFIED".equals(row.get("driverResolution"))&&"10.2.0.5".equals(row.get("driverVersion")),"end-to-end process discovery uses real legacy product version before driver strategy");
            // $CATALINA_HOME/conf/catalina.properties is not a Tomcat fallback; -Dcatalina.config is.
            File noProps=base(root,"no-props"),propsHome=new File(root,"props-home"),homeExtra=new File(root,"home-extra/ojdbc11.jar");jar(new File(noProps,"lib/ojdbc8.jar"),"19.3.0");jar(homeExtra,"21.1.0");
            text(new File(propsHome,"conf/catalina.properties"),"common.loader=\""+homeExtra.getParent()+"/*.jar\"\n");
            row=resolve(ProcessLaunchTest.scanner(),context(noProps,propsHome,309),new File(root,"proc/309"));check("19.3.0".equals(row.get("driverVersion")),"home catalina.properties ignored: "+Json.stringify(row));
            String[] cfgArgs={"java","-Dcatalina.config=file:"+new File(propsHome,"conf/catalina.properties").getCanonicalPath(),"-Dcatalina.base="+noProps.getCanonicalPath(),"-Dcatalina.home="+propsHome.getCanonicalPath(),"org.apache.catalina.startup.Bootstrap"};
            File cfgProc=new File(root,"cfg-proc");ProcessLaunchTest.process(cfgProc,"310",cfgArgs);ProcessLaunch withConfig=ProcessLaunch.parse(cfgArgs,noProps);
            AssessmentScan.ProcessContext cfg=new AssessmentScan.ProcessContext(withConfig,310,null,AssessmentScan.map("javaVersion","17"),null);cfg.server=AssessmentScan.map("name","Tomcat 310","version","9.0.100");
            AssessmentScan cfgScan=ProcessLaunchTest.scanner();cfgScan.serverDataSources.procRoot=cfgProc;row=resolve(cfgScan,cfg,new File(cfgProc,"310"));check("21.1.0".equals(row.get("driverVersion")),"-Dcatalina.config file: honored: "+Json.stringify(row));
            // Java 8 home in an excluded path: its own boot/ext entries must not block the decision.
            File jdk=new File(root,"opt/jdk1.8"),excludedBase=base(root,"java8-excluded");jar(new File(excludedBase,"lib/ojdbc8.jar"),"19.3.0");
            JdbcDriverResolverTest.jar(new File(jdk,"jre/lib/rt.jar"),"java.lang.Object","1.8.0",null,null);new File(jdk,"jre/lib/ext").mkdirs();
            for(boolean exclude:new boolean[]{true,false}){
                AssessmentScan s8=ProcessLaunchTest.scanner();if(exclude)s8.excluded.add(new File(root,"opt").getCanonicalPath());
                AssessmentScan.ProcessContext base8=context(excludedBase,excludedBase,exclude?307:308);
                AssessmentScan.ProcessContext c8=new AssessmentScan.ProcessContext(base8.launch,base8.pid,jdk.getCanonicalPath(),AssessmentScan.map("javaVersion","1.8.0_202"),null);c8.server=base8.server;
                c8.launch.bootClasspath.add(new File(jdk,"jre/lib/rt.jar").getCanonicalPath());
                TomcatDataSources helper=s8.serverDataSources.tomcat;helper.configureResolver(c8,s8.serverDataSources.resolver(c8));helper.discover(c8);
                Map r8=(Map)s8.datasources.get(0);Set<String>wanted=Collections.singleton(DRIVER.replace('.','/')+".class");
                JdbcDriverResolver.Search search=s8.jdbcDrivers.new Search(c8,wanted,DRIVER.replace('.','/')+".class",s8.jdbcDrivers.new OpenFiles(c8,new File(root,"proc/"+c8.pid)));
                s8.jdbcDrivers.runtimeOrigins(search);helper.resolve(search,r8);Map d8=search.result();
                check("IDENTIFIED".equals(d8.get("driverResolution"))&&"19.3.0".equals(d8.get("driverVersion")),"Java 8 boot/ext "+(exclude?"excluded":"readable")+" does not block decision: "+Json.stringify(d8));
                check(exclude==(d8.get("driverResolutionCaveat")!=null),"excluded JVM origins reported as caveat only when excluded");
            }
            System.out.println("TomcatDiscoveryTest: "+checks+" checks passed");
        }finally{CollectorTest.remove(root);}
    }
}
