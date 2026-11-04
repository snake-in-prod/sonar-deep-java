package rules;

class Mover {
  void move(int from, int to) {
    System.out.println(from - to);
  }

  void use(int from, int to) {
    move(to, from); // Noncompliant
    move(from, to);
  }
}
