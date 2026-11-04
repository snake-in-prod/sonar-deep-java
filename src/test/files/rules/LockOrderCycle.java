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

class Ledger {
  private Journal journal;

  synchronized void post() {
    journal.record();
  }

  synchronized void close() {
    System.out.println("closed");
  }
}

class Journal {
  private Ledger ledger;

  synchronized void record() {
    System.out.println("recorded");
  }

  synchronized void flush() {
    ledger.close(); // Noncompliant
  }
}

class Hub {
  private final java.util.Map<String, Node> nodes = new java.util.HashMap<>();

  synchronized void tick() {
    nodes.get("a").touch(); // Noncompliant
  }

  synchronized void ping() {
    System.out.println("ping");
  }
}

class Node {
  private Hub hub;

  synchronized void touch() {
    System.out.println("touch");
  }

  synchronized void report() {
    hub.ping();
  }
}
