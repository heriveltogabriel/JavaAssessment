package assessment;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded configuration evidence. No XML entities, JDBC calls, credentials or raw URLs are retained. */
final class ServerDataSources {
    static final int CONFIG_LIMIT = 256 * 1024, MAX_CONFIGS = 128, MAX_ELEMENTS = 12000, MAX_DEPTH = 64, SMALL_LIMIT = 64 * 1024;
    static final Set<String> TOMCAT_TYPES = new HashSet<String>(Arrays.asList("javax.sql.DataSource", "javax.sql.XADataSource", "javax.sql.ConnectionPoolDataSource",
        "com.zaxxer.hikari.HikariDataSource", "oracle.ucp.jdbc.PoolDataSource", "oracle.ucp.jdbc.PoolDataSourceImpl", "oracle.ucp.jdbc.PoolXADataSource", "oracle.ucp.jdbc.PoolXADataSourceImpl",
        "org.apache.tomcat.jdbc.pool.DataSource", "org.apache.tomcat.jdbc.pool.XADataSource", "org.apache.commons.dbcp.BasicDataSource", "org.apache.commons.dbcp2.BasicDataSource",
        "org.apache.tomcat.dbcp.dbcp.BasicDataSource", "org.apache.tomcat.dbcp.dbcp2.BasicDataSource"));
    static final Set<String> TOMCAT_FACTORIES = new HashSet<String>(Arrays.asList("org.apache.tomcat.jdbc.pool.DataSourceFactory", "org.apache.tomcat.dbcp.dbcp.BasicDataSourceFactory",
        "org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory", "org.apache.commons.dbcp.BasicDataSourceFactory", "org.apache.commons.dbcp2.BasicDataSourceFactory",
        "com.zaxxer.hikari.HikariJNDIFactory", "oracle.ucp.jdbc.PoolDataSourceImpl"));
    final AssessmentScan scan;
    final Map<String,List<Map>> cache = new LinkedHashMap<String,List<Map>>();
    // Files whose rows depend on one process's properties, environment or TNS files are cached per PID.
    final Set<String> processDependent = new HashSet<String>();
    final WebLogicDataSources weblogic;
    File procRoot = new File("/proc");
    JdbcEndpoint.Resolver resolver; long resolverPid = -1;
    int cachedRows;
    ServerDataSources(AssessmentScan scan) { this.scan = scan; this.weblogic = new WebLogicDataSources(this); }

    JdbcEndpoint.Resolver resolver(AssessmentScan.ProcessContext context) {
        if (resolver == null || resolverPid != context.pid) { resolver = new JdbcEndpoint.Resolver(scan, new File(procRoot, String.valueOf(context.pid))); resolverPid = context.pid; }
        return resolver;
    }
    void discover(AssessmentScan.ProcessContext context) {
        ProcessLaunch launch = context.launch;
        if ("Tomcat".equals(launch.serverType) && launch.serverBase != null) {
            File base = new File(launch.serverBase);
            List<Map> server = inspectRequired(new File(base, "conf/server.xml"), "Tomcat", context, null);
            inspect(new File(base, "conf/context.xml"), "Tomcat", context);
            descriptors(new File(base, "conf"), 2, context);
            Set<String> appBases = new LinkedHashSet<String>();
            for (Map row : server) if ("appBase".equals(row.get("marker")) && appBases.size() < 8) {
                String value = resolver(context).expand((String)row.get("appBase"), new ArrayList<String>());
                String path = value == null ? null : ProcessLaunch.path(value, base);
                if (path != null) appBases.add(path); else scan.note("Tomcat Host appBase could not be resolved; its application contexts were not examined");
            }
            if (appBases.isEmpty()) appBases.add(new File(base, "webapps").getPath());
            for (String appBase : appBases) applications(new File(appBase), context);
        } else if ("WebLogic".equals(launch.serverType)) {
            weblogic.discover(context);
        } else if ("JBoss".equals(launch.serverType)) {
            if ("org.jboss.as.server".equals(launch.moduleName) || "org.jboss.as.host-controller".equals(launch.moduleName)) { domain(context); return; }
            if (launch.moduleName == null && "org.jboss.Main".equals(launch.mainClass)) { legacy(context); return; }
            if (!"org.jboss.as.standalone".equals(launch.moduleName)) return;
            File directory = launch.serverConfigDirectory != null ? new File(launch.serverConfigDirectory) : standaloneBase(launch) == null ? null : new File(standaloneBase(launch), "configuration");
            String selected = launch.serverConfigFile == null ? "standalone.xml" : launch.serverConfigFile;
            String path = ProcessLaunch.path(selected, directory);
            if (path != null && selected.indexOf("${") < 0 && selected.indexOf("#{") < 0) inspectRequired(new File(path), "JBoss", context, null);
            else scan.warn("JBoss JDBC configuration path could not be resolved from the process launcher");
        }
    }
    static File standaloneBase(ProcessLaunch launch) {
        File base = launch.serverBase == null ? null : new File(launch.serverBase);
        if (base != null && !launch.serverBaseExplicit && launch.serverBase.equals(launch.serverHome)) base = new File(base, "standalone");
        return base;
    }
    void applications(File source, AssessmentScan.ProcessContext context) {
        File apps = scan.linkedPath(source);
        if (apps == null || !apps.isDirectory() || !scan.visitLinked(apps)) return;
        for (File app : children(apps)) {
            if (!scan.available() || scan.filesVisited >= scan.maxFiles) break;
            File canonical = scan.linkedPath(app);
            if (canonical == null || !canonical.equals(app)) continue;
            String name = app.getName();
            if (app.isDirectory() && scan.visitLinked(app)) inspect(new File(app, "META-INF/context.xml"), "Tomcat", context);
            else if (app.isFile() && name.toLowerCase(Locale.ENGLISH).endsWith(".war") && !new File(apps, name.substring(0, name.length() - 4)).isDirectory() && scan.visitLinked(app))
                load(app, "META-INF/context.xml", "Tomcat", context, null);
        }
    }

