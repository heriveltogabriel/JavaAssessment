package assessment;

import java.io.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded configuration evidence. No XML entities, JDBC calls, credentials or raw URLs are retained. */
final class ServerDataSources {
    static final int CONFIG_LIMIT = 256 * 1024, MAX_CONFIGS = 128, MAX_ELEMENTS = 12000, MAX_DEPTH = 64;
    final AssessmentScan scan;
    final Map<String,List<Map>> cache = new LinkedHashMap<String,List<Map>>();
    final WebLogicDataSources weblogic;
    int cachedRows;
    ServerDataSources(AssessmentScan scan) { this.scan = scan; this.weblogic = new WebLogicDataSources(this); }

    void discover(AssessmentScan.ProcessContext context) {
        ProcessLaunch launch = context.launch;
        if ("Tomcat".equals(launch.serverType) && launch.serverBase != null) {
            File base = new File(launch.serverBase);
            inspectRequired(new File(base, "conf/server.xml"), "Tomcat", context);
            inspect(new File(base, "conf/context.xml"), "Tomcat", context);
            descriptors(new File(base, "conf"), 2, context);
            File apps = scan.linkedPath(new File(base, "webapps"));
            if (apps != null && apps.isDirectory() && scan.visitLinked(apps)) {
                for (File app : children(apps)) {
                    if (!scan.available() || scan.filesVisited >= scan.maxFiles) break;
                    File canonical = scan.linkedPath(app);
                    if (canonical != null && canonical.equals(app) && app.isDirectory() && scan.visitLinked(app))
                        inspect(new File(app, "META-INF/context.xml"), "Tomcat", context);
                }
            }
        } else if ("WebLogic".equals(launch.serverType)) {
            weblogic.discover(context);
        } else if ("JBoss".equals(launch.serverType)) {
            if (!"org.jboss.as.standalone".equals(launch.moduleName)) {
                scan.note("JBoss JDBC configuration discovery requires an identified standalone launcher; domain and legacy profiles were not resolved"); return;
            }
            File base = launch.serverBase == null ? null : new File(launch.serverBase);
            if (base != null && !launch.serverBaseExplicit && launch.serverBase.equals(launch.serverHome)) base = new File(base, "standalone");
            File directory = launch.serverConfigDirectory != null ? new File(launch.serverConfigDirectory) : base == null ? null : new File(base, "configuration");
            String selected = launch.serverConfigFile == null ? "standalone.xml" : launch.serverConfigFile;
            String path = ProcessLaunch.path(selected, directory);
            if (path != null && selected.indexOf("${") < 0 && selected.indexOf("#{") < 0) inspectRequired(new File(path), "JBoss", context);
            else scan.warn("JBoss JDBC configuration path could not be resolved from the process launcher");
        }
    }
    void inspectRequired(File source, String kind, AssessmentScan.ProcessContext context) {
        File file = scan.linkedPath(source);
        if (file != null && scan.available() && !file.exists()) scan.warn("Selected JDBC server configuration is unavailable: " + file.getPath());
        else inspect(source, kind, context);
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
    void inspect(File source, String kind, AssessmentScan.ProcessContext context) {
        File file = scan.linkedPath(source);
        if (file == null || !scan.available() || !file.exists() || !scan.visitLinked(file)) return;
        if (!file.isFile() || !file.canRead()) { scan.warn("JDBC configuration file could not be read: " + file.getPath()); return; }
        String key = kind + ":" + file.getPath();
        List<Map> rows = cache.get(key);
        if (rows == null) {
            if (cache.size() >= MAX_CONFIGS) { scan.warn("JDBC configuration file limit reached; additional files were not examined"); return; }
            if (cachedRows >= 512 || scan.datasources.size() >= 512) { scan.warn("Datasource row limit reached; additional JDBC configuration files were not examined"); return; }
            rows = new ArrayList<Map>(); cache.put(key, rows);
            String xml = scan.read(file, CONFIG_LIMIT);
            if (xml == null) return;
            try {
                List<Map> parsed = parse(xml, kind, scan);
                if (parsed.size() > 512 - cachedRows) { scan.warn("JDBC configuration cache row limit reached; additional findings were not retained"); return; }
                rows.addAll(parsed); cachedRows += rows.size();
            }
            catch (Exception e) {
                // Parser exception text can include source values. Never include it in diagnostics.
                scan.warn("JDBC XML configuration was rejected (malformed, unsafe or beyond parser limits): " + file.getPath()); return;
            }
        }
        for (Map fields : rows) add(file, fields, context);
    }
    void add(File file, Map fields, AssessmentScan.ProcessContext context) {
        String key = "server-config:" + Json.stringify(Arrays.asList(file.getPath(), fields.get("contextIdentity"), fields.get("configurationContext"), fields.get("name")));
        Map row = scan.configuredDatasourceRows.get(key);
        if (row == null) {
            if (scan.datasources.size() >= 512) { scan.warn("Datasource row limit reached; remaining configuration findings were not retained"); return; }
            row = AssessmentScan.map("id", AssessmentScan.id("datasource", scan.hostId + key), "hostId", scan.hostId,
                "sourcePath", file.getPath(), "configurationSource", file.getPath(),
                "state", Boolean.FALSE.equals(fields.get("configuredEnabled")) ? "DISABLED" : "CONFIGURED",
                "evidenceLevel", "configuration", "scanScope", "process-linked",
                "evidence", "JDBC resource declared in server configuration; activation, effective overrides, driver loading and database connectivity were not verified; no pool runtime metrics collected",
                "observedAt", scan.timestamp);
            row.putAll(fields); row.remove("contextIdentity"); scan.configuredDatasourceRows.put(key, row); scan.datasources.add(row);
        }
        Map association = AssessmentScan.map("pid", Long.valueOf(context.pid));
        if (context.server != null) association.put("serverName", context.server.get("name"));
        if (context.application != null) {
            association.put("applicationId", context.application.get("id")); association.put("applicationName", context.application.get("name"));
        }
        List associations = row.get("associations") instanceof List ? (List)row.get("associations") : new ArrayList();
        if (!associations.contains(association)) {
            if (associations.size() < 16) associations.add(association);
            else scan.warn("JDBC datasource process association limit reached; additional associations were not retained");
        }
        row.put("associations", associations);
        for (String field : new String[]{"pid", "serverName", "applicationId", "applicationName"}) {
            if (associations.size() == 1 && association.containsKey(field)) row.put(field, association.get(field)); else row.remove(field);
        }
    }
    static List<Map> parse(String xml, String kind, AssessmentScan scan) throws Exception {
        Handler handler = new Handler(kind, scan);
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
    static final class Frame {
        final String name, uri, scope;
        final StringBuilder text = new StringBuilder();
        Frame(String name, String uri, String scope) { this.name = name; this.uri = uri; this.scope = scope; }
    }
    static final class Handler extends DefaultHandler {
        final String kind; final AssessmentScan scan;
        final List<Frame> stack = new ArrayList<Frame>();
        final List<Map> rows = new ArrayList<Map>();
        final Map<String,Map> drivers = new HashMap<String,Map>();
        Map current, driver; int currentDepth, driverDepth, elements;
        Handler(String kind, AssessmentScan scan) { this.kind = kind; this.scan = scan; }
        Frame parent() { return stack.size() < 2 ? null : stack.get(stack.size() - 2); }
        boolean ancestor(String name) { for (Frame frame : stack) if (frame.name.equals(name)) return true; return false; }
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
        boolean datasourceSubsystem() {
            for (Frame frame : stack) if (frame.name.equals("subsystem") && frame.uri.startsWith("urn:jboss:domain:datasources:")) return true;
            return false;
        }
        public void startElement(String uri, String local, String qualified, Attributes attrs) throws SAXException {
            if (++elements > MAX_ELEMENTS || stack.size() >= MAX_DEPTH || scan != null && !scan.available()) throw new SAXException("XML budget");
            String name = local.length() > 0 ? local : qualified, scope = null;
            if ("Tomcat".equals(kind)) {
                if (name.equals("GlobalNamingResources")) scope = "global";
                else if (name.equals("Context")) { String path = resourceName(attrs.getValue("path")); scope = "context:" + (path == null ? "default" : path); }
                else if (name.equals("Engine") || name.equals("Host")) { String label = resourceName(attrs.getValue("name")); scope = name.toLowerCase(Locale.ENGLISH) + ":" + (label == null ? "default" : label); }
            }
            stack.add(new Frame(name, uri == null ? "" : uri, scope));
            if (stack.size() == 1 && !("Tomcat".equals(kind) ? name.equals("Server") || name.equals("Context") : name.equals("server"))) throw new SAXException("Unexpected configuration root");
            if ("Tomcat".equals(kind) && name.equals("Resource") && parent() != null && (parent().name.equals("Context") || parent().name.equals("GlobalNamingResources"))) {
                String type = attrs.getValue("type"), resource = resourceName(attrs.getValue("name"));
                if (resource != null && ("javax.sql.DataSource".equals(type) || "javax.sql.XADataSource".equals(type))) {
                    Map row = AssessmentScan.map("name", resource, "configurationContext", scope(), "resourceType", type);
                    row.put("contextIdentity", contextIdentity());
                    String driverName = SpringDataSource.validDriver(attrs.getValue("driverClassName"));
                    if (driverName != null) row.put("driverName", driverName);
                    String database = SpringDataSource.databaseType(String.valueOf(attrs.getValue("url")));
                    if (database != null) row.put("databaseType", database);
                    Integer maximum = pool(SpringDataSource.first(attrs.getValue("maxTotal"), attrs.getValue("maxActive")));
                    if (maximum != null) row.put("configuredMaxPoolSize", maximum);
                    retain(row);
                }
            }
            if (!"JBoss".equals(kind) || !datasourceSubsystem()) return;
            if ((name.equals("datasource") || name.equals("xa-datasource")) && parent() != null && parent().name.equals("datasources")) {
                String resource = resourceName(SpringDataSource.first(attrs.getValue("jndi-name"), attrs.getValue("pool-name")));
                if (resource != null) {
                    current = AssessmentScan.map("name", resource, "configurationContext", "standalone", "resourceType", name.equals("xa-datasource") ? "javax.sql.XADataSource" : "javax.sql.DataSource");
                    String enabled = attrs.getValue("enabled");
                    if (enabled == null || enabled.equals("true")) current.put("configuredEnabled", Boolean.TRUE);
                    else if (enabled.equals("false")) current.put("configuredEnabled", Boolean.FALSE);
                    else current.put("activationUnresolved", Boolean.TRUE);
                    currentDepth = stack.size();
                }
            } else if (name.equals("driver") && parent() != null && parent().name.equals("drivers")) {
                String resource = resourceName(attrs.getValue("name"));
                if (resource != null) { driver = AssessmentScan.map("name", resource); driverDepth = stack.size(); }
            }
        }
        public void characters(char[] chars, int start, int length) throws SAXException {
            if (stack.isEmpty()) return;
            Frame frame = stack.get(stack.size() - 1);
            boolean selected = current != null && (frame.name.equals("driver") || frame.name.equals("driver-class") || frame.name.equals("connection-url") || frame.name.equals("max-pool-size")) ||
                driver != null && (frame.name.equals("driver-class") || frame.name.equals("xa-datasource-class"));
            if (selected) {
                if (frame.text.length() + length > 8192) throw new SAXException("XML scalar limit");
                frame.text.append(chars, start, length);
            }
        }
        public void endElement(String uri, String local, String qualified) throws SAXException {
            Frame frame = stack.get(stack.size() - 1); String value = frame.text.toString().trim();
            if (current != null) {
                if (stack.size() == currentDepth + 1 && frame.name.equals("driver")) { String token = resourceName(value); if (token != null) current.put("driverReference", token); }
                else if (stack.size() == currentDepth + 1 && frame.name.equals("driver-class")) { String token = SpringDataSource.validDriver(value); if (token != null) current.put("driverName", token); }
                else if (stack.size() == currentDepth + 1 && frame.name.equals("connection-url")) { String database = SpringDataSource.databaseType(value); if (database != null) current.put("databaseType", database); }
                else if (stack.size() == currentDepth + 2 && frame.name.equals("max-pool-size") && parent() != null && (parent().name.equals("pool") || parent().name.equals("xa-pool"))) { Integer maximum = pool(value); if (maximum != null) current.put("configuredMaxPoolSize", maximum); }
                if (stack.size() == currentDepth) { retain(current); current = null; }
            }
            if (driver != null) {
                if (stack.size() == driverDepth + 1 && (frame.name.equals("driver-class") || frame.name.equals("xa-datasource-class"))) {
                    String token = SpringDataSource.validDriver(value); if (token != null) driver.put(frame.name, token);
                }
                if (stack.size() == driverDepth) { drivers.put((String)driver.get("name"), driver); driver = null; }
            }
            stack.remove(stack.size() - 1);
        }
        void retain(Map row) throws SAXException { if (rows.size() >= 512) throw new SAXException("XML datasource row limit"); rows.add(row); }
        public void error(SAXParseException e) throws SAXException { throw e; }
        public void fatalError(SAXParseException e) throws SAXException { throw e; }
        List<Map> finish() {
            Map<String,Map> unique = new LinkedHashMap<String,Map>();
            for (Map row : rows) {
                Object reference = row.remove("driverReference"); Map declaration = drivers.get(reference);
                if (row.get("driverName") == null && declaration != null) {
                    Object driverName = declaration.get("javax.sql.XADataSource".equals(row.get("resourceType")) ? "xa-datasource-class" : "driver-class");
                    if (driverName != null) row.put("driverName", driverName);
                }
                if (row.get("databaseType") == null) { String database = SpringDataSource.driverDatabase((String)row.get("driverName")); if (database != null) row.put("databaseType", database); }
                String key = Json.stringify(Arrays.asList(row.get("contextIdentity"), row.get("configurationContext"), row.get("name")));
                if (!unique.containsKey(key)) unique.put(key, row);
            }
            return new ArrayList<Map>(unique.values());
        }
    }
}
