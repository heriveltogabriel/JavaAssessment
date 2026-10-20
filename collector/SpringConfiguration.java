package assessment;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Passive, bounded local configuration candidates. Never executes Spring or application code. */
final class SpringConfiguration {
    static final String[] CONTROLS = {"spring.application.name", "spring.profiles.active", "spring.profiles.default",
        "spring.profiles.include", "spring.config.name", "spring.config.location", "spring.config.additional-location",
        "spring.config.import", "spring.config.activate.on-profile", "spring.config.activate.on-cloud-platform", "spring.profiles"};
    static String normalized(String s) { return SpringDataSource.key(s); }
    static boolean relevant(String s) {
        String k = normalized(s);
        if (JdbcEndpoint.Resolver.secret(k)) return false;
        for (String c : CONTROLS) if (k.equals(normalized(c))) return true;
        if (Arrays.asList("spring.jpa.database", "spring.jpa.databaseplatform", "spring.jpa.properties.hibernate.dialect", "hibernate.dialect").contains(k)) return true;
        if (k.startsWith("spring.profiles.group.")) return true;
        if (!(k.startsWith("spring.datasource.") || k.indexOf("datasource.") >= 0)) return false;
        for (String suffix : new String[]{"url", "jdbcurl", "driverclassname", "maximumpoolsize", "name", "poolname", "jndiname", "type"})
            if (k.endsWith("." + suffix)) return true;
        return false;
    }
    static void input(Map<String,String> target, String key, String value) {
        if (target.size() < 128 && key.length() <= 256 && value.length() <= 8192 && relevant(key)) target.put(normalized(key), value);
    }
    final AssessmentScan scan; final AssessmentScan.ProcessContext context;
    final List<String> prefixes = new ArrayList<String>();
    final Map<String,String> prefixLabels = new LinkedHashMap<String,String>();
    final List<File> classpathRoots = new ArrayList<File>();
    final Map<String,String> startup = new LinkedHashMap<String,String>(), values = new LinkedHashMap<String,String>();
    final Map<String,String> origins = new LinkedHashMap<String,String>(), environment = new LinkedHashMap<String,String>();
    final Map<String,String> resourceOrigins = new LinkedHashMap<String,String>();
    final Map<String,Properties> cache = new LinkedHashMap<String,Properties>();
    final List<String> profiles = new ArrayList<String>();
    ArchiveMetadata.ConfigurationArchive packaged;
    File artifact; ZipFile zip; String archivePrefix; boolean partial, artifactIdentityVerified; int files;
    final Set<String> duplicateEntries = new HashSet<String>();
    String rawEnvironment;
    final Set<String> merging = new HashSet<String>(), imports = new HashSet<String>(), importedSources = new HashSet<String>();
    int importDepth;
    JdbcEndpoint.Resolver resolver;
    SpringConfiguration(AssessmentScan scan, AssessmentScan.ProcessContext context) {
        this.scan = scan; this.context = context; prefixes.add("spring.datasource");
        String custom = scan.config.getProperty("spring.datasourcePrefixes", "");
        for (String prefix : custom.split(",")) {
            prefix = prefix.trim();
            if (prefixes.size() < 9 && prefix.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,119}") && !prefixes.contains(normalized(prefix))) { prefixes.add(normalized(prefix)); prefixLabels.put(normalized(prefix), prefix); }
        }
    }
    static final class Capture extends SpringDataSource.AllowedProperties {
        final SpringConfiguration owner;
        Capture(SpringConfiguration owner) { this.owner = owner; }
        public synchronized Object put(Object k, Object v) {
            String rawKey = String.valueOf(k);
            String key = normalized(rawKey);
            int dot = rawKey.lastIndexOf('.');
            if (dot > 0 && owner.prefixLabels.size() < 32) {
                String prefix = rawKey.substring(0, dot);
                if (prefix.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,119}") && normalized(prefix).endsWith("datasource")) owner.prefixLabels.put(normalized(prefix), prefix);
            }
            boolean keep = relevant(key);
            for (String prefix : owner.prefixes) if (key.startsWith(normalized(prefix) + ".")) {
                String suffix = key.substring(normalized(prefix).length() + 1);
                keep |= Arrays.asList("url", "jdbcurl", "driverclassname", "name", "jndiname", "type", "hikari.jdbcurl", "hikari.driverclassname", "hikari.maximumpoolsize", "hikari.poolname").contains(suffix);
            }
            if (keep && size() < 128 && String.valueOf(v).length() <= 8192) {
                // Store only the allowlisted keys, not Properties' superclass arbitrary input map.
                return superPut(key, String.valueOf(v).trim());
            }
            return null;
        }
        Object superPut(String k, String v) { return super.storeAllowed(k, v); }
    }
    static void discover(AssessmentScan scan, AssessmentScan.ProcessContext context, File processDirectory) {
        if (!Boolean.TRUE.equals(scan.settings.get("inspectArchives")) || context.application == null || !scan.available()) return;
        SpringConfiguration resolver = new SpringConfiguration(scan, context);
        try { resolver.run(processDirectory); }
        catch (Exception e) { scan.note("Spring local configuration inspection incomplete; effective configuration was not verified"); }
        finally { if (resolver.zip != null) try { resolver.zip.close(); } catch (IOException ignored) { } resolver.rawEnvironment = null; resolver.cache.clear(); resolver.values.clear(); resolver.environment.clear(); }
    }
    void run(File processDirectory) throws IOException {
        String jar = context.launch.jarPath;
        if (jar != null) {
            artifact = ApplicationConfigFiles.safe(scan, new File(jar));
            if (artifact == null || !artifact.isFile() || !"Spring Boot".equals(context.application.get("applicationType"))) return;
            if (artifact.length() > ArchiveMetadata.FILE_LIMIT) return;
            packaged = scan.archiveMetadata.configuration(artifact);
            if (packaged != null) { archivePrefix = packaged.prefix; partial |= packaged.partial; }
            else {
                scan.archiveMetadata.preflight(artifact); zip = new ZipFile(artifact);
                Set<String> seen = new HashSet<String>(); Enumeration<? extends ZipEntry> entries = zip.entries(); int count = 0;
                while (entries.hasMoreElements() && count++ < ArchiveMetadata.ENTRY_LIMIT) { String name = entries.nextElement().getName(); if (!seen.add(name)) duplicateEntries.add(name); }
                archivePrefix = zip.getEntry("BOOT-INF/classes/") != null || hasEntryPrefix("BOOT-INF/classes/") ? "BOOT-INF/classes/" : "WEB-INF/classes/";
            }
        } else {
            // An exploded Boot launcher has an explicit process classpath, not a guessed working directory.
            for (String path : context.launch.classpath) {
                if (!(ProcessLaunch.springLauncher(context.launch.mainClass) || context.springBootEvidence)) break;
                File dir = ApplicationConfigFiles.safe(scan, new File(path));
                if (dir == null) { partial = true; classpathRoots.add(new File(path)); break; }
                if (classpathRoots.size() >= 16) { partial = true; break; }
                File boot = new File(dir, "BOOT-INF/classes");
                if (ProcessLaunch.springLauncher(context.launch.mainClass) && boot.isDirectory()) dir = boot;
                if (artifact == null && (dir.isDirectory() || dir.isFile())) artifact = dir;
                if (!classpathRoots.contains(dir)) classpathRoots.add(dir);
            }
            if (artifact == null) return;
            selectClasspathArtifact();
            context.application.put("applicationType", "Spring Boot"); context.application.put("type", "Spring Boot");
            context.application.put("path", artifact.getPath());
            readExplodedIdentity();
            context.process.put("applicationType", "Spring Boot");
            if (context.springBootVersion != null) context.application.put("frameworkVersion", context.springBootVersion);
            context.application.put("evidence", "Live Java process with Spring Boot launcher or verified Spring Boot library on its classpath; framework activation not verified");
        }
        rawEnvironment = scan.metadataText(new File(processDirectory, "environ"), 65536, false);
        // Spring order for placeholder keys: loaded configuration and startup values, -D properties, then the environment.
        resolver = new JdbcEndpoint.Resolver(scan, processDirectory) {
            String environment(String key) { return envValue(key); }
            String property(String key) {
                if (secret(key)) return null;
                String found = null; String[] argv = lines("cmdline", arguments); arguments = argv;
                for (int i = 1; i < argv.length; i++) {
                    String arg = argv[i];
                    if (arg.equals("-jar")) continue;
                    if (arg.equals("-cp") || arg.equals("-classpath") || arg.equals("--class-path") || ProcessLaunch.takesValue(arg)) { i++; continue; }
                    if (!arg.startsWith("-")) break;
                    if (arg.startsWith("-D" + key + "=")) found = arg.substring(key.length() + 3);
                }
                return found;
            }
            String lookup(String key, List<String> sources) {
                String found = values.get(normalized(key));
                if (found != null) { source(sources, "configuration"); return found; }
                found = property(key);
                if (found != null) { source(sources, "system-property"); return found; }
                found = envValue(key);
                if (found == null) found = envValue(key.toUpperCase(Locale.ENGLISH).replace("-", "").replace('.', '_'));
                if (found != null) source(sources, "environment");
                return found;
            }
        };
        for (String key : controlAndDatasourceKeys()) {
            String env = key.toUpperCase(Locale.ENGLISH).replace("-", "").replace('.', '_');
            String value = envValue(env); if (value != null && value.length() <= 8192) startup.put(normalized(key), value);
        }
        startup.putAll(context.launch.springSystem); startup.putAll(context.launch.springArguments);
        List<String> locations = locations();
        String basename = get(startup, "spring.config.name"); if (basename == null) basename = "application";
        List<String> names = tokens(basename, false); if ("application".equals(basename)) names.add(0, "bootstrap"); if (names.isEmpty()) { partial = true; return; }
        load(locations, names, Collections.<String>emptyList());
        String configuredName = ProcessLaunch.label(SpringDataSource.first(get(startup, "spring.application.name"), get(values, "spring.application.name")));
        if (configuredName != null) {
            context.application.put("name", configuredName);
            for (String loc : scan.applicationConfigFiles.locations(context)) if (!locations.contains(loc)) locations.add(loc);
            load(locations, names, Collections.<String>emptyList());
        }
        Map<String,String> controls = new LinkedHashMap<String,String>(values); controls.putAll(startup);
        String active = get(controls, "spring.profiles.active");
        if (active == null) active = get(controls, "spring.profiles.default");
        if (active == null) active = "default";
        profiles.addAll(tokens(active, true));
        if (get(controls, "spring.profiles.include") != null) partial = true;
        for (String key : controls.keySet()) if (key.startsWith("spring.profiles.group.")) partial = true;
        values.clear(); origins.clear();
        load(locations, names, profiles);
        values.putAll(startup);
        for (String key : startup.keySet()) origins.put(key, "process startup configuration");
        followImports(get(startup, "spring.config.import"), null);
        values.putAll(startup);
        String appName = ProcessLaunch.label(setting("spring.application", "name"));
        if (appName != null) context.application.put("name", appName);
        context.application.put("activeProfiles", new ArrayList<String>(profiles));
        context.application.put("configurationResolution", partial ? "partial" : "local-candidate");
        context.application.put("configurationEvidence", "Local configuration candidates; programmatic overrides, remote imports and actual datasource use are not verified");
        autoPrefixes();
        for (String prefix : prefixes) add(prefix);
        context.application.put("configurationResolution", partial ? "partial" : "local-candidate");
        if (partial) scan.note("Spring configuration includes unresolved activation, imports or unsupported local sources; effective configuration was not verified");
    }
    static void machine(AssessmentScan scan, File file) {
        Map app = AssessmentScan.map("name", "Configuração da máquina");
        AssessmentScan.ProcessContext context = new AssessmentScan.ProcessContext(new ProcessLaunch(), 0, null, new LinkedHashMap(), app);
        SpringConfiguration config = new SpringConfiguration(scan, context); config.artifact = file;
        config.resolver = new JdbcEndpoint.Resolver(scan, null);
        config.merge(file.getPath());
        int before = scan.datasources.size();
        config.autoPrefixes();
        for (String prefix : config.prefixes) config.add(prefix);
        for (int i = before; i < scan.datasources.size(); i++) {
            Map row = (Map)scan.datasources.get(i);
            for (String key : new String[]{"pid", "applicationId", "applicationName", "associations"}) row.remove(key);
            row.put("configurationBinding", "machine-unbound"); row.put("scanScope", "app-config-dir");
            row.put("evidence", "Configuração da máquina não vinculada a uma aplicação");
        }
        config.values.clear(); config.cache.clear();
    }
    boolean hasEntryPrefix(String prefix) {
        Enumeration<? extends ZipEntry> entries = zip.entries(); int n = 0;
        while (entries.hasMoreElements() && n++ < ArchiveMetadata.ENTRY_LIMIT) if (entries.nextElement().getName().startsWith(prefix)) return true;
        return false;
    }
    List<String> controlAndDatasourceKeys() {
        List<String> keys = new ArrayList<String>(Arrays.asList(CONTROLS));
        for (String prefix : prefixes) for (String suffix : new String[]{"url", "jdbc-url", "driver-class-name", "name", "jndi-name", "type", "hikari.jdbc-url", "hikari.driver-class-name", "hikari.maximum-pool-size", "hikari.pool-name"}) keys.add(prefix + "." + suffix);
        return keys;
    }
    String envValue(String key) {
        if (rawEnvironment == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || key.toLowerCase(Locale.ENGLISH).matches(".*(password|passwd|secret|token|credential).*")) return null;
        for (String pair : rawEnvironment.split("\u0000")) if (pair.startsWith(key + "=")) return pair.substring(key.length() + 1);
        return null;
    }
    List<String> tokens(String value, boolean profile) {
        List<String> result = new ArrayList<String>();
        if (value == null) return result;
        for (String item : value.split(",", -1)) {
            item = item.trim();
            if (!item.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}") || item.contains("..") || result.size() >= 8) { partial = true; continue; }
            if (!result.contains(item)) result.add(item);
        }
        return result;
    }
    List<String> locations() {
        List<String> result = new ArrayList<String>();
        String explicit = get(startup, "spring.config.location");
        if (explicit == null) {
            result.add("classpath:"); result.add("classpath:config/");
            if (context.launch.cwd != null) { result.add(context.launch.cwd.getPath() + "/"); result.add(new File(context.launch.cwd, "config").getPath() + "/"); }
        } else appendLocations(result, explicit);
        result.addAll(scan.applicationConfigFiles.locations(context));
        appendLocations(result, get(startup, "spring.config.additional-location"));
        return result;
    }
    void appendLocations(List<String> out, String value) {
        if (value == null) return;
        for (String raw : value.split(",")) {
            String loc = raw.trim();
            if (loc.startsWith("optional:")) loc = loc.substring(9);
            if (loc.startsWith("file:")) loc = loc.substring(5);
            if (loc.startsWith("classpath:/")) loc = "classpath:" + loc.substring(11);
            if (loc.contains("*") || loc.contains(";") || loc.contains("${") || loc.contains("..") || out.size() >= 12) { partial = true; continue; }
            if (!loc.startsWith("classpath:")) {
                String path = ProcessLaunch.path(loc, context.launch.cwd);
                if (path == null) { partial = true; continue; }
                loc = path + (loc.endsWith("/") && !path.endsWith("/") ? "/" : "");
            }
            out.add(loc);
        }
    }
    void load(List<String> locations, List<String> names, List<String> active) {
        if (get(startup, "spring.config.location") != null) {
            for (String loc : locations) { loadLocation(loc, names, null); for (String profile : active) loadLocation(loc, names, profile); }
            return;
        }
        // Packaged locations precede external locations; profile files override bases in each group.
        for (int group = 0; group < 2; group++) {
            for (String loc : locations) if (loc.startsWith("classpath:") == (group == 0)) loadLocation(loc, names, null);
            for (String profile : active) for (String loc : locations) if (loc.startsWith("classpath:") == (group == 0)) loadLocation(loc, names, profile);
        }
    }
    void loadLocation(String loc, List<String> names, String profile) {
        boolean directory = loc.endsWith("/") || loc.equals("classpath:");
        if (!directory) {
            int dot = loc.lastIndexOf('.');
            merge(profile == null ? loc : dot >= 0 ? loc.substring(0, dot) + "-" + profile + loc.substring(dot) : loc);
            return;
        }
        for (String name : names) {
            String base = loc + name + (profile == null ? "" : "-" + profile);
            // Merge per property: a properties file does not erase independent YAML keys.
            merge(base + ".yml"); merge(base + ".yaml"); merge(base + ".properties");
        }
    }
    boolean merge(String location) {
        if (!merging.add(location)) { incomplete("Ciclo de imports locais do Spring detectado"); return false; }
        try {
            Properties properties = readConfig(location);
            if (properties == null) return false;
            if (properties.containsKey(normalized("spring.config.activate.on-profile")) || properties.containsKey(normalized("spring.config.activate.on-cloud-platform")) || properties.containsKey("spring.profiles")) { partial = true; return true; }
            for (Object raw : properties.keySet()) { String k = String.valueOf(raw); values.put(k, properties.getProperty(k)); origins.put(k, source(location)); }
            followImports(properties.getProperty(normalized("spring.config.import")), location);
            return true;
        } finally { merging.remove(location); }
    }
    void incomplete(String message) { partial = true; scan.note(message); }
    void followImports(String value, String from) {
        if (value == null) return;
        if (importDepth >= 3) { incomplete("Limite de profundidade dos imports Spring atingido"); return; }
        for (String item : value.split(",")) {
            String target = item.trim(); boolean optional = target.startsWith("optional:");
            if (optional) target = target.substring(9);
            if (target.length() == 0) continue;
            target = resolve(target, new ArrayList<String>());
            if (target == null) { incomplete("Import Spring com caminho não resolvido"); continue; }
            boolean local = target.startsWith("file:"); if (local) target = target.substring(5);
            if (target.startsWith("classpath:")) {
                if (zip == null && packaged == null) { incomplete("Import classpath fora do arquivo inspecionado não foi seguido"); continue; }
                target = "classpath:" + target.substring(10).replaceFirst("^/", "");
            } else if (target.indexOf(':') >= 0 || target.indexOf('*') >= 0) {
                incomplete("Import Spring remoto ou protocolo não suportado não foi seguido"); continue;
            } else if (!local && !new File(target).isAbsolute() && from != null && from.startsWith("classpath:")) {
                String origin = from.substring(10); int slash = origin.lastIndexOf('/');
                target = "classpath:" + (slash < 0 ? "" : origin.substring(0, slash + 1)) + target;
            } else {
                File base = from != null && !from.startsWith("classpath:") ? new File(from).getParentFile() : context.launch.cwd;
                String resolved = ProcessLaunch.path(target, base);
                File safe = resolved == null ? null : ApplicationConfigFiles.safe(scan, new File(resolved));
                if (safe == null) { incomplete("Import Spring excluído, inválido ou com link simbólico"); continue; }
                target = safe.getPath();
            }
            if (!imports.contains(target) && imports.size() >= 20) { incomplete("Limite de 20 arquivos importados pelo Spring atingido"); continue; }
            if (!target.matches(".*\\.(properties|yml|yaml)")) { incomplete("Formato de import Spring não suportado"); continue; }
            imports.add(target); importedSources.add(source(target));
            importDepth++;
            try { if (!merge(target) && !optional) incomplete("Arquivo de import Spring obrigatório não encontrado"); }
            finally { importDepth--; }
        }
    }
    void autoPrefixes() {
        for (String key : values.keySet()) {
            int end = key.lastIndexOf("datasource."); if (end < 0) continue;
            String suffix = key.substring(end + 11);
            if (!Arrays.asList("url", "jdbcurl", "jndiname", "driverclassname").contains(suffix)) continue;
            String prefix = key.substring(0, end + 10);
            if (prefixes.size() < 32 && !prefixes.contains(prefix)) prefixes.add(prefix);
        }
    }
    static String dialectType(String value) {
        if (value == null) return null;
        String v = value.toLowerCase(Locale.ENGLISH);
        if (v.contains("postgres")) return "PostgreSQL";
        if (v.contains("oracle")) return "Oracle";
        if (v.contains("sqlserver") || v.equals("sql_server")) return "SQL Server";
        if (v.contains("mariadb")) return "MariaDB";
        if (v.contains("mysql")) return "MySQL";
        if (v.contains("db2")) return "DB2";
        return null;
    }
    void dialect(Map fields) {
        String hint = null;
        for (String key : new String[]{"spring.jpa.database", "spring.jpa.database-platform", "spring.jpa.properties.hibernate.dialect", "hibernate.dialect"}) {
            hint = dialectType(get(values, key)); if (hint != null) break;
        }
        if (hint == null) return;
        if (fields.get("databaseType") == null) { fields.put("databaseType", hint); fields.put("databaseTypeEvidence", "Tipo inferido pelo dialeto/JPA; conexão não verificada"); }
        else if (!hint.equals(fields.get("databaseType"))) fields.put("configurationObservation", "O tipo de banco da URL diverge do dialeto/JPA; o tipo da URL foi preservado");
    }
    String source(String loc) {
        if (resourceOrigins.containsKey(loc)) return resourceOrigins.get(loc);
        return loc.startsWith("classpath:") ? artifact.getPath() + (zip == null && packaged == null ? "/" : "!/") + (zip == null && packaged == null ? "" : archivePrefix) + loc.substring(10) : loc;
    }
    byte[] classpathResource(String name) throws IOException {
        if (!ArchiveMetadata.safeName(name)) throw new IOException("Unsafe resource");
        for (File root : classpathRoots) {
            if (!scan.available()) throw new IOException("Deadline");
            File safeRoot = ApplicationConfigFiles.safe(scan, root);
            if (safeRoot == null || root.getName().equals("*")) throw new IOException("Classpath order not resolved");
            if (safeRoot.isDirectory()) {
                File file = ApplicationConfigFiles.safe(scan, new File(safeRoot, name));
                if (file == null) throw new IOException("Excluded resource");
                if (!file.exists()) continue;
                if (!scan.visitLinked(file) || !file.isFile() || file.length() > ArchiveMetadata.METADATA_LIMIT) throw new IOException("Resource limit");
                InputStream input = new FileInputStream(file);
                try { byte[] value = scan.archiveMetadata.read(input, ArchiveMetadata.METADATA_LIMIT); resourceOrigins.put("classpath:" + name, file.getPath()); return value; }
                finally { input.close(); }
            } else if (safeRoot.isFile() && safeRoot.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar")) {
                ArchiveMetadata.ConfigurationArchive jar = scan.archiveMetadata.classpathConfiguration(safeRoot);
                if (jar.duplicates.contains(name)) throw new IOException("Duplicate resource");
                byte[] value = jar.entries.get(name);
                if (value != null) { resourceOrigins.put("classpath:" + name, safeRoot.getPath() + "!/" + name); return value; }
                if (jar.partial) throw new IOException("Classpath configuration incomplete");
            } else if (safeRoot.exists()) throw new IOException("Unsupported classpath entry");
        }
        return null;
    }
    Properties readConfig(String location) {
        if (cache.containsKey(location)) return cache.get(location);
        if (!scan.available() || files >= 64) { partial = true; return null; }
        byte[] bytes = null;
        try {
            if (location.startsWith("classpath:") && !classpathRoots.isEmpty()) {
                bytes = classpathResource(location.substring(10));
                if (bytes == null) { cache.put(location, null); return null; }
                files++;
            } else if (location.startsWith("classpath:") && packaged != null) {
                String entryName = archivePrefix + location.substring(10);
                if (packaged.duplicates.contains(entryName)) throw new IOException("Duplicate entry");
                bytes = packaged.entries.get(entryName);
                if (bytes == null) { cache.put(location, null); return null; }
                files++;
            } else if (location.startsWith("classpath:") && zip != null) {
                String entryName = archivePrefix + location.substring(10);
                if (duplicateEntries.contains(entryName)) throw new IOException("Duplicate entry");
                if (!ArchiveMetadata.safeName(entryName)) { partial = true; return null; }
                ZipEntry entry = zip.getEntry(entryName); if (entry == null) { cache.put(location, null); return null; }
                files++; if (entry.getSize() > ArchiveMetadata.METADATA_LIMIT) throw new IOException("Size");
                InputStream in = zip.getInputStream(entry);
                try { bytes = scan.archiveMetadata.read(in, ArchiveMetadata.METADATA_LIMIT); } finally { in.close(); }
            } else {
                File file = location.startsWith("classpath:") ? new File(artifact, location.substring(10)) : new File(location);
                File safe = ApplicationConfigFiles.safe(scan, file); if (safe == null) { partial = true; return null; }
                if (!safe.exists()) { cache.put(location, null); return null; }
                scan.applicationConfigFiles.claim(safe, context);
                files++; if (!scan.visitLinked(safe) || !safe.isFile() || safe.length() > 1024 * 1024) throw new IOException("Size");
                InputStream in = new FileInputStream(safe);
                try { bytes = scan.archiveMetadata.read(in, 1024 * 1024); } finally { in.close(); }
            }
            Capture p = new Capture(this);
            if (location.endsWith(".properties")) {
                for (String line : new String(bytes, "ISO-8859-1").split("\\r?\\n")) if (line.trim().equals("#---") || line.trim().equals("!---")) throw new IOException("Multiple documents");
                p.load(new ByteArrayInputStream(bytes));
            } else SpringDataSource.parseYaml(bytes, p);
            cache.put(location, p); return p;
        } catch (Exception e) { partial = true; cache.put(location, new Properties()); return cache.get(location); }
    }
    String resolve(String value, List<String> sources) {
        // Placeholders may be exact or embedded in a URL; keys and depth are bounded by the resolver.
        return resolver == null ? (SpringDataSource.resolved(value) ? value : null) : resolver.expand(value, sources);
    }
    static String get(Map<String,String> map, String key) { return map.get(normalized(key)); }
    String setting(String prefix, String suffix) { return setting(prefix, suffix, new ArrayList<String>()); }
    String setting(String prefix, String suffix, List<String> sources) { String value = get(values, prefix + "." + suffix); String resolved = resolve(value, sources); if (value != null && resolved == null) partial = true; return resolved; }
    void add(String prefix) {
        String url = null; List<String> sources = new ArrayList<String>();
        for (String suffix : new String[]{"url", "jdbc-url", "hikari.jdbc-url"}) {
            List<String> candidate = new ArrayList<String>(); String value = setting(prefix, suffix, candidate);
            if (url == null && value != null && value.length() > 0) { url = value; sources = candidate; }
        }
        String driver = SpringDataSource.validDriver(SpringDataSource.first(setting(prefix, "driver-class-name"), setting(prefix, "hikari.driver-class-name")));
        String jndi = setting(prefix, "jndi-name");
        if (jndi != null && !jndi.matches("[A-Za-z0-9_:/.-]{1,200}")) jndi = null;
        String db = url == null ? null : JdbcEndpoint.databaseType(url);
        boolean jdbcUrl = url != null && url.length() <= 8192 && url.matches("jdbc:[A-Za-z][A-Za-z0-9_-]*:.*");
        if (!jdbcUrl && driver == null && jndi == null) return;
        Map fields = new LinkedHashMap(); fields.put("configurationPresent", Boolean.TRUE);
        fields.put("configurationKey", prefix); fields.put("datasourceKey", prefix);
        JdbcEndpoint.addTo(fields, url, resolver, sources);
        if (db != null) fields.put("databaseType", db); if (driver != null) fields.put("driverName", driver);
        if (jndi != null) fields.put("jndiName", jndi);
        dialect(fields);
        String name = ProcessLaunch.label(setting(prefix, "name"));
        if (name == null && !normalized(prefix).equals("spring.datasource")) {
            name = prefixLabels.containsKey(normalized(prefix)) ? prefixLabels.get(normalized(prefix)) : prefix;
            name = name.replaceFirst("[.]datasource$", "");
        }
        if (name != null) fields.put("configuredName", name);
        // Pool names are deliberately excluded: existing snapshots promised not to retain arbitrary pool identifiers.
        String max = setting(prefix, "hikari.maximum-pool-size");
        if (max != null && max.matches("[0-9]{1,6}")) { int n = Integer.parseInt(max); if (n > 0 && n <= 100000) fields.put("configuredMaxPoolSize", Integer.valueOf(n)); }
        String origin = null;
        for (String suffix : new String[]{"url", "jdbc-url", "hikari.jdbc-url", "driver-class-name", "hikari.driver-class-name", "jndi-name"}) {
            if (origins.containsKey(normalized(prefix + "." + suffix))) { origin = origins.get(normalized(prefix + "." + suffix)); break; }
        }
        fields.put("configurationSource", origin == null ? artifact.getPath() : origin);
        String method = importedSources.contains(origin) ? "spring-import" : origin != null && scan.applicationConfigFiles.claimed.contains(origin) ? "app-config-dir" : "spring-properties";
        fields.put("discoveryMethod", method);
        List methods = fields.get("resolvedFrom") instanceof List ? (List)fields.get("resolvedFrom") : new ArrayList();
        if (!methods.contains(method)) methods.add(method); fields.put("resolvedFrom", methods);
        fields.put("configurationResolution", partial ? "partial" : "local-candidate");
        fields.put("activeProfiles", new ArrayList<String>(profiles));
        List candidates = new ArrayList();
        for (Object raw : scan.libraries) {
            Map lib = (Map)raw; Object associations = lib.get("associations");
            boolean belongs = context.application.get("id") != null && context.application.get("id").equals(lib.get("applicationId"));
            if (associations instanceof List) for (Object a : (List)associations) if (context.application.get("id") != null && context.application.get("id").equals(((Map)a).get("applicationId"))) belongs = true;
            if (belongs) candidates.add(lib);
        }
        SpringDataSource.matchPackagedDriver(fields, candidates);
        scan.addConfiguredSpringDatasource(artifact.getPath(), fields, context.application, context);
        context.application.put("configurationResolution", partial ? "partial" : "local-candidate");
    }
    void selectClasspathArtifact() {
        if (ProcessLaunch.springLauncher(context.launch.mainClass)) { artifactIdentityVerified = true; return; }
        if (context.launch.mainClass == null) return;
        String name = context.launch.mainClass.replace('.', '/') + ".class";
        for (File root : classpathRoots) try {
            File safe = ApplicationConfigFiles.safe(scan, root);
            if (safe == null || root.getName().equals("*")) { partial = true; return; }
            if (safe.isDirectory()) {
                File main = ApplicationConfigFiles.safe(scan, new File(safe, name));
                if (main == null) { partial = true; return; }
                if (main.isFile()) { artifact = safe; artifactIdentityVerified = true; return; }
            } else if (safe.isFile() && safe.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar")) {
                ArchiveMetadata.ConfigurationArchive jar = scan.archiveMetadata.classpathConfiguration(safe);
                if (jar.classEntries.contains(name)) { artifact = safe; artifactIdentityVerified = true; return; }
                if (jar.partial) { partial = true; return; }
            }
        } catch (IOException e) { partial = true; return; }
    }
    void readExplodedIdentity() {
        for (String name : new String[]{"META-INF/build-info.properties", "META-INF/MANIFEST.MF"}) {
            try {
                // Application identity uses its own artifact; dependency manifests are not application versions.
                byte[] identity;
                if (name.equals("META-INF/build-info.properties")) {
                    identity = classpathResource(name);
                } else if (!artifactIdentityVerified) continue;
                else if (artifact.isFile()) {
                    ArchiveMetadata.ConfigurationArchive jar = scan.archiveMetadata.classpathConfiguration(artifact);
                    if (jar.duplicates.contains(name)) { partial = true; continue; }
                    identity = jar.entries.get(name);
                } else {
                    File file = ApplicationConfigFiles.safe(scan, new File(artifact, name));
                    if (file == null || !file.isFile()) continue;
                    if (!scan.visitLinked(file)) { partial = true; continue; }
                    String text = scan.metadataText(file, 65536, false);
                    identity = text == null ? null : text.getBytes("UTF-8");
                }
                if (identity == null) continue;
                String text = new String(identity, "UTF-8");
                String title = null, version = null, boot = null;
                if (name.endsWith(".MF")) {
                    java.util.jar.Attributes attrs = new java.util.jar.Manifest(new ByteArrayInputStream(text.getBytes("UTF-8"))).getMainAttributes();
                    title = attrs.getValue("Implementation-Title"); version = attrs.getValue("Implementation-Version"); boot = attrs.getValue("Spring-Boot-Version");
                } else { Properties p = new Properties(); p.load(new ByteArrayInputStream(text.getBytes("UTF-8"))); title = p.getProperty("build.name"); version = p.getProperty("build.version"); }
                if (ProcessLaunch.label(title) != null) context.application.put("name", title);
                if (version != null && version.matches("[0-9][A-Za-z0-9._+\\-]{0,127}")) context.application.put("version", version);
                if (boot != null && boot.matches("[0-9][A-Za-z0-9._+\\-]{0,127}")) context.application.put("frameworkVersion", boot);
            } catch (Exception e) { partial = true; }
        }
    }
}