    /** WildFly/EAP managed domain: profiles from domain.xml, narrowed through host.xml and server groups when possible. */
    void domain(AssessmentScan.ProcessContext context) {
        ProcessLaunch launch = context.launch; JdbcEndpoint.Resolver values = resolver(context);
        boolean managed = "org.jboss.as.server".equals(launch.moduleName);
        String serverName = null, domainBase = values.property("jboss.domain.base.dir");
        values.arguments = values.lines("cmdline", values.arguments);
        for (String arg : values.arguments) if (arg.startsWith("-D[Server:") && arg.endsWith("]")) serverName = ProcessLaunch.label(arg.substring(10, arg.length() - 1));
        // Managed servers receive their directories as domain/servers/<name>/{data,log,tmp}.
        String serverData = SpringDataSource.first(values.property("jboss.server.data.dir"), values.property("jboss.server.log.dir"));
        if (serverData != null && new File(serverData).isAbsolute()) {
            File server = new File(serverData).getParentFile(), servers = server == null ? null : server.getParentFile();
            if (servers != null && servers.getName().equals("servers") && servers.getParentFile() != null) {
                if (domainBase == null) domainBase = servers.getParent();
                if (serverName == null) serverName = ProcessLaunch.label(server.getName());
            }
        }
        if (serverName == null) serverName = launch.serverName;
        if (domainBase == null && launch.serverHome != null) domainBase = new File(launch.serverHome, "domain").getPath();
        String configDirectory = values.property("jboss.domain.config.dir");
        if (configDirectory == null && domainBase != null) configDirectory = new File(domainBase, "configuration").getPath();
        String domainFile = SpringDataSource.first(values.property("jboss.domain.default.config"), "domain.xml");
        String hostFile = SpringDataSource.first(values.property("jboss.host.default.config"), "host.xml");
        for (int i = 0; i < values.arguments.length; i++) {
            String arg = values.arguments[i];
            if ((arg.equals("-c") || arg.equals("--domain-config")) && i + 1 < values.arguments.length) domainFile = values.arguments[++i];
            else if (arg.startsWith("--domain-config=")) domainFile = arg.substring(16);
            else if (arg.equals("--host-config") && i + 1 < values.arguments.length) hostFile = values.arguments[++i];
            else if (arg.startsWith("--host-config=")) hostFile = arg.substring(14);
        }
        File directory = configDirectory == null ? null : new File(configDirectory);
        String domainPath = ProcessLaunch.path(domainFile, directory), hostPath = ProcessLaunch.path(hostFile, directory);
        if (domainPath == null || domainFile.indexOf("${") >= 0) { scan.warn("JBoss domain JDBC configuration path could not be resolved from the process launcher"); return; }
        String profile = null;
        if (managed && serverName != null && hostPath != null) {
            String group = null;
            for (Map<String,String> server : elements(new File(hostPath), CONFIG_LIMIT, "host", "servers", "server", "name", "group"))
                if (serverName.equals(server.get("name"))) group = server.get("group");
            if (group != null) for (Map<String,String> candidate : elements(new File(domainPath), CONFIG_LIMIT, "domain", "server-groups", "server-group", "name", "profile"))
                if (group.equals(candidate.get("name"))) profile = ServerDataSources.resourceName(candidate.get("profile"));
        }
        if (managed && profile == null) scan.note("JBoss managed server profile was not resolved; all datasource profiles of the domain configuration were inventoried");
        inspectRequired(new File(domainPath), "JBoss", context, profile);
    }

