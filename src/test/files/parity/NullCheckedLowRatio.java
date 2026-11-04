package parity; // parity

class Config {
  private String name;
  private Integer limit;

  String name() {
    return name;
  }

  Integer limit() {
    return limit;
  }
}

class Users {
  int a(Config c) {
    return c.name() == null ? 0 : c.name().length();
  }

  int b(Config c) {
    String n = c.name();
    return n != null ? n.length() : 0;
  }

  int d(Config c) {
    return c.name().length(); // Noncompliant
  }

  boolean e(Config c, String x) {
    return !c.name().equals(x); // Noncompliant
  }

  int f(Config c) {
    if (c.limit() == null) {
      return 0;
    }
    return c.limit();
  }

  int g(Config c) {
    return java.util.Optional.ofNullable(c.limit()).orElse(1);
  }

  boolean h(Config c, int distance) {
    return distance <= c.limit(); // Noncompliant
  }
}
