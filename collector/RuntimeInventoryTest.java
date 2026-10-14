package assessment;

import java.io.*;
import java.util.*;

/** Observed runtime fixtures. No discovered Java or application is executed. */
public final class RuntimeInventoryTest {
    static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}

    public static Map snapshot(String hostId,String jobId,String capturedAt)throws Exception {
        File root=CollectorTest.temp("el9-runtime-without-compiler");
        try {
            File home=new File(root,"java-21-openjdk-21.0.12.1.1-1.2.0.1.el9.aarch64").getCanonicalFile();
            byte[] elf=new byte[20];elf[0]=127;elf[1]='E';elf[2]='L';elf[3]='F';elf[4]=2;elf[5]=1;elf[18]=(byte)183;
            ProcessLaunchTest.write(new File(home,"bin/java"),elf);
            ProcessLaunchTest.text(new File(home,"release"),"JAVA_VERSION=\"21.0.12\"\nIMPLEMENTOR=\"Fixture vendor\"\nOS_ARCH=\"aarch64\"\n");
            check(!new File(home,"bin/javac").exists(),"Fixture must not contain a compiler");
            File proc=new File(root,"proc");
            File collectorJar=new File(root,"installation/collector.jar"),helperJar=new File(root,"java-assessment-uninstall/collector.jar");
            ProcessLaunchTest.text(collectorJar,"owned code-location fixture");ProcessLaunchTest.text(helperJar,"owned helper fixture");
            ProcessLaunchTest.process(proc,"98766981",new File(home,"bin/java").getPath(),"App");
            ProcessLaunchTest.process(proc,"98766982",new File(home,"bin/java").getPath(),"App");
            String[] tools={"assessment.AssessmentCollector","assessment.AgentTool","assessment.UninstallWorker","assessment.JavaProbe"};
            File toolProc=new File(root,"tool-proc");
            for(int i=0;i<tools.length;i++) {
                String jar=tools[i].equals("assessment.UninstallWorker")?helperJar.getPath():collectorJar.getPath();
                ProcessLaunchTest.process(proc,String.valueOf(98766983+i),new File(home,"bin/java").getPath(),"-cp",jar,tools[i]);
                ProcessLaunchTest.process(toolProc,String.valueOf(98766983+i),new File(home,"bin/java").getPath(),"-cp",jar,tools[i]);
            }
            AssessmentScan internalOnly=new AssessmentScan(new Properties(),hostId,"brunods-dev-vnic",AssessmentScan.map("pauseMillis",0),collectorJar);
            internalOnly.discoverProcesses(toolProc);
            check(internalOnly.runtimes.isEmpty()&&internalOnly.applications.isEmpty()&&internalOnly.processes.isEmpty(),
                "Collector/helper/diagnostic JVMs alone must not establish application runtime use");
            AssessmentScan scan=new AssessmentScan(new Properties(),hostId,"brunods-dev-vnic",
                AssessmentScan.map("maxFiles",1000,"maxDurationSeconds",120,"pauseMillis",0),collectorJar);
            check(scan.addRuntime(home,false,"Filesystem only",scan.timestamp,null,null,null)==null,
                "An unused standalone runtime remains outside the application inventory");
            scan.discoverProcesses(proc);
            check(scan.runtimes.size()==1,"Two active JVMs without javac must count as one Java installation");
            check(scan.processes.size()==2&&scan.applications.size()==2,"Both application JVMs are retained and owned agent tools excluded");
            Map row=(Map)scan.runtimes.values().iterator().next();
            check("JRE".equals(row.get("kind"))&&Boolean.TRUE.equals(row.get("inUse")),"Runtime classified as JRE and observed in use");
            check("21.0.12".equals(row.get("version"))&&"aarch64 (64-bit)".equals(row.get("architecture")),"Version and architecture read from metadata/ELF");
            for(Object value:scan.applications)check(home.getPath().equals(((Map)value).get("javaHome")),"Application links to inventoried Java home");
            String id=(String)row.get("id");
            check(scan.addRuntime(home,false,"Collector metadata",scan.timestamp,"21.0.12","Fixture vendor",null)==row,
                "Later non-application metadata enriches an already observed runtime");
            scan.walk(home,0);
            check(scan.runtimes.size()==1&&id.equals(row.get("id")),"Filesystem rediscovery does not duplicate the installation");
            return AssessmentScan.map("schemaVersion","1.0","eventId",UUID.randomUUID().toString(),"jobId",jobId,
                "capturedAt",capturedAt,"collectorJavaVersion","21.0.12",
                "host",AssessmentScan.map("id",hostId,"hostname","brunods-dev-vnic","architecture","aarch64"),
                "runtimes",new ArrayList(scan.runtimes.values()),"processes",scan.processes,"applications",scan.applications,
                "servers",new ArrayList(scan.servers.values()),"libraries",scan.libraries,"datasources",scan.datasources,
                "coverage",AssessmentScan.map("status",scan.partial?"partial":"complete","warnings",scan.warnings,"stats",AssessmentScan.map()));
        } finally {CollectorTest.remove(root);}
    }

    public static void main(String[] args)throws Exception {
        snapshot("fixture-host","fixture-job",AssessmentScan.now());
        System.out.println("RUNTIME_INVENTORY_TESTS=PASS OpenJDK21 EL9 aarch64 no-javac two-JVMs one-installation metadata application-links dedup unused-runtime-omission owned-agent-tools-excluded");
    }
}
