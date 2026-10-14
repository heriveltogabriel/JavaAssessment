package assessment;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Shared verifier with real RSA/ZIP bytes; temporary files only, no service or product key. */
public final class AgentBundleManifestTest {
    static int checks;
    static KeyPair key;
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message);checks++; }
    static Map map(Object... values) { Map m=new LinkedHashMap();for(int i=0;i<values.length;i+=2)m.put(values[i],values[i+1]);return m; }
    static void write(File file,byte[] bytes)throws Exception { file.getParentFile().mkdirs();FileOutputStream o=new FileOutputStream(file);try{o.write(bytes);}finally{o.close();} }
    static byte[] read(File file)throws Exception { FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream();try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}finally{in.close();}return out.toByteArray(); }
    static int u16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    static long u32(byte[] b,int p){return (long)u16(b,p)|((long)u16(b,p+2)<<16);}
    static void put32(byte[] b,int p,long n){for(int i=0;i<4;i++)b[p+i]=(byte)(n>>(i*8));}
    static String digest(byte[] b)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");StringBuilder s=new StringBuilder();for(byte v:d.digest(b))s.append(String.format("%02x",v&255));return s.toString();}
    static byte[] zip(Map<String,byte[]> entries)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out);
        for(Map.Entry<String,byte[]> item:entries.entrySet()){ZipEntry e=new ZipEntry(item.getKey());e.setTime(631152000000L);zip.putNextEntry(e);zip.write(item.getValue());zip.closeEntry();}zip.close();
        byte[] b=out.toByteArray();int p=(int)u32(b,b.length-6);
        while(u32(b,p)==0x02014b50L){String n=new String(b,p+46,u16(b,p+28),"US-ASCII");b[p+5]=3;put32(b,p+38,(long)(0100000|(n.endsWith(".sh")?0700:0600))<<16);p+=46+u16(b,p+28)+u16(b,p+30)+u16(b,p+32);}
        return b;
    }
    static void remove(File file){if(file.isDirectory())for(File c:file.listFiles())remove(c);file.delete();}
    static final class Fixture implements Closeable {
        final File base,manifest,signature,pin,bundle; final Map<String,byte[]> entries=new LinkedHashMap<String,byte[]>(); Map header;
        Fixture()throws Exception {
            base=File.createTempFile("bundle-manifest-test-","").getCanonicalFile();base.delete();base.mkdir();
            manifest=new File(base,"manifest.json");signature=new File(base,"manifest.sig");pin=new File(base,"pin.der");bundle=new File(base,"bundle.zip");
            entries.put("code/collector.jar","collector bytes".getBytes("UTF-8"));entries.put("code/run-once.sh","#!/bin/sh\nexit 0\n".getBytes("UTF-8"));
            entries.put("manager/helper.jar","helper bytes".getBytes("UTF-8"));entries.put("defaults/collector.properties.example","example only\n".getBytes("UTF-8"));
            write(pin,key.getPublic().getEncoded());write(bundle,zip(entries));reset();
        }
        void reset()throws Exception {
            Map files=new LinkedHashMap();for(Map.Entry<String,byte[]> item:entries.entrySet()) {String name=item.getKey();files.put(name,map("bytes",Long.valueOf(item.getValue().length),"sha256",digest(item.getValue()),"mode",name.endsWith(".sh")?"700":"600","role",name.startsWith("code/")?"code":name.startsWith("manager/")?"manager":name.startsWith("bootstrap/")?"bootstrap":"default"));}
            header=map("schema",Long.valueOf(1),"type","ASSESSMENT_AGENT_BUNDLE_V1","format","agent-bundle-v1","version","9.0.2","minUpdater",Long.valueOf(3),"minJava",Long.valueOf(6),"platform","linux","bytes",Long.valueOf(bundle.length()),"sha256",AgentBundleManifest.hash(bundle),"files",files);sign();
        }
        void changedZip(byte[] bytes)throws Exception{write(bundle,bytes);header.put("bytes",Long.valueOf(bytes.length));header.put("sha256",digest(bytes));sign();}
        void sign()throws Exception{byte[] raw=Json.stringify(header).getBytes("UTF-8");write(manifest,raw);Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key.getPrivate());s.update(raw);write(signature,s.sign());}
        Map verify()throws Exception{return AgentBundleManifest.verify(manifest,signature,pin,bundle,"9.0.2");}
        void refused(String name)throws Exception{try{verify();throw new AssertionError("accepted "+name);}catch(AssertionError e){throw e;}catch(Exception expected){check(true,name+" refused");}check(!new File(base,"extract").exists(),name+" did not extract");}
        public void close(){remove(base);}
    }
    static void failures()throws Exception {
        String[] names={"signature","key","unknown-field","schema","minimum","java","platform","version","size","hash","inventory-extra","file-hash","file-size","role","mode","traversal","partial-bootstrap","too-many","expanded-limit","unix-symlink","unix-fifo","writable","local-name","local-method","local-crc","central-crc","duplicate","zip-comment","zip-extra","prepend","trailing","payload-crc"};
        for(String name:names){Fixture f=new Fixture();try {
            Map files=(Map)f.header.get("files"), spec=(Map)files.get("code/collector.jar");byte[] b=read(f.bundle);int central=(int)u32(b,b.length-6);
            if(name.equals("signature")){byte[] s=read(f.signature);s[0]^=1;write(f.signature,s);}
            else if(name.equals("key")){KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);write(f.pin,g.generateKeyPair().getPublic().getEncoded());}
            else if(name.equals("unknown-field")){f.header.put("command","forbidden");f.sign();}
            else if(name.equals("schema")){f.header.put("schema",Long.valueOf(2));f.sign();}
            else if(name.equals("minimum")){f.header.put("minUpdater",Long.valueOf(2));f.sign();}
            else if(name.equals("java")){f.header.put("minJava",Long.valueOf(7));f.sign();}
            else if(name.equals("platform")){f.header.put("platform","windows");f.sign();}
            else if(name.equals("version")){f.header.put("version","9.0.3");f.sign();}
            else if(name.equals("size")){f.header.put("bytes",Long.valueOf(AgentBundleManifest.MAX_ZIP+1));f.sign();}
            else if(name.equals("hash")){f.header.put("sha256",String.join("",Collections.nCopies(64,"0")));f.sign();}
            else if(name.equals("inventory-extra")){files.put("code/absent.sh",map("bytes",Long.valueOf(0),"sha256",digest(new byte[0]),"mode","700","role","code"));f.sign();}
            else if(name.equals("file-hash")){spec.put("sha256",digest(new byte[0]));f.sign();}
            else if(name.equals("file-size")){spec.put("bytes",Long.valueOf(0));f.sign();}
            else if(name.equals("role")){spec.put("role","bootstrap");f.sign();}
            else if(name.equals("mode")){spec.put("mode","700");f.sign();}
            else if(name.equals("traversal")){files.put("code/../escape.sh",files.remove("code/run-once.sh"));f.sign();}
            else if(name.equals("partial-bootstrap")){files.put("bootstrap/anchor.jar",map("bytes",Long.valueOf(0),"sha256",digest(new byte[0]),"mode","600","role","bootstrap"));f.sign();}
            else if(name.equals("too-many")){for(int i=0;i<260;i++)files.put("code/file"+i+".sh",map("bytes",Long.valueOf(0),"sha256",digest(new byte[0]),"mode","700","role","code"));f.sign();}
            else if(name.equals("expanded-limit")){spec.put("bytes",Long.valueOf(AgentBundleManifest.MAX_EXPANDED+1));f.sign();}
            else if(name.equals("unix-symlink")){put32(b,central+38,(long)(0120000|0600)<<16);f.changedZip(b);}
            else if(name.equals("unix-fifo")){put32(b,central+38,(long)(0010000|0600)<<16);f.changedZip(b);}
            else if(name.equals("writable")){put32(b,central+38,(long)(0100000|0666)<<16);f.changedZip(b);}
            else if(name.equals("local-name")){b[30]^=1;f.changedZip(b);}
            else if(name.equals("local-method")){b[8]=0;f.changedZip(b);}
            else if(name.equals("local-crc")){b[14]=1;f.changedZip(b);}
            else if(name.equals("central-crc")){b[central+16]^=1;f.changedZip(b);}
            else if(name.equals("duplicate")){String original="code/collector.jar", replacement="manager/helper.jar";check(original.length()==replacement.length(),"duplicate test names same length");byte[] needle=original.getBytes("US-ASCII"),value=replacement.getBytes("US-ASCII");for(int i=0;i<=b.length-needle.length;i++){boolean match=true;for(int n=0;n<needle.length;n++)if(b[i+n]!=needle[n])match=false;if(match)System.arraycopy(value,0,b,i,value.length);}f.changedZip(b);}
            else if(name.equals("zip-comment")){b[b.length-2]=1;f.changedZip(b);}
            else if(name.equals("zip-extra")){b[central+30]=1;f.changedZip(b);}
            else if(name.equals("prepend")){byte[] p=new byte[b.length+1];System.arraycopy(b,0,p,1,b.length);f.changedZip(p);}
            else if(name.equals("trailing")){f.changedZip(Arrays.copyOf(b,b.length+1));}
            else if(name.equals("payload-crc")){b[30+u16(b,26)]^=1;f.changedZip(b);}
            f.refused(name);
        }finally{f.close();}}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==5){Map verified=AgentBundleManifest.verify(new File(args[0]).getCanonicalFile(),new File(args[1]).getCanonicalFile(),new File(args[2]).getCanonicalFile(),new File(args[3]).getCanonicalFile(),args[4]);System.out.println("SIGNED_BUNDLE_VERIFIED="+((Map)verified.get("files")).size());return;}
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);key=generator.generateKeyPair();
        Fixture f=new Fixture();try {
            Map header=AgentBundleManifest.verifyHeader(f.manifest,f.signature,f.pin,"9.0.2");check(header.get("version").equals("9.0.2"),"signed header verified before ZIP download");
            Map verified=f.verify();File extracted=new File(f.base,"extract");AgentBundleManifest.extractVerified(verified,f.bundle,extracted);
            for(Map.Entry<String,byte[]> e:f.entries.entrySet())check(Arrays.equals(read(new File(extracted,e.getKey())),e.getValue()),"strict extraction "+e.getKey());
            try{verified.put("version","changed");throw new AssertionError("mutable header");}catch(UnsupportedOperationException expected){checks++;}
            try{((Map)((Map)verified.get("files")).get("code/collector.jar")).put("mode","700");throw new AssertionError("mutable inventory");}catch(UnsupportedOperationException expected){checks++;}
            try{AgentBundleManifest.extractVerified(f.header,f.bundle,new File(f.base,"forged"));throw new AssertionError("forged verifier result");}catch(IOException expected){checks++;}
            try{AgentBundleManifest.extractVerified(verified,f.bundle,extracted);throw new AssertionError("overwrite extraction");}catch(IOException expected){checks++;}
            byte[] b=read(f.bundle);b[0]^=1;write(f.bundle,b);File stale=new File(f.base,"stale");try{AgentBundleManifest.extractVerified(verified,f.bundle,stale);throw new AssertionError("bundle changed after verify");}catch(IOException expected){check(!stale.exists(),"changed bundle refused before extraction");}
        }finally{f.close();}
        failures();System.out.println("AgentBundleManifestTest PASS "+checks+" checks");
    }
}
