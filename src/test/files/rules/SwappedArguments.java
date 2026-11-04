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

class Relation {
  Relation(String source, String target) {
    System.out.println(source + target);
  }
}

class Graph {
  Relation link(String source, String target) {
    return new Relation(target, source); // Noncompliant
  }

  Relation same(String source, String target) {
    return new Relation(source, target);
  }
}

@lombok.AllArgsConstructor
class Range {
  private int low;
  private int high;
}

class Ranges {
  Range of(int low, int high) {
    return new Range(high, low); // Noncompliant
  }
}
