package assessment;

import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Actual released manager -> final manager, real signatures/files; OS/network adapters. */
public final class AgentDirectV3UpgradeTest {
 static Map<String,byte[]> zip(byte[] raw)throws Exception{
  Map<String,byte[]> m=new LinkedHashMap<>();try(var z=new ZipInputStream(new ByteArrayInputStream(raw))){ZipEntry e;while((e=z.getNextEntry())!=null)if(!e.isDirectory())m.put(e.getName(),z.readAllBytes());}return m;
 }
 static void put(Path p,byte[] b)throws Exception{Files.createDirectories(p.getParent());Files.write(p,b);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static String str(Object o){return String.valueOf(o);}
 static Map obj(byte[] b)throws Exception{return (Map)Json.parse(new String(b,"UTF-8"));}
 static Object invoke(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);try{return m.invoke(o);}catch(InvocationTargetException e){throw (Exception)e.getCause();}}
 static Object create(Class<?> c,Class<?>[] types,Object...args)throws Exception{Constructor<?> k=c.getDeclaredConstructor(types);k.setAccessible(true);return k.newInstance(args);}
 static Object worker(Path jar,Path root,Path home,Properties conf,Map<String,byte[]> target,Map claim,List<String> reports,boolean newManager,List<URLClassLoader> loaders)throws Exception{
  List<URL> urls=new ArrayList<>();urls.add(jar.toUri().toURL());try(var libs=Files.list(jar.getParent().resolve("lib"))){for(Path p:libs.toList())urls.add(p.toUri().toURL());}
  URLClassLoader loader=new URLClassLoader(urls.toArray(new URL[0]),null);loaders.add(loader);
  Class<?> layoutType=loader.loadClass("assessment.AgentInstallLayout"),platform=loader.loadClass("assessment.AgentBundleInstaller$Platform"),remote=loader.loadClass("assessment.AgentBundleInstaller$Remote");
  Object layout=create(layoutType,new Class[]{File.class,File.class},root.toFile(),home.toFile());
  Object os=java.lang.reflect.Proxy.newProxyInstance(loader,new Class[]{platform},(p,m,a)->{
   if(m.getName().equals("local")){check(!a[0].equals("install-bootstrap"),"V3 must not use a bridge/bootstrap installation");return null;}
   if(m.getName().equals("probe")){check(str(a[1]).equals(AgentUpdateWorker.jarVersion(new File((File)a[0],"collector.jar"))),"probe target version");return 0;}
   if(m.getName().equals("serviceEvidence"))return newManager;
   if(m.getName().equals("processUses"))return false;
   throw new AssertionError(m);
  });
  Object network=java.lang.reflect.Proxy.newProxyInstance(loader,new Class[]{remote},(p,m,a)->{
   if(m.getName().equals("download")){String part=str(a[0]);byte[] b=target.get("full-update/"+(part.equals("manifest")?"manifest.json":part.equals("signature")?"manifest.sig":"bundle.zip"));check(b.length<=((Number)a[2]).longValue(),"bounded transfer");Files.write(((File)a[1]).toPath(),b);return null;}
   if(m.getName().equals("request")){if(a[0].equals("claim"))return claim;Map body=(Map)a[1];reports.add(str(body.get("status")));return AssessmentScan.map("accepted",true,"operationId",claim.get("operationId"),"status",body.get("status"));}
   throw new AssertionError(m);
  });
  return create(loader.loadClass("assessment.AgentBundleInstaller"),new Class[]{layoutType,platform,remote,Properties.class},layout,os,network,conf);
 }
 public static void main(String[] args)throws Exception{
  Map<String,byte[]> old=zip(Files.readAllBytes(Path.of(args[0]))),target=zip(Files.readAllBytes(Path.of(args[1])));
  check(Arrays.equals(old.get("update-public.der"),target.get("update-public.der")),"public pin preserved");
  Map beforeManifest=obj(old.get("full-update/manifest.json")),afterManifest=obj(target.get("full-update/manifest.json"));
  String baseline=args.length>2?args[2]:"2.0.53";check(Set.of("2.0.53","2.0.57","2.1.0","2.1.2","2.1.4","2.1.6","2.1.8").contains(baseline),"reviewed client baseline");check(beforeManifest.get("version").equals(baseline),"exact client baseline");
  Path tmp=Files.createTempDirectory("direct-v3-253-").toRealPath(),root=tmp.resolve("collector"),home=tmp.resolve("helper");
  AgentInstallLayout l=new AgentInstallLayout(root.toFile(),home.toFile());l.initialize();
  for(var e:old.entrySet())if(!e.getKey().startsWith("full-update/"))put(root.resolve(e.getKey()),e.getValue());
  Map<String,byte[]> bundle=zip(old.get("full-update/bundle.zip"));
  Path code=root.resolve("releases/release-253"),manager=home.resolve("managers/manager-253"),boot=home.resolve("bootstrap");
  for(var e:bundle.entrySet()){
   Path dir=e.getKey().startsWith("code/")?code:e.getKey().startsWith("manager/")?manager:boot;
   put(dir.resolve(e.getKey().substring(e.getKey().indexOf('/')+1)),e.getValue());
  }
  for(Path dir:List.of(code,manager,boot)){
   put(dir.resolve("bundle-manifest.json"),old.get("full-update/manifest.json"));put(dir.resolve("bundle-manifest.sig"),old.get("full-update/manifest.sig"));
   AgentInstallLayout.durable(dir.resolve(dir.equals(boot)?"bootstrap-owned.json":"installed.json").toFile(),AssessmentScan.map("schema",1L,"role",dir.equals(code)?"code":"manager","version",baseline,"bundleSha256",beforeManifest.get("sha256"),"files",AgentInstallLayout.inventory(dir.toFile())));
  }
  String oldHash=AgentBundleManifest.hash(manager.resolve("helper.jar").toFile());
  l.promote(AssessmentScan.map("schema",1L,"releaseId","release-253","managerId","manager-253","version",baseline,"bundleSha256",beforeManifest.get("sha256"),"managerSha256",oldHash));
  Properties conf=new Properties();conf.setProperty("hostId","direct-v3-fixture");conf.setProperty("agentToken","local-fixture-token");conf.setProperty("serverUrl","http://127.0.0.1:1");conf.setProperty("tlsCaFile",root.resolve("conf/ca.pem").toString());conf.setProperty("spoolDirectory",root.resolve("collector-spool").toString());
  try(var out=Files.newOutputStream(root.resolve("conf/collector.properties"))){conf.store(out,"fixture");}
  put(root.resolve("conf/java.path"),"/fixture/java6/bin/java\n".getBytes());put(root.resolve("conf/installation-id"),"same-installation\n".getBytes());put(root.resolve("conf/update-capability"),"update-v3\n".getBytes());
  Map preserved=AgentInstallLayout.inventory(root.resolve("conf").toFile()),anchor=AgentInstallLayout.inventory(boot.toFile());
  String version=str(afterManifest.get("version")),hash=str(((Map)((Map)afterManifest.get("files")).get("manager/helper.jar")).get("sha256")),id="00000000-0000-0000-0000-000000000053";
  Map claim=AssessmentScan.map("operationId",id,"executable",true,"targetVersion",version,"signedPlanHash","a".repeat(64),"expiresMillis",System.currentTimeMillis()+600000L,"bundleSha256",afterManifest.get("sha256"),"managerSha256",hash);
  Map action=new LinkedHashMap(claim);action.put("type","UPDATE_AGENT_V3");AgentInstallLayout.durable(l.request(),action);
  List<String> reports=new ArrayList<>();List<URLClassLoader> loaders=new ArrayList<>();
  try{
   Object original=worker(manager.resolve("helper.jar"),root,home,conf,target,claim,reports,false,loaders);invoke(original,"cycle");
   check(AgentInstallLayout.read(l.journal()).get("phase").equals("REMOTE_CONFIRMING"),"original manager must activate final bundle");check(!reports.contains("SUCCEEDED"),"old manager cannot certify replacement");
   Object replacement=worker(l.manager(l.current()).toPath().resolve("helper.jar"),root,home,conf,target,claim,reports,true,loaders);invoke(replacement,"cycle");
   check(reports.contains("SUCCEEDED")&&!l.journal().exists(),"new manager confirms same operation");check(l.current().get("version").equals(version),"direct final version");check(preserved.equals(AgentInstallLayout.inventory(root.resolve("conf").toFile())),"configuration/identity/CA preserved");check(anchor.equals(AgentInstallLayout.inventory(boot.toFile())),"immutable anchor preserved");
   System.out.println("DIRECT_V3_UPGRADE=PASS "+baseline+" -> "+version+"; original manager binary, final signed bundle, same operation, identity/CA/anchor preserved; OS/network adapters");
  }finally{for(var loader:loaders)loader.close();}
 }
}
