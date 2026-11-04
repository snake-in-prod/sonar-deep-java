package parity; // parity

class Misc {
  int pick(int a, int b, java.util.List<String> list, int x, int y) {
    return (a + b - 1) < list.size() - 1 ? x : y; // Noncompliant
  }
}
