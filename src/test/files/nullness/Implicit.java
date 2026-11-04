package nullness;

import javax.annotation.Nullable;

class Box {
  @Nullable
  Long getTime() {
    return System.nanoTime() > 0 ? 1L : null;
  }

  @Nullable
  String getMode() {
    return "a";
  }

  @Nullable
  Boolean getFlag() {
    return Boolean.TRUE;
  }
}

class Implicit {
  void ack(long t) {
    System.out.println(t);
  }

  void args(Box b) {
    ack(b.getTime()); // Noncompliant
  }

  long assign(Box b) {
    long t = b.getTime(); // Noncompliant
    return t;
  }

  long arith(Box b) {
    return b.getTime() + 1; // Noncompliant
  }

  boolean compare(Box b, long x) {
    return b.getTime() == x; // Noncompliant
  }

  boolean nullCompare(Box b) {
    return b.getTime() == null;
  }

  String concat(Box b) {
    return "t=" + b.getTime();
  }

  int onSwitch(Box b) {
    switch (b.getMode()) { // Noncompliant
      case "a":
        return 1;
      default:
        return 0;
    }
  }

  int switchOnLocal(Box b) {
    String m = b.getMode();
    switch (m) { // Noncompliant
      case "a":
        return 1;
      default:
        return 0;
    }
  }

  boolean condition(Box b) {
    if (b.getFlag()) { // Noncompliant
      return true;
    }
    return false;
  }

  int later(Box b) {
    String m = b.getMode();
    System.out.println(m);
    return m.length(); // Noncompliant
  }

  int checkedLater(Box b) {
    String m = b.getMode();
    System.out.println(m);
    if (m == null) {
      return 0;
    }
    return m.length();
  }
}

class Holder {
  @Nullable
  private java.util.List<String> annotations;

  void each() {
    annotations.forEach(System.out::println); // Noncompliant
  }

  void inLambda() {
    Runnable r = () -> annotations.forEach(System.out::println); // Noncompliant
    r.run();
  }

  void guarded() {
    if (annotations != null) {
      annotations.forEach(System.out::println);
    }
  }
}
