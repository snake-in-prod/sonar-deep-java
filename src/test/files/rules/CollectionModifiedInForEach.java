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
