package parity; // parity

class ConstantSecrets {
  private static final String EXPECTED = "s3cr3t";

  boolean check(String password) {
    return password.equals(EXPECTED); // Noncompliant
  }
}
