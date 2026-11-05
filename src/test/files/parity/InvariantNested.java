package parity; // parity

class InvariantNested {
  boolean control(char c) {
    return c == ' ' || '\u0000' <= c && c < ' '; // Noncompliant
  }
}