    /** JBoss AS 4/5/6: *-ds.xml descriptors in server/<configuration>/deploy. */
    void legacy(AssessmentScan.ProcessContext context) {
        ProcessLaunch launch = context.launch; JdbcEndpoint.Resolver values = resolver(context);
        String home = launch.serverHome;
        if (home == null) for (String entry : launch.classpath) if (new File(entry).getName().equals("run.jar") && new File(entry).getParentFile() != null) home = new File(entry).getParentFile().getParent();
        values.arguments = values.lines("cmdline", values.arguments);
        String configuration = launch.serverName; boolean afterMain = false;
        for (int i = 0; i < values.arguments.length; i++) {
            String arg = values.arguments[i];
            if (arg.equals("org.jboss.Main")) { afterMain = true; continue; }
            if (!afterMain) continue;
            if ((arg.equals("-c") || arg.equals("--configuration")) && i + 1 < values.arguments.length) configuration = values.arguments[++i];
            else if (arg.startsWith("--configuration=")) configuration = arg.substring(16);
            else if (arg.startsWith("-c") && arg.length() > 2 && !arg.startsWith("-cp")) configuration = arg.substring(2);
        }
        if (configuration == null) configuration = "default";
        String serverHome = values.property("jboss.server.home.dir");
        if (serverHome == null) {
            String serverBase = SpringDataSource.first(values.property("jboss.server.base.dir"), home == null ? null : new File(home, "server").getPath());
            if (ProcessLaunch.label(configuration) == null || configuration.indexOf("..") >= 0 || serverBase == null) { scan.warn("Legacy JBoss configuration directory could not be resolved from the process launcher"); return; }
            serverHome = new File(serverBase, configuration).getPath();
        }
        String deploy = ProcessLaunch.path(serverHome, null);
        if (deploy == null) { scan.warn("Legacy JBoss configuration directory could not be resolved from the process launcher"); return; }
        legacyDescriptors(new File(deploy, "deploy"), 2, context);
    }
    void legacyDescriptors(File source, int depth, AssessmentScan.ProcessContext context) {
        File directory = scan.linkedPath(source);
        if (directory == null || !directory.isDirectory() || !scan.available() || !scan.visitLinked(directory)) return;
        for (File file : children(directory)) {
            if (!scan.available()) return;
            if (scan.filesVisited >= scan.maxFiles) { scan.warn("File count limit reached; remaining JDBC descriptors were not examined"); return; }
            File canonical = scan.linkedPath(file);
            if (canonical == null || !canonical.equals(file)) continue;
            if (file.isDirectory() && depth > 0) legacyDescriptors(file, depth - 1, context);
            else if (file.isFile() && file.getName().endsWith("-ds.xml")) inspect(file, "JBossLegacy", context);
        }
    }

    List<Map> inspectRequired(File source, String kind, AssessmentScan.ProcessContext context, String profile) {
        File file = scan.linkedPath(source);
        if (file != null && scan.available() && !file.exists()) { scan.warn("Selected JDBC server configuration is unavailable: " + file.getPath()); return Collections.emptyList(); }
        return inspect(source, kind, context, profile);
    }
    File[] children(File directory) {
        List<File> retained = new ArrayList<File>(); BoundedDirectory children = null;
        try {
            children = BoundedDirectory.open(directory, scan.deadline);
            for (File child; scan.available() && (child = children.next()) != null;) {
                if (retained.size() == 512) { scan.warn("JDBC configuration directory entry limit reached; additional descriptors were not examined"); break; }
                retained.add(child);
            }
        } catch (IOException e) { scan.warn("JDBC configuration directory enumeration incomplete (read or resource limit): " + directory.getPath()); }
        finally { if (children != null) children.close(); }
        Collections.sort(retained); return retained.toArray(new File[retained.size()]);
    }

