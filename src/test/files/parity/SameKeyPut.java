package parity; // parity

import java.util.HashMap;
import java.util.Map;

class Refresh {
  void bump(HashMap<String, Integer> counts) {
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      counts.put(e.getKey(), e.getValue() + 1); // Noncompliant
    }
  }
}
