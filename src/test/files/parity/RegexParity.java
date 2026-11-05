package parity; // parity

class RegexParity {
  boolean nul(String s) {
    return s.matches("[\\000]*"); // Noncompliant
  }

  String[] fields(String s) {
    return s.split(" |,|\r|\""); // Noncompliant
  }

  String[] words(String s) {
    return s.split("and|or");
  }
}
