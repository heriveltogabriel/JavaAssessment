package assessment;

import java.time.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Bounded SQL views: summaries are aggregate queries, never an in-memory fleet. */
final class InventoryQueries {
    static final Set<String> KINDS=Set.of("runtimes","processes","servers","libraries","applications","datasources");
    final InventoryStore db;
    InventoryQueries(InventoryStore db){this.db=db;}
    static String s(Object o){return o==null?"":String.valueOf(o);}
    static long n(Object o){return o instanceof Number?((Number)o).longValue():0;}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
    static final class Scope {
        String environment="",host="",view="overview",q="",version="",mode="family",subset="",from="",to="";
        int page=1,size=20;
        final List<Object> args=new ArrayList<>();
        String where(){args.clear();String w="1=1";if(!environment.isEmpty()){w+=" AND h.environment_id=?";args.add(environment.equals("__unassigned__")?"":environment);}if(!host.isEmpty()){w+=" AND h.id=?";args.add(host);}return w;}
    }
    static String escapeLike(String s){return s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    static String time(String raw,boolean end){
        if(raw.isEmpty())return "";
        try{if(raw.length()==10)return (end?LocalDate.parse(raw).plusDays(1):LocalDate.parse(raw)).atStartOfDay(ZoneOffset.UTC).toInstant().toString();return Instant.parse(raw).toString();}
        catch(Exception e){throw new IllegalArgumentException("Invalid date filter");}
    }
    private String conditions(Scope f,String kind,List<Object> a){
        String w=f.where();a.addAll(f.args);
        if(!f.q.isEmpty()){
            String col=kind.equals("hosts")?"(h.hostname || ' ' || coalesce(json_extract(h.payload,'$.distribution'),'') || ' ' || coalesce(json_extract(h.payload,'$.ipAddresses'),''))":kind.equals("jobs")?"(j.status || ' ' || CASE j.status WHEN 'SUCCEEDED' THEN 'Completed' WHEN 'PARTIAL' THEN 'Completed with notes' ELSE '' END || ' ' || j.id || ' ' || h.hostname || ' ' || coalesce(json_extract(j.payload,'$.summary'),''))":"(i.search_text || ' ' || h.hostname || ' ' || coalesce(json_extract(h.payload,'$.fqdn'),''))";
            w+=" AND "+col+" LIKE ? ESCAPE '\\'";a.add("%"+escapeLike(f.q)+"%");
        }
        String stamp=kind.equals("hosts")?"h.last_seen":kind.equals("jobs")?"j.created_at":"i.observed_at";
        if(!f.from.isEmpty()){w+=" AND (coalesce("+stamp+",'')='' OR julianday("+stamp+") >= julianday(?))";a.add(f.from);}
        if(!f.to.isEmpty()){w+=" AND (coalesce("+stamp+",'')='' OR julianday("+stamp+") < julianday(?))";a.add(f.to);}
        if(kind.equals("runtimes")&&!f.version.isEmpty()){w+=" AND i."+(f.mode.equals("exact")?"version":"major")+"=?";a.add(f.version.equals("Version unknown")?"":f.mode.equals("exact")?f.version:f.version.replaceFirst("^Java ",""));}
        if(kind.equals("servers")&&f.subset.equals("runtime"))w+=" AND coalesce(json_extract(i.payload,'$.state'),'') <> ''";
        if(kind.equals("libraries")&&f.subset.equals("jdbc"))w+=" AND lower(coalesce(json_extract(i.payload,'$.category'),''))='jdbc'";
        return w;
    }
    Map<String,Object> page(Scope f,String kind){
        List<Object> a=new ArrayList<>();String w=conditions(f,kind,a),from,select,order;
        if(kind.equals("hosts")){from="hosts h";select="h.payload";order="lower(h.hostname),h.id";}
        else if(kind.equals("jobs")){from="jobs j JOIN hosts h ON h.id=j.host_id";select="j.payload";order="j.created_at DESC,j.id DESC";}
        else {if(!KINDS.contains(kind))throw new IllegalArgumentException("Unknown inventory kind");from="inventory i JOIN hosts h ON h.id=i.host_id";select="i.payload";order="h.hostname,i.host_id,i.id";w+=" AND i.kind=?";a.add(kind);}
        long total=db.scalar("SELECT count(*) FROM "+from+" WHERE "+w,a.toArray());int pages=(int)Math.max(1,(total+f.size-1)/f.size),p=Math.min(f.page,pages);a.add(f.size);a.add((p-1)*f.size);
        List<Map<String,Object>> items=db.documents("SELECT "+select+" FROM "+from+" WHERE "+w+" ORDER BY "+order+" LIMIT ? OFFSET ?",a.toArray());
        return map("items",items,"pagination",map("collection",kind,"page",p,"pageSize",f.size,"total",total,"pages",pages));
    }
    Map<String,Object> libraryPage(Scope f){
        List<Object> a=new ArrayList<>();String w=conditions(f,"libraries",a);String kinds=f.subset.equals("jdbc")?"i.kind='libraries'":"i.kind IN ('libraries','datasources')";
        long total=db.scalar("SELECT count(*) FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE "+kinds+" AND "+w,a.toArray());int pages=(int)Math.max(1,(total+f.size-1)/f.size),p=Math.min(f.page,pages);a.add(f.size);a.add((p-1)*f.size);
        List<Object> libraries=new ArrayList<>(),datasources=new ArrayList<>();Set<String> hosts=new LinkedHashSet<>();
        for(Map<String,Object> row:db.rows("SELECT i.kind,i.host_id,i.payload FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE "+kinds+" AND "+w+" ORDER BY i.kind DESC,h.hostname,i.host_id,i.id LIMIT ? OFFSET ?",a.toArray())){(s(row.get("kind")).equals("datasources")?datasources:libraries).add(Json.parse(s(row.get("payload"))));hosts.add(s(row.get("host_id")));}
        return map("libraries",libraries,"datasources",datasources,"hostIds",hosts,"pagination",map("collection","libraries","page",p,"pageSize",f.size,"total",total,"pages",pages));
    }
    Map<String,Object> summary(Scope f){
        String w=f.where();Object[] a=f.args.toArray();Map<String,Object> result=map("hosts",db.scalar("SELECT count(*) FROM hosts h WHERE "+w,a));
        result.put("activeAgents",db.scalar("SELECT count(*) FROM hosts h JOIN identities idn ON idn.id=h.id WHERE idn.status='active' AND coalesce(json_extract(h.payload,'$.agentLifecycle'),'')<>'uninstalled' AND "+w,a));
        for(String kind:KINDS)result.put(kind,0L);
        for(Map<String,Object> row:db.rows("SELECT i.kind,count(*) count FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE "+w+" GROUP BY i.kind",a))result.put(s(row.get("kind")),row.get("count"));
        result.put("jobs",db.scalar("SELECT count(*) FROM jobs j JOIN hosts h ON h.id=j.host_id WHERE "+w,a));
        result.put("majorVersions",db.scalar("SELECT count(DISTINCT major) FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='runtimes' AND "+w,a));
        result.put("distinctVersions",db.scalar("SELECT count(DISTINCT version) FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='runtimes' AND "+w,a));
        result.put("reportedServers",db.scalar("SELECT count(*) FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='servers' AND coalesce(json_extract(i.payload,'$.state'),'')<>'' AND "+w,a));
        result.put("jdbcLibraries",db.scalar("SELECT count(*) FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='libraries' AND lower(json_extract(i.payload,'$.category'))='jdbc' AND "+w,a));
        return result;
    }
    static final String DS_CLASS="CASE WHEN lower(coalesce(json_extract(i.payload,'$.evidenceLevel'),''))='configuration' OR upper(coalesce(json_extract(i.payload,'$.state'),''))='CONFIGURED' THEN 'configured' WHEN lower(coalesce(json_extract(i.payload,'$.evidenceLevel'),''))='runtime' OR upper(coalesce(json_extract(i.payload,'$.state'),'')) IN ('RUNNING','SUSPENDED','SHUTDOWN','OVERLOADED','UNHEALTHY') OR coalesce(json_extract(i.payload,'$.evidence'),'') LIKE '%Existing WebLogic JMX snapshot%' OR lower(coalesce(json_extract(i.payload,'$.evidence'),'')) LIKE 'confirmed%' THEN 'runtime' ELSE 'unknown' END";
    Map<String,Object> datasourceSummary(Scope f){String w=f.where();Map<String,Object> counts=map("configured",0L,"runtime",0L,"unknown",0L);for(Map<String,Object> row:db.rows("SELECT "+DS_CLASS+" category,count(*) count FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='datasources' AND "+w+" GROUP BY category",f.args.toArray()))counts.put(s(row.get("category")),row.get("count"));return counts;}
    List<Map<String,Object>> javaGroups(Scope f,boolean exact){return javaGroups(f,exact,2000,0,false);}
    List<Map<String,Object>> javaGroups(Scope f,boolean exact,int limit,int offset,boolean selected){
        String w=f.where(),column=exact?"i.version":"i.major";List<Object> args=new ArrayList<>(f.args);
        if(selected&&!f.version.isEmpty()){w+=" AND "+column+"=?";args.add(f.version.equals("Version unknown")?"":exact?f.version:f.version.replaceFirst("^Java ",""));}
        args.add(limit);args.add(offset);
        return db.rows("SELECT "+(exact?"coalesce(nullif(i.version,''),'Version unknown')":"CASE WHEN i.major='' THEN 'Version unknown' ELSE 'Java ' || i.major END")+" name,count(*) count,count(DISTINCT i.host_id) hostCount FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE i.kind='runtimes' AND "+w+" GROUP BY "+column+" ORDER BY count DESC,name LIMIT ? OFFSET ?",args.toArray());
    }
    List<Map<String,Object>> osGroups(Scope f){String w=f.where();return db.rows("SELECT coalesce(nullif(json_extract(h.payload,'$.distribution'),''),nullif(json_extract(h.payload,'$.osName'),''),'OS not reported') name,count(*) count FROM hosts h WHERE "+w+" GROUP BY name ORDER BY count DESC,name LIMIT 1000",f.args.toArray());}
    List<Map<String,Object>> productGroups(Scope f,String kind,long total){
        boolean server=kind.equals("servers");if(!server&&!kind.equals("datasources"))throw new IllegalArgumentException("Unsupported distribution");
        String name=server?"coalesce(nullif(i.type,''),'Java server')":"coalesce(nullif(json_extract(i.payload,'$.name'),''),'Datasource sem nome')";
        String version=server?"coalesce(i.version,'')":"coalesce(json_extract(i.payload,'$.driverVersion'),'')";
        String w=f.where();List<Object> args=new ArrayList<>(f.args);args.add(kind);
        List<Map<String,Object>> groups=db.rows("SELECT "+name+" name,"+version+" version,count(*) count,count(DISTINCT i.host_id) hostCount FROM inventory i JOIN hosts h ON h.id=i.host_id WHERE "+w+" AND i.kind=?"+(server?" AND coalesce(json_extract(i.payload,'$.state'),'')<>''":"")+" GROUP BY 1,2 ORDER BY count DESC,name,version LIMIT 3",args.toArray());
        long shown=0;for(var group:groups)shown+=n(group.get("count"));
        if(total>shown)groups.add(map("name","Outros","version","","count",total-shown,"other",true));return groups;
    }
    Map<String,Object> coverage(Scope f){
        String w=f.where();long complete=0,partial=0,pending=0;String latest="";
        for(Map<String,Object> row:db.rows("SELECT coverage_status status,count(*) count,max(captured_at) latest FROM hosts h WHERE "+w+" GROUP BY coverage_status",f.args.toArray())){long count=n(row.get("count"));String status=s(row.get("status"));if(status.equals("complete"))complete+=count;else if(status.equals("partial"))partial+=count;else pending+=count;if(s(row.get("latest")).compareTo(latest)>0)latest=s(row.get("latest"));}
        if(!f.host.isEmpty()){Map<String,Object> h=db.get("hosts",f.host);if(h!=null&&h.get("coverage") instanceof Map)return obj(h.get("coverage"));}
        List<Object> warnings=new ArrayList<>();if(partial>0)warnings.add(partial+" machine(s) have coverage notes");if(pending>0)warnings.add(pending+" machine(s) await an assessment");
        return map("status",complete+partial==0?"pending":partial+pending>0?"partial":"complete","warnings",warnings,"lastAssessmentAt",latest,"completeHosts",complete,"partialHosts",partial,"pendingHosts",pending,"stats",map());
    }
    Map<String,Object> presence(Scope f){String w=f.where();String recent=java.time.Instant.now().minusSeconds(150).toString(),silent=java.time.Instant.now().minusSeconds(300).toString();List<Object> a=new ArrayList<>(List.of(recent,silent));a.addAll(f.args);Map<String,Object> result=map("recent",0,"delayed",0,"silent",0,"awaiting",0,"revoked",0,"uninstalled",0);for(Map<String,Object> r:db.rows("SELECT CASE WHEN json_extract(h.payload,'$.agentLifecycle')='uninstalled' THEN 'uninstalled' WHEN idn.status='revoked' THEN 'revoked' WHEN h.last_seen='' THEN 'awaiting' WHEN julianday(h.last_seen)>=julianday(?) THEN 'recent' WHEN julianday(h.last_seen)>=julianday(?) THEN 'delayed' ELSE 'silent' END status,count(*) count FROM hosts h LEFT JOIN identities idn ON idn.id=h.id WHERE "+w+" GROUP BY 1",a.toArray()))result.put(s(r.get("status")),r.get("count"));return result;}
    void decorateHosts(List<Map<String,Object>> hosts){
        for(Map<String,Object> host:hosts){Scope scope=new Scope();scope.host=s(host.get("id"));Map<String,Object> counts=summary(scope);host.put("counts",counts);host.put("javaVersions",javaGroups(scope,false));host.put("exactJavaVersions",javaGroups(scope,true));host.put("datasourceSummary",datasourceSummary(scope));
            host.put("workloads",db.rows("SELECT i.kind kind,coalesce(nullif(json_extract(i.payload,'$.applicationType'),''),nullif(i.type,''),'Java application') type,max(CASE WHEN json_extract(i.payload,'$.state')='PROCESS_RUNNING' THEN 2 WHEN coalesce(json_extract(i.payload,'$.state'),'') NOT IN ('','FOUND','FILE','FILE_FOUND','UNKNOWN') THEN 3 ELSE 1 END) priority FROM inventory i WHERE i.host_id=? AND i.kind IN ('servers','applications') GROUP BY kind,type LIMIT 20",scope.host));
        }
    }
    Map<String,Object> view(Scope f){
        Map<String,Object> result=map("scoped",true,"version",DashboardVersion.VERSION,"fleet",db.metadata("fleet"),"settings",db.metadata("settings"),"summary",summary(f),"coverage",coverage(f),"javaGroups",javaGroups(f,false,6,0,false),"exactJavaGroups",javaGroups(f,true,6,0,false),"osGroups",osGroups(f),"datasourceSummary",datasourceSummary(f),"presence",presence(f),"serverTime",AssessmentServer.now(),"pilot",false,"scope",map("environmentId",f.environment,"hostId",f.host));
        if(f.view.equals("versions")){
            boolean exact=f.mode.equals("exact");String key=exact?"exactJavaGroups":"javaGroups";
            if(f.version.isEmpty()){long total=n(obj(result.get("summary")).get(exact?"distinctVersions":"majorVersions"));int pages=(int)Math.max(1,(total+f.size-1)/f.size),page=Math.min(f.page,pages);result.put(key,javaGroups(f,exact,f.size,(page-1)*f.size,false));result.put("pagination",map("collection","versionGroups","page",page,"pageSize",f.size,"total",total,"pages",pages));}
            else result.put(key,javaGroups(f,exact,1,0,true));
        }else{
            List<Map<String,Object>> groups=(List<Map<String,Object>>)result.get("javaGroups");long shown=0;for(Map<String,Object> group:groups)shown+=n(group.get("count"));long others=n(obj(result.get("summary")).get("runtimes"))-shown;if(others>0)groups.add(map("name","Other versions","count",others,"hostCount",0,"other",true));
        }
        for(String kind:KINDS)result.put(kind,new ArrayList<>());result.put("jobs",new ArrayList<>());List<Map<String,Object>> hosts=new ArrayList<>();String kind=f.view.equals("versions")?"runtimes":f.view;
        if(kind.equals("libraries")&&f.subset.equals("datasources"))kind="datasources";
        if(f.view.equals("libraries")&&!f.subset.equals("datasources")){
            Map<String,Object> page=libraryPage(f);result.put("libraries",page.get("libraries"));result.put("datasources",page.get("datasources"));result.put("pagination",page.get("pagination"));for(String id:(Set<String>)page.get("hostIds")){Map<String,Object> h=db.get("hosts",id);if(h!=null)hosts.add(h);}
        }else if(KINDS.contains(kind)||Set.of("hosts","jobs").contains(kind)){
            if(!f.view.equals("versions")||!f.version.isEmpty()){
                Map<String,Object> page=page(f,kind);
                if(kind.equals("servers"))for(Map<String,Object> server:(List<Map<String,Object>>)page.get("items"))server.put("applicationCount",db.scalar("SELECT count(*) FROM inventory WHERE kind='applications' AND host_id=? AND json_extract(payload,'$.serverName')=?",server.get("hostId"),server.get("name")));
                result.put(kind,page.get("items"));result.put("pagination",page.get("pagination"));
                if(kind.equals("hosts"))hosts=(List<Map<String,Object>>)page.get("items");
                else {Set<String> ids=new LinkedHashSet<>();for(Map<String,Object> row:(List<Map<String,Object>>)page.get("items"))ids.add(s(row.get("hostId")));for(String id:ids){Map<String,Object> h=db.get("hosts",id);if(h!=null)hosts.add(h);}}
            }
        }else if(f.view.equals("overview")||f.view.equals("machine")){
            Scope preview=new Scope();preview.environment=f.environment;preview.host=f.host;preview.size=6;
            result.put("jobs",page(preview,"jobs").get("items"));
            if(f.host.isEmpty()){
                preview.size=10;preview.page=f.page;preview.q=f.q;Map<String,Object> hostPage=page(preview,"hosts");hosts=(List<Map<String,Object>>)hostPage.get("items");
                result.put("pagination",hostPage.get("pagination"));result.put("machineQuery",f.q);
                Map<String,Object> totals=obj(result.get("summary"));result.put("serverGroups",productGroups(f,"servers",n(totals.get("reportedServers"))));result.put("jdbcGroups",productGroups(f,"datasources",n(totals.get("datasources"))));
            }else hosts=(List<Map<String,Object>>)page(preview,"hosts").get("items");
            if(!f.host.isEmpty()){preview.size=8;for(String k:KINDS){preview.subset=k.equals("libraries")?"jdbc":"";result.put(k,page(preview,k).get("items"));}}
        }
        if(!f.host.isEmpty()&&hosts.stream().noneMatch(h->f.host.equals(h.get("id")))){Map<String,Object> selected=db.get("hosts",f.host);if(selected!=null)hosts.add(selected);}
        decorateHosts(hosts);result.put("hosts",hosts);
        List<Map<String,Object>> environments=db.documents("SELECT payload FROM environments ORDER BY name,id LIMIT 200");
        Map<String,Long> environmentHosts=new HashMap<>();for(Map<String,Object> count:db.rows("SELECT environment_id,count(*) count FROM hosts GROUP BY environment_id"))environmentHosts.put(s(count.get("environment_id")),n(count.get("count")));
        for(Map<String,Object> env:environments){env.put("counts",map("hosts",environmentHosts.getOrDefault(s(env.get("id")),0L)));if(f.view.equals("environments")){Scope e=new Scope();e.environment=s(env.get("id"));env.put("counts",summary(e));env.put("coverage",coverage(e));env.put("lastAssessmentAt",obj(env.get("coverage")).get("lastAssessmentAt"));}}
        result.put("environments",environments);return result;
    }
}
