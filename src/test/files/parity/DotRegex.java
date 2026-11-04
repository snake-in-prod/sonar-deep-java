package parity; // parity

class Chop {
  String first(String s) {
    return s.replaceFirst(".", ""); // Noncompliant
  }
}
