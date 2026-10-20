package assessment;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Server homes recognized from marker files: identity comes from target files only and product internals are not swept. */
final class ProductHomes {
    // shortcut: fixed child names cover 10.3-14.1 layouts; versioned names (coherence_3.x) are swept, list them if they cost budget.
    static final String[] ORACLE_INTERNALS = {"wlserver", "wlserver_10.3", "wlserver_12.1", "oracle_common", "coherence", "OPatch",
        "inventory", "oui", "install", "cfgtoollogs", "em", "modules", "utils"};
    static final String[] JBOSS_INTERNALS = {"modules/system", "docs"}, TOMCAT_INTERNALS = {"lib", "bin"};
    static final int REGISTRY_LIMIT = 1024 * 1024;
    static final Pattern VERSION_TEXT = Pattern.compile("^\\s*(\\S[^\\n]{0,120}?)\\s+-\\s+Version\\s+([0-9][0-9A-Za-z._-]{0,40})");

    /** Registers a recognized home and returns true when the whole directory is product internals. */
    static boolean sweep(AssessmentScan scan, File dir, String path) {
        boolean inventory = new File(dir, "inventory/registry.xml").isFile();
        File wl = inventory || new File(dir, "registry.xml").isFile() ? webLogicServer(dir) : null;
        if (wl != null) {
            Map product = inventory ? webLogicRegistry(scan, new File(dir, "inventory/registry.xml")) : new HashMap();
            String source = "Oracle Home inventory registry";
            if (product.get("version") == null) { product.put("version", manifestVersion(scan, new File(wl, "server/lib/weblogic.jar"))); source = "weblogic.jar manifest"; }
            Map row = register(scan, "WebLogic", path, (String) product.get("version"), "WebLogic Oracle Home found; " + source + " read", new File(wl, "server/lib/weblogic.jar"));
            if (row != null) for (String field : new String[]{"distribution", "patches"}) if (product.get(field) != null) row.put(field, product.get(field));
            internals(scan, dir, ORACLE_INTERNALS);
            drivers(scan, new File(wl, "server/lib")); drivers(scan, new File(dir, "oracle_common/modules/oracle.jdbc"));
            return false;
        }
        File weblogic = new File(dir, "server/lib/weblogic.jar");
        if (weblogic.isFile()) {
            register(scan, "WebLogic", path, manifestVersion(scan, weblogic), "WebLogic server home found; weblogic.jar manifest read", weblogic);
            drivers(scan, weblogic.getParentFile()); return true;
        }
        if (new File(dir, "jboss-modules.jar").isFile() && new File(dir, "modules").isDirectory()) {
            String[] product = jboss(scan, dir);
            register(scan, product[0], path, product[1], "JBoss-family home found; " + (product[1] == null ? "release not identified" : product[2] + " read"), null);
            internals(scan, dir, JBOSS_INTERNALS); return false;
        }
        File catalina = new File(dir, "lib/catalina.jar");
        if (catalina.isFile() && new File(dir, "bin/catalina.sh").isFile()) {
            register(scan, "Tomcat", path, manifestVersion(scan, catalina), "Tomcat home found; catalina.jar metadata read", catalina);
            drivers(scan, catalina.getParentFile()); internals(scan, dir, TOMCAT_INTERNALS); return false;
        }
        return false;
    }
    static File webLogicServer(File home) {
        for (String name : new String[]{"wlserver", "wlserver_10.3", "wlserver_12.1"}) {
            File wl = new File(home, name);
            if (new File(wl, "server/lib/weblogic.jar").isFile()) return wl;
        }
        return null;
    }
    static void internals(AssessmentScan scan, File home, String[] names) {
        for (String name : names) if (scan.productInternals.size() < 4096) scan.productInternals.add(new File(home, name).getPath());
    }
    /** Keeps the historical file:<jar> key where a product jar exists, so installation IDs stay stable; a home a live server runs from gets no second row. */
    static Map register(AssessmentScan scan, String type, String path, String version, String evidence, File jar) {
        if (scan.processServerHomes.contains(path)) return null;
        String key = "home:" + path;
        try { if (jar != null) key = "file:" + jar.getCanonicalPath(); } catch (IOException e) { /* home key */ }
        if (scan.servers.containsKey(key)) return scan.servers.get(key);
        if (scan.servers.size() >= 256) { scan.warn("Server row limit reached"); return null; }
        Map row = AssessmentScan.map("id", AssessmentScan.id("server", scan.hostId + key), "hostId", scan.hostId, "type", type, "name", type + " installation",
            "version", version, "path", path, "state", null, "evidence", evidence + "; product internals were not traversed; runtime state unknown", "observedAt", scan.timestamp);
        scan.servers.put(key, row); return row;
    }
    static String manifestVersion(AssessmentScan scan, File jar) {
        return AssessmentScan.clean(scan.archiveMetadata.inspect(jar).get("serverVersion"));
    }
    /** Known JDBC/framework jars directly in a pruned library directory keep their library rows. */
    static void drivers(AssessmentScan scan, File directory) {
        if (!directory.isDirectory()) return;
        BoundedDirectory files = null;
        try {
            files = BoundedDirectory.open(directory, scan.deadline);
            for (File file; scan.available() && (file = files.next()) != null;) {
                String name = file.getName().toLowerCase(Locale.ENGLISH);
                if (!ArchiveMetadata.isKnownCandidate(name) || name.equals("weblogic.jar") || name.equals("catalina.jar") || !file.isFile()) continue;
                if (scan.sweepFiles >= scan.maxFiles) { scan.fileLimitReached = true; scan.warn("File count limit reached; remaining paths were not examined"); return; }
                scan.filesVisited++; scan.sweepFiles++;
                scan.inspectArtifact(file, null, false);
            }
        } catch (IOException e) { scan.warn("Directory enumeration incomplete (read or resource limit): " + directory.getPath()); }
        finally { if (files != null) files.close(); }
    }

