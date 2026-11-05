package nullness; // parity

import java.util.Objects;

class ParityRequireNonNull {
  static String find(String k) {
    if (k.isEmpty()) {
      return null;
    }
    return k;
  }

  String direct() {
    return Objects.requireNonNull(find("k")); // Noncompliant
  }

  void local() {
    String v = find("k");
    Objects.requireNonNull(v); // Noncompliant
  }
}
