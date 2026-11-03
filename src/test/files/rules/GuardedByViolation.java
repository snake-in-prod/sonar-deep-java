package rules;

import javax.annotation.concurrent.GuardedBy;

class Guarded {
  @GuardedBy("this")
  private int value;

  synchronized void set(int v) {
    value = v;
  }

  int read() {
    return value; // Noncompliant
  }
}
