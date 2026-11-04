package parity; // parity

import java.util.HashMap;
import java.util.Map;

class Dao<K, V> {
  private final Map<K, V> cache = new HashMap<>();

  V byName(String name) {
    return cache.get(name); // Noncompliant
  }
}
