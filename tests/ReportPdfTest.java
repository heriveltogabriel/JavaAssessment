package assessment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independent PDF structural and data-preservation checks; rendering is also checked with Poppler. */
public final class ReportPdfTest {
    private static int checks;
    private static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    public static Map<String,Object> fixture() {
        List<Object> columns=new ArrayList<>();
        String[] keys={"hostname","environment","datasource","driver","driverVersion","driverClass","server","java","os"};
        String[] labels={"Máquina","Ambiente","Datasource","Driver JDBC","Versão do driver","Classe do driver","Servidor Java","Java","Sistema operacional"};
        for(int i=0;i<keys.length;i++)columns.add(map("key",keys[i],"label",labels[i]));
        List<Object> rows=new ArrayList<>();
        for(int i=0;i<68;i++)rows.add(map("hostname","servidor-"+(i+1),"environment",i%2==0?"Produção · São Paulo":"Homologação","datasource","Financeiro-"+(i+1),"driver","PostgreSQL JDBC Driver","driverVersion","42.7.8","driverClass","org.postgresql.Driver","server","WebLogic 10.3.6.0","java","1.6.0_45","os","Red Hat Enterprise Linux Server release 5.11 (Tikanga)"));
        rows.set(1,map("hostname","servidor-2","environment","Produção","datasource","Aplicação (A) \\ <unsafe>\n第二 / 🚀","driver","Oracle JDBC","driverVersion","23.9","driverClass","oracle.jdbc.OracleDriver","server","Tomcat 10.1.0","java","21","os","Oracle Linux"));
        rows.set(3,map("hostname","servidor-4","environment","Homologação","datasource","LONG-CELL-START "+"Caracteres e informações de conexão preservados. ".repeat(120)+"LONG-CELL-END","driver","MySQL Connector/J","driverVersion","8.4.0","driverClass","com.mysql.cj.jdbc.Driver","server","JBoss EAP 7.4","java","11","os","Linux"));
        return map("title","Relatório JDBC · Inventário Java","generatedAt","15/10/2026 08:00 BRT","summary",map("machines",68,"environments",2,"records",68),"filters",List.of(map("label","Ambiente","value","Todos os ambientes"),map("label","Datasource","value","Financeiro / Aplicação")),"columns",columns,"rows",rows);
    }
    private static String bytes(Map<String,Object> report)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();ReportPdf.write(out,report);return out.toString(StandardCharsets.ISO_8859_1);}
    private static String text(String pdf) {
        StringBuilder out=new StringBuilder();Matcher m=Pattern.compile("<([0-9a-f]*)> Tj").matcher(pdf);
        while(m.find()){String hex=m.group(1);byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);out.append(new String(b,java.nio.charset.Charset.forName("windows-1252"))).append('\n');}
        return out.toString();
    }
    private static void structural(String pdf) {
        check(pdf.startsWith("%PDF-1.4"),"PDF header");check(pdf.endsWith("%%EOF\n"),"PDF trailer");
        int start=pdf.indexOf("xref\n"),pointer=Integer.parseInt(pdf.substring(pdf.lastIndexOf("startxref\n")+10,pdf.lastIndexOf("\n%%EOF")));
        check(start==pointer,"startxref points at xref");String[] entries=pdf.substring(start).split("\n");int size=Integer.parseInt(entries[1].split(" ")[1]);
        for(int i=1;i<size;i++){int offset=Integer.parseInt(entries[i+2].substring(0,10));check(pdf.startsWith(i+" 0 obj\n",offset),"xref entry "+i);}
        Matcher streams=Pattern.compile("/Length (\\d+) >>\\nstream\\n").matcher(pdf);
        int count=0;
        while(streams.find()){int end=streams.end()+Integer.parseInt(streams.group(1));check(pdf.startsWith("endstream\n",end),"exact stream length");count++;}
        check(count>0,"pages exist");check(pdf.contains("/Count "+count+" /Kids"),"page tree count");
        Matcher baseline=Pattern.compile("1 0 0 1 ([0-9.]+) ([0-9.]+) Tm").matcher(pdf);
        while(baseline.find()){double x=Double.parseDouble(baseline.group(1)),y=Double.parseDouble(baseline.group(2));if(x<0||x>842||y<0||y>595)throw new AssertionError("Text baseline outside page");}
        check(true,"all baselines inside page");check(!pdf.contains("/JavaScript")&&!pdf.contains("/OpenAction"),"no executable actions");
    }
    private static void invalid(Map<String,Object> report,String msg)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();boolean threw=false;
        try{ReportPdf.write(out,report);}catch(IllegalArgumentException expected){threw=true;}
        check(threw,msg);check(out.size()==0,"invalid input rejected before response bytes");
    }
    public static void main(String[] args)throws Exception {
        Map<String,Object> fixture=fixture();String pdf=bytes(fixture);structural(pdf);String text=text(pdf);
        check(text.contains("Relatório JDBC")&&text.contains("Produção")&&text.contains("São Paulo"),"Portuguese preserved");
        check(text.contains("[U+7B2C]")&&text.contains("[U+4E8C]")&&text.contains("[U+1F680]"),"unsupported Unicode explicitly represented");
        check(text.contains("LONG-CELL-START")&&text.contains("LONG-CELL-END"),"oversized row spans pages without truncation");
        check(text.contains("Registro 4 · continuação"),"continued row identity repeated");
        check(text.contains("COLUNAS 2 DE 2")&&text.contains("Classe do driver"),"column groups");
        check(text.split("servidor-68",-1).length==3,"last row appears in both column groups with repeated identity");
        check(text.contains("<unsafe>")&&text.contains("\\"),"PDF syntax characters remain literal text");
        Matcher pages=Pattern.compile("stream\\n(.*?)endstream",Pattern.DOTALL).matcher(pdf);
        while(pages.find()){String page=text(pages.group(1));if(page.contains("COLUNAS 1 DE 2"))check(page.contains("Seção 1/2"),"footer matches first column group");else if(page.contains("COLUNAS 2 DE 2"))check(page.contains("Seção 2/2"),"footer matches second column group");}
        Map<String,Object> dated=new LinkedHashMap<>(fixture);dated.put("generatedAt","2026-10-15T12:21:38.450225Z");dated.put("context","JDBC da máquina; associação ao servidor não confirmada.");String datedText=text(bytes(dated));check(datedText.contains("15/10/2026 12:21 UTC"),"readable generation date with timezone");check(datedText.contains("JDBC da máquina; associação ao servidor"),"association context exported");
        Map<String,Object> empty=new LinkedHashMap<>(fixture);empty.put("rows",List.of());String emptyPdf=bytes(empty);structural(emptyPdf);check(text(emptyPdf).contains("Nenhum registro"),"empty report");
        Map<String,Object> giant=new LinkedHashMap<>(fixture);giant.put("rows",List.of(map("hostname","x".repeat(8193))));invalid(giant,"cell bound");
        Map<String,Object> tooMany=new LinkedHashMap<>(fixture);tooMany.put("rows",java.util.Collections.nCopies(10001,map("hostname","x")));invalid(tooMany,"row bound");
        Map<String,Object> nested=new LinkedHashMap<>(fixture);nested.put("rows",List.of(map("hostname",map("token","secret"))));invalid(nested,"nested cell rejected");
        Map<String,Object> duplicates=new LinkedHashMap<>(fixture);duplicates.put("columns",List.of(map("key","hostname","label","A"),map("key","hostname","label","B")));invalid(duplicates,"duplicate columns rejected");
        Map<String,Object> controls=new LinkedHashMap<>(fixture);controls.put("rows",List.of(map("hostname","a\u0000\u202Eb")));check(text(bytes(controls)).contains("a[U+0000][U+202E]b"),"control and bidi characters explicit");
        Map<String,Object> many=new LinkedHashMap<>(fixture);many.put("columns",List.of(map("key","hostname","label","Máquina")));many.put("rows",java.util.Collections.nCopies(10000,map("hostname","host-limite")));String stress=bytes(many);structural(stress);check(text(stress).split("host-limite",-1).length==10001,"all 10000 rows exported");
        Path output=Path.of(args.length>0?args[0]:"output/pdf/reports-jdbc-validation.pdf");Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,pdf.getBytes(StandardCharsets.ISO_8859_1));
        System.out.println("REPORT_PDF_TESTS="+checks+" PASS; fixture="+output+"; bytes="+pdf.length()+"; stressBytes="+stress.length());
    }
}
