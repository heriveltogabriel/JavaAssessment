package assessment;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import static assessment.AssessmentServer.map;

/** Signed immutable paths. Legacy agents receive their original wire format. */
final class AgentUpdateRoutes {
    static final long MAX_BUNDLE=64L*1024*1024;
    record Bundle(String version,Path directory,byte[] manifest,byte[] signature,Map<String,Object> metadata,String manifestHash,String sha256,String managerHash){}
    record Bridge(String id,String fromVersion,String fromHash,String supportProfile,int minUpdater,String version,Path directory,byte[] manifest,byte[] signature,String sha256,long bytes) {
        AgentUpdateCatalog.Release legacy(){return new AgentUpdateCatalog.Release(version,manifest,signature,directory.resolve("collector.jar"),sha256,bytes,minUpdater);}
    }
    record Catalog(String version,String hash,Bundle bundle,List<Bridge> bridges){}
    private final Path root;
    private Catalog cached;
    private long cachedStamp=-1;
    AgentUpdateRoutes(File root){try{if(Files.isSymbolicLink(root.toPath()))throw new IOException("Linked update root");this.root=root.toPath().toRealPath();}catch(IOException invalid){throw new IllegalArgumentException("Invalid update root",invalid);}}
    private static void require(boolean ok)throws IOException{if(!ok)throw new IOException("Signed update route unavailable");}
    private static String s(Object v){return v instanceof String?(String)v:"";}
    private static long n(Object v){return v instanceof Number?((Number)v).longValue():-1;}
    static String hash(byte[] bytes)throws IOException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(GeneralSecurityException e){throw new IOException(e);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)throws IOException{require(value instanceof Map);return (Map<String,Object>)value;}
    private byte[] read(Path file,long max)throws IOException{
        Path p=file.toAbsolutePath().normalize();require(p.startsWith(root)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)>0&&Files.size(p)<=max);
        for(Path at=p;at!=null&&at.startsWith(root);at=at.getParent())require(!Files.isSymbolicLink(at));
        try(InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes((int)max+1);require(bytes.length<=max);return bytes;}
    }
    private void signature(byte[] raw,byte[] sig)throws IOException{
        try{byte[] der=read(root.resolve("dist/agent-update-public.der"),1024);PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            require(key instanceof RSAPublicKey rsa&&rsa.getModulus().bitLength()==3072&&sig.length==384);Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(raw);require(verifier.verify(sig));
        }catch(GeneralSecurityException e){throw new IOException("Signed update route unavailable",e);}
    }
    private Map<String,Object> signed(Path directory,String name)throws IOException{
        byte[] raw=read(directory.resolve(name+".json"),65536),sig=read(directory.resolve(name+".sig"),512);signature(raw,sig);return object(Json.parse(new String(raw,StandardCharsets.UTF_8),65536));
    }
    private static void version(String v)throws IOException{require(AgentUpdateCatalog.version(v));}
    private static void sha(String v)throws IOException{require(v.matches("[a-f0-9]{64}"));}
    private Bundle bundle(String target,String expected)throws IOException{
        Path directory=root.resolve("dist/agent-bundles").resolve(target);byte[] raw=read(directory.resolve("manifest.json"),65536),sig=read(directory.resolve("manifest.sig"),512);signature(raw,sig);require(hash(raw).equals(expected));Map<String,Object> m=object(Json.parse(new String(raw,StandardCharsets.UTF_8),65536));
        require(m.keySet().equals(Set.of("schema","type","format","version","minUpdater","minJava","platform","bytes","sha256","files")));
        require(n(m.get("schema"))==1&&n(m.get("minUpdater"))==3&&n(m.get("minJava"))==6&&"linux".equals(m.get("platform"))&&"ASSESSMENT_AGENT_BUNDLE_V1".equals(m.get("type"))&&"agent-bundle-v1".equals(m.get("format"))&&target.equals(m.get("version")));
        long bytes=n(m.get("bytes"));require(bytes>0&&bytes<=MAX_BUNDLE);String digest=s(m.get("sha256"));sha(digest);require(hash(read(directory.resolve("bundle.zip"),MAX_BUNDLE)).equals(digest)&&Files.size(directory.resolve("bundle.zip"))==bytes);
        try{m=object(AgentBundleManifest.verify(directory.resolve("manifest.json").toFile(),directory.resolve("manifest.sig").toFile(),root.resolve("dist/agent-update-public.der").toFile(),directory.resolve("bundle.zip").toFile(),target));}
        catch(Exception invalid){throw new IOException("Signed bundle validation failed",invalid);}
        Map<String,Object> files=object(m.get("files"));String manager=s(object(files.get("manager/helper.jar")).get("sha256"));sha(manager);
        return new Bundle(target,directory,raw,sig,m,expected,digest,manager);
    }
    private Bridge bridge(Map<String,Object> route,String target)throws IOException{
        require(route.keySet().equals(Set.of("id","fromVersion","fromCollectorSha256","supportProfileId","minUpdater","bridgeVersion","bridgeManifestSha256")));
        String id=s(route.get("id")),from=s(route.get("fromVersion")),fromHash=s(route.get("fromCollectorSha256")),profile=s(route.get("supportProfileId")),v=s(route.get("bridgeVersion"));sha(id);sha(fromHash);sha(profile);version(from);version(v);
        int min=(int)n(route.get("minUpdater"));require((min==1||min==2)&&AgentUpdateCatalog.compare(from,"1.5.12")>=0&&AgentUpdateCatalog.compare(v,from)>0&&AgentUpdateCatalog.compare(target,v)>0);
        Path directory=root.resolve("dist/agent-bridges").resolve(v).resolve(id);byte[] raw=read(directory.resolve("manifest.json"),65536),sig=read(directory.resolve("manifest.sig"),512);signature(raw,sig);require(hash(raw).equals(id)&&id.equals(route.get("bridgeManifestSha256")));Map<String,Object> m=object(Json.parse(new String(raw,StandardCharsets.UTF_8),65536));
        require(m.keySet().equals(Set.of("schema","type","format","version","minUpdater","bytes","sha256","requiredFiles"))&&n(m.get("schema"))==1&&n(m.get("minUpdater"))==min&&v.equals(m.get("version"))&&"ASSESSMENT_AGENT_UPDATE_V1".equals(m.get("type"))&&"collector-jar-v1".equals(m.get("format")));
        Map<String,Object> support=object(m.get("requiredFiles"));require(!support.isEmpty()&&support.size()<=128&&support.containsKey("update-public.der"));for(var e:support.entrySet()){require(e.getKey().matches("[A-Za-z0-9_-]+\\.sh|lib/[A-Za-z0-9_.-]+\\.jar|update-public\\.der"));sha(s(e.getValue()));}
        long bytes=n(m.get("bytes"));require(bytes>0&&bytes<=32L*1024*1024);String digest=s(m.get("sha256"));sha(digest);require(hash(read(directory.resolve("collector.jar"),32L*1024*1024)).equals(digest)&&Files.size(directory.resolve("collector.jar"))==bytes);
        try(JarFile jar=new JarFile(directory.resolve("collector.jar").toFile())){require(v.equals(jar.getManifest().getMainAttributes().getValue("Implementation-Version")));JarEntry metadata=jar.getJarEntry("migration/bridge.json"),baseline=jar.getJarEntry("migration/baseline/collector.jar");require(metadata!=null&&metadata.getSize()>0&&metadata.getSize()<=65536&&baseline!=null&&baseline.getSize()>0&&baseline.getSize()<=32L*1024*1024);
            try(InputStream in=jar.getInputStream(metadata)){Map<String,Object> b=object(Json.parse(new String(in.readNBytes(65537),StandardCharsets.UTF_8),65536));require(n(b.get("schema"))==1&&from.equals(b.get("fromVersion"))&&fromHash.equals(b.get("fromCollectorSha256"))&&profile.equals(b.get("supportProfileId"))&&v.equals(b.get("bridgeVersion"))&&target.equals(b.get("targetVersion"))&&"migration/baseline/collector.jar".equals(b.get("recoveryResource")));}
            try(InputStream in=jar.getInputStream(baseline)){require(hash(in.readNBytes(32*1024*1024+1)).equals(fromHash));}
        }return new Bridge(id,from,fromHash,profile,min,v,directory,raw,sig,digest,bytes);
    }
    Catalog load(String target)throws IOException{
        version(target);Path directory=root.resolve("dist/agent-update-routes").resolve(target);Map<String,Object> index=signed(directory,"routes");require(index.keySet().equals(Set.of("schema","type","targetVersion","minimumAgentVersion","bundleManifestSha256","routes"))&&n(index.get("schema"))==1&&"ASSESSMENT_AGENT_ROUTES_V1".equals(index.get("type"))&&target.equals(index.get("targetVersion"))&&"1.5.12".equals(index.get("minimumAgentVersion")));
        String expected=s(index.get("bundleManifestSha256"));sha(expected);Bundle bundle=bundle(target,expected);require(index.get("routes") instanceof List<?>);List<?> rows=(List<?>)index.get("routes");require(rows.size()<=32);List<Bridge> bridges=new ArrayList<>();Set<String> ids=new HashSet<>();for(Object row:rows){Bridge b=bridge(object(row),target);require(ids.add(b.id));bridges.add(b);}
        return new Catalog(target,hash(read(directory.resolve("routes.json"),65536)),bundle,List.copyOf(bridges));
    }
    boolean configured(){return Files.exists(root.resolve("dist/agent-update-routes").resolve(DashboardVersion.VERSION),LinkOption.NOFOLLOW_LINKS);}
    synchronized Catalog current(){try{Path index=root.resolve("dist/agent-update-routes").resolve(DashboardVersion.VERSION).resolve("routes.json");long stamp=Files.getLastModifiedTime(index,LinkOption.NOFOLLOW_LINKS).toMillis();if(cached==null||stamp!=cachedStamp){cached=load(DashboardVersion.VERSION);cachedStamp=stamp;}return cached;}catch(Exception unavailable){cached=null;return null;}}
    List<Bridge> candidates(Catalog catalog,Map<String,Object> host){String from=s(host.get("collectorVersion"));int generation=AgentUpdateRegistry.updaterGeneration(host.get("updateCapability"));if(!AgentUpdateCatalog.version(from)||AgentUpdateCatalog.compare(from,"1.5.12")<0||generation<1)return List.of();return catalog.bridges.stream().filter(b->b.fromVersion.equals(from)&&generation>=b.minUpdater).toList();}
    byte[] asset(Bundle b,String part)throws IOException{return switch(part){case "manifest"->b.manifest;case "signature"->b.signature;case "package"->read(b.directory.resolve("bundle.zip"),MAX_BUNDLE);default->throw new IOException("Unknown bundle asset");};}
}
