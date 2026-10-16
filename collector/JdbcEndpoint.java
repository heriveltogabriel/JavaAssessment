package assessment;

import java.util.*;

/** Passive, bounded extraction. Never returns a raw URL, credentials or connection properties. */
final class JdbcEndpoint {
    static final int MAX_ENDPOINTS = 16;
    static void addTo(Map fields, String url) {
        Map parsed = parse(url);
        if (!parsed.isEmpty()) fields.putAll(parsed);
    }
    static Map parse(String url) {
        Map result = new LinkedHashMap();
        if (!SpringDataSource.resolved(url) || url.length() > 8192) return result;
        List<Map> endpoints = new ArrayList<Map>(); String name = null;
        try {
            if (url.startsWith("jdbc:oracle:thin:@") || url.startsWith("jdbc:oracle:oci:@") || url.startsWith("jdbc:oracle:thin:") || url.startsWith("jdbc:oracle:oci:")) {
                int at = url.lastIndexOf('@'); if (at < 0) return result;
                String target = url.substring(at + 1).trim();
                if (target.startsWith("(")) {
                    Descriptor parser = new Descriptor(target); Node root = parser.node(0);
                    parser.space(); if (parser.pos != target.length() || !(root.key.equals("DESCRIPTION") || root.key.equals("DESCRIPTION_LIST"))) return result;
                    List<String> names = new ArrayList<String>(); descriptor(root, endpoints, names);
                    if (names.size() == 1) name = names.get(0);
                } else {
                    target = strip(target);
                    if (target.startsWith("//")) target = target.substring(2);
                    int slash = target.indexOf('/');
                    if (slash >= 0) { name = name(target.substring(slash + 1)); authority(target.substring(0, slash), endpoints); }
                    else {
                        // Legacy host:port:SID. A bare TNS alias is not a hostname.
                        int last = target.lastIndexOf(':');
                        int addressEnd = target.startsWith("[") ? target.indexOf(']') : -1;
                        if (last > 0 && target.substring(addressEnd + 1, last).indexOf(':') >= 0 && !target.endsWith("]")) {
                            name = name(target.substring(last + 1)); endpoint(target.substring(0, last), endpoints);
                        } else if (target.indexOf(':') > 0) endpoint(target, endpoints);
                    }
                }
            } else {
                String[] schemes = {"jdbc:postgresql://", "jdbc:mysql://", "jdbc:mysql:loadbalance://", "jdbc:mysql:replication://", "jdbc:mariadb://", "jdbc:mariadb:sequential://", "jdbc:mariadb:loadbalance://", "jdbc:sqlserver://", "jdbc:db2://", "jdbc:derby://", "jdbc:h2:tcp://", "jdbc:h2:ssl://", "jdbc:hsqldb:hsql://", "jdbc:hsqldb:hsqls://"};
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
                } else {
                    target = strip(target); int slash = target.indexOf('/');
                    authority(slash < 0 ? target : target.substring(0, slash), endpoints);
                    if (slash >= 0) name = name(target.substring(slash + 1));
                }
            }
            if (!endpoints.isEmpty()) {
                result.put("databaseEndpoints", endpoints);
                result.put("databaseHost", endpoints.get(0).get("host"));
                if (endpoints.get(0).containsKey("port")) result.put("databasePort", endpoints.get(0).get("port"));
                if (name != null) result.put("databaseName", name);
                result.put("databaseEndpointEvidence", "configured");
            }
        } catch (IllegalArgumentException invalid) { return new LinkedHashMap(); }
        return result;
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
}
