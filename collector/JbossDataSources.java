package assessment;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Passive JBoss Modules/configuration strategy. Resolution decisions stay in JdbcDriverResolver. */
final class JbossDataSources {
    static final int ROOTS=8, LAYERS=16, ADDONS=32, OVERLAYS=32, RESOURCES=32, DEPLOYMENTS=128;
    final ServerDataSources owner; final AssessmentScan scan;
    final Map<Long,State> states=new LinkedHashMap<Long,State>();
    final Map<String,Module> modules=new LinkedHashMap<String,Module>();
    final Map<String,List<String>> serviceCache=new LinkedHashMap<String,List<String>>();
    final Set<String> applications=new HashSet<String>();
    static final class State {
        File base, config, data; String server, group, profile, reason; boolean domain, managed, rootsTruncated;
        final Map<String,Deployment> deployments=new LinkedHashMap<String,Deployment>();
        final Set<String> assigned=new HashSet<String>(), disabled=new HashSet<String>();
    }
    static final class Deployment {String name,runtime,sha,path,relative;boolean enabled=true;}
    static final class Module {
        File descriptor; String source,reason;
        final List<File> resources=new ArrayList<File>(); final List<String> artifacts=new ArrayList<String>(); final List<Object> entries=new ArrayList<Object>();
    }
    JbossDataSources(ServerDataSources owner){this.owner=owner;scan=owner.scan;}
    String value(AssessmentScan.ProcessContext c,String key){return owner.resolver(c).property(key);}
    String expanded(AssessmentScan.ProcessContext c,String text){return owner.resolver(c).expand(text,new ArrayList<String>());}
    File path(String text,File base){String p=ProcessLaunch.path(text,base);return p==null?null:new File(p);}
    String read(File raw,int limit){File file=scan.linkedPath(raw);return file!=null&&file.isFile()&&scan.visitLinked(file)?scan.read(file,limit):null;}
    void observation(AssessmentScan.ProcessContext c,String text){scan.note(text);c.process.put("datasourceObservation",text);if(c.server!=null)c.server.put("datasourceObservation",text);}
    State state(AssessmentScan.ProcessContext c){State s=states.get(Long.valueOf(c.pid));if(s==null){s=new State();if(states.size()<256)states.put(Long.valueOf(c.pid),s);}return s;}
    void discover(AssessmentScan.ProcessContext c){
        State s=state(c);ProcessLaunch l=c.launch;
        if(l.moduleName==null&&"org.jboss.Main".equals(l.mainClass)){owner.legacy(c);return;}
        s.domain="org.jboss.as.server".equals(l.moduleName)||"org.jboss.as.host-controller".equals(l.moduleName);
        if(s.domain){domain(c,s);return;}
        if(!"org.jboss.as.standalone".equals(l.moduleName))return;
        s.base=ServerDataSources.standaloneBase(l);s.data=path(value(c,"jboss.server.data.dir"),null);if(s.data==null&&s.base!=null)s.data=new File(s.base,"data");
        File directory=l.serverConfigDirectory==null?s.base==null?null:new File(s.base,"configuration"):new File(l.serverConfigDirectory);
        s.config=path(l.serverConfigFile==null?"standalone.xml":l.serverConfigFile,directory);
        if(s.config==null){scan.note("Caminho da configuração JDBC do JBoss não resolvido");return;}
        metadata(c,s);owner.inspectRequired(s.config,"JBoss",c,null);deployments(c,s);
    }
    void domain(AssessmentScan.ProcessContext c,State s){
        ProcessLaunch l=c.launch;JdbcEndpoint.Resolver r=owner.resolver(c);s.managed="org.jboss.as.server".equals(l.moduleName);
        r.arguments=r.lines("cmdline",r.arguments);String base=value(c,"jboss.domain.base.dir");s.server=l.serverName;
        for(String a:r.arguments)if(a.startsWith("-D[Server:")&&a.endsWith("]"))s.server=ProcessLaunch.label(a.substring(10,a.length()-1));
        String serverData=SpringDataSource.first(value(c,"jboss.server.data.dir"),value(c,"jboss.server.log.dir"));
        if(serverData!=null){File server=new File(serverData).getParentFile(),servers=server==null?null:server.getParentFile();if(servers!=null&&servers.getName().equals("servers")){if(base==null)base=servers.getParent();if(s.server==null)s.server=ProcessLaunch.label(server.getName());}}
        s.base=path(base,null);if(s.base==null&&l.serverHome!=null)s.base=new File(l.serverHome,"domain");
        s.data=path(value(c,"jboss.server.data.dir"),null);if(s.data==null&&s.base!=null)s.data=new File(s.base,"data");
        File directory=path(value(c,"jboss.domain.config.dir"),null);if(directory==null&&s.base!=null)directory=new File(s.base,"configuration");
        String domain=SpringDataSource.first(value(c,"jboss.domain.default.config"),"domain.xml"),host=SpringDataSource.first(value(c,"jboss.host.default.config"),"host.xml"),dc=null;
        for(int i=0;i<r.arguments.length;i++){String a=r.arguments[i];
            if((a.equals("--domain-config")||a.equals("-c"))&&i+1<r.arguments.length)domain=r.arguments[++i];else if(a.startsWith("--domain-config="))domain=a.substring(16);
            else if(a.equals("--host-config")&&i+1<r.arguments.length)host=r.arguments[++i];else if(a.startsWith("--host-config="))host=a.substring(14);
            else if((a.equals("--master-address")||a.equals("--primary-address"))&&i+1<r.arguments.length)dc=r.arguments[++i];else if(a.startsWith("--master-address="))dc=a.substring(17);else if(a.startsWith("--primary-address="))dc=a.substring(18);
        }
        File hostFile=path(host,directory);String hostXml=hostFile==null?null:read(hostFile,ServerDataSources.CONFIG_LIMIT);
        if(hostXml!=null)try{Host h=new Host(scan,s.server);ServerDataSources.parseXml(hostXml,h);s.group=h.group;if(dc==null)dc=expanded(c,h.dc);}catch(Exception e){scan.note("host.xml do JBoss inválido ou fora dos limites");}
        s.config=path(domain,directory);File safe=s.config==null?null:scan.linkedPath(s.config);
        if(safe==null||!safe.isFile()){
            File cached=directory==null?null:scan.linkedPath(new File(directory,"domain.cached-remote.xml"));
            if(cached!=null&&cached.isFile()){s.config=cached;observation(c,"Configuração do domínio: cópia local do domain.xml do controlador");}
            else{String hostName=dc!=null&&dc.matches("[A-Za-z0-9.:-]{1,253}")?" ("+dc+")":"";observation(c,"Datasources deste servidor estão no controlador do domínio"+hostName+"; colete a máquina do controlador para inventariá-los");return;}
        }
        metadata(c,s);
        if(s.managed&&s.profile==null)scan.note("JBoss managed server profile was not resolved; all datasource profiles of the domain configuration were inventoried");
        owner.inspectRequired(s.config,"JBoss",c,s.profile);deployments(c,s);
    }
    static final class Host extends DefaultHandler {
        final AssessmentScan scan;final String server;String group,dc;int depth,count;
        Host(AssessmentScan s,String name){scan=s;server=name;}
        public void startElement(String uri,String local,String q,Attributes a)throws SAXException{String n=local.length()>0?local:q;if(++depth>64||++count>12000||!scan.available())throw new SAXException("limit");if(n.equals("server")&&server!=null&&server.equals(a.getValue("name")))group=ServerDataSources.resourceName(a.getValue("group"));if(n.equals("remote")){String v=a.getValue("host");if(v!=null&&v.length()<=512)dc=v;}}
        public void endElement(String u,String l,String q){depth--;}
    }
    void metadata(AssessmentScan.ProcessContext c,State s){String xml=read(s.config,ServerDataSources.CONFIG_LIMIT);if(xml==null){s.reason="Configuração do deployment não legível";return;}try{ServerDataSources.parseXml(xml,new Metadata(s,scan));}catch(Exception e){s.reason="Configuração do deployment inválida ou fora dos limites";s.deployments.clear();}}
    static final class Metadata extends DefaultHandler {
        final State state;final AssessmentScan scan;final List<String> stack=new ArrayList<String>();Deployment current;String group;int count;
        Metadata(State s,AssessmentScan scan){state=s;this.scan=scan;}
        public void startElement(String u,String l,String q,Attributes a)throws SAXException{String n=l.length()>0?l:q;if(stack.size()>64||++count>12000||!scan.available())throw new SAXException("limit");String parent=stack.isEmpty()?null:stack.get(stack.size()-1);stack.add(n);
            if(n.equals("server-group")){group=ServerDataSources.resourceName(a.getValue("name"));if(group!=null&&group.equals(state.group))state.profile=ServerDataSources.resourceName(a.getValue("profile"));}
            if(n.equals("deployment")&&"deployments".equals(parent)){
                String name=ServerDataSources.resourceName(a.getValue("name"));if(name==null)return;
                if(group!=null){if(group.equals(state.group)){state.assigned.add(name);if("false".equals(a.getValue("enabled")))state.disabled.add(name);}return;}
                if(state.deployments.size()>=DEPLOYMENTS)throw new SAXException("limit");current=new Deployment();current.name=name;current.runtime=ServerDataSources.resourceName(a.getValue("runtime-name"));current.enabled=!"false".equals(a.getValue("enabled"));state.deployments.put(name,current);
            }else if(current!=null&&n.equals("content")){String sha=a.getValue("sha1");if(sha!=null&&sha.matches("[a-fA-F0-9]{40}"))current.sha=sha.toLowerCase(Locale.ENGLISH);}
            else if(current!=null&&(n.equals("fs-archive")||n.equals("fs-exploded"))){String p=a.getValue("path");if(p!=null&&p.length()<=4096)current.path=p;String r=a.getValue("relative-to");if(r!=null&&r.matches("[A-Za-z0-9_.-]{1,128}"))current.relative=r;}
        }
        public void endElement(String u,String l,String q){String n=l.length()>0?l:q;if(n.equals("deployment"))current=null;if(n.equals("server-group"))group=null;stack.remove(stack.size()-1);}
    }
    boolean enabled(State s,Deployment d){return d.enabled&&(!s.domain||!s.managed||s.assigned.contains(d.name)&&!s.disabled.contains(d.name));}
    List<File> deploymentFiles(AssessmentScan.ProcessContext c,State s,Deployment d){
        List<File> out=new ArrayList<File>();if(d.path!=null){String text=expanded(c,d.path);File relative=null;if(d.relative!=null){relative=path(value(c,d.relative),null);if(relative==null&&d.relative.equals("jboss.server.base.dir"))relative=s.base;if(relative==null&&d.relative.equals("jboss.server.data.dir"))relative=s.data;if(relative==null&&d.relative.equals("jboss.home.dir"))relative=path(c.launch.serverHome,null);}File f=path(text,relative);if(f!=null)out.add(f);}
        if(d.sha!=null){String suffix="content/"+d.sha.substring(0,2)+"/"+d.sha.substring(2)+"/content";if(s.data!=null)out.add(new File(s.data,suffix));if(s.domain&&s.base!=null){if(s.server!=null)out.add(new File(s.base,"servers/"+s.server+"/data/"+suffix));out.add(new File(s.base,"data/"+suffix));}}
        return out;
    }
    List<File> roots(AssessmentScan.ProcessContext c){List<String> paths=new ArrayList<String>(c.launch.modulePaths);if(paths.isEmpty()){String env=owner.resolver(c).environment("JBOSS_MODULEPATH");if(env!=null)ProcessLaunch.paths(paths,env,c.launch.cwd);}if(paths.isEmpty()&&c.launch.serverHome!=null)paths.add(new File(c.launch.serverHome,"modules").getPath());state(c).rootsTruncated=paths.size()>ROOTS;List<File> out=new ArrayList<File>();for(String p:paths){if(out.size()>=ROOTS)break;out.add(new File(p));}return out;}
    List<Module> findModules(String name,AssessmentScan.ProcessContext c){
        List<Module> out=new ArrayList<Module>();int colon=name.indexOf(':');String id=colon<0?name:name.substring(0,colon),slot=colon<0?"main":name.substring(colon+1);
        if(!id.matches("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){0,15}")||!slot.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}")||slot.indexOf("..")>=0)return out;
        String suffix=id.replace('.','/')+"/"+slot;
        for(File raw:roots(c)){
            File root=scan.linkedPath(raw);if(root==null){Module bad=new Module();bad.reason="Module path excluído: "+raw;out.add(bad);continue;}
            collectModule(out,new File(root,suffix),"module-path");
            Properties props=new Properties();File config=new File(root,"layers.conf");String text=read(config,ServerDataSources.SMALL_LIMIT);if(text==null&&config.exists())problem(out,"layers.conf não legível ou fora dos limites: "+config);if(text!=null)try{props.load(new StringReader(text));}catch(Exception e){problem(out,"layers.conf inválido: "+config);}
            List<String> layers=new ArrayList<String>();boolean layerLimit=false;String configured=props.getProperty("layers","");for(String layer:configured.split(",")){layer=layer.trim();if(layer.length()==0)continue;if(!layer.matches("[A-Za-z0-9_-]{1,64}")){problem(out,"Nome de camada inválido em layers.conf");continue;}if(!layers.contains(layer)){if(layers.size()>=LAYERS){layerLimit=true;break;}layers.add(layer);}}if(!layers.contains("base")&&layers.size()<LAYERS)layers.add("base");
            for(String layer:layers)layer(out,new File(root,"system/layers/"+layer),suffix,"layer:"+layer);if(layerLimit)problem(out,"Limite de 16 camadas do JBoss atingido");
            File rawAddons=new File(root,"system/add-ons"),addons=scan.linkedPath(rawAddons);if(rawAddons.exists()&&(addons==null||!addons.canRead()))problem(out,"Add-ons do JBoss excluídos ou não legíveis");if(addons!=null&&addons.isDirectory()){int n=0;for(File addon:owner.children(addons)){if(++n>ADDONS){problem(out,"Limite de 32 add-ons do JBoss atingido");break;}if(addon.isDirectory()&&addon.getName().matches("[A-Za-z0-9_-]{1,64}"))layer(out,addon,suffix,"add-on:"+addon.getName());}}
        }if(state(c).rootsTruncated)problem(out,"Limite de 8 raízes do module path atingido");return out;
    }
    void problem(List<Module> out,String reason){Module m=new Module();m.reason=reason;out.add(m);}
    void layer(List<Module> out,File layer,String suffix,String source){File file=new File(layer,".overlays/.overlays");String text=read(file,ServerDataSources.SMALL_LIMIT);if(text==null&&file.exists())problem(out,"Lista de overlays não legível ou fora dos limites: "+file);if(text!=null){int n=0;for(String id:text.split("[\\r\\n]+")){id=id.trim();if(id.length()==0)continue;if(++n>OVERLAYS){problem(out,"Limite de 32 overlays do JBoss atingido");break;}if(id.matches("[A-Za-z0-9_.-]{1,128}")&&id.indexOf("..")<0)collectModule(out,new File(layer,".overlays/"+id+"/"+suffix),"overlay:"+id);else problem(out,"ID de overlay inválido");}}collectModule(out,new File(layer,suffix),source);}
    void collectModule(List<Module> out,File directory,String source){
        File file=scan.linkedPath(new File(directory,"module.xml"));if(file==null){Module bad=new Module();bad.reason="Módulo excluído ou inacessível: "+directory;out.add(bad);return;}if(!file.isFile())return;
        Module prior=modules.get(file.getPath());if(prior!=null){out.add(prior);return;}Module m=new Module();m.descriptor=file;m.source=source;out.add(m);if(modules.size()>=128){m.reason="Limite de descritores de módulos atingido";return;}modules.put(file.getPath(),m);
        String xml=read(file,ServerDataSources.SMALL_LIMIT);if(xml==null){m.reason="Descritor do módulo não legível: "+file;return;}
        try{ServerDataSources.parseXml(xml,new ModuleXml(m,scan));}catch(Exception e){m.resources.clear();m.artifacts.clear();m.entries.clear();m.reason="Descritor do módulo inválido ou fora dos limites: "+file;}
    }
    static final class ModuleXml extends DefaultHandler {
        final Module module;final AssessmentScan scan;int depth,count;String parent;
        ModuleXml(Module m,AssessmentScan s){module=m;scan=s;}
        public void startElement(String u,String l,String q,Attributes a)throws SAXException{String n=l.length()>0?l:q;if(++depth>64||++count>12000||!scan.available())throw new SAXException("limit");if(depth==1&&!n.equals("module"))throw new SAXException("root");if(depth==3&&"resources".equals(parent)){
            if(module.resources.size()+module.artifacts.size()>=RESOURCES)throw new SAXException("limit");
            String path=a.getValue("path");if(n.equals("resource-root")){
                if(path==null||path.length()>1024||path.indexOf("..")>=0||path.indexOf(':')>=0||path.indexOf('\\')>=0||ProcessLaunch.path(path,module.descriptor.getParentFile())==null)throw new SAXException("unsafe resource path");
                File resource=new File(ProcessLaunch.path(path,module.descriptor.getParentFile()));module.resources.add(resource);module.entries.add(resource);
            }
            String artifact=a.getValue("name");if(n.equals("artifact")&&artifact!=null&&artifact.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+()-]+(?::[A-Za-z0-9_.-]+)?")){module.artifacts.add(artifact);module.entries.add(artifact);}
        }parent=n;}
        public void endElement(String u,String l,String q){depth--;parent=depth==2?"resources":null;}
    }
    File firstJar(String module,AssessmentScan.ProcessContext c){for(Module m:findModules(module,c)){if(m.reason!=null)return null;for(File f:m.resources)if(f.getName().endsWith(".jar"))return scan.linkedPath(f);break;}return null;}
    List<String> services(File raw){
        File f=scan.linkedPath(raw);if(f==null||!f.isFile()||!scan.visitLinked(f)||f.length()>ArchiveMetadata.FILE_LIMIT)return Collections.emptyList();List<String> prior=serviceCache.get(f.getPath());if(prior!=null)return prior;
        List<String> out=new ArrayList<String>();if(serviceCache.size()>=128)return out;serviceCache.put(f.getPath(),out);
        String text=owner.entry(f,"META-INF/services/java.sql.Driver",8192);if(text!=null)for(String line:text.split("[\\r\\n]+")){int hash=line.indexOf('#');if(hash>=0)line=line.substring(0,hash);String name=SpringDataSource.validDriver(line.trim());if(name!=null&&!out.contains(name)&&out.size()<16)out.add(name);}return out;
    }
    String prepare(AssessmentScan.ProcessContext c,Map row){
        String driver=SpringDataSource.validDriver(AssessmentScan.clean(row.get("driverName")));if(driver!=null&&row.get("driverClassEvidence")==null)return driver;if(row.get("driverClassEvidence")!=null){row.remove("driverName");row.remove("driverClassEvidence");}if(!Boolean.TRUE.equals(scan.settings.get("inspectArchives")))return null;
        List<File> files=new ArrayList<File>();Object module=row.get("driverModule"),deployment=row.get("driverDeployment");
        if(module instanceof String){for(Module m:findModules((String)module,c)){if(m.reason!=null)break;files.addAll(m.resources);for(String artifact:m.artifacts){String[] parts=artifact.split(":");if(ArchiveMetadata.jdbcArtifact(parts[0],parts[1])){String inferred=provider(parts[1]);if(inferred!=null){row.put("driverName",inferred);row.put("driverClassEvidence","Classe inferida das coordenadas JDBC declaradas no módulo");return inferred;}}}break;}}
        if(deployment instanceof String){State s=state(c);Deployment d=findDeployment(s,(String)deployment);if(d!=null&&enabled(s,d))files.addAll(deploymentFiles(c,s,d));if(d==null&&s.base!=null)files.add(new File(s.base,"deployments/"+deployment));}
        for(File f:files){List<String> names=services(f);if(names.size()==1){row.put("driverName",names.get(0));row.put("driverClassEvidence","META-INF/services/java.sql.Driver");return names.get(0);}}
        return null;
    }
    static String provider(String artifact){String a=artifact.toLowerCase(Locale.ENGLISH);if(a.startsWith("ojdbc"))return "oracle.jdbc.OracleDriver";if(a.equals("postgresql"))return "org.postgresql.Driver";if(a.startsWith("mssql-jdbc"))return "com.microsoft.sqlserver.jdbc.SQLServerDriver";if(a.equals("mysql-connector-j"))return "com.mysql.cj.jdbc.Driver";return null;}
    Deployment findDeployment(State s,String name){Deployment d=s.deployments.get(name);if(d==null)for(Deployment item:s.deployments.values())if(name.equals(item.runtime)){d=item;break;}return d;}
    void resolve(JdbcDriverResolver.Search search,Map row){
        AssessmentScan.ProcessContext c=search.context;if(row.get("driverName")==null)search.notFoundReason="Classe do driver não declarada e serviço java.sql.Driver não encontrado";String module=AssessmentScan.clean(row.get("driverModule")),deployment=AssessmentScan.clean(row.get("driverDeployment"));
        if(module!=null){boolean selected=false;for(Module m:findModules(module,c)){
            if(m.reason!=null){if(!selected)search.fail(m.reason);continue;}boolean disk=selected;selected=true;
            for(Object resource:m.entries){
                if(resource instanceof File){File f=(File)resource;if(!disk&&f.isFile()&&f.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar"))scan.linkedFile(f,c);search.walk(f,m.source,0,null,disk);}
                else {String artifact=(String)resource;String[] p=artifact.split(":");if(!ArchiveMetadata.jdbcArtifact(p[0],p[1])||JdbcDriverResolver.version(p[2])==null)continue;String declaredProvider=provider(p[1]);String declaredDatabase=declaredProvider==null?null:SpringDataSource.driverDatabase(declaredProvider),actualDatabase=SpringDataSource.driverDatabase(AssessmentScan.clean(row.get("driverName")));if(declaredDatabase==null||actualDatabase!=null&&!actualDatabase.equals(declaredDatabase))continue;
                    Map candidate=AssessmentScan.map("path",m.descriptor.getPath()+"#artifact:"+artifact,"version",p[2],"versionSource","module-artifact","source",m.source,"order",Integer.valueOf(++search.order),"open",null,"loaded",Boolean.FALSE,"onClasspath",Boolean.valueOf(!disk));
                    if(search.candidates.size()<JdbcDriverResolver.MAX_CANDIDATES){search.candidates.add(candidate);if(!disk&&search.winner==null&&search.reason==null&&!search.terminal){search.choose(Collections.singletonList(candidate));search.declared=true;search.winnerReason="Versão declarada no artefato JDBC do módulo; arquivo e classe não verificados; nenhum download efetuado";}}
                }
            }
        }return;}
        if(deployment!=null){State s=state(c);Deployment d=findDeployment(s,deployment);if(d!=null&&!enabled(s,d)){search.notFoundReason="Deployment do driver desativado/não atribuído";return;}if(s.reason!=null){search.fail(s.reason);return;}
            if(d!=null){for(File f:deploymentFiles(c,s,d))search.walk(f,d.sha==null?"deployment não gerenciado":"repositório de conteúdo",0,null,false);}
            if((d==null||d.sha==null&&d.path==null)&&s.base!=null)search.walk(new File(s.base,"deployments/"+deployment),"deployment",0,null,false);
            return;
        }
        if(c.launch.moduleName==null&&"org.jboss.Main".equals(c.launch.mainClass)){
            String home=c.launch.serverHome;String server=state(c).base==null?value(c,"jboss.server.home.dir"):state(c).base.getPath();if(server==null&&home!=null){String base=SpringDataSource.first(value(c,"jboss.server.base.dir"),new File(home,"server").getPath());String config=c.launch.serverName==null?"default":c.launch.serverName;server=new File(base,config).getPath();}
            if(server!=null)search.group(search.directory(new File(server,"lib"),"server/lib"),"server/lib");if(home!=null){search.group(search.directory(new File(home,"common/lib"),"common/lib"),"common/lib");search.group(search.directory(new File(home,"lib"),"lib"),"lib");}
        }
    }
    void deployments(AssessmentScan.ProcessContext c,State s){
        Set<String> seen=new HashSet<String>();for(Deployment d:s.deployments.values())if(enabled(s,d))for(File f:deploymentFiles(c,s,d))if(f.exists()){application(f,SpringDataSource.first(d.runtime,d.name),c);seen.add(f.getPath());break;}
        if(s.base==null)return;File dir=scan.linkedPath(new File(s.base,"deployments"));if(dir==null||!dir.isDirectory()||!scan.visitLinked(dir))return;
        int n=0;for(File f:owner.children(dir)){if(++n>DEPLOYMENTS||!scan.available())break;String name=f.getName();Deployment declared=findDeployment(s,name);if(declared!=null&&!enabled(s,declared))continue;if(seen.contains(f.getPath()))continue;
            if(name.endsWith("-ds.xml")&&f.isFile())owner.inspect(f,"JBossDeployment",c);else if(name.matches("(?i).*[.](war|ear)")&&(f.isFile()||f.isDirectory()))application(f,name,c);
        }
    }
    void application(File raw,String name,AssessmentScan.ProcessContext c){
        if(!name.matches("(?i).*[.](war|ear)"))return;File f=scan.linkedPath(raw);if(f==null||!scan.visitLinked(f)||!applications.add(c.pid+":"+f.getPath())||scan.applications.size()>=500)return;
        String appName=ServerDataSources.resourceName(name);if(appName==null)return;String key=f.getPath()+":"+c.pid;Map app=scan.applicationsByPath.get(key);if(app==null){app=AssessmentScan.map("id",AssessmentScan.id("app",scan.hostId+key),"hostId",scan.hostId,"name",appName,"path",f.getPath(),"type",name.toLowerCase(Locale.ENGLISH).endsWith(".war")?"WAR":"EAR","pid",Long.valueOf(c.pid),"scanScope","process-linked","state","CONFIGURED","evidence","Deployment configurado no servidor observado; execução não verificada","observedAt",scan.timestamp);if(c.server!=null)app.put("serverName",c.server.get("name"));scan.applications.add(app);scan.applicationsByPath.put(key,app);}
        AssessmentScan.ProcessContext deployed=new AssessmentScan.ProcessContext(c.launch,c.pid,c.javaHome,c.process,app);deployed.server=c.server;
        if(f.isDirectory()){for(String dir:new String[]{"WEB-INF","META-INF"}){File folder=scan.linkedPath(new File(f,dir));if(folder==null||!folder.isDirectory()||!scan.visitLinked(folder))continue;for(File xml:owner.children(folder)){String p=dir+"/"+xml.getName();if(xml.getName().endsWith("-ds.xml"))applicationDatasource(xml,null,deployed);else if(descriptor(p)){String text=read(xml,ServerDataSources.CONFIG_LIMIT);if(text!=null)applicationXml(xml.getPath(),text,deployed);}}}}
        else{if(f.length()>ArchiveMetadata.FILE_LIMIT)return;ZipFile zip=null;try{scan.archiveMetadata.preflight(f);zip=new ZipFile(f);Enumeration<? extends ZipEntry> all=zip.entries();int count=0,selected=0;Set<String>seen=new HashSet<String>();while(all.hasMoreElements()&&scan.available()){if(++count>ArchiveMetadata.ENTRY_LIMIT)throw new IOException();ZipEntry e=all.nextElement();String p=e.getName();if(e.isDirectory()||!ArchiveMetadata.safeName(p)||!seen.add(p))continue;if(!(p.startsWith("WEB-INF/")||p.startsWith("META-INF/")))continue;if(!(p.endsWith("-ds.xml")||descriptor(p)))continue;if(++selected>64)throw new IOException();if(p.endsWith("-ds.xml"))applicationDatasource(f,p,deployed);else{String text=owner.entry(f,p,ServerDataSources.CONFIG_LIMIT);if(text!=null)applicationXml(f.getPath()+"!/"+p,text,deployed);}}}catch(IOException e){scan.note("Descritores de aplicação JBoss não examinados por completo: limite de leitura");}finally{if(zip!=null)try{zip.close();}catch(IOException e){}}}
    }
    static boolean descriptor(String name){return Arrays.asList("WEB-INF/jboss-web.xml","META-INF/jboss-app.xml","META-INF/persistence.xml","WEB-INF/classes/META-INF/persistence.xml","WEB-INF/web.xml","META-INF/application.xml","META-INF/ejb-jar.xml").contains(name);}
    void applicationDatasource(File f,String entry,AssessmentScan.ProcessContext c){int before=scan.datasources.size();if(entry==null)owner.inspect(f,"JBossDeployment",c);else owner.load(f,entry,"JBossDeployment",c,null);for(int i=before;i<scan.datasources.size();i++){Map row=(Map)scan.datasources.get(i);row.put("evidence","Datasource implantado com a aplicação; configuração passiva");row.put("configurationScope","application-deployment");}}
    void applicationXml(String source,String xml,AssessmentScan.ProcessContext c){try{AppXml h=new AppXml(scan);ServerDataSources.parseXml(xml,h);for(Map<String,String> item:h.rows){String jndi=JndiLinks.normalized(expanded(c,item.get("jndi")));if(jndi!=null){List<Map> matches=new ArrayList<Map>();for(Object raw:new ArrayList(scan.datasources)){Map target=(Map)raw;if(JdbcDriverResolver.linked(target,c.pid)&&JndiLinks.matches(target,jndi)&&target.get("jndiResolution")==null&&!"application-deployment".equals(target.get("configurationScope")))matches.add(target);}if(matches.size()==1){Map target=matches.get(0);associate(target,c);target.put("applicationLinkEvidence","Vínculo JNDI declarado pela aplicação; conexão não verificada");}else if(matches.size()>1)scan.note("Vínculo JNDI ambíguo para a aplicação; múltiplos datasources neste processo");}
            if(item.get("url")!=null||item.get("driver")!=null||item.get("host")!=null){Map row=AssessmentScan.map("name",ServerDataSources.resourceName(SpringDataSource.first(item.get("name"),"Datasource da aplicação")),"configurationContext","application:"+c.application.get("id"),"configurationScope","application-deployment","evidence","Datasource implantado com a aplicação; configuração passiva");JdbcEndpoint.addTo(row,item.get("url"),owner.resolver(c));String driver=SpringDataSource.validDriver(item.get("driver"));if(driver!=null)row.put("driverName",driver);if(row.get("databaseEndpoints")==null&&item.get("host")!=null)row.putAll(JdbcEndpoint.parts(expanded(c,item.get("host")),expanded(c,item.get("port")),expanded(c,item.get("database")),new ArrayList<String>()));owner.add(source,row,c);}
        }}catch(Exception e){scan.note("Descritor JDBC/JNDI da aplicação inválido ou fora dos limites");}}
    void associate(Map target,AssessmentScan.ProcessContext c){
        List associations=target.get("associations") instanceof List?(List)target.get("associations"):new ArrayList();
        Map association=AssessmentScan.map("pid",Long.valueOf(c.pid),"applicationId",c.application.get("id"),"applicationName",c.application.get("name"));
        if(c.server!=null){association.put("serverId",c.server.get("id"));association.put("serverName",c.server.get("name"));}
        for(Object raw:associations){Map prior=(Map)raw;if(Long.valueOf(c.pid).equals(prior.get("pid"))&&c.application.get("id").equals(prior.get("applicationId")))return;}
        if(associations.size()<16)associations.add(association);else scan.note("Limite de associações JDBC/JNDI atingido");target.put("associations",associations);if(associations.size()>1){target.remove("applicationId");target.remove("applicationName");}
    }
    static final class AppXml extends DefaultHandler {
        final AssessmentScan scan;final List<Map<String,String>> rows=new ArrayList<Map<String,String>>();Map<String,String> row;int depth,ownerDepth,textDepth,count;String key;StringBuilder text;
        AppXml(AssessmentScan s){scan=s;}
        public void startElement(String u,String l,String q,Attributes a)throws SAXException{String n=l.length()>0?l:q;if(++depth>64||++count>12000||rows.size()>=64||!scan.available())throw new SAXException("limit");if(row==null&&(n.equals("resource-ref")||n.equals("data-source")||n.equals("persistence-unit"))){row=new LinkedHashMap<String,String>();ownerDepth=depth;}
            if(row==null)return;String selected=null;if(n.equals("jndi-name")||n.equals("lookup-name")||n.equals("jta-data-source")||n.equals("non-jta-data-source"))selected="jndi";else if(n.equals("res-ref-name")||n.equals("name"))selected="name";else if(n.equals("class-name"))selected="driver";else if(n.equals("url"))selected="url";else if(n.equals("server-name"))selected="host";else if(n.equals("port-number"))selected="port";else if(n.equals("database-name"))selected="database";
            if(selected!=null){key=selected;textDepth=depth;text=new StringBuilder();}
        }
        public void characters(char[] data,int start,int count)throws SAXException{if(text!=null){if(text.length()+count>8192)throw new SAXException("limit");text.append(data,start,count);}}
        public void endElement(String u,String l,String q){if(row!=null&&text!=null&&depth==textDepth){row.put(key,text.toString().trim());text=null;key=null;}if(row!=null&&depth==ownerDepth){rows.add(row);row=null;}depth--;}
    }
}
