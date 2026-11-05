package parity; // parity

class Bounds {
  private int minWidth;
  private int maxWidth;

  void reset() {
    minWidth = 0;
    minWidth = 100; // Noncompliant
  }

  void both() {
    minWidth = 0;
    maxWidth = 100;
  }

  int range(String s) {
    int start = s.indexOf('[');
    int end = s.indexOf(']');
    return span(start, start); // Noncompliant
  }

  int fine(String s) {
    int start = s.indexOf('[');
    int end = s.indexOf(']');
    return span(start, end);
  }

  static int span(int a, int b) {
    return b - a;
  }
}
