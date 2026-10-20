package assessment;

import java.util.*;
import java.util.function.Consumer;

/** One resource per row; nested evidence remains complete JSON within quoted cells. */
final class InventoryArchiveCsv {
    private final Consumer<String> output;
    private final InventoryStore store;
    private final List<String> fields;
    private final Map<String,Map<String,Object>> cache=new LinkedHashMap<>(128,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,Map<String,Object>> e){return size()>128;}
    };
    InventoryArchiveCsv(Consumer<String> output,InventoryStore store,List<String> fields){this.output=output;this.store=store;this.fields=fields;}
    void start(Map<String,Object> metadata){
        output.accept("\uFEFF");
        List<Object> header=new ArrayList<>(List.of("recordType","machineName","environmentName","hostIpAddresses","exportMetadata"));header.addAll(fields);row(header);
        List<Object> values=new ArrayList<>(Arrays.asList("metadata",null,null,null,metadata));for(String field:fields)values.add(null);row(values);
    }
    void record(String kind,Map<String,Object> record){
        Map<String,Object> host=kind.equals("hosts")?record:document("hosts",record.get("hostId"));
        Map<String,Object> env=kind.equals("environments")?record:document("environments",host.get("environmentId"));
        List<Object> values=new ArrayList<>(Arrays.asList(kind,host.get("hostname"),env.get("name"),host.get("ipAddresses"),null));
        for(String field:fields)values.add(record.get(field));row(values);
    }
    private Map<String,Object> document(String table,Object id){
        if(!(id instanceof String key)||key.isEmpty())return Map.of();
        return cache.computeIfAbsent(table+":"+key,ignored->{Map<String,Object> raw=store.get(table,key);return raw==null?Map.of():InventoryArchive.document(table,raw);});
    }
    private void row(List<Object> values){
        boolean first=true;
        for(Object value:values){if(!first)output.accept(",");first=false;
            if(value==null)continue;
            if(value instanceof Number||value instanceof Boolean){output.accept(String.valueOf(value));continue;}
            String text=value instanceof String s?s:Json.stringify(value);
            output.accept("\""+(formula(text)?"'":"")+text.replace("\"","\"\"")+"\"");
        }
        output.accept("\r\n");
    }
    private static boolean formula(String text){
        for(int i=0;i<text.length();){int cp=text.codePointAt(i);
            if(cp=='\t'||cp=='\r'||cp=='\n'||cp==0xFEFF)return true;
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)||Character.isISOControl(cp)){i+=Character.charCount(cp);continue;}
            return cp=='='||cp=='+'||cp=='-'||cp=='@';
        }return false;
    }
}
