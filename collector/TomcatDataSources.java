package assessment;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Tomcat discovery and common-loader origins. Driver decisions belong to JdbcDriverResolver. */
final class TomcatDataSources {
    final ServerDataSources owner; final AssessmentScan scan;
    final Map<Long,State> states=new LinkedHashMap<Long,State>();
    static final class State {
        final Map<String,String> properties=new LinkedHashMap<String,String>();
        final List<File> applications=new ArrayList<File>(); final Set<String> seenApps=new HashSet<String>();
        boolean configured,discovered; String propertyFailure;
    }
    TomcatDataSources(ServerDataSources owner){this.owner=owner;this.scan=owner.scan;}
    State state(AssessmentScan.ProcessContext c){Long pid=Long.valueOf(c.pid);State s=states.get(pid);if(s==null){s=new State();if(states.size()<512)states.put(pid,s);}return s;}
    void configureResolver(AssessmentScan.ProcessContext c,JdbcEndpoint.Resolver r){
        if(!"Tomcat".equals(c.launch.serverType))return;
        State s=state(c);
        if(!s.configured){s.configured=true;
            // Tomcat order: -Dcatalina.config, then $CATALINA_BASE/conf; otherwise the defaults inside catalina.jar (never $CATALINA_HOME/conf).
            List<File> sources=new ArrayList<File>();String config=r.property("catalina.config");
            if(config!=null){String local=config.startsWith("file://")?config.substring(7):config.startsWith("file:")?config.substring(5):config.indexOf(':')>0?null:config;
                String resolved=local==null?null:ProcessLaunch.path(local,c.launch.cwd);if(resolved!=null)sources.add(new File(resolved));else s.propertyFailure="catalina.config remoto ou não local não foi lido";}
            if(c.launch.serverBase!=null)sources.add(new File(c.launch.serverBase,"conf/catalina.properties"));
            if(s.propertyFailure==null)for(File raw:sources){
                File f=scan.linkedPath(raw);
                if(f==null){s.propertyFailure="catalina.properties em caminho excluído ou não acessível";break;}
                if(!f.exists())continue;
                if(!f.isFile()||!scan.visitLinked(f)){s.propertyFailure="catalina.properties indisponível";break;}
                String text=scan.read(f,ServerDataSources.CONFIG_LIMIT);
                if(text==null){s.propertyFailure="catalina.properties excede os limites ou não pode ser lido";break;}
                try{Properties p=new Properties();p.load(new ByteArrayInputStream(text.getBytes("ISO-8859-1")));
                    if(p.size()>1024)s.propertyFailure="Limite de propriedades do catalina.properties atingido";
                    for(Object key:p.keySet()){String k=key.toString(),v=p.getProperty(k);if(k.length()<=128&&!JdbcEndpoint.Resolver.secret(k)&&v.length()<=8192&&s.properties.size()<1024)s.properties.put(k,v);}
                }catch(Exception e){s.propertyFailure="catalina.properties inválido";}
                break;
            }
            if(c.launch.serverBase!=null)s.properties.put("catalina.base",c.launch.serverBase);
            if(c.launch.serverHome!=null)s.properties.put("catalina.home",c.launch.serverHome);
        }
        r.serverProperties.putAll(s.properties);r.serverPropertySource="catalina.properties";
        String source=r.property("org.apache.tomcat.util.digester.PROPERTY_SOURCE");
        String version=c.server==null?null:AssessmentScan.clean(c.server.get("version"));
        boolean supported=false;if(version!=null)try{String[] parts=version.split("[.]");int major=Integer.parseInt(parts[0]),minor=parts.length>1?Integer.parseInt(parts[1]):0;supported=major>8||major==8&&minor>=5;}catch(NumberFormatException ignored){}
        r.environmentPropertySource=false;if(supported&&source!=null)for(String item:source.split(","))if(item.trim().equals("org.apache.tomcat.util.digester.EnvironmentPropertySource"))r.environmentPropertySource=true;
    }
    void discover(AssessmentScan.ProcessContext c){
        State s=state(c);if(s.discovered)return;s.discovered=true;
        if(c.launch.serverBase==null)return;
        if(owner.cache.size()>=ServerDataSources.MAX_CONFIGS){scan.warn("Limite de arquivos de configuração JDBC atingido");return;}
        if(owner.cachedRows>=512||scan.datasources.size()>=512){scan.warn("Limite de datasources atingido");return;}
        for(String root:new String[]{c.launch.serverHome,c.launch.serverBase})if(root!=null)for(String location:new String[]{"lib/catalina.jar","server/lib/catalina.jar"}){File catalina=scan.linkedPath(new File(root,location));if(catalina!=null&&catalina.isFile())scan.linkedFile(catalina,c);}
        JdbcEndpoint.Resolver values=owner.resolver(c);configureResolver(c,values);File base=new File(c.launch.serverBase);
        owner.inspectRequired(new File(base,"conf/server.xml"),"Tomcat",c,null);
        owner.inspect(new File(base,"conf/context.xml"),"Tomcat",c);
        Layout layout=layout(new File(base,"conf/server.xml"),values);
        if(layout.hosts.isEmpty())layout.hosts.add(new Host("Catalina","localhost","webapps",null));
        int contexts=0;
        for(Host host:layout.hosts){
            File appBase=path(host.appBase,base,values);if(appBase==null)continue;
            File descriptorBase=host.xmlBase==null?new File(base,"conf/"+host.engine+"/"+host.name):path(host.xmlBase,base,values);
            if(descriptorBase!=null){File safe=scan.linkedPath(descriptorBase);if(safe!=null&&safe.isDirectory()&&scan.visitLinked(safe))for(File raw:owner.children(safe)){
                if(!raw.getName().endsWith(".xml")&&!raw.getName().equals("context.xml.default"))continue;
                if(++contexts>64){scan.note("Limite de 64 contextos Tomcat atingido");break;}
                File descriptor=scan.linkedPath(raw);if(descriptor==null||!descriptor.equals(raw.getAbsoluteFile()))continue;
                Layout item=layout(descriptor,values);String appPath=contextName(raw.getName());
                File doc=null;if(!item.contexts.isEmpty())doc=path(item.contexts.get(0).docBase,appBase,values);
                if(doc==null){String name=raw.getName().replaceFirst("[.]xml$","");File exploded=new File(appBase,name);doc=exploded.isDirectory()?exploded:new File(appBase,name+".war");}
                AssessmentScan.ProcessContext app=application(doc,appPath,c,s);
                owner.inspect(descriptor,"Tomcat",app==null?c:app);
            }}
            for(Context item:host.contexts){if(++contexts>64)break;File doc=path(item.docBase,appBase,values);if(doc!=null){AssessmentScan.ProcessContext app=application(doc,item.path==null?contextName(doc.getName()):item.path,c,s);if(app!=null&&app.application!=null&&item.path!=null)associateInline(c,app,host.name,item.path,new File(base,"conf/server.xml"));}}
            File safe=scan.linkedPath(appBase);if(safe==null||!safe.isDirectory()||!scan.visitLinked(safe))continue;
            for(File raw:owner.children(safe)){
                if(s.applications.size()>=64||!scan.available())break;
                File canonical=scan.linkedPath(raw);if(canonical==null||!canonical.equals(raw.getAbsoluteFile()))continue;
                if(raw.isDirectory()||raw.getName().toLowerCase(Locale.ENGLISH).endsWith(".war")){
                    if(raw.isFile()&&new File(safe,raw.getName().substring(0,raw.getName().length()-4)).isDirectory())continue;
                    application(raw,contextName(raw.getName()),c,s);
                }
            }
        }
        for(String root:new String[]{c.launch.serverBase,c.launch.serverHome})if(root!=null){
            for(String dir:new String[]{"conf","lib","webapps","work"}){File raw=new File(root,dir),safe=scan.linkedPath(raw);if(safe!=null&&safe.exists()&&!safe.equals(raw.getAbsoluteFile())){scan.visitLinked(safe);indication(c,"instalado por pacote (diretórios ligados por links simbólicos)");}}
            File custom=scan.linkedPath(new File(root,"lib/org/apache/catalina/util/ServerInfo.properties"));
            if(custom!=null&&custom.isFile()&&scan.visitLinked(custom)){String text=scan.read(custom,ServerDataSources.SMALL_LIMIT);if(text!=null)indication(c,"server.info personalizado (versão ocultada nas respostas HTTP)");}
            File lib=scan.linkedPath(new File(root,"lib"));if(lib!=null&&lib.isDirectory()&&scan.visitLinked(lib))for(File f:owner.children(lib))if(ArchiveMetadata.isKnownCandidate(f.getName())){File safe=scan.linkedPath(f);if(safe!=null&&safe.isFile())scan.linkedFile(safe,c);}
        }
    }
    void associateInline(AssessmentScan.ProcessContext original,AssessmentScan.ProcessContext app,String host,String path,File config){
        File safe=scan.linkedPath(config);if(safe==null)return;
        for(Object raw:scan.datasources){Map row=(Map)raw;String scope=AssessmentScan.clean(row.get("configurationContext"));if(!safe.getPath().equals(row.get("configurationSource"))||scope==null||!scope.endsWith("host:"+host+"/context:"+path))continue;
            if(row.get("associations") instanceof List)for(Object item:(List)row.get("associations")){Map a=(Map)item;if(Long.valueOf(original.pid).equals(a.get("pid"))){a.put("applicationId",app.application.get("id"));a.put("applicationName",app.application.get("name"));}}
            if(row.get("associations") instanceof List&&((List)row.get("associations")).size()==1){row.put("applicationId",app.application.get("id"));row.put("applicationName",app.application.get("name"));}
        }
    }
    void indication(AssessmentScan.ProcessContext c,String text){c.process.put("tomcatInstallationEvidence",text);if(c.server!=null)c.server.put("tomcatInstallationEvidence",text);scan.note(text);}
    static String contextName(String name){name=name.replaceFirst("(?i)[.](xml|war)$","");return "ROOT".equals(name)?"/":"/"+name.replace('#','/');}
    File path(String value,File base,JdbcEndpoint.Resolver resolver){if(value==null)return null;String expanded=resolver.expand(value,new ArrayList<String>()),p=expanded==null?null:ProcessLaunch.path(expanded,base);return p==null?null:scan.linkedPath(new File(p));}
    AssessmentScan.ProcessContext application(File raw,String name,AssessmentScan.ProcessContext c,State s){
        File app=scan.linkedPath(raw);if(app==null||!app.exists()||!scan.visitLinked(app))return null;
        if(!app.isDirectory()&&!app.getName().toLowerCase(Locale.ENGLISH).endsWith(".war"))return null;
        Map model=scan.applicationsByPath.get(app.getPath());
        if(model==null&&scan.applications.size()<500){model=AssessmentScan.map("id",AssessmentScan.id("app",scan.hostId+app.getPath()),"hostId",scan.hostId,"name",name,"path",app.getPath(),"type",app.isDirectory()?"Web application":"WAR","applicationType","Tomcat","contextPath",name,"pid",Long.valueOf(c.pid),"scanScope","process-linked","evidence","Contexto configurado no Tomcat; estado da aplicação não confirmado","observedAt",scan.timestamp);if(c.server!=null)model.put("serverName",c.server.get("name"));scan.applications.add(model);scan.applicationsByPath.put(app.getPath(),model);}
        AssessmentScan.ProcessContext ctx=new AssessmentScan.ProcessContext(c.launch,c.pid,c.javaHome,c.process,model);ctx.server=c.server;ctx.mapping=c.mapping;
        if(!s.seenApps.add(app.getPath()))return ctx;if(s.applications.size()>=64){scan.note("Limite de 64 aplicações Tomcat atingido");return ctx;}s.applications.add(app);
        if(app.isDirectory()){owner.inspect(new File(app,"META-INF/context.xml"),"Tomcat",ctx);scan.linkedTree(new File(app,"WEB-INF/lib"),0,ctx,false);}
        else{scan.linkedFile(app,ctx);owner.load(app,"META-INF/context.xml","Tomcat",ctx,null);}
        return ctx;
    }
    static final class Host { final String engine,name,appBase,xmlBase;final List<Context>contexts=new ArrayList<Context>();Host(String e,String n,String a,String x){engine=e;name=n;appBase=a;xmlBase=x;} }
    static final class Context {String docBase,path;Context(String d,String p){docBase=d;path=p;} }
    static final class Layout {final List<Host>hosts=new ArrayList<Host>();final List<Context>contexts=new ArrayList<Context>();}
    final Map<String,Layout> layouts=new HashMap<String,Layout>();
    Layout layout(File raw,final JdbcEndpoint.Resolver resolver){
        File file=scan.linkedPath(raw);String key=(file==null?raw:file).getPath()+"\u0000"+owner.resolverPid;
        Layout cached=layouts.get(key);if(cached!=null)return cached;final Layout result=new Layout();if(layouts.size()<256)layouts.put(key,result);
        if(file==null||!file.isFile()||file.length()>ServerDataSources.CONFIG_LIMIT||!scan.visitLinked(file))return result;String xml=scan.read(file,ServerDataSources.CONFIG_LIMIT);if(xml==null)return result;
        try{ServerDataSources.parseXml(xml,new DefaultHandler(){String engine="Catalina";Host host;int depth,elements;
            String value(Attributes a,String k){String s=a.getValue(k);return s!=null&&s.length()<=4096?s:null;}
            public void startElement(String uri,String local,String q,Attributes a)throws SAXException{if(++depth>64||++elements>12000)throw new SAXException("limit");String n=local.length()>0?local:q;
                if(n.equals("Engine")){String v=ServerDataSources.resourceName(value(a,"name"));if(v!=null&&!v.contains("/"))engine=v;}
                if(n.equals("Host")){String name=ServerDataSources.resourceName(value(a,"name"));if(name==null||name.contains("/"))name="localhost";host=new Host(engine,name,SpringDataSource.first(value(a,"appBase"),"webapps"),value(a,"xmlBase"));if(result.hosts.size()<8)result.hosts.add(host);}
                if(n.equals("Context")&&result.contexts.size()<64){Context c=new Context(value(a,"docBase"),ServerDataSources.resourceName(value(a,"path")));result.contexts.add(c);if(host!=null)host.contexts.add(c);}
            }
            public void endElement(String uri,String local,String q){String n=local.length()>0?local:q;if(n.equals("Host"))host=null;depth--;}
        });}catch(Exception e){scan.note("Contextos Tomcat não puderam ser examinados dentro dos limites");}
        return result;
    }
    void resolve(JdbcDriverResolver.Search search,Map row){
        State s=state(search.context);JdbcEndpoint.Resolver resolver=owner.resolver(search.context);
        if(s.propertyFailure!=null)search.fail(s.propertyFailure);
        String common=resolver.property("common.loader");
        if(common==null)common="\"${catalina.base}/lib\",\"${catalina.base}/lib/*.jar\",\"${catalina.home}/lib\",\"${catalina.home}/lib/*.jar\"";
        loaders(common,"pasta do common.loader",search,resolver);
        String version=search.context.server==null?null:AssessmentScan.clean(search.context.server.get("version"));
        boolean legacy=version!=null&&(version.startsWith("5.")||version.startsWith("6."));
        if(legacy)for(String root:new String[]{search.context.launch.serverBase,search.context.launch.serverHome})if(root!=null)for(String dir:new String[]{"common/lib","shared/lib"})search.group(search.directory(new File(root,dir),dir),dir);
        if(legacy){loaders(resolver.property("server.loader"),"server.loader",search,resolver);loaders(resolver.property("shared.loader"),"shared.loader",search,resolver);}
        int start=search.candidates.size();if(search.winner==null&&!search.terminal&&search.reason==null)for(File app:s.applications)applicationCandidates(app,search);
        if(search.winner==null&&!search.terminal&&search.reason==null&&search.candidates.size()>start){
            // Informational evidence must not change NOT_FOUND into INCOMPLETE.
            row.put("tomcatApplicationOnlyDriver",Boolean.TRUE);
        }
    }
    void loaders(String text,String source,JdbcDriverResolver.Search search,JdbcEndpoint.Resolver resolver){
        if(text==null||text.trim().length()==0)return;
        List<String> entries=new ArrayList<String>();StringBuilder part=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(ch=='"')quoted=!quoted;else if(ch==','&&!quoted){entries.add(part.toString().trim());part.setLength(0);}else part.append(ch);}
        entries.add(part.toString().trim());if(quoted||entries.size()>64){search.fail("common.loader inválido ou acima do limite");return;}
        for(String entry:entries){if(entry.length()==0)continue;File file=path(entry,search.context.launch.cwd,resolver);if(file==null){search.fail("Entrada de "+source+" excluída ou não resolvida");continue;}
            if(file.getName().equals("*.jar"))search.group(search.directory(file.getParentFile(),source),source);else search.walk(file,source,0,null,false);
        }
    }
    void applicationCandidates(File app,JdbcDriverResolver.Search search){
        if(search.candidates.size()>=JdbcDriverResolver.MAX_CANDIDATES||!scan.available())return;
        if(app.isDirectory()){
            File lib=scan.linkedPath(new File(app,"WEB-INF/lib"));if(lib==null||!lib.isDirectory()||!scan.visitLinked(lib))return;
            for(File f:owner.children(lib))if(f.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar"))search.walk(f,"aplicação; não usado pelo datasource do Tomcat",0,null,true);
        }else{
            ZipFile zip=null;
            try{scan.archiveMetadata.preflight(app);zip=new ZipFile(app);Enumeration<? extends ZipEntry> entries=zip.entries();int n=0,jars=0;
                while(entries.hasMoreElements()){if(++n>ArchiveMetadata.ENTRY_LIMIT||!scan.available())break;ZipEntry e=entries.nextElement();String name=e.getName();if(!name.startsWith("WEB-INF/lib/")||!name.endsWith(".jar")||!ArchiveMetadata.safeName(name))continue;
                    if(++jars>32||e.getSize()>WebLogicApplicationDrivers.NESTED_LIMIT||e.getSize()<0)break;
                    InputStream input=zip.getInputStream(e);byte[] data;try{data=scan.archiveMetadata.read(input,WebLogicApplicationDrivers.NESTED_LIMIT);}finally{input.close();}
                    JdbcDriverResolver.Archive a=WebLogicApplicationDrivers.nested(scan,data,search.driver);
                    if(a.classes.contains(search.driver)&&search.candidates.size()<JdbcDriverResolver.MAX_CANDIDATES)search.candidates.add(AssessmentScan.map("path",app.getPath()+"!/"+name,"version",a.versions.size()==1?a.versions.iterator().next():null,"versionSource",a.versionSource,"source","aplicação; não usado pelo datasource do Tomcat","order",Integer.valueOf(++search.order),"open",null,"loaded",Boolean.FALSE,"onClasspath",Boolean.FALSE));
                }
            }catch(IOException e){/* Application libraries are informational, never decisive for a common resource. */}finally{if(zip!=null)try{zip.close();}catch(IOException ignored){}}
        }
    }
}
