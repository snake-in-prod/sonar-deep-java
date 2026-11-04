package rules;

class Tokens {
  private final String expectedToken = "t";

  boolean valid(String token) {
    return token.equals(expectedToken); // Noncompliant
  }

  boolean empty(String token) {
    return token.equals("");
  }

  boolean sameName(String tokenName, String other) {
    return tokenName.equals(other);
  }

  boolean bytes(byte[] signature, byte[] computed) {
    return java.util.Arrays.equals(signature, computed); // Noncompliant
  }
}
