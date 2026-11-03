package rules;

class Mixed {
  private final Object a = new Object();
  private final Object b = new Object();
  private int n;

  void inc() {
    synchronized (a) {
      n++;
    }
  }

  void dec() {
    synchronized (b) {
      n--; // Noncompliant
    }
  }
}
