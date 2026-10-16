package assessment;

import java.io.*;
import java.util.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Reads referenced domain JDBC system modules. No WLST, JMX, credentials or DB connections. */
final class WebLogicDataSources {
    final ServerDataSources owner;
    final AssessmentScan scan;
    final Set<String> parsedConfigurations = new HashSet<String>();
    WebLogicDataSources(ServerDataSources owner) { this.owner = owner; this.scan = owner.scan; }

    void discover(AssessmentScan.ProcessContext context) {
        if (context.launch.serverBase == null) { scan.warn("WebLogic JDBC domain directory could not be resolved from the observed process"); return; }
        File base = domain(context);
        if (base == null) return;
        if (context.server != null) { context.server.put("domainName", base.getName()); context.server.put("domainPath", base.getPath()); }
        if (context.application != null) { context.application.put("domainName", base.getName()); context.application.put("domainPath", base.getPath()); }
        File config = new File(base, "config");
        Set<String> referenced = new HashSet<String>();
        File domainConfig = scan.linkedPath(new File(config, "config.xml"));
        if (domainConfig == null) return;
        List<Map> references = load(domainConfig, true);
        if (!parsedConfigurations.contains("WebLogic:true:" + domainConfig.getPath())) return;
        boolean referencesComplete = true;
        for (Map reference : references) {
            if (!scan.available()) return;
            String descriptor = (String)reference.get("descriptor");
            if (descriptor == null || descriptor.length() > 512 || !descriptor.startsWith("jdbc/") || !descriptor.endsWith(".xml") ||
                !descriptor.matches("[A-Za-z0-9_./ \\-]+") || Arrays.asList(descriptor.split("/")).contains("..")) {
                scan.warn("WebLogic JDBC descriptor reference is unsupported or outside config/jdbc"); referencesComplete = false; continue;
            }
            File source = scan.linkedPath(new File(config, descriptor));
            if (source == null) { referencesComplete = false; continue; }
            // Resolve the descriptor but keep the lexical domain boundary: no escaping symlinks.
            if (!AssessmentScan.under(source.getPath(), new File(config, "jdbc").getPath())) {
                scan.warn("WebLogic JDBC descriptor resolves outside config/jdbc"); referencesComplete = false; continue;
            }
            referenced.add(source.getPath());
            for (Map fields : load(source, false)) {
                if (!reference.get("name").equals(fields.get("name"))) { scan.warn("WebLogic JDBC module name differs from its domain reference"); continue; }
                Map row = new LinkedHashMap(fields);
                row.put("configurationContext", "WebLogic domain " + base.getPath());
                row.put("configuredTargets", reference.get("configuredTargets"));
                row.put("configurationScope", "domain-system-module");
                row.put("domainName", base.getName()); row.put("domainPath", base.getPath());
                owner.add(source, row, context);
            }
        }
        // Inspect only this live process's domain, never sibling domains on disk.
        if (!referencesComplete) return;
        File jdbc = scan.linkedPath(new File(config, "jdbc"));
        if (jdbc != null && jdbc.equals(new File(config, "jdbc")) && jdbc.isDirectory()) for (File child : owner.children(jdbc)) {
            if (!scan.available() || scan.filesVisited >= scan.maxFiles) break;
            File source = scan.linkedPath(child);
            if (source == null || !source.equals(child) || !source.isFile() || !source.getName().endsWith(".xml") || referenced.contains(source.getPath())) continue;
            for (Map fields : load(source, false)) {
                Map row = new LinkedHashMap(fields);
                row.put("configurationContext", "WebLogic domain " + base.getPath());
                row.put("domainName", base.getName()); row.put("domainPath", base.getPath());
                row.put("configurationScope", "domain-unreferenced-module");
                row.put("evidence", "JDBC descriptor in a running WebLogic domain; no domain system-resource reference found; deployment and connectivity not verified");
                owner.add(source, row, context);
            }
        }
    }

    File domain(AssessmentScan.ProcessContext context) {
        File candidate = new File(context.launch.serverBase);
        for (int depth = 0; candidate != null && depth < 6; depth++, candidate = candidate.getParentFile()) {
            File base = scan.linkedPath(candidate);
            if (base == null) return null;
            File descriptor = scan.linkedPath(new File(base, "config/config.xml"));
            if (descriptor != null && descriptor.isFile()) return base;
            if (context.launch.serverBaseExplicit) break;
        }
        scan.warn("WebLogic JDBC domain config/config.xml was not found for the observed process"); return null;
    }

    List<Map> load(File source, boolean domain) {
        File file = scan.linkedPath(source);
        if (file == null || !scan.available() || !scan.visitLinked(file)) return Collections.emptyList();
        String key = "WebLogic:" + domain + ":" + file.getPath();
        if (owner.cache.containsKey(key)) return owner.cache.get(key);
        if (owner.cache.size() >= ServerDataSources.MAX_CONFIGS || owner.cachedRows >= 512 || scan.datasources.size() >= 512) {
            scan.warn("WebLogic JDBC configuration count limit reached"); return Collections.emptyList();
        }
        List<Map> rows = new ArrayList<Map>(); owner.cache.put(key, rows);
        if (!file.isFile() || !file.canRead()) { scan.warn("WebLogic JDBC configuration is unavailable: " + file.getPath()); return rows; }
        String xml = scan.read(file, ServerDataSources.CONFIG_LIMIT);
        if (xml == null) return rows;
        try {
            Handler handler = new Handler(domain, scan);
            ServerDataSources.parseXml(xml, handler);
            if (owner.cachedRows + handler.rows.size() > 512) throw new SAXException("Row limit");
            rows.addAll(handler.rows); owner.cachedRows += rows.size();
            parsedConfigurations.add(key);
        } catch (Exception e) { scan.warn("WebLogic JDBC XML was rejected (malformed, unsafe or beyond parser limits): " + file.getPath()); }
        return rows;
    }

