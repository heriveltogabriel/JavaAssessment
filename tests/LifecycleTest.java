package assessment;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Version-to-line matching and support status against the versioned catalog (web/lifecycle.json). */
public final class LifecycleTest {
    static final LocalDate TODAY=LocalDate.parse("2026-10-19");
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Map<String,Object> support(String kind,Map<String,Object> item){return Lifecycle.support(kind,item,TODAY);}
    static void expect(String kind,Map<String,Object> item,String line,String status){
        Map<String,Object> support=support(kind,item);
        check(Objects.equals(line,support.get("linha")),item+" line: "+support.get("linha"));
        check(status.equals(support.get("status")),item+" status: "+support.get("status"));
        if(line!=null)check(String.valueOf(support.get("fonte")).startsWith("https://"),item+" source");
    }
    static Map<String,Object> java(String version){return map("version",version,"vendor","Oracle Corporation");}
    static Map<String,Object> server(String type,String version){return map("type",type,"version",version);}
    static Map<String,Object> boot(String version){return map("type","Spring Boot","frameworkVersion",version,"version","1.0.0");}
    static Map<String,Object> jdbc(String name,String version){return map("name",name,"version",version,"category","JDBC");}
    @SuppressWarnings("unchecked")
    public static void main(String[] args){
        Lifecycle.load(new File("web/lifecycle.json"));
        Map<String,Object> catalog=Lifecycle.catalog();
        check("2026-10-19".equals(catalog.get("dataReferencia")),"reference date");
        List<Object> products=(List<Object>)catalog.get("produtos");
        check(products.size()==7,"seven products");
        for(Object value:products){
            Map<String,Object> product=(Map<String,Object>)value;
            check(String.valueOf(product.get("fonte")).startsWith("https://"),"official source: "+product.get("id"));
            for(Object raw:(List<Object>)product.getOrDefault("linhas",List.of())){
                Map<String,Object> line=(Map<String,Object>)raw;
                check(!((List<Object>)line.get("versoes")).isEmpty(),"versions: "+line.get("linha"));
                check(line.get("fimPrincipal")!=null||line.get("fimEstendido")!=null||Boolean.TRUE.equals(line.get("vigente")),"dated or current: "+line.get("linha"));
                for(String key:new String[]{"fimPrincipal","fimEstendido"})if(line.get(key)!=null)LocalDate.parse(String.valueOf(line.get(key)));
            }
        }
        check(String.valueOf(support("runtimes",java("17.0.10")).get("referencia")).contains("Outros fornecedores"),"Java reference is explicit about vendors");

        expect("runtimes",java("1.8.0_381"),"Java 8",Lifecycle.EXTENDED);
        expect("runtimes",java("11.0.22"),"Java 11",Lifecycle.EXTENDED);
        expect("runtimes",java("17.0.10"),"Java 17",Lifecycle.EXTENDED);
        expect("runtimes",java("21.0.4"),"Java 21",Lifecycle.SUPPORTED);
        expect("runtimes",java("25.0.1"),"Java 25",Lifecycle.SUPPORTED);
        expect("runtimes",java("1.6.0_45"),"Java 6",Lifecycle.OUT);
        expect("runtimes",java("1.7.0_80"),"Java 7",Lifecycle.OUT);
        expect("runtimes",java("14.0.2"),"Java 12–16",Lifecycle.OUT);
        expect("runtimes",java("27"),"Java 27",Lifecycle.ENDING);
        expect("runtimes",java(""),null,Lifecycle.UNKNOWN);

        expect("servers",server("WebLogic","12.2.1.4.0"),"WebLogic 12.2.1.4",Lifecycle.ENDING);
        expect("servers",server("WebLogic","10.3.6.0"),"WebLogic 10.3.6 (11g)",Lifecycle.OUT);
        expect("servers",server("WebLogic","14.1.2.0.0"),"WebLogic 14.1.2",Lifecycle.SUPPORTED);
        expect("servers",server("WebLogic","14.1.1.0.0"),"WebLogic 14.1.1",Lifecycle.SUPPORTED);
        expect("servers",server("WebLogic","12.2.1.3.0"),null,Lifecycle.UNKNOWN);
        expect("servers",server("JBoss","7.4.0.GA"),"JBoss EAP 7.4",Lifecycle.EXTENDED);
        expect("servers",server("JBoss","EAP 8.0.0.GA"),"JBoss EAP 8",Lifecycle.SUPPORTED);
        expect("servers",server("JBoss","7.1.1.Final"),null,Lifecycle.UNKNOWN);
        expect("servers",server("WildFly","26.1.3.Final"),"WildFly 26",Lifecycle.OUT);
        expect("servers",server("WildFly","41.0.1.Final"),"WildFly 41",Lifecycle.SUPPORTED);
        expect("servers",server("Tomcat","9.0.85"),"Tomcat 9.0",Lifecycle.ENDING);
        expect("servers",server("Tomcat","10.1.20"),"Tomcat 10.1",Lifecycle.SUPPORTED);
        expect("servers",server("Tomcat","8.5.100"),"Tomcat 8.5",Lifecycle.OUT);
        expect("servers",server("Jetty","9.4.53"),null,Lifecycle.UNKNOWN);
        expect("servers",map("name","WebLogic","version","10.3.6.0"),"WebLogic 10.3.6 (11g)",Lifecycle.OUT);

        expect("applications",boot("2.7.18"),"Spring Boot 2.7",Lifecycle.EXTENDED);
        expect("applications",boot("3.5.4"),"Spring Boot 3.5",Lifecycle.EXTENDED);
        expect("applications",boot("4.0.1"),"Spring Boot 4.0",Lifecycle.ENDING);
        expect("applications",boot("4.1.1"),"Spring Boot 4.1",Lifecycle.ENDING);
        expect("applications",boot("1.5.22.RELEASE"),"Spring Boot 1.5",Lifecycle.OUT);
        expect("applications",map("type","Java","applicationType","Spring Boot","frameworkVersion","3.0.13"),"Spring Boot 3.0",Lifecycle.OUT);
        expect("applications",map("type","WAR","frameworkVersion","2.7.18"),null,Lifecycle.UNKNOWN);

        expect("libraries",jdbc("ojdbc8","19.3.0.0"),"Oracle JDBC 19c",Lifecycle.SUPPORTED);
        expect("libraries",jdbc("ojdbc8","12.2.0.1"),"Oracle JDBC 18c e anteriores",Lifecycle.OUT);
        expect("libraries",jdbc("ojdbc11","21.9.0.0"),"Oracle JDBC 21c",Lifecycle.ENDING);
        expect("libraries",jdbc("ojdbc11-23.26.0.0.0.jar","23.26.0.0.0"),"Oracle JDBC 23ai/26ai",Lifecycle.SUPPORTED);
        expect("libraries",jdbc("postgresql","42.7.4"),null,Lifecycle.UNKNOWN);
        check(support("hosts",map())==null,"hosts have no lifecycle");

        Map<String,Object> line=map("fimPrincipal","2026-12-31","fimEstendido","2027-12-31");
        check(Lifecycle.status(line,LocalDate.parse("2025-12-30")).equals(Lifecycle.SUPPORTED),"more than 12 months");
        check(Lifecycle.status(line,LocalDate.parse("2025-12-31")).equals(Lifecycle.ENDING),"exactly 12 months");
        check(Lifecycle.status(line,LocalDate.parse("2026-12-31")).equals(Lifecycle.ENDING),"last premier day");
        check(Lifecycle.status(line,LocalDate.parse("2027-01-01")).equals(Lifecycle.EXTENDED),"extended only");
        check(Lifecycle.status(line,LocalDate.parse("2028-01-01")).equals(Lifecycle.OUT),"after extended");
        check(Lifecycle.status(map("fimPrincipal","2020-01-31"),TODAY).equals(Lifecycle.OUT),"premier only");
        check(Lifecycle.status(map(),TODAY).equals(Lifecycle.UNKNOWN),"undated is unknown");
        check(Lifecycle.status(map("vigente",true),TODAY).equals(Lifecycle.SUPPORTED),"current without announced end");
        System.out.println("Lifecycle catalog and classification checks passed");
    }
}
