package assessment;

import java.util.*;
import static assessment.AssessmentServer.map;

/** Datasources grouped by configured database destination; every aggregate runs in SQLite. */
final class DatabaseGroups {
    /** Inventory subset value that selects the datasources of one destination ("db:" + key). */
    static final String PREFIX="db:";
    /** Key of the group whose datasources have no resolved host (placeholder or alias not resolved). */
    static final String UNIDENTIFIED="__unidentified__";
    static final int LIST_LIMIT=12;

    private DatabaseGroups() {}

    /** Normalized destination of datasource alias {@code a}: sorted lower-case host:port list plus "/" + lower-case service; '' without a host. */
    static String key(String a){
        String host="lower(trim("+Reports.associationField("ep","host")+"))";
        String endpoints="(SELECT group_concat(v,',') FROM (SELECT DISTINCT "+Reports.address(host,Reports.associationField("ep","port"))+" AS v FROM json_each("+Reports.endpointArray(a)+") ep WHERE "+host+"<>'' ORDER BY v))";
        String scalarHost="lower(trim("+Reports.field(a,"databaseHost")+"))";
        String scalar="CASE WHEN "+scalarHost+"<>'' THEN "+Reports.address(scalarHost,Reports.field(a,"databasePort"))+" END";
        String name="lower(trim("+Reports.field(a,"databaseName")+"))";
        return "coalesce(coalesce("+endpoints+","+scalar+") || CASE WHEN "+name+"<>'' THEN '/' || "+name+" ELSE '' END,'')";
    }

    /** SQL condition (and its argument) restricting datasources to the destination named by a "db:" subset. */
    static String condition(String subset,List<Object> args){
        String wanted=subset.substring(PREFIX.length());
        args.add(wanted.equals(UNIDENTIFIED)?"":wanted);
        return key("i")+"=?";
    }

    static Map<String,Object> page(InventoryQueries queries,InventoryQueries.Scope f){
        List<Object> args=new ArrayList<>();
        String where=queries.conditions(f,"datasources",args);
        String scoped="WITH ds AS (SELECT i.host_id,h.hostname,i.payload,"+key("i")+" AS k,"+Reports.destination("i")+" AS label FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='datasources' AND "+where+") ";
        long total=queries.db.scalar(scoped+"SELECT count(DISTINCT k) FROM ds",args.toArray());
        int pages=(int)Math.max(1,(total+f.size-1)/f.size),page=Math.min(f.page,pages);
        List<Object> pageArgs=new ArrayList<>(args);
        pageArgs.add(f.size);
        pageArgs.add((page-1)*f.size);
        List<Map<String,Object>> groups=queries.db.rows(scoped+"SELECT k AS key,min(label) AS label,count(*) AS datasources,count(DISTINCT host_id) AS machineCount FROM ds GROUP BY k ORDER BY k='',datasources DESC,k LIMIT ? OFFSET ?",pageArgs.toArray());
        Map<String,Map<String,Object>> byKey=new LinkedHashMap<>();
        for(Map<String,Object> group:groups){
            String k=String.valueOf(group.get("key"));
            group.put("key",k.isEmpty()?UNIDENTIFIED:k);
            group.put("identified",!k.isEmpty());
            for(String list:List.of("machines","servers","applications","databaseTypes"))group.put(list,new ArrayList<>());
            for(String count:List.of("serverCount","applicationCount"))group.put(count,0L);
            byKey.put(k,group);
        }
        if(!byKey.isEmpty())details(queries.db,scoped,args,byKey);
        return map("groups",groups,"pagination",map("collection","databaseGroups","page",page,"pageSize",f.size,"total",total,"pages",pages));
    }

    /** One bounded query fills the machine, server, application and database-type lists of the page's groups. */
    @SuppressWarnings("unchecked")
    private static void details(InventoryStore db,String scoped,List<Object> args,Map<String,Map<String,Object>> byKey){
        String server="CASE WHEN json_type(i.payload,'$.serverName')='text' THEN json_extract(i.payload,'$.serverName') ELSE '' END";
        String application="CASE WHEN json_type(i.payload,'$.applicationName')='text' THEN json_extract(i.payload,'$.applicationName') ELSE '' END";
        String values="SELECT i.k,'machines' AS t,i.hostname AS v,i.host_id AS id FROM sel i"
            +" UNION SELECT i.k,'servers',"+server+",'' FROM sel i"
            +" UNION SELECT i.k,'servers',"+Reports.associationField("a","serverName")+",'' FROM sel i,json_each("+Reports.associationArray("i")+") a"
            +" UNION SELECT i.k,'applications',"+application+",'' FROM sel i"
            +" UNION SELECT i.k,'applications',"+Reports.associationField("a","applicationName")+",'' FROM sel i,json_each("+Reports.associationArray("i")+") a"
            +" UNION SELECT i.k,'applications',"+Reports.associationField("c","context")+",'' FROM sel i,json_each(CASE WHEN json_type(i.payload,'$.linkedContexts')='array' THEN json_extract(i.payload,'$.linkedContexts') ELSE '[]' END) c"
            +" UNION SELECT i.k,'databaseTypes',"+Reports.field("i","databaseType")+",'' FROM sel i";
        List<Object> detailArgs=new ArrayList<>(args);
        detailArgs.addAll(byKey.keySet());
        detailArgs.add(LIST_LIMIT);
        String keys=String.join(",",Collections.nCopies(byKey.size(),"?"));
        String sql=scoped+", sel AS (SELECT * FROM ds WHERE k IN ("+keys+")) SELECT k,t,v,id,n FROM (SELECT k,t,v,id,row_number() OVER (PARTITION BY k,t ORDER BY lower(v),id) AS r,count(*) OVER (PARTITION BY k,t) AS n FROM ("+values+") WHERE v<>'') WHERE r<=? ORDER BY k,t,r";
        for(Map<String,Object> row:db.rows(sql,detailArgs.toArray())){
            Map<String,Object> group=byKey.get(String.valueOf(row.get("k")));
            String type=String.valueOf(row.get("t"));
            ((List<Object>)group.get(type)).add(type.equals("machines")?map("id",row.get("id"),"name",row.get("v")):row.get("v"));
            if(type.equals("servers"))group.put("serverCount",row.get("n"));
            if(type.equals("applications"))group.put("applicationCount",row.get("n"));
        }
    }
}
