package assessment;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Allowlisted configuration evidence only. Never resolves settings or opens JDBC connections. */
final class SpringDataSource {
    static final String PREFIX = "BOOT-INF/classes/";
    static final String[] BASE_FILES = { PREFIX + "application.properties", PREFIX + "application.yaml", PREFIX + "application.yml" };
    static final String URL = "spring.datasource.url", HIKARI_URL = "spring.datasource.hikari.jdbcurl";
    static final String DRIVER = "spring.datasource.driverclassname", HIKARI_DRIVER = "spring.datasource.hikari.driverclassname";
    static final String POOL = "spring.datasource.hikari.maximumpoolsize";
    static final class Parsed {
        final Map fields; final String notice;
        Parsed(Map fields, String notice) { this.fields = fields; this.notice = notice; }
    }
    static String key(String value) { return value.toLowerCase(Locale.ENGLISH).replace("-", "").replace("_", ""); }
    static boolean allowed(String value) {
        return value.equals(URL) || value.equals(HIKARI_URL) || value.equals(DRIVER) || value.equals(HIKARI_DRIVER) || value.equals(POOL);
    }
    static boolean condition(String value) { return value.equals("spring.config.activate.onprofile") || value.equals("spring.config.activate.oncloudplatform") || value.equals("spring.profiles"); }
    static class AllowedProperties extends Properties {
        boolean conditional;
        Object storeAllowed(String key, String value) { return super.put(key, value); }
        public synchronized Object put(Object rawKey, Object rawValue) {
            if (!(rawKey instanceof String) || !(rawValue instanceof String)) return null;
            String key = key((String)rawKey), value = ((String)rawValue).trim();
            if (condition(key) && value.length() > 0) conditional = true;
            // Usernames, passwords, pool names and arbitrary application settings are never retained.
            return allowed(key) ? super.put(key, value) : null;
        }
    }
    static Parsed parse(String entry, byte[] content) throws IOException {
        if (content.length > ArchiveMetadata.METADATA_LIMIT) throw new IOException("Spring configuration exceeds bounded size");
        AllowedProperties values = new AllowedProperties();
        if (entry.endsWith(".properties")) {
            String raw = new String(content, "ISO-8859-1");
            for (String line : raw.split("\\r?\\n")) if (line.trim().equals("#---") || line.trim().equals("!---"))
                return new Parsed(new LinkedHashMap(), "Multi-document Spring configuration was skipped; activation was not resolved");
            try { values.load(new ByteArrayInputStream(content)); }
            catch (IllegalArgumentException malformed) { throw new IOException("Malformed Spring properties"); }
        } else parseYaml(content, values);
        if (values.conditional) return new Parsed(new LinkedHashMap(), "Profile-conditional Spring configuration was skipped; activation was not resolved");
        String url = first(values.getProperty(URL), values.getProperty(HIKARI_URL));
        String driver = validDriver(first(values.getProperty(DRIVER), values.getProperty(HIKARI_DRIVER)));
        boolean urlPresent = resolved(url) && url.length() <= 8192 && url.matches("jdbc:[A-Za-z][A-Za-z0-9_-]*:.*");
        if (!urlPresent && driver == null) return new Parsed(new LinkedHashMap(), null);
        Map result = new LinkedHashMap();
        if (driver != null) result.put("driverName", driver);
        String database = urlPresent ? databaseType(url) : null;
        if (database != null) result.put("databaseType", database);
        if (urlPresent) JdbcEndpoint.addTo(result, url);
        // This marker is internal; it is not a connection URL or a claim of connectivity.
        result.put("configurationPresent", Boolean.TRUE);
        String pool = values.getProperty(POOL);
        if (resolved(pool) && pool.matches("[0-9]{1,6}")) {
            int count = Integer.parseInt(pool);
            if (count >= 1 && count <= 100000) result.put("configuredMaxPoolSize", Integer.valueOf(count));
        }
        return new Parsed(result, null);
    }
    static String first(String first, String second) { return first == null || first.length() == 0 ? second : first; }
    static boolean resolved(String value) {
        if (value == null || value.length() == 0 || value.indexOf("${") >= 0 || value.indexOf("#{") >= 0) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return false;
        return true;
    }
    static String validDriver(String value) {
        return resolved(value) && value.length() <= 256 && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+") ? value : null;
    }
    static String databaseType(String url) {
        String[] protocols = {"h2", "postgresql", "mysql", "mariadb", "oracle", "sqlserver", "derby", "db2", "hsqldb", "jtds:sqlserver", "jtds:sybase", "sybase",
            "informix-sqli", "teradata", "sap", "snowflake", "sqlite", "firebirdsql"};
        String[] names = {"H2", "PostgreSQL", "MySQL", "MariaDB", "Oracle", "SQL Server", "Derby", "DB2", "HSQLDB", "SQL Server", "Sybase", "Sybase",
            "Informix", "Teradata", "SAP HANA", "Snowflake", "SQLite", "Firebird"};
        for (int i = 0; i < protocols.length; i++) if (url.startsWith("jdbc:" + protocols[i] + ":")) return names[i];
        return null;
    }
    static String driverDatabase(String driver) {
        if ("org.h2.Driver".equals(driver)) return "H2";
        if ("org.postgresql.Driver".equals(driver)) return "PostgreSQL";
        if ("com.mysql.cj.jdbc.Driver".equals(driver) || "com.mysql.jdbc.Driver".equals(driver)) return "MySQL";
        if ("org.mariadb.jdbc.Driver".equals(driver)) return "MariaDB";
        if ("oracle.jdbc.OracleDriver".equals(driver) || "oracle.jdbc.driver.OracleDriver".equals(driver)) return "Oracle";
        if ("com.microsoft.sqlserver.jdbc.SQLServerDriver".equals(driver)) return "SQL Server";
        if ("org.apache.derby.jdbc.EmbeddedDriver".equals(driver) || "org.apache.derby.jdbc.ClientDriver".equals(driver) || "org.apache.derby.iapi.jdbc.AutoloadedDriver".equals(driver)) return "Derby";
        if ("com.ibm.db2.jcc.DB2Driver".equals(driver)) return "DB2";
        if ("org.hsqldb.jdbc.JDBCDriver".equals(driver) || "org.hsqldb.jdbcDriver".equals(driver)) return "HSQLDB";
        if ("com.sybase.jdbc4.jdbc.SybDriver".equals(driver) || "com.sybase.jdbc3.jdbc.SybDriver".equals(driver) || "com.sybase.jdbc2.jdbc.SybDriver".equals(driver)) return "Sybase";
        if ("com.informix.jdbc.IfxDriver".equals(driver)) return "Informix";
        if ("com.teradata.jdbc.TeraDriver".equals(driver)) return "Teradata";
        if ("com.sap.db.jdbc.Driver".equals(driver)) return "SAP HANA";
        if ("net.snowflake.client.jdbc.SnowflakeDriver".equals(driver)) return "Snowflake";
        if ("org.sqlite.JDBC".equals(driver)) return "SQLite";
        if ("org.firebirdsql.jdbc.FBDriver".equals(driver)) return "Firebird";
        // XA and pool DataSource classes (for example PGXADataSource) are named by vendor package.
        if (driver == null) return null;
        String[][] packages = {{"oracle.jdbc.", "Oracle"}, {"org.postgresql.", "PostgreSQL"}, {"com.mysql.", "MySQL"}, {"org.mariadb.", "MariaDB"},
            {"com.microsoft.sqlserver.", "SQL Server"}, {"com.ibm.db2.", "DB2"}, {"org.h2.", "H2"}, {"org.apache.derby.", "Derby"}};
        for (String[] item : packages) if (driver.startsWith(item[0])) return item[1];
        // jTDS serves both SQL Server and Sybase, so the driver class alone does not name the database.
        return null;
    }
    static String libraryDatabase(String path) {
        String name = ArchiveMetadata.base(path).toLowerCase(Locale.ENGLISH);
        if (name.equals("h2.jar") || name.startsWith("h2-")) return "H2";
        if (name.startsWith("postgresql")) return "PostgreSQL";
        if (name.startsWith("mysql-connector")) return "MySQL";
        if (name.startsWith("mariadb-java-client")) return "MariaDB";
        if (name.startsWith("ojdbc") || name.equals("classes12.jar")) return "Oracle";
        if (name.startsWith("mssql-jdbc") || name.startsWith("sqljdbc")) return "SQL Server";
        if (name.equals("derby.jar") || name.equals("derbyclient.jar") || name.startsWith("derby-") || name.startsWith("derbyclient-")) return "Derby";
        if (name.startsWith("db2jcc") || name.matches("jcc-[0-9].*")) return "DB2";
        if (name.equals("hsqldb.jar") || name.startsWith("hsqldb-")) return "HSQLDB";
        if (name.startsWith("jconn")) return "Sybase";
        if (name.startsWith("ifxjdbc")) return "Informix";
        if (name.startsWith("terajdbc")) return "Teradata";
        if (name.startsWith("ngdbc")) return "SAP HANA";
        if (name.startsWith("snowflake-jdbc")) return "Snowflake";
        if (name.startsWith("sqlite-jdbc")) return "SQLite";
        if (name.startsWith("jaybird")) return "Firebird";
        // shortcut: Maven Informix jdbc-<version>.jar is generic by name, so it is not matched by path; add a pom-based match if needed.
        return null;
    }
    static void matchPackagedDriver(Map fields, List libraries) {
        String driver = (String)fields.get("driverName"), database = (String)fields.get("databaseType");
        String driverType = driverDatabase(driver);
        if (driver != null && driverType == null || driverType != null && database != null && !driverType.equals(database)) return;
        String type = driverType == null ? database : driverType;
        if (type == null) return;
        int matches = 0; String version = null, artifact = null;
        for (Object value : libraries) if (value instanceof Map) {
            Map library = (Map)value; Object rawPath = library.get("path");
            if (!(rawPath instanceof String) || !"JDBC".equals(library.get("category"))) continue;
            if (!type.equals(libraryDatabase((String)rawPath))) continue;
            matches++;
            artifact = (String)rawPath;
            Object rawVersion = library.get("version");
            if (rawVersion instanceof String && ((String)rawVersion).matches("[0-9][A-Za-z0-9._+\\-]{0,127}")) version = (String)rawVersion;
        }
        if (matches == 1) {
            fields.put("driverArtifact", artifact);
            if (version != null) fields.put("driverVersion", version);
        }
    }
    static boolean profileFile(String name) {
        return name.startsWith(PREFIX + "application-") && (name.endsWith(".properties") || name.endsWith(".yaml") || name.endsWith(".yml"));
    }
    static void parseYaml(byte[] content, AllowedProperties values) throws IOException {
        CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try { text = decoder.decode(ByteBuffer.wrap(content)).toString(); }
        catch (CharacterCodingException malformed) { throw new IOException("Malformed Spring YAML encoding"); }
        List<String> parents = new ArrayList<String>(); List<Integer> indents = new ArrayList<Integer>(); Set<String> keys = new HashSet<String>();
        Map<String,Integer> childIndents = new HashMap<String,Integer>();
        int previousIndent = -1; boolean previousMapping = true;
        for (String raw : text.split("\\r?\\n")) {
            if (raw.indexOf('\t') >= 0) throw new IOException("Unsupported Spring YAML indentation");
            for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) < 32 || raw.charAt(i) == 127) throw new IOException("Unsupported Spring YAML control character");
            String line = yamlComment(raw).trim();
            if (line.length() == 0) continue;
            if (line.equals("---") || line.equals("...") || line.startsWith("-") || line.startsWith("%")) throw new IOException("Unsupported Spring YAML document structure");
            int indent = 0; while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            if (previousIndent >= 0 && indent > previousIndent && !previousMapping) throw new IOException("Unsupported Spring YAML structure");
            while (!indents.isEmpty() && indent <= indents.get(indents.size() - 1).intValue()) { indents.remove(indents.size() - 1); parents.remove(parents.size() - 1); }
            int colon = line.indexOf(':');
            if (colon <= 0 || colon + 1 < line.length() && line.charAt(colon + 1) != ' ') throw new IOException("Unsupported Spring YAML mapping");
            String name = line.substring(0, colon).trim(), scalar = line.substring(colon + 1).trim();
            if (!name.matches("[A-Za-z0-9_.-]+")) throw new IOException("Unsupported Spring YAML key");
            StringBuilder qualified = new StringBuilder(); for (String parent : parents) qualified.append(parent).append('.');
            String parentPath = qualified.toString(); Integer expectedIndent = childIndents.get(parentPath);
            if (expectedIndent != null && expectedIndent.intValue() != indent) throw new IOException("Inconsistent Spring YAML mapping indentation");
            childIndents.put(parentPath, Integer.valueOf(indent)); qualified.append(name);
            String path = qualified.toString();
            if (!keys.add(key(path))) throw new IOException("Duplicate Spring YAML key");
            previousIndent = indent; previousMapping = scalar.length() == 0;
            if (previousMapping) { if (parents.size() >= 12) throw new IOException("Spring YAML nesting limit reached"); parents.add(name); indents.add(Integer.valueOf(indent)); }
            else values.put(path, yamlScalar(scalar));
        }
    }
    static String yamlComment(String value) throws IOException {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') { i++; continue; }
                if (c == quote) { if (quote == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') i++; else quote = 0; }
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '#' && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) return value.substring(0, i);
        }
        if (quote != 0) throw new IOException("Unterminated Spring YAML scalar");
        return value;
    }
    static String yamlScalar(String value) throws IOException {
        // Preserve exact Spring placeholders as inert text; the passive resolver handles only allowlisted sources.
        if (value.startsWith("${") && value.endsWith("}") && value.indexOf("${", 2) < 0) return value;
        if (value.startsWith("'") && value.endsWith("'")) {
            String body = value.substring(1, value.length() - 1); StringBuilder result = new StringBuilder();
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\'') { if (i + 1 >= body.length() || body.charAt(i + 1) != '\'') throw new IOException("Unsupported Spring YAML quoted scalar"); i++; }
                result.append(c);
            }
            return result.toString();
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            String result = value.substring(1, value.length() - 1);
            if (result.indexOf('\\') >= 0 || result.indexOf('"') >= 0) throw new IOException("Unsupported Spring YAML quoted escape");
            return result;
        }
        // Embedded placeholders such as jdbc:oracle:thin:@${DB_HOST}:${DB_PORT:1521}/SVC are inert text, not YAML flow mappings.
        String structure = value.replaceAll("\\$\\{[A-Za-z0-9_.,:/@-]*\\}", "");
        if (structure.indexOf('[') >= 0 || structure.indexOf(']') >= 0 || structure.indexOf('{') >= 0 || structure.indexOf('}') >= 0 || value.startsWith("&") || value.startsWith("*") || value.startsWith("!") || value.startsWith("|") || value.startsWith(">") || value.indexOf(": ") >= 0)
            throw new IOException("Unsupported Spring YAML scalar structure");
        return value.equals("null") || value.equals("~") ? "" : value;
    }
}
