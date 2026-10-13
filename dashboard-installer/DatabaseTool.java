import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;

/** Local maintenance only: a consistent SQLite copy, or private aggregate checks. */
class DatabaseTool {
    static Connection open(Path file) throws Exception {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file))
            throw new IllegalArgumentException("Database must be an existing regular file");
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toUri() + "?mode=ro");
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    static void check(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next())
                throw new IllegalStateException("SQLite quick_check failed");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM metadata WHERE key='migration.v1'")) {
            if (!result.next() || result.getInt(1) != 1)
                throw new IllegalStateException("Initial inventory migration is not complete");
        }
        System.out.println("QUICK_CHECK_OK; MIGRATION_V1_COMPLETE");
        for (String table : new String[]{"hosts", "identities", "environments", "inventory", "jobs"}) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                result.next();
                System.out.println(table + "=" + result.getLong(1));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !("check".equals(args[0]) && args.length == 2 ||
                                "backup".equals(args[0]) && args.length == 3))
            throw new IllegalArgumentException("Usage: database.sh check DATABASE | backup DATABASE NEW_BACKUP");
        Path source = Path.of(args[1]).toAbsolutePath().normalize();
        try (Connection connection = open(source)) {
            if ("check".equals(args[0])) {
                check(connection);
                return;
            }
            Path destination = Path.of(args[2]).toAbsolutePath().normalize();
            // CREATE_NEW refuses overwrites; SQLite accepts a zero-byte VACUUM INTO target.
            Files.createFile(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            boolean completed = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
                    statement.setString(1, destination.toString());
                    statement.execute();
                }
                try (Connection backup = open(destination)) { check(backup); }
                completed = true;
                System.out.println("BACKUP_OK " + destination);
            } finally {
                if (!completed) Files.deleteIfExists(destination);
            }
        }
    }
}
