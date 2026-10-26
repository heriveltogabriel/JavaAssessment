package assessment;
import java.io.*;
public class MakeFixture { public static void main(String[] a) throws Exception {
  JdbcEndpointTest.domain(new File("/u01/domain"), "db.example");
  JdbcDriverResolverTest.jar(new File("/u01/lib/ojdbc8.jar"), "oracle.jdbc.OracleDriver", "19.3.0.0.0", null, null);
  System.out.println("fixture ok"); } }
