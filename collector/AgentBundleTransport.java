package assessment;

import java.io.*;
import java.net.URLEncoder;
import java.util.*;

/** V3 requests stay on the saved authenticated origin; no remote URLs or commands. */
final class AgentBundleTransport implements AgentBundleInstaller.Remote {
    final AgentTransport transport; final String hostId, token;
    AgentBundleTransport(Properties config) throws Exception {
        AgentMigrationBootstrap.checkExternalCa(config);
        transport = new AgentTransport(config); hostId = AssessmentCollector.required(config, "hostId"); token = AssessmentCollector.required(config, "agentToken");
    }
    public Map request(String action, Map body) throws Exception {
        AgentInstallLayout.require(Arrays.asList(new String[]{"claim", "report"}).contains(action), "INVALID_OPERATION");
        return transport.request("POST", "/api/agent/update-v3/" + action, Json.stringify(body), token);
    }
    Map action() throws Exception { return transport.request("GET", "/api/agent/update-v3/action?hostId=" + URLEncoder.encode(hostId, "UTF-8"), null, token); }
    public void download(String asset, File target, long limit, String operationId) throws Exception {
        AgentInstallLayout.require(Arrays.asList(new String[]{"manifest", "signature", "package"}).contains(asset) && AgentInstallLayout.uuid(operationId), "INVALID_OPERATION");
        transport.download("/api/agent/update-v3/" + asset + "?hostId=" + URLEncoder.encode(hostId, "UTF-8") + "&operationId=" + operationId, target, limit, token);
    }
}
