package assessment;

import java.io.*;
import java.util.*;

/** Bounded external configuration candidates; never follow filesystem links. */
final class ApplicationConfigFiles {
    final AssessmentScan scan;
    final List<File> files = new ArrayList<File>();
    final Set<String> seen = new HashSet<String>(), claimed = new HashSet<String>();
    final Map<String,AssessmentScan.ProcessContext> directOwners = new HashMap<String,AssessmentScan.ProcessContext>();
    final Set<String> shared = new HashSet<String>();
    boolean loaded;
    ApplicationConfigFiles(AssessmentScan scan) { this.scan = scan; }
    static File safe(AssessmentScan scan, File file) {
        try {
            File normalized = new File(file.getAbsoluteFile().toURI().normalize());
            File canonical = scan.linkedPath(normalized);
            return canonical != null && canonical.equals(normalized) ? canonical : null;
        } catch (Exception e) { return null; }
    }
    List<String> roots() {
        Object paths = scan.settings.get("appConfigPaths");
        if (!(paths instanceof List)) return Arrays.asList("/etc/config");
        List<String> result = new ArrayList<String>();
        for (Object value : (List) paths) if (value instanceof String && result.size() < 20) {
            String path = (String)value;
            if (path.startsWith("/") && !path.equals("/") && path.length() <= 512 && path.indexOf("..") < 0) result.add(path);
        }
        return result;
    }
    static boolean textFile(String name) { return name.toLowerCase(Locale.ENGLISH).matches(".*\\.(properties|yml|yaml|xml|conf|cfg|env|ini|json)"); }
    void load() { if (loaded) return; loaded = true; for (String root : roots()) walk(new File(root), 0); }
    void walk(File directory, int depth) {
        File dir = safe(scan, directory);
        if (dir == null || !dir.isDirectory() || depth > 3 || !scan.available() || seen.size() >= 512 || !seen.add(dir.getPath())) return;
        BoundedDirectory entries = null;
        try {
            entries = BoundedDirectory.open(dir, scan.deadline);
            for (File next; (next = entries.next()) != null && scan.available();) {
                if (seen.size() >= 512 || files.size() >= 128) { scan.warn("Limite de arquivos de configuração das aplicações atingido"); break; }
                File file = safe(scan, next); if (file == null || !scan.visitLinked(file)) continue;
                if (file.isDirectory()) { if (!AssessmentScan.IGNORED_DIRECTORIES.contains(file.getName())) walk(file, depth + 1); }
                else if (seen.add(file.getPath()) && file.isFile() && textFile(file.getName()) && file.length() <= 512 * 1024) files.add(file);
            }
        } catch (IOException e) { scan.note("Não foi possível examinar uma pasta de configuração das aplicações"); }
        finally { if (entries != null) entries.close(); }
        Collections.sort(files);
    }
    boolean boot(AssessmentScan.ProcessContext c) { return c.application != null && "Spring Boot".equals(c.application.get("applicationType")); }
    /** Lower case, with _ - . and spaces as one separator; extension and trailing version removed. */
    static String normalize(String value, boolean artifact) {
        if (value == null) return "";
        String v = value.trim().toLowerCase(Locale.ENGLISH);
        if (artifact) v = v.replaceFirst("\\.(jar|war|ear)$", "");
        v = v.replaceAll("[\\s._-]+", "-").replaceAll("^-+|-+$", "");
        if (artifact) v = v.replaceFirst("-v?[0-9]+(-[0-9]+)*(-(snapshot|release|final|[a-z]*[0-9]*))?$", "");
        return v;
    }
    List<String> names(AssessmentScan.ProcessContext context) {
        List<String> names = new ArrayList<String>();
        if (context.launch.jarPath != null) add(names, normalize(new File(context.launch.jarPath).getName(), true));
        if (context.application != null && context.application.get("name") instanceof String) add(names, normalize((String)context.application.get("name"), true));
        String explicit = context.launch.springArguments.get("spring.application.name");
        if (explicit == null) explicit = context.launch.springSystem.get("spring.application.name");
        if (ProcessLaunch.label(explicit) != null) add(names, normalize(explicit, false));
        return names;
    }
    static void add(List<String> names, String name) { if (name.length() >= 3 && !names.contains(name)) names.add(name); }
    /** Token match: <app>.ext, <app>-<suffix>.ext or a folder named <app>; application/bootstrap files only by folder. */
    boolean matches(File file, AssessmentScan.ProcessContext context) {
        String base = normalize(file.getName().replaceFirst("\\.[^.]+$", ""), false);
        String parent = file.getParentFile() == null ? "" : normalize(file.getParentFile().getName(), false);
        boolean generic = base.matches("(application|bootstrap)(-.*)?");
        for (String app : names(context)) {
            if (parent.equals(app)) return true;
            if (!generic && (base.equals(app) || base.startsWith(app + "-"))) return true;
        }
        return false;
    }
    void claim(File file, AssessmentScan.ProcessContext context) {
        claimed.add(file.getPath());
        if (context.pid == 0 || shared.contains(file.getPath())) return;
        AssessmentScan.ProcessContext previous = directOwners.get(file.getPath());
        if (previous != null && previous != context) { directOwners.remove(file.getPath()); shared.add(file.getPath()); }
        else directOwners.put(file.getPath(), context);
    }
    static String canonical(File file) { try { return file.getCanonicalPath(); } catch (IOException e) { return null; } }
    AssessmentScan.ProcessContext owner(File file) {
        if (directOwners.containsKey(file.getPath())) return directOwners.get(file.getPath());
        if (shared.contains(file.getPath())) return null;
        AssessmentScan.ProcessContext named = null, only = null; int matches = 0, boots = 0;
        for (AssessmentScan.ProcessContext c : scan.processContexts) {
            if (boot(c)) { only = c; boots++; }
            if (c.application != null && matches(file, c)) { named = c; matches++; }
        }
        if (matches == 1) return named;
        if (matches > 1) return null;
        return boots == 1 && file.getName().matches("(?i)(application|bootstrap).*\\.(properties|ya?ml)") ? only : null;
    }
    List<String> locations(AssessmentScan.ProcessContext context) {
        load(); List<String> result = new ArrayList<String>();
        for (File file : files) if (owner(file) == context && file.getName().matches("(?i).*\\.(properties|ya?ml)")) {
            String name = file.getName();
            if (name.matches("(?i)(application|bootstrap)([-.].*)")) {
                String dir = file.getParent() + "/"; if (!result.contains(dir)) result.add(dir);
            } else result.add(file.getPath());
            claimed.add(file.getPath());
        }
        return result;
    }
    void unbound() {
        if (!Boolean.TRUE.equals(scan.settings.get("inspectArchives"))) return;
        load();
        for (File file : files) if (!claimed.contains(file.getPath()) && owner(file) == null && file.getName().matches("(?i).*\\.(properties|ya?ml)")) SpringConfiguration.machine(scan, file);
    }
}
