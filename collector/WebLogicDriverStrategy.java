package assessment;
import java.io.File;
import java.util.*;
/** WebLogic-specific origins; selection and metadata are shared by all server strategies. */
final class WebLogicDriverStrategy implements JdbcDriverResolver.Strategy {
    final JdbcDriverResolver owner;
    WebLogicDriverStrategy(JdbcDriverResolver owner){this.owner=owner;}
    public boolean requiresClasspath(){return true;}
    public String prepare(AssessmentScan.ProcessContext context,Map row){return AssessmentScan.clean(row.get("driverName"));}
    public void resolve(JdbcDriverResolver.Search search,Map row,List<String> cp){
        WebLogicApplicationDrivers.Result app=WebLogicApplicationDrivers.inspect(owner.scan,row,search.driver);
        if(app.preferred){if(app.reason!=null)search.fail(app.reason);search.application(app);}
        for(String path:cp)search.walk(new File(path),"classpath",0,null,false);
        Object domain=row.get("domainPath");if(domain instanceof String)search.group(search.directory(new File((String)domain,"lib"),"lib do domínio"),"lib do domínio");
        if(!app.preferred){if(app.reason!=null)search.fail(app.reason);search.application(app);}
        for(File file:owner.standard(search.context,cp))search.walk(file,"disco, fora do classpath",0,null,true);
    }
}
