package rules;

import java.util.HashMap;
import java.util.Map;

class Cache {
  private final Map<String, String> cache = new HashMap<>();

  String get(String k) {
    if (!cache.containsKey(k)) { // Noncompliant
      synchronized (this) {
        cache.put(k, k.toUpperCase());
      }
    }
    return cache.get(k);
  }

  String doubleChecked(String k) {
    if (!cache.containsKey(k)) {
      synchronized (this) {
        if (!cache.containsKey(k)) {
          cache.put(k, k);
        }
      }
    }
    return cache.get(k);
  }
}
