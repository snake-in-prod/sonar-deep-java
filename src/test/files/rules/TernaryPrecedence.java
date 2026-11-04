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
}
