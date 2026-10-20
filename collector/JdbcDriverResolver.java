package assessment;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Passive driver-class lookup. Never loads classes, executes scripts or connects to a database. */
final class JdbcDriverResolver {
    final AssessmentScan scan;
    final Map<String,Archive> cache = new LinkedHashMap<String,Archive>();
    static final int MAX_PATHS = 512, MAX_CANDIDATES = 32;
    int archivesRead;
    static final class Archive {
        final Set<String> classes = new HashSet<String>(), checked = new HashSet<String>();
        final Set<String> versions = new LinkedHashSet<String>();
        String classpath, derbyVersion, derbyEncodedPrefix; boolean complete = true;
    }
    static final class Location {
        File file; int depth; String source;
        Location(File f, int d, String s) { file=f; depth=d; source=s; }
    }
    JdbcDriverResolver(AssessmentScan scan) { this.scan=scan; }
    static String version(String value) { return value!=null && value.matches("[0-9][A-Za-z0-9._+ ()\\-]{0,127}") ? value : null; }
    static void addVersion(Archive a, String value) { String v=version(value); if(v!=null) a.versions.add(v); }
    static boolean linked(Map row,long pid) {
        Object entries=row.get("associations");
        if(entries instanceof List) for(Object raw:(List)entries) if(raw instanceof Map && Long.valueOf(pid).equals(((Map)raw).get("pid"))) return true;
        return false;
    }
    void discover(AssessmentScan.ProcessContext context,File proc) {
        if(!"WebLogic".equals(context.launch.serverType)) return;
        List<Map> rows=new ArrayList<Map>(); Set<String> wanted=new LinkedHashSet<String>();
        for(Object raw:scan.datasources) {
            Map row=(Map)raw; Object driver=row.get("driverName");
            if(linked(row,context.pid) && driver instanceof String && SpringDataSource.validDriver((String)driver)!=null) { rows.add(row); wanted.add(((String)driver).replace('.','/')+".class"); }
        }
        if(rows.isEmpty()) return;
        boolean complete=Boolean.TRUE.equals(scan.settings.get("inspectArchives")) && !context.launch.opaqueArguments;
        List<Location> queue=new ArrayList<Location>();
        List<String> cp=new ArrayList<String>(context.launch.classpath);
        if(cp.isEmpty() && !context.launch.explicitClasspath) {
            String env=scan.metadataText(new File(proc,"environ"),65536,false);
            if(env!=null) for(String entry:env.split("\u0000")) if(entry.startsWith("CLASSPATH=")) ProcessLaunch.paths(cp,entry.substring(10),context.launch.cwd);
            // An unreadable process environment cannot establish a complete classpath.
            if(env==null) complete=false;
        }
        if(cp.size()>=64) complete=false;
        for(String path:cp) queue.add(new Location(new File(path),0,"Classpath do processo"));
        if(context.launch.serverHome!=null) queue.add(new Location(new File(context.launch.serverHome,"lib/weblogic.jar"),0,"Manifesto WebLogic"));
        Set<String> domains=new LinkedHashSet<String>();
        for(Map row:rows) if(row.get("domainPath") instanceof String) domains.add((String)row.get("domainPath"));
        for(String domain:domains) {
            File lib=new File(domain,"lib");
            if(lib.exists()) { if(!directory(lib,queue,0,"lib do domínio")) complete=false; }
        }
        Map<String,List<Map>> found=new LinkedHashMap<String,List<Map>>();
        for(String driver:wanted) found.put(driver,new ArrayList<Map>());
        Set<String> seen=new HashSet<String>();
        for(int i=0;i<queue.size();i++) {
            if(!Boolean.TRUE.equals(scan.settings.get("inspectArchives")) || !scan.available() || seen.size()>=MAX_PATHS) { complete=false; break; }
            Location location=queue.get(i); File f=scan.linkedPath(location.file);
            if(f==null) { complete=false; continue; }
            if(f.getName().equals("*")) { if(!directory(f.getParentFile(),queue,location.depth,location.source)) complete=false; continue; }
            if(!seen.add(f.getPath())) continue;
            if(!f.exists()) continue;
            if(!scan.visitLinked(f) || !f.canRead()) { complete=false; continue; }
            if(f.isDirectory()) {
                for(String driver:wanted) {
                    File cls=scan.linkedPath(new File(f,driver));
                    if(cls!=null && cls.isFile()) found.get(driver).add(AssessmentScan.map("path",f.getPath(),"version",null,"source",location.source,"evidence","Classe em diretório; versão indisponível"));
                }
                continue;
            }
            if(!f.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar")) { complete=false; continue; }
            Archive a=inspect(f,wanted); if(!a.complete) complete=false;
            for(String driver:wanted) if(a.classes.contains(driver)) {
                List<Map> candidates=found.get(driver);
                if(candidates.size()>=MAX_CANDIDATES) { complete=false; continue; }
                candidates.add(AssessmentScan.map("path",f.getPath(),"version",a.versions.size()==1?a.versions.iterator().next():null,
                    "source",location.source,"evidence",a.versions.size()>1?"Metadados de versão conflitantes":"Classe exata e metadados internos do JAR",
                    "metadataVersions",new ArrayList<String>(a.versions)));
            }
            if(a.classpath!=null) for(String entry:a.classpath.trim().split("\\s+")) {

                try {
                    URI relative=new URI(entry);
                    if(relative.isAbsolute() || relative.getRawAuthority()!=null || relative.getQuery()!=null || relative.getFragment()!=null) { complete=false; continue; }
                    File target=new File(f.toURI().resolve(relative));
                    File canonical=scan.linkedPath(target);
                    boolean scheduled=canonical!=null && seen.contains(canonical.getPath());
                    if(!scheduled && canonical!=null) for(Location queued:queue) if(canonical.equals(scan.linkedPath(queued.file))) { scheduled=true; break; }
                    if(scheduled) continue;
                    if(location.depth>=8 || queue.size()>=512) { scan.note("JDBC manifest reference depth/count limit: "+f.getPath()); complete=false; continue; }
                    queue.add(new Location(target,location.depth+1,"Class-Path do manifesto"));
                } catch(Exception e) { complete=false; }
            }
        }
        for(Map row:rows) {
            String driver=((String)row.get("driverName")).replace('.','/')+".class";
            List<Map> candidates=row.get("driverCandidates") instanceof List?(List<Map>)row.get("driverCandidates"):new ArrayList<Map>();
            for(Map candidate:found.get(driver)) {
                boolean duplicate=false; for(Map prior:candidates) if(prior.get("path").equals(candidate.get("path"))) duplicate=true;
                if(!duplicate) { if(candidates.size()<MAX_CANDIDATES) candidates.add(candidate); else complete=false; }
            }
            if("INCOMPLETE".equals(row.get("driverResolution"))) complete=false;
            row.put("driverCandidates",candidates); row.remove("driverVersion"); row.remove("driverArtifact");
            String status=!complete?"INCOMPLETE":candidates.size()>1?"AMBIGUOUS":candidates.isEmpty()?"NOT_FOUND":candidates.get(0).get("version")==null?"VERSION_UNAVAILABLE":"IDENTIFIED";
            row.put("driverResolution",status);
            row.put("driverVersionEvidence","IDENTIFIED".equals(status)?"Classe exata em JAR vinculado ao processo; carregamento na JVM não confirmado":
                "AMBIGUOUS".equals(status)?"Múltiplos JARs candidatos; driver em uso não confirmado":
                "INCOMPLETE".equals(status)?"Inspeção incompleta; consulte candidatos e observações":"Versão do driver não identificada nos arquivos examinados");
            if("IDENTIFIED".equals(status)) { row.put("driverVersion",candidates.get(0).get("version")); row.put("driverArtifact",candidates.get(0).get("path")); }
        }
        if(!complete) scan.warn("WebLogic JDBC driver resolution incomplete (permissions, exclusions, references or inspection budget)");
    }
    boolean directory(File source,List<Location> queue,int depth,String reason) {
        File dir=scan.linkedPath(source);
        if(dir==null || !dir.isDirectory() || !dir.canRead() || !scan.visitLinked(dir)) return false;
        File[] files=scan.serverDataSources.children(dir); boolean complete=files.length<512;
        for(File file:files) if(file.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar")) {
            if(queue.size()>=256) return false;
            queue.add(new Location(file,depth,reason));
        }
        return complete;
    }
    Archive inspect(File file,Set<String> wanted) {
        Archive old=cache.get(file.getPath());
        if(old!=null && old.checked.containsAll(wanted)) return old;
        Archive a=new Archive(); a.checked.addAll(wanted);
        ArchiveMetadata reader=scan.archiveMetadata;
        try {
            if(!scan.available() || archivesRead>=256 || scan.bytesRead>=ArchiveMetadata.READ_LIMIT || file.length()>ArchiveMetadata.FILE_LIMIT) throw new IOException();
            archivesRead++;
            // The large legacy weblogic.jar needs its specialized bounded manifest reader.
            if(file.getName().equals("weblogic.jar")) {
                byte[] bytes=WebLogicManifest.read(reader,file);
                if(bytes!=null) manifest(a,bytes);
                // Do not infer an embedded driver version from the WebLogic product version.
                a.versions.clear();
            } else {
                reader.preflight(file);
                ZipFile zip=new ZipFile(file);
                try {
                    Set<String> names=new HashSet<String>(); List<ZipEntry> metadata=new ArrayList<ZipEntry>();
                    Enumeration<? extends ZipEntry> entries=zip.entries(); int count=0;
                    while(entries.hasMoreElements()) {
                        if(!scan.available() || ++count>ArchiveMetadata.ENTRY_LIMIT) throw new IOException();
                        ZipEntry entry=entries.nextElement(); String name=entry.getName();
                        if(wanted.contains(name)) { if(!names.add(name)) throw new IOException(); a.classes.add(name); }
                        if(name.equals("META-INF/MANIFEST.MF") || name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties") || name.equals("org/apache/derby/info/DBMS.properties")) {
                            if(!names.add(name) || metadata.size()>=32) throw new IOException(); metadata.add(entry);
                        }
                    }
                    for(ZipEntry entry:metadata) {
                        if(a.classes.isEmpty() && !entry.getName().equals("META-INF/MANIFEST.MF")) continue;
                        boolean isManifest=entry.getName().equals("META-INF/MANIFEST.MF");
                        if(entry.getSize()<0 || !isManifest && entry.getSize()>ArchiveMetadata.METADATA_LIMIT) throw new IOException();
                        InputStream input=zip.getInputStream(entry); byte[] bytes;
                        try { bytes=isManifest?manifestHead(reader,input):reader.read(input,ArchiveMetadata.METADATA_LIMIT); } finally { input.close(); }
                        if(entry.getName().equals("META-INF/MANIFEST.MF")) manifest(a,bytes);
                        else if(!a.classes.isEmpty()) {
                            Properties p=new Properties(); p.load(new ByteArrayInputStream(bytes));
                            if(entry.getName().endsWith("/pom.properties")) {
                                if(ArchiveMetadata.jdbcArtifact(p.getProperty("groupId"),p.getProperty("artifactId"))) addVersion(a,p.getProperty("version"));
                            } else { String major=p.getProperty("derby.version.major"),minor=p.getProperty("derby.version.minor"),maint=p.getProperty("derby.version.maint");
                                if(major!=null && minor!=null && maint!=null) try { int m=Integer.parseInt(maint); a.derbyVersion=major+"."+minor+"."+(m/1000000)+"."+(m%1000000); a.derbyEncodedPrefix=major+"."+minor+"."+maint+"."; } catch(NumberFormatException ignored) { }
                            }
                        }
                    }
                if(a.derbyVersion!=null) {
                        Iterator<String> versions=a.versions.iterator(); while(versions.hasNext()) { String v=versions.next(); if(v.startsWith(a.derbyEncodedPrefix) && v.substring(a.derbyEncodedPrefix.length()).matches("[0-9]+")) versions.remove(); }
                        a.versions.add(a.derbyVersion);
                    }
                } finally { zip.close(); }
            }
        } catch(Exception e) { a.complete=false; scan.note("JDBC archive inspection unavailable or limited: "+file.getPath()); }
        if(cache.size()<256) cache.put(file.getPath(),a);
        return a;
    }
    static byte[] manifestHead(ArchiveMetadata reader,InputStream input) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); boolean empty=true;
        for(int i=0;i<ArchiveMetadata.METADATA_LIMIT;i++) {
            reader.ensureBudget(); int b=input.read(); if(b<0) return out.toByteArray(); reader.charge(1);out.write(b);
            if(b=='\n') { if(empty) return out.toByteArray(); empty=true; } else if(b!='\r') empty=false;
        }
        throw new IOException("Manifest header limit");
    }
    static void manifest(Archive a,byte[] bytes) throws IOException {
        Attributes attrs=new Manifest(new ByteArrayInputStream(bytes)).getMainAttributes();
        a.classpath=attrs.getValue("Class-Path");
        String implementation=attrs.getValue("Implementation-Version");
        addVersion(a,implementation!=null?implementation:attrs.getValue("Bundle-Version"));
    }
}
