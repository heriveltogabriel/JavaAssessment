package assessment;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Configuration-only JDBC discovery bounded to observed applications. Never connects or loads classes. */
final class ApplicationJdbc {
    static final int TEXT_LIMIT=512*1024, FILES=128, ENTRIES=512, NESTED_LIMIT=4*1024*1024;
    static final Pattern URL=Pattern.compile("jdbc:[^\\s\\\"'<>\\\\]{1,8192}");
    final AssessmentScan scan;
    final Set<String> visited=new HashSet<String>();
    int files, entries, archives;
    ApplicationJdbc(AssessmentScan scan){this.scan=scan;}
    void discover() {
        if(!Boolean.TRUE.equals(scan.settings.get("inspectArchives")))return;
        for(AssessmentScan.ProcessContext c:scan.processContexts) {
            if(c.application==null)continue;
            // A server's working directory is not an application configuration root.
            if(c.server==null) {
                if(c.launch.jarPath!=null){File jar=new File(c.launch.jarPath);archive(jar,c);directory(jar.getParentFile(),c,0);}
                // A WebLogic domain home belongs to the domain's servers: a sidecar process (e.g. Derby) started there is not the owner.
                AssessmentScan.ProcessContext home=c.launch.cwd!=null&&new File(c.launch.cwd,"config/config.xml").isFile()?domainOwner(c.launch.cwd):null;
                if(c.launch.cwd==null||!new File(c.launch.cwd,"config/config.xml").isFile())directory(c.launch.cwd,c,0);
                else if(home!=null&&home!=c)directory(c.launch.cwd,home,0);
                for(String path:c.launch.classpath){File file=new File(path);if(file.isDirectory())directory(file,c,0);}
            }
            // WAR/EARs already associated with this observed server, never unrelated stopped deployments.
            if(c.server!=null)for(Object raw:scan.applications){Map app=(Map)raw;
                if(app==c.application || !Long.valueOf(c.pid).equals(app.get("pid")) || !(app.get("path") instanceof String))continue;
                AssessmentScan.ProcessContext deployed=new AssessmentScan.ProcessContext(c.launch,c.pid,c.javaHome,c.process,app);
                File file=new File((String)app.get("path"));if(file.isFile())archive(file,deployed);else directory(file,deployed,0);
            }
        }
        scan.applicationConfigFiles.load();
        for(File file:scan.applicationConfigFiles.files)if(!scan.applicationConfigFiles.shared.contains(file.getPath()))inspect(file,scan.applicationConfigFiles.owner(file));
    }
    /** The first running WebLogic server (lowest PID) whose domain is this directory; null for a stopped or unrelated domain. */
    AssessmentScan.ProcessContext domainOwner(File directory){
        String wanted=ApplicationConfigFiles.canonical(directory);AssessmentScan.ProcessContext owner=null;
        if(wanted==null)return null;
        for(AssessmentScan.ProcessContext w:scan.processContexts){
            if(w.server==null||!"WebLogic".equals(w.server.get("type"))||!(w.server.get("domainPath") instanceof String))continue;
            if(wanted.equals(ApplicationConfigFiles.canonical(new File((String)w.server.get("domainPath"))))&&(owner==null||w.pid<owner.pid))owner=w;
        }
        return owner;
    }
    String identity(String path,AssessmentScan.ProcessContext c){return path+":"+(c==null?"machine":String.valueOf(c.application.get("id")))+":"+(c==null?0:c.pid);}
    boolean capacity(){if(files>=FILES||entries>=ENTRIES){scan.warn("Limite da busca de configurações JDBC das aplicações atingido");return false;}return scan.available();}
    void directory(File root,AssessmentScan.ProcessContext c,int depth){
        if(root==null||depth>3||!capacity())return;
        File safe=ApplicationConfigFiles.safe(scan,root);
        if(safe==null||!safe.isDirectory()||safe.getParentFile()==null||!visited.add(identity(safe.getPath()+"/",c)))return;
        BoundedDirectory children=null;
        try {
            children=BoundedDirectory.open(safe,scan.deadline);
            for(File file;capacity()&&(file=children.next())!=null;){
                entries++; File child=ApplicationConfigFiles.safe(scan,file);if(child==null||!scan.visitLinked(child))continue;
                if(child.isDirectory()){
                    // Only known configuration subtrees, not arbitrary sibling applications or dependencies.
                    if(Arrays.asList("config","conf","META-INF","WEB-INF","classes").contains(child.getName()))directory(child,c,depth+1);
                } else inspect(child,c);
            }
        }catch(IOException e){scan.note("Leitura de pasta de configuração JDBC incompleta");}
        finally{if(children!=null)children.close();}
    }
    void inspect(File original,AssessmentScan.ProcessContext c){
        if(!capacity()||!ApplicationConfigFiles.textFile(original.getName()))return;
        File file=ApplicationConfigFiles.safe(scan,original);
        if(file==null||!file.isFile()||!visited.add(identity(file.getPath(),c))||!scan.visitLinked(file))return;
        if(file.length()>TEXT_LIMIT){scan.note("Arquivo de configuração JDBC excede 512 KiB; leitura ignorada");return;}
        files++;InputStream in=null;
        try{in=new FileInputStream(file);byte[] bytes=scan.archiveMetadata.read(in,TEXT_LIMIT);parse(file.getPath(),bytes,c);}
        catch(IOException e){scan.note("Leitura limitada de configuração JDBC não concluída");}
        finally{if(in!=null)try{in.close();}catch(IOException e){}}
    }
    void archive(File original,AssessmentScan.ProcessContext c){
        if(!capacity()||archives>=64||!original.getName().matches("(?i).*\\.(jar|war|ear)"))return;
        File file=ApplicationConfigFiles.safe(scan,original);
        if(file==null||!file.isFile()||file.length()>ArchiveMetadata.FILE_LIMIT||!visited.add(identity(file.getPath(),c))||!scan.visitLinked(file))return;
        archives++;ZipFile zip=null;
        try{
            scan.archiveMetadata.preflight(file);zip=new ZipFile(file);
            List<ZipEntry> selected=new ArrayList<ZipEntry>();Set<String> names=new HashSet<String>(),duplicates=new HashSet<String>();
            Enumeration<? extends ZipEntry> all=zip.entries();int count=0;
            while(all.hasMoreElements()&&scan.available()){
                if(++count>ArchiveMetadata.ENTRY_LIMIT)throw new IOException("entries");
                ZipEntry e=all.nextElement();String name=e.getName();
                if(e.isDirectory()||!ArchiveMetadata.safeName(name))continue;
                boolean nested=name.matches("(?i).*\\.(jar|war)") && file.getName().toLowerCase(Locale.ENGLISH).endsWith(".ear");
                if(!ApplicationConfigFiles.textFile(name)&&!nested)continue;
                // Dependency resources are not this application's configuration.
                if(name.startsWith("BOOT-INF/lib/")||name.startsWith("WEB-INF/lib/"))continue;
                if(!names.add(name))duplicates.add(name);
                if(selected.size()<FILES)selected.add(e);else scan.note("Limite de configurações dentro do arquivo Java atingido");
            }
            for(ZipEntry e:selected){
                if(!capacity())break;if(duplicates.contains(e.getName())){scan.note("Entrada de configuração duplicada no arquivo Java; ignorada");continue;}
                boolean nested=e.getName().matches("(?i).*\\.(jar|war)");
                int limit=nested?NESTED_LIMIT:TEXT_LIMIT;if(e.getSize()<0||e.getSize()>limit)continue;
                InputStream in=zip.getInputStream(e);byte[] bytes;
                try{bytes=scan.archiveMetadata.read(in,limit);}finally{in.close();}
                String source=file.getPath()+"!/"+e.getName();
                if(nested)nested(source,bytes,c,1);else{files++;parse(source,bytes,c);}
            }
        }catch(IOException e){scan.note("Inspeção limitada das configurações do arquivo Java incompleta");}
        finally{if(zip!=null)try{zip.close();}catch(IOException e){}}
    }
    void nested(String source,byte[] bytes,AssessmentScan.ProcessContext c,int depth)throws IOException{
        if(depth>2)return;
        ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes));int expanded=0,count=0;
        // Fully bound inflation, including skipped entries; getNextEntry never drains an unbounded entry.
        List<String> names=new ArrayList<String>();List<byte[]> values=new ArrayList<byte[]>();Set<String> seen=new HashSet<String>(),duplicates=new HashSet<String>();
        try{for(ZipEntry e;capacity()&&(e=zip.getNextEntry())!=null;){
            if(++count>4096)throw new IOException("entries");
            byte[] value=scan.archiveMetadata.read(zip,NESTED_LIMIT-expanded);expanded+=value.length;
            String name=e.getName();if(!seen.add(name))duplicates.add(name);
            if(!e.isDirectory()&&ArchiveMetadata.safeName(name)&&ApplicationConfigFiles.textFile(name)&&value.length<=TEXT_LIMIT&&names.size()<FILES){names.add(name);values.add(value);}
        }}finally{zip.close();}
        for(int i=0;i<names.size()&&capacity();i++)if(!duplicates.contains(names.get(i))){files++;parse(source+"!/"+names.get(i),values.get(i),c);}
    }
    JdbcEndpoint.Resolver resolver(final AssessmentScan.ProcessContext c){return new JdbcEndpoint.Resolver(scan,c==null?null:new File(scan.serverDataSources.procRoot,String.valueOf(c.pid))) {
        String lookup(String key,List<String> sources){
            String value=super.lookup(key,sources);
            if(value==null&&!key.startsWith("env.")){value=environment(key);if(value!=null)source(sources,"environment");}
            return value;
        }
    };}
    static String field(String key){
        String k=SpringDataSource.key(key);
        if(JdbcEndpoint.Resolver.secret(k))return null;
        if(Arrays.asList("url","jdbcurl","connection.url","hibernate.connection.url","javax.persistence.jdbc.url","jakarta.persistence.jdbc.url").contains(k))return "url";
        if(Arrays.asList("driverclassname","driverclass","connection.driverclass","hibernate.connection.driverclass","javax.persistence.jdbc.driver","jakarta.persistence.jdbc.driver").contains(k))return "driver";
        if(Arrays.asList("jndiname","connection.datasource","hibernate.connection.datasource","jtadatasource","nonjtadatasource").contains(k))return "jndi";
        if(k.equals("dialect")||k.equals("hibernate.dialect"))return "dialect";
        return null;
    }
    static final class Allowed extends Properties {
        public synchronized Object put(Object key,Object value){String name=field(String.valueOf(key));return name!=null&&String.valueOf(value).length()<=8192?super.put(name,String.valueOf(value)):null;}
    }
    void parse(String source,byte[] bytes,AssessmentScan.ProcessContext c){
        // Server-specific XML parsers own these resources and associations. Do not invent a second generic datasource.
        for(Object value:scan.configuredDatasourceRows.values())if(source.equals(((Map)value).get("configurationSource")))return;
        String text;
        try{text=new String(bytes,"UTF-8");}catch(UnsupportedEncodingException e){return;}
        if(text.indexOf('\u0000')>=0)return;
        String name=source.substring(source.lastIndexOf('/')+1).toLowerCase(Locale.ENGLISH);
        if(name.endsWith(".xml")){
            Xml handler=new Xml();
            try{ServerDataSources.parseXml(text,handler);}
            catch(Exception e){scan.note("XML de configuração JDBC inválido ou fora dos limites; ignorado");return;}
            for(Map<String,String> properties:handler.rows)emit(source,properties,c,handler.method,false);
        }else if(name.equals("hibernate.properties")){
            Allowed properties=new Allowed();
            try{properties.load(new ByteArrayInputStream(bytes));Map<String,String> row=new LinkedHashMap<String,String>();for(Object key:properties.keySet())row.put(String.valueOf(key),properties.getProperty(String.valueOf(key)));emit(source,row,c,"hibernate-cfg",false);}
            catch(Exception e){scan.note("Propriedades Hibernate inválidas; ignoradas");}
        }
        // Spring's own parser handles activation/precedence; generic fallback must not resurrect inactive profiles.
        boolean spring = c != null && "Spring Boot".equals(c.application.get("applicationType"));
        if(spring && (name.matches("(application|bootstrap).*\\.(properties|yml|yaml)")||text.contains("spring.datasource")||text.contains("spring:\n")||text.contains("spring:\r\n")))return;
        Matcher matcher=URL.matcher(text);int found=0;
        while(matcher.find()&&found++<32&&scan.available()){
            if(matcher.end()<text.length() && !Character.isWhitespace(text.charAt(matcher.end())) && "\"'<>\\".indexOf(text.charAt(matcher.end()))<0)continue;
            int lineStart=text.lastIndexOf('\n',matcher.start())+1;
            String key=text.substring(lineStart,matcher.start());
            if(JdbcEndpoint.Resolver.secret(key))continue;
            Map<String,String> row=new LinkedHashMap<String,String>();row.put("url",matcher.group());emit(source,row,c,"generic-scan",true);
        }
    }
    void emit(String source,Map<String,String> properties,AssessmentScan.ProcessContext c,String method,boolean generic){
        JdbcEndpoint.Resolver resolver=resolver(c);Map fields=new LinkedHashMap();
        JdbcEndpoint.addTo(fields,properties.get("url"),resolver);
        String driver=SpringDataSource.validDriver(resolver.expand(properties.get("driver"),new ArrayList<String>()));if(driver!=null)fields.put("driverName",driver);
        String jndi=JndiLinks.normalized(resolver.expand(properties.get("jndi"),new ArrayList<String>()));if(jndi!=null)fields.put("jndiName",jndi);
        String type=SpringConfiguration.dialectType(properties.get("dialect"));
        if(type!=null){if(fields.get("databaseType")==null){fields.put("databaseType",type);fields.put("databaseTypeEvidence","Tipo inferido pelo dialeto; conexão não verificada");}else if(!type.equals(fields.get("databaseType")))fields.put("configurationObservation","Tipo da URL diverge do dialeto; a URL foi preservada");}
        if(fields.get("databaseType")==null&&fields.get("databaseHost")==null&&driver==null&&jndi==null)return;
        // A dialect by itself is not a configured datasource.
        if(properties.get("url")==null&&driver==null&&jndi==null)return;
        if(generic&&fields.get("databaseHost")==null)return;
        String domain=c!=null&&c.server!=null&&"WebLogic".equals(c.server.get("type"))&&c.server.get("domainPath") instanceof String?(String)c.server.get("domainPath"):null;
        for(Object value:scan.datasources){Map existing=(Map)value;
            boolean sameApp=c==null?existing.get("applicationId")==null:c.application.get("id")!=null&&c.application.get("id").equals(existing.get("applicationId"));
            boolean sameSource=source.equals(existing.get("configurationSource"));
            boolean endpoint=fields.get("databaseHost")!=null&&fields.get("databaseHost").equals(existing.get("databaseHost"))&&equal(fields.get("databasePort"),existing.get("databasePort"))&&equal(fields.get("databaseName"),existing.get("databaseName"));
            boolean sameJndi=jndi!=null&&jndi.equals(JndiLinks.normalized(existing.get("jndiName")));
            if(domain!=null&&domain.equals(existing.get("domainPath"))&&(endpoint||sameJndi))return;
            if(sameApp&&(generic||sameSource)&&(endpoint||sameJndi)&&(generic||properties.get("name")==null))return;
        }
        if(scan.datasources.size()>=512){scan.warn("Limite de datasources atingido");return;}
        fields.put("configurationPresent",Boolean.TRUE);fields.put("configurationSource",source);fields.put("discoveryMethod",method);
        fields.put("configurationKey",method+":"+String.valueOf(properties.get("name")));fields.put("configurationResolution","local-candidate");
        String name=ProcessLaunch.label(properties.get("name"));if(name!=null)fields.put("configuredName",name);
        List methods=fields.get("resolvedFrom") instanceof List?new ArrayList((List)fields.get("resolvedFrom")):new ArrayList();methods.add(method);fields.put("resolvedFrom",methods);
        Map app=c==null?AssessmentScan.map("name","Configuração da máquina"):c.application;
        AssessmentScan.ProcessContext context=c==null?new AssessmentScan.ProcessContext(new ProcessLaunch(),0,null,new LinkedHashMap(),app):c;
        List libraries=new ArrayList();if(c!=null)for(Object value:scan.libraries){Map lib=(Map)value;if(app.get("id")!=null&&app.get("id").equals(lib.get("applicationId")))libraries.add(lib);}
        SpringDataSource.matchPackagedDriver(fields,libraries);
        int before=scan.datasources.size();scan.addConfiguredSpringDatasource(source,fields,app,context);
        for(int i=before;i<scan.datasources.size();i++){Map row=(Map)scan.datasources.get(i);row.put("evidence","Configuração JDBC encontrada por "+method+"; carregamento do driver e conexão não verificados");
            if(domain!=null){row.put("domainPath",domain);row.put("domainName",new File(domain).getName());}
            if(c==null){for(String key:new String[]{"pid","applicationId","applicationName","associations"})row.remove(key);row.put("configurationBinding","machine-unbound");row.put("scanScope","app-config-dir");}
        }
    }
    static boolean equal(Object a,Object b){return a==null?b==null:a.equals(b);}
    static boolean bean(String name){return name!=null&&(Arrays.asList("org.springframework.jdbc.datasource.DriverManagerDataSource","org.springframework.jdbc.datasource.SimpleDriverDataSource","org.apache.commons.dbcp.BasicDataSource","org.apache.commons.dbcp2.BasicDataSource","com.zaxxer.hikari.HikariDataSource","com.zaxxer.hikari.HikariConfig","com.mchange.v2.c3p0.ComboPooledDataSource","org.springframework.jndi.JndiObjectFactoryBean").contains(name)||name.matches("oracle\\.ucp\\.[A-Za-z0-9_.]*Pool(?:XA)?DataSource(?:Impl)?"));}
    static final class Xml extends DefaultHandler {
        final List<Map<String,String>> rows=new ArrayList<Map<String,String>>();
        Map<String,String> row;String key,method="spring-xml-bean";StringBuilder text;int depth,ownerDepth,elements,propertyDepth;
        public void startElement(String uri,String local,String qualified,Attributes a)throws SAXException{
            String name=local.length()>0?local:qualified;
            if(++depth>32||++elements>12000||rows.size()>64)throw new SAXException("limit");
            if(row==null&&(name.equals("persistence-unit")||name.equals("session-factory")||name.equals("bean")&&bean(a.getValue("class")))){
                row=new LinkedHashMap<String,String>();ownerDepth=depth;String id=a.getValue("name");if(id==null)id=a.getValue("id");if(ProcessLaunch.label(id)!=null)row.put("name",id);
                if(name.equals("persistence-unit"))method="persistence-xml";else if(name.equals("session-factory"))method="hibernate-cfg";
            }
            if(name.equals("jndi-lookup")){Map<String,String> lookup=new LinkedHashMap<String,String>();lookup.put("jndi",a.getValue("jndi-name"));if(ProcessLaunch.label(a.getValue("id"))!=null)lookup.put("name",a.getValue("id"));rows.add(lookup);}
            if(row==null)return;
            if(name.equals("property")){key=field(a.getValue("name")==null?"":a.getValue("name"));propertyDepth=depth;text=key==null?null:new StringBuilder();String value=a.getValue("value");if(key!=null&&value!=null&&value.length()<=8192)row.put(key,value);}
            else if(name.equals("jta-data-source")||name.equals("non-jta-data-source")){key="jndi";propertyDepth=depth;text=new StringBuilder();}
        }
        public void characters(char[] data,int start,int count)throws SAXException{if(text!=null){if(text.length()+count>8192)throw new SAXException("value limit");text.append(data,start,count);}}
        public void endElement(String uri,String local,String qualified){
            if(row!=null&&key!=null&&depth==propertyDepth){String value=text==null?"":text.toString().trim();if(value.length()>0)row.put(key,value);key=null;text=null;}
            if(row!=null&&depth==ownerDepth){rows.add(row);row=null;}
            depth--;
        }
    }
}
