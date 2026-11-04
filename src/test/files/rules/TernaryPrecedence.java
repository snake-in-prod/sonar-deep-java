package rules;

class Ternary {
  int f(int x, int y, int z) {
    return x == y ? 1 : 0 + z; // Noncompliant
  }

  int h(int x, int y) {
    return x + y == 0 ? 1 : 0; // Noncompliant
  }

  int g(int x, int y, int z) {
    return (x == y ? 1 : 0) + z;
  }

  int k(int a, int b, int c, int x, int y) {
    return a + b - 1 < c ? x : y; // Noncompliant
  }

  int m(int x, int a, int b) {
    return x % 2 == 0 ? a + 1 : b; // Noncompliant
  }

  int plain(int i, int n, int a, int b) {
    return i + 1 < n ? a : b;
  }

  int sized(int a, int b, java.util.List<String> c, int x, int y) {
    return a + b - 1 < c.size() - 1 ? x : y; // Noncompliant
  }
}
