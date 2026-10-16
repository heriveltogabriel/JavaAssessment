package assessment;

import java.io.*;

/** Compatible systemd start extension; never rewrites the immutable bootstrap. */
final class AgentRecoveryService {
    static final String UNIT = "java-assessment-recovery.service";
    static final String NAME = "10-java-assessment-start.conf";
    static final String PREPARE =
        "set -eu; PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH; " +
        "test \"$(id -u)\" = 0; " +
        "for p in /opt /opt/java-assessment-update /opt/java-assessment-update/state; do " +
        "test -d \"$p\" && test ! -L \"$p\" && test \"$(stat -c %u \"$p\")\" = 0; " +
        "m=$(stat -c %a \"$p\"); test \"$((0$m & 022))\" = 0; done; " +
        "s=/opt/java-assessment-update/state; " +
        "if test -e \"$s/recovery.pid\" || test -L \"$s/recovery.pid\"; then " +
        "test -f \"$s/recovery.pid\" && test ! -L \"$s/recovery.pid\"; " +
        "test \"$(stat -c %u:%a:%h \"$s/recovery.pid\")\" = 0:600:1; " +
        "test \"$(stat -c %s \"$s/recovery.pid\")\" -le 32; " +
        "pid=$(cat \"$s/recovery.pid\"); case \"$pid\" in \"\"|*[!0-9]*) exit 1;; esac; " +
        "if test -d \"/proc/$pid\"; then test -r \"/proc/$pid/cmdline\"; " +
        "if tr \"\\000\" \" \" < \"/proc/$pid/cmdline\" | grep -F \"/opt/java-assessment-update/bootstrap/update-bootstrap.sh loop\" >/dev/null; then exit 1; fi; fi; fi; " +
        "if test -e \"$s/recovery.stop\" || test -L \"$s/recovery.stop\"; then " +
        "test -f \"$s/recovery.stop\" && test ! -L \"$s/recovery.stop\"; " +
        "test \"$(stat -c %u:%a:%h:%s \"$s/recovery.stop\")\" = 0:600:1:0; " +
        "rm -- \"$s/recovery.stop\"; fi";
    // systemd expands dollars/specifiers before /bin/sh receives the argument.
    static final String CONTENT = "# Java Assessment owned recovery startup v1\n[Service]\nExecStartPre=/bin/sh -ec '"
        + PREPARE.replace("%", "%%").replace("$", "$$") + "'\n";

    static class Host {
        String run(String[] args) throws Exception {
            AgentMigrationBootstrap.Production.Commands.Captured result = new AgentMigrationBootstrap.Production.Commands().read(args);
            AgentInstallLayout.require(result.status == 0, "RECOVERY_START_SETUP_FAILED"); return result.text.trim();
        }
        void trusted(File path) throws Exception {
            AgentInstallLayout.require(path.equals(path.getCanonicalFile()), "RECOVERY_START_SETUP_FAILED");
            for (File p = path; p != null; p = p.getParentFile()) {
                String value = run(new String[]{"/usr/bin/stat", "-c", "%u:%a:%h", p.getPath()});
                String[] fields = value.split(":");
                AgentInstallLayout.require(fields.length == 3 && fields[0].equals("0") && fields[1].matches("[0-7]{3,4}")
                    && (Integer.parseInt(fields[1], 8) & 022) == 0 && (p.isDirectory() || p.isFile() && fields[2].equals("1")), "RECOVERY_START_SETUP_FAILED");
            }
        }
    }
    static void checkDirectory(File dir, Host host) throws Exception {
        host.trusted(dir); File[] files = dir.listFiles();
        AgentInstallLayout.require(files != null && files.length <= 1, "RECOVERY_START_CUSTOMIZED");
        for (int i=0;i<files.length;i++) {
            File file=files[i]; host.trusted(file);
            AgentInstallLayout.require(NAME.equals(file.getName()) && file.isFile()
                && CONTENT.equals(new String(AgentInstallLayout.bytes(file, 8192), "UTF-8")), "RECOVERY_START_CUSTOMIZED");
        }
    }
    static void install(AgentInstallLayout layout, File systemd, Host host) throws Exception {
        if (!"systemd".equals(new String(AgentInstallLayout.bytes(new File(layout.home,"scheduler"),32),"UTF-8").trim())) return;
        File unit=new File(systemd,UNIT), original=new File(layout.home,"bootstrap/recovery.service"), dir=new File(systemd,UNIT+".d");
        host.trusted(systemd); host.trusted(unit); host.trusted(original);
        AgentInstallLayout.require(AgentBundleManifest.hash(unit).equals(AgentBundleManifest.hash(original)),"RECOVERY_START_CUSTOMIZED");
        String dropins=host.run(new String[]{"/bin/systemctl","show",UNIT,"-p","DropInPaths"});
        AgentInstallLayout.require(dropins.equals("DropInPaths=") || dropins.equals("DropInPaths="+new File(dir,NAME).getPath()),"RECOVERY_START_CUSTOMIZED");
        if (dir.exists()) checkDirectory(dir,host);
        else { AgentInstallLayout.require(dir.equals(dir.getCanonicalFile()) && dir.mkdir(),"RECOVERY_START_SETUP_FAILED"); AgentTool.ownerOnly(dir); AgentInstallLayout.require(dir.setExecutable(true,true),"RECOVERY_START_SETUP_FAILED"); }
        File target=new File(dir,NAME);
        if (!target.exists()) {
            // Exclusive creation: never follow a pre-existing temporary path.
            File temporary=File.createTempFile(".start-", ".tmp", dir); AgentTool.ownerOnly(temporary);
            FileOutputStream out=new FileOutputStream(temporary);
            try {out.write(CONTENT.getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
            AgentInstallLayout.require(temporary.renameTo(target),"RECOVERY_START_SETUP_FAILED");
        }
        checkDirectory(dir,host);
        // Also reload on retry: a previous process may have exited after rename.
        host.run(new String[]{"/bin/systemctl","daemon-reload"});
    }
}
