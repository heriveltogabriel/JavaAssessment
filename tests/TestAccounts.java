package assessment;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import static assessment.AssessmentServer.map;

/** Fixture-only access through the public bootstrap and individual-login endpoints. */
public final class TestAccounts {
    public static final String EMAIL="fixture-admin@example.com";
    public static final String PASSWORD="fixture-personal-password-1234";
    private static final HttpClient CLIENT=HttpClient.newHttpClient();
    private TestAccounts() {}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(String value){return (Map<String,Object>)Json.parse(value);}
    public static HttpResponse<String> login(String base)throws Exception{return login(base,"fixture-password");}
    public static HttpResponse<String> login(String base,String legacyPassword)throws Exception{
        URI origin=URI.create(base);
        if(!"http".equals(origin.getScheme())||!"127.0.0.1".equals(origin.getHost()))
            throw new IllegalArgumentException("TestAccounts accepts isolated loopback fixtures only");
        var status=CLIENT.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/status")).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(status.statusCode()!=200)throw new AssertionError("Fixture auth status HTTP "+status.statusCode());
        if(Boolean.TRUE.equals(object(status.body()).get("bootstrapRequired"))){
            var bootstrap=post(base,"/api/auth/bootstrap",map("currentPassword",legacyPassword,"name","Fixture Administrator","email",EMAIL,"password",PASSWORD));
            if(bootstrap.statusCode()!=200&&bootstrap.statusCode()!=201)throw new AssertionError("Fixture bootstrap HTTP "+bootstrap.statusCode());
        }
        var response=post(base,"/api/login",credentials());
        if(response.statusCode()!=200)throw new AssertionError("Fixture individual login HTTP "+response.statusCode());
        return response;
    }
    public static Map<String,Object> credentials(){return map("email",EMAIL,"password",PASSWORD);}
    private static HttpResponse<String> post(String base,String path,Object payload)throws Exception{
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
