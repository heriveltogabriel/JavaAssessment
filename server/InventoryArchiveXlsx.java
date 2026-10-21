package assessment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/**
 * Minimal streaming Office Open XML workbook: one sheet per collection plus "Mapa de bancos".
 * Inline strings only (no shared-string table), so rows are written once and never buffered.
 * Values arrive already sanitized by {@link InventoryArchive#document}.
 */
final class InventoryArchiveXlsx {
    static final int CELL_LIMIT=32_000, ROW_LIMIT=1_048_575;
    static final String CUT=" … [cortado; detalhe completo na exportação JSON]";
    /** Columns shown first when present; the rest follow in alphabetical order. */
    static final List<String> PREFERRED=List.of("id","hostId","hostname","fqdn","name","type","kind","category","applicationType","serverType",
        "version","javaVersion","javaVendor","vendor","distribution","path","javaHome","pid","mainClass","launchJar","applicationName","serverName",
        "domainName","jndiName","databaseType","databaseHost","databasePort","databaseName","driverName","driverArtifact","driverVersion","driverResolution","support","status","state");
    private final ZipOutputStream zip;
    private final Consumer<String> budget;
    private final List<String> sheets=new ArrayList<>();

    InventoryArchiveXlsx(OutputStream target,Consumer<String> budget){
        // The workbook is itself a ZIP written inside the export entry; closing it must not close the export.
        this.zip=new ZipOutputStream(new FilterOutputStream(target){@Override public void write(byte[] b,int o,int n)throws IOException{out.write(b,o,n);}@Override public void close()throws IOException{flush();}},StandardCharsets.UTF_8);
        this.budget=budget;
    }

    final class Sheet implements AutoCloseable {
        private final Writer out;private int rows;private final int columns;
        Sheet(String name,List<String> header)throws IOException{
            sheets.add(name);zip.putNextEntry(new ZipEntry("xl/worksheets/sheet"+sheets.size()+".xml"));
            out=new BufferedWriter(new OutputStreamWriter(new FilterOutputStream(zip){@Override public void write(byte[] b,int o,int n)throws IOException{out.write(b,o,n);}@Override public void close()throws IOException{flush();}},StandardCharsets.UTF_8),65536);
            columns=Math.max(1,header.size());
            text("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                +"<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
                +"<sheetFormatPr defaultRowHeight=\"15\"/><cols><col min=\"1\" max=\""+columns+"\" width=\"24\" customWidth=\"1\"/></cols><sheetData>");
            row(new ArrayList<>(header),true);
        }
        void row(List<Object> values)throws IOException{row(values,false);}
        private void row(List<Object> values,boolean header)throws IOException{
            if(++rows>ROW_LIMIT)throw new InventoryArchive.Failure("Uma aba da planilha excedeu o limite de linhas do Excel; use a exportação JSON.");
            StringBuilder b=new StringBuilder("<row r=\"").append(rows).append("\">");
            for(int i=0;i<values.size();i++){
                Object v=values.get(i);if(v==null)continue;
                String ref=column(i)+rows;
                if(!header&&v instanceof Number n&&Double.isFinite(n.doubleValue())){b.append("<c r=\"").append(ref).append("\"><v>").append(n instanceof Double d&&d==Math.rint(d)&&Math.abs(d)<1e15?String.valueOf(d.longValue()):String.valueOf(n)).append("</v></c>");continue;}
                String t=v instanceof String s?s:String.valueOf(v);if(t.isEmpty())continue;
                if(t.length()>CELL_LIMIT)t=t.substring(0,CELL_LIMIT)+CUT;
                b.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(header?" s=\"1\"":"").append("><is><t xml:space=\"preserve\">").append(escape(t)).append("</t></is></c>");
            }
            text(b.append("</row>").toString());
        }
        int rows(){return rows;}
        private void text(String s)throws IOException{budget.accept(s);out.write(s);}
        @Override public void close()throws IOException{
            text("</sheetData><autoFilter ref=\"A1:"+column(columns-1)+Math.max(1,rows)+"\"/></worksheet>");out.flush();zip.closeEntry();
        }
    }
    Sheet sheet(String name,List<String> header)throws IOException{return new Sheet(name,header);}

