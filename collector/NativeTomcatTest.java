package assessment;

import java.io.*;
import java.util.*;

/** Passive native launcher fixtures. No Java application or service is started. */
public final class NativeTomcatTest {
    static void check(boolean v,String m){ProcessLaunchTest.check(v,m);}
    public static void main(String[] args)throws Exception {
        File root=CollectorTest.temp("native-tomcat");
        try {
            File home=new File(root,"tomcat"),jdk=new File(root,"jdk"),proc=new File(root,"proc");
            ProcessLaunchTest.text(new File(jdk,"release"),"JAVA_VERSION=\"17.0.1\"\nIMPLEMENTOR=\"Fixture\"\n");
            ProcessLaunchTest.text(new File(jdk,"bin/java"),"fixture never executed");
            ProcessLaunchTest.text(new File(home,"conf/server.xml"),"<Server><Service><Engine name=\"Catalina\"><Host name=\"localhost\" appBase=\"webapps\"/></Engine></Service></Server>");
            ProcessLaunchTest.text(new File(home,"conf/context.xml"),"<Context><Resource name=\"jdbc/App\" type=\"javax.sql.DataSource\" driverClassName=\"oracle.jdbc.OracleDriver\" url=\"jdbc:oracle:thin:@db.example:1521/APP\"/></Context>");
            String base=home.getCanonicalPath(),java=jdk.getCanonicalPath();
            String[] command={"/usr/bin/jsvc","-user","tomcat","-pidfile","/run/tomcat.pid","-outfile","/var/log/tomcat.out","-errfile","/var/log/tomcat.err","-home",java,"-procname","tomcat","-wait","10","-umask","0027","-jvm","server","-cwd","-nodetach","-keepstdin","-debug","-verbose","-Dcatalina.base="+base,"-Dcatalina.home="+base,"-Dpassword=DO_NOT_RETAIN","-cp",base+"/bin/bootstrap.jar","org.apache.catalina.startup.Bootstrap","start"};
            ProcessLaunch parsed=ProcessLaunch.parse(command,null);
            check("Tomcat".equals(parsed.serverType)&&parsed.nativeTomcat&&java.equals(parsed.nativeJavaHome)&&base.equals(parsed.serverBase),"native flags preserve Tomcat main and Java home");
            ProcessLaunchTest.process(proc,"98776001",command);
            ProcessLaunchTest.text(new File(proc,"98776001/comm"),"jsvc\n");
            String maps="00400000-00410000 r-xp 00000000 08:01 123 "+java+"/lib/server/libjvm.so\n";
            ProcessLaunchTest.process(proc,"98776002","/usr/bin/native-wrapper","-Dcatalina.base="+base,"-Dcatalina.home="+base,"org.apache.catalina.startup.Bootstrap","start");
            ProcessLaunchTest.text(new File(proc,"98776002/maps"),maps);
            ProcessLaunchTest.process(proc,"98776003","/usr/bin/unrelated-wrapper","example.Other");
            ProcessLaunchTest.text(new File(proc,"98776003/maps"),maps);
            AssessmentScan scan=ProcessLaunchTest.scanner();scan.discoverProcesses(proc);
            check(scan.processes.size()==2,"jsvc and mapped Tomcat accepted; unrelated libjvm wrapper rejected");
            for(Object value:scan.processes){Map process=(Map)value;check(java.equals(process.get("javaHome"))&&"Tomcat iniciado via jsvc".equals(process.get("launcherObservation")),"native process home and observation");}
            check(!scan.datasources.isEmpty(),"native launcher datasource inspected");
            AssessmentScan excluded=ProcessLaunchTest.scanner();excluded.excluded.add(base);excluded.discoverProcesses(proc);
            check(excluded.processes.size()==2&&excluded.datasources.isEmpty(),"excluded Tomcat remains visible without datasource inspection");
            for(Object value:excluded.processes)check(((Map)value).get("excludedPathObservation")!=null,"excluded process observation");
            String serialized=Json.stringify(AssessmentScan.map("processes",scan.processes,"apps",scan.applications,"datasources",scan.datasources,"warnings",scan.warnings));
            check(!serialized.contains("DO_NOT_RETAIN")&&!serialized.contains("-Dpassword")&&!serialized.contains("jdbc:oracle"),"no raw commands URL or credentials retained");
            System.out.println("NATIVE_TOMCAT=PASS jsvc flags libjvm negative excluded-process privacy");
        }finally{CollectorTest.remove(root);}
    }
}
