package rules; // parity

class Chars {
  boolean printable(String s, int i) {
    char c = s.charAt(i);
    return '\u0000' <= c && c <= '~'; // Noncompliant
  }

  boolean any(String s) {
    return s.charAt(0) >= 0; // Noncompliant
  }

  boolean top(char c) {
    return c <= '￿'; // Noncompliant
  }

  boolean real(char c) {
    return c >= 'a' && c <= 'z';
  }

  int literal(int x) {
    if (false) { // Noncompliant
      return 1;
    }
    return x;
  }
}
