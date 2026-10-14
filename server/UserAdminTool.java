package assessment;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Protected, offline first-admin provisioning and recovery. Never prints a credential. */
public final class UserAdminTool {
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();
        for(int i=0;i<args.length;i+=2){if(i+1==args.length||!Set.of("--root","--mode","--name","--email","--password-file").contains(args[i])||options.put(args[i],args[i+1])!=null)throw new IllegalArgumentException("Expected --root, --mode provision|recover, --name, --email and --password-file");}
        for(String required:List.of("--root","--mode","--email","--password-file"))if(!options.containsKey(required))throw new IllegalArgumentException("Missing "+required);
        String mode=options.get("--mode");if(!Set.of("provision","recover").contains(mode))throw new IllegalArgumentException("Mode must be provision or recover");
        Path root=Path.of(options.get("--root")),passwordFile=Path.of(options.get("--password-file"));
        if(!root.isAbsolute()||!passwordFile.isAbsolute())throw new IllegalArgumentException("Use absolute paths");
        trusted(root,true,false);trusted(root.resolve("conf"),true,false);trusted(root.resolve("conf/server.properties"),false,false);trusted(passwordFile,false,true);
        if(Files.exists(root.resolve("data"),LinkOption.NOFOLLOW_LINKS))trusted(root.resolve("data"),true,false);
        long size=Files.size(passwordFile);if(size>1024)throw new IllegalArgumentException("Password file is too large");
        String password=Files.readString(passwordFile);if(password.endsWith("\r\n"))password=password.substring(0,password.length()-2);else if(password.endsWith("\n"))password=password.substring(0,password.length()-1);
        PasswordHash.validate(password);
        try(AssessmentServer server=new AssessmentServer(root.toFile(),true)) {
            UserAuth.User user=mode.equals("provision")?server.auth.provision(options.get("--name"),options.get("--email"),password):server.auth.recover(options.get("--email"),password);
            System.out.println(Json.stringify(AssessmentServer.map("mode",mode,"user",user.dto(),"expiresAt",java.time.Instant.ofEpochMilli(user.temporaryExpires()).toString())));
        }
    }
    private static void trusted(Path path,boolean directory,boolean passwordFile)throws Exception {
        if(Files.isSymbolicLink(path)||!(directory?Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)))throw new IllegalArgumentException("A protected regular "+(directory?"directory":"file")+" is required");
        PosixFileAttributes attributes=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);Set<PosixFilePermission> mode=attributes.permissions();
        if(mode.contains(PosixFilePermission.GROUP_WRITE)||mode.contains(PosixFilePermission.OTHERS_WRITE)||!directory&&(mode.contains(PosixFilePermission.OTHERS_READ)||mode.contains(PosixFilePermission.GROUP_EXECUTE)||mode.contains(PosixFilePermission.OTHERS_EXECUTE)||passwordFile&&(mode.contains(PosixFilePermission.GROUP_READ)||mode.contains(PosixFilePermission.OWNER_EXECUTE))))throw new IllegalArgumentException("Use a private password/config file and non-writable installation directories");
        if(passwordFile&&!mode.equals(PosixFilePermissions.fromString("rw-------"))&&!mode.equals(PosixFilePermissions.fromString("r--------")))throw new IllegalArgumentException("Password file must use mode 0600 or 0400");
        String current=System.getProperty("user.name"),owner=attributes.owner().getName();
        if(passwordFile&&!passwordOwnerAllowed(current,owner)||!passwordFile&&!current.equals("root")&&!owner.equals(current)&&!owner.equals("root"))throw new IllegalArgumentException("Account maintenance requires a password file owned by the current user or root");
    }
    static boolean passwordOwnerAllowed(String current,String owner){return owner.equals(current)||owner.equals("root");}
}
