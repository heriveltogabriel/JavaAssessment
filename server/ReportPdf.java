package assessment;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline PDF writer. Uses PDF standard fonts; no installed fonts, native libraries or network. */
public final class ReportPdf {
    private ReportPdf() {}
    private static final Charset WIN = Charset.forName("windows-1252");
    private static final double W=842, H=595, M=34, RIGHT=W-M, BOTTOM=H-44, TABLE_X=M+23;
    private static final double FONT=9, LINE=13, WIDTH=RIGHT-TABLE_X;
    private static final String INK="0.15 0.18 0.13", OLIVE="0.30 0.39 0.25", MUTED="0.40 0.44 0.37";
    private static final String PALE="0.94 0.96 0.91", BORDER="0.84 0.87 0.81";

    /** Input must be the same materialized, allowlisted report used by the CSV exporter. */
    public static void write(OutputStream out, Map<String,Object> report) throws IOException {
        Data data=new Data(report); // Validate before emitting any PDF bytes.
        Pdf pdf=new Pdf(out);
        Layout layout=new Layout(pdf,data);
        layout.render();
        pdf.finish();
    }

    private record Column(String key,String label) {}
    private static final class Data {
        final String title, generatedAt, context;
        final List<Column> columns=new ArrayList<>();
        final List<Map<?,?>> rows=new ArrayList<>();
        final List<String> filters=new ArrayList<>();
        final String[] totals=new String[3];
        boolean escapedUnicode;
        long characters;
        Data(Map<String,Object> source) {
            if(source==null)throw new IllegalArgumentException("Report is required");
            title=value(source.get("title"),300,"title");
            generatedAt=formatTime(value(source.get("generatedAt"),100,"generatedAt"));
            context=value(source.get("context"),2000,"context");
            List<?> rawColumns=list(source.get("columns"));
            if(rawColumns.isEmpty()||rawColumns.size()>32)throw new IllegalArgumentException("Select between 1 and 32 columns");
            Set<String> keys=new HashSet<>();
            for(Object raw:rawColumns) {
                Map<?,?> c=map(raw);String key=scalar(c.get("key"));
                if(key.isEmpty()||key.length()>100||!keys.add(key))throw new IllegalArgumentException("Invalid report column");
                columns.add(new Column(key,value(c.get("label"),200,"column label")));
            }
            List<?> inputRows=list(source.get("rows"));
            if(inputRows.size()>10000)throw new IllegalArgumentException("Report exceeds 10000 rows; refine filters");
            for(Object raw:inputRows) {
                Map<?,?> row=map(raw);
                for(Column c:columns)validate(row.get(c.key),8192,"cell");
                rows.add(row);
            }
            List<?> inputFilters=list(source.get("filters"));
            if(inputFilters.size()>50)throw new IllegalArgumentException("Too many report filters");
            for(Object raw:inputFilters) {
                Map<?,?> f=map(raw);
                filters.add(value(f.get("label"),200,"filter label")+": "+value(f.get("value"),2000,"filter value"));
            }
            Map<?,?> summary=map(source.get("summary"));
            int i=0;
            for(String key:List.of("machines","environments","records")) {
                Object v=summary.get(key);
                if(!(v instanceof Number)||!scalar(v).matches("[0-9]{1,12}"))throw new IllegalArgumentException("Invalid report summary");
                totals[i++]=scalar(v);
            }
        }
        private void validate(Object object,int limit,String field) {
            String s=scalar(object);
            if(s.length()>limit)throw new IllegalArgumentException("Report "+field+" exceeds "+limit+" characters; refine filters");
            characters+=s.length();
            if(characters>8_000_000)throw new IllegalArgumentException("Report exceeds 8 million characters; refine filters");
            if(!WIN.newEncoder().canEncode(s)||hasControls(s))escapedUnicode=true;
        }
        private String value(Object object,int limit,String field){validate(object,limit,field);return printable(scalar(object));}
        private static List<?> list(Object o){if(o==null)return List.of();if(o instanceof List<?>)return (List<?>)o;throw new IllegalArgumentException("Invalid report list");}
        private static Map<?,?> map(Object o){if(o instanceof Map<?,?>)return (Map<?,?>)o;throw new IllegalArgumentException("Invalid report object");}
    }
    private static String formatTime(String value){
        try{return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.parse(value));}
        catch(java.time.format.DateTimeParseException ignored){return value;}
    }
    private static String scalar(Object o) {
        if(o==null)return "";
        if(o instanceof String||o instanceof Number||o instanceof Boolean)return o.toString();
        throw new IllegalArgumentException("Report cells must be scalar values");
    }
    private static boolean hasControls(String s) {
        return s.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')||Character.getType(c)==Character.FORMAT);
    }
    /** Unsupported characters remain identifiable, rather than disappearing as '?' glyphs. */
    private static String printable(String input) {
        String s=Normalizer.normalize(input,Normalizer.Form.NFC).replace("\r\n","\n").replace('\r','\n').replace("\t","    ");
        StringBuilder out=new StringBuilder();
        s.codePoints().forEach(cp->{
            String glyph=new String(Character.toChars(cp));
            if(cp=='\n'||(!Character.isISOControl(cp)&&Character.getType(cp)!=Character.FORMAT&&WIN.newEncoder().canEncode(glyph)))out.append(glyph);
            else out.append(String.format(Locale.ROOT,"[U+%04X]",cp));
        });
        return out.toString();
    }

    private static final class Layout {
        final Pdf pdf;final Data data;final List<List<Column>> groups=new ArrayList<>();
        StringBuilder page; double y; int groupIndex,pageGroup; boolean tableHeader;
        Layout(Pdf pdf,Data data) {
            this.pdf=pdf;this.data=data;
            Column identity=null;
            for(Column c:data.columns)if(Set.of("hostname","machine","machineName","hostName","host","environment").contains(c.key)){identity=c;break;}
            int offset=0;
            while(offset<data.columns.size()) {
                List<Column> group=new ArrayList<>();
                if(offset>0&&identity!=null)group.add(identity);
                while(offset<data.columns.size()&&group.size()<5) {
                    Column c=data.columns.get(offset++);if(!group.contains(c))group.add(c);
                }
                groups.add(group);
            }
        }
        void render() throws IOException {
            startPage(true);
            summary();
            if(!data.context.isBlank())paragraph(data.context,9,MUTED,WIDTH,false);
            if(data.escapedUnicode)paragraph("Caracteres fora do alfabeto Latin-1 são identificados como [U+XXXX]; o CSV preserva o texto Unicode original.",9,MUTED,WIDTH,false);
            if(!data.filters.isEmpty()) {
                section("FILTROS APLICADOS");
                for(String f:data.filters)paragraph(f,9,INK,RIGHT-M,false);
                y+=10;
            }
            for(groupIndex=0;groupIndex<groups.size();groupIndex++) {
                if(groupIndex>0){finishPage();startPage(false);}
                header();
                if(data.rows.isEmpty())paragraph("Nenhum registro encontrado para os filtros selecionados.",11,MUTED,WIDTH,false);
                for(int row=0;row<data.rows.size();row++)row(row,groups.get(groupIndex));
            }
            finishPage();
        }
        void startPage(boolean first) throws IOException {
            page=new StringBuilder();tableHeader=false;
            rect(0,0,W,7,OLIVE);
            text(M,31,"JAVA ASSESSMENT",9,true,OLIVE);
            y=48;
            if(first) {
                for(String line:wrap(data.title.isBlank()?"Relatório de inventário":data.title,RIGHT-M,23,true)) {
                    text(M,y+23,line,23,true,INK);y+=29;
                }
                if(!data.generatedAt.isBlank()) {
                    for(String line:wrap("Gerado em "+data.generatedAt,RIGHT-M,9,false)) {
                        text(M,y+13,line,9,false,MUTED);y+=15;
                    }
                }
                y+=15;
            } else {
                // Long titles were presented fully on page one; continuation heading is intentionally generic.
                text(M,y+15,"Relatório de inventário · continuação",15,true,INK);y+=34;
            }
        }
        void finishPage() throws IOException {
            if(page==null)return;
            line(M,H-32,RIGHT,H-32,BORDER);
            text(M,H-18,"Java Assessment · Inventário",8,false,MUTED);
            String footer="Página "+(pdf.pages.size()+1);
            if(tableHeader)footer+=" · Seção "+(Math.min(pageGroup+1,groups.size()))+"/"+groups.size();
            text(RIGHT-width(footer,8,false),H-18,footer,8,false,MUTED);
            pdf.page(page.toString());page=null;
        }
        void ensure(double height,boolean withTable) throws IOException {
            if(y+height>BOTTOM) {
                finishPage();startPage(false);
                if(withTable)header();
            }
        }
        void summary() throws IOException {
            ensure(70,false);
            double card=(RIGHT-M-24)/3;
            String[] labels={"MÁQUINAS","AMBIENTES","REGISTROS"};
            for(int i=0;i<3;i++) {
                double x=M+i*(card+12);rect(x,y,card,60,PALE);
                text(x+14,y+24,data.totals[i],22,true,OLIVE);
                text(x+14,y+45,labels[i],8,true,MUTED);
            }
            y+=76;
        }
        void section(String s) throws IOException {ensure(27,false);text(M,y+10,s,8,true,OLIVE);y+=22;}
        void paragraph(String s,double size,String color,double available,boolean bold) throws IOException {
            for(String part:wrap(s,available,size,bold)) {
                ensure(size+6,false);text(M,y+size,part,size,bold,color);y+=size+5;
            }
        }
        void header() throws IOException {
            List<Column> cs=groups.get(groupIndex);
            double col=WIDTH/cs.size();List<List<String>> lines=new ArrayList<>();int n=1;
            for(Column c:cs){List<String> ls=wrap(c.label,col-16,9,true);lines.add(ls);n=Math.max(n,ls.size());}
            double height=n*LINE+16;
            ensure(height+55,false);
            text(M,y+10,"RESULTADOS"+(groups.size()>1?" · COLUNAS "+(groupIndex+1)+" DE "+groups.size():""),8,true,OLIVE);y+=25;
            rect(M,y,RIGHT-M,height,PALE);
            text(M+3,y+17,"#",8,true,OLIVE);
            for(int c=0;c<cs.size();c++)for(int l=0;l<lines.get(c).size();l++)text(TABLE_X+c*col+8,y+17+l*LINE,lines.get(c).get(l),9,true,OLIVE);
            y+=height;tableHeader=true;pageGroup=groupIndex;
        }
        void row(int index,List<Column> cs) throws IOException {
            double col=WIDTH/cs.size();List<List<String>> cells=new ArrayList<>();int count=1;
            for(Column c:cs) {
                String value=printable(scalar(data.rows.get(index).get(c.key)));
                List<String> ls=wrap(value.isEmpty()?"—":value,col-16,FONT,false);
                cells.add(ls);count=Math.max(count,ls.size());
            }
            // Keep ordinary rows together; genuinely tall cells continue with their row number.
            double rowHeight=count*LINE+14;
            if(rowHeight<=300&&y+rowHeight>BOTTOM){finishPage();startPage(false);header();}
            int offset=0;
            while(offset<count) {
                ensure(LINE+18,true);
                int room=Math.max(1,(int)Math.floor((BOTTOM-y-14)/LINE));
                int take=Math.min(count-offset,room);double height=take*LINE+14;
                if(index%2==0)rect(M,y,RIGHT-M,height,"0.98 0.98 0.97");
                text(M+2,y+16,Integer.toString(index+1),7,false,MUTED);
                for(int c=0;c<cs.size();c++) {
                    List<String> cell=cells.get(c);
                    for(int l=0;l<take&&offset+l<cell.size();l++)text(TABLE_X+c*col+8,y+16+l*LINE,cell.get(offset+l),FONT,false,INK);
                }
                y+=height;line(M,y,RIGHT,y,BORDER);offset+=take;
                if(offset<count) {
                    finishPage();startPage(false);header();
                    text(M,y+11,"Registro "+(index+1)+" · continuação",8,true,MUTED);y+=22;
                }
            }
        }
        void rect(double x,double top,double w,double h,String color){page.append(color).append(" rg ").append(num(x)).append(' ').append(num(H-top-h)).append(' ').append(num(w)).append(' ').append(num(h)).append(" re f\n");}
        void line(double x1,double top1,double x2,double top2,String color){page.append(color).append(" RG 0.5 w ").append(num(x1)).append(' ').append(num(H-top1)).append(" m ").append(num(x2)).append(' ').append(num(H-top2)).append(" l S\n");}
        void text(double x,double baseline,String value,double size,boolean bold,String color){page.append("BT /").append(bold?"F2":"F1").append(' ').append(num(size)).append(" Tf ").append(color).append(" rg 1 0 0 1 ").append(num(x)).append(' ').append(num(H-baseline)).append(" Tm <").append(hex(value.getBytes(WIN))).append("> Tj ET\n");}
    }

    private static List<String> wrap(String s,double available,double size,boolean bold) {
        List<String> out=new ArrayList<>();
        for(String paragraph:s.split("\n",-1)) {
            if(paragraph.isEmpty()){out.add("");continue;}
            int start=0;
            while(start<paragraph.length()) {
                int end=start,lastSpace=-1;double used=0;
                while(end<paragraph.length()) {
                    double next=width(paragraph.substring(end,end+1),size,bold);
                    if(used+next>available&&end>start)break;
                    used+=next;
                    if(paragraph.charAt(end)==' ')lastSpace=end;
                    end++;
                }
                if(end<paragraph.length()&&lastSpace>start)end=lastSpace;
                out.add(paragraph.substring(start,end));start=end;
                if(start<paragraph.length()&&paragraph.charAt(start)==' ')start++;
            }
        }
        return out;
    }
    // Standard Helvetica widths in 1/1000 em. Accents inherit the base glyph's width.
    private static double width(String s,double size,boolean bold) {
        double result=0;
        for(int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            String normalized=Normalizer.normalize(String.valueOf(ch),Normalizer.Form.NFD);
            char c=normalized.charAt(0);int units;
            if(c==' ')units=278;
            else if(c>='0'&&c<='9')units=556;
            else if(c>='A'&&c<='Z')units=(bold?BOLD_UPPER:UPPER)[c-'A'];
            else if(c>='a'&&c<='z')units=(bold?BOLD_LOWER:LOWER)[c-'a'];
            else units=switch(c){case '.',',',':',';','!','\''->bold&&(c==':'||c==';'||c=='!')?333:278;case '(',')','[',']','{','}','/','\\','-'->389;case '|'->280;case '"'->bold?474:355;case '–'->556;case '—'->1000;case '·'->278;case '@'->1015;case '%','&'->900;case '+' ,'=', '<','>'->584;default->1000;};
            result+=units*size/1000.0;
        }
        return result;
    }
    private static final int[] UPPER={667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611};
    private static final int[] LOWER={556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500};
    private static final int[] BOLD_UPPER={722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,667,778,722,667,611,722,667,944,667,667,611};
    private static final int[] BOLD_LOWER={556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500};
    private static String num(double n){return String.format(Locale.ROOT,"%.2f",n);}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder(bytes.length*2);for(byte b:bytes)s.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));return s.toString();}

    /** Page streams are emitted immediately. Memory grows only with cross-reference/page offsets. */
    private static final class Pdf {
        final OutputStream out;long position;final List<Long> offsets=new ArrayList<>();final List<Integer> pages=new ArrayList<>();
        Pdf(OutputStream out)throws IOException {
            this.out=out;for(int i=0;i<5;i++)offsets.add(0L);
            raw("%PDF-1.4\n%âãÏÓ\n");
            object(3,"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
            object(4,"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        }
        void page(String contents)throws IOException {
            int stream=allocate(),page=allocate();byte[] bytes=contents.getBytes(StandardCharsets.ISO_8859_1);
            offsets.set(stream,position);raw(stream+" 0 obj\n<< /Length "+bytes.length+" >>\nstream\n");out.write(bytes);position+=bytes.length;raw("endstream\nendobj\n");
            object(page,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "+stream+" 0 R >>");pages.add(page);
        }
        int allocate(){offsets.add(0L);return offsets.size()-1;}
        void finish()throws IOException {
            StringBuilder kids=new StringBuilder();for(int p:pages)kids.append(p).append(" 0 R ");
            object(2,"<< /Type /Pages /Count "+pages.size()+" /Kids ["+kids+"] >>");object(1,"<< /Type /Catalog /Pages 2 0 R >>");
            long xref=position;raw("xref\n0 "+offsets.size()+"\n0000000000 65535 f \n");
            for(int i=1;i<offsets.size();i++)raw(String.format(Locale.ROOT,"%010d 00000 n \n",offsets.get(i)));
            raw("trailer\n<< /Size "+offsets.size()+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");out.flush();
        }
        void object(int id,String body)throws IOException {offsets.set(id,position);raw(id+" 0 obj\n"+body+"\nendobj\n");}
        void raw(String s)throws IOException {byte[] bytes=s.getBytes(StandardCharsets.ISO_8859_1);out.write(bytes);position+=bytes.length;}
    }
}