    /** {version, distribution, patches} from an Oracle Home inventory/registry.xml; absent values are not guessed. */
    static Map webLogicRegistry(AssessmentScan scan, File registry) {
        Map result = new HashMap();
        if (registry.length() > REGISTRY_LIMIT) { scan.note("Oracle Home registry exceeds the bounded read limit: " + registry.getPath()); return result; }
        String text = scan.read(registry, REGISTRY_LIMIT); if (text == null) return result;
        Registry handler = new Registry(scan);
        // shortcut: only the first 256 KiB are parsed; distributions precede features, patches listed later may be missed.
        try { ServerDataSources.parseXml(text.length() > ServerDataSources.CONFIG_LIMIT ? text.substring(0, ServerDataSources.CONFIG_LIMIT) : text, handler); }
        catch (Exception truncatedOrInvalid) { if (text.length() <= ServerDataSources.CONFIG_LIMIT) scan.note("Oracle Home registry could not be parsed completely: " + registry.getPath()); }
        if (handler.version != null) result.put("version", handler.version);
        if (!handler.distributions.isEmpty()) result.put("distribution", join(handler.distributions));
        if (!handler.patches.isEmpty()) result.put("patches", handler.patches);
        return result;
    }
    static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) { if (text.length() > 0) text.append(", "); text.append(value); }
        return text.toString();
    }
    static final class Registry extends DefaultHandler {
        final AssessmentScan scan; String version; int elements;
        final List<String> distributions = new ArrayList<String>(), patches = new ArrayList<String>();
        Registry(AssessmentScan scan) { this.scan = scan; }
        public void startElement(String uri, String local, String qualified, Attributes attrs) throws SAXException {
            if (++elements > ServerDataSources.MAX_ELEMENTS || !scan.available()) throw new SAXException("XML budget");
            String name = local.length() > 0 ? local : qualified;
            if (name.equals("distribution") && (attrs.getValue("status") == null || "installed".equals(attrs.getValue("status")))) {
                String label = AssessmentScan.clean(attrs.getValue("name")), value = attrs.getValue("version");
                if (label == null || label.length() == 0 || label.length() > 128) return;
                if (distributions.size() < 8 && !distributions.contains(label)) distributions.add(label);
                if (version == null && label.indexOf("WebLogic Server") >= 0 && value != null && value.matches("[0-9][0-9A-Za-z._-]{0,40}")) version = value;
            } else if (name.equals("patch")) {
                String id = attrs.getValue("id");
                if (id != null && id.matches("[0-9]{4,12}") && patches.size() < 64 && !patches.contains(id)) patches.add(id);
            }
        }
    }

    /** {type, version, source} for a JBoss/WildFly home: the product module manifest first, as for live servers, then version.txt. */
    static String[] jboss(AssessmentScan scan, File home) {
        String slot = "main";
        File conf = new File(home, "bin/product.conf");
        String text = conf.isFile() && conf.length() <= 4096 ? scan.read(conf, 4096) : null;
        if (text != null) for (String line : text.split("\n")) if (line.trim().startsWith("slot=") && line.trim().substring(5).matches("[A-Za-z0-9._-]{1,64}")) slot = line.trim().substring(5);
        File manifest = new File(home, "modules/system/layers/base/org/jboss/as/product/" + slot + "/dir/META-INF/MANIFEST.MF");
        text = manifest.isFile() && manifest.length() <= 65536 ? scan.read(manifest, 65536) : null;
        try {
            String[] release = text == null ? null : AssessmentScan.productRelease(text);
            if (release != null) return new String[]{release[0], release[1], "product module manifest"};
        } catch (IOException e) { scan.note("Product release manifest could not be parsed: " + manifest.getPath()); }
        File versionFile = new File(home, "version.txt");
        if (versionFile.isFile() && versionFile.length() <= 4096) {
            String[] product = versionText(scan.read(versionFile, 4096));
            if (product != null) return new String[]{product[0], product[1], "version.txt"};
        }
        return new String[]{"JBoss", null, null};
    }
    /** {type, version} from "Red Hat JBoss Enterprise Application Platform - Version 7.4.0.GA", or null. */
    static String[] versionText(String text) {
        if (text == null) return null;
        Matcher matcher = VERSION_TEXT.matcher(text);
        if (!matcher.find()) return null;
        String product = matcher.group(1).toLowerCase(Locale.ENGLISH);
        if (product.indexOf("wildfly") < 0 && product.indexOf("jboss") < 0) return null;
        return new String[]{product.indexOf("wildfly") >= 0 ? "WildFly" : "JBoss", matcher.group(2)};
    }
}
