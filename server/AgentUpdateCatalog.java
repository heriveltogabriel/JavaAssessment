package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

/** Only the pinned, signed artifact matching this dashboard release can be offered. */
final class AgentUpdateCatalog {
    static final long MAX_JAR=32L*1024*1024;
    record Release(String version,byte[] manifest,byte[] signature,Path jar,String sha256,long bytes,int minUpdater){}
    private final Path root;
    AgentUpdateCatalog(File root){this.root=root.toPath();}
    File rootFile(){return root.toFile();}
    static boolean version(String value){return value!=null&&value.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})");}
    static int compare(String a,String b){if(!version(a)||!version(b))throw new IllegalArgumentException("Invalid numeric version");String[] x=a.split("\\."),y=b.split("\\.");for(int i=0;i<3;i++){int c=Long.compare(Long.parseLong(x[i]),Long.parseLong(y[i]));if(c!=0)return c;}return 0;}
    private static void require(boolean condition)throws IOException{if(!condition)throw new IOException("Signed update catalog validation failed");}
    private static byte[] read(Path file,int max)throws IOException{require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file)&&Files.size(file)<=max);try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(max+1);require(bytes.length<=max);return bytes;}}
    private static String hash(InputStream in,long limit)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[16384];long total=0;for(int n;(n=in.read(block))!=-1;){total+=n;require(total<=limit);digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private static boolean asset(String name){return name.matches("[A-Za-z0-9_-]+\\.sh")||name.matches("lib/[A-Za-z0-9_.-]+\\.jar")||name.equals("update-public.der");}
    @SuppressWarnings("unchecked") synchronized Release load()throws IOException {
        try {
            require(version(DashboardVersion.VERSION));Path dist=root.resolve("dist"),releases=dist.resolve("agent-updates"),directory=releases.resolve(DashboardVersion.VERSION);
            for(Path path:List.of(dist,releases,directory))require(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path));
            byte[] keyBytes=read(dist.resolve("agent-update-public.der"),1024),manifest=read(directory.resolve("manifest.json"),65536),signature=read(directory.resolve("manifest.sig"),512);
            PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));require(publicKey instanceof RSAPublicKey rsa&&rsa.getModulus().bitLength()==3072&&signature.length==384);
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(publicKey);verifier.update(manifest);require(verifier.verify(signature));
            Object parsed=Json.parse(new String(manifest,StandardCharsets.UTF_8),65536);require(parsed instanceof Map);Map<String,Object> m=(Map<String,Object>)parsed;
            require(m.keySet().equals(Set.of("schema","type","version","format","minUpdater","sha256","bytes","requiredFiles")));
            require(Long.valueOf(1).equals(m.get("schema"))&&(Long.valueOf(1).equals(m.get("minUpdater"))||Long.valueOf(2).equals(m.get("minUpdater")))&&"ASSESSMENT_AGENT_UPDATE_V1".equals(m.get("type"))&&"collector-jar-v1".equals(m.get("format"))&&DashboardVersion.VERSION.equals(m.get("version")));
            require(m.get("sha256") instanceof String digest&&digest.matches("[a-f0-9]{64}")&&m.get("bytes") instanceof Number);long bytes=((Number)m.get("bytes")).longValue();require(bytes>0&&bytes<=MAX_JAR&&((Number)m.get("bytes")).doubleValue()==bytes);
            Path jar=directory.resolve("collector.jar");require(Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(jar)&&Files.size(jar)==bytes);try(InputStream input=Files.newInputStream(jar)){require(hash(input,MAX_JAR).equals(m.get("sha256")));}
            try(JarFile archive=new JarFile(jar.toFile())){JarEntry entry=archive.getJarEntry("META-INF/MANIFEST.MF");require(entry!=null&&entry.getSize()<=65536);try(InputStream input=archive.getInputStream(entry)){byte[] data=input.readNBytes(65537);require(data.length<=65536);Manifest metadata=new Manifest(new ByteArrayInputStream(data));require(DashboardVersion.VERSION.equals(metadata.getMainAttributes().getValue("Implementation-Version")));}}
            require(m.get("requiredFiles") instanceof Map);Map<String,Object> required=(Map<String,Object>)m.get("requiredFiles");require(!required.isEmpty()&&required.size()<=100&&required.containsKey("update-public.der"));
            for(var entry:required.entrySet())require(asset(entry.getKey())&&entry.getValue() instanceof String digest&&digest.matches("[a-f0-9]{64}"));
            Path generic=dist.resolve("assessment-collector.zip");require(Files.isRegularFile(generic,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(generic)&&Files.size(generic)<=64L*1024*1024);
            try(ZipFile archive=new ZipFile(generic.toFile())){Set<String> seen=new HashSet<>(),stable=new HashSet<>();long total=0;var entries=archive.entries();while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();require(seen.add(entry.getName())&&seen.size()<=100);if(asset(entry.getName())){require(!entry.isDirectory()&&entry.getSize()>=0&&(total+=entry.getSize())<=64L*1024*1024);stable.add(entry.getName());require(required.containsKey(entry.getName()));try(InputStream input=archive.getInputStream(entry)){require(hash(input,64L*1024*1024).equals(required.get(entry.getName())));}}}require(stable.equals(required.keySet()));
                ZipEntry pinned=archive.getEntry("update-public.der");try(InputStream input=archive.getInputStream(pinned)){require(MessageDigest.isEqual(keyBytes,input.readNBytes(1025)));}}
            return new Release(DashboardVersion.VERSION,manifest,signature,jar,(String)m.get("sha256"),bytes,((Number)m.get("minUpdater")).intValue());
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Signed update catalog validation failed",e);}
    }
    Map<String,Object> summary(){try{Release release=load();return map("version",release.version,"available",true,"reason","","minUpdater",release.minUpdater);}catch(IOException e){return map("version",DashboardVersion.VERSION,"available",false,"reason","RELEASE_UNAVAILABLE");}}
    Release required(String version){try{Release release=load();if(!release.version.equals(version))throw new EnrollmentRegistry.Failure(409,"TARGET_VERSION_CHANGED");return release;}catch(IOException e){throw new EnrollmentRegistry.Failure(503,"RELEASE_UNAVAILABLE");}}
    byte[] bytes(Release release,String part)throws IOException{if(part.equals("manifest"))return release.manifest;if(part.equals("signature"))return release.signature;byte[] data=read(release.jar,(int)MAX_JAR);try{require(data.length==release.bytes&&HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(release.sha256));return data;}catch(GeneralSecurityException e){throw new IOException("Signed update unavailable",e);}}
}
