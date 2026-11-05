package parity; // parity

import java.util.Map;

class Sessions {
  private Map<String, Long> expiry;

  void purge(long now) {
    expiry.keySet().forEach(k -> {
      if (expiry.get(k) < now) {
        expiry.remove(k); // Noncompliant
      }
    });
  }

  void safe(long now) {
    expiry.keySet().removeIf(k -> expiry.get(k) < now);
  }
}
