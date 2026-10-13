package assessment;

import java.io.*;
import java.net.*;
import java.util.*;
import com.sun.net.httpserver.*;

/** Isolated registration/diagnostic fixtures. No production state is contacted. */
public final class AgentToolTest {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    public static void main(String[] args) throws Exception {
        final List<String> identities = new ArrayList<String>(); final int[] registrations = {0}, polls = {0}, eventCount = {0};
        final String credential = "fixture-agent-credential-123456789";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange x) throws IOException {
                int status = 200; Map result = AssessmentScan.map("status", "ok");
                try {
                    String path = x.getRequestURI().getPath();
                    Map requestBody = null;
                    if ("POST".equals(x.getRequestMethod())) {
                        InputStream input = x.getRequestBody();
                        try { requestBody = (Map) Json.parse(AssessmentScan.readStream(input, 32768)); }
                        finally { input.close(); }
                    }
                    if (path.equals("/api/agent/register")) {
                        check("Bearer fixture-enrollment-code".equals(x.getRequestHeaders().getFirst("Authorization")), "Enrollment bearer missing");
                        Map body = requestBody; identities.add((String) body.get("installationId")); registrations[0]++;
                        if (registrations[0] == 1) { status = 503; result = AssessmentScan.map("error", "Simulated missing response after identity accepted"); }
                        else result = AssessmentScan.map("registered", true, "hostId", "fixture-host-id", "agentToken", credential,
                            "serverUrl", "https://untrusted-response.invalid", "settings", AssessmentScan.map("includePaths", Arrays.asList("/not-existing-assessment-fixture")));
                    } else if (path.startsWith("/api/agent/")) {
                        check(("Bearer " + credential).equals(x.getRequestHeaders().getFirst("Authorization")), "Individual host credential missing");
                        if (path.equals("/api/agent/poll")) polls[0]++;
                        if (path.equals("/api/agent/install-events")) {
                            Map body = requestBody;
                            check("fixture-host-id".equals(body.get("hostId")), "Events must be scoped to saved host identity");
                            eventCount[0] += ((List) body.get("events")).size();
                        }
                        if (path.equals("/api/agent/settings")) result = AssessmentScan.map("settings", AssessmentScan.map("includePaths", Arrays.asList("/not-existing-assessment-fixture")));
                        else result = AssessmentScan.map("accepted", true);
                    }
                    byte[] bytes = Json.stringify(result).getBytes("UTF-8"); x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes);
                } catch (AssertionError failure) {
                    failure.printStackTrace();
                    byte[] bytes = "{\"fixtureAssertionFailed\":true}".getBytes("UTF-8");
                    x.sendResponseHeaders(500, bytes.length); x.getResponseBody().write(bytes);
                } finally { x.close(); }
            }
        });
        File root = CollectorTest.temp("enrollment"); new File(root, "conf").mkdir(); new File(root, "logs").mkdir();
        server.start();
        try {
            File bootstrapFile = new File(root, "conf/bootstrap.properties"), finalFile = new File(root, "conf/collector.properties");
            File identityFile = new File(root, "conf/installation-id");
            Properties bootstrap = new Properties();
            bootstrap.setProperty("serverUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            bootstrap.setProperty("enrollmentToken", "fixture-enrollment-code"); bootstrap.setProperty("hostname", "fixture-host");
            bootstrap.setProperty("installationIdPath", identityFile.getPath()); bootstrap.setProperty("spoolDirectory", new File(root, "spool").getPath());
            AgentTool.saveConfig(bootstrapFile, bootstrap);
            boolean failed = false;
            try { AgentTool.register(bootstrapFile, finalFile); } catch (AgentTransport.ApiException expected) { failed = true; }
            check(failed && identityFile.isFile() && !finalFile.exists(), "Failed bootstrap must persist retry identity without claiming registration success");
            String id = AssessmentScan.readFile(identityFile, 128).trim();
            AgentTool.register(bootstrapFile, finalFile);
            check(identities.size() == 2 && identities.get(0).equals(id) && identities.get(1).equals(id), "Enrollment retry must preserve identity");
            Properties config = AgentTool.load(finalFile);
            check(credential.equals(config.getProperty("agentToken")), "Credential must be persisted");
            check(bootstrap.getProperty("serverUrl").equals(config.getProperty("serverUrl")), "Server response must not redirect bootstrap credential storage");
            check(config.getProperty("enrollmentToken") == null, "Enrollment code must not persist in operational config");
            bootstrapFile.delete(); AgentTool.register(bootstrapFile, finalFile);
            check(registrations[0] == 2, "Reinstall must preserve operational identity without new registration");
            AgentTool.diagnose(finalFile);
            check(polls[0] == 0, "Diagnostic must not lease a collection job");
            check(new File(root, "logs/permission-check.json").isFile(), "Diagnostic must preserve permission report");
            int prior = eventCount[0];
            List oversized = new ArrayList(); for (int i = 0; i < 31; i++) oversized.add(AssessmentScan.map());
            failed = false; try { AgentTool.events(config, oversized, new AgentTransport(config)); } catch (IllegalArgumentException expected) { failed = true; }
            check(failed && eventCount[0] == prior, "Oversized events must not reach server");
            List secret = Arrays.asList(AssessmentScan.map("eventId", UUID.randomUUID().toString(), "time", AssessmentScan.now(), "stage", "java", "status", "OK", "message", credential));
            failed = false; try { AgentTool.events(config, secret, new AgentTransport(config)); } catch (IllegalArgumentException expected) { failed = true; }
            check(failed && eventCount[0] == prior, "Current credential must never be uploaded in diagnostic text");
            Properties bad = new Properties(); bad.setProperty("serverUrl", "http://192.0.2.1");
            failed = false; try { new AgentTransport(bad); } catch (IllegalArgumentException expected) { failed = true; }
            check(failed, "Remote HTTP must be refused");
            System.out.println("AGENT_TOOL_TESTS=PASS retry identity persistence reinstall permission report no-job diagnostic event bounds credential redaction");
        } finally { server.stop(0); CollectorTest.remove(root); }
    }
}
