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

  @Override
  public boolean equals(Object o) {
    return o instanceof Tokens t && expectedToken.equals(t.expectedToken);
  }

  @Override
  public int hashCode() {
    return expectedToken.hashCode();
  }

  boolean bytes(byte[] signature, byte[] computed) {
    return java.util.Arrays.equals(signature, computed); // Noncompliant
  }
}
