package assessment;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Map;

/** Commands carry only a narrowly scoped installer credential, never a human session. */
final class InstallCommand {
    static Map<String,Object> build(String serverUrl,String environmentId,String token,byte[] ca)throws Exception {
        URI uri=new URI(serverUrl);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getPort()>65535||uri.getPort()==0||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||
                !(uri.getPath().isEmpty()||uri.getPath().equals("/"))||serverUrl.chars().anyMatch(c->"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789:/.-[]".indexOf(c)<0)||
                !environmentId.matches("env-[A-Za-z0-9-]{1,90}")||!token.matches("eia1_[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid installation command origin or credential");
        if(ca.length==0||ca.length>65536)throw new IllegalArgumentException("Invalid installation CA");
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(ca));
        if(certificates.isEmpty()||certificates.size()>8)throw new IllegalArgumentException("Invalid installation CA chain");
        StringBuilder pem=new StringBuilder();
        for(var certificate:certificates) pem.append("-----BEGIN CERTIFICATE-----\n")
            .append(Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(certificate.getEncoded()))
            .append("\n-----END CERTIFICATE-----\n");
        String origin=serverUrl.endsWith("/")?serverUrl.substring(0,serverUrl.length()-1):serverUrl;
        String url=origin+"/api/install/package?environmentId="+environmentId;
        return AssessmentServer.map("curl",script("curl",url,token,pem.toString()),"wget",script("wget",url,token,pem.toString()));
    }
    private static String retry(String invocation){
        return "  JA_ATTEMPT=0\n  JA_DELAY=2\n  until "+invocation+"; do\n"+
            "    JA_ATTEMPT=$((JA_ATTEMPT + 1))\n    if test \"$JA_ATTEMPT\" -ge 6; then printf 'Download falhou. Confira o acesso e a conexao HTTPS.\\n' >&2; exit 1; fi\n"+
            "    sleep \"$JA_DELAY\"\n    JA_DELAY=$((JA_DELAY * 2))\n  done\n";
    }
    private static String script(String client,String url,String token,String pem){
        String pre="(\n  set +x\n  set -eu\n  umask 077\n  command -v "+client+" >/dev/null\n  command -v unzip >/dev/null\n  command -v sha256sum >/dev/null\n"+
            "  if test \"$(id -u)\" = 0 && test \"${SUDO_UID:-0}\" != 0; then\n"+
            "    printf 'Execute este download sem sudo, com seu usuario normal. Use sudo somente no comando de instalacao do passo 2.\\n' >&2\n"+
            "    exit 1\n  fi\n"+
            "  JA_STAGE=$(mktemp -d /tmp/java-assessment-install.XXXXXX)\n"+
            "  trap 'rm -f \"$JA_STAGE/download.conf\"' 0\n"+
            "  trap 'exit 1' 1 2 15\n"+
            "  cat > \"$JA_STAGE/dashboard-ca.pem\" <<'JAVA_ASSESSMENT_CA'\n"+pem+"JAVA_ASSESSMENT_CA\n";
        String download;
        if(client.equals("curl")) download="  cat > \"$JA_STAGE/download.conf\" <<JAVA_ASSESSMENT_DOWNLOAD\n"+
            "url = \""+url+"\"\nheader = \"Authorization: Bearer "+token+"\"\ncacert = \"$JA_STAGE/dashboard-ca.pem\"\n"+
            "output = \"$JA_STAGE/agent.zip\"\nfail\nsilent\nshow-error\nconnect-timeout = 15\nmax-time = 600\nmax-redirs = 0\nJAVA_ASSESSMENT_DOWNLOAD\n"+
            retry("curl -q --config \"$JA_STAGE/download.conf\"");
        else download="  cat > \"$JA_STAGE/download.conf\" <<JAVA_ASSESSMENT_DOWNLOAD\n"+
            "header = Authorization: Bearer "+token+"\nca_certificate = $JA_STAGE/dashboard-ca.pem\ncheck_certificate = on\n"+
            "output_document = $JA_STAGE/agent.zip\nmax_redirect = 0\ntimeout = 30\ntries = 2\nquiet = on\nJAVA_ASSESSMENT_DOWNLOAD\n"+
            retry("WGETRC=\"$JA_STAGE/download.conf\" wget '"+url+"'");
        return pre+download+
            "  rm -f \"$JA_STAGE/download.conf\"\n"+
            "  unzip -tq \"$JA_STAGE/agent.zip\" >/dev/null\n"+
            "  mkdir \"$JA_STAGE/package\"\n  unzip -q \"$JA_STAGE/agent.zip\" -d \"$JA_STAGE/package\"\n"+
            "  (cd \"$JA_STAGE/package\" && sha256sum -c SHA256SUMS)\n"+
            "  test -f \"$JA_STAGE/package/install.sh\"\n"+
            "  printf '\\nPacote verificado. Passo 2: o instalador configura e ativa os servicos automaticamente. Execute:\\n'\n"+
            "  printf \"sudo /bin/sh '%s/package/install.sh' --system-root\\n\" \"$JA_STAGE\"\n"+
            ")";
    }
}
