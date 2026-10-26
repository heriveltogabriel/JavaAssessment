package assessment;
import java.io.*; import java.util.*;
public class RealProcCheck { public static void main(String[] a) throws Exception {
  AssessmentScan scan = ProcessLaunchTest.scanner();
  scan.discoverProcesses(new File("/proc"));
  for (Object o : scan.datasources) { Map r = (Map) o;
    System.out.println("DS " + r.get("name") + " -> " + r.get("driverResolution") + " " + r.get("driverVersion") + " | " + r.get("driverResolutionReason")); }
  System.out.println("datasources=" + scan.datasources.size()); } }
