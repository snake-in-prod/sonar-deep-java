package rules; // parity

class Kinds {
  String kind(String s) {
    if (s.matches("[0-9]+")) {
      return "number:" + s.trim();
    } else if (s.matches("[a-z]+")) { // Noncompliant
      return "number:" + s.trim();
    } else if (s.isEmpty()) {
      return "number:" + s.trim();
    }
    return "other";
  }

  String different(String s) {
    if (s.matches("[0-9]+")) {
      return "number:" + s.trim();
    } else if (s.matches("[a-z]+")) {
      return "word:" + s.trim();
    }
    return "other";
  }
}
