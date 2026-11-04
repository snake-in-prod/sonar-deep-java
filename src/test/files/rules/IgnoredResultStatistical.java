package rules;

class Store {
  boolean save(String s) {
    return !s.isEmpty();
  }
}

class Users {
  private final Store store = new Store();

  boolean a() {
    return store.save("a");
  }

  boolean b() {
    boolean ok = store.save("b");
    return ok;
  }

  void c() {
    if (!store.save("c")) {
      throw new IllegalStateException();
    }
  }

  int d() {
    return store.save("d") ? 1 : 0;
  }

  void e() {
    store.save("e"); // Noncompliant
  }
}

class Ports {
  int a(String s) {
    return Integer.parseInt(s);
  }

  int b(String s) {
    return Integer.parseInt(s) + 1;
  }

  int c(String s) {
    int p = Integer.parseInt(s);
    return p * 2;
  }

  long d(String s) {
    return Integer.parseInt(s);
  }

  void validate(String s) {
    Integer.parseInt(s); // Noncompliant {{The result of "Integer.parseInt()" is used at 4 of 5 call sites; check it here too.}}
  }
}
