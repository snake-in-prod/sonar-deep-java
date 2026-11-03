package rules;

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
