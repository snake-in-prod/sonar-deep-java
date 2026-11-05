package parity; // parity

class Chars {
  static char at(String s, int i) {
    return s.charAt(i);
  }

  char after(String s) {
    int i = s.indexOf(':');
    return at(s, i); // Noncompliant
  }

  char direct(String s) {
    return at(s, s.indexOf('=')); // Noncompliant
  }

  char checked(String s) {
    int i = s.indexOf(':');
    return i < 0 ? ' ' : at(s, i);
  }
}
