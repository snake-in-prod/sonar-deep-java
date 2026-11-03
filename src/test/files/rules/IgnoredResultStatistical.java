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
