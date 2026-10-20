package assessment;
import java.io.*;
import java.util.*;
import java.util.zip.*;
public final class JdbcDriverResolverTest {
    static int checks;
    static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    static void jar(File f,String driver,String version,String cp,String pom) throws Exception {
        f.getParentFile().mkdirs(); ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f));
        try {
            java.util.jar.Manifest mf=new java.util.jar.Manifest();mf.getMainAttributes().putValue("Manifest-Version","1.0");if(version!=null)mf.getMainAttributes().putValue("Implementation-Version",version);if(cp!=null)mf.getMainAttributes().putValue("Class-Path",cp);ByteArrayOutputStream manifest=new ByteArrayOutputStream();mf.write(manifest);entry(z,"META-INF/MANIFEST.MF",new String(manifest.toByteArray(),"UTF-8"));
            if(driver!=null) entry(z,driver.replace('.','/')+".class","fixture");
            if(pom!=null) entry(z,"META-INF/maven/com.oracle.database.jdbc/ojdbc8/pom.properties","groupId=com.oracle.database.jdbc\nartifactId=ojdbc8\nversion="+pom+"\n");
        } finally { z.close(); }
    }
    static void entry(ZipOutputStream z,String name,String value)throws Exception {z.putNextEntry(new ZipEntry(name));z.write(value.getBytes("UTF-8"));z.closeEntry();}
    static AssessmentScan scan(File root,String cp,long pid,AssessmentScan prior)throws Exception {
        File proc=new File(root,"proc/"+pid);ProcessLaunchTest.text(new File(proc,"environ"),"CLASSPATH="+(cp==null?"":cp)+"\u0000PASSWORD=SECRET\u0000");
        ProcessLaunch launch=ProcessLaunch.parse(new String[]{"java","-Dweblogic.RootDirectory="+root.getCanonicalPath(),"-Dweblogic.Name=AdminServer","weblogic.Server"},root);
        AssessmentScan scan=prior==null?ProcessLaunchTest.scanner():prior;
        AssessmentScan.ProcessContext ctx=new AssessmentScan.ProcessContext(launch,pid,null,AssessmentScan.map(),null);
        scan.serverDataSources.discover(ctx);scan.jdbcDrivers.discover(ctx,proc);return scan;
    }
    static Map row(AssessmentScan s) {return (Map)s.datasources.get(0);}
    public static void main(String[] args)throws Exception {
        File root=CollectorTest.temp("jdbc-driver");
        try {
            File domain=new File(root,"one");JdbcEndpointTest.domain(domain,"db.local");
            File jar=new File(domain,"lib/renamed.jar");jar(jar,"oracle.jdbc.OracleDriver","19.3.0.0",null,null);
            AssessmentScan s=scan(domain,null,100,null);Map row=row(s);
            check("19.3.0.0".equals(row.get("driverVersion")),"Renamed JAR exact class resolves version");
            check(jar.getCanonicalPath().equals(row.get("driverArtifact")),"Artifact source retained");
            check(!Json.stringify(row).contains("SECRET"),"No environment or XML secrets");
            scan(domain,jar.getCanonicalPath(),101,s);
            check("19.3.0.0".equals(row.get("driverVersion")),"Same JAR via classpath and lib must deduplicate");
            File second=new File(domain,"lib/other.jar");jar(second,"oracle.jdbc.OracleDriver","21.1.0",null,null);
            row=row(scan(domain,null,102,null));check("AMBIGUOUS".equals(row.get("driverResolution")) && row.get("driverVersion")==null,"Two exact candidates do not guess");
            File two=new File(root,"two");JdbcEndpointTest.domain(two,"second.local");
            File indirect=new File(two,"external/renamed.jar");jar(indirect,"oracle.jdbc.OracleDriver",null,null,"23.2.0");
            File manifest=new File(two,"launch.jar");jar(manifest,null,null,"external/renamed.jar launch.jar",null);
            row=row(scan(two,manifest.getCanonicalPath(),103,null));check("23.2.0".equals(row.get("driverVersion")),"Manifest dependency and cycle bounded, Maven version");
            AssessmentScan excluded=ProcessLaunchTest.scanner();excluded.excluded.add(indirect.getCanonicalPath());row=row(scan(two,manifest.getCanonicalPath(),104,excluded));
            check("INCOMPLETE".equals(row.get("driverResolution")) && row.get("driverVersion")==null,"Excluded reference cannot claim complete");
            AssessmentScan limited=ProcessLaunchTest.scanner();limited.jdbcDrivers.archivesRead=256;row=row(scan(two,manifest.getCanonicalPath(),105,limited));check("INCOMPLETE".equals(row.get("driverResolution")),"Archive budget honored");
            jar(indirect,"oracle.jdbc.OracleDriver","21.0",null,"23.2.0");row=row(scan(two,manifest.getCanonicalPath(),106,null));check("21.0".equals(row.get("driverVersion")) && "manifest".equals(row.get("driverVersionSource")),"Manifest version takes precedence over Maven metadata");
            File wrong=new File(root,"wrong");JdbcEndpointTest.domain(wrong,"third.local");jar(new File(wrong,"lib/ojdbc8.jar"),"some.other.Driver","99.0",null,null);
            row=row(scan(wrong,null,107,null));check("NOT_FOUND".equals(row.get("driverResolution")),"Filename never substitutes exact class");
            for(String filename:new String[]{"ojdbc6.jar","ojdbc7.jar","ojdbc8.jar","ojdbc11.jar"}) {
                File oracle=new File(root,filename+"-domain");JdbcEndpointTest.domain(oracle,"oracle-sim.local");
                jar(new File(oracle,"lib/"+filename),"oracle.jdbc.OracleDriver","19.22.0.0.0",null,null);
                check("19.22.0.0.0".equals(row(scan(oracle,null,150,null)).get("driverVersion")),"Oracle JDBC " +filename);
            }
            for(String driver:new String[]{"oracle.jdbc.driver.OracleDriver","oracle.jdbc.xa.client.OracleXADataSource"}) {
                File oracle=new File(root,driver);JdbcEndpointTest.domain(oracle,"oracle-sim.local");
                File config=new File(oracle,"config/jdbc/orders.xml");
                ProcessLaunchTest.text(config,JdbcEndpointTest.xml("Orders","jdbc:oracle:thin:@//oracle-sim.local:1521/TEST").replace("oracle.jdbc.OracleDriver",driver));
                jar(new File(oracle,"lib/ojdbc8.jar"),driver,"23.5.0.24.07",null,null);
                check("23.5.0.24.07".equals(row(scan(oracle,null,152,null)).get("driverVersion")),"Oracle driver class "+driver);
            }
            String[][] maven={{"com.ibm.db2.jcc.DB2Driver","jcc-11.5.9.0.jar","com.ibm.db2/jcc","11.5.9.0","jdbc:db2://db2-sim.local:50000/TEST"},
                {"com.informix.jdbc.IfxDriver","jdbc-4.50.10.jar","com.ibm.informix/jdbc","4.50.10","jdbc:informix-sqli://ifx-sim.local:9088/test:INFORMIXSERVER=ol"}};
            for(String[] m:maven) {
                File home=new File(root,m[1]+"-domain");JdbcEndpointTest.domain(home,"sim.local");
                ProcessLaunchTest.text(new File(home,"config/jdbc/orders.xml"),JdbcEndpointTest.xml("Orders",m[4]).replace("oracle.jdbc.OracleDriver",m[0]));
                File lib=new File(home,"lib/"+m[1]);lib.getParentFile().mkdirs();ZipOutputStream out=new ZipOutputStream(new FileOutputStream(lib));
                try { entry(out,"META-INF/MANIFEST.MF","Manifest-Version: 1.0\n\n");entry(out,m[0].replace('.','/')+".class","fixture");
                    String[] ga=m[2].split("/");entry(out,"META-INF/maven/"+m[2]+"/pom.properties","groupId="+ga[0]+"\nartifactId="+ga[1]+"\nversion="+m[3]+"\n"); } finally { out.close(); }
                check(m[3].equals(row(scan(home,null,153,null)).get("driverVersion")),"Maven groupId identifies driver version: "+m[1]);
            }
            File signed=new File(root,"signed");JdbcEndpointTest.domain(signed,"signed.local");File signedJar=new File(signed,"lib/ojdbc8.jar");signedJar.getParentFile().mkdirs();
            ZipOutputStream z=new ZipOutputStream(new FileOutputStream(signedJar));
            try {
                StringBuilder large=new StringBuilder("Manifest-Version: 1.0\nImplementation-Version: 21.9.0.0\n\n");
                for(int n=0;n<2000;n++) large.append("Name: arbitrary/class").append(n).append(".class\nSHA-256-Digest: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n\n");
                entry(z,"META-INF/MANIFEST.MF",large.toString());entry(z,"oracle/jdbc/OracleDriver.class","fixture");
            } finally {z.close();}
            check("21.9.0.0".equals(row(scan(signed,null,151,null)).get("driverVersion")),"Large signed manifest reads only main attributes");
            AssessmentScan disabled=ProcessLaunchTest.scanner();disabled.settings.put("inspectArchives",Boolean.FALSE);row=row(scan(domain,null,108,disabled));check(row.get("driverVersion")==null,"Inspection opt-out honored");
            AssessmentScan multi=scan(two,manifest.getCanonicalPath(),109,null);scan(wrong,null,110,multi);check(multi.datasources.size()==2,"Domains remain separate");
            System.out.println("JDBC_DRIVER_RESOLVER_TESTS=PASS checks="+checks);
        } finally {CollectorTest.remove(root);}
    }
}
