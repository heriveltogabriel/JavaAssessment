package assessment;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Passive classpath precedence, never JVM attach, class loading or a database connection. */
final class JdbcDriverResolver {
    final AssessmentScan scan;
    static final int MAX_PATHS=4096, MAX_CANDIDATES=32;
    final Map<String,Archive> cache=new LinkedHashMap<String,Archive>();
    int archivesRead;
    static final class Archive {
        final Set<String> classes=new HashSet<String>(),checked=new HashSet<String>();
        final Set<String> versions=new LinkedHashSet<String>();
        String classpath,versionSource,reason; boolean complete=true;
    }
    JdbcDriverResolver(AssessmentScan scan){this.scan=scan;}
    static String version(String v){return v!=null&&v.matches("[0-9][A-Za-z0-9._+ ()\\-]{0,127}")?v:null;}
    static void addVersion(Archive a,String v){if(version(v)!=null)a.versions.add(v);}
    static boolean linked(Map row,long pid){if(row.get("associations") instanceof List)for(Object raw:(List)row.get("associations"))if(raw instanceof Map&&Long.valueOf(pid).equals(((Map)raw).get("pid")))return true;return false;}
    static String family(String path){String name=new File(path).getName();java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)^(ojdbc(14|5|6|7|8|10|11|17))(?:[-_.].*)?\\.jar$").matcher(name);return m.matches()?m.group(1).toLowerCase(Locale.ENGLISH)+" (JDK "+(m.group(2).equals("14")?"1.4":m.group(2))+(m.group(2).equals("8")?"+":"")+")":null;}
    interface Strategy { String prepare(AssessmentScan.ProcessContext context,Map row); void resolve(Search search,Map row,List<String> classpath); boolean requiresClasspath(); }
    Strategy strategy(String type) {
        if("WebLogic".equals(type))return new WebLogicDriverStrategy(this);
        if("Tomcat".equals(type))return new Strategy(){public boolean requiresClasspath(){return true;}public String prepare(AssessmentScan.ProcessContext context,Map row){return AssessmentScan.clean(row.get("driverName"));}public void resolve(Search search,Map row,List<String> cp){for(String path:cp)search.walk(new File(path),"classpath",0,null,false);scan.serverDataSources.tomcat.resolve(search,row);}};
        if("JBoss".equals(type))return new Strategy(){public boolean requiresClasspath(){return false;}public String prepare(AssessmentScan.ProcessContext context,Map row){return scan.serverDataSources.jboss.prepare(context,row);}public void resolve(Search search,Map row,List<String> cp){search.followManifest=search.context.launch.moduleName==null;scan.serverDataSources.jboss.resolve(search,row);}};
        return null;
    }
    void discover(AssessmentScan.ProcessContext context,File proc){
        Strategy strategy=strategy(context.launch.serverType);if(strategy==null)return;
        List<Map> rows=new ArrayList<Map>();Set<String>wanted=new LinkedHashSet<String>();
        for(Object raw:scan.datasources){Map row=(Map)raw;if(!linked(row,context.pid))continue;String driver=strategy.prepare(context,row);if(SpringDataSource.validDriver(driver)!=null){row.put("driverName",driver);rows.add(row);wanted.add(driver.replace('.','/')+".class");}else if("JBoss".equals(context.launch.serverType)){rows.add(row);wanted.add("__assessment_unresolved_driver__.class");}}
        if(rows.isEmpty())return;
        OpenFiles open=new OpenFiles(context,proc);
        List<String> cp=new ArrayList<String>(context.launch.classpath);boolean unknown=false, environmentComplete=true;
        if(strategy.requiresClasspath()&&cp.isEmpty()&&!context.launch.explicitClasspath){String env=scan.metadataText(new File(proc,"environ"),ProcessLaunch.PROCESS_TEXT_LIMIT,false);unknown=env==null;if(env!=null)for(String pair:env.split("\u0000"))if(pair.startsWith("CLASSPATH="))environmentComplete=ProcessLaunch.paths(cp,pair.substring(10),context.launch.cwd);}
        for(Map row:rows){
            Search search=new Search(context,wanted,row.get("driverName")==null?"__assessment_unresolved_driver__.class":((String)row.get("driverName")).replace('.','/')+".class",open);
            if(!Boolean.TRUE.equals(scan.settings.get("inspectArchives")))search.fail("Inspeção de arquivos desligada no ambiente");
            else {
                if(context.launch.opaqueArguments&&!context.launch.perfDataCompleted)search.fail("Argumentos opacos: classpath efetivo não confirmado");
                if(context.launch.runtimePathsIncomplete)search.fail(context.launch.pathsReason());
                if(!environmentComplete)search.fail("CLASSPATH do ambiente excedeu "+ProcessLaunch.MAX_PATH_ENTRIES+" entradas ou contém caminho não resolvido");
                if(unknown)search.fail("Ambiente do processo não legível; classpath não confirmado");
                runtimeOrigins(search);
                strategy.resolve(search,row,cp);
            }
            Map decision=search.result();
            if(Boolean.TRUE.equals(row.remove("tomcatApplicationOnlyDriver"))&&"NOT_FOUND".equals(decision.get("driverResolution"))){decision.put("driverResolutionReason","Driver presente na aplicação; não usado pelo datasource do Tomcat");decision.put("driverVersionEvidence",decision.get("driverResolutionReason"));}
            for(Object raw:(List)row.get("associations")){Map association=(Map)raw;if(Long.valueOf(context.pid).equals(association.get("pid")))association.put("driverResult",decision);}
            aggregate(row);
            if("INCOMPLETE".equals(decision.get("driverResolution")))scan.note((String)decision.get("driverResolutionReason"));
        }
    }
    void runtimeOrigins(Search search){
        AssessmentScan.ProcessContext context=search.context;
        for(String path:context.launch.bootPrepend)runtimeEntry(search,path);
        for(String path:context.launch.bootClasspath)runtimeEntry(search,path);
        for(String path:context.launch.bootAppend)runtimeEntry(search,path);
        List<String> ext=new ArrayList<String>(context.launch.extDirs);
        String javaVersion=AssessmentScan.clean(context.process.get("javaVersion"));
        if(javaVersion==null&&context.javaHome!=null&&!Boolean.TRUE.equals(context.process.get("runtimeReplacedOnDisk")))for(Map runtime:scan.runtimes.values())if(context.javaHome.equals(runtime.get("javaHome")))javaVersion=AssessmentScan.clean(runtime.get("version"));
        if(ext.isEmpty()&&!context.launch.explicitExtDirs&&context.javaHome!=null&&javaVersion!=null&&javaVersion.matches("1\\.[0-8].*")){File home=new File(context.javaHome);ext.add(new File(home,home.getName().equals("jre")?"lib/ext":"jre/lib/ext").getPath());}
        if(javaVersion==null&&!context.launch.explicitExtDirs&&context.javaHome!=null&&!jvmExcluded(context,context.javaHome)&&new File(context.javaHome,"jre/lib/ext").isDirectory())search.fail("Versão da JVM não confirmada; precedência do diretório de extensões incerta");
        List<File> extensions=new ArrayList<File>();for(String path:ext){if(jvmExcluded(context,path)){search.caveat=JVM_EXCLUDED;continue;}extensions.addAll(search.directory(new File(path),"ext"));}search.group(extensions,"ext");
    }
    static final String JVM_EXCLUDED="Extensões/boot da JVM em caminho excluído não verificados";
    /** The JVM's own boot/ext entries under an excluded Java home are skipped: a user exclusion must not block every datasource of that JVM. */
    boolean jvmExcluded(AssessmentScan.ProcessContext context,String path){
        String home=context.javaHome;if(home==null||path==null)return false;
        File jre=new File(home).getName().equals("jre")?new File(home).getParentFile():null;
        boolean owned=AssessmentScan.under(path,home)||jre!=null&&AssessmentScan.under(path,jre.getPath());
        return owned&&(scan.excluded(path)||scan.excluded(home));
    }
    void runtimeEntry(Search search,String path){
        if(jvmExcluded(search.context,path)){search.caveat=JVM_EXCLUDED;return;}
        search.walk(new File(path),"bootclasspath",0,null,false);
    }
    final class Search {
        final AssessmentScan.ProcessContext context;final Set<String>wanted;final String driver;final OpenFiles open;
        final Set<String>seen=new HashSet<String>();final List<Map>candidates=new ArrayList<Map>();
        String reason,notFoundReason,winnerReason,caveat;Map winner;boolean terminal,probable,declared,followManifest=true;int order;
        Search(AssessmentScan.ProcessContext c,Set<String>w,String d,OpenFiles o){context=c;wanted=w;driver=d;open=o;}
        final List<String> reasons=new ArrayList<String>();
        void fail(String why){if(why!=null&&winner==null&&!reasons.contains(why)&&reasons.size()<4)reasons.add(why);if(winner==null&&!terminal&&reason==null)reason=why;}
        List<File> directory(File raw,String source){
            List<File> out=new ArrayList<File>();File dir=scan.linkedPath(raw);
            if(dir==null){fail("Caminho excluído ou não acessível: "+raw.getPath());return out;}
            if(!dir.exists())return out;
            if(!dir.isDirectory()||!dir.canRead()||!scan.visitLinked(dir)){fail("Sem permissão ou orçamento de leitura em "+dir.getPath());return out;}
            BoundedDirectory entries=null;
            try{entries=BoundedDirectory.open(dir,scan.deadline);int n=0;for(File f;(f=entries.next())!=null;){if(++n>512||!scan.available()){fail("Limite de entradas ou tempo em "+dir.getPath());break;}if(f.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar"))out.add(f);}}
            catch(IOException e){fail("Pasta não legível: "+dir.getPath());}finally{if(entries!=null)entries.close();}
            Collections.sort(out);return out;
        }
        void group(List<File> files,String source){List<Map> group=new ArrayList<Map>();for(File file:files)walk(file,source,0,group,false);choose(group);}
        void choose(List<Map> group){
            if(group.isEmpty()||winner!=null||terminal||reason!=null)return;
            if(group.size()==1){winner=group.get(0);return;}
            open.read();List<Map> opened=new ArrayList<Map>();for(Map c:group){markOpen(c);if(Boolean.TRUE.equals(c.get("open")))opened.add(c);}
            if(opened.size()==1){winner=opened.get(0);probable=true;}
            else {terminal=true;reason="Múltiplos JARs na mesma origem sem ordem garantida"+(open.reason==null?"": "; "+open.reason);}
        }
        void markOpen(Map c){String path=(String)c.get("path");c.put("open",open.paths.contains(path)?Boolean.TRUE:open.readable?Boolean.FALSE:null);if(open.deleted.contains(path))c.put("replacedOnDisk",Boolean.TRUE);}
        void application(WebLogicApplicationDrivers.Result result){List<Map> group=new ArrayList<Map>();for(Map raw:result.candidates){Map c=new LinkedHashMap(raw);c.put("order",Integer.valueOf(++order));c.put("source","aplicação");c.put("open",null);c.put("loaded",Boolean.FALSE);if(candidates.size()<MAX_CANDIDATES){candidates.add(c);group.add(c);}else fail("Limite de candidatos JDBC atingido");}choose(group);}
        void walk(File raw,String source,int depth,List<Map> group,boolean disk){
            if(disk&&candidates.size()>=MAX_CANDIDATES)return;
            File file=scan.linkedPath(raw);if(file==null){if(!disk)fail("Caminho excluído ou não acessível: "+raw.getPath());return;}
            if(file.getName().equals("*")){if(!disk)group(directory(file.getParentFile(),source),source);return;}
            if(!seen.add(file.getPath()))return;
            if(seen.size()>MAX_PATHS||depth>24||!scan.available()){if(!disk)fail("Limite de caminhos, profundidade ou tempo antes de encontrar a classe");return;}
            if(!file.exists())return; // JVM ignores absent classpath entries.
            if(!file.canRead()||!scan.visitLinked(file)){if(!disk)fail("Sem permissão ou orçamento de leitura em "+file.getPath());return;}
            int position=++order;Archive a;
            if(file.isDirectory()){
                a=new Archive();File cls=scan.linkedPath(new File(file,driver));if(cls==null){if(!disk)fail("Diretório de classes excluído: "+file.getPath());return;}if(cls.isFile())a.classes.add(driver);
            }else if((file.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar")||file.getName().equals("content")))a=inspect(file,wanted);
            else return;
            if(!a.complete&&!disk)fail(a.reason);
            if(a.classes.contains(driver)){
                Map c=AssessmentScan.map("path",file.getPath(),"version",a.versions.size()==1?a.versions.iterator().next():null,"versionSource",a.versionSource,"family",family(file.getPath()),"source",source,"order",Integer.valueOf(position),"open",null,"loaded",Boolean.FALSE,"onClasspath",Boolean.valueOf(!disk));
                if(candidates.size()<MAX_CANDIDATES){candidates.add(c);if(!disk){if(group!=null)group.add(c);else if(winner==null&&!terminal&&reason==null)winner=c;}}
                else if(!disk)fail("Limite de candidatos JDBC atingido antes da decisão");
            }
            if(followManifest&&!disk&&a.classpath!=null)for(String token:a.classpath.trim().split("\\s+"))try{
                URI reference=new URI(token);if(reference.isAbsolute()||reference.getRawAuthority()!=null||reference.getQuery()!=null||reference.getFragment()!=null){fail("Referência de manifesto não local; ordem não confirmada em "+file.getPath());continue;}
                walk(new File(file.toURI().resolve(reference)),("WebLogic".equals(context.launch.serverType)?"manifesto WebLogic":"manifesto do classpath"),depth+1,group,false);
            }catch(Exception e){fail("Referência de manifesto inválida em "+file.getPath());}
        }
        Map result(){
            open.read();for(Map c:candidates)markOpen(c);
            String status=winner!=null?((probable||declared)?"PROBABLE":winner.get("version")==null?"VERSION_UNAVAILABLE":"IDENTIFIED"):terminal?"AMBIGUOUS":reason!=null?"INCOMPLETE":"NOT_FOUND";
            String why=winnerReason!=null&&winner!=null?winnerReason:winner!=null?(probable?"Único JAR candidato aberto pela JVM; carregamento da classe não comprovado":"Classe exata selecionada pela ordem de carregamento; leitura passiva") :reason!=null?reason:notFoundReason!=null?notFoundReason:"Classe do driver não encontrada nas origens examinadas";
            if(winner==null&&"INCOMPLETE".equals(status)&&reasons.size()>1){StringBuilder more=new StringBuilder(why);for(int i=0;i<reasons.size();i++)if(!reasons.get(i).equals(reason)&&more.length()<400)more.append(" | também: ").append(reasons.get(i));why=more.toString();}
            Map out=AssessmentScan.map("driverResolution",status,"driverResolutionReason",why,"driverCandidates",candidates);
            if(winner!=null){winner.put("loaded",Boolean.valueOf(!probable&&!declared));out.put("driverArtifact",winner.get("path"));if(winner.get("version")!=null)out.put("driverVersion",winner.get("version"));out.put("driverVersionSource",winner.get("versionSource"));out.put("driverFamily",winner.get("family"));if(probable||declared)out.put("driverConfidence","PROBABLE");if(Boolean.TRUE.equals(winner.get("replacedOnDisk"))){out.put("driverReplacedOnDisk",Boolean.TRUE);out.put("driverResolutionReason",why+"; JAR do driver substituído em disco; reinício pendente. Versão refere-se ao arquivo atual em disco");}}
            if(caveat!=null){out.put("driverResolutionCaveat",caveat);out.put("driverResolutionReason",out.get("driverResolutionReason")+"; "+caveat);}
            out.put("driverVersionEvidence",out.get("driverResolutionReason"));return out;
        }
    }
    final class OpenFiles {
        final AssessmentScan.ProcessContext context;final File proc;final Set<String>paths=new HashSet<String>(),deleted=new HashSet<String>();boolean done,readable;String reason;
        OpenFiles(AssessmentScan.ProcessContext c,File p){context=c;proc=p;}
        void read(){if(done)return;done=true;
            if(context.mapping!=null){paths.addAll(context.mapping.jars);deleted.addAll(context.mapping.deletedJars);}
            File fd=new File(proc,"fd");BoundedDirectory entries=null;
            try{if(!fd.isDirectory()||!fd.canRead())throw new IOException();entries=BoundedDirectory.open(fd,scan.deadline);int count=0;readable=true;
                for(File f;(f=entries.next())!=null;){if(++count>4096||!scan.available()){readable=false;reason="Limite de arquivos abertos do processo atingido";break;}String path=f.getCanonicalPath();boolean removed=path.endsWith(" (deleted)");if(removed)path=path.substring(0,path.length()-10);if(path.toLowerCase(Locale.ENGLISH).endsWith(".jar")){paths.add(path);if(removed)deleted.add(path);}}
            }catch(IOException e){reason="Arquivos abertos do processo não legíveis";}finally{if(entries!=null)entries.close();}
        }
    }
    List<File> standard(AssessmentScan.ProcessContext c,List<String> cp){
        Set<File> roots=new LinkedHashSet<File>();List<File> out=new ArrayList<File>();
        for(String path:cp)if(new File(path).getName().equals("weblogic.jar"))roots.add(new File(path).getParentFile());
        for(String path:new String[]{c.launch.serverHome,c.launch.webLogicHome,c.launch.wlsHome})if(path!=null){File f=new File(path);roots.add(new File(f,"lib"));roots.add(new File(f,"server/lib"));}
        Set<String>seen=new HashSet<String>();
        for(File root:roots)for(File ancestor=root;ancestor!=null&&seen.size()<32;ancestor=ancestor.getParentFile()){
            if(!seen.add(ancestor.getPath()))break;
            for(String name:new String[]{"ojdbc5.jar","ojdbc6.jar","ojdbc7.jar","ojdbc8.jar","ojdbc10.jar","ojdbc11.jar"}){File f=scan.linkedPath(new File(ancestor,name));if(f!=null&&f.isFile()&&!out.contains(f))out.add(f);}
            File modules=scan.linkedPath(new File(ancestor,"oracle_common/modules"));if(modules==null||!modules.isDirectory())continue;
            for(File module:scan.serverDataSources.children(modules))if(module.getName().matches("oracle[.]jdbc(?:_[0-9.]+)?")){File safe=scan.linkedPath(module);if(safe==null)continue;for(File file:scan.serverDataSources.children(safe))if(file.getName().matches("(?i)ojdbc.*[.]jar")&&out.size()<64&&!out.contains(file))out.add(file);}
        }
        return out;
    }
    static void aggregate(Map row){
        List<Map> results=new ArrayList<Map>(),all=new ArrayList<Map>();Set<String> keys=new LinkedHashSet<String>();
        for(Object raw:(List)row.get("associations")){Map association=(Map)raw;if(!(association.get("driverResult") instanceof Map))continue;Map result=(Map)association.get("driverResult");results.add(result);keys.add(String.valueOf(result.get("driverResolution"))+"|"+result.get("driverArtifact")+"|"+result.get("driverVersion"));
            for(Object value:(List)result.get("driverCandidates")){Map candidate=(Map)value;boolean present=false;for(Map prior:all)if(prior.get("path").equals(candidate.get("path")))present=true;if(!present&&all.size()<MAX_CANDIDATES)all.add(new LinkedHashMap(candidate));}
        }
        for(String key:new String[]{"driverArtifact","driverVersion","driverVersionSource","driverFamily","driverConfidence","driverReplacedOnDisk"})row.remove(key);
        if(results.isEmpty())return;
        if(keys.size()==1){row.putAll(results.get(0));row.put("driverCandidates",all);}
        else {for(Map candidate:all)candidate.put("loaded",null);row.put("driverResolution","VARIES");row.put("driverResolutionReason","Resultados diferentes entre processos; consulte a decisão por PID");row.put("driverVersionEvidence",row.get("driverResolutionReason"));row.put("driverCandidates",all);}
    }
    Archive inspect(File file,Set<String>wanted){
        Archive prior=cache.get(file.getPath());if(prior!=null&&prior.checked.containsAll(wanted))return prior;
        Archive a=new Archive();a.checked.addAll(wanted);
        try{
            if(!scan.available()||cache.size()>=MAX_PATHS||file.length()>ArchiveMetadata.FILE_LIMIT)throw new IOException("Limite de manifestos, tamanho ou tempo em "+file.getPath());
            byte[] bytes=WebLogicManifest.read(scan.archiveMetadata,file,ArchiveMetadata.METADATA_LIMIT,wanted,a.classes);if(bytes!=null)manifest(a,bytes);
            if(file.getName().equals("weblogic.jar")){a.versions.clear();a.versionSource=null;}
            if(!a.classes.isEmpty()){
                if(archivesRead>=256)throw new IOException("Limite de 256 JARs candidatos atingido antes da decisão");archivesRead++;
                ZipFile zip=new ZipFile(file);Set<String>pom=new LinkedHashSet<String>();String derby=null;
                try{Enumeration<? extends ZipEntry> entries=zip.entries();int count=0,metadata=0;
                    while(entries.hasMoreElements()){if(++count>ArchiveMetadata.ENTRY_LIMIT||!scan.available())throw new IOException("Limite de entradas JDBC");ZipEntry e=entries.nextElement();String name=e.getName();
                        if(!(name.startsWith("META-INF/maven/")&&name.endsWith("/pom.properties")||name.equals("org/apache/derby/info/DBMS.properties")))continue;
                        if(++metadata>32||e.getSize()>ArchiveMetadata.METADATA_LIMIT)throw new IOException("Limite de metadados JDBC");InputStream in=zip.getInputStream(e);Properties p=new Properties();try{p.load(new ByteArrayInputStream(scan.archiveMetadata.read(in,ArchiveMetadata.METADATA_LIMIT)));}finally{in.close();}
                        if(name.endsWith("pom.properties")&&ArchiveMetadata.jdbcArtifact(p.getProperty("groupId"),p.getProperty("artifactId"))&&version(p.getProperty("version"))!=null)pom.add(p.getProperty("version"));
                        if(name.endsWith("DBMS.properties"))try{int n=Integer.parseInt(p.getProperty("derby.version.maint"));derby=p.getProperty("derby.version.major")+"."+p.getProperty("derby.version.minor")+"."+(n/1000000)+"."+(n%1000000);}catch(Exception ignored){}
                    }
                }finally{zip.close();}
                if(derby!=null&&version(derby)!=null){a.versions.clear();a.versions.add(derby);a.versionSource="manifest";}
                else if(a.versions.isEmpty()&&pom.size()==1){a.versions.addAll(pom);a.versionSource="pom";}
                if(a.versions.isEmpty()&&!file.getName().equals("weblogic.jar")){
                    String parent=file.getParentFile().getName();if(parent.matches("oracle[.]jdbc_[0-9]+(?:[.][0-9]+)+")){addVersion(a,parent.substring(12));a.versionSource="module-dir";}
                    else {java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)^ojdbc[0-9]+-([0-9]+(?:[.][0-9]+)+)[.]jar$").matcher(file.getName());if(m.matches()){addVersion(a,m.group(1));a.versionSource="file-name";}else if(family(file.getPath())!=null)a.versionSource="file-name";}
                }
            }
        }catch(Exception e){a.complete=false;a.reason="Inspeção do JAR indisponível ou limitada: "+file.getPath();if(e.getMessage()!=null&&e.getMessage().startsWith("Limite"))a.reason=e.getMessage();}
        if(cache.size()<MAX_PATHS)cache.put(file.getPath(),a);return a;
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
        String specification=attrs.getValue("Specification-Version");
        String embedded=null;
        if(version(implementation)==null&&implementation!=null){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)driver version\\s*-\\s*([0-9][0-9A-Za-z._+\\-]*)$").matcher(implementation.trim());if(m.find())embedded=m.group(1);}
        addVersion(a,version(implementation)!=null?implementation:embedded!=null?embedded:version(specification)!=null?specification:attrs.getValue("Bundle-Version"));
        if(!a.versions.isEmpty())a.versionSource="manifest";
    }
}
