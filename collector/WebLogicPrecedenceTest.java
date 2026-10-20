package assessment;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class WebLogicPrecedenceTest {
    static final String DRIVER="oracle.jdbc.OracleDriver";
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static void jar(File f,String ver,String cp)throws Exception{JdbcDriverResolverTest.jar(f,ver==null?null:DRIVER,ver,cp,null);}
    static Map collect(File domain,List<String> cp,long pid,AssessmentScan scan,String home,String javaVersion,String...opts)throws Exception{
        List<String>args=new ArrayList<String>(Arrays.asList("java","-Dweblogic.RootDirectory="+domain.getCanonicalPath(),"-Dweblogic.Name=Server"+pid,"-cp",ProductHomes.join(cp).replace(", ",File.pathSeparator)));
        args.addAll(Arrays.asList(opts));args.add("weblogic.Server");
        File proc=new File(domain,"proc/"+pid);ProcessLaunchTest.text(new File(proc,"environ"),"PASSWORD=SECRET_CANARY\u0000");
        ProcessLaunch launch=ProcessLaunch.parse(args.toArray(new String[args.size()]),domain);
        AssessmentScan.ProcessContext ctx=new AssessmentScan.ProcessContext(launch,pid,home,AssessmentScan.map("javaVersion",javaVersion),null);
        ctx.mapping=HsPerfData.mapping(scan.metadataText(new File(proc,"maps"),1024*1024,false));
        ctx.server=AssessmentScan.map("name","Server"+pid,"id","server"+pid);scan.serverDataSources.discover(ctx);scan.jdbcDrivers.discover(ctx,proc);return (Map)scan.datasources.get(0);
    }
    static Map candidate(Map row,File f)throws Exception{for(Object raw:(List)row.get("driverCandidates")){Map c=(Map)raw;if(f.getCanonicalPath().equals(c.get("path")))return c;}return null;}
    static File domain(File root,String name)throws Exception{File d=new File(root,name);JdbcEndpointTest.domain(d,"db.example");return d;}
    public static void main(String[] args)throws Exception{
        File root=CollectorTest.temp("weblogic-precedence");
        try{
            File d=domain(root,"modern"),wl=new File(d,"oracle/wlserver/server/lib/weblogic.jar"),driver=new File(d,"oracle/oracle_common/modules/oracle.jdbc/ojdbc8.jar");jar(driver,"19.3.0.0.0",null);
            File bridge=new File(wl.getParentFile(),"bridge.jar");jar(bridge,null,"../../../oracle_common/modules/oracle.jdbc/ojdbc8.jar");
            StringBuilder refs=new StringBuilder("bridge.jar");for(int i=0;i<300;i++){refs.append(" unrelated").append(i).append(".jar");jar(new File(wl.getParentFile(),"unrelated"+i+".jar"),null,null);}jar(wl,null,refs.toString());
            AssessmentScan scan=ProcessLaunchTest.scanner();Map row=collect(d,Arrays.asList(wl.getCanonicalPath()),100,scan,null,"1.8.0_401");
            check("IDENTIFIED".equals(row.get("driverResolution"))&&driver.getCanonicalPath().equals(row.get("driverArtifact")),"DFS reaches Oracle before 300 unrelated manifests: "+Json.stringify(row));
            check("19.3.0.0.0".equals(row.get("driverVersion"))&&scan.jdbcDrivers.archivesRead==1,"unrelated manifests do not consume candidate archive slots");
            List<String> longClasspath=new ArrayList<String>();for(int n=0;n<100;n++)longClasspath.add(new File(d,"missing"+n+".jar").getPath());longClasspath.add(wl.getCanonicalPath());
            Map longRow=collect(d,longClasspath,109,ProcessLaunchTest.scanner(),null,"17");
            check("IDENTIFIED".equals(longRow.get("driverResolution"))&&"19.3.0.0.0".equals(longRow.get("driverVersion")),"Driver beyond old 64-entry classpath limit is selected");
            File pre=new File(d,"drivers/ojdbc8.jar");jar(pre,"21.9.0.0",null);row=collect(d,Arrays.asList(pre.getCanonicalPath(),wl.getCanonicalPath()),101,ProcessLaunchTest.scanner(),null,"17.0.1");
            check(pre.getCanonicalPath().equals(row.get("driverArtifact"))&&Boolean.FALSE.equals(candidate(row,driver).get("loaded")),"PRE_CLASSPATH wins; later driver visible");
            scan=ProcessLaunchTest.scanner();row=collect(d,Arrays.asList(wl.getCanonicalPath()),110,scan,null,"1.8.0_401");for(int i=111;i<=113;i++)row=collect(d,Arrays.asList(wl.getCanonicalPath()),i,scan,null,"1.8.0_401");check("IDENTIFIED".equals(row.get("driverResolution"))&&((List)row.get("associations")).size()==4,"four PIDs agree");
            collect(d,Arrays.asList(pre.getCanonicalPath(),wl.getCanonicalPath()),114,scan,null,"1.8.0_401");check("VARIES".equals(row.get("driverResolution"))&&row.get("driverArtifact")==null,"different managed server preserved per association");
            File old=new File(d,"oracle/oracle_common/modules/oracle.jdbc_11.2.0/ojdbc6.jar"),next=new File(d,"oracle/oracle_common/modules/oracle.jdbc_12.1.0/ojdbc7.jar");jar(old,"11.2.0.4",null);jar(next,"12.1.0.2.0",null);jar(wl,null,"../../../oracle_common/modules/oracle.jdbc_12.1.0/ojdbc7.jar");
            row=collect(d,Arrays.asList(wl.getCanonicalPath()),120,ProcessLaunchTest.scanner(),null,"1.8.0_401");check(next.getCanonicalPath().equals(row.get("driverArtifact"))&&Boolean.FALSE.equals(candidate(row,old).get("onClasspath")),"only referenced module wins; other disk version listed");
            File legacy=domain(root,"legacy"),legacyWl=new File(legacy,"wlserver_10.3/server/lib/weblogic.jar"),legacyDriver=new File(legacyWl.getParentFile(),"ojdbc6.jar");jar(legacyDriver,"11.2.0.3.0",null);jar(legacyWl,null,"ojdbc6.jar");
            row=collect(legacy,Arrays.asList(legacyWl.getCanonicalPath()),130,ProcessLaunchTest.scanner(),null,"1.6.0_45");check(legacyDriver.getCanonicalPath().equals(row.get("driverArtifact")),"legacy WebLogic manifest");
            File home=new File(legacy,"jdk8"),ext=new File(home,"jre/lib/ext/ojdbc6.jar");jar(ext,"11.2.0.4",null);row=collect(legacy,Arrays.asList(legacyWl.getCanonicalPath()),131,ProcessLaunchTest.scanner(),home.getCanonicalPath(),"1.8.0_401");check(ext.getCanonicalPath().equals(row.get("driverArtifact")),"Java 8 extensions precede system classpath");
            File boot=new File(legacy,"boot/ojdbc8.jar");jar(boot,"19.22.0",null);row=collect(legacy,Arrays.asList(legacyWl.getCanonicalPath()),132,ProcessLaunchTest.scanner(),home.getCanonicalPath(),"1.8.0_401","-Xbootclasspath/p:"+boot.getCanonicalPath());check(boot.getCanonicalPath().equals(row.get("driverArtifact")),"boot prepend wins");
            File ambiguous=domain(root,"ambiguous");List<File> libs=new ArrayList<File>();for(int i=0;i<3;i++){File f=new File(ambiguous,"lib/ojdbc"+(6+i)+".jar");jar(f,"19."+i,null);libs.add(f);}
            row=collect(ambiguous,Collections.<String>emptyList(),140,ProcessLaunchTest.scanner(),null,"17");check("AMBIGUOUS".equals(row.get("driverResolution"))&&((List)row.get("driverCandidates")).size()==3,"unordered domain lib ambiguous");
            File fd=new File(ambiguous,"proc/141/fd");fd.mkdirs();check(new ProcessBuilder("ln","-s",libs.get(1).getCanonicalPath(),new File(fd,"42").getPath()).start().waitFor()==0,"fd fixture");
            row=collect(ambiguous,Collections.<String>emptyList(),141,ProcessLaunchTest.scanner(),null,"17");check("PROBABLE".equals(row.get("driverResolution"))&&libs.get(1).getCanonicalPath().equals(row.get("driverArtifact")),"unique opened candidate probable");
            ProcessLaunchTest.text(new File(ambiguous,"proc/142/maps"),"000-111 r--p 000 00:00 1 "+libs.get(2).getCanonicalPath()+" (deleted)\n");row=collect(ambiguous,Collections.<String>emptyList(),142,ProcessLaunchTest.scanner(),null,"17");check("PROBABLE".equals(row.get("driverResolution"))&&Boolean.TRUE.equals(row.get("driverReplacedOnDisk")),"maps reused and replaced JAR indicated");
            scan=ProcessLaunchTest.scanner();scan.excluded.add(next.getCanonicalPath());row=collect(d,Arrays.asList(wl.getCanonicalPath()),150,scan,null,"17");check("INCOMPLETE".equals(row.get("driverResolution"))&&row.get("driverResolutionReason").toString().contains("excluído"),"decisive exclusion is specific");
            scan=ProcessLaunchTest.scanner();scan.jdbcDrivers.archivesRead=255;row=collect(d,Arrays.asList(pre.getCanonicalPath(),next.getCanonicalPath()),151,scan,null,"17");check("IDENTIFIED".equals(row.get("driverResolution")),"later archive limit cannot invalidate winner");
            File spec=domain(root,"spec"),specJar=new File(spec,"lib/ojdbc8.jar");specJar.getParentFile().mkdirs();ZipOutputStream z=new ZipOutputStream(new FileOutputStream(specJar));try{JdbcDriverResolverTest.entry(z,"META-INF/MANIFEST.MF","Manifest-Version: 1.0\nSpecification-Version: 19.4.0\n\n");JdbcDriverResolverTest.entry(z,DRIVER.replace('.','/')+".class","test");}finally{z.close();}
            row=collect(spec,Collections.<String>emptyList(),160,ProcessLaunchTest.scanner(),null,"17");check("19.4.0".equals(row.get("driverVersion"))&&"manifest".equals(row.get("driverVersionSource")),"Specification-Version fallback");
            JdbcDriverResolverTest.jar(next,DRIVER,null,null,null);row=collect(d,Arrays.asList(wl.getCanonicalPath()),161,ProcessLaunchTest.scanner(),null,"17");check("12.1.0".equals(row.get("driverVersion"))&&"module-dir".equals(row.get("driverVersionSource")),"module base is explicitly labeled");
            JdbcDriverResolverTest.jar(specJar,DRIVER,null,null,null);row=collect(spec,Collections.<String>emptyList(),162,ProcessLaunchTest.scanner(),null,"17");check("VERSION_UNAVAILABLE".equals(row.get("driverResolution"))&&row.get("driverArtifact")!=null&&row.get("driverFamily")!=null,"family is not exact version; retain selected JAR");
            File app=new File(root,"application"),appJar=new File(app,"APP-INF/lib/ojdbc8.jar");jar(appJar,"23.5.0",null);
            String xml="<weblogic-application><prefer-application-packages><package-name>oracle.jdbc.*</package-name></prefer-application-packages></weblogic-application>";
            ProcessLaunchTest.text(new File(app,"META-INF/weblogic-application.xml"),xml);
            Map appRow=AssessmentScan.map("configurationScope","application-module","applicationSource",app.getCanonicalPath());
            WebLogicApplicationDrivers.Result ar=WebLogicApplicationDrivers.inspect(ProcessLaunchTest.scanner(),appRow,DRIVER.replace('.','/')+".class");
            check(ar.preferred&&ar.candidates.size()==1&&"23.5.0".equals(ar.candidates.get(0).get("version")),"exploded application preference and driver");
            WebLogicApplicationDrivers.Preference pref=new WebLogicApplicationDrivers.Preference(DRIVER.replace('.','/')+".class");ServerDataSources.parseXml("<weblogic-application><prefer-application-resources><package-name>oracle.jdbc.*</package-name></prefer-application-resources></weblogic-application>",pref);check(!pref.preferred,"resource preferences do not override driver classloading");
            check(WebLogicApplicationDrivers.inspect(ProcessLaunchTest.scanner(),AssessmentScan.map("configurationScope","domain-system-module","applicationSource",app.getCanonicalPath()),DRIVER.replace('.','/')+".class").candidates.isEmpty(),"application libs never leak into system datasource");
            File ear=new File(root,"application.ear");ZipOutputStream out=new ZipOutputStream(new FileOutputStream(ear));try{JdbcDriverResolverTest.entry(out,"META-INF/weblogic-application.xml",xml);out.putNextEntry(new ZipEntry("APP-INF/lib/ojdbc8.jar"));InputStream in=new FileInputStream(appJar);try{byte[] b=new byte[4096];int n;while((n=in.read(b))>=0)out.write(b,0,n);}finally{in.close();}out.closeEntry();}finally{out.close();}
            appRow.put("applicationSource",ear.getCanonicalPath());ar=WebLogicApplicationDrivers.inspect(ProcessLaunchTest.scanner(),appRow,DRIVER.replace('.','/')+".class");check(ar.preferred&&ar.candidates.size()==1&&ar.candidates.get(0).get("path").toString().contains("!/APP-INF/lib/"),"packaged EAR bounded driver inspection");
            Map<String,String> perf=new HashMap<String,String>();perf.put("java.property.java.class.path",pre.getCanonicalPath());perf.put("java.property.java.ext.dirs",home.getCanonicalPath()+"/jre/lib/ext");perf.put("sun.property.sun.boot.class.path",boot.getCanonicalPath());
            ProcessLaunch lp=ProcessLaunch.parse(new String[]{"java","-cp",wl.getCanonicalPath(),"weblogic.Server"},root,perf);check(lp.classpath.get(0).equals(pre.getCanonicalPath())&&lp.bootClasspath.size()==1&&lp.extDirs.size()==1,"perfdata supplies effective paths");
            check(!Json.stringify(row).contains("SECRET_CANARY")&&!Json.stringify(row).contains("jdbc:"),"safe output");
            System.out.println("WEBLOGIC_PRECEDENCE=PASS checks="+checks+" DFS modules pre-classpath boot ext fd maps deleted per-PID limits provenance privacy");
        }finally{CollectorTest.remove(root);}
    }
}
