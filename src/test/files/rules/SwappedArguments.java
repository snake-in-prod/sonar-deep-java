package rules;

class Mover {
  void move(int from, int to) {
    System.out.println(from - to);
  }

  void use(int from, int to) {
    move(to, from); // Noncompliant
    move(from, to);
  }

  boolean between(int b, int c) {
    return ordered(b, c) || ordered(c, b);
  }

  boolean ordered(int b, int c) {
    return b < c;
  }
}
