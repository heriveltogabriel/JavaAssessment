package assessment;

/** Compiled as Java 6 bytecode; selection never trusts java -version alone. */
public final class JavaProbe {
    public static void main(String[] args) throws Exception {
        String version = System.getProperty("java.specification.version", "0");
        int major = Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
        String implementation = (System.getProperty("java.vm.name", "") + " " + System.getProperty("java.vendor", "")).toLowerCase(java.util.Locale.ENGLISH);
        if (major < 6 || implementation.indexOf("gcj") >= 0 || implementation.indexOf("free software foundation") >= 0) {
            throw new IllegalStateException("Collector requires a compatible Java 6 or newer runtime");
        }
        Class.forName("java.lang.management.ManagementFactory");
        Class.forName("java.nio.channels.FileLock");
        java.io.File.class.getMethod("getTotalSpace");
    }
}
