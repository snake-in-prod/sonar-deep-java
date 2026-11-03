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

  void firstOnly(List<String> names) {
    for (String s : names) {
      if (s.isEmpty()) {
        names.remove(s);
        break;
      }
    }
  }
}
