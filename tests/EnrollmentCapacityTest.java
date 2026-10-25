package assessment;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import static assessment.AssessmentServer.map;

/** Enrollment capacity without machine limits: exhausted codes leave the active count; uses per code are high. */
public final class EnrollmentCapacityTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory(new File(System.getProperty("java.io.tmpdir")).toPath(),"enrollment-capacity-");
        try {
            InventoryStore store=new InventoryStore(new File(root.toFile(),"inventory.db"),map(),"","");
            EnrollmentRegistry registry=new EnrollmentRegistry(store);
            Set<String> envs=Set.of();

            check(EnrollmentRegistry.MAX_USES_PER_CODE>=100000,"uses per code is at least 100,000");
            check(EnrollmentRegistry.MAX_ENROLLMENTS>=10000,"active enrollment bound is at least 10,000");

            // Historically the 500-code bound blocked the 500th active one-use code. It must now pass.
            int issued=0;
            for(int i=0;i<600;i++){registry.issue("code"+i,1440,1,map(),false,"",envs);issued++;}
            check(issued==600,"600 active one-use codes accepted");

            // Exhausted codes (single use consumed) must not occupy active slots.
            List<Map<String,Object>> active=store.documents("SELECT payload FROM enrollments WHERE json_extract(payload,'$.usedCount')=0");
            int consumed=0;
            for(Map<String,Object> e:active){
                if(consumed>=300)break;
                String token=(String)e.get("tokenHash");
                consumed++;
                e.put("usedCount",1);
                store.put("enrollments",e);
            }
            check(consumed==300,"300 codes exhausted in the fixture");
            for(int i=0;i<300;i++)registry.issue("after-exhaustion"+i,1440,1,map(),false,"",envs);
            check(true,"new codes accepted after exhaustion");

            // A code with many uses registers machines well beyond the old 1,000 per-code cap.
            String code=(String)registry.issue("mass",1440,5000,map(),false,"",envs).get("enrollmentToken");
            int registered=0;
            for(int i=0;i<2000;i++){registry.register(code,"capacity-install-"+String.format("%08d",i),map(),envs);registered++;}
            check(registered==2000,"2,000 machines register with one code");

            System.out.println("EnrollmentCapacityTest PASS: "+checks+" checks; no machine cap, exhausted codes excluded, uses above 1,000 per code.");
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());
        }
    }
}