    void finish()throws IOException{
        StringBuilder types=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            +"<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            +"<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            +"<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder book=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for(int i=1;i<=sheets.size();i++){
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            book.append("<sheet name=\"").append(escape(sheets.get(i-1))).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(sheets.size()+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        entry("[Content_Types].xml",types.append("</Types>").toString());
        entry("_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        entry("xl/workbook.xml",book.append("</sheets></workbook>").toString());
        entry("xl/_rels/workbook.xml.rels",rels.toString());
        entry("xl/styles.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            +"<fonts count=\"2\"><font><sz val=\"10\"/><name val=\"Arial\"/></font><font><b/><sz val=\"10\"/><name val=\"Arial\"/></font></fonts>"
            +"<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            +"<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            +"<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            +"<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
            +"<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        zip.finish();zip.flush();
    }
    private void entry(String name,String text)throws IOException{byte[] data=text.getBytes(StandardCharsets.UTF_8);budget.accept(text);zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}

    /** Spreadsheet-friendly text for nested evidence; the JSON export keeps the full structure. */
    @SuppressWarnings("unchecked")
    static Object cell(String key,Object v){
        if(v==null||v instanceof Number||v instanceof String)return v;
        if(v instanceof Boolean b)return b?"sim":"não";
        if(v instanceof Map<?,?> m){
            Map<String,Object> map=(Map<String,Object>)m;
            if(key.equals("support"))return join(" — ",map.get("status"),map.get("linha"),map.get("fimPrincipal"));
            if(key.equals("coverage")){Object w=map.get("warnings");return join(" — ",map.get("status"),w instanceof List<?> l?l.size()+" observação(ões)":null);}
            if(key.equals("driverResult"))return join(" — ",map.get("driverResolution"),map.get("driverArtifact"),map.get("driverVersion"));
            return Json.stringify(map);
        }
        if(v instanceof List<?> list){
            StringBuilder b=new StringBuilder();
            for(Object item:list){
                String part;
                if(item instanceof Map<?,?> m){
                    Map<String,Object> e=(Map<String,Object>)m;
                    part=switch(key){
                        case "driverCandidates"->join(" ",e.get("path"),e.get("version")==null?null:"("+e.get("version")+")",Boolean.FALSE.equals(e.get("onClasspath"))?"[fora do classpath]":null);
                        case "associations"->join(" ","PID "+InventoryArchiveMap.s(e.get("pid"))+":",InventoryArchiveMap.first(e.get("serverName"),e.get("applicationName")),
                            e.get("driverResult") instanceof Map<?,?> d?"["+InventoryArchiveMap.statusLabel(InventoryArchiveMap.s(((Map<String,Object>)d).get("driverResolution")))+"]":null);
                        case "databaseEndpoints"->InventoryArchiveMap.s(e.get("host"))+(e.get("port")==null?"":":"+InventoryArchiveMap.s(e.get("port")))+(InventoryArchiveMap.first(e.get("service"),e.get("database"),e.get("sid")).isEmpty()?"":"/"+InventoryArchiveMap.first(e.get("service"),e.get("database"),e.get("sid")));
                        case "disks"->join(" ",e.get("mount"),e.get("totalBytes")==null?null:InventoryArchiveMap.s(e.get("totalBytes"))+" bytes");
                        default->Json.stringify(e);
                    };
                }else part=InventoryArchiveMap.s(item);
                if(part.isEmpty())continue;
                if(b.length()>0)b.append("; ");b.append(part);
                if(b.length()>CELL_LIMIT)break;
            }
            return b.toString();
        }
        return String.valueOf(v);
    }
    private static String join(String sep,Object... parts){StringBuilder b=new StringBuilder();for(Object p:parts){String t=InventoryArchiveMap.s(p);if(t.isEmpty())continue;if(b.length()>0)b.append(sep);b.append(t);}return b.toString();}
    static List<String> columns(Set<String> present){
        List<String> out=new ArrayList<>();for(String k:PREFERRED)if(present.contains(k))out.add(k);
        TreeSet<String> rest=new TreeSet<>(present);rest.removeAll(out);out.addAll(rest);return out;
    }
    static String column(int index){StringBuilder b=new StringBuilder();for(int i=index+1;i>0;i=(i-1)/26)b.insert(0,(char)('A'+(i-1)%26));return b.toString();}
    static String escape(String s){
        StringBuilder b=new StringBuilder(s.length()+16);
        for(int i=0;i<s.length();i++){char c=s.charAt(i);
            switch(c){case '&'->b.append("&amp;");case '<'->b.append("&lt;");case '>'->b.append("&gt;");case '"'->b.append("&quot;");
                default->{if(c<0x20&&c!='\t'&&c!='\n'&&c!='\r'||c==0xFFFE||c==0xFFFF)b.append(' ');else if(Character.isSurrogate(c)&&!(Character.isHighSurrogate(c)&&i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1)))&&!(Character.isLowSurrogate(c)&&i>0&&Character.isHighSurrogate(s.charAt(i-1))))b.append(' ');else b.append(c);}}
        }
        return b.toString();
    }
}
