package assessment;

import java.io.*;
import java.util.*;

/** Product homes are identified from target files while their internals stay out of the sweep. No server is executed. */
public final class ProductHomesTest {
    static void check(boolean value, String message) { CollectorTest.check(value, message); }
    static void text(File file, String value) throws Exception { ProcessLaunchTest.text(file, value); }
    static void jar(File file, String... attributes) throws Exception { ProcessLaunchTest.write(file, ProcessLaunchTest.jar(ProcessLaunchTest.attrs(attributes), ProcessLaunchTest.entries())); }
    static Map home(AssessmentScan scan, File home) { return scan.servers.get("home:" + home.getPath()); }
    static boolean deferred(AssessmentScan scan, String name) { for (File file : scan.deferredArchives) if (file.getName().equals(name)) return true; return false; }
    static boolean library(AssessmentScan scan, String path) { for (Object row : scan.libraries) if (path.equals(((Map) row).get("path"))) return true; return false; }
    static String registry(String patch) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<registry home=\"/u01/oracle\" platform=\"226\" xmlns=\"http://xmlns.oracle.com/cie/gdr/rgy\">"
            + "<distributions><distribution status=\"installed\" name=\"Oracle Fusion Middleware Infrastructure\" version=\"12.2.1.4.0\"/>"
            + "<distribution status=\"installed\" name=\"WebLogic Server\" version=\"12.2.1.4.0\"><features><feature status=\"installed\" name=\"Core Server\" version=\"12.2.1.4.0\"/></features></distribution>"
            + "</distributions>" + patch + "</registry>";
    }

    static void oracleHome(File root) throws Exception {
        File oh = new File(root, "u01/oracle"), wl = new File(oh, "wlserver");
        text(new File(oh, "inventory/registry.xml"), registry("<patches><patch id=\"33416868\" description=\"WLS PATCH SET UPDATE\"/></patches>"));
        jar(new File(wl, "server/lib/weblogic.jar"), "Implementation-Title", "WebLogic Server", "Implementation-Version", "12.2.1.4.0-manifest");
        jar(new File(wl, "modules/internal-module.jar"), "Implementation-Version", "1.0");
        jar(new File(oh, "coherence/lib/coherence.jar"), "Implementation-Version", "1.0");
        jar(new File(oh, "oracle_common/modules/oracle.jdbc/ojdbc8.jar"), "Implementation-Title", "Oracle JDBC driver", "Implementation-Version", "19.3.0.0.0");
        File domain = new File(oh, "user_projects/domains/base_domain");
        text(new File(domain, "config/config.xml"), "<domain/>");
        text(new File(domain, "autodeploy/orders.war"), "war");
        jar(new File(domain, "lib/custom.jar"), "Implementation-Version", "1.0");
        AssessmentScan scan = ProcessLaunchTest.scanner(); scan.walk(root, 0);
        Map row = home(scan, oh.getCanonicalFile());
        check(row != null && "WebLogic".equals(row.get("type")) && "12.2.1.4.0".equals(row.get("version")), "Oracle Home version comes from inventory/registry.xml: " + scan.servers);
        check(Arrays.asList("33416868").equals(row.get("patches")) && String.valueOf(row.get("distribution")).contains("WebLogic Server"), "Registry patches and distributions are retained");
        check(scan.servers.size() == 1, "The pruned weblogic.jar does not add a second installation row");
        check(!deferred(scan, "internal-module.jar") && !deferred(scan, "coherence.jar") && deferred(scan, "custom.jar"), "Product internals are pruned, domain libraries are swept");
        check(scan.applicationsByPath.containsKey(new File(domain, "autodeploy/orders.war").getCanonicalPath()), "Domain deployments inside the Oracle Home stay in scope");
        check(library(scan, new File(oh, "oracle_common/modules/oracle.jdbc/ojdbc8.jar").getCanonicalPath()), "Bundled JDBC driver keeps its library row");

        text(new File(oh, "inventory/registry.xml"), registry("").replace("WebLogic Server", "Other Product"));
        AssessmentScan fallback = ProcessLaunchTest.scanner(); fallback.walk(root, 0);
        check("12.2.1.4.0-manifest".equals(home(fallback, oh.getCanonicalFile()).get("version")), "Missing registry release falls back to the weblogic.jar manifest");
        text(new File(oh, "inventory/registry.xml"), "<!DOCTYPE registry [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><registry>&x;</registry>");
        AssessmentScan hostile = ProcessLaunchTest.scanner(); hostile.walk(root, 0);
        check("12.2.1.4.0-manifest".equals(home(hostile, oh.getCanonicalFile()).get("version")), "Hostile registry XML is rejected by the secure parser");
        text(new File(oh, "inventory/registry.xml"), registry("<patches><patch id=\"33416868\"/></patches>"));

        File proc = new File(root, "proc");
        ProcessLaunchTest.process(proc, "98767101", "java", "-Dweblogic.home=" + new File(wl, "server").getCanonicalPath(), "-Dweblogic.Name=AdminServer",
            "-Dweblogic.RootDirectory=" + domain.getCanonicalPath(), "weblogic.Server");
        AssessmentScan linked = ProcessLaunchTest.scanner(); linked.discoverProcesses(proc);
        Map server = WebLogicVersionTest.server(linked, "AdminServer");
        check("12.2.1.4.0".equals(server.get("version")) && Arrays.asList("33416868").equals(server.get("patches")), "Live WebLogic server reads the Oracle Home registry: " + server);
        for (Object app : linked.applications) check("12.2.1.4.0".equals(((Map) app).get("frameworkVersion")), "Registry release reaches the server application");
        check(linked.processRoots.equals(Arrays.asList(domain.getCanonicalPath())), "The live domain becomes a sweep root");
        List<String> walked = linked.walkProcessRoots(Arrays.asList(new File(root, "elsewhere").getPath()));
        check(walked.size() == 1 && linked.applicationsByPath.containsKey(new File(domain, "autodeploy/orders.war").getCanonicalPath()), "Uncovered server base is swept");
        AssessmentScan covered = ProcessLaunchTest.scanner(); covered.discoverProcesses(proc);
        check(covered.walkProcessRoots(Arrays.asList(root.getPath())).isEmpty(), "A base already under includePaths is not swept twice");
    }

    static void jbossAndTomcat(File root) throws Exception {
        File eap = new File(root, "eap");
        jar(new File(eap, "jboss-modules.jar"), "Implementation-Version", "1.12.0");
        text(new File(eap, "version.txt"), "Red Hat JBoss Enterprise Application Platform - Version 7.4.0.GA\n");
        jar(new File(eap, "modules/system/layers/base/org/internal/main/internal.jar"), "Implementation-Version", "1.0");
        jar(new File(eap, "modules/com/oracle/main/ojdbc8.jar"), "Implementation-Title", "Oracle JDBC driver", "Implementation-Version", "19.3.0.0.0");
        text(new File(eap, "standalone/deployments/billing.war"), "war");
        File wildfly = new File(root, "wildfly");
        jar(new File(wildfly, "jboss-modules.jar"), "Implementation-Version", "2.0");
        text(new File(wildfly, "bin/product.conf"), "slot=wildfly-full\n");
        text(new File(wildfly, "modules/system/layers/base/org/jboss/as/product/wildfly-full/dir/META-INF/MANIFEST.MF"),
            "Manifest-Version: 1.0\nJBoss-Product-Release-Name: WildFly Full\nJBoss-Product-Release-Version: 26.1.3.Final\n\n");
        File tomcat = new File(root, "tomcat");
        jar(new File(tomcat, "lib/catalina.jar"), "Implementation-Title", "Apache Tomcat", "Implementation-Version", "9.0.85");
        jar(new File(tomcat, "lib/tomcat-util.jar"), "Implementation-Version", "9.0.85");
        jar(new File(tomcat, "lib/ojdbc11.jar"), "Implementation-Title", "Oracle JDBC driver", "Implementation-Version", "23.3.0.0.0");
        text(new File(tomcat, "bin/catalina.sh"), "#!/bin/sh\n");
        text(new File(tomcat, "webapps/shop.war"), "war");
        AssessmentScan scan = ProcessLaunchTest.scanner(); scan.walk(root, 0);
        Map jboss = home(scan, eap.getCanonicalFile()), wf = home(scan, wildfly.getCanonicalFile()), cat = home(scan, tomcat.getCanonicalFile());
        check(jboss != null && "JBoss".equals(jboss.get("type")) && "7.4.0.GA".equals(jboss.get("version")), "EAP release comes from version.txt: " + scan.servers);
        check(wf != null && "WildFly".equals(wf.get("type")) && "26.1.3.Final".equals(wf.get("version")), "WildFly falls back to the product module manifest");
        check(cat != null && "Tomcat".equals(cat.get("type")) && "9.0.85".equals(cat.get("version")) && scan.servers.size() == 3, "Tomcat version comes from catalina.jar without a duplicate row");
        check(!deferred(scan, "internal.jar") && !deferred(scan, "tomcat-util.jar"), "Module system and Tomcat libraries are product internals");
        check(library(scan, new File(eap, "modules/com/oracle/main/ojdbc8.jar").getCanonicalPath()) && library(scan, new File(tomcat, "lib/ojdbc11.jar").getCanonicalPath()),
            "Customer JDBC modules and Tomcat lib drivers stay inventoried");
        check(scan.applicationsByPath.containsKey(new File(eap, "standalone/deployments/billing.war").getCanonicalPath())
            && scan.applicationsByPath.containsKey(new File(tomcat, "webapps/shop.war").getCanonicalPath()), "Deployments and webapps are swept");
        check(ProductHomes.versionText("WildFly Full - Version 26.1.3.Final") [0].equals("WildFly") && ProductHomes.versionText("Some Product - Version 1.0") == null
            && ProductHomes.versionText("JBoss EAP - Version 7.4.0.GA; rm -rf /") [1].equals("7.4.0.GA"), "version.txt parsing is strict");

        File proc = new File(root, "proc");
        ProcessLaunchTest.process(proc, "98767102", "java", "-Djboss.home.dir=" + eap.getCanonicalPath(), "-jar", new File(eap, "jboss-modules.jar").getCanonicalPath(),
            "-mp", new File(eap, "modules").getCanonicalPath(), "org.jboss.as.standalone");
        AssessmentScan linked = ProcessLaunchTest.scanner(); linked.discoverProcesses(proc);
        Map server = null; for (Map row : linked.servers.values()) server = row;
        check(server != null && "7.4.0.GA".equals(server.get("version")), "Live JBoss without a product manifest falls back to version.txt: " + linked.servers);
        check(linked.processRoots.equals(Arrays.asList(new File(eap, "standalone").getCanonicalPath())), "JBoss server base becomes a sweep root");
    }

    public static void main(String[] args) throws Exception {
        File root = CollectorTest.temp("product-homes").getCanonicalFile();
        try { oracleHome(new File(root, "oracle")); jbossAndTomcat(new File(root, "middleware")); }
        finally { CollectorTest.remove(root); }
        System.out.println("PRODUCT_HOMES_TESTS=PASS oracle-home-registry patches manifest-fallback hostile-xml pruned-internals domains bundled-jdbc process-registry process-roots EAP WildFly Tomcat");
    }
}
