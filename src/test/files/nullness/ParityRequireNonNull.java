package nullness; // parity

import java.util.Map;
import java.util.Objects;

class ParityRequireNonNull {
  String direct(Map<String, String> m) {
    return Objects.requireNonNull(m.get("k")); // Noncompliant
  }

  void local(Map<String, String> m) {
    String v = m.get("k");
    Objects.requireNonNull(v); // Noncompliant
  }
}
