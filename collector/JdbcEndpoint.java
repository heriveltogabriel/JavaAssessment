package assessment;

import java.io.File;
import java.util.*;

/** Passive, bounded extraction. Never returns a raw URL, credentials or connection properties. */
final class JdbcEndpoint {
    static final int MAX_ENDPOINTS = 16, TNS_LIMIT = 256 * 1024;
    static final String SECRET_KEY = ".*(password|passwd|pwd|secret|token|credential|username|user_name|userid|user_id).*|.*user$";
    static final String[][] TYPES = {{"jdbc:jtds:sqlserver:", "SQL Server"}, {"jdbc:jtds:sybase:", "Sybase"}, {"jdbc:sybase:", "Sybase"},
        {"jdbc:informix-sqli:", "Informix"}, {"jdbc:as400:", "DB2 for i"}, {"jdbc:teradata:", "Teradata"}, {"jdbc:sap:", "SAP HANA"}, {"jdbc:snowflake:", "Snowflake"}};
    static void addTo(Map fields, String url) {
        Map parsed = parse(url);
        if (!parsed.isEmpty()) fields.putAll(parsed);
    }
    /** Resolves allowlisted placeholders and TNS aliases, then retains only product, host, port and service. */
    static void addTo(Map fields, String raw, Resolver resolver) { addTo(fields, raw, resolver, new ArrayList<String>()); }
    static void addTo(Map fields, String raw, Resolver resolver, List<String> resolvedFrom) {
        if (raw == null) return;
        List<String> sources = new ArrayList<String>(resolvedFrom);
        String url = raw.indexOf("${") >= 0 && resolver != null ? resolver.expand(raw, sources) : raw;
        // An unresolved placeholder still leaves the product prefix readable.
        String database = databaseType(url == null ? raw : url);
        if (database != null && fields.get("databaseType") == null) fields.put("databaseType", database);
        if (url == null) return;
        Map parsed = parse(url, resolver, sources);
        if (!parsed.isEmpty()) fields.putAll(parsed);
    }
    static String databaseType(String url) {
        if (url == null) return null;
        String known = SpringDataSource.databaseType(url);
        if (known != null) return known;
        for (String[] type : TYPES) if (url.startsWith(type[0])) return type[1];
        return null;
    }
    /** Separate XA-style host/port/database properties. */
    static Map parts(String host, String port, String database, List<String> sources) {
        Map result = new LinkedHashMap(); List<Map> endpoints = new ArrayList<Map>();
        try { endpoint(host, port, endpoints); } catch (IllegalArgumentException invalid) { return result; }
        finish(result, endpoints, name(database), sources, false);
        return result;
    }
    static Map parse(String url) { return parse(url, null, new ArrayList<String>()); }
    static Map parse(String url, Resolver resolver, List<String> sources) {
        Map result = new LinkedHashMap();
        if (!SpringDataSource.resolved(url) || url.length() > 8192) return result;
        List<Map> endpoints = new ArrayList<Map>(); String name = null; boolean tns = false;
        try {
            if (url.startsWith("jdbc:oracle:thin:@") || url.startsWith("jdbc:oracle:oci:@") || url.startsWith("jdbc:oracle:thin:") || url.startsWith("jdbc:oracle:oci:")) {
                int at = url.lastIndexOf('@'); if (at < 0) return result;
                String target = url.substring(at + 1).trim(), lower = target.toLowerCase(Locale.ENGLISH);
                // Directory naming resolves the database through LDAP; the LDAP server is not the database.
                if (lower.startsWith("ldap://") || lower.startsWith("ldaps://")) { result.put("databaseEndpointEvidence", "directory-naming"); return result; }
                Node root = null;
                if (target.startsWith("(")) {
                    Descriptor parser = new Descriptor(target); root = parser.node(0);
                    parser.space(); if (parser.pos != target.length()) return result;
                } else if (strip(target).matches("[A-Za-z][A-Za-z0-9_.$-]{0,127}") && !strip(target).endsWith(".")) {
                    // A bare TNS alias is not a hostname: only a local tnsnames.ora entry can resolve it.
                    if (resolver == null) return result;
                    root = resolver.tns(strip(target), parameter(target, "TNS_ADMIN", '?', '&'));
                    if (root == null) return result;
                    tns = true; if (!sources.contains("tnsnames")) sources.add("tnsnames");
                }
                if (root != null) {
                    if (!(root.key.equals("DESCRIPTION") || root.key.equals("DESCRIPTION_LIST"))) return result;
                    List<String> names = new ArrayList<String>(); descriptor(root, endpoints, names);
                    if (names.size() == 1) name = names.get(0);
                } else {
                    target = strip(target);
                    if (target.startsWith("//")) target = target.substring(2);
                    int slash = target.indexOf('/');
                    if (slash >= 0) { name = name(target.substring(slash + 1)); authority(target.substring(0, slash), endpoints); }
                    else {
                        // Legacy host:port:SID.
                        int last = target.lastIndexOf(':');
                        int addressEnd = target.startsWith("[") ? target.indexOf(']') : -1;
                        if (last > 0 && target.substring(addressEnd + 1, last).indexOf(':') >= 0 && !target.endsWith("]")) {
                            name = name(target.substring(last + 1)); endpoint(target.substring(0, last), endpoints);
                        } else if (target.indexOf(':') > 0) endpoint(target, endpoints);
                    }
                }
            } else if (url.startsWith("jdbc:sybase:Tds:")) {
                String target = strip(url.substring(16)); int slash = target.indexOf('/');
                endpoint(slash < 0 ? target : target.substring(0, slash), endpoints);
                if (slash >= 0) name = name(target.substring(slash + 1));
            } else {
                String[] schemes = {"jdbc:postgresql://", "jdbc:mysql://", "jdbc:mysql:loadbalance://", "jdbc:mysql:replication://", "jdbc:mariadb://", "jdbc:mariadb:sequential://", "jdbc:mariadb:loadbalance://", "jdbc:sqlserver://", "jdbc:db2://", "jdbc:derby://", "jdbc:h2:tcp://", "jdbc:h2:ssl://", "jdbc:hsqldb:hsql://", "jdbc:hsqldb:hsqls://",
                    "jdbc:jtds:sqlserver://", "jdbc:jtds:sybase://", "jdbc:as400://", "jdbc:informix-sqli://", "jdbc:sap://", "jdbc:snowflake://", "jdbc:teradata://"};
                String scheme = null; for (String candidate : schemes) if (url.startsWith(candidate)) { scheme = candidate; break; }
                if (scheme == null) return result;
                String target = url.substring(scheme.length());
                if (scheme.equals("jdbc:sqlserver://")) {
                    String[] parts = target.split(";"); authority(parts[0], endpoints);
                    // Braced SQL Server values can contain semicolons and fake property names.
                    // Keep the authority but omit the database name when this syntax is present.
                    if (target.indexOf('{') < 0 && target.indexOf('}') < 0) {
                        int names = 0;
                        for (int i = 1; i < parts.length; i++) { int eq = parts[i].indexOf('='); if (eq > 0 && parts[i].substring(0, eq).equalsIgnoreCase("databaseName")) { names++; name = name(parts[i].substring(eq + 1)); } }
                        if (names > 1) name = null;
                    }
                } else if (scheme.equals("jdbc:teradata://")) {
                    // jdbc:teradata://host/DATABASE=x,DBS_PORT=1025,...: only these two parameters are read.
                    int slash = target.indexOf('/'); String settings = slash < 0 ? "" : target.substring(slash + 1);
                    String host = slash < 0 ? target : target.substring(0, slash), port = parameter("?" + settings, "DBS_PORT", '?', ',');
                    if (port != null && host.indexOf(':') < 0) endpoint(host, port, endpoints); else authority(host, endpoints);
                    name = name(parameter("?" + settings, "DATABASE", '?', ','));
                } else {
                    String query = target; target = strip(target); int slash = target.indexOf('/');
                    authority(slash < 0 ? target : target.substring(0, slash), endpoints);
                    String path = slash < 0 ? "" : target.substring(slash + 1);
                    // Informix: host:port/database:INFORMIXSERVER=name.
                    if (scheme.equals("jdbc:informix-sqli://") && path.indexOf(':') > 0) path = path.substring(0, path.indexOf(':'));
                    if (path.length() > 0) name = name(path);
                    else if (scheme.equals("jdbc:sap://")) name = name(parameter(query, "databaseName", '?', '&'));
                    else if (scheme.equals("jdbc:snowflake://")) name = name(parameter(query, "db", '?', '&'));
                }
            }
            finish(result, endpoints, name, sources, tns);
        } catch (IllegalArgumentException invalid) { return new LinkedHashMap(); }
        return result;
    }
    static void finish(Map result, List<Map> endpoints, String name, List<String> sources, boolean tns) {
        if (endpoints.isEmpty()) return;
        result.put("databaseEndpoints", endpoints);
        result.put("databaseHost", endpoints.get(0).get("host"));
        if (endpoints.get(0).containsKey("port")) result.put("databasePort", endpoints.get(0).get("port"));
        if (name != null) result.put("databaseName", name);
        result.put("databaseEndpointEvidence", tns ? "tnsnames" : sources.isEmpty() ? "configured" : "configured-resolved");
        if (!sources.isEmpty()) result.put("resolvedFrom", new ArrayList<String>(sources));
    }
    /** One named parameter after the first marker, only when it appears exactly once. */
    static String parameter(String value, String key, char marker, char separator) {
        int start = value.indexOf(marker); if (start < 0) return null;
        String found = null; int count = 0;
        for (String pair : value.substring(start + 1).split(java.util.regex.Pattern.quote(String.valueOf(separator)))) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equalsIgnoreCase(key)) { count++; found = pair.substring(eq + 1).trim(); }
        }
        return count == 1 ? found : null;
    }
    static String strip(String value) {
        int end = value.length(); for (char c : new char[]{'?', ';', '#'}) { int i = value.indexOf(c); if (i >= 0 && i < end) end = i; }
        return value.substring(0, end);
    }
    static String name(String value) { return value != null && value.length() <= 256 && value.matches("[A-Za-z0-9_.$-]+") ? value : null; }
    static void authority(String value, List<Map> rows) {
        // Strip standard user-info as a whole; never retain any portion of it.
        int at = value.lastIndexOf('@'); if (at >= 0) value = value.substring(at + 1);
        for (String part : value.split(",")) endpoint(part.trim(), rows);
    }
    static void endpoint(String value, List<Map> rows) {
        String host, port = null;
        if (value.startsWith("[")) {
            int close = value.indexOf(']'); if (close < 0) throw new IllegalArgumentException();
            host = value.substring(1, close);
            if (close + 1 < value.length()) { if (value.charAt(close + 1) != ':') throw new IllegalArgumentException(); port = value.substring(close + 2); }
        } else {
            int colon = value.indexOf(':');
            host = colon < 0 ? value : value.substring(0, colon); port = colon < 0 ? null : value.substring(colon + 1);
            // SQL Server instance is configuration, not a hostname. Port may be omitted.
            int instance = host.indexOf('\\'); if (instance >= 0) host = host.substring(0, instance);
        }
        endpoint(host, port, rows);
    }
    static void endpoint(String host, String port, List<Map> rows) {
        if (host == null || host.length() == 0 || host.length() > 253 ||
            !(host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9.])?") || host.indexOf(':') >= 0 && host.matches("[0-9a-fA-F:.]+"))) throw new IllegalArgumentException();
        Map row = new LinkedHashMap(); row.put("host", host.toLowerCase(Locale.ENGLISH));
        if (port != null) {
            if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
            int number = Integer.parseInt(port); if (number < 1 || number > 65535) throw new IllegalArgumentException();
            row.put("port", Integer.valueOf(number));
        }
        if (!rows.contains(row)) { if (rows.size() >= MAX_ENDPOINTS) throw new IllegalArgumentException(); rows.add(row); }
    }
    static final class Node {
        String key, value; List<Node> children = new ArrayList<Node>();
        String scalar(String key) { String found = null; for (Node n : children) if (key.equals(n.key)) { if (found != null || n.value == null) throw new IllegalArgumentException(); found = n.value; } return found; }
    }
    static final class Descriptor {
        final String text; int pos, nodes;
        Descriptor(String text) { this.text = text; }
        void space() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        void expect(char c) { space(); if (pos >= text.length() || text.charAt(pos++) != c) throw new IllegalArgumentException(); }
        Node node(int depth) {
            if (depth > 16 || ++nodes > 256) throw new IllegalArgumentException();
            expect('('); space(); int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) pos++;
            if (pos == start) throw new IllegalArgumentException();
            Node n = new Node(); n.key = text.substring(start, pos).toUpperCase(Locale.ENGLISH); expect('='); space();
            if (pos < text.length() && text.charAt(pos) == '(') { while (pos < text.length() && text.charAt(pos) == '(') { n.children.add(node(depth + 1)); space(); } }
            else { start = pos; while (pos < text.length() && text.charAt(pos) != ')') { if (text.charAt(pos) == '(') throw new IllegalArgumentException(); pos++; } n.value = text.substring(start, pos).trim(); }
            expect(')'); return n;
        }
    }
    static void descriptor(Node node, List<Map> endpoints, List<String> names) {
        if (node.key.equals("ADDRESS")) {
            String protocol = node.scalar("PROTOCOL");
            if ("TCP".equalsIgnoreCase(protocol) || "TCPS".equalsIgnoreCase(protocol)) endpoint(node.scalar("HOST"), node.scalar("PORT"), endpoints);
            return;
        }
        if (node.key.equals("CONNECT_DATA")) {
            String value = name(node.scalar("SERVICE_NAME")); if (value == null) value = name(node.scalar("SID"));
            if (value != null && !names.contains(value)) names.add(value); return;
        }
        if (node.key.equals("DESCRIPTION") || node.key.equals("DESCRIPTION_LIST") || node.key.equals("ADDRESS_LIST"))
            for (Node child : node.children) descriptor(child, endpoints, names);
    }
    /**
     * Passive placeholder and TNS resolution for one process. Reads only the process command line and
     * environment (bounded), returns only the requested key and never resolves secret-like keys or expressions.
     */
    static class Resolver {
        final AssessmentScan scan; final File process;
        String[] arguments, environment; boolean used;
        Map<String,String> serverProperties = new HashMap<String,String>();
        String serverPropertySource; boolean environmentPropertySource;
        final Map<String,Node> aliases = new HashMap<String,Node>();
        Resolver(AssessmentScan scan, File process) { this.scan = scan; this.process = process; }
        static void source(List<String> sources, String value) { if (!sources.contains(value)) sources.add(value); }
        static boolean secret(String key) { return key.toLowerCase(Locale.ENGLISH).matches(SECRET_KEY) || key.toLowerCase(Locale.ENGLISH).matches("(?:.*[._-])?pass(?:[._-].*)?"); }
        String[] lines(String name, String[] cached) {
            if (cached != null) return cached;
            String text = process == null || scan == null ? null : scan.metadataText(new File(process, name), 65536, false);
            return text == null ? new String[0] : text.split("\u0000");
        }
        /** Last -Dkey=value wins, as in the JVM. */
        String systemProperty(String key) {
            if (secret(key)) return null;
            arguments = lines("cmdline", arguments); String found = null;
            for (String arg : arguments) if (arg.startsWith("-D" + key + "=")) found = arg.substring(key.length() + 3);
            return found;
        }
        String property(String key) { String value=systemProperty(key);return value!=null?value:secret(key)?null:serverProperties.get(key); }
        String environment(String key) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || secret(key)) return null;
            environment = lines("environ", environment);
            for (String pair : environment) if (pair.startsWith(key + "=")) return pair.substring(key.length() + 1);
            return null;
        }
        /** JBoss/WildFly semantics: env.NAME reads the environment; other keys are -D system properties. */
        String lookup(String key, List<String> sources) {
            if(secret(key))return null;
            String found=key.startsWith("env.")?environment(key.substring(4)):systemProperty(key);
            if(found!=null)source(sources,key.startsWith("env.")?"environment":"system-property");
            else if(!key.startsWith("env.")){found=serverProperties.get(key);if(found!=null)source(sources,serverPropertySource==null?"server-properties":serverPropertySource);else if(environmentPropertySource){found=environment(key);if(found!=null)source(sources,"environment");}}
            return found;
        }
        String expand(String value, List<String> sources) { return expand(value, sources, 0); }
        String expand(String value, List<String> sources, int depth) {
            if (value == null || depth > 8 || value.length() > 8192 || value.indexOf("#{") >= 0 || value.startsWith("ENC(") || value.indexOf("VAULT::") >= 0) return null;
            StringBuilder out = new StringBuilder(); int index = 0, count = 0;
            while (true) {
                int start = value.indexOf("${", index);
                if (start < 0) { out.append(value.substring(index)); break; }
                out.append(value.substring(index, start));
                int end = close(value, start + 2);
                if (end < 0 || ++count > 16) return null;
                used = true;
                String inside = value.substring(start + 2, end), found = null; int colon = inside.indexOf(':');
                for (String key : (colon < 0 ? inside : inside.substring(0, colon)).split(",", -1)) {
                    key = key.trim();
                    if (!key.matches("[A-Za-z0-9_.\\-]{1,128}") || secret(key)) return null;
                    if (found == null) found = lookup(key, sources);
                }
                if (found == null && colon >= 0) { found = inside.substring(colon + 1); source(sources, "default"); }
                found = expand(found, sources, depth + 1);
                if (found == null) return null;
                out.append(found); index = end + 1;
                if (out.length() > 8192) return null;
            }
            String result = out.toString();
            return SpringDataSource.resolved(result) ? result : null;
        }
        static int close(String value, int from) {
            int depth = 1;
            for (int i = from; i < value.length(); i++) {
                if (value.charAt(i) == '{') depth++;
                else if (value.charAt(i) == '}' && --depth == 0) return i;
            }
            return -1;
        }
        /** Oracle thin search order: URL TNS_ADMIN, -Doracle.net.tns_admin, TNS_ADMIN, then ORACLE_HOME/network/admin. */
        Node tns(String alias, String urlDirectory) {
            used = true;
            String home = environment("ORACLE_HOME");
            String[] directories = {urlDirectory, property("oracle.net.tns_admin"), environment("TNS_ADMIN"), home == null ? null : home + "/network/admin"};
            for (String directory : directories) {
                if (directory == null || directory.length() > 4096 || !new File(directory).isAbsolute() || directory.indexOf("${") >= 0) continue;
                File file = scan.linkedPath(new File(directory, "tnsnames.ora"));
                if (file == null || !file.isFile()) continue;
                String key = file.getPath() + "\u0000" + alias.toUpperCase(Locale.ENGLISH);
                if (aliases.containsKey(key)) return aliases.get(key);
                Node found = null;
                if (scan.visitLinked(file)) {
                    String text = scan.read(file, TNS_LIMIT);
                    if (text != null) try { found = tnsEntry(text, alias); } catch (IllegalArgumentException malformed) { scan.warn("Oracle tnsnames.ora could not be parsed within bounds: " + file.getPath()); }
                }
                aliases.put(key, found);
                // The first tnsnames.ora found is the one the driver uses.
                return found;
            }
            return null;
        }
    }
    /** Finds one alias in tnsnames.ora. IFILE and other non-descriptor entries are skipped, not followed. */
    static Node tnsEntry(String text, String alias) {
        StringBuilder clean = new StringBuilder(text.length());
        for (String line : text.split("\r?\n", -1)) { int hash = line.indexOf('#'); clean.append(hash < 0 ? line : line.substring(0, hash)).append('\n'); }
        Descriptor parser = new Descriptor(clean.toString()); Node match = null; int entries = 0;
        while (true) {
            parser.space();
            if (parser.pos >= parser.text.length()) return match;
            if (++entries > 4096) throw new IllegalArgumentException();
            int eq = parser.text.indexOf('=', parser.pos);
            if (eq < 0) return match;
            String names = parser.text.substring(parser.pos, eq); parser.pos = eq + 1; parser.space();
            if (parser.pos >= parser.text.length() || parser.text.charAt(parser.pos) != '(') {
                int line = parser.text.indexOf('\n', parser.pos); parser.pos = line < 0 ? parser.text.length() : line; continue;
            }
            parser.nodes = 0; Node node = parser.node(0);
            for (String name : names.split(",")) if (name.trim().equalsIgnoreCase(alias)) {
                // shortcut: exact alias only; NAMES.DEFAULT_DOMAIN from sqlnet.ora is not applied.
                if (match != null) throw new IllegalArgumentException();
                match = node;
            }
        }
    }
}
