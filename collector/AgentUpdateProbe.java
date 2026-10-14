package assessment;

import java.io.*;
import java.util.*;

/** The signed candidate proves its code/version on this JVM without scanning applications. */
public final class AgentUpdateProbe {
    public static void main(String[] args) {
        try {
            if (args.length < 2 || !("probe".equals(args[0]) || "heartbeat".equals(args[0]))
                    || !AgentTransport.VERSION.equals(args[1])) throw new IllegalArgumentException("Invalid probe");
            JavaProbe.main(new String[0]);
            String[] classes = {"assessment.AssessmentCollector", "assessment.AssessmentScan", "assessment.AgentTransport",
                "assessment.AgentUpdateWorker", "org.bouncycastle.jce.provider.BouncyCastleProvider",
                "org.bouncycastle.jsse.provider.BouncyCastleJsseProvider", "assessment.AgentBundleManifest",
                "assessment.AgentMigrationBootstrap", "assessment.AgentBundleInstaller", "assessment.AgentBundleRecovery",
                "assessment.AgentOwnedCleanup", "assessment.AgentV3UninstallInventory"};
            for (int i = 0; i < classes.length; i++) Class.forName(classes[i]);
            AgentMigrationBootstrap.probeBridge();
            if ("probe".equals(args[0])) {
                if (args.length != 2) throw new IllegalArgumentException("Invalid probe arguments");
                System.out.println("AGENT_UPDATE_PROBE=" + AgentTransport.VERSION); return;
            }
            if (args.length != 3) throw new IllegalArgumentException("Invalid heartbeat arguments");
            Properties config = AgentTool.load(new File(args[2]));
            try { AgentTool.heartbeat(config, new AgentTransport(config)); }
            catch (Exception e) {
                System.err.println("AGENT_UPDATE_HEARTBEAT=" + AgentTransport.safeError(e));
                System.exit(e instanceof AgentTransport.ApiException && (((AgentTransport.ApiException)e).status == 401
                    || ((AgentTransport.ApiException)e).status == 403) ? 11 : 10); return;
            }
            System.out.println("AGENT_UPDATE_HEARTBEAT=" + AgentTransport.VERSION);
        } catch (Throwable e) { System.err.println("AGENT_UPDATE_PROBE=FAILED"); System.exit(20); }
    }
}
