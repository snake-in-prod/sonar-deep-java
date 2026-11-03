package rules;

class Counter {
  private int count;

  synchronized void inc() {
    count++;
  }

  synchronized void dec() {
    count--;
  }

  synchronized int get() {
    return count;
  }

  void reset() {
    count = 0; // Noncompliant
  }
}
