package assessment;

import java.io.File;
import java.util.*;

/** An allowlisted description of a Java launcher. Never retains the original command line. */
final class ProcessLaunch {
    File cwd;
    final Map<String,String> springSystem = new LinkedHashMap<String,String>(), springArguments = new LinkedHashMap<String,String>();
    String mainClass, jarPath, moduleName, serverType, serverHome, serverBase, serverName, applicationType;
    String serverConfigDirectory, serverConfigFile, webLogicHome, wlsHome;
    final List<String> classpath = new ArrayList<String>(), modulePaths = new ArrayList<String>();
    boolean javaCommand, opaqueArguments, serverBaseExplicit, explicitClasspath, perfDataCompleted;

    static boolean javaExecutable(String value) {
        if (value == null) return false;
        return new File(value).getName().equals("java");
    }
    static String identifier(String value) {
        return value != null && value.length() <= 512 && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*") ? value : null;
    }
    static String label(String value) {
        return value != null && value.length() > 0 && value.length() <= 200 && value.matches("[A-Za-z0-9_.@ -]+") ? value : null;
    }
    static String path(String value, File cwd) {
        if (value == null || value.length() == 0 || value.length() > 4096 || value.indexOf(':') >= 0) return null;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return null;
        File file = new File(value);
        if (!file.isAbsolute()) { if (cwd == null) return null; file = new File(cwd, value); }
        return file.getPath();
    }
    static void paths(List<String> target, String value, File cwd) {
        if (value == null) return;
        String[] parts = value.split(":", -1);
        for (int i = 0; i < parts.length && target.size() < 64; i++) {
            String resolved = path(parts[i].length() == 0 ? "." : parts[i], cwd);
            if (resolved != null && !target.contains(resolved)) target.add(resolved);
        }
    }
    void property(String arg, File cwd) {
        if (!arg.startsWith("-D")) return;
        int equals = arg.indexOf('='); if (equals < 0) return;
        String key = arg.substring(2, equals), value = arg.substring(equals + 1);
        SpringConfiguration.input(springSystem, key, value);
        if (key.equals("catalina.home") || key.equals("jboss.home.dir")) serverHome = path(value, cwd);
        else if (key.equals("weblogic.home")) webLogicHome = path(value, cwd);
        else if (key.equals("wls.home")) wlsHome = path(value, cwd);
        else if (key.equals("catalina.base") || key.equals("jboss.server.base.dir") || key.equals("weblogic.RootDirectory")) { serverBase = path(value, cwd); serverBaseExplicit = serverBase != null; }
        else if (key.equals("weblogic.Name") || key.equals("jboss.server.name") || key.equals("jboss.node.name")) serverName = label(value);
        else if (key.equals("jboss.server.config.dir")) serverConfigDirectory = path(value, cwd);
        else if (key.equals("module.path")) paths(modulePaths, value, cwd);
    }
    static boolean takesValue(String arg) {
        return Arrays.asList("--module-path", "-p", "--upgrade-module-path", "--add-modules", "--limit-modules", "--add-exports", "--add-opens", "--add-reads", "--patch-module", "--enable-native-access", "--source", "--describe-module", "-d").contains(arg);
    }
    static boolean springLauncher(String main) {
        return main != null && main.matches("org\\.springframework\\.boot\\.loader\\.(launch\\.)?(JarLauncher|WarLauncher|PropertiesLauncher)");
    }
    /**
     * Completes a launcher hidden by @argfiles or CLASSPATH with the running JVM's perf data.
     * Only -D options, the effective classpath and the main class or jar are taken; other arguments are discarded.
     */
    static ProcessLaunch parse(String[] args, File cwd, Map<String,String> perf) {
        ProcessLaunch launch = parse(args, cwd);
        if (perf == null || args == null || args.length == 0 || !launch.javaCommand) return launch;
        List<String> original = Arrays.asList(args), merged = new ArrayList<String>();
        merged.add(args[0]);
        String vmArgs = perf.get("java.rt.vmArgs");
        // shortcut: vmArgs is space-joined, so a -D value containing spaces is cut; acceptable for allowlisted path/name keys.
        if (vmArgs != null) for (String token : vmArgs.split(" ")) if (token.startsWith("-D") && (launch.opaqueArguments || !original.contains(token)) && merged.size() < 256) merged.add(token);
        String command = perf.get("sun.rt.javaCommand"), first = command == null ? null : command.trim().split(" ")[0];
        boolean jar = first != null && first.endsWith(".jar");
        String classpath = perf.get("java.property.java.class.path");
        if (classpath != null && launch.jarPath == null && !(launch.opaqueArguments && jar) && (launch.opaqueArguments || !launch.explicitClasspath)) { merged.add("-cp"); merged.add(classpath); }
        if (launch.opaqueArguments) {
            if (first != null && first.length() > 0) { if (jar) merged.add("-jar"); merged.add(first); }
        } else merged.addAll(original.subList(1, args.length));
        if (merged.equals(original)) return launch;
        ProcessLaunch completed = parse(merged.toArray(new String[merged.size()]), cwd);
        completed.opaqueArguments = launch.opaqueArguments; completed.perfDataCompleted = true;
        return completed;
    }
    static ProcessLaunch parse(String[] args, File cwd) {
        ProcessLaunch launch = new ProcessLaunch(); launch.cwd = cwd;
        if (args == null || args.length == 0) return launch;
        launch.javaCommand = javaExecutable(args[0]);
        int after = args.length;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("@")) { launch.opaqueArguments = true; break; }
            if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path")) {
                launch.explicitClasspath = true; if (i + 1 < args.length) paths(launch.classpath, args[++i], cwd); continue;
            }
            if (arg.startsWith("--class-path=")) { launch.explicitClasspath = true; paths(launch.classpath, arg.substring(13), cwd); continue; }
            if (arg.equals("-jar")) { if (i + 1 < args.length) launch.jarPath = path(args[++i], cwd); after = i + 1; break; }
            if (arg.equals("-m") || arg.equals("--module") || arg.startsWith("--module=")) {
                String module = arg.startsWith("--module=") ? arg.substring(9) : i + 1 < args.length ? args[++i] : null;
                if (module != null) { int slash = module.indexOf('/'); launch.moduleName = identifier(slash < 0 ? module : module.substring(0, slash)); if (slash >= 0) launch.mainClass = identifier(module.substring(slash + 1)); }
                after = i + 1; break;
            }
            if (arg.equals("--")) { if (i + 1 < args.length) launch.mainClass = identifier(args[++i]); after = i + 1; break; }
            if (takesValue(arg)) { if (i + 1 < args.length) i++; continue; }
            if (arg.startsWith("-")) { launch.property(arg, cwd); continue; }
            launch.mainClass = identifier(arg); after = i + 1; break;
        }
        if ("weblogic.Server".equals(launch.mainClass)) launch.serverType = "WebLogic";
        else if ("org.apache.catalina.startup.Bootstrap".equals(launch.mainClass)) launch.serverType = "Tomcat";
        else if ("org.jboss.Main".equals(launch.mainClass)) launch.serverType = "JBoss";
        else if ("org.eclipse.jetty.start.Main".equals(launch.mainClass) || "org.eclipse.jetty.xml.XmlConfiguration".equals(launch.mainClass)) launch.serverType = "Jetty";
        if (springLauncher(launch.mainClass)) launch.applicationType = "Spring Boot";
        boolean modules = "org.jboss.modules.Main".equals(launch.mainClass) || launch.jarPath != null && new File(launch.jarPath).getName().equals("jboss-modules.jar");
        if (modules) {
            boolean foundModule = false;
            for (int i = after; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("-D")) { launch.property(arg, cwd); continue; }
                if (foundModule) continue;
                if (arg.equals("-mp") || arg.equals("-modulepath") || arg.equals("--modulepath")) { if (i + 1 < args.length) paths(launch.modulePaths, args[++i], cwd); continue; }
                if (arg.equals("-jaxpmodule") || arg.equals("-cp") || arg.equals("-class") || arg.equals("-dep") || arg.equals("-logmodule")) { if (i + 1 < args.length) i++; continue; }
                if (arg.startsWith("-")) continue;
                foundModule = true;
                if (Arrays.asList("org.jboss.as.standalone", "org.jboss.as.server", "org.jboss.as.host-controller", "org.jboss.as.process-controller").contains(arg)) { launch.moduleName = arg; launch.serverType = "JBoss"; }
            }
            if (launch.serverHome == null && launch.jarPath != null) launch.serverHome = new File(launch.jarPath).getParent();
        }
        if (launch.serverHome == null && "Tomcat".equals(launch.serverType)) {
            for (String entry : launch.classpath) if (new File(entry).getName().equals("bootstrap.jar")) { File bin = new File(entry).getParentFile(); if (bin != null && bin.getParentFile() != null) launch.serverHome = bin.getParent(); }
        }
        if ("WebLogic".equals(launch.serverType)) {
            // These properties identify WL_HOME/server, not the domain directory.
            launch.serverHome = launch.webLogicHome != null ? launch.webLogicHome : launch.wlsHome;
            if (launch.serverBase == null && cwd != null) launch.serverBase = path(cwd.getPath(), null);
        } else if (launch.serverBase == null) launch.serverBase = launch.serverHome;
        if ("org.jboss.as.standalone".equals(launch.moduleName)) {
            for (int i = after; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("-c") || arg.equals("--server-config") || arg.equals("--read-only-server-config")) {
                    if (i + 1 < args.length) launch.serverConfigFile = args[++i];
                } else if (arg.startsWith("--server-config=")) launch.serverConfigFile = arg.substring(16);
                else if (arg.startsWith("--read-only-server-config=")) launch.serverConfigFile = arg.substring(26);
            }
        }
        for (int i = after; i < args.length; i++) {
            String arg = args[i]; int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 2) SpringConfiguration.input(launch.springArguments, arg.substring(2, equals), arg.substring(equals + 1));
        }
        return launch;
    }
}