    static final class Frame {
        final String name, uri; final StringBuilder text = new StringBuilder();
        Frame(String name, String uri) { this.name = name; this.uri = uri; }
    }
    static final class Handler extends DefaultHandler {
        final boolean domain; final AssessmentScan scan;
        final List<Frame> stack = new ArrayList<Frame>();
        final List<Map> rows = new ArrayList<Map>();
        Map current; String namespace; int elements;
        Handler(boolean domain, AssessmentScan scan) { this.domain = domain; this.scan = scan; }
        String path() {
            StringBuilder path = new StringBuilder();
            for (Frame frame : stack) { if (!frame.uri.equals(namespace)) return ""; if (path.length() > 0) path.append('/'); path.append(frame.name); }
            return path.toString();
        }
        public void startElement(String uri, String local, String qualified, Attributes attrs) throws SAXException {
            if (++elements > ServerDataSources.MAX_ELEMENTS || stack.size() >= ServerDataSources.MAX_DEPTH || !scan.available()) throw new SAXException("XML budget");
            String name = local.length() > 0 ? local : qualified;
            if (stack.isEmpty()) {
                String root = domain ? "domain" : "jdbc-data-source";
                if (!name.equals(root) || !(uri.equals("http://xmlns.oracle.com/weblogic/" + root) || uri.equals("http://www.bea.com/ns/weblogic/90" + (domain ? "" : "/jdbc-data-source")))) throw new SAXException("Unexpected WebLogic XML root");
                namespace = uri;
            }
            stack.add(new Frame(name, uri));
            if (domain && path().equals("domain/jdbc-system-resource")) current = new LinkedHashMap();
            if (!domain && stack.size() == 1) current = AssessmentScan.map("resourceType", "javax.sql.DataSource");
        }
        boolean selected(String path) {
            return domain ? path.equals("domain/jdbc-system-resource/name") || path.equals("domain/jdbc-system-resource/descriptor-file-name") || path.equals("domain/jdbc-system-resource/target") :
                path.equals("jdbc-data-source/name") || path.equals("jdbc-data-source/jdbc-driver-params/driver-name") || path.equals("jdbc-data-source/jdbc-driver-params/url") ||
                path.equals("jdbc-data-source/jdbc-connection-pool-params/max-capacity") || path.equals("jdbc-data-source/jdbc-data-source-params/jndi-name") || path.equals("jdbc-data-source/jdbc-data-source-params/data-source-list");
        }
        public void characters(char[] chars, int start, int length) throws SAXException {
            if (stack.isEmpty() || current == null || !selected(path())) return;
            Frame frame = stack.get(stack.size() - 1);
            if (frame.text.length() + length > 8192) throw new SAXException("XML scalar budget");
            frame.text.append(chars, start, length);
        }
        public void endElement(String uri, String local, String qualified) throws SAXException {
            String path = path(); Frame frame = stack.get(stack.size() - 1); String value = frame.text.toString().trim();
            if (current != null) {
                if (path.equals(domain ? "domain/jdbc-system-resource/name" : "jdbc-data-source/name")) {
                    String name = ServerDataSources.resourceName(value); if (name != null) current.put("name", name);
                } else if (domain && path.equals("domain/jdbc-system-resource/descriptor-file-name")) {
                    if (value.length() <= 512) current.put("descriptor", value);
                } else if (domain && path.equals("domain/jdbc-system-resource/target")) {
                    List<String> targets = new ArrayList<String>();
                    for (String target : value.split(",")) { String label = ServerDataSources.resourceName(target.trim()); if (label != null && targets.size() < 64 && !targets.contains(label)) targets.add(label); }
                    current.put("configuredTargets", targets);
                } else if (!domain) {
                    if (path.equals("jdbc-data-source/jdbc-driver-params/driver-name")) { String driver = SpringDataSource.validDriver(value); if (driver != null) current.put("driverName", driver); }
                    else if (path.equals("jdbc-data-source/jdbc-driver-params/url")) { String database = SpringDataSource.databaseType(value); if (database != null) current.put("databaseType", database); JdbcEndpoint.addTo(current, value); }
                    else if (path.equals("jdbc-data-source/jdbc-connection-pool-params/max-capacity")) { Integer max = ServerDataSources.pool(value); if (max != null) current.put("configuredMaxPoolSize", max); }
                    else if (path.equals("jdbc-data-source/jdbc-data-source-params/jndi-name")) {
                        String name = ServerDataSources.resourceName(value);
                        List<String> names = (List<String>) current.get("jndiNames");
                        if (names == null) { names = new ArrayList<String>(); current.put("jndiNames", names); }
                        if (name != null && names.size() < 16 && !names.contains(name)) names.add(name);
                    } else if (path.equals("jdbc-data-source/jdbc-data-source-params/data-source-list")) current.put("resourceType", "WebLogic Multi Data Source");
                }
                if (path.equals(domain ? "domain/jdbc-system-resource" : "jdbc-data-source")) {
                    if (rows.size() >= 128) throw new SAXException("XML resource budget");
                    if (current.get("name") != null) rows.add(current);
                    current = null;
                }
            }
            stack.remove(stack.size() - 1);
        }
        public void error(SAXParseException e) throws SAXException { throw e; }
        public void fatalError(SAXParseException e) throws SAXException { throw e; }
    }
}
