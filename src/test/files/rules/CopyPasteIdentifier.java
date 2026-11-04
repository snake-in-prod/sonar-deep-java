package rules;

class Point {
  double x;
  double y;
}

class Pair {
  private int first;
  private int second;

  int getFirst() {
    return first;
  }

  int getSecond() {
    return second;
  }

  void setFirst(int v) {
    first = v;
  }

  void setSecond(int v) {
    second = v;
  }

  Pair first(int v) {
    first = v;
    return this;
  }

  Pair second(int v) {
    second = v;
    return this;
  }
}

class Copier {
  void copy(Pair x, Pair a) {
    x.setFirst(a.getFirst());
    x.setSecond(a.getFirst()); // Noncompliant
  }

  Pair build(Pair a) {
    return new Pair().first(a.getFirst()).second(a.getFirst()); // Noncompliant
  }

  void fine(Pair x, Pair a) {
    x.setFirst(a.getFirst());
    x.setSecond(a.getSecond());
  }
}

class Geometry {
  double dist(Point a, Point b) {
    double dx = a.x - b.x;
    double dy = a.y - b.x; // Noncompliant
    return Math.sqrt(dx * dx + dy * dy);
  }

  double ok(Point a, Point b) {
    double dx = a.x - b.x;
    double dy = a.y - b.y;
    return dx + dy;
  }
}