    void descriptors(File source, int depth, AssessmentScan.ProcessContext context) {
        File directory = scan.linkedPath(source);
        if (directory == null || !directory.isDirectory() || !scan.available() || !scan.visitLinked(directory)) return;
        for (File file : children(directory)) {
            if (!scan.available()) return;
            if (scan.filesVisited >= scan.maxFiles) { scan.warn("File count limit reached; remaining JDBC descriptors were not examined"); return; }
            File canonical = scan.linkedPath(file);
            if (canonical == null || !canonical.equals(file)) continue;
            if (file.isDirectory() && depth > 0) descriptors(file, depth - 1, context);
            else if (depth == 0 && (file.getName().endsWith(".xml") || file.getName().equals("context.xml.default"))) inspect(file, "Tomcat", context);
        }
    }
    List<Map> inspect(File source, String kind, AssessmentScan.ProcessContext context) { return inspect(source, kind, context, null); }
    List<Map> inspect(File source, String kind, AssessmentScan.ProcessContext context, String profile) {
        File file = scan.linkedPath(source);
        if (file == null || !scan.available() || !file.exists() || !scan.visitLinked(file)) return Collections.emptyList();
        if (!file.isFile() || !file.canRead()) { scan.warn("JDBC configuration file could not be read: " + file.getPath()); return Collections.emptyList(); }
        return load(file, null, kind, context, profile);
    }
    String cacheKey(String key, AssessmentScan.ProcessContext context) { return processDependent.contains(key) ? key + "\u0000pid:" + context.pid : key; }
    /** Moves a just-parsed entry to a per-process key when its values came from this process. */
    void processResult(String key, List<Map> rows, AssessmentScan.ProcessContext context) {
        if (!resolver(context).used || processDependent.contains(key)) return;
        cache.remove(key); processDependent.add(key); cache.put(cacheKey(key, context), rows);
    }
    /** Parses one configuration file, or one bounded entry of an unexpanded archive, through the sanitized cache. */
    List<Map> load(File file, String entry, String kind, AssessmentScan.ProcessContext context, String profile) {
        String path = entry == null ? file.getPath() : file.getPath() + "!/" + entry, key = kind + ":" + path;
        List<Map> rows = cache.get(cacheKey(key, context));
        if (rows == null) {
            if (cache.size() >= MAX_CONFIGS) { scan.warn("JDBC configuration file limit reached; additional files were not examined"); return Collections.emptyList(); }
            if (cachedRows >= 512 || scan.datasources.size() >= 512) { scan.warn("Datasource row limit reached; additional JDBC configuration files were not examined"); return Collections.emptyList(); }
            rows = new ArrayList<Map>(); cache.put(cacheKey(key, context), rows);
            String xml = entry == null ? scan.read(file, CONFIG_LIMIT) : entry(file, entry, CONFIG_LIMIT);
            if (xml == null) return rows;
            JdbcEndpoint.Resolver values = resolver(context); values.used = false;
            try {
                Handler handler = new Handler(kind, scan, values); parseXml(xml, handler);
                List<Map> parsed = handler.finish();
                if (parsed.size() > 512 - cachedRows) { scan.warn("JDBC configuration cache row limit reached; additional findings were not retained"); return rows; }
                driverArtifacts(parsed, context);
                rows.addAll(parsed); cachedRows += rows.size();
            }
            catch (Exception e) {
                // Parser exception text can include source values. Never include it in diagnostics.
                scan.warn("JDBC XML configuration was rejected (malformed, unsafe or beyond parser limits): " + path); return rows;
            }
            processResult(key, rows, context);
        }
        for (Map fields : rows) {
            if (profile != null && !("domain profile " + profile).equals(fields.get("configurationContext"))) continue;
            if ("resourceLink".equals(fields.get("marker"))) link(path, fields, context);
            else if (fields.get("marker") == null) add(path, fields, context);
        }
        return rows;
    }
    /** Reads one entry of a WAR/EAR without extracting it. */
    String entry(File archive, String name, int limit) {
        ZipFile zip = null;
        try {
            if (archive.length() > ArchiveMetadata.FILE_LIMIT) throw new IOException("Archive too large");
            scan.archiveMetadata.preflight(archive); // charges its own bounded read
            zip = new ZipFile(archive); ZipEntry found = zip.getEntry(name);
            if (found == null) return null;
            if (found.getSize() > limit) throw new IOException("Entry too large");
            InputStream input = zip.getInputStream(found);
            try { return new String(scan.archiveMetadata.read(input, limit), "UTF-8"); } finally { input.close(); }
        } catch (IOException e) { scan.warn("JDBC configuration inside an archive could not be read within bounds: " + archive.getPath()); return null; }
        finally { if (zip != null) try { zip.close(); } catch (IOException ignored) { } }
    }
    /** Associates a Tomcat ResourceLink with the global resource of the same server instance. */
    void link(String path, Map fields, AssessmentScan.ProcessContext context) {
        String label = (String)fields.get("configurationContext");
        // conf/context.xml is the default context of every application: keep its existing "context:default" label.
        boolean defaults = path.endsWith("/conf/context.xml");
        if (!defaults && ("context-descriptor".equals(label) || "context:default".equals(label))) {
            File file = new File(path.endsWith("!/META-INF/context.xml") ? path.substring(0, path.length() - 22) : path);
            if (file.getName().equals("context.xml") && file.getParentFile() != null && file.getParentFile().getName().equals("META-INF")) file = file.getParentFile().getParentFile();
            String name = file.getName().replaceAll("\\.(xml|war)$", "");
            label = "context:" + (name.equals("ROOT") ? "/" : "/" + name.replace('#', '/'));
        }
        for (Map row : scan.configuredDatasourceRows.values()) {
            if (!"global".equals(row.get("configurationContext")) || !fields.get("global").equals(row.get("name")) || !(row.get("associations") instanceof List)) continue;
            boolean sameProcess = false;
            for (Object association : (List)row.get("associations")) if (Long.valueOf(context.pid).equals(((Map)association).get("pid"))) sameProcess = true;
            if (!sameProcess) continue;
            List links = row.get("linkedContexts") instanceof List ? (List)row.get("linkedContexts") : new ArrayList();
            Map entry = AssessmentScan.map("context", label, "linkName", fields.get("linkName"), "configurationSource", path);
            if (!links.contains(entry) && links.size() < 32) links.add(entry);
            row.put("linkedContexts", links);
        }
    }
    void add(File file, Map fields, AssessmentScan.ProcessContext context) { add(file.getPath(), fields, context); }
    void add(String path, Map fields, AssessmentScan.ProcessContext context) {
        List identity = new ArrayList(Arrays.asList(path, fields.get("contextIdentity"), fields.get("configurationContext"), fields.get("name")));
        // Rows resolved from one process's values are distinct when another process resolves a different destination.
        if (fields.get("resolvedFrom") != null) { identity.add(fields.get("databaseEndpoints")); identity.add(fields.get("databaseName")); }
        String key = "server-config:" + Json.stringify(identity);
        Map row = scan.configuredDatasourceRows.get(key);
        if (row == null) {
            if (scan.datasources.size() >= 512) { scan.warn("Datasource row limit reached; remaining configuration findings were not retained"); return; }
            row = AssessmentScan.map("id", AssessmentScan.id("datasource", scan.hostId + key), "hostId", scan.hostId,
                "sourcePath", path, "configurationSource", path,
                "state", Boolean.FALSE.equals(fields.get("configuredEnabled")) ? "DISABLED" : "CONFIGURED",
                "evidenceLevel", "configuration", "scanScope", "process-linked",
                "evidence", "JDBC resource declared in server configuration; activation, effective overrides, driver loading and database connectivity were not verified; no pool runtime metrics collected",
                "observedAt", scan.timestamp);
            row.putAll(fields); row.remove("contextIdentity"); scan.configuredDatasourceRows.put(key, row); scan.datasources.add(row);
        }
        Map association = AssessmentScan.map("pid", Long.valueOf(context.pid));
        if (context.server != null) { association.put("serverName", context.server.get("name")); association.put("serverId", context.server.get("id")); }
        if (context.application != null) {
            association.put("applicationId", context.application.get("id")); association.put("applicationName", context.application.get("name"));
        }
        List associations = row.get("associations") instanceof List ? (List)row.get("associations") : new ArrayList();
        if (!associations.contains(association)) {
            if (associations.size() < 16) associations.add(association);
            else scan.warn("JDBC datasource process association limit reached; additional associations were not retained");
        }
        row.put("associations", associations);
        for (String field : new String[]{"pid", "serverName", "serverId", "applicationId", "applicationName"}) {
            if (associations.size() == 1 && association.containsKey(field)) row.put(field, association.get(field)); else row.remove(field);
        }
    }

