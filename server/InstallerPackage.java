package assessment;

import java.io.*;
import java.util.*;
import java.text.Normalizer;
import java.util.zip.*;

/** Streams a private configured download; never rewrites the shared, credential-free ZIP. */
final class InstallerPackage {
    private static final long LIMIT=64L*1024*1024;
    /** ASCII filename derived from the current display name, never from its stable identity. */
    static String filename(String environmentName) {
        String normalized=Normalizer.normalize(environmentName==null?"":environmentName.toLowerCase(Locale.ENGLISH),Normalizer.Form.NFD);
        String slug=normalized.replaceAll("\\p{M}+","").replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$","");
        return "assessment-"+(slug.length()==0?"ambiente":slug)+".zip";
    }
    private final ZipFile source;
    InstallerPackage(File file)throws IOException {
        if(!file.isFile()||file.length()>LIMIT)throw new IOException("Generic installer package unavailable");
        source=new ZipFile(file);boolean valid=false;
        try {
            Enumeration<? extends ZipEntry> entries=source.entries();Set<String> names=new HashSet<String>();long total=0;int count=0;
            while(entries.hasMoreElements()){
                ZipEntry e=entries.nextElement();String name=e.getName();
                if(++count>100||!safe(name)||!names.add(name)||e.isDirectory()||e.getSize()<0||(total+=e.getSize())>LIMIT)throw new IOException("Invalid generic installer package");
            }
            for(String name:new String[]{"install.sh","system-install.sh","bootstrap-config.sh","collector.jar","conf/ca.pem"})if(!names.contains(name))throw new IOException("Incomplete generic installer package");
            valid=true;
        }finally{if(!valid)source.close();}
    }
    private boolean safe(String name){
        if(name.matches("[A-Za-z0-9_-]+\\.sh"))return true;
        if(name.matches("lib/[A-Za-z0-9_.-]+\\.jar"))return true;
        return Arrays.asList("collector.jar","README.md","INSTALL.md","conf/collector.properties.example","conf/ca.pem","SHA256SUMS","DEPENDENCIES.json","BOUNCY-CASTLE-LICENSE.txt").contains(name);
    }
    void write(OutputStream target,String serverUrl,String code,String environmentId)throws IOException {
        ZipOutputStream zip=new ZipOutputStream(target);byte[] buffer=new byte[16384];long total=0;
        Enumeration<? extends ZipEntry> entries=source.entries();
        while(entries.hasMoreElements()){
            ZipEntry original=entries.nextElement(),entry=new ZipEntry(original.getName());entry.setTime(original.getTime());zip.putNextEntry(entry);
            InputStream in=source.getInputStream(original);try{int n;while((n=in.read(buffer))!=-1){total+=n;if(total>LIMIT)throw new IOException("Installer package limit exceeded");zip.write(buffer,0,n);}}finally{in.close();}zip.closeEntry();
        }
        // Shell bootstrap reader handles literal key=value. URLs must remain unescaped.
        String literal="serverUrl="+serverUrl+"\nenrollmentToken="+code+"\nenvironmentId="+environmentId+"\n";
        zip.putNextEntry(new ZipEntry("conf/bootstrap.properties"));zip.write(literal.getBytes("UTF-8"));zip.closeEntry();zip.finish();zip.close();
    }
    void close()throws IOException {source.close();}
}
