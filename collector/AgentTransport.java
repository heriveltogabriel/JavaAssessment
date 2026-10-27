package assessment;

import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
import org.bouncycastle.jsse.BCSSLParameters;
import org.bouncycastle.jsse.BCSSLSocket;

/** Bounded authenticated transport with per-connection PKIX and endpoint validation. */
final class AgentTransport {
    // These utilities run in their own process. Old JDK6 keep-alive pooling can race
    // closed server connections across sequential bootstrap requests.
    static { System.setProperty("http.keepAlive", "false"); }
    static final String VERSION = "2.1.28";
    static final int MAX_BODY = 2 * 1024 * 1024;
    final String server;
    private final SSLSocketFactory sockets;

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status) { super("Dashboard returned HTTP " + status); this.status = status; }
    }

    AgentTransport(Properties config) throws Exception {
        server = AssessmentCollector.required(config, "serverUrl").replaceAll("/+$", "");
        URL endpoint = new URL(server);
        if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getRef() != null
                || !(endpoint.getPath().equals("") || endpoint.getPath().equals("/")) || endpoint.getHost().length() == 0)
            throw new IllegalArgumentException("serverUrl must contain only scheme, host and port");
        boolean local = endpoint.getHost().equals("127.0.0.1") || endpoint.getHost().equals("localhost") || endpoint.getHost().equals("[::1]");
        if (!(endpoint.getProtocol().equals("https") || (local && endpoint.getProtocol().equals("http"))))
            throw new IllegalArgumentException("HTTP is allowed only on loopback; remote agents require HTTPS");
        if (endpoint.getProtocol().equals("https")) {
            String provider = config.getProperty("tlsProvider", "BCJSSE");
            if (!"BCJSSE".equals(provider)) throw new IllegalArgumentException("tlsProvider must be BCJSSE");
            sockets = tls(AssessmentCollector.required(config, "tlsCaFile"), endpoint.getHost());
        } else sockets = null;
    }

    static SSLSocketFactory tls(String caPath) throws Exception { return tls(caPath, null); }

    static synchronized SSLSocketFactory tls(String caPath, String peerHost) throws Exception {
        if (Security.getProvider("BC") == null) Security.addProvider((Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").newInstance());
        if (Security.getProvider("BCJSSE") == null) Security.addProvider((Provider) Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider").newInstance());
        File ca = new File(caPath);
        if (!ca.isAbsolute() || !ca.isFile() || ca.length() == 0 || ca.length() > 512 * 1024) throw new IOException("CA bundle is missing or exceeds 512 KiB");
        KeyStore roots = KeyStore.getInstance("JKS"); roots.load(null, null);
        InputStream in = new FileInputStream(ca); Collection certificates;
        try { certificates = CertificateFactory.getInstance("X.509", "BC").generateCertificates(in); } finally { in.close(); }
        if (certificates.isEmpty() || certificates.size() > 100) throw new IOException("CA bundle must contain 1 to 100 certificates");
        int count = 0;
        for (Object value : certificates) {
            X509Certificate cert = (X509Certificate) value;
            if (cert.getBasicConstraints() < 0) throw new IOException("Trust bundle must contain CA certificates only");
            roots.setCertificateEntry("ca-" + count++, cert);
        }
        TrustManagerFactory managers = TrustManagerFactory.getInstance("PKIX", "BCJSSE"); managers.init(roots);
        SSLContext context = SSLContext.getInstance("TLSv1.2", "BCJSSE"); context.init(null, managers.getTrustManagers(), new SecureRandom());
        return new VerifiedSockets(context.getSocketFactory(), peerHost);
    }

    /** Enforces identity during TLS, before HTTP credentials are written. */
    static final class VerifiedSockets extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String peerHost;
        VerifiedSockets(SSLSocketFactory delegate, String peerHost) { this.delegate = delegate; this.peerHost = peerHost; }
        private Socket configure(Socket socket, String host) throws IOException {
            if (!(socket instanceof BCSSLSocket)) { socket.close(); throw new SSLException("Verified BCJSSE socket unavailable"); }
            BCSSLSocket secure = (BCSSLSocket) socket;
            if (host == null || host.length() == 0) { socket.close(); throw new SSLException("TLS peer identity is required"); }
            // Java 6 HttpsURLConnection can create an unconnected socket without a hostname.
            // Carry the verified origin into that path instead of inferring a reverse-DNS name.
            secure.setHost(host);
            BCSSLParameters parameters = secure.getParameters();
            parameters.setProtocols(new String[] {"TLSv1.2"});
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            secure.setParameters(parameters);
            return socket;
        }
        public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        public Socket createSocket() throws IOException { return configure(delegate.createSocket(), peerHost); }
        public Socket createSocket(Socket s, String h, int p, boolean c) throws IOException { return configure(delegate.createSocket(s,h,p,c), h); }
        public Socket createSocket(String h, int p) throws IOException { return configure(delegate.createSocket(h,p), h); }
        public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return configure(delegate.createSocket(h,p,l,lp), h); }
        public Socket createSocket(InetAddress h, int p) throws IOException { return configure(delegate.createSocket(h,p), h.getHostAddress()); }
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return configure(delegate.createSocket(h,p,l,lp), h.getHostAddress()); }
    }

    Map request(String method, String path, String body, String token) throws Exception {
        if (!(path.equals("/health") || path.startsWith("/api/agent/"))) throw new IllegalArgumentException("Unexpected collector endpoint");
        if (token != null && (token.length() == 0 || token.length() > 1024 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0))
            throw new IllegalArgumentException("Invalid collector credential");
        HttpURLConnection conn = (HttpURLConnection) new URL(server + path).openConnection();
        if (conn instanceof HttpsURLConnection) ((HttpsURLConnection) conn).setSSLSocketFactory(sockets);
        conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(5000); conn.setReadTimeout(15000);
        conn.setRequestMethod(method);
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json"); conn.setRequestProperty("User-Agent", "legacy-assessment-collector/" + VERSION);
        conn.setRequestProperty("Connection", "close");
        try {
            if (body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                if (bytes.length > MAX_BODY) throw new IOException("Payload exceeds transport limit");
                conn.setDoOutput(true); conn.setFixedLengthStreamingMode(bytes.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream out = conn.getOutputStream(); try { out.write(bytes); } finally { out.close(); }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new ApiException(status);
            InputStream input = conn.getInputStream(); String content;
            try { content = AssessmentScan.readStream(input, 256 * 1024); } finally { input.close(); }
            Object parsed = Json.parse(content);
            if (!(parsed instanceof Map)) throw new IOException("Dashboard response is not an object");
            return (Map) parsed;
        } finally { conn.disconnect(); }
    }

    /** Raw update assets stay on the configured authenticated origin. No redirects. */
    void download(String path, File target, long limit, String token) throws Exception {
        boolean legacyAsset = path.matches("/api/agent/update/(manifest|signature|package)\\?hostId=[A-Za-z0-9%+_.-]+&requestId=[a-f0-9-]{36}");
        boolean bundleAsset = path.matches("/api/agent/update-v3/(manifest|signature|package)\\?hostId=[A-Za-z0-9%+_.-]+&operationId=[a-f0-9-]{36}");
        if (!(legacyAsset || bundleAsset) || limit < 1 || limit > (bundleAsset ? 64L : 32L) * 1024 * 1024)
            throw new IllegalArgumentException("Unexpected update asset");
        if (token == null || token.length() == 0 || token.length() > 1024 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid collector credential");
        HttpURLConnection conn = (HttpURLConnection) new URL(server + path).openConnection();
        if (conn instanceof HttpsURLConnection) ((HttpsURLConnection) conn).setSSLSocketFactory(sockets);
        conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(5000); conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/octet-stream"); conn.setRequestProperty("Connection", "close");
        boolean complete = false;
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new ApiException(status);
            if (conn.getContentLength() > limit) throw new IOException("Update asset exceeds limit");
            InputStream in = conn.getInputStream();
            try {
                FileOutputStream out = new FileOutputStream(target);
                try {
                    byte[] buffer = new byte[16384]; long total = 0, deadline = System.currentTimeMillis() + 120000L; int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > limit || System.currentTimeMillis() > deadline) throw new IOException("Update transfer limit");
                        out.write(buffer, 0, n);
                    }
                    out.getFD().sync(); complete = true;
                } finally { out.close(); }
            } finally { in.close(); }
        } finally { conn.disconnect(); if (!complete) target.delete(); }
    }

    static boolean isTlsFailure(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof SSLException || cursor instanceof CertificateException
                    || cursor.getClass().getName().startsWith("org.bouncycastle.tls.")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    static String safeError(Exception error) {
        if (error instanceof ApiException) return "HTTP_" + ((ApiException) error).status;
        if (isTlsFailure(error)) return "TLS_VALIDATION_OR_NEGOTIATION_FAILED";
        if (error instanceof SocketTimeoutException) return "CONNECTION_TIMED_OUT";
        if (error instanceof ConnectException || error instanceof UnknownHostException) return "DASHBOARD_UNREACHABLE";
        if (error instanceof IllegalArgumentException) return "INVALID_CONFIGURATION_OR_INPUT";
        if (error instanceof IOException) return "FILE_OR_TRANSPORT_ERROR";
        return "UNEXPECTED_" + error.getClass().getSimpleName();
    }
}