    /** JBoss drivers declared as modules or deployed JARs: the JAR is a candidate, its classes are never loaded. */
    void driverArtifacts(List<Map> rows, AssessmentScan.ProcessContext context) {
        for (Map row : rows) {
            Object module = row.remove("driverModule"), deployment = row.remove("driverDeployment");
            File jar = null;
            if (module instanceof String) jar = moduleJar((String)module, context);
            else if (deployment instanceof String && "org.jboss.as.standalone".equals(context.launch.moduleName) && standaloneBase(context.launch) != null) {
                File candidate = scan.linkedPath(new File(new File(standaloneBase(context.launch), "deployments"), (String)deployment));
                if (candidate != null && candidate.isFile()) jar = candidate;
            }
            if (jar == null) continue;
            scan.linkedFile(jar, context);
            row.put("driverArtifact", jar.getPath());
            Object libraries = scan.archiveMetadata.inspect(jar).get("libraries");
            if (libraries instanceof List) for (Object raw : (List)libraries) {
                Map library = (Map)raw; Object version = library.get("version");
                if ("JDBC".equals(library.get("category")) && jar.getPath().equals(library.get("path")) && version instanceof String && ((String)version).matches("[0-9][A-Za-z0-9._+\\-]{0,127}")) {
                    row.put("driverVersion", version);
                    row.put("driverVersionEvidence", "JDBC library metadata of the JAR declared by the JBoss driver; runtime loading not verified");
                }
            }
        }
    }
    File moduleJar(String module, AssessmentScan.ProcessContext context) {
        int colon = module.indexOf(':');
        String name = colon < 0 ? module : module.substring(0, colon), slot = colon < 0 ? "main" : module.substring(colon + 1);
        if (!name.matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){0,15}") || !slot.matches("[A-Za-z0-9_.-]{1,64}") || slot.indexOf("..") >= 0) return null;
        List<String> roots = new ArrayList<String>(context.launch.modulePaths);
        if (roots.isEmpty() && context.launch.serverHome != null) roots.add(new File(context.launch.serverHome, "modules").getPath());
        for (String root : roots) for (String prefix : new String[]{"system/layers/base/", ""}) {
            // shortcut: only the base layer and the module root; add-ons and custom layers are not searched.
            File directory = new File(root, prefix + name.replace('.', '/') + "/" + slot);
            File descriptor = scan.linkedPath(new File(directory, "module.xml"));
            if (descriptor == null || !descriptor.isFile()) continue;
            List<String> jars = new ArrayList<String>();
            for (Map<String,String> resource : elements(descriptor, SMALL_LIMIT, "module", "resources", "resource-root", "path")) {
                String path = resource.get("path");
                if (path != null && path.matches("[A-Za-z0-9_.+-]{1,200}\\.jar")) jars.add(path);
            }
            if (jars.size() != 1) return null;
            File jar = scan.linkedPath(new File(directory, jars.get(0)));
            return jar != null && jar.isFile() && AssessmentScan.under(jar.getPath(), scan.linkedPath(directory).getPath()) ? jar : null;
        }
        return null;
    }
    /** Selected attributes of root/.../parent/element in a bounded trusted-parser XML file; domain.xml/host.xml take the configuration limit. */
    List<Map<String,String>> elements(File source, int limit, final String root, final String parent, final String element, final String... attributes) {
        final List<Map<String,String>> result = new ArrayList<Map<String,String>>();
        File file = scan.linkedPath(source);
        if (file == null || !file.isFile() || !scan.visitLinked(file)) return result;
        String xml = scan.read(file, limit);
        if (xml == null) return result;
        try {
            parseXml(xml, new DefaultHandler() {
                final List<String> stack = new ArrayList<String>(); int count;
                public void startElement(String uri, String local, String qualified, Attributes attrs) throws SAXException {
                    String name = local.length() > 0 ? local : qualified;
                    if (++count > MAX_ELEMENTS || stack.size() >= MAX_DEPTH || !scan.available()) throw new SAXException("XML budget");
                    if (stack.isEmpty() && !name.equals(root)) throw new SAXException("Unexpected root");
                    if (name.equals(element) && !stack.isEmpty() && stack.get(stack.size() - 1).equals(parent)) {
                        if (result.size() >= 256) throw new SAXException("Element budget");
                        Map<String,String> values = new HashMap<String,String>();
                        for (String attribute : attributes) { String value = attrs.getValue(attribute); if (value != null && value.length() <= 512) values.put(attribute, value); }
                        result.add(values);
                    }
                    stack.add(name);
                }
                public void endElement(String uri, String local, String qualified) { stack.remove(stack.size() - 1); }
                public void error(SAXParseException e) throws SAXException { throw e; }
                public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
        } catch (Exception e) { scan.warn("Server XML configuration was rejected (malformed, unsafe or beyond parser limits): " + file.getPath()); result.clear(); }
        return result;
    }
    static List<Map> parse(String xml, String kind, AssessmentScan scan) throws Exception {
        Handler handler = new Handler(kind, scan, null);
        parseXml(xml, handler); return handler.finish();
    }
    static void parseXml(String xml, DefaultHandler handler) throws Exception {
        if (xml.length() > CONFIG_LIMIT) throw new SAXException("Configuration limit");
        SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true); factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setContentHandler(handler); reader.setErrorHandler(handler);
        reader.setEntityResolver(new EntityResolver() { public InputSource resolveEntity(String publicId, String systemId) throws SAXException { throw new SAXException("External entity disabled"); } });
        reader.parse(new InputSource(new StringReader(xml)));
    }
    static String resourceName(String value) {
        return value != null && value.length() > 0 && value.length() <= 200 && value.matches("[A-Za-z0-9_./:@ #+\\-]+") ? value : null;
    }
    static Integer pool(String value) {
        if (value == null || !value.matches("[0-9]{1,6}")) return null;
        int number = Integer.parseInt(value); return number >= 1 && number <= 100000 ? Integer.valueOf(number) : null;
    }
    /** Case-insensitive attribute lookup: pool libraries spell the same setting as url, URL or jdbcUrl. */
    static String attribute(Attributes attrs, String... names) {
        for (String name : names) for (int i = 0; i < attrs.getLength(); i++) {
            String attribute = attrs.getLocalName(i) == null || attrs.getLocalName(i).length() == 0 ? attrs.getQName(i) : attrs.getLocalName(i);
            if (attribute.equalsIgnoreCase(name)) return attrs.getValue(i);
        }
        return null;
    }
    static final class Frame {
        final String name, uri, scope; String property;
        final StringBuilder text = new StringBuilder();
        Frame(String name, String uri, String scope) { this.name = name; this.uri = uri; this.scope = scope; }
    }
    static final class Handler extends DefaultHandler {
        static final List<String> LEGACY = Arrays.asList("local-tx-datasource", "xa-datasource", "no-tx-datasource");
        static final List<String> TEXT = Arrays.asList("driver", "driver-class", "connection-url", "max-pool-size", "xa-datasource-property", "xa-datasource-class", "jndi-name");
        final String kind; final AssessmentScan scan; final JdbcEndpoint.Resolver resolver;
        final List<Frame> stack = new ArrayList<Frame>();
        final List<Map> rows = new ArrayList<Map>(), markers = new ArrayList<Map>();
        final Map<String,Map> drivers = new HashMap<String,Map>();
        final Map<String,String> xa = new HashMap<String,String>();
        Map current, driver; int currentDepth, driverDepth, elements;
        Handler(String kind, AssessmentScan scan, JdbcEndpoint.Resolver resolver) { this.kind = kind; this.scan = scan; this.resolver = resolver; }
        boolean jboss() { return "JBoss".equals(kind) || "JBossLegacy".equals(kind); }
        Frame parent() { return stack.size() < 2 ? null : stack.get(stack.size() - 2); }
        String scope() throws SAXException {
            StringBuilder value = new StringBuilder();
            for (Frame frame : stack) if (frame.scope != null) { if (value.length() > 0) value.append('/'); value.append(frame.scope); }
            if (value.length() > 1024) throw new SAXException("XML context scope limit");
            return value.length() > 0 ? value.toString() : "context-descriptor";
        }
        List<String> contextIdentity() {
            List<String> result = new ArrayList<String>();
            for (Frame frame : stack) if (frame.scope != null) result.add(frame.scope);
            return result;
        }
        String profile() { for (Frame frame : stack) if (frame.scope != null && frame.scope.startsWith("profile:")) return frame.scope.substring(8); return null; }
        boolean datasourceSubsystem() {
            if ("JBossLegacy".equals(kind)) return true;
            for (Frame frame : stack) if (frame.name.equals("subsystem") && frame.uri.startsWith("urn:jboss:domain:datasources:")) return true;
            return false;
        }
        String resolved(String value, List<String> sources) {
            if (value == null || value.indexOf("${") < 0) return value;
            return resolver == null ? null : resolver.expand(value, sources);
        }
        public void startElement(String uri, String local, String qualified, Attributes attrs) throws SAXException {
            if (++elements > MAX_ELEMENTS || stack.size() >= MAX_DEPTH || scan != null && !scan.available()) throw new SAXException("XML budget");
            String name = local.length() > 0 ? local : qualified, scope = null;
            if ("Tomcat".equals(kind)) {
                if (name.equals("GlobalNamingResources")) scope = "global";
                else if (name.equals("Context")) { String path = resourceName(attrs.getValue("path")); scope = "context:" + (path == null ? "default" : path); }
                else if (name.equals("Engine") || name.equals("Host")) { String label = resourceName(attrs.getValue("name")); scope = name.toLowerCase(Locale.ENGLISH) + ":" + (label == null ? "default" : label); }
            } else if ("JBoss".equals(kind) && name.equals("profile") && !stack.isEmpty() && stack.get(stack.size() - 1).name.equals("profiles")) {
                String profile = resourceName(attrs.getValue("name")); if (profile != null) scope = "profile:" + profile;
            }
            stack.add(new Frame(name, uri == null ? "" : uri, scope));
            if (stack.size() == 1) {
                boolean root = "Tomcat".equals(kind) ? name.equals("Server") || name.equals("Context") : "JBossLegacy".equals(kind) ? name.equals("datasources") || name.equals("connection-factories") : name.equals("server") || name.equals("domain");
                if (!root) throw new SAXException("Unexpected configuration root");
            }
            if ("Tomcat".equals(kind)) { tomcat(name, attrs); return; }
            if (!jboss() || !datasourceSubsystem()) return;
            if (name.equals("xa-datasource-property") && current != null) stack.get(stack.size() - 1).property = attrs.getValue("name");
            boolean modern = "JBoss".equals(kind) && (name.equals("datasource") || name.equals("xa-datasource")) && parent() != null && parent().name.equals("datasources");
            boolean legacy = "JBossLegacy".equals(kind) && stack.size() == 2 && LEGACY.contains(name) && parent().name.equals("datasources");
            if (modern || legacy) {
                String resource = modern ? resourceName(SpringDataSource.first(attrs.getValue("jndi-name"), attrs.getValue("pool-name"))) : null;
                if (resource != null || legacy) {
                    String profile = profile();
                    current = AssessmentScan.map("name", resource, "configurationContext", legacy ? "JBoss deploy" : profile == null ? "standalone" : "domain profile " + profile,
                        "resourceType", name.equals("xa-datasource") ? "javax.sql.XADataSource" : "javax.sql.DataSource");
                    if (profile != null) current.put("configurationScope", "domain-profile");
                    String enabled = attrs.getValue("enabled");
                    if (enabled == null || enabled.equals("true")) current.put("configuredEnabled", Boolean.TRUE);
                    else if (enabled.equals("false")) current.put("configuredEnabled", Boolean.FALSE);
                    else current.put("activationUnresolved", Boolean.TRUE);
                    currentDepth = stack.size(); xa.clear();
                }
            } else if ("JBoss".equals(kind) && name.equals("driver") && parent() != null && parent().name.equals("drivers")) {
                String resource = resourceName(attrs.getValue("name"));
                if (resource != null) {
                    driver = AssessmentScan.map("name", resource); driverDepth = stack.size();
                    String module = attrs.getValue("module");
                    if (module != null && module.matches("[A-Za-z0-9_.:-]{1,256}")) driver.put("module", module);
                }
            }
        }
        void tomcat(String name, Attributes attrs) throws SAXException {
            if (name.equals("Host")) {
                String base = SpringDataSource.first(attrs.getValue("appBase"), "webapps");
                if (base.length() <= 4096 && base.indexOf("#{") < 0) markers.add(AssessmentScan.map("marker", "appBase", "appBase", base));
            }
            if (parent() == null || !(parent().name.equals("Context") || parent().name.equals("GlobalNamingResources"))) return;
            if (name.equals("ResourceLink") && parent().name.equals("Context")) {
                String global = resourceName(attrs.getValue("global")), link = resourceName(attrs.getValue("name"));
                if (global != null && link != null) markers.add(AssessmentScan.map("marker", "resourceLink", "global", global, "linkName", link, "configurationContext", scope()));
                return;
            }
            if (!name.equals("Resource")) return;
            String type = attrs.getValue("type"), factory = attrs.getValue("factory"), resource = resourceName(attrs.getValue("name"));
            boolean jdbc = TOMCAT_TYPES.contains(type) || TOMCAT_FACTORIES.contains(factory) && SpringDataSource.validDriver(type) != null;
            if (resource == null || !jdbc) return;
            Map row = AssessmentScan.map("name", resource, "configurationContext", scope(), "resourceType", type);
            row.put("contextIdentity", contextIdentity());
            String driverName = SpringDataSource.validDriver(SpringDataSource.first(attribute(attrs, "driverClassName"), SpringDataSource.first(attribute(attrs, "connectionFactoryClassName"), attribute(attrs, "dataSourceClassName"))));
            if (driverName != null) row.put("driverName", driverName);
            JdbcEndpoint.addTo(row, attribute(attrs, "url", "jdbcUrl"), resolver);
            Integer maximum = pool(SpringDataSource.first(attribute(attrs, "maxTotal", "maxActive"), attribute(attrs, "maximumPoolSize", "maxPoolSize")));
            if (maximum != null) row.put("configuredMaxPoolSize", maximum);
            retain(row);
        }
        public void characters(char[] chars, int start, int length) throws SAXException {
            if (stack.isEmpty()) return;
            Frame frame = stack.get(stack.size() - 1);
            boolean selected = current != null && TEXT.contains(frame.name) ||
                driver != null && (frame.name.equals("driver-class") || frame.name.equals("xa-datasource-class"));
            if (selected) {
                if (frame.text.length() + length > 8192) throw new SAXException("XML scalar limit");
                frame.text.append(chars, start, length);
            }
        }
        public void endElement(String uri, String local, String qualified) throws SAXException {
            Frame frame = stack.get(stack.size() - 1); String value = frame.text.toString().trim();
            if (current != null) {
                boolean child = stack.size() == currentDepth + 1;
                if (child && frame.name.equals("driver")) { String token = resourceName(value); if (token != null) current.put("driverReference", token); }
                else if (child && (frame.name.equals("driver-class") || frame.name.equals("xa-datasource-class") && current.get("driverName") == null)) { String token = SpringDataSource.validDriver(value); if (token != null) current.put("driverName", token); }
                else if (child && frame.name.equals("jndi-name") && "JBossLegacy".equals(kind)) { String token = resourceName(value); if (token != null) current.put("name", token); }
                else if (child && frame.name.equals("connection-url")) url(value);
                else if (child && frame.name.equals("xa-datasource-property") && frame.property != null && xa.size() < 32) xa.put(frame.property.toLowerCase(Locale.ENGLISH), value);
                else if (frame.name.equals("max-pool-size") && ("JBossLegacy".equals(kind) ? child : stack.size() == currentDepth + 2 && parent() != null && (parent().name.equals("pool") || parent().name.equals("xa-pool")))) { Integer maximum = pool(value); if (maximum != null) current.put("configuredMaxPoolSize", maximum); }
                if (stack.size() == currentDepth) {
                    if (current.get("databaseEndpoints") == null && xa.get("url") != null) url(xa.get("url"));
                    String host = xa.get("servername");
                    if (current.get("databaseEndpoints") == null && host != null) {
                        List<String> sources = new ArrayList<String>();
                        String port = resolved(SpringDataSource.first(xa.get("portnumber"), xa.get("port")), sources), database = resolved(SpringDataSource.first(xa.get("databasename"), xa.get("servicename")), sources);
                        current.putAll(JdbcEndpoint.parts(resolved(host, sources), port, database, sources));
                    }
                    if (current.get("name") != null) retain(current);
                    current = null; xa.clear();
                }
            }
            if (driver != null) {
                if (stack.size() == driverDepth + 1 && (frame.name.equals("driver-class") || frame.name.equals("xa-datasource-class"))) {
                    String token = SpringDataSource.validDriver(value); if (token != null) driver.put(frame.name, token);
                }
                if (stack.size() == driverDepth) { drivers.put((String)driver.get("name"), driver); driver = null; }
            }
            stack.remove(stack.size() - 1);
        }
        void url(String value) { JdbcEndpoint.addTo(current, value, resolver); }
        void retain(Map row) throws SAXException { if (rows.size() >= 512) throw new SAXException("XML datasource row limit"); rows.add(row); }
        public void error(SAXParseException e) throws SAXException { throw e; }
        public void fatalError(SAXParseException e) throws SAXException { throw e; }
        List<Map> finish() throws SAXException {
            Map<String,Map> unique = new LinkedHashMap<String,Map>();
            for (Map row : rows) {
                Object reference = row.remove("driverReference"); Map declaration = drivers.get(reference);
                if (row.get("driverName") == null && declaration != null) {
                    Object driverName = declaration.get("javax.sql.XADataSource".equals(row.get("resourceType")) ? "xa-datasource-class" : "driver-class");
                    if (driverName != null) row.put("driverName", driverName);
                }
                if (declaration != null && declaration.get("module") != null) row.put("driverModule", declaration.get("module"));
                // A deployed driver is referenced by its deployment name, e.g. ojdbc8.jar or ojdbc8.jar_oracle.jdbc.OracleDriver_19_3.
                else if (declaration == null && reference instanceof String && ((String)reference).indexOf(".jar") > 0) {
                    String jar = ((String)reference).substring(0, ((String)reference).indexOf(".jar") + 4);
                    if (jar.matches("[A-Za-z0-9_.+-]{1,200}")) row.put("driverDeployment", jar);
                }
                if (row.get("databaseType") == null) { String database = SpringDataSource.driverDatabase((String)row.get("driverName")); if (database != null) row.put("databaseType", database); }
                String key = Json.stringify(Arrays.asList(row.get("contextIdentity"), row.get("configurationContext"), row.get("name")));
                if (!unique.containsKey(key)) unique.put(key, row);
            }
            if (unique.size() + markers.size() > 512) throw new SAXException("XML datasource row limit");
            List<Map> result = new ArrayList<Map>(unique.values()); result.addAll(markers);
            return result;
        }
    }
}
