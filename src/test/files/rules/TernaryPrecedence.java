package rules;

class Ternary {
  int f(int x, int y, int z) {
    return x == y ? 1 : 0 + z; // Noncompliant
  }

  int g(int x, int y, int z) {
    return (x == y ? 1 : 0) + z;
  }
}
