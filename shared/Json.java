package assessment;

import java.util.*;

/** Small JSON codec with explicit size, depth, duplicate-key and numeric limits. */
public final class Json {
    private final String s; private int p;
    private Json(String s) { this.s=s; }
    public static Object parse(String s) { return parse(s,2097152); }
    /** Explicit larger bound is reserved for locally persisted fleet state. */
    public static Object parse(String s,int maxChars) {
        if(maxChars<1 || maxChars>33554432 || s==null || s.length()>maxChars) throw new IllegalArgumentException("JSON too large");
        Json j=new Json(s); Object v=j.value(0); j.space();
        if(j.p!=s.length()) throw new IllegalArgumentException("Trailing JSON"); return v;
    }
    private void space(){ while(p<s.length() && " \r\n\t".indexOf(s.charAt(p))>=0)p++; }
    private RuntimeException bad(){ return new IllegalArgumentException("Invalid JSON at "+p); }
    private Object value(int depth) {
        if(depth>40)throw new IllegalArgumentException("JSON depth exceeded"); space(); if(p>=s.length())throw bad();
        char c=s.charAt(p);
        if(c=='"')return string();
        if(c=='{') {p++;Map<String,Object> m=new LinkedHashMap<String,Object>();space();if(take('}'))return m;
            do {space();if(p>=s.length()||s.charAt(p)!='"')throw bad();String k=string();space();if(!take(':')||m.containsKey(k))throw bad();m.put(k,value(depth+1));space();if(take('}'))return m;}while(take(','));throw bad();}
        if(c=='[') {p++;List<Object> a=new ArrayList<Object>();space();if(take(']'))return a;
            do {a.add(value(depth+1));space();if(take(']'))return a;}while(take(','));throw bad();}
        if(s.startsWith("true",p)){p+=4;return Boolean.TRUE;}if(s.startsWith("false",p)){p+=5;return Boolean.FALSE;}if(s.startsWith("null",p)){p+=4;return null;}
        int start=p;if(take('-')){}if(p>=s.length())throw bad();
        if(take('0')){}else {if(s.charAt(p)<'1'||s.charAt(p)>'9')throw bad();while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;}
        boolean floating=false;if(take('.')){floating=true;int q=p;while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;if(q==p)throw bad();}
        if(p<s.length()&&(s.charAt(p)=='e'||s.charAt(p)=='E')){floating=true;p++;if(p<s.length()&&(s.charAt(p)=='+'||s.charAt(p)=='-'))p++;int q=p;while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;if(q==p)throw bad();}
        try {String n=s.substring(start,p);if(n.length()>64)throw bad();if(floating){Double d=Double.valueOf(n);if(d.isInfinite()||d.isNaN())throw bad();return d;}return Long.valueOf(n);}catch(NumberFormatException e){throw bad();}
    }
    private boolean take(char c){if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}
    private String string(){p++;StringBuilder b=new StringBuilder();while(p<s.length()){char c=s.charAt(p++);if(c=='"')return b.toString();if(c<32)throw bad();if(c=='\\'){if(p>=s.length())throw bad();c=s.charAt(p++);if(c=='u'){if(p+4>s.length())throw bad();try{b.append((char)Integer.parseInt(s.substring(p,p+4),16));}catch(NumberFormatException e){throw bad();}p+=4;continue;}int i="\"\\/bfnrt".indexOf(c);if(i<0)throw bad();c="\"\\/\b\f\n\r\t".charAt(i);}b.append(c);}throw bad();}
    public static String stringify(Object value){StringBuilder b=new StringBuilder();write(value,b,0);return b.toString();}
    private static void write(Object v,StringBuilder b,int d){if(d>40)throw new IllegalArgumentException("JSON depth");if(v==null){b.append("null");return;}if(v instanceof String){quote((String)v,b);return;}if(v instanceof Boolean){b.append(v);return;}if(v instanceof Number){String n=v.toString();if(n.indexOf("NaN")>=0||n.indexOf("Infinity")>=0)throw new IllegalArgumentException("Nonfinite");b.append(n);return;}if(v instanceof Map){b.append('{');boolean comma=false;for(Object x:((Map)v).entrySet()){Map.Entry e=(Map.Entry)x;if(comma)b.append(',');comma=true;quote(String.valueOf(e.getKey()),b);b.append(':');write(e.getValue(),b,d+1);}b.append('}');return;}if(v instanceof Iterable){b.append('[');boolean comma=false;for(Object x:(Iterable)v){if(comma)b.append(',');comma=true;write(x,b,d+1);}b.append(']');return;}throw new IllegalArgumentException("Unsupported JSON type");}
    private static void quote(String s,StringBuilder b){b.append('"');for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32){String h=Integer.toHexString(c);b.append("\\u");for(int z=h.length();z<4;z++)b.append('0');b.append(h);}else b.append(c);}b.append('"');}
}
