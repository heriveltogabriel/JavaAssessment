package assessment;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

/** Shared signed, bounded bundle inventory. This class never executes bundle content. */
public final class AgentBundleManifest {
    public static final long MAX_ZIP = 64L * 1024 * 1024;
    public static final long MAX_EXPANDED = 128L * 1024 * 1024;
    public static final int MAX_FILES = 256;
    private static final String[] FIELDS = {"schema","type","format","version","minUpdater","minJava","platform","bytes","sha256","files"};
    private AgentBundleManifest() {}
    private static void need(boolean ok) throws IOException { if (!ok) throw new IOException("Invalid signed agent bundle"); }
    private static long number(Object value) { return value instanceof Long || value instanceof Integer ? ((Number)value).longValue() : -1; }
    private static String text(Object value) { return value instanceof String ? (String)value : ""; }
    private static Set keys(String[] names) { return new HashSet(Arrays.asList(names)); }
    private static boolean version(String value) { return value.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"); }
    private static String hex(byte[] data) { StringBuffer out = new StringBuffer(); for (int i=0;i<data.length;i++) { int n=data[i]&255; if(n<16)out.append('0'); out.append(Integer.toHexString(n)); } return out.toString(); }
    private static void regular(File file) throws IOException { need(file.isFile() && file.getAbsoluteFile().equals(file.getCanonicalFile())); }
    private static byte[] read(File file, long max) throws IOException {
        regular(file); need(file.length()>0 && file.length()<=max);
        InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream();
        try { byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) { need(out.size()+n<=max); out.write(b,0,n); } }
        finally { in.close(); } return out.toByteArray();
    }
    public static String hash(File file) throws Exception {
        regular(file); MessageDigest digest=MessageDigest.getInstance("SHA-256"); InputStream in=new FileInputStream(file);
        try { byte[] b=new byte[16384]; int n; while((n=in.read(b))!=-1)digest.update(b,0,n); } finally { in.close(); }
        return hex(digest.digest());
    }
    private static String role(String path) {
        if(path.length()>180 || path.indexOf("..")>=0)return "";
        if(path.equals("code/collector.jar") || path.matches("code/[A-Za-z0-9_-]+[.]sh") || path.matches("code/lib/[A-Za-z0-9_.-]+[.]jar"))return "code";
        if(path.equals("manager/helper.jar") || path.matches("manager/lib/[A-Za-z0-9_.-]+[.]jar"))return "manager";
        if(path.equals("bootstrap/anchor.jar") || path.equals("bootstrap/root-trust.sh") || path.equals("bootstrap/update-bootstrap.sh")
            || path.matches("bootstrap/lib/[A-Za-z0-9_.-]+[.]jar"))return "bootstrap";
        if(path.matches("defaults/[A-Za-z0-9_.-]+[.]example"))return "default";
        return "";
    }
    private static Map validate(Map m,String requested) throws Exception {
        need(m.keySet().equals(keys(FIELDS)) && number(m.get("schema"))==1 && "ASSESSMENT_AGENT_BUNDLE_V1".equals(m.get("type"))
            && "agent-bundle-v1".equals(m.get("format")) && number(m.get("minUpdater"))==3 && number(m.get("minJava"))==6
            && "linux".equals(m.get("platform")) && version(requested) && requested.equals(m.get("version"))
            && number(m.get("bytes"))>0 && number(m.get("bytes"))<=MAX_ZIP && text(m.get("sha256")).matches("[a-f0-9]{64}")
            && m.get("files") instanceof Map);
        Map files=(Map)m.get("files"); need(files.size()>0 && files.size()<=MAX_FILES && files.containsKey("code/collector.jar") && files.containsKey("manager/helper.jar"));
        long total=0; int bootstrap=0; Map immutable=new LinkedHashMap();
        for(Object item:files.entrySet()) {
            Map.Entry e=(Map.Entry)item; String path=text(e.getKey()), expected=role(path); need(!expected.equals("") && e.getValue() instanceof Map);
            Map f=(Map)e.getValue(); String mode=text(f.get("mode"));
            need(f.keySet().equals(keys(new String[]{"bytes","sha256","mode","role"})) && number(f.get("bytes"))>=0
                && number(f.get("bytes"))<=MAX_EXPANDED && text(f.get("sha256")).matches("[a-f0-9]{64}")
                && expected.equals(f.get("role")) && ("600".equals(mode)||"700".equals(mode))
                && mode.equals(path.endsWith(".sh")?"700":"600"));
            total+=number(f.get("bytes")); need(total<=MAX_EXPANDED); if(expected.equals("bootstrap"))bootstrap++;
            immutable.put(path,Collections.unmodifiableMap(new LinkedHashMap(f)));
        }
        need(bootstrap==0 || (files.containsKey("bootstrap/anchor.jar") && files.containsKey("bootstrap/root-trust.sh") && files.containsKey("bootstrap/update-bootstrap.sh")));
        Map copy=new LinkedHashMap(m); copy.put("files",Collections.unmodifiableMap(immutable)); return copy;
    }
    private static final class Verified extends AbstractMap {
        private final Map value;
        Verified(Map m) { value=Collections.unmodifiableMap(m); }
        public Set entrySet() { return value.entrySet(); }
        public Object get(Object key) { return value.get(key); }
    }
    public static Map verify(File manifest,File signature,File pin,File bundle,String requestedVersion) throws Exception {
        Map m=verifyHeader(manifest,signature,pin,requestedVersion); verifyArchive(m,bundle,null); return m;
    }
    /** Allows a bounded download using authenticated metadata before the ZIP exists. */
    public static Map verifyHeader(File manifest,File signature,File pin,String requestedVersion) throws Exception {
        byte[] raw=read(manifest,65536), signed=read(signature,1024), keyBytes=read(pin,8192);
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        need(key instanceof RSAPublicKey && ((RSAPublicKey)key).getModulus().bitLength()==3072 && signed.length==384);
        Signature verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(key); verifier.update(raw); need(verifier.verify(signed));
        String decoded=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        Object parsed=Json.parse(decoded,65536); need(parsed instanceof Map); Map m=validate((Map)parsed,requestedVersion);
        return new Verified(m);
    }
    private static int u16(RandomAccessFile in) throws IOException { return in.readUnsignedByte() | in.readUnsignedByte()<<8; }
    private static long u32(RandomAccessFile in) throws IOException { return (long)u16(in) | (long)u16(in)<<16; }
    private static byte[] bytes(RandomAccessFile in,int length) throws IOException { byte[] b=new byte[length]; in.readFully(b); return b; }
    private static String ascii(byte[] bytes) throws IOException { for(int i=0;i<bytes.length;i++)need(bytes[i]>0 && bytes[i]<127); return new String(bytes,"US-ASCII"); }
    private static final class Entry {
        String name; int flags,method; long crc,compressed,size,offset,end;
    }
    /** Read Unix file types from the central directory; ZipEntry hides these bits on Java 6. */
    private static List directory(File bundle,Map files) throws Exception {
        RandomAccessFile in=new RandomAccessFile(bundle,"r"); List entries=new ArrayList();
        try {
            long length=in.length(); need(length>=22 && length<=MAX_ZIP); in.seek(length-22); need(u32(in)==0x06054b50L);
            need(u16(in)==0 && u16(in)==0); int count=u16(in); need(count==u16(in) && count==files.size() && count<=MAX_FILES);
            long centralBytes=u32(in), centralOffset=u32(in); need(u16(in)==0 && centralOffset+centralBytes==length-22);
            in.seek(centralOffset); Set seen=new HashSet(); long expanded=0;
            for(int i=0;i<count;i++) {
                need(in.getFilePointer()+46<=length-22 && u32(in)==0x02014b50L); int made=u16(in), needed=u16(in);
                Entry e=new Entry(); e.flags=u16(in); e.method=u16(in); u16(in);u16(in);e.crc=u32(in);e.compressed=u32(in);e.size=u32(in);
                int nameLength=u16(in),extraLength=u16(in),commentLength=u16(in); need(u16(in)==0); u16(in);long attrs=u32(in);e.offset=u32(in);
                need((made>>8)==3 && needed<=20 && (e.flags & ~(8|2048))==0 && (e.method==0||e.method==8)
                    && nameLength>0 && nameLength<=180 && extraLength==0 && commentLength==0 && (attrs & 0xffff)==0);
                e.name=ascii(bytes(in,nameLength)); need(seen.add(e.name) && files.containsKey(e.name)); Map spec=(Map)files.get(e.name);
                long mode=attrs>>16; need((mode&0170000)==0100000 && (mode&07777)==("700".equals(spec.get("mode"))?0700:0600));
                need(e.size==number(spec.get("bytes")) && e.compressed<=MAX_ZIP && (expanded+=e.size)<=MAX_EXPANDED && e.offset<centralOffset);
                entries.add(e);
            }
            need(in.getFilePointer()==centralOffset+centralBytes && seen.equals(files.keySet()));
            Collections.sort(entries,new Comparator(){public int compare(Object a,Object b){long x=((Entry)a).offset,y=((Entry)b).offset;return x<y?-1:x==y?0:1;}});
            long next=0;
            for(Object item:entries) {
                Entry e=(Entry)item; need(e.offset==next); in.seek(e.offset); need(u32(in)==0x04034b50L && u16(in)<=20 && u16(in)==e.flags && u16(in)==e.method);
                u16(in);u16(in);long crc=u32(in),compressed=u32(in),size=u32(in);int nameLength=u16(in),extraLength=u16(in);
                need(nameLength==e.name.length() && extraLength==0 && ascii(bytes(in,nameLength)).equals(e.name));
                if((e.flags&8)==0)need(crc==e.crc && compressed==e.compressed && size==e.size);
                else need(crc==0 && compressed==0 && size==0);
                e.end=in.getFilePointer()+e.compressed; need(e.end<=centralOffset); in.seek(e.end);
                if((e.flags&8)!=0) { need(u32(in)==0x08074b50L && u32(in)==e.crc && u32(in)==e.compressed && u32(in)==e.size);e.end+=16; }
                next=e.end;
            }
            need(next==centralOffset); return entries;
        } finally { in.close(); }
    }
    private static void permissions(File path,boolean executable) throws IOException {
        need(path.setReadable(false,false) && path.setWritable(false,false) && path.setExecutable(false,false)
            && path.setReadable(true,true) && path.setWritable(true,true) && (!executable || path.setExecutable(true,true)));
    }
    private static void verifyArchive(Map m,File bundle,File destination) throws Exception {
        regular(bundle); need(bundle.length()==number(m.get("bytes")) && hash(bundle).equals(m.get("sha256")));
        Map files=(Map)m.get("files"); List metadata=directory(bundle,files); ZipFile zip=new ZipFile(bundle);
        try {
            need(zip.size()==metadata.size()); long total=0;
            for(Object item:metadata) {
                Entry expected=(Entry)item; ZipEntry entry=zip.getEntry(expected.name); Map spec=(Map)files.get(expected.name);
                need(entry!=null && !entry.isDirectory() && entry.getSize()==expected.size && entry.getCompressedSize()==expected.compressed
                    && entry.getMethod()==expected.method && entry.getCrc()==expected.crc);
                InputStream input=zip.getInputStream(entry); FileOutputStream output=null; File target=null;
                try {
                    if(destination!=null) {
                        target=new File(destination,expected.name); File parent=target.getParentFile();
                        need(parent.mkdirs()||parent.isDirectory()); need(parent.getCanonicalFile().equals(parent.getAbsoluteFile()));
                        for(File d=parent;!d.equals(destination);d=d.getParentFile()) { need(d!=null);permissions(d,true); }
                        need(!target.exists() && target.createNewFile()); permissions(target,false); output=new FileOutputStream(target);
                    }
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");CRC32 crc=new CRC32();long size=0;byte[] buffer=new byte[16384];int n;
                    while((n=input.read(buffer))!=-1) { size+=n;total+=n;need(size<=expected.size && total<=MAX_EXPANDED);digest.update(buffer,0,n);crc.update(buffer,0,n);if(output!=null)output.write(buffer,0,n); }
                    need(size==expected.size && crc.getValue()==expected.crc && hex(digest.digest()).equals(spec.get("sha256")));
                    if(output!=null) { output.getFD().sync(); output.close();output=null;permissions(target,"700".equals(spec.get("mode"))); }
                } finally { input.close();if(output!=null)output.close(); }
            }
        } finally { zip.close(); }
        need(hash(bundle).equals(m.get("sha256")));
    }
    /** Only a verifier-issued immutable inventory can be extracted, into fresh staging. */
    public static void extractVerified(Map manifest,File bundle,File destination) throws Exception {
        need(manifest instanceof Verified); verifyArchive(manifest,bundle,null);
        need(destination.getAbsoluteFile().equals(destination.getCanonicalFile()));
        need(!destination.exists() || (destination.isDirectory() && destination.list()!=null && destination.list().length==0));
        need(destination.mkdirs()||destination.isDirectory());permissions(destination,true);
        verifyArchive(manifest,bundle,destination);
    }
}
