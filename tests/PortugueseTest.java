package assessment;
public final class PortugueseTest {
 public static void main(String[] args){
  check(Portuguese.error("Administrator access required").equals("Esta ação requer acesso de administrador."));
  check(!Portuguese.error("Email or password is incorrect").equals("Email or password is incorrect"));
  check(Portuguese.error("HASH_MISMATCH").equals("HASH_MISMATCH"));
  check(Portuguese.error("Mensagem já traduzida").equals("Mensagem já traduzida"));
  System.out.println("PORTUGUESE_ERRORS=PASS localized HTTP errors preserve technical codes and existing translations");
 }
 private static void check(boolean condition){if(!condition)throw new AssertionError();}
}
