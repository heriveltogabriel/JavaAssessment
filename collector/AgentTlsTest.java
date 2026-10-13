package assessment;

import java.net.*;
import java.io.*;
import java.util.*;
import javax.net.ssl.*;

/** Read-only TLS test: positive PKIX/IP-or-DNS identity, wrong name, wrong trust anchor. */
public final class AgentTlsTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: AgentTlsTest HTTPS_ORIGIN CA_PEM UNRELATED_CA_PEM");
        Properties config = new Properties(); config.setProperty("serverUrl", args[0]); config.setProperty("tlsCaFile", args[1]);
        AgentTransport transport = new AgentTransport(config); AgentTool.health(transport);
        System.out.println("TLS_VALID_CHAIN_AND_ENDPOINT=PASS");
        URL endpoint = new URL(args[0]); int port = endpoint.getPort() < 0 ? 443 : endpoint.getPort();
        Socket tcp = new Socket(); tcp.connect(new InetSocketAddress(endpoint.getHost(), port), 5000); tcp.setSoTimeout(15000);
        SSLSocket wrongName = (SSLSocket) AgentTransport.tls(args[1]).createSocket(tcp, "mismatch.invalid", port, true);
        boolean rejected = false;
        try { wrongName.startHandshake(); } catch (IOException expected) { if (!AgentTransport.isTlsFailure(expected)) throw expected; rejected = true; } finally { wrongName.close(); }
        if (!rejected) throw new AssertionError("Wrong TLS peer name accepted");
        System.out.println("TLS_WRONG_ENDPOINT_REJECTED=PASS");
        config.setProperty("tlsCaFile", args[2]); rejected = false;
        try { AgentTool.health(new AgentTransport(config)); } catch (IOException expected) { if (!AgentTransport.isTlsFailure(expected)) throw expected; rejected = true; }
        if (!rejected) throw new AssertionError("Untrusted certificate accepted");
        System.out.println("TLS_WRONG_TRUST_REJECTED=PASS");
    }
}
