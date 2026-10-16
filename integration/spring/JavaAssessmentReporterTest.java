package example.assessment;

import java.io.File;
import java.lang.reflect.*;
import java.sql.*;
import javax.sql.DataSource;

/** Dependency-free local mocks; does not open any socket or require a running database. */
public final class JavaAssessmentReporterTest {
    static int connections, closes, assertions;
    static boolean fail;
    static void check(boolean value, String description) { assertions++; if (!value) throw new AssertionError(description); }
    static Object proxy(Class type, InvocationHandler handler) { return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler); }
    static DataSource source() {
        final DatabaseMetaData metadata = (DatabaseMetaData)proxy(DatabaseMetaData.class, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.equals("getDriverName")) { if (fail) throw new SQLException("SECRET_ERROR_MUST_NOT_BE_WRITTEN"); return "PostgreSQL JDBC Driver"; }
                if (name.equals("getDriverVersion")) return "42.7.4";
                if (name.equals("getDatabaseProductName")) return "PostgreSQL";
                if (name.equals("getDatabaseProductVersion")) return "16.5";
                throw new AssertionError("Unexpected JDBC operation: " + name);
            }
        });
        final Connection connection = (Connection)proxy(Connection.class, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getName().equals("getMetaData")) return metadata;
                if (method.getName().equals("close")) { closes++; return null; }
                throw new AssertionError("No SQL, credentials or remote URL should be read");
            }
        });
        return (DataSource)proxy(DataSource.class, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getName().equals("getConnection") && (args == null || args.length == 0)) { connections++; return connection; }
                throw new AssertionError("Unexpected DataSource operation");
            }
        });
    }
    public static void main(String[] args) throws Exception {
        JavaAssessmentReporter reporter = new JavaAssessmentReporter();
        check(!reporter.write(false, null, null, null, null) && connections == 0, "Disabled adapter performs no I/O or connection");
        String row = JavaAssessmentReporter.metadata(source());
        check(connections == 1 && closes == 1, "Borrowed connection closed on success");
        check(row.indexOf("16.5") >= 0 && row.indexOf("42.7.4") >= 0, "Database and driver independent");
        DataSource cachedSource = source();
        reporter.cachedMetadata("orders", cachedSource, 1000000L);
        reporter.cachedMetadata("orders", cachedSource, 1001000L);
        check(connections == 2 && closes == 2, "Metadata cached for same datasource without repeated connection");
        reporter.cachedMetadata("orders", cachedSource, 1300000L);
        check(connections == 3 && closes == 3, "Cache expires after five minutes");
        reporter.cachedMetadata("orders", source(), 1301000L);
        check(connections == 4 && closes == 4, "Datasource replacement refreshes metadata");
        fail = true;
        try { JavaAssessmentReporter.metadata(source()); throw new AssertionError("Expected failure"); } catch (SQLException expected) {}
        check(connections == 5 && closes == 5, "Borrowed connection closed on metadata error");
        check(JavaAssessmentReporter.field("driverVersion", "jdbc:postgresql://secret").equals(""), "URLs rejected");
        check(JavaAssessmentReporter.safe("Oracle Database 19c\nVersion 19.2", 256).equals("Oracle Database 19c Version 19.2"), "Known multiline metadata normalized");
        check(JavaAssessmentReporter.field("databaseVersion", new String(new char[257])).equals(""), "Metadata bounded");
        System.out.println("JAVA_ASSESSMENT_REPORTER_TESTS=PASS assertions=" + assertions + " no-network close-on-failure disabled-default allowlist");
    }
}
