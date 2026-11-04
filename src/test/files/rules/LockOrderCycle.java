package rules;

class Outer {
  private final Object lock = new Object();

  synchronized void a() {
    synchronized (lock) {
      System.out.println("a");
    }
  }

  class Inner {
    void b() {
      synchronized (lock) {
        synchronized (Outer.this) { // Noncompliant
          System.out.println("b");
        }
      }
    }
  }
}

class Accounts {
  private final Object a = new Object();
  private final Object b = new Object();

  void ab() {
    synchronized (a) {
      synchronized (b) { // Noncompliant
        System.out.println("ab");
      }
    }
  }

  void ba() {
    synchronized (b) {
      synchronized (a) {
        System.out.println("ba");
      }
    }
  }
}
