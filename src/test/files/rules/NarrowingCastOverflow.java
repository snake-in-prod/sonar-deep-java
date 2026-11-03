package rules;

class Casts {
  int seed() {
    return (int) System.currentTimeMillis(); // Noncompliant
  }

  long ok() {
    return System.currentTimeMillis();
  }
}
