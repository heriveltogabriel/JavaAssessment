package assessment;

import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Public product artifacts re-signed by a disposable test pin; no host operations. */
public final class AgentUpdateRoutesTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void copy(Path from,Path to)throws Exception{try(var paths=Files.walk(from)){for(var p:paths.toList()){var t=to.resolve(from.relativize(p));if(Files.isDirectory(p))Files.createDirectories(t);else Files.copy(p,t);}}}
    public static void main(String[] args)throws Exception{
        Path fixture=Path.of(args.length>0?args[0]:"tmp/full-update/http-fixture"),root=Files.createTempDirectory("route-catalog-");
        try{
            copy(fixture.resolve("dist"),root.resolve("dist"));var catalog=new AgentUpdateRoutes(root.toFile());var c=catalog.load(DashboardVersion.VERSION);
            check(c.version().equals(DashboardVersion.VERSION),"target matches release");check(!c.bridges().isEmpty(),"legacy bridge included");check(c.bundle().metadata().get("minUpdater").equals(3L),"full updater protocol is explicit");check(c.bundle().sha256().matches("[a-f0-9]{64}"),"bundle hash pinned");
            var bridge=c.bridges().get(0);check(bridge.minUpdater()==1&&bridge.fromVersion().equals("1.5.12"),"minimum original protocol preserved");check(bridge.legacy().version().equals(bridge.version()),"legacy wire artifact retained");
            check(catalog.candidates(c,map("collectorVersion","1.5.12","updateCapability","update-v1")).size()==1,"known legacy profile selected");check(catalog.candidates(c,map("collectorVersion","1.5.11","updateCapability","update-v1")).isEmpty(),"below minimum excluded");check(catalog.candidates(c,map("collectorVersion","1.5.12","updateCapability","")).isEmpty(),"missing root updater excluded");
            check(AgentUpdateRoutes.hash(catalog.asset(c.bundle(),"package")).equals(c.bundle().sha256()),"served bundle exactly matches signed manifest");
            byte[] original=Files.readAllBytes(c.bundle().directory().resolve("bundle.zip")),bad=original.clone();bad[bad.length/2]^=1;Files.write(c.bundle().directory().resolve("bundle.zip"),bad);
            try{catalog.load(DashboardVersion.VERSION);throw new AssertionError("tampered ZIP accepted");}catch(java.io.IOException expected){checks++;}Files.write(c.bundle().directory().resolve("bundle.zip"),original);
            Path index=root.resolve("dist/agent-update-routes/"+DashboardVersion.VERSION+"/routes.json");byte[] raw=Files.readAllBytes(index),changed=raw.clone();changed[changed.length-2]^=1;Files.write(index,changed);
            try{catalog.load(DashboardVersion.VERSION);throw new AssertionError("invalid signed route accepted");}catch(java.io.IOException expected){checks++;}Files.write(index,raw);
            try{catalog.load("../../outside");throw new AssertionError("path traversal accepted");}catch(java.io.IOException expected){checks++;}
            Path payload=bridge.directory().resolve("collector.jar"),moved=payload.resolveSibling("collector-original.jar");Files.move(payload,moved);Files.createSymbolicLink(payload,moved.getFileName());
            try{catalog.load(DashboardVersion.VERSION);throw new AssertionError("linked bridge accepted");}catch(java.io.IOException expected){checks++;}
            System.out.println("AgentUpdateRoutesTest PASS "+checks+" checks: signed/pinned full bundle, legacy profiles, minimum version, corruption, route signature, traversal and symlinks.");
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
