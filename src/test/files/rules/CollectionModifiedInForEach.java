package rules;

import java.util.List;

class Iter {
  void clean(List<String> names) {
    for (String s : names) {
      if (s.isEmpty()) {
        names.remove(s); // Noncompliant
      }
    }
  }

  void viaKeys(java.util.Map<String, String> m) {
    for (String k : m.keySet()) {
      m.put(k + "x", "v"); // Noncompliant
    }
  }

  void replaceValues(java.util.Map<String, String> m) {
    for (java.util.Map.Entry<String, String> e : m.entrySet()) {
      m.put(e.getKey(), e.getValue().trim());
    }
    for (java.util.Map.Entry<String, String> e : m.entrySet()) {
      String key = e.getKey();
      m.put(key, e.getValue().trim());
    }
  }

  void viaLambda(List<String> names) {
    names.forEach(n -> names.remove(n)); // Noncompliant
  }

  void firstOnly(List<String> names) {
    for (String s : names) {
      if (s.isEmpty()) {
        names.remove(s);
        break;
      }
    }
  }
}

class Registry {
  private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();

  void prune() {
    for (String k : counts.keySet()) {
      drop(k); // Noncompliant
    }
  }

  private void drop(String k) {
    counts.remove(k);
  }

  void evict(CacheMap<String, String> c) {
    for (String k : c.keySet()) {
      c.remove(k); // Noncompliant
    }
  }
}

class CacheMap<K, V> extends java.util.HashMap<K, V> {
}



class Holder2 {
  private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();

  java.util.Map<String, Integer> getCounts() {
    return counts;
  }

  void prune() {
    for (String k : getCounts().keySet()) {
      getCounts().remove(k); // Noncompliant
    }
  }
}
