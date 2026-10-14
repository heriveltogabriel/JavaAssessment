package assessment;

import java.io.*;
import java.util.*;

/** Recovery executes from the independent anchor/previous manager, never the candidate collector. */
final class AgentBundleRecovery {
    final AgentInstallLayout layout; final AgentBundleInstaller.Platform platform;
    AgentBundleRecovery(AgentInstallLayout layout, AgentBundleInstaller.Platform platform) { this.layout = layout; this.platform = platform; }
    void restore(Map journal) throws Exception {
        platform.local("pause", journal);
        Object previous = journal.get("previousPointer");
        if (previous instanceof Map) {
            layout.validatePointer((Map)previous); layout.promote((Map)previous);
            AgentInstallLayout.require(platform.probe(layout.code((Map)previous), AgentInstallLayout.text(journal.get("previousVersion")), false) == 0, "RECOVERY_REQUIRED");
        } else {
            File origin = AgentInstallLayout.child(layout.state, "origin"); Map inventory = AgentInstallLayout.read(AgentInstallLayout.child(origin, "origin.json"));
            AgentInstallLayout.require(inventory.get("files") instanceof Map, "RECOVERY_REQUIRED");
            Map files = (Map)inventory.get("files");
            for (Object item : files.keySet()) {
                String name = AgentInstallLayout.text(item); File backup = AgentInstallLayout.child(origin, "files/" + name);
                AgentInstallLayout.require(files.get(name).equals(AgentBundleManifest.hash(backup)), "RECOVERY_REQUIRED");
            }
            AgentBundleInstaller copier = new AgentBundleInstaller(layout, platform, null, AgentTool.load(AgentInstallLayout.child(layout.root, "conf/collector.properties"))); copier.journal = journal;
            for (Object item : files.keySet()) copier.copyIntentFile(AgentInstallLayout.child(origin, "files/" + AgentInstallLayout.text(item)), AgentInstallLayout.child(layout.root, AgentInstallLayout.text(item)), true);
            File current = AgentInstallLayout.child(layout.root, "current.json"), manager = AgentInstallLayout.child(layout.state, "current-manager.json");
            AgentInstallLayout.require(!current.exists() || current.delete(), "RECOVERY_REQUIRED"); AgentInstallLayout.require(!manager.exists() || manager.delete(), "RECOVERY_REQUIRED");
            AgentInstallLayout.require(AgentInstallLayout.text(inventory.get("version")).equals(AgentUpdateWorker.jarVersion(AgentInstallLayout.child(layout.root, "collector.jar"))), "RECOVERY_REQUIRED");
        }
        platform.local("restore-uninstall", journal); platform.local("restore-services", journal); platform.local("sync", journal); platform.local("start", journal);
    }
}
