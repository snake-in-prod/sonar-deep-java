package rules;

class Deref {
  int size(String s) {
    int n = s.length();
    if (s == null) { // Noncompliant
      return 0;
    }
    return n;
  }

  int ok(String s) {
    if (s == null) {
      return 0;
    }
    return s.length();
  }

  int reassigned(String s) {
    int n = s.length();
    s = null;
    if (s == null) {
      return n;
    }
    return 0;
  }
}
