package assessment;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Libraries scoped to the declared application, never promoted to system datasource candidates. */
final class WebLogicApplicationDrivers {
    static final int NESTED_LIMIT=4*1024*1024;
    static final class Result { boolean preferred;String reason;final List<Map>candidates=new ArrayList<Map>(); }
    static Result inspect(AssessmentScan scan,Map row,String driver){
        Result result=new Result();if(!"application-module".equals(row.get("configurationScope")))return result;
        Object path=row.get("applicationSource");if(!(path instanceof String)){result.preferred=true;result.reason="Origem do módulo da aplicação indisponível; classloader não confirmado";return result;}
        File source=scan.linkedPath(new File((String)path));
        if(source==null){result.preferred=true;result.reason="Aplicação excluída da inspeção do driver";return result;}
        try{
            String descriptor=source.getName().toLowerCase(Locale.ENGLISH).endsWith(".war")?"WEB-INF/weblogic.xml":"META-INF/weblogic-application.xml";
            String xml;
            if(source.isDirectory()){File config=scan.linkedPath(new File(source,descriptor));if(config==null||!scan.visitLinked(config))throw new IOException();xml=scan.read(config,ServerDataSources.SMALL_LIMIT);}
            else{if(!scan.visitLinked(source))throw new IOException();xml=scan.serverDataSources.entry(source,descriptor,ServerDataSources.SMALL_LIMIT);}
            if(xml==null)throw new IOException();Preference preference=new Preference(driver);ServerDataSources.parseXml(xml,preference);result.preferred=preference.preferred;
            if(source.isDirectory())for(String lib:new String[]{"APP-INF/lib","WEB-INF/lib"}){
                File dir=scan.linkedPath(new File(source,lib));if(dir==null)throw new IOException();if(!dir.isDirectory())continue;
                File[] files=scan.serverDataSources.children(dir);if(files.length>=512)throw new IOException();
                for(File file:files)if(file.getName().endsWith(".jar")){File safe=scan.linkedPath(file);if(safe==null||!scan.visitLinked(safe))throw new IOException();JdbcDriverResolver.Archive a=scan.jdbcDrivers.inspect(safe,Collections.singleton(driver));if(!a.complete)throw new IOException();if(a.classes.contains(driver))add(result,safe.getPath(),a);}
            }else{
                scan.archiveMetadata.preflight(source);ZipFile zip=new ZipFile(source);
                try{Enumeration<? extends ZipEntry> entries=zip.entries();int count=0;Set<String>seen=new HashSet<String>();
                    while(entries.hasMoreElements()){if(++count>ArchiveMetadata.ENTRY_LIMIT||!scan.available())throw new IOException();ZipEntry e=entries.nextElement();String name=e.getName();if(!(name.startsWith("APP-INF/lib/")||name.startsWith("WEB-INF/lib/"))||!name.endsWith(".jar")||!ArchiveMetadata.safeName(name))continue;
                        if(!seen.add(name)||e.getSize()<0||e.getSize()>NESTED_LIMIT||seen.size()>32)throw new IOException();InputStream in=zip.getInputStream(e);byte[] bytes;try{bytes=scan.archiveMetadata.read(in,NESTED_LIMIT);}finally{in.close();}
                        JdbcDriverResolver.Archive a=nested(scan,bytes,driver);if(a.classes.contains(driver))add(result,source.getPath()+"!/"+name,a);
                    }
                }finally{zip.close();}
            }
        }catch(Exception e){result.preferred=true;result.reason="Inspeção incompleta das bibliotecas ou preferências do classloader da aplicação";}
        return result;
    }
    static void add(Result result,String path,JdbcDriverResolver.Archive a)throws IOException{if(result.candidates.size()>=32)throw new IOException();result.candidates.add(AssessmentScan.map("path",path,"version",a.versions.size()==1?a.versions.iterator().next():null,"versionSource",a.versionSource,"family",JdbcDriverResolver.family(path),"onClasspath",Boolean.TRUE));}
    static JdbcDriverResolver.Archive nested(AssessmentScan scan,byte[] bytes,String driver)throws IOException{
        JdbcDriverResolver.Archive a=new JdbcDriverResolver.Archive();ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes));Set<String>seen=new HashSet<String>();Set<String>pom=new LinkedHashSet<String>();int total=0,count=0;
        try{for(ZipEntry e;(e=zip.getNextEntry())!=null;){if(++count>4096||!seen.add(e.getName()))throw new IOException();byte[] data=scan.archiveMetadata.read(zip,NESTED_LIMIT-total);total+=data.length;
            if(e.getName().equals(driver))a.classes.add(driver);
            if(e.getName().equals("META-INF/MANIFEST.MF")){if(data.length>ArchiveMetadata.METADATA_LIMIT)throw new IOException();JdbcDriverResolver.manifest(a,data);}
            else if(e.getName().startsWith("META-INF/maven/")&&e.getName().endsWith("pom.properties")){if(data.length>ArchiveMetadata.METADATA_LIMIT)throw new IOException();Properties p=new Properties();p.load(new ByteArrayInputStream(data));if(ArchiveMetadata.jdbcArtifact(p.getProperty("groupId"),p.getProperty("artifactId"))&&JdbcDriverResolver.version(p.getProperty("version"))!=null)pom.add(p.getProperty("version"));}
        }}finally{zip.close();}
        if(a.versions.isEmpty()&&pom.size()==1){a.versions.addAll(pom);a.versionSource="pom";}return a;
    }
    static final class Preference extends DefaultHandler {
        final String driver;StringBuilder text;String active;boolean preferred;int depth,elements,packagesDepth;
        Preference(String driver){this.driver=driver.replace('/','.').replaceFirst("[.]class$","");}
        public void startElement(String uri,String local,String q,Attributes attrs)throws SAXException{if(++depth>32||++elements>12000)throw new SAXException("limit");String name=local.length()>0?local:q;if(name.equals("prefer-application-packages"))packagesDepth=depth;if(name.equals("prefer-web-inf-classes")||name.equals("package-name")&&packagesDepth>0){active=name;text=new StringBuilder();}}
        public void characters(char[] data,int start,int length)throws SAXException{if(text!=null){if(text.length()+length>512)throw new SAXException("limit");text.append(data,start,length);}}
        public void endElement(String uri,String local,String q){String name=local.length()>0?local:q;if(name.equals(active)){String value=text.toString().trim();if(name.equals("prefer-web-inf-classes")&&value.equals("true"))preferred=true;else if(name.equals("package-name")&&(driver.equals(value)||value.endsWith(".*")&&driver.startsWith(value.substring(0,value.length()-1))))preferred=true;active=null;text=null;}if(depth==packagesDepth)packagesDepth=0;depth--;}
    }
}
