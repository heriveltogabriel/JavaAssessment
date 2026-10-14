package assessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Versioned Argon2id hashes. Parsing is deliberately bounded before allocating memory. */
public final class PasswordHash {
    private static final int MEMORY=19456, ITERATIONS=2, PARALLELISM=1;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final java.util.concurrent.Semaphore DERIVATIONS=new java.util.concurrent.Semaphore(2,true);
    private PasswordHash() {}
    public static void validate(String password) {
        int length=password.codePointCount(0,password.length());
        if(length<8||length>128||password.indexOf('\0')>=0)throw new UserAuth.Failure(400,"Use a password with 8 to 128 characters");
    }
    public static String hash(String password) {
        validate(password);byte[] salt=new byte[16];RANDOM.nextBytes(salt);
        byte[] result=derive(password,salt);
        try{return "$argon2id$v=19$m="+MEMORY+",t="+ITERATIONS+",p="+PARALLELISM+"$"+encode(salt)+"$"+encode(result);}
        finally{Arrays.fill(result,(byte)0);}
    }
    public static boolean verify(String password,String encoded) {
        if(password==null||password.length()>512||encoded==null||encoded.length()>200)return false;
        try {
            String[] fields=encoded.split("\\$",-1);
            if(fields.length!=6||!fields[0].isEmpty()||!fields[1].equals("argon2id")||!fields[2].equals("v=19")||!fields[3].equals("m="+MEMORY+",t="+ITERATIONS+",p="+PARALLELISM))return false;
            byte[] salt=Base64.getDecoder().decode(fields[4]),expected=Base64.getDecoder().decode(fields[5]);
            if(salt.length!=16||expected.length!=32)return false;
            byte[] actual=derive(password,salt);try{return MessageDigest.isEqual(expected,actual);}finally{Arrays.fill(actual,(byte)0);}
        }catch(IllegalArgumentException e){return false;}
    }
    private static byte[] derive(String password,byte[] salt) {
        DERIVATIONS.acquireUninterruptibly();
        try {return calculate(password,salt);}finally{DERIVATIONS.release();}
    }
    private static byte[] calculate(String password,byte[] salt) {
        Argon2Parameters parameters=new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(MEMORY).withIterations(ITERATIONS).withParallelism(PARALLELISM).withSalt(salt).build();
        Argon2BytesGenerator generator=new Argon2BytesGenerator();generator.init(parameters);byte[] input=password.getBytes(StandardCharsets.UTF_8),result=new byte[32];
        try{generator.generateBytes(input,result);return result;}finally{Arrays.fill(input,(byte)0);parameters.clear();}
    }
    private static String encode(byte[] bytes){return Base64.getEncoder().withoutPadding().encodeToString(bytes);}
}
