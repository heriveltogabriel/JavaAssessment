package assessment;
import java.util.*;

/** Correlates configuration on this host only; a match never proves a live connection. */
final class JndiLinks {
    static String normalized(Object value) {
        if (!(value instanceof String)) return null;
        String name = ((String)value).trim();
        if (!name.matches("[A-Za-z0-9_:/.$-]{1,200}")) return null;
        for (String prefix : new String[]{"java:comp/env/", "java:jboss/", "java:global/", "java:/"}) if (name.startsWith(prefix)) { name = name.substring(prefix.length()); break; }
        return name.length() == 0 ? null : name;
    }
    static boolean server(Map row) {
        if (row.get("serverId") != null) return true;
        if (row.get("associations") instanceof List) for (Object v : (List)row.get("associations")) if (v instanceof Map && ((Map)v).get("serverId") != null) return true;
        return false;
    }
    static boolean matches(Map row, String name) {
        if (name.equals(normalized(row.get("jndiName"))) || name.equals(normalized(row.get("name")))) return true;
        if (row.get("jndiNames") instanceof List) for (Object v : (List)row.get("jndiNames")) if (name.equals(normalized(v))) return true;
        if (row.get("linkedContexts") instanceof List) for (Object v : (List)row.get("linkedContexts")) if (v instanceof Map && name.equals(normalized(((Map)v).get("linkName")))) return true;
        return false;
    }
    static void link(AssessmentScan scan) {
        List<Map> servers = new ArrayList<Map>();
        for (Object v : scan.datasources) { Map row=(Map)v; if (server(row) && row.get("jndiResolution") == null && scan.hostId.equals(row.get("hostId"))) servers.add(row); }
        for (Object v : scan.datasources) {
            Map app=(Map)v; if (servers.contains(app)) continue;
            String name=normalized(app.get("jndiName")); if(name==null)continue;
            List<Map> candidates=new ArrayList<Map>();
            for(Map row:servers)if(matches(row,name))candidates.add(row);
            if(candidates.size()!=1){
                app.put("jndiResolution",candidates.isEmpty()?"NOT_FOUND":"AMBIGUOUS");
                app.put("jndiEvidence","JNDI "+name+(candidates.isEmpty()?" declarado; datasource não encontrado nesta máquina":" declarado; múltiplos datasources de servidor nesta máquina, vínculo não confirmado"));
                continue;
            }
            Map target=candidates.get(0);
            if(app.get("databaseHost")!=null && target.get("databaseHost")!=null && !app.get("databaseHost").equals(target.get("databaseHost"))) {
                app.put("jndiResolution","CONFLICT"); app.put("jndiEvidence","Destino configurado diverge do datasource JNDI; vínculo não confirmado");continue;
            }
            if(app.get("applicationId")!=null){
                List associations=target.get("associations") instanceof List?(List)target.get("associations"):new ArrayList();
                boolean found=false;for(Object a:associations)if(app.get("applicationId").equals(((Map)a).get("applicationId")))found=true;
                if(!found&&associations.size()<16){Map link=AssessmentScan.map("applicationId",app.get("applicationId"),"applicationName",app.get("applicationName"),"evidence","Vínculo JNDI declarado; conexão não verificada");if(app.get("pid")!=null)link.put("applicationPid",app.get("pid"));if(target.get("pid")!=null)link.put("pid",target.get("pid"));if(target.get("serverId")!=null)link.put("serverId",target.get("serverId"));if(target.get("serverName")!=null)link.put("serverName",target.get("serverName"));associations.add(link);target.put("associations",associations);}
            }
            for(String field:new String[]{"databaseType","databaseHost","databasePort","databaseName","databaseEndpoints","driverName","driverClass","driverVersion","driverArtifact","driverResolution","driverVersionEvidence","driverVersionSource","driverFamily","driverConfidence","driverResolutionReason","driverCandidates","driverReplacedOnDisk"})if(target.get(field)!=null)app.put(field,target.get(field));
            if(target.get("associations") instanceof List){
                List associations=app.get("associations") instanceof List?new ArrayList((List)app.get("associations")):new ArrayList();
                for(Object raw:(List)target.get("associations")){
                    if(!(raw instanceof Map)||!(((Map)raw).get("driverResult") instanceof Map))continue;
                    Map source=(Map)raw; boolean found=false;
                    for(Object existing:associations)if(existing instanceof Map&&source.get("pid")!=null&&source.get("pid").equals(((Map)existing).get("pid"))&&((Map)existing).get("driverResult")!=null)found=true;
                    if(!found&&associations.size()<16){Map decision=new LinkedHashMap();for(String field:new String[]{"pid","serverId","serverName","driverResult"})if(source.get(field)!=null)decision.put(field,source.get(field));associations.add(decision);}
                }
                app.put("associations",associations);
            }
            if(target.get("databaseType")!=null)app.put("databaseTypeEvidence","Tipo declarado pelo datasource do servidor via JNDI");
            app.put("jndiResolution","MATCHED"); app.put("linkedDatasourceId",target.get("id"));
            String server=ProcessLaunch.label(AssessmentScan.clean(target.get("serverName")));
            if(server==null)server="identificado no inventário";
            app.put("jndiEvidence","Via JNDI "+name+" do servidor "+server+"; conexão não verificada");
            app.put("databaseEndpointEvidence","jndi-link");
            List methods=app.get("resolvedFrom") instanceof List?new ArrayList((List)app.get("resolvedFrom")):new ArrayList();
            if(!methods.contains("jndi-link"))methods.add("jndi-link");app.put("resolvedFrom",methods);
        }
    }
}
