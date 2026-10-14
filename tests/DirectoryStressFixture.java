package assessment;

import java.io.File;
import java.util.Properties;

/** Disposable directory supplied by collector-directory-stress.py. */
public final class DirectoryStressFixture {
    public static void main(String[] args) throws Exception {
        AssessmentScan scan = new AssessmentScan(new Properties(), "fixture", "fixture",
            AssessmentScan.map("maxFiles", Integer.valueOf(args[1]), "pauseMillis", 0), null);
        scan.excluded.clear(); scan.walk(new File(args[0]).getCanonicalFile(), 0);
        System.out.println(Json.stringify(AssessmentScan.map("filesVisited", scan.filesVisited,
            "partial", scan.partial, "elapsedMillis", System.currentTimeMillis() - scan.start,
            "peakHeapBytes", scan.peakHeap, "maxHeapBytes", Runtime.getRuntime().maxMemory(), "warnings", scan.warnings)));
    }
}
