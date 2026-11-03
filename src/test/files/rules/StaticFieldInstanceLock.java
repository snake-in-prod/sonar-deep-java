package rules;

class Totals {
  private static int total;

  synchronized void reset() {
    total = 0; // Noncompliant
  }

  static synchronized void ok() {
    total = 1;
  }
}
