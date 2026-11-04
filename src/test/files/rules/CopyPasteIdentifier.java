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

class Person {
  private String name;
  private String email;
  private String phone;
  private String fooType;

  String getName() {
    return name;
  }

  String getEmail() {
    return email;
  }

  String getPhone() {
    return phone;
  }

  String getFooType() {
    return fooType;
  }

  void setName(String v) {
    name = v;
  }

  void setEmail(String v) {
    email = v;
  }

  void setPhone(String v) {
    phone = v;
  }
}

class Merge {
  void merge(Person copy, Person src, Person other) {
    copy.setName(src.getName());
    copy.setEmail(other.getEmail()); // Noncompliant
    copy.setPhone(src.getPhone());
  }

  void groups(Person copy, Person a, Person b) {
    copy.setName(a.getName());
    copy.setEmail(b.getEmail());
    copy.setPhone(b.getPhone());
  }

  String types(Person p) {
    String fooType = p.getFooType();
    String nonFooType = p.getFooType(); // Noncompliant
    return fooType + nonFooType;
  }
}
